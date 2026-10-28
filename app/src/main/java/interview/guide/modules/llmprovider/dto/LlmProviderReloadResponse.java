package interview.guide.modules.llmprovider.dto;

import java.time.Instant;

/**
 * 手动刷新的同步结果：成功时带回新的构建时间，失败时带回失败原因与是否可重试。
 *
 * <p>失败不是异常路径：Service 用 {@code Result.error(code, message, data)} 返回，
 * {@code data} 里仍然带着本响应，因此前端能拿到真实的 {@code retryable} 而不是靠猜。
 */
public record LlmProviderReloadResponse(
    boolean success,
    Instant builtAt,
    String failureReason,
    boolean retryable
) {}
