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

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope（OpenAI 兼容模式）非流式聊天客户端。
 *
 * 与 {@link DeepSeekClient} 区别：
 * - DeepSeekClient: 流式（stream=true），用于聊天 SSE 输出
 * - DashScopeChatClient: 非流式（stream=false），用于摘要、总结等一次性生成场景
 *
 * 复用同一套 deepseek.api.* 配置（URL、Key、Model），无需新增配置项。
 */
@Service
public class DashScopeChatClient {

    private static final Logger logger = LoggerFactory.getLogger(DashScopeChatClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient webClient;
    private final String model;
    private final boolean enabled;

    public DashScopeChatClient(@Value("${deepseek.api.url}") String apiUrl,
                               @Value("${deepseek.api.key}") String apiKey,
                               @Value("${deepseek.api.model}") String model) {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(apiUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024));

        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            this.enabled = true;
        } else {
            this.enabled = false;
            logger.warn("DashScopeChatClient 未配置 API Key，非流式聊天功能将不可用");
        }

        this.webClient = builder.build();
        this.model = model;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 同步调用 chat/completions 接口，返回 assistant 消息文本。
     *
     * @param systemPrompt 系统提示词，可为 null
     * @param userContent  用户消息（含待处理文本）
     * @param maxTokens    最大输出 tokens
     * @param temperature  采样温度
     * @return 模型生成的完整回复文本（已去除首尾空白）
     */
    public String complete(String systemPrompt, String userContent, int maxTokens, double temperature) {
        if (!enabled) {
            throw new IllegalStateException("DashScope Chat 未启用：缺少 deepseek.api.key");
        }

        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("stream", false);
        request.put("temperature", temperature);
        request.put("max_tokens", maxTokens);

        java.util.List<Map<String, String>> messages = new java.util.ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userContent));
        request.put("messages", messages);

        long start = System.currentTimeMillis();
        String responseBody = webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(120))
                .block();

        long cost = System.currentTimeMillis() - start;
        if (responseBody == null) {
            throw new RuntimeException("DashScope Chat 返回空响应");
        }

        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                logger.error("DashScope Chat 响应缺少 choices: {}", responseBody);
                throw new RuntimeException("DashScope Chat 响应格式异常");
            }
            String content = choices.get(0).path("message").path("content").asText("");
            logger.info("DashScope Chat 完成，model={}, 输入字符={}, 输出字符={}, 耗时={}ms",
                    model, userContent.length(), content.length(), cost);
            return content.strip();
        } catch (Exception e) {
            logger.error("解析 DashScope Chat 响应失败: {}", responseBody, e);
            throw new RuntimeException("解析模型响应失败: " + e.getMessage(), e);
        }
    }

    /**
     * 便捷重载：使用默认采样参数（max_tokens=1500, temperature=0.3）。
     */
    public String complete(String systemPrompt, String userContent) {
        return complete(systemPrompt, userContent, 1500, 0.3);
    }
}
