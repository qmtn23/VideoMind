package com.qmtn.videomind.controller;

import com.qmtn.videomind.service.SummaryService;
import com.qmtn.videomind.utils.LogUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 视频摘要相关接口。
 *
 *   - GET  /api/v1/documents/{fileMd5}/summary   查询已生成的摘要
 *   - POST /api/v1/documents/{fileMd5}/summary   触发生成（同步，5-15s）
 */
@RestController
@RequestMapping("/api/v1/documents")
public class SummaryController {

    private static final Logger logger = LoggerFactory.getLogger(SummaryController.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private SummaryService summaryService;

    @GetMapping("/{fileMd5}/summary")
    public ResponseEntity<?> getSummary(@PathVariable String fileMd5,
                                        @RequestAttribute("userId") String userId) {
        try {
            Optional<SummaryService.SummaryResult> result = summaryService.findExisting(fileMd5, userId);
            if (result.isEmpty()) {
                Map<String, Object> body = new HashMap<>();
                body.put("code", HttpStatus.NOT_FOUND.value());
                body.put("message", "尚未生成摘要");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
            }
            return ResponseEntity.ok(buildSuccess(result.get()));
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_SUMMARY", userId, "查询摘要失败: fileMd5=%s", e, fileMd5);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "查询摘要失败: " + e.getMessage());
        }
    }

    @PostMapping("/{fileMd5}/summary")
    public ResponseEntity<?> generateSummary(@PathVariable String fileMd5,
                                             @RequestAttribute("userId") String userId) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GENERATE_SUMMARY");
        try {
            LogUtils.logBusiness("GENERATE_SUMMARY", userId, "请求生成视频摘要: fileMd5=%s", fileMd5);
            SummaryService.SummaryResult result = summaryService.generateForVideo(fileMd5, userId);
            monitor.end("摘要生成成功");
            return ResponseEntity.ok(buildSuccess(result));
        } catch (IllegalArgumentException e) {
            monitor.end("参数错误: " + e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            monitor.end("服务未启用: " + e.getMessage());
            return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (Exception e) {
            LogUtils.logBusinessError("GENERATE_SUMMARY", userId, "摘要生成失败: fileMd5=%s", e, fileMd5);
            monitor.end("摘要生成失败: " + e.getMessage());
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "摘要生成失败: " + e.getMessage());
        }
    }

    private Map<String, Object> buildSuccess(SummaryService.SummaryResult result) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", 200);
        body.put("message", "成功");
        Map<String, Object> data = new HashMap<>();
        data.put("summary", result.summary());
        data.put("generatedAt", result.generatedAt() == null ? null : result.generatedAt().format(DT_FMT));
        data.put("truncated", result.truncated());
        body.put("data", data);
        return body;
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String msg) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", status.value());
        body.put("message", msg);
        return ResponseEntity.status(status).body(body);
    }
}
