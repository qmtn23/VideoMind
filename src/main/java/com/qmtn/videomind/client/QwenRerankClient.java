package com.qmtn.videomind.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope qwen3-rerank 重排客户端。
 *
 * <p>调用 OpenAI 兼容路径 <code>POST /compatible-api/v1/reranks</code>。
 * 失败时返回空 List，调用方应做降级处理。</p>
 */
@Service
public class QwenRerankClient {

    private static final Logger logger = LoggerFactory.getLogger(QwenRerankClient.class);

    private final WebClient webClient;
    private final boolean enabled;
    private final String model;
    private final String instruct;
    private final long timeoutSeconds;

    public QwenRerankClient(@Value("${reranker.api.url:https://dashscope.aliyuncs.com/compatible-api/v1}") String apiUrl,
                            @Value("${reranker.api.key:}") String apiKey,
                            @Value("${reranker.model:qwen3-rerank}") String model,
                            @Value("${reranker.enabled:true}") boolean enabled,
                            @Value("${reranker.instruct:Given a web search query, retrieve relevant passages that answer the query.}") String instruct,
                            @Value("${reranker.timeoutSeconds:30}") long timeoutSeconds) {
        this.enabled = enabled;
        this.model = model;
        this.instruct = instruct;
        this.timeoutSeconds = timeoutSeconds;

        WebClient.Builder builder = WebClient.builder().baseUrl(apiUrl);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        // 增大响应缓冲区（rerank 响应可能较大）
        builder.codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024));
        // 不复用全局 WebClientConfig，避免与流式 LLM 客户端互相影响
        HttpClient http = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(this.timeoutSeconds));
        builder.clientConnector(new ReactorClientHttpConnector(http));

        this.webClient = builder.build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 重排：返回每个候选文档对应的相关性得分（按原始 documents 顺序的索引映射）。
     * 失败/未启用 → 返回空列表，调用方应直接走原始顺序。
     *
     * @param query     查询语句
     * @param documents 候选文本块
     * @param topN      返回前几个；传 null/&lt;=0 默认与 documents.size() 相同
     * @return List of {index, relevanceScore}，按 relevanceScore 降序
     */
    public List<RerankItem> rerank(String query, List<String> documents, Integer topN) {
        if (!enabled) {
            return List.of();
        }
        if (query == null || query.isBlank() || documents == null || documents.isEmpty()) {
            return List.of();
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("query", query);
        body.put("documents", documents);
        if (topN != null && topN > 0) {
            body.put("top_n", Math.min(topN, documents.size()));
        }
        if (instruct != null && !instruct.isBlank()) {
            body.put("instruct", instruct);
        }

        long t0 = System.currentTimeMillis();
        try {
            String raw = webClient.post()
                    .uri("/reranks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(timeoutSeconds));

            if (raw == null || raw.isBlank()) {
                logger.warn("[Rerank] 返回为空，跳过重排");
                return List.of();
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(raw);
            JsonNode results = node.path("results");
            if (!results.isArray() || results.isEmpty()) {
                logger.warn("[Rerank] 响应中无 results 字段，原文：{}", trim(raw));
                return List.of();
            }

            List<RerankItem> items = new ArrayList<>(results.size());
            for (JsonNode r : results) {
                int idx = r.path("index").asInt(-1);
                double score = r.path("relevance_score").asDouble(0d);
                if (idx >= 0 && idx < documents.size()) {
                    items.add(new RerankItem(idx, score));
                }
            }
            long elapsed = System.currentTimeMillis() - t0;
            logger.info("[Rerank] 完成，输入 {} 文档 -> 输出 {} 项，耗时 {}ms", documents.size(), items.size(), elapsed);
            return items;
        } catch (Exception e) {
            logger.warn("[Rerank] 调用失败，降级走 RRF 顺序：{}", e.getMessage());
            return List.of();
        }
    }

    private String trim(String s) {
        if (s == null) return "";
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }

    /**
     * Rerank 单条结果。
     *
     * @param index          原始 documents 列表中的位置
     * @param relevanceScore 相关性得分（0~1）
     */
    public record RerankItem(int index, double relevanceScore) {}
}
