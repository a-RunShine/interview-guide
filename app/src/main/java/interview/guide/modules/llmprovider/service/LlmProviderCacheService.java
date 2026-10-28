package interview.guide.modules.llmprovider.service;

import interview.guide.common.ai.LlmProviderRefreshRetrier;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.LlmProviderRegistry.CacheStatus;
import interview.guide.common.ai.LlmProviderRegistry.RefreshResult;
import interview.guide.modules.llmprovider.dto.LlmProviderCacheStatusResponse;
import interview.guide.modules.llmprovider.dto.LlmProviderReloadResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 缓存刷新与状态查询的编排层。
 *
 * <p>手动刷新走这里而不是事件：AFTER_COMMIT 监听器会吞掉刷新异常（避免把缓存失败冒成 500），
 * 而手动刷新需要把结果原样交给调用方，所以直接调用 {@link LlmProviderRegistry#refresh()}。
 * 刷新失败仍按原有语义进入宽限 / 重试链，这里只负责把结果翻译成响应。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LlmProviderCacheService {

  /** 刷新失败但异常没有 message 时的兜底文案，保证接口总能给出可展示的原因。 */
  private static final String UNKNOWN_FAILURE_REASON = "LLM Provider 缓存刷新失败，原因未知";

  private final LlmProviderRegistry registry;
  private final LlmProviderRefreshRetrier retrier;

  /**
   * 同步尝试验证并切换缓存。失败不抛异常：宽限期内的旧一代继续服务，
   * 可恢复失败交给重试调度，结果由调用方按 {@code Result} 约定呈现。
   */
  public LlmProviderReloadResponse reloadProviders() {
    RefreshResult result = registry.refresh();
    retrier.onRefreshResult(result);
    if (result.success()) {
      log.info("[LlmProviderCacheService] Manual reload succeeded, builtAt={}", result.builtAt());
      return new LlmProviderReloadResponse(true, result.builtAt(), null, result.retryable());
    }
    String reason = result.failureReason() != null ? result.failureReason() : UNKNOWN_FAILURE_REASON;
    log.warn("[LlmProviderCacheService] Manual reload failed, retryable={}, reason={}",
        result.retryable(), reason);
    return new LlmProviderReloadResponse(false, null, reason, result.retryable());
  }

  /** 当前缓存状态快照，状态名保持枚举原名，由前端负责翻译。 */
  public LlmProviderCacheStatusResponse cacheStatus() {
    CacheStatus status = registry.status();
    return new LlmProviderCacheStatusResponse(
        status.stateName(),
        status.lastSuccessAt(),
        status.lastFailureReason(),
        status.graceSecondsRemaining(),
        status.retryable());
  }
}
