package com.qmtn.videomind.service;

import com.qmtn.videomind.client.DashScopeChatClient;
import com.qmtn.videomind.model.DocumentVector;
import com.qmtn.videomind.model.FileUpload;
import com.qmtn.videomind.repository.DocumentVectorRepository;
import com.qmtn.videomind.repository.FileUploadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 视频摘要生成服务。
 *
 * 流程：
 *   1. 校验文件存在且属于当前用户
 *   2. 校验文件扩展名属于视频类型
 *   3. 从 document_vectors 拉取按 chunkId 排序的所有 ASR 分块文本，拼接为完整转写文本
 *   4. 超长截断（避免 LLM 上下文超限）
 *   5. 调用 DashScopeChatClient 同步生成摘要
 *   6. 写回 file_upload.summary 与 summary_generated_at
 */
@Service
public class SummaryService {

    private static final Logger logger = LoggerFactory.getLogger(SummaryService.class);

    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "avi", "mov", "mkv", "webm", "flv", "wmv", "m4v"
    );

    /** 拼接 ASR 全文后传给 LLM 的最大字符数，超过则截断。qwen-plus 上下文约 32k token，预留输出空间 */
    private static final int MAX_INPUT_CHARS = 30000;

    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是视频内容摘要助手。下面用户消息中的文本是某段视频的语音转写结果，可能包含口语错别字、" +
            "语气词或重复内容。请基于该文本输出一份结构化摘要，仅使用简体中文，使用 Markdown 格式：\n" +
            "1. 第一行用 `## 一句话主旨` 后跟 30 字以内的中心思想；\n" +
            "2. 然后是 `## 核心要点`，列 3-5 条要点（每条不超过 50 字）；\n" +
            "3. 最后是 `## 关键术语`，列出视频中出现的人名、产品名、技术术语等（若无则写「无」）。\n" +
            "严格基于给定文本，不要编造未提及的信息。";

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private DashScopeChatClient dashScopeChatClient;

    @Value("${ai.prompt.video-summary:}")
    private String configuredSystemPrompt;

    /**
     * 为指定视频生成摘要（同步阻塞）。已存在摘要时会被覆盖。
     *
     * @param fileMd5 文件 MD5
     * @param userId  当前用户 ID（用于权限校验）
     * @return 生成的摘要文本
     * @throws IllegalArgumentException 文件不存在 / 不属于当前用户 / 不是视频 / 没有可用文本
     * @throws IllegalStateException    LLM 客户端未启用
     */
    @Transactional
    public SummaryResult generateForVideo(String fileMd5, String userId) {
        FileUpload file = fileUploadRepository.findByFileMd5AndUserId(fileMd5, userId)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在或无权访问: " + fileMd5));

        String fileName = file.getFileName() == null ? "" : file.getFileName();
        String ext = extractExtension(fileName);
        if (ext.isEmpty() || !VIDEO_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("仅视频文件支持生成摘要，当前文件: " + fileName);
        }

        if (!dashScopeChatClient.isEnabled()) {
            throw new IllegalStateException("AI 服务未启用，无法生成摘要（请检查 deepseek.api.key 配置）");
        }

        List<DocumentVector> chunks = documentVectorRepository.findByFileMd5OrderByChunkIdAsc(fileMd5);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("视频尚未完成 ASR 解析或无可用文本，请稍后再试");
        }

        StringBuilder buf = new StringBuilder();
        for (DocumentVector chunk : chunks) {
            String text = chunk.getTextContent();
            if (text == null || text.isBlank()) continue;
            if (buf.length() > 0) buf.append('\n');
            buf.append(text);
        }
        String fullText = buf.toString().strip();
        if (fullText.isEmpty()) {
            throw new IllegalArgumentException("视频转写文本为空，无法生成摘要");
        }

        boolean truncated = false;
        if (fullText.length() > MAX_INPUT_CHARS) {
            fullText = fullText.substring(0, MAX_INPUT_CHARS);
            truncated = true;
            logger.info("ASR 全文超长被截断, fileMd5={}, 截取前 {} 字", fileMd5, MAX_INPUT_CHARS);
        }

        String systemPrompt = (configuredSystemPrompt != null && !configuredSystemPrompt.isBlank())
                ? configuredSystemPrompt : DEFAULT_SYSTEM_PROMPT;

        String userContent = "视频名称：" + fileName + "\n\n以下是该视频的语音转写内容：\n" + fullText;

        logger.info("开始生成视频摘要, fileMd5={}, fileName={}, 输入字符={}, 已截断={}",
                fileMd5, fileName, fullText.length(), truncated);

        String summary = dashScopeChatClient.complete(systemPrompt, userContent);
        if (summary == null || summary.isBlank()) {
            throw new RuntimeException("LLM 返回空摘要");
        }

        LocalDateTime now = LocalDateTime.now();
        file.setSummary(summary);
        file.setSummaryGeneratedAt(now);
        fileUploadRepository.save(file);

        logger.info("视频摘要生成成功, fileMd5={}, 摘要字符数={}", fileMd5, summary.length());
        return new SummaryResult(summary, now, truncated);
    }

    /**
     * 查询已存在的摘要，不存在返回 empty。
     */
    public Optional<SummaryResult> findExisting(String fileMd5, String userId) {
        return fileUploadRepository.findByFileMd5AndUserId(fileMd5, userId)
                .filter(f -> f.getSummary() != null && !f.getSummary().isBlank())
                .map(f -> new SummaryResult(f.getSummary(), f.getSummaryGeneratedAt(), false));
    }

    private String extractExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) return "";
        return fileName.substring(idx + 1).toLowerCase();
    }

    public record SummaryResult(String summary, LocalDateTime generatedAt, boolean truncated) {}
}
