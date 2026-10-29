export interface ProviderItem {
  id: string;
  baseUrl: string;
  maskedApiKey: string;
  model: string;
  embeddingModel: string | null;
  embeddingDimensions: number | null;
  supportsEmbedding: boolean;
  temperature: number | null;
  defaultChatProvider: boolean;
  defaultEmbeddingProvider: boolean;
}

export interface CreateProviderRequest {
  id: string;
  baseUrl: string;
  apiKey: string;
  model: string;
  embeddingModel?: string;
  embeddingDimensions?: number;
  supportsEmbedding?: boolean;
  temperature?: number;
}

export interface UpdateProviderRequest {
  baseUrl?: string;
  apiKey?: string;
  model?: string;
  embeddingModel?: string;
  embeddingDimensions?: number;
  supportsEmbedding?: boolean;
  temperature?: number;
}

export interface ProviderTestResult {
  success: boolean;
  message: string;
  model: string;
}

/** LLM Provider 缓存状态，与后端状态机枚举保持一致，翻译在前端完成。 */
export type LlmProviderCacheState = 'READY' | 'REFRESHING' | 'STALE' | 'EXPIRED';

export interface LlmProviderCacheStatus {
  state: LlmProviderCacheState | string;
  lastSuccessAt: string | null;
  lastFailureReason: string | null;
  graceSecondsRemaining: number;
  retryable: boolean;
}

export interface LlmProviderReloadResult {
  success: boolean;
  builtAt: string | null;
  failureReason: string | null;
  retryable: boolean;
}

export interface DefaultProvider {
  defaultProvider: string;
  defaultEmbeddingProvider: string;
}

export interface AsrConfig {
  url: string;
  model: string;
  maskedApiKey: string;
  language: string;
  format: string;
  sampleRate: number;
  enableTurnDetection: boolean;
  turnDetectionType: string;
  turnDetectionThreshold: number;
  turnDetectionSilenceDurationMs: number;
}

export interface TtsConfig {
  model: string;
  maskedApiKey: string;
  voice: string;
  format: string;
  sampleRate: number;
  mode: string;
  languageType: string;
  speechRate: number;
  volume: number;
}

export interface AsrConfigRequest {
  url?: string;
  model?: string;
  apiKey?: string;
  language?: string;
  format?: string;
  sampleRate?: number;
  enableTurnDetection?: boolean;
  turnDetectionType?: string;
  turnDetectionThreshold?: number;
  turnDetectionSilenceDurationMs?: number;
}

export interface TtsConfigRequest {
  model?: string;
  apiKey?: string;
  voice?: string;
  format?: string;
  sampleRate?: number;
  mode?: string;
  languageType?: string;
  speechRate?: number;
  volume?: number;
}
