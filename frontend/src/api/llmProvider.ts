import request, { isResult } from './request';
import type { Result } from './request';
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
   * 失败也走 `Result.error`（HTTP 200），统一拦截器会把 message 变成异常、丢掉 retryable，
   * 所以这里跳过解包，把失败原因和可重试标记一起交给页面展示。
   */
  reload: async (): Promise<LlmProviderReloadResult> => {
    const payload = await request.post<Result<LlmProviderReloadResult>>(
      '/api/llm-provider/reload',
      undefined,
      { skipResultTransform: true },
    );
    if (isResult(payload) && payload.code === 200 && payload.data) {
      return payload.data;
    }
    return {
      success: false,
      builtAt: null,
      failureReason: isResult(payload) ? payload.message : null,
      retryable: false,
    };
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
