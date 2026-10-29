package interview.guide.modules.llmprovider.listener;

import interview.guide.common.ai.LlmProviderCacheRefresher;
import interview.guide.modules.llmprovider.event.LlmProviderCacheInvalidatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 在 Provider 配置事务提交后刷新 LLM Provider 缓存。
 * 没有活动事务时立即刷新，覆盖手动重载。
 *
 * <p>刷新与重试决策都交给 {@link LlmProviderCacheRefresher#refreshAfterCommit()}，
 * 与手动刷新接口共用同一个接缝，避免两条路径的重试判断漂移。
 * 这条路径绝不把异常冒到提交方：任何 Throwable 都在接缝里被拦下并记日志和指标。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmProviderCacheInvalidationListener {

  private final LlmProviderCacheRefresher refresher;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onInvalidated(LlmProviderCacheInvalidatedEvent event) {
    log.debug("Reloading LLM provider cache after {}", event);
    refresher.refreshAfterCommit();
  }
}
