package com.qmtn.videomind.service;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.TimeUnit;

/**
 * 负责将视频处理中产生的临时音频文件上传到 MinIO，并生成供 DashScope ASR 调用的公网预签名 URL。
 *
 * <p>重要前提：MinIO 必须配置可被 DashScope 服务端访问的公网地址（minio.publicUrl），
 * 否则 ASR 无法拉取音频。本地开发可使用 ngrok 等工具临时暴露 MinIO 端口。</p>
 *
 * <p>上传使用内网 minioClient，预签名使用 minioPublicClient（endpoint 为公网域名），
 * 保证预签名 URL 中的 Host 与 DashScope 实际访问的 Host 一致，避免签名校验 403。</p>
 */
@Service
public class MinioAudioService {

    private static final Logger logger = LoggerFactory.getLogger(MinioAudioService.class);
    private static final String BUCKET = "uploads";

    @Autowired
    private MinioClient minioClient;

    @Autowired
    @Qualifier("minioPublicClient")
    private MinioClient minioPublicClient;

    /**
     * 上传临时音频文件到 MinIO，返回 1 小时有效的预签名 GET URL。
     *
     * @param audioFile  本地音频文件（MP3）
     * @param objectName MinIO 对象路径，例如 "audio-temp/{md5}.mp3"
     * @return 预签名下载 URL（公网可访问）
     */
    public String uploadTempAudio(File audioFile, String objectName) throws Exception {
        logger.info("上传临时音频到 MinIO: objectName={}, size={} bytes", objectName, audioFile.length());

        try (FileInputStream fis = new FileInputStream(audioFile)) {
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(BUCKET)
                    .object(objectName)
                    .stream(fis, audioFile.length(), -1)
                    .contentType("audio/mpeg")
                    .build()
            );
        }

        // 用公网 MinioClient 生成预签名 URL（签名的 Host 即为公网域名，DashScope 可直接访问）
        String presignedUrl = minioPublicClient.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(BUCKET)
                .object(objectName)
                .expiry(1, TimeUnit.HOURS)
                .build()
        );

        logger.info("临时音频预签名 URL 已生成: {}", presignedUrl);
        return presignedUrl;
    }

    /**
     * 删除 MinIO 中的临时音频对象，用于任务完成后清理资源。
     *
     * @param objectName MinIO 对象路径
     */
    public void deleteTempAudio(String objectName) {
        try {
            minioClient.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(BUCKET)
                    .object(objectName)
                    .build()
            );
            logger.info("已删除 MinIO 临时音频: {}", objectName);
        } catch (Exception e) {
            logger.warn("删除 MinIO 临时音频失败（已忽略）: objectName={}, error={}", objectName, e.getMessage());
        }
    }
}
