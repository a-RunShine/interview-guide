package interview.guide.modules.llmprovider.listener;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.llmprovider.event.LlmProviderCacheInvalidatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 在 Provider 配置事务提交后刷新 {@link LlmProviderRegistry}。
 * 没有活动事务时立即刷新，覆盖手动重载。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmProviderCacheInvalidationListener {

  private final LlmProviderRegistry registry;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onInvalidated(LlmProviderCacheInvalidatedEvent event) {
    log.debug("Reloading LLM provider cache after {}", event);
    registry.reload();
  }
}
