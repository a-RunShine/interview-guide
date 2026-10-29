import type { LlmProviderCacheState } from '../types/llmProvider';

/**
 * LLM Provider 缓存状态的展示映射。
 *
 * 后端的四个状态名（READY / REFRESHING / STALE / EXPIRED）只在这里翻译成中文，
 * 页面组件只消费这里给出的文案与语气，避免同一套翻译散落在各处。
 */

export type CacheStatusTone = 'success' | 'refreshing' | 'warning' | 'error' | 'neutral';

export interface CacheStatusPresentation {
  label: string;
  tone: CacheStatusTone;
  description: string;
}

export type ReloadNoticeTone = 'success' | 'error';

export interface ReloadNotice {
  tone: ReloadNoticeTone;
  text: string;
}

const UNKNOWN_STATUS: CacheStatusPresentation = {
  label: '状态未知',
  tone: 'neutral',
  description: '暂时读不到缓存状态，可以点手动刷新再试一次。',
};

const CACHE_STATUS_PRESENTATIONS: Record<LlmProviderCacheState, CacheStatusPresentation> = {
  READY: {
    label: '最新',
    tone: 'success',
    description: '当前配置已校验通过，正在使用最新一代。',
  },
  REFRESHING: {
    label: '刷新中',
    tone: 'refreshing',
    description: '正在读取并校验最新配置，稍后自动更新。',
  },
  STALE: {
    label: '已过期（宽限中）',
    tone: 'warning',
    description: '新配置校验失败，暂时继续使用旧配置。',
  },
  EXPIRED: {
    label: '已停止服务',
    tone: 'error',
    description: '宽限期已结束，模型请求会直接失败，请尽快修复配置。',
  },
};

const UNKNOWN_FAILURE_REASON = '原因未知';
const MANUAL_RETRY_HINT = '请检查配置后手动再试';
const AUTO_RETRY_HINT = '系统会自动重试';

export function getCacheStatusPresentation(
  state?: string | null,
): CacheStatusPresentation {
  if (!state) return UNKNOWN_STATUS;
  return CACHE_STATUS_PRESENTATIONS[state as LlmProviderCacheState] ?? UNKNOWN_STATUS;
}

/** 宽限剩余时间只对有效的正秒数给出文案；其他情况（含 READY 时为 0）不展示。 */
export function formatGraceRemaining(
  seconds?: number | null,
): string | null {
  if (typeof seconds !== 'number' || !Number.isFinite(seconds) || seconds <= 0) {
    return null;
  }
  return `剩余 ${Math.ceil(seconds)} 秒`;
}

/**
 * 手动刷新的真实结果。失败时保留后端给出的原因，不用笼统的"刷新成功"覆盖掉。
 */
export function getReloadNotice(result: {
  success: boolean;
  failureReason?: string | null;
  retryable?: boolean | null;
}): ReloadNotice {
  if (result.success) {
    return { tone: 'success', text: '缓存已刷新' };
  }
  const reason = result.failureReason?.trim() || UNKNOWN_FAILURE_REASON;
  const hint = result.retryable ? AUTO_RETRY_HINT : MANUAL_RETRY_HINT;
  return { tone: 'error', text: `刷新失败：${reason}（${hint}）` };
}

/**
 * "最近成功"时间的展示文案。
 *
 * 后端只在真正成功刷新过一次之后才给出 `lastSuccessAt`：进程刚启动、还没刷新过时是 null。
 * 这时必须显示"尚未刷新"，而不是空白（看起来像加载失败）或拿页面加载时间冒充成功时间。
 */
export function formatLastSuccessAt(timestamp?: string | null): string {
  if (!timestamp) return '尚未刷新';
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) return '尚未刷新';
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}
