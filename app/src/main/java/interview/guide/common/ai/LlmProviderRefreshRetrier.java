package interview.guide.common.ai;

import interview.guide.common.ai.LlmProviderRegistry.CacheState;
import interview.guide.common.ai.LlmProviderRegistry.CacheStatus;
import interview.guide.common.ai.LlmProviderRegistry.RefreshResult;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 可恢复刷新失败的有界重试调度器。
 *
 * <p>提交配置的监听器在 {@link LlmProviderRegistry#refresh()} 返回可恢复失败后调用
 * {@link #onRefreshResult(RefreshResult)} 安排重试。重试跑在独立的单线程守护线程池上，
 * 不占用请求线程，也不在任何事务内执行。
 *
 * <p>每次尝试都重新读取真实状态：缓存已是 {@link CacheState#READY}（更新的一次刷新成功）
 * 就不再重试；失败变为不可恢复立即停止；可恢复则按递增退避继续，次数耗尽由
 * {@link LlmProviderRegistry#markGraceExpired()} 把状态推进到 {@link CacheState#EXPIRED}。
 *
 * <p>队列上界：同一时刻最多只有一条重试链、一个待执行的尝试（由 {@link #chainId} 令牌保证），
 * 因此虽然有界队列来自 {@link ScheduledThreadPoolExecutor} 的延迟队列，实际排队深度始终为 1。
 */
@Component
@Slf4j
public class LlmProviderRefreshRetrier {

  /** 首次失败后最多再尝试的次数。 */
  static final int MAX_ATTEMPTS = 3;

  /** 第一次重试的退避间隔，之后每次翻倍。 */
  static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);

  private static final String THREAD_NAME = "llm-provider-refresh-retry";

  private final LlmProviderRegistry registry;
  private final ScheduledExecutorService executor;
  /** 当前重试链的令牌；任何"停止"决定都通过自增使在途任务失效。 */
  private final AtomicLong chainId = new AtomicLong();
  /** 是否已有重试链在等待下一次尝试，保证同一时刻只有一条链。 */
  private final AtomicBoolean chainInFlight = new AtomicBoolean();

  public LlmProviderRefreshRetrier(LlmProviderRegistry registry) {
    this(registry, newSingleThreadScheduler());
  }

  LlmProviderRefreshRetrier(LlmProviderRegistry registry, ScheduledExecutorService executor) {
    this.registry = registry;
    this.executor = executor;
  }

  /**
   * 单线程、守护线程、有界延迟队列的显式执行器。不使用 {@code Executors.newXxx}。
   */
  private static ScheduledExecutorService newSingleThreadScheduler() {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(
        1,
        runnable -> {
          Thread thread = new Thread(runnable, THREAD_NAME);
          thread.setDaemon(true);
          return thread;
        },
        (runnable, pool) -> log.warn(
            "[LlmProviderRefreshRetrier] Retry task rejected, pool={}", pool.isShutdown()));
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }

  /**
   * 根据一次刷新的结果决定是否安排重试。
   *
   * <p>成功：作废在途重试链。不可重试：不安排。可重试：若已有重试链在途则沿用（它的下一次
   * 尝试会重新读状态），否则开启一条新链。{@code null} 结果（例如被替换的假实现）不做任何事。
   */
  public void onRefreshResult(RefreshResult result) {
    if (result == null) {
      log.warn("[LlmProviderRefreshRetrier] Refresh result unavailable, retry not scheduled");
      return;
    }
    if (result.success()) {
      stopChain("a newer refresh succeeded");
      return;
    }
    if (!result.retryable()) {
      log.error("[LlmProviderRefreshRetrier] Refresh failed with non-retryable error, retry skipped: {}",
          result.failureReason());
      return;
    }
    startChain();
  }

  private void startChain() {
    if (!chainInFlight.compareAndSet(false, true)) {
      log.debug("[LlmProviderRefreshRetrier] A retry chain is already in flight, reusing it");
      return;
    }
    scheduleAttempt(chainId.incrementAndGet(), 1);
  }

  private void scheduleAttempt(long token, int attempt) {
    Duration delay = INITIAL_BACKOFF.multipliedBy(1L << (attempt - 1));
    log.warn("[LlmProviderRefreshRetrier] Scheduling refresh retry {}/{} in {} ms",
        attempt, MAX_ATTEMPTS, delay.toMillis());
    try {
      executor.schedule(() -> attempt(token, attempt), delay.toMillis(), TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException e) {
      log.error("[LlmProviderRefreshRetrier] Retry executor rejected attempt {}/{}",
          attempt, MAX_ATTEMPTS, e);
      stopChain("executor rejected the retry task");
    }
  }

  private void attempt(long token, int attempt) {
    if (token != chainId.get()) {
      log.debug("[LlmProviderRefreshRetrier] Retry attempt {} superseded, skipping", attempt);
      return;
    }
    CacheStatus current = readStatus();
    if (current != null && current.state() == CacheState.READY) {
      stopChain("cache is already current");
      return;
    }
    if (current != null && current.lastFailureReason() != null && !current.retryable()) {
      stopChain("the last failure became non-retryable");
      return;
    }

    RefreshResult result = refreshQuietly(attempt);
    if (result == null) {
      stopChain("retry attempt aborted with an unexpected error");
      return;
    }
    if (result.success()) {
      log.info("[LlmProviderRefreshRetrier] Retry attempt {}/{} succeeded", attempt, MAX_ATTEMPTS);
      stopChain("retry succeeded");
      return;
    }
    if (!result.retryable()) {
      stopChain("retry failed with a non-retryable error");
      return;
    }
    if (attempt >= MAX_ATTEMPTS) {
      expireGrace();
      stopChain("retry attempts exhausted");
      return;
    }
    scheduleAttempt(token, attempt + 1);
  }

  private CacheStatus readStatus() {
    try {
      return registry.status();
    } catch (RuntimeException e) {
      log.error("[LlmProviderRefreshRetrier] Failed to read cache status before retry", e);
      return null;
    }
  }

  /**
   * 重试路径上的兜底：{@code refresh()} 自己会吞 RuntimeException，这里再拦住其它 Throwable，
   * 保证重试线程不会因为异常直接终止。
   */
  private RefreshResult refreshQuietly(int attempt) {
    try {
      return registry.refresh();
    } catch (Throwable t) {
      log.error("[LlmProviderRefreshRetrier] Retry attempt {}/{} threw unexpectedly",
          attempt, MAX_ATTEMPTS, t);
      return null;
    }
  }

  private void expireGrace() {
    try {
      registry.markGraceExpired();
      log.error("[LlmProviderRefreshRetrier] Retry attempts exhausted after {}, cache is now {}",
          MAX_ATTEMPTS, CacheState.EXPIRED);
    } catch (RuntimeException e) {
      log.error("[LlmProviderRefreshRetrier] Failed to mark grace as expired after exhausted retries", e);
    }
  }

  /** 结束当前重试链：自增令牌让在途任务失效，并允许下一条链开始。 */
  private void stopChain(String reason) {
    chainId.incrementAndGet();
    chainInFlight.set(false);
    log.debug("[LlmProviderRefreshRetrier] Retry chain stopped: {}", reason);
  }

  @PreDestroy
  public void shutdown() {
    chainInFlight.set(false);
    chainId.incrementAndGet();
    executor.shutdownNow();
    log.info("[LlmProviderRefreshRetrier] Retry executor shut down");
  }
}
