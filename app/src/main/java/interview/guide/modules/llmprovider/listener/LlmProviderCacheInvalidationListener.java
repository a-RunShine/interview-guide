package interview.guide.modules.llmprovider.listener;

import interview.guide.common.ai.LlmProviderRefreshRetrier;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.LlmProviderRegistry.RefreshResult;
import interview.guide.modules.llmprovider.event.LlmProviderCacheInvalidatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 在 Provider 配置事务提交后刷新 {@link LlmProviderRegistry}。
 * 没有活动事务时立即刷新，覆盖手动重载。
 *
 * <p>这是缓存刷新的唯一入口，因此也是"刷新失败绝不冒到提交方"的保证点：
 * 任何 Throwable 都在这里被拦下并记 ERROR，可恢复失败交给
 * {@link LlmProviderRefreshRetrier} 安排有界重试。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmProviderCacheInvalidationListener {

  private final LlmProviderRegistry registry;
  private final LlmProviderRefreshRetrier retrier;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onInvalidated(LlmProviderCacheInvalidatedEvent event) {
    log.debug("Reloading LLM provider cache after {}", event);
    try {
      RefreshResult result = registry.refresh();
      retrier.onRefreshResult(result);
    } catch (Throwable t) {
      // 故意捕获 Throwable：AFTER_COMMIT 里抛出的异常会冒到提交方，
      // 让一个纯缓存的失败把已成功的业务事务变成 500。
      log.error("[LlmProviderCacheInvalidationListener] LLM provider cache refresh failed, "
          + "cache keeps serving the previous generation", t);
    }
  }
}
