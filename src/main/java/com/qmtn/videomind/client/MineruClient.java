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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * MinerU 云端 API 客户端
 * 文档解析流程（四步）：
 *   1. 申请文件上传 URL（POST /api/v4/file-urls/batch）
 *   2. PUT 文件字节到上传 URL
 *   3. 轮询批次结果（GET /api/v4/extract-results/batch/{batch_id}）
 *   4. 下载 ZIP，解压 full.md 返回 Markdown 文本
 */
@Component
public class MineruClient {

    private static final Logger logger = LoggerFactory.getLogger(MineruClient.class);

    private static final String BASE_URL = "https://mineru.net";
    private static final String BATCH_UPLOAD_URL = BASE_URL + "/api/v4/file-urls/batch";
    private static final String BATCH_RESULT_URL = BASE_URL + "/api/v4/extract-results/batch/";

    @Value("${mineru.api.key:}")
    private String apiKey;

    @Value("${mineru.api.enabled:false}")
    private boolean enabled;

    @Value("${mineru.api.timeout:300}")
    private int timeoutSeconds;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /**
     * 将 PDF 字节流解析为 Markdown 文本
     *
     * @param fileBytes 文件字节数组
     * @param fileName  文件名（含扩展名），用于 MinerU 判断文件类型
     * @return Markdown 格式的文档正文
     * @throws Exception 解析失败时抛出
     */
    public String parsePdf(byte[] fileBytes, String fileName) throws Exception {
        logger.info("MinerU 开始解析文件: {}, 大小: {} bytes", fileName, fileBytes.length);

        // Step 1: 申请上传 URL
        String[] uploadInfo = requestUploadUrl(fileName);
        String batchId = uploadInfo[0];
        String uploadUrl = uploadInfo[1];
        logger.info("MinerU 获取上传 URL 成功, batchId: {}", batchId);

        // Step 2: PUT 上传文件
        uploadFile(uploadUrl, fileBytes);
        logger.info("MinerU 文件上传完成");

        // Step 3: 轮询结果
        String zipUrl = pollForResult(batchId);
        logger.info("MinerU 解析完成, zipUrl: {}", zipUrl);

        // Step 4: 下载 ZIP 解压 full.md
        String markdown = downloadAndExtractMarkdown(zipUrl);
        logger.info("MinerU 提取 Markdown 成功, 字符数: {}", markdown.length());
        return markdown;
    }

    // ─── Step 1 ───────────────────────────────────────────────────────────────

    private String[] requestUploadUrl(String fileName) throws Exception {
        HttpHeaders headers = buildAuthHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
            "files", List.of(Map.of("name", fileName)),
            "model_version", "vlm"
        );

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(BATCH_UPLOAD_URL, request, String.class);

        JsonNode root = objectMapper.readTree(response.getBody());
        if (root.path("code").asInt(-1) != 0) {
            throw new RuntimeException("MinerU 申请上传 URL 失败: " + root.path("msg").asText());
        }
        JsonNode data = root.path("data");
        String batchId = data.path("batch_id").asText();
        String uploadUrl = data.path("file_urls").get(0).asText();
        return new String[]{batchId, uploadUrl};
    }

    // ─── Step 2 ───────────────────────────────────────────────────────────────

    private void uploadFile(String uploadUrl, byte[] fileBytes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        HttpEntity<byte[]> request = new HttpEntity<>(fileBytes, headers);
        restTemplate.exchange(URI.create(uploadUrl), HttpMethod.PUT, request, Void.class);
    }

    // ─── Step 3 ───────────────────────────────────────────────────────────────

    private String pollForResult(String batchId) throws Exception {
        String url = BATCH_RESULT_URL + batchId;
        HttpHeaders headers = buildAuthHeaders();
        HttpEntity<Void> request = new HttpEntity<>(headers);

        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        int pollIntervalMs = 3000;

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(pollIntervalMs);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            if (root.path("code").asInt(-1) != 0) {
                throw new RuntimeException("MinerU 查询结果失败: " + root.path("msg").asText());
            }

            JsonNode results = root.path("data").path("extract_result");
            if (results.isArray() && results.size() > 0) {
                JsonNode item = results.get(0);
                String state = item.path("state").asText();
                logger.debug("MinerU 任务状态: {}", state);

                switch (state) {
                    case "done" -> {
                        return item.path("full_zip_url").asText();
                    }
                    case "failed" -> throw new RuntimeException("MinerU 解析失败: " + item.path("err_msg").asText());
                    default -> {
                        // pending / running / converting / waiting-file → 继续等待
                        int extracted = item.path("extract_progress").path("extracted_pages").asInt(0);
                        int total = item.path("extract_progress").path("total_pages").asInt(0);
                        if (total > 0) {
                            logger.info("MinerU 解析进度: {}/{} 页", extracted, total);
                        }
                    }
                }
            }
        }
        throw new RuntimeException("MinerU 解析超时（已等待 " + timeoutSeconds + " 秒）");
    }

    // ─── Step 4 ───────────────────────────────────────────────────────────────

    private String downloadAndExtractMarkdown(String zipUrl) throws Exception {
        byte[] zipBytes = restTemplate.getForObject(URI.create(zipUrl), byte[].class);
        if (zipBytes == null || zipBytes.length == 0) {
            throw new RuntimeException("MinerU 返回的 ZIP 文件为空");
        }

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith("full.md")) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = zis.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                    return out.toString("UTF-8").trim();
                }
                zis.closeEntry();
            }
        }
        throw new RuntimeException("MinerU 返回的 ZIP 中未找到 full.md");
    }

    // ─── 工具方法 ──────────────────────────────────────────────────────────────

    private HttpHeaders buildAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        return headers;
    }
}
