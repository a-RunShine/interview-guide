package interview.guide.modules.llmprovider.dto;

import java.time.Instant;

/**
 * LLM Provider 缓存状态。
 *
 * <p>{@code state} 保持注册表的原始枚举名（{@code READY} / {@code REFRESHING} /
 * {@code STALE} / {@code EXPIRED}），翻译交给前端，避免前后端各维护一套状态名。
 */
public record LlmProviderCacheStatusResponse(
    String state,
    Instant lastSuccessAt,
    String lastFailureReason,
    long graceSecondsRemaining,
    boolean retryable
) {}
