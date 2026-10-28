package interview.guide.common.ai;

import interview.guide.common.ai.LlmProviderRegistry.CacheState;
import interview.guide.common.ai.LlmProviderRegistry.CacheStatus;
import interview.guide.common.ai.LlmProviderRegistry.RefreshResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LLM Provider 刷新重试调度器")
class LlmProviderRefreshRetrierTest {

  @Mock
  private LlmProviderRegistry registry;

  private ManualScheduler scheduler;
  private LlmProviderRefreshRetrier retrier;

  @BeforeEach
  void setUp() {
    scheduler = new ManualScheduler();
    retrier = new LlmProviderRefreshRetrier(registry, scheduler);
  }

  @AfterEach
  void tearDown() {
    retrier.shutdown();
  }

  @Test
  @DisplayName("可恢复失败会安排一次重试，首次退避 1 秒")
  void schedulesRetryForRetryableFailure() {
    retrier.onRefreshResult(retryableFailure());

    assertEquals(1, scheduler.pendingCount(), "可恢复失败应安排一次重试");
    assertEquals(List.of(1000L), scheduler.delays());
  }

  @Test
  @DisplayName("成功刷新后不再安排剩余重试")
  void successStopsRemainingRetries() {
    when(registry.status()).thenReturn(stale(true));
    when(registry.refresh()).thenReturn(retryableFailure(), success());

    retrier.onRefreshResult(retryableFailure());
    assertTrue(scheduler.runNext(), "第一次重试应已排队");

    assertTrue(scheduler.runNext(), "第一次重试失败后应安排第二次");
    assertEquals(List.of(1000L, 2000L), scheduler.delays(), "重试间隔应递增");

    assertEquals(0, scheduler.pendingCount(), "重试成功后不应再排队");
    verify(registry, times(2)).refresh();
    verify(registry, never()).markGraceExpired();
  }

  @Test
  @DisplayName("两次尝试之间外部刷新成功时，在途重试被作废")
  void newerSuccessInvalidatesPendingRetry() {
    retrier.onRefreshResult(retryableFailure());
    assertEquals(1, scheduler.pendingCount());

    // 另一次保存配置触发的刷新成功了：剩余重试必须作废
    retrier.onRefreshResult(success());
    scheduler.runNext();

    assertEquals(0, scheduler.pendingCount());
    verify(registry, never()).refresh();
    verify(registry, never()).markGraceExpired();
  }

  @Test
  @DisplayName("不可恢复失败不安排任何重试")
  void nonRetryableFailureSchedulesNothing() {
    retrier.onRefreshResult(nonRetryableFailure());

    assertEquals(0, scheduler.pendingCount(), "不可恢复失败不应安排重试");
    verify(registry, never()).refresh();
    verify(registry, never()).markGraceExpired();
  }

  @Test
  @DisplayName("重试次数耗尽后标记宽限已过（进入 EXPIRED）")
  void exhaustionMarksGraceExpired() {
    when(registry.status()).thenReturn(stale(true));
    when(registry.refresh()).thenReturn(retryableFailure());

    retrier.onRefreshResult(retryableFailure());
    assertTrue(scheduler.runNext(), "第 1 次重试");
    assertTrue(scheduler.runNext(), "第 2 次重试");
    assertTrue(scheduler.runNext(), "第 3 次重试");

    assertEquals(List.of(1000L, 2000L, 4000L), scheduler.delays(), "最多 3 次重试且间隔递增");
    assertEquals(0, scheduler.pendingCount(), "次数耗尽后不应再排队");
    verify(registry, times(3)).refresh();
    verify(registry).markGraceExpired();
    assertFalse(scheduler.isShutdown(), "重试耗尽不等于关停执行器");
  }

  @Test
  @DisplayName("重试过程中失败变为不可恢复时立即停止")
  void stopsWhenFailureTurnsNonRetryable() {
    when(registry.status()).thenReturn(stale(true), stale(false));
    when(registry.refresh()).thenReturn(retryableFailure());

    retrier.onRefreshResult(retryableFailure());
    assertTrue(scheduler.runNext(), "第 1 次重试仍可恢复，应安排下一次");
    assertEquals(1, scheduler.pendingCount());

    assertTrue(scheduler.runNext(), "第 2 次尝试前读到不可恢复，直接停止");

    assertEquals(0, scheduler.pendingCount(), "失败变为不可恢复后不应再排队");
    verify(registry, times(1)).refresh();
    verify(registry, never()).markGraceExpired();
  }

  @Test
  @DisplayName("重试前发现缓存已是最新（READY）时直接停止，不再刷新")
  void stopsWhenCacheAlreadyCurrent() {
    when(registry.status()).thenReturn(ready());

    retrier.onRefreshResult(retryableFailure());
    assertTrue(scheduler.runNext());

    assertEquals(0, scheduler.pendingCount());
    verify(registry, never()).refresh();
  }

  @Test
  @DisplayName("重试时 registry 抛出 Error 也不外抛、不再继续排队")
  void swallowThrowableDuringRetry() {
    when(registry.status()).thenReturn(stale(true));
    when(registry.refresh()).thenThrow(new AssertionError("boom"));

    retrier.onRefreshResult(retryableFailure());

    assertTrue(scheduler.runNext(), "重试任务本身不应把 Error 抛给执行器");
    assertEquals(0, scheduler.pendingCount(), "出现 Error 视为不可恢复，停止重试");
    verify(registry, never()).markGraceExpired();
  }

  @Test
  @DisplayName("关闭时停掉执行器，后续刷新结果不再安排重试")
  void shutdownStopsExecutor() {
    retrier.shutdown();
    retrier.onRefreshResult(retryableFailure());

    assertTrue(scheduler.isShutdown(), "关闭时应关停执行器");
  }

  private static RefreshResult success() {
    return LlmProviderRegistry.RefreshResult.success(Instant.EPOCH);
  }

  private static RefreshResult retryableFailure() {
    return LlmProviderRegistry.RefreshResult.failure("read timed out", true);
  }

  private static RefreshResult nonRetryableFailure() {
    return LlmProviderRegistry.RefreshResult.failure("provider disabled", false);
  }

  private static CacheStatus ready() {
    return new CacheStatus(CacheState.READY, Instant.EPOCH, null, 0L, false);
  }

  private static CacheStatus stale(boolean retryable) {
    return new CacheStatus(CacheState.STALE, Instant.EPOCH, "read timed out", 42L, retryable);
  }

  /**
   * 手动驱动的调度器：记录每次 {@code schedule} 的延迟，由测试显式执行任务，
   * 因此重试测试不依赖任何真实等待。
   */
  private static final class ManualScheduler extends ScheduledThreadPoolExecutor {

    private final Deque<Runnable> queued = new ArrayDeque<>();
    private final List<Long> delays = new ArrayList<>();

    private ManualScheduler() {
      super(1, runnable -> {
        Thread thread = new Thread(runnable, "manual-retry");
        thread.setDaemon(true);
        return thread;
      });
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      queued.add(command);
      delays.add(unit.toMillis(delay));
      return null;
    }

    private int pendingCount() {
      return queued.size();
    }

    private List<Long> delays() {
      return List.copyOf(delays);
    }

    private boolean runNext() {
      Runnable next = queued.poll();
      if (next == null) {
        return false;
      }
      next.run();
      return true;
    }
  }
}
