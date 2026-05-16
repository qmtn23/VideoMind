package com.qmtn.videomind.controller;

import com.qmtn.videomind.client.DeepSeekClient;
import com.qmtn.videomind.entity.SearchResult;
import com.qmtn.videomind.service.HybridSearchService;
import com.qmtn.videomind.utils.LogUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评估专用接口（供 Ragas 等离线评估脚本调用）
 *
 * <p>复用项目内的 {@link HybridSearchService#searchWithPermission} 与
 * {@link DeepSeekClient#getResponse}，等价于关闭流式输出的对话流程。
 * 返回结构化的 {question, contexts, answer} 数据，便于外部脚本计算指标。</p>
 */
@RestController
@RequestMapping("/api/v1/eval")
public class EvalController {

    private static final Logger logger = LoggerFactory.getLogger(EvalController.class);

    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private DeepSeekClient deepSeekClient;

    /**
     * 单条评估接口：给定问题，返回检索到的上下文 + LLM 回答。
     *
     * <pre>
     * 请求体： { "question": "...", "topK": 5 }
     * 返回体： {
     *   "code": 200,
     *   "data": {
     *     "question": "...",
     *     "answer": "...",
     *     "contexts": ["chunk1", "chunk2"],
     *     "retrievalTimeMs": 120,
     *     "generationTimeMs": 8500
     *   }
     * }
     * </pre>
     */
    @PostMapping("/answer")
    public ResponseEntity<?> answer(@RequestBody EvalRequest request,
                                    @RequestAttribute(value = "userId", required = false) String userId) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("EVAL_ANSWER");
        try {
            if (request == null || request.question() == null || request.question().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "code", 400,
                        "message", "question 不能为空"
                ));
            }
            int topK = request.topK() != null && request.topK() > 0 ? request.topK() : 5;
            String safeUserId = userId != null ? userId : "eval-anonymous";
            logger.info("[EVAL] 收到评估请求，userId={}, question={}, topK={}",
                    safeUserId, request.question(), topK);

            long t0 = System.currentTimeMillis();
            List<SearchResult> searchResults = hybridSearchService.searchWithPermission(
                    request.question(), safeUserId, topK);
            long retrievalTimeMs = System.currentTimeMillis() - t0;
            logger.info("[EVAL] 检索完成，命中 chunk 数={}, 耗时={}ms",
                    searchResults == null ? 0 : searchResults.size(), retrievalTimeMs);

            List<String> contexts = searchResults == null ? Collections.emptyList()
                    : searchResults.stream()
                            .map(SearchResult::getTextContent)
                            .filter(c -> c != null && !c.isBlank())
                            .collect(Collectors.toList());
            String contextStr = buildContextString(searchResults);

            long t1 = System.currentTimeMillis();
            String answer = deepSeekClient.getResponse(request.question(), contextStr, new ArrayList<>());
            long generationTimeMs = System.currentTimeMillis() - t1;

            Map<String, Object> data = new HashMap<>();
            data.put("question", request.question());
            data.put("answer", answer);
            data.put("contexts", contexts);
            data.put("retrievalTimeMs", retrievalTimeMs);
            data.put("generationTimeMs", generationTimeMs);

            monitor.end("评估接口处理成功");
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "success",
                    "data", data
            ));
        } catch (Exception e) {
            logger.error("评估接口失败: {}", e.getMessage(), e);
            monitor.end("评估接口失败: " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                    "code", 500,
                    "message", e.getMessage() != null ? e.getMessage() : "Internal server error"
            ));
        }
    }

    /**
     * 把检索结果拼成给 LLM 的参考文本。格式与 ChatHandler.buildContext 保持一致风格，
     * 便于评估接口尽量贴近真实对话流程。
     */
    private String buildContextString(List<SearchResult> searchResults) {
        if (searchResults == null || searchResults.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < searchResults.size(); i++) {
            SearchResult r = searchResults.get(i);
            sb.append("【片段 ").append(i + 1).append("】");
            if (r.getFileName() != null) {
                sb.append("（来源：").append(r.getFileName()).append("）");
            }
            sb.append("\n");
            if (r.getTextContent() != null) {
                sb.append(r.getTextContent()).append("\n\n");
            }
        }
        return sb.toString();
    }
}

record EvalRequest(String question, Integer topK) {}
