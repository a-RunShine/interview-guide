import type { LlmProviderReloadResult } from '../types/llmProvider';

interface ResultEnvelope {
  code: number;
  message: string;
  data?: unknown;
}

function isEnvelope(value: unknown): value is ResultEnvelope {
  return value !== null
    && typeof value === 'object'
    && typeof (value as ResultEnvelope).code === 'number'
    && typeof (value as ResultEnvelope).message === 'string';
}

/**
 * 手动刷新响应的解析：成功和失败两条路径都必须保留 `data`。
 *
 * 后端约定 HTTP 200 + `Result`：成功是 `code=200`，失败是业务错误码，
 * 但两者都会在 `data` 里给出 `success` / `failureReason` / `retryable`。
 * 只有真的没有 `data`（网络中断、网关错误页）才退回"失败 + 不可重试"的兜底值。
 *
 * 这里自己判 `Result` 结构而不是复用 `request.ts`，让本模块不依赖 axios，
 * 可以直接用 `node --test` 覆盖这条契约。
 */
export function toReloadResult(payload: unknown): LlmProviderReloadResult {
  if (isEnvelope(payload) && payload.data) {
    return payload.data as LlmProviderReloadResult;
  }
  return {
    success: false,
    builtAt: null,
    failureReason: isEnvelope(payload) ? payload.message : null,
    retryable: false,
  };
}
