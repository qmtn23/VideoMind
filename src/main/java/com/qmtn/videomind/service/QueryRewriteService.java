package com.qmtn.videomind.service;

import com.qmtn.videomind.client.DeepSeekClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 查询改写服务：把用户口语化问题改写为更适合 RAG 混合检索的表达。
 *
 * <p>策略：单次 LLM 同义改写（保留实体/关键词，补充 1-2 个同义词）。
 * 失败/超时/未启用 → 直接回退到原 query。</p>
 */
@Service
public class QueryRewriteService {

    private static final Logger logger = LoggerFactory.getLogger(QueryRewriteService.class);

    private static final String SYSTEM_PROMPT = """
            你是一个检索查询改写助手。请把用户的原始问题改写为更适合在企业知识库中做语义+关键词混合检索的表达。
            要求：
            1. 保留所有关键实体、专有名词、数字、英文术语缩写。
            2. 把口语化表达替换为更书面、更精炼的关键词。
            3. 同时补充 1-2 个同义词或近义短语，提升召回率。
            4. 输出改写后的问题本身，纯文本一行，不要解释，不要前缀，不要标点列表。
            """;

    @Autowired
    private DeepSeekClient deepSeekClient;

    @Value("${search.queryRewrite.enabled:true}")
    private boolean enabled;

    @Value("${search.queryRewrite.timeoutSeconds:5}")
    private long timeoutSeconds;

    /**
     * 同义改写。任何异常都安全降级返回原 query，调用方无需处理失败。
     */
    public String rewrite(String original) {
        if (original == null || original.isBlank()) {
            return original;
        }
        if (!enabled) {
            return original;
        }

        long t0 = System.currentTimeMillis();
        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    String userMessage = "原始问题：" + original.trim() + "\n请输出改写后的问题：";
                    return deepSeekClient.getResponse(userMessage, SYSTEM_PROMPT, new ArrayList<>());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            String rewritten = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (rewritten == null) {
                logger.warn("[QueryRewrite] 返回为 null，回退原 query");
                return original;
            }
            String cleaned = clean(rewritten);
            if (cleaned.isBlank()) {
                logger.warn("[QueryRewrite] 清洗后为空，回退原 query");
                return original;
            }

            long elapsed = System.currentTimeMillis() - t0;
            logger.info("[QueryRewrite] 原: \"{}\" -> 改写: \"{}\" (耗时 {}ms)", original, cleaned, elapsed);
            return cleaned;
        } catch (TimeoutException e) {
            logger.warn("[QueryRewrite] 改写超时（>{}s），回退原 query", timeoutSeconds);
            return original;
        } catch (Exception e) {
            logger.warn("[QueryRewrite] 改写失败，回退原 query: {}", e.getMessage());
            return original;
        }
    }

    /**
     * LLM 偶尔会带上 "改写后的问题：" 这种前缀或多余引号，做轻量清洗。
     */
    private String clean(String raw) {
        String s = raw.trim();
        if (s.startsWith("改写后的问题：") || s.startsWith("改写后：")) {
            s = s.substring(s.indexOf("：") + 1).trim();
        }
        if (s.length() >= 2 && (s.startsWith("\"") || s.startsWith("\u201C")) &&
                (s.endsWith("\"") || s.endsWith("\u201D"))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        // 去掉换行，只取第一行
        int nl = s.indexOf('\n');
        if (nl > 0) {
            s = s.substring(0, nl).trim();
        }
        return s;
    }
}
