package interview.guide.modules.llmprovider.service;

import interview.guide.common.ai.LlmProviderCacheRefresher;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.LlmProviderRegistry.CacheStatus;
import interview.guide.common.ai.LlmProviderRegistry.RefreshResult;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.Result;
import interview.guide.modules.llmprovider.dto.LlmProviderCacheStatusResponse;
import interview.guide.modules.llmprovider.dto.LlmProviderReloadResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 缓存刷新与状态查询的编排层。
 *
 * <p>手动刷新走这里而不是事件：AFTER_COMMIT 监听器会吞掉刷新异常（避免把缓存失败冒成 500），
 * 而手动刷新需要把结果原样交给调用方。刷新与重试决策统一交给
 * {@link LlmProviderCacheRefresher}，与监听器共用同一条接缝。
 *
 * <p>失败也必须在 {@code data} 里带回 {@code success=false}、失败原因和真实的
 * {@code retryable}，因此成功 / 失败到错误码的判定放在这里：控制器只做路由与委托。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LlmProviderCacheService {

  /** 刷新失败但异常没有 message 时的兜底文案，保证接口总能给出可展示的原因。 */
  private static final String UNKNOWN_FAILURE_REASON = "LLM Provider 缓存刷新失败，原因未知";

  private final LlmProviderCacheRefresher refresher;
  private final LlmProviderRegistry registry;

  /**
   * 同步尝试验证并切换缓存。失败不抛异常：宽限期内的旧一代继续服务，
   * 可恢复失败交给重试调度，结果按 {@code Result} 约定呈现（HTTP 仍为 200）。
   */
  public Result<LlmProviderReloadResponse> reloadProviders() {
    RefreshResult result = refresher.refreshAndScheduleRetry();
    String failureReason = result.failureReason() != null ? result.failureReason() : UNKNOWN_FAILURE_REASON;
    LlmProviderReloadResponse response = new LlmProviderReloadResponse(
        result.success(), result.builtAt(), result.success() ? null : failureReason, result.retryable());
    if (result.success()) {
      log.info("[LlmProviderCacheService] Manual reload succeeded, builtAt={}", result.builtAt());
      return Result.success(response);
    }
    log.warn("[LlmProviderCacheService] Manual reload failed, retryable={}, reason={}",
        result.retryable(), failureReason);
    // 失败仍带回 data：retryable 是给前端的决策依据，不能因为错误码而丢掉
    return Result.error(ErrorCode.PROVIDER_CONFIG_READ_FAILED, failureReason, response);
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
