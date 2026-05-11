package com.qmtn.videomind.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URI;

/**
 * 启动时检测 minio.publicUrl 是否对外可达。
 *
 * <p>若不可达（ngrok 未启动、域名失效等），打印 WARN 级别告警并列出修复步骤，
 * 应用仍正常启动——普通文本文件功能不受影响，仅视频 ASR 解析会失败。</p>
 */
@Component
@Order(10)
public class MinioPublicUrlChecker implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(MinioPublicUrlChecker.class);
    private static final int CONNECT_TIMEOUT_MS = 5000;

    @Value("${minio.publicUrl}")
    private String publicUrl;

    @Qualifier("minioPublicUrl")
    @org.springframework.beans.factory.annotation.Autowired
    private String minioPublicUrlBean;

    @Override
    public void run(String... args) {
        if (publicUrl == null || publicUrl.isBlank() || publicUrl.equals("http://localhost:19000")) {
            logger.warn("""
                    ╔══════════════════════════════════════════════════════════════╗
                    ║  [视频功能] minio.publicUrl 未配置公网地址                      ║
                    ║  当前值: {}
                    ║  视频 ASR 解析将失败，普通文档上传不受影响。                       ║
                    ║  修复：在 application-local.yml 中配置                         ║
                    ║    minio.publicUrl: "https://<your-ngrok-domain>"             ║
                    ║  并确保 ngrok 已启动：                                          ║
                    ║    ngrok http --url=<your-ngrok-domain> 19000                 ║
                    ╚══════════════════════════════════════════════════════════════╝
                    """, publicUrl);
            return;
        }

        logger.info("[视频功能] 正在检测 minio.publicUrl 可达性: {}", publicUrl);
        try {
            // 只发 HEAD 请求到根路径，不需要实际内容，快速判断连通性
            String checkUrl = publicUrl.endsWith("/") ? publicUrl + "minio/health/live" : publicUrl + "/minio/health/live";
            HttpURLConnection conn = (HttpURLConnection) URI.create(checkUrl).toURL().openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(CONNECT_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            conn.disconnect();

            // MinIO /minio/health/live 返回 200；ngrok 检查页返回 200/307 均视为可达
            if (code < 500) {
                logger.info("[视频功能] minio.publicUrl 可达，响应码: {} — 视频 ASR 链路就绪", code);
            } else {
                warnNotReachable(publicUrl, "HTTP " + code);
            }
        } catch (Exception e) {
            warnNotReachable(publicUrl, e.getMessage());
        }
    }

    private void warnNotReachable(String url, String reason) {
        logger.warn("""
                ╔══════════════════════════════════════════════════════════════╗
                ║  [视频功能] minio.publicUrl 不可达，视频 ASR 解析将失败！          ║
                ║  地址: {}
                ║  原因: {}
                ║                                                              ║
                ║  请检查：                                                       ║
                ║    1. ngrok 是否已启动？                                         ║
                ║       运行：ngrok http --url=<域名> 19000                       ║
                ║       验证：浏览器打开 http://127.0.0.1:4040                     ║
                ║    2. application-local.yml 中 minio.publicUrl 与 ngrok 域名   ║
                ║       是否一致？                                                 ║
                ║    3. Docker MinIO 是否在 19000 端口运行？                        ║
                ║       运行：docker ps | grep minio                              ║
                ╚══════════════════════════════════════════════════════════════╝
                """, url, reason);
    }
}
