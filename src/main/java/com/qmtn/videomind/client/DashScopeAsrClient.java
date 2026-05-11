package com.qmtn.videomind.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 阿里云 DashScope Paraformer-v2 语音转文字客户端（FunASR 系列）
 * 调用流程：
 *   1. POST /api/v1/services/audio/asr/transcription 提交异步任务
 *   2. GET  /api/v1/tasks/{task_id} 轮询直到 SUCCEEDED
 *   3. 下载 transcription_url 返回的 JSON，拼接所有句子文本
 */
@Component
public class DashScopeAsrClient {

    private static final Logger logger = LoggerFactory.getLogger(DashScopeAsrClient.class);

    private static final String SUBMIT_PATH = "/api/v1/services/audio/asr/transcription";
    private static final String TASK_PATH   = "/api/v1/tasks/";

    @Value("${asr.api.url:https://dashscope.aliyuncs.com}")
    private String apiUrl;

    @Value("${asr.api.key:}")
    private String apiKey;

    @Value("${asr.model:paraformer-v2}")
    private String model;

    @Value("${asr.enabled:true}")
    private boolean enabled;

    @Value("${asr.timeout:600}")
    private int timeoutSeconds;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /**
     * 对指定的公网可访问音频 URL 执行语音转文字
     *
     * @param audioUrl 公网可访问的音频文件预签名 URL
     * @return 完整识别文本（各句拼接，保留换行）
     */
    public String transcribe(String audioUrl) throws Exception {
        logger.info("DashScope ASR 开始转写, audioUrl 长度: {}", audioUrl.length());

        // Step 1: 提交异步任务
        String taskId = submitTask(audioUrl);
        logger.info("DashScope ASR 任务已提交, taskId: {}", taskId);

        // Step 2: 轮询任务状态
        String transcriptionUrl = pollUntilSucceeded(taskId);
        logger.info("DashScope ASR 转写完成, transcriptionUrl: {}", transcriptionUrl);

        // Step 3: 下载结果 JSON 拼接文本
        String fullText = fetchAndJoinText(transcriptionUrl);
        logger.info("DashScope ASR 识别文本长度: {}", fullText.length());
        return fullText;
    }

    // ─── Step 1 ───────────────────────────────────────────────────────────────

    private String submitTask(String audioUrl) throws Exception {
        HttpHeaders headers = buildHeaders();
        headers.set("X-DashScope-Async", "enable");

        Map<String, Object> body = Map.of(
            "model", model,
            "input", Map.of("file_urls", List.of(audioUrl))
        );

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl + SUBMIT_PATH, request, String.class);

        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode taskId = root.path("output").path("task_id");
        if (taskId.isMissingNode() || taskId.asText().isBlank()) {
            throw new RuntimeException("DashScope ASR 提交任务失败，响应: " + response.getBody());
        }
        return taskId.asText();
    }

    // ─── Step 2 ───────────────────────────────────────────────────────────────

    private String pollUntilSucceeded(String taskId) throws Exception {
        HttpHeaders headers = buildHeaders();
        HttpEntity<Void> request = new HttpEntity<>(headers);
        String url = apiUrl + TASK_PATH + taskId;

        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        int pollIntervalMs = 3000;

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(pollIntervalMs);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode output = root.path("output");
            String status = output.path("task_status").asText();
            logger.debug("DashScope ASR 任务状态: {}", status);

            switch (status) {
                case "SUCCEEDED" -> {
                    JsonNode results = output.path("results");
                    if (results.isArray() && results.size() > 0) {
                        return results.get(0).path("transcription_url").asText();
                    }
                    throw new RuntimeException("DashScope ASR SUCCEEDED 但 results 为空");
                }
                case "FAILED" -> throw new RuntimeException(
                        "DashScope ASR 任务失败: " + output.path("message").asText());
                default -> {
                    // PENDING / RUNNING → 继续等待
                }
            }
        }
        throw new RuntimeException("DashScope ASR 转写超时（已等待 " + timeoutSeconds + " 秒）");
    }

    // ─── Step 3 ───────────────────────────────────────────────────────────────

    private String fetchAndJoinText(String transcriptionUrl) throws Exception {
        String json = restTemplate.getForObject(URI.create(transcriptionUrl), String.class);
        if (json == null || json.isBlank()) {
            throw new RuntimeException("DashScope ASR 转写结果 JSON 为空");
        }

        JsonNode root = objectMapper.readTree(json);
        JsonNode transcripts = root.path("transcripts");

        StringBuilder sb = new StringBuilder();
        if (transcripts.isArray()) {
            for (JsonNode transcript : transcripts) {
                String text = transcript.path("text").asText("").trim();
                if (!text.isEmpty()) {
                    sb.append(text).append("\n");
                }
            }
        }
        return sb.toString().trim();
    }

    // ─── 工具方法 ──────────────────────────────────────────────────────────────

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);
        return headers;
    }
}
