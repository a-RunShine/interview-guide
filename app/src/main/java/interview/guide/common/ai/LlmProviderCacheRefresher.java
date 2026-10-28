package interview.guide.common.ai;

import interview.guide.common.ai.LlmProviderRegistry.RefreshResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 刷新缓存的唯一接缝：{@link LlmProviderRegistry#refresh()} + 重试决策。
 *
 * <p>事务提交后的监听器和手动刷新接口都从这里进入，保证两条路径的重试判断不会各写一份而漂移。
 * 两个入口对异常的态度不同，因此各有一个方法：
 * <ul>
 *   <li>{@link #refreshAndScheduleRetry()} 把结果原样交给调用方，手动刷新用它同步返回成功 / 失败。</li>
 *   <li>{@link #refreshAfterCommit()} 绝不外抛，失败只记日志和指标，避免把纯缓存的失败冒到提交方。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmProviderCacheRefresher {

  private final LlmProviderRegistry registry;
  private final LlmProviderRefreshRetrier retrier;

  /**
   * 先刷新、再把结果交给 {@link LlmProviderRefreshRetrier} 决定是否安排重试。
   *
   * @return 本次刷新的真实结果，失败时也带回原因与是否可重试
   */
  public RefreshResult refreshAndScheduleRetry() {
    RefreshResult result = registry.refresh();
    retrier.onRefreshResult(result);
    return result;
  }

  /**
   * 事务提交后的刷新：故意捕获 {@link Throwable}，只记日志和失败指标。
   * AFTER_COMMIT 里抛出的异常会冒到提交方，让一个纯缓存的失败把已成功的业务事务变成 500。
   */
  public void refreshAfterCommit() {
    try {
      refreshAndScheduleRetry();
    } catch (Throwable t) {
      registry.recordEscapedRefreshFailure(t);
      log.error("[LlmProviderCacheRefresher] LLM provider cache refresh blew up, "
          + "cache keeps serving the previous generation", t);
    }
  }
}
