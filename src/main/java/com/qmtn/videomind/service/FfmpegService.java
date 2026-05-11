package com.qmtn.videomind.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 调用系统 FFmpeg 命令行工具从视频中提取音频轨道。
 * 要求：系统已安装 ffmpeg 并加入 PATH，或通过 ffmpeg.path 配置绝对路径。
 */
@Service
public class FfmpegService {

    private static final Logger logger = LoggerFactory.getLogger(FfmpegService.class);

    @Value("${ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    /**
     * 从视频文件中提取音频，输出为 16 kHz 单声道 MP3。
     *
     * @param videoFile 输入视频文件（本地临时文件）
     * @param audioFile 输出音频文件（由调用方创建临时文件路径）
     * @throws IOException          IO 错误
     * @throws InterruptedException 进程被中断
     */
    public void extractAudio(File videoFile, File audioFile) throws IOException, InterruptedException {
        logger.info("FFmpeg 开始提取音频: {} → {}", videoFile.getAbsolutePath(), audioFile.getAbsolutePath());

        List<String> cmd = List.of(
            ffmpegPath,
            "-y",                           // 覆盖已存在输出文件
            "-i", videoFile.getAbsolutePath(),
            "-vn",                          // 不处理视频流
            "-ar", "16000",                 // 采样率 16 kHz（DashScope ASR 推荐）
            "-ac", "1",                     // 单声道
            "-b:a", "64k",                  // 音频比特率
            "-f", "mp3",                    // 输出格式
            audioFile.getAbsolutePath()
        );

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException(
                "无法启动 FFmpeg（路径: " + ffmpegPath + "）。" +
                "请确认系统已安装 FFmpeg 并加入 PATH，或在配置中设置 ffmpeg.path 的绝对路径。" +
                "参考：https://ffmpeg.org/download.html", e);
        }

        // 读取进程输出（避免缓冲区阻塞）
        String output;
        try (var reader = process.inputReader()) {
            output = reader.lines().reduce("", (a, b) -> a + "\n" + b).trim();
        }

        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("FFmpeg 提取音频超时（超过 10 分钟）");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            logger.error("FFmpeg 退出码 {}, 输出:\n{}", exitCode, output);
            throw new IOException("FFmpeg 提取音频失败，退出码: " + exitCode);
        }

        if (!audioFile.exists() || audioFile.length() == 0) {
            throw new IOException("FFmpeg 提取音频失败：输出文件为空");
        }

        logger.info("FFmpeg 音频提取完成，文件大小: {} bytes", audioFile.length());
    }
}
