import request from './request';
import type { Result } from './request';
import { toReloadResult } from './llmProviderReload';
import type {
  ProviderItem,
  CreateProviderRequest,
  UpdateProviderRequest,
  ProviderTestResult,
  DefaultProvider,
  AsrConfig,
  TtsConfig,
  AsrConfigRequest,
  TtsConfigRequest,
  LlmProviderCacheStatus,
  LlmProviderReloadResult,
} from '../types/llmProvider';

export const llmProviderApi = {
  list: () => request.get<ProviderItem[]>('/api/llm-provider/list'),

  get: (id: string) => request.get<ProviderItem>(`/api/llm-provider/${id}`),

  create: (data: CreateProviderRequest) =>
    request.post<void>('/api/llm-provider', data),

  update: (id: string, data: UpdateProviderRequest) =>
    request.put<void>(`/api/llm-provider/${id}`, data),

  delete: (id: string) =>
    request.delete<void>(`/api/llm-provider/${id}`),

  test: (id: string) =>
    request.post<ProviderTestResult>(`/api/llm-provider/${id}/test`),

  /**
   * 手动刷新：后端同步校验并返回真实结果。
   *
   * 成功是 `Result.success`，失败是 `Result.error`（HTTP 200），但两条路径的 `data` 里都带
   * `success` / `failureReason` / `retryable`。统一拦截器会把失败变成异常、丢掉 data，
   * 所以这里跳过解包自己读；解析规则集中在 `toReloadResult`，便于单测覆盖。
   */
  reload: async (): Promise<LlmProviderReloadResult> => {
    const payload = await request.post<Result<LlmProviderReloadResult>>(
      '/api/llm-provider/reload',
      undefined,
      { skipResultTransform: true },
    );
    return toReloadResult(payload);
  },

  getCacheStatus: () =>
    request.get<LlmProviderCacheStatus>('/api/llm-provider/cache-status'),

  getDefaultProvider: () =>
    request.get<DefaultProvider>('/api/llm-provider/default-provider'),

  updateDefaultProvider: (data: DefaultProvider) =>
    request.put<void>('/api/llm-provider/default-provider', data),

  updateDefaultEmbeddingProvider: (data: DefaultProvider) =>
    request.put<void>('/api/llm-provider/default-embedding-provider', data),

  // Voice ASR/TTS Config
  getAsrConfig: () =>
    request.get<AsrConfig>('/api/llm-provider/voice/asr'),

  updateAsrConfig: (data: AsrConfigRequest) =>
    request.put<void>('/api/llm-provider/voice/asr', data),

  getTtsConfig: () =>
    request.get<TtsConfig>('/api/llm-provider/voice/tts'),

  updateTtsConfig: (data: TtsConfigRequest) =>
    request.put<void>('/api/llm-provider/voice/tts', data),

  testAsr: () =>
    request.post<ProviderTestResult>('/api/llm-provider/voice/asr/test'),
};
