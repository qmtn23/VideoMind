import { request } from '../request';

/** 视频摘要：拉取分块 + 同步 LLM，单独放宽超时 */
const SUMMARY_TIMEOUT_MS = 100 * 1000;

export interface VideoSummary {
  /** 摘要正文，Markdown 格式 */
  summary: string;
  /** 生成时间，格式 yyyy-MM-dd HH:mm:ss */
  generatedAt: string | null;
  /** 是否因 ASR 全文超长被截断 */
  truncated: boolean;
}

/** 查询已存在的视频摘要，未生成时业务码会是 404，由调用方处理 */
export function fetchVideoSummary(fileMd5: string) {
  return request<VideoSummary>({
    url: `/documents/${fileMd5}/summary`,
    timeout: SUMMARY_TIMEOUT_MS
  });
}

/** 触发生成视频摘要（同步阻塞，可能耗时数十秒） */
export function generateVideoSummary(fileMd5: string) {
  return request<VideoSummary>({
    url: `/documents/${fileMd5}/summary`,
    method: 'post',
    timeout: SUMMARY_TIMEOUT_MS
  });
}
