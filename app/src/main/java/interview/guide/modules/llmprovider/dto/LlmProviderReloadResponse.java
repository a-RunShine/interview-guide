package interview.guide.modules.llmprovider.dto;

import java.time.Instant;

/**
 * 手动刷新的同步结果：成功时带回新的构建时间，失败时带回失败原因。
 *
 * <p>失败不是异常路径，控制器按 {@code Result.error} 约定返回，不抛 500。
 */
public record LlmProviderReloadResponse(
    boolean success,
    Instant builtAt,
    String failureReason,
    boolean retryable
) {}
