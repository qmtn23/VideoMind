package com.qmtn.videomind.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.qmtn.videomind.client.EmbeddingClient;
import com.qmtn.videomind.client.QwenRerankClient;
import com.qmtn.videomind.entity.EsDocument;
import com.qmtn.videomind.entity.SearchResult;
import com.qmtn.videomind.exception.CustomException;
import com.qmtn.videomind.model.FileUpload;
import com.qmtn.videomind.model.User;
import com.qmtn.videomind.repository.FileUploadRepository;
import com.qmtn.videomind.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 混合检索服务，四阶段 RAG 检索增强：
 * <pre>
 *   1) 查询改写（QueryRewriteService 同义改写）
 *   2) 双路独立检索：HNSW KNN（向量） + BM25（关键词），各自带权限过滤
 *   3) RRF 融合：score = Σ 1 / (k + rank_i)，去重后取 topK*3
 *   4) qwen3-rerank Cross-Encoder 重排，取最终 topK
 * </pre>
 *
 * <p>各阶段失败均有降级路径，保证不阻塞主流程。</p>
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);
    private static final String INDEX = "knowledge_base";

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private QueryRewriteService queryRewriteService;

    @Autowired
    private QwenRerankClient qwenRerankClient;

    @Value("${search.hybrid.recallMultiplier:10}")
    private int recallMultiplier;

    @Value("${search.hybrid.rrfTopMultiplier:3}")
    private int rrfTopMultiplier;

    @Value("${search.hybrid.rrfK:60}")
    private int rrfK;

    /**
     * 带权限的混合检索（主入口）。
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        long t0 = System.currentTimeMillis();
        logger.info("[Hybrid] 开始混合检索，原始 query=\"{}\", userId={}, topK={}", query, userId, topK);

        try {
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            String userDbId = getUserDbId(userId);

            // ===== 阶段 1：查询改写 =====
            String rewrittenQuery = queryRewriteService.rewrite(query);

            // ===== 阶段 2：双路独立检索（并行） =====
            int recallSize = Math.max(topK * recallMultiplier, topK);
            List<Float> queryVector = embedToVectorList(rewrittenQuery);

            CompletableFuture<List<DocWithScore>> vecFuture = CompletableFuture.supplyAsync(() ->
                    queryVector == null ? List.<DocWithScore>of()
                            : safeKnnSearch(queryVector, userDbId, userEffectiveTags, recallSize));
            CompletableFuture<List<DocWithScore>> bmFuture = CompletableFuture.supplyAsync(() ->
                    safeBmSearch(rewrittenQuery, userDbId, userEffectiveTags, recallSize));

            List<DocWithScore> vecHits = vecFuture.join();
            List<DocWithScore> bmHits = bmFuture.join();
            logger.info("[Hybrid] 双路召回：vec={} 个, bm={} 个", vecHits.size(), bmHits.size());

            if (vecHits.isEmpty() && bmHits.isEmpty()) {
                logger.warn("[Hybrid] 两路均无结果，回退纯文本（含权限）兜底");
                return textOnlySearchWithPermission(rewrittenQuery, userDbId, userEffectiveTags, topK);
            }

            // ===== 阶段 3：RRF 融合 =====
            int rrfTopN = Math.max(topK * rrfTopMultiplier, topK);
            List<CandidateDoc> fused = mergeWithRRF(vecHits, bmHits, rrfTopN, rrfK);
            logger.info("[Hybrid] RRF 融合后保留 {} 个候选", fused.size());

            // ===== 阶段 4：qwen3-rerank 重排 =====
            List<CandidateDoc> finalDocs = applyRerank(rewrittenQuery, fused, topK);

            List<SearchResult> results = finalDocs.stream()
                    .map(CandidateDoc::toSearchResult)
                    .collect(Collectors.toList());
            attachFileNames(results);

            logger.info("[Hybrid] 完成，最终返回 {} 个 chunk，总耗时 {}ms",
                    results.size(), System.currentTimeMillis() - t0);
            return results;
        } catch (Exception e) {
            logger.error("[Hybrid] 混合检索失败，降级走纯文本", e);
            try {
                return textOnlySearchWithPermission(query,
                        getUserDbId(userId), getUserEffectiveOrgTags(userId), topK);
            } catch (Exception fallback) {
                logger.error("[Hybrid] 纯文本兜底也失败", fallback);
                return Collections.emptyList();
            }
        }
    }

    // ============================== 双路检索 ==============================

    /**
     * 第 1 路：HNSW KNN 向量检索。
     */
    private List<DocWithScore> safeKnnSearch(List<Float> queryVector, String userDbId,
                                              List<String> userEffectiveTags, int recallSize) {
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> s
                            .index(INDEX)
                            .knn(kn -> kn
                                    .field("vector")
                                    .queryVector(queryVector)
                                    .k(recallSize)
                                    .numCandidates(recallSize))
                            .query(q -> q.bool(b -> b.filter(buildPermissionFilter(userDbId, userEffectiveTags))))
                            .size(recallSize),
                    EsDocument.class);
            return extractDocsWithScore(response);
        } catch (Exception e) {
            logger.warn("[Hybrid] 向量路检索失败：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 第 2 路：BM25 关键词检索。
     */
    private List<DocWithScore> safeBmSearch(String query, String userDbId,
                                             List<String> userEffectiveTags, int recallSize) {
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> s
                            .index(INDEX)
                            .query(q -> q.bool(b -> b
                                    .must(m -> m.match(ma -> ma
                                            .field("textContent")
                                            .query(query)
                                            .operator(Operator.Or)))
                                    .filter(buildPermissionFilter(userDbId, userEffectiveTags))))
                            .size(recallSize),
                    EsDocument.class);
            return extractDocsWithScore(response);
        } catch (Exception e) {
            logger.warn("[Hybrid] BM25 路检索失败：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 抽取命中文档并保留 _score 用于诊断（RRF 本身只用 rank）。
     */
    private List<DocWithScore> extractDocsWithScore(SearchResponse<EsDocument> resp) {
        List<DocWithScore> list = new ArrayList<>(resp.hits().hits().size());
        for (Hit<EsDocument> hit : resp.hits().hits()) {
            EsDocument doc = hit.source();
            if (doc == null) continue;
            list.add(new DocWithScore(doc, hit.score() == null ? 0d : hit.score()));
        }
        return list;
    }

    /**
     * 构建权限过滤 should 子句，复用于两路检索。
     */
    private Query buildPermissionFilter(String userDbId, List<String> userEffectiveTags) {
        BoolQuery.Builder bf = new BoolQuery.Builder();
        bf.should(s1 -> s1.term(t -> t.field("userId").value(userDbId)));
        bf.should(s2 -> s2.term(t -> t.field("public").value(true)));
        if (userEffectiveTags != null && !userEffectiveTags.isEmpty()) {
            if (userEffectiveTags.size() == 1) {
                bf.should(s3 -> s3.term(t -> t.field("orgTag").value(userEffectiveTags.get(0))));
            } else {
                bf.should(s3 -> s3.bool(inner -> {
                    userEffectiveTags.forEach(tag -> inner.should(sh -> sh.term(t -> t.field("orgTag").value(tag))));
                    return inner;
                }));
            }
        }
        bf.minimumShouldMatch("1");
        return Query.of(q -> q.bool(bf.build()));
    }

    // ============================== RRF 融合 ==============================

    /**
     * Reciprocal Rank Fusion：
     * <pre>
     *   score(d) = Σ_{i ∈ paths} 1 / (k + rank_i(d))
     * </pre>
     * rank 从 1 开始；只在一路出现的文档另一路项视为 0（即相加少一项）。
     */
    private List<CandidateDoc> mergeWithRRF(List<DocWithScore> vecHits, List<DocWithScore> bmHits,
                                             int topN, int k) {
        Map<String, CandidateDoc> map = new LinkedHashMap<>();

        for (int i = 0; i < vecHits.size(); i++) {
            DocWithScore d = vecHits.get(i);
            String key = key(d.doc());
            CandidateDoc c = map.computeIfAbsent(key, k0 -> new CandidateDoc(d.doc()));
            c.vecRank = i + 1;
            c.vecScore = d.score();
            c.rrfScore += 1.0 / (k + (i + 1));
        }
        for (int i = 0; i < bmHits.size(); i++) {
            DocWithScore d = bmHits.get(i);
            String key = key(d.doc());
            CandidateDoc c = map.computeIfAbsent(key, k0 -> new CandidateDoc(d.doc()));
            c.bmRank = i + 1;
            c.bmScore = d.score();
            c.rrfScore += 1.0 / (k + (i + 1));
        }

        return map.values().stream()
                .sorted((a, b) -> Double.compare(b.rrfScore, a.rrfScore))
                .limit(topN)
                .collect(Collectors.toList());
    }

    private String key(EsDocument doc) {
        return doc.getFileMd5() + "#" + doc.getChunkId();
    }

    // ============================== Rerank ==============================

    /**
     * 调 qwen3-rerank 做 Cross-Encoder 重排。失败时直接按 RRF 顺序取前 topK。
     */
    private List<CandidateDoc> applyRerank(String query, List<CandidateDoc> candidates, int topK) {
        if (!qwenRerankClient.isEnabled() || candidates.isEmpty()) {
            return capByTopK(candidates, topK);
        }
        List<String> docTexts = candidates.stream()
                .map(c -> c.doc.getTextContent() == null ? "" : c.doc.getTextContent())
                .collect(Collectors.toList());

        List<QwenRerankClient.RerankItem> rerankResults =
                qwenRerankClient.rerank(query, docTexts, topK);

        if (rerankResults.isEmpty()) {
            logger.info("[Hybrid] rerank 未返回结果，按 RRF 顺序取前 {} 个", topK);
            return capByTopK(candidates, topK);
        }

        List<CandidateDoc> result = new ArrayList<>(rerankResults.size());
        for (QwenRerankClient.RerankItem item : rerankResults) {
            if (item.index() < 0 || item.index() >= candidates.size()) continue;
            CandidateDoc c = candidates.get(item.index());
            c.rerankScore = item.relevanceScore();
            result.add(c);
        }
        return result;
    }

    private List<CandidateDoc> capByTopK(List<CandidateDoc> candidates, int topK) {
        if (candidates.size() <= topK) return candidates;
        return candidates.subList(0, topK);
    }

    // ============================== 兼容方法 ==============================

    /**
     * 仅使用文本匹配的带权限搜索方法（兜底）。
     */
    private List<SearchResult> textOnlySearchWithPermission(String query, String userDbId,
                                                            List<String> userEffectiveTags, int topK) {
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> s
                            .index(INDEX)
                            .query(q -> q.bool(b -> b
                                    .must(m -> m.match(ma -> ma.field("textContent").query(query)))
                                    .filter(buildPermissionFilter(userDbId, userEffectiveTags))))
                            .minScore(0.3d)
                            .size(topK),
                    EsDocument.class);
            List<SearchResult> results = response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        SearchResult r = new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                hit.score() == null ? 0d : hit.score().doubleValue(),
                                hit.source().getUserId(),
                                hit.source().getOrgTag(),
                                hit.source().isPublic()
                        );
                        r.setBmScore(hit.score() == null ? 0d : hit.score().doubleValue());
                        return r;
                    })
                    .collect(Collectors.toList());
            attachFileNames(results);
            return results;
        } catch (Exception e) {
            logger.error("纯文本搜索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 无权限版本（兼容 SearchController#search），保留旧逻辑。
     */
    public List<SearchResult> search(String query, int topK) {
        try {
            logger.warn("使用了没有权限过滤的搜索方法，建议使用 searchWithPermission");
            List<Float> queryVector = embedToVectorList(query);
            if (queryVector == null) {
                return textOnlySearch(query, topK);
            }
            int recallK = topK * 30;
            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index(INDEX);
                s.knn(kn -> kn.field("vector").queryVector(queryVector).k(recallK).numCandidates(recallK));
                s.query(q -> q.match(m -> m.field("textContent").query(query)));
                s.rescore(r -> r.windowSize(recallK)
                        .query(rq -> rq.queryWeight(0.2d).rescoreQueryWeight(1.0d)
                                .query(rqq -> rqq.match(m -> m.field("textContent").query(query).operator(Operator.And)))));
                s.size(topK);
                return s;
            }, EsDocument.class);

            return response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        return new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                hit.score() == null ? 0d : hit.score().doubleValue());
                    })
                    .toList();
        } catch (Exception e) {
            logger.error("搜索失败", e);
            try {
                return textOnlySearch(query, topK);
            } catch (Exception fallback) {
                logger.error("后备搜索也失败", fallback);
                throw new RuntimeException("搜索完全失败", fallback);
            }
        }
    }

    private List<SearchResult> textOnlySearch(String query, int topK) throws Exception {
        SearchResponse<EsDocument> response = esClient.search(s -> s
                        .index(INDEX)
                        .query(q -> q.match(m -> m.field("textContent").query(query)))
                        .size(topK),
                EsDocument.class);

        return response.hits().hits().stream()
                .map(hit -> {
                    assert hit.source() != null;
                    return new SearchResult(
                            hit.source().getFileMd5(),
                            hit.source().getChunkId(),
                            hit.source().getTextContent(),
                            hit.score() == null ? 0d : hit.score().doubleValue());
                })
                .toList();
    }

    // ============================== 内部辅助 ==============================

    private List<Float> embedToVectorList(String text) {
        try {
            List<float[]> vecs = embeddingClient.embed(List.of(text));
            if (vecs == null || vecs.isEmpty()) return null;
            float[] raw = vecs.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float v : raw) list.add(v);
            return list;
        } catch (Exception e) {
            logger.error("生成向量失败", e);
            return null;
        }
    }

    private List<String> getUserEffectiveOrgTags(String userId) {
        try {
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                user = userRepository.findById(userIdLong)
                        .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
            } catch (NumberFormatException e) {
                user = userRepository.findByUsername(userId)
                        .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
            }
            return orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
        } catch (Exception e) {
            logger.error("获取用户有效组织标签失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private String getUserDbId(String userId) {
        try {
            try {
                Long userIdLong = Long.parseLong(userId);
                userRepository.findById(userIdLong)
                        .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                return userIdLong.toString();
            } catch (NumberFormatException e) {
                User user = userRepository.findByUsername(userId)
                        .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                return user.getId().toString();
            }
        } catch (Exception e) {
            logger.error("获取用户数据库ID失败: {}", e.getMessage(), e);
            throw new RuntimeException("获取用户数据库ID失败", e);
        }
    }

    private void attachFileNames(List<SearchResult> results) {
        if (results == null || results.isEmpty()) return;
        try {
            Set<String> md5Set = results.stream().map(SearchResult::getFileMd5).collect(Collectors.toSet());
            List<FileUpload> uploads = fileUploadRepository.findByFileMd5In(new ArrayList<>(md5Set));
            Map<String, String> md5ToName = uploads.stream()
                    .collect(Collectors.toMap(FileUpload::getFileMd5, FileUpload::getFileName));
            results.forEach(r -> r.setFileName(md5ToName.get(r.getFileMd5())));
        } catch (Exception e) {
            logger.error("补充文件名失败", e);
        }
    }

    // ============================== 内部 DTO ==============================

    private record DocWithScore(EsDocument doc, double score) {}

    /**
     * 候选文档：贯穿 RRF + rerank 阶段，最后转为 SearchResult。
     */
    private static class CandidateDoc {
        final EsDocument doc;
        Integer vecRank;
        Integer bmRank;
        Double vecScore;
        Double bmScore;
        double rrfScore = 0d;
        Double rerankScore;

        CandidateDoc(EsDocument doc) {
            this.doc = doc;
        }

        SearchResult toSearchResult() {
            double finalScore = rerankScore != null ? rerankScore : rrfScore;
            SearchResult r = new SearchResult(
                    doc.getFileMd5(),
                    doc.getChunkId(),
                    doc.getTextContent(),
                    finalScore,
                    doc.getUserId(),
                    doc.getOrgTag(),
                    doc.isPublic()
            );
            r.setVecScore(vecScore);
            r.setBmScore(bmScore);
            r.setRrfScore(rrfScore);
            r.setRerankScore(rerankScore);
            return r;
        }
    }
}
