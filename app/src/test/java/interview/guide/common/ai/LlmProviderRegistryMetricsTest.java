package interview.guide.common.ai;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("LLM Provider 缓存刷新可观测性")
class LlmProviderRegistryMetricsTest {

  /** 指标名和标签是运维侧告警规则的契约：改名必须同步改这里。 */
  private static final String FAILURE_METRIC = "app.ai.llm_provider.refresh.failures";
  private static final String STATE_METRIC = "app.ai.llm_provider.cache.state";
  private static final String FAILURE_TYPE_TAG = "type";
  private static final String RECOVERABLE = "recoverable";
  private static final String NON_RECOVERABLE = "non_recoverable";
  private static final String RETRY_EXHAUSTED = "retry_exhausted";

  private LlmProviderProperties properties;
  private LlmProviderRepository providerRepository;
  private LlmGlobalSettingRepository globalSettingRepository;
  private ApiKeyEncryptionService encryptionService;
  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() {
    properties = mock(LlmProviderProperties.class);
    providerRepository = mock(LlmProviderRepository.class);
    globalSettingRepository = mock(LlmGlobalSettingRepository.class);
    encryptionService = mock(ApiKeyEncryptionService.class);
    meterRegistry = new SimpleMeterRegistry();
    when(encryptionService.decrypt("nonce", "cipher")).thenReturn("key-1");
    when(globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID))
        .thenReturn(Optional.of(globalSetting("p1")));
  }

  @Test
  @DisplayName("可恢复失败计一次 recoverable")
  void recoverableFailureIsCounted() {
    givenProviders(entity("p1", "model-v1"));
    LlmProviderRegistry registry = newRegistry(meterRegistry);
    assertTrue(registry.refresh().success());

    when(providerRepository.findByEnabledTrueOrderByIdAsc())
        .thenThrow(new QueryTimeoutException("read timed out"));
    assertFalse(registry.refresh().success());

    assertEquals(1.0, failureCount(RECOVERABLE), "可恢复失败应计一次");
    assertEquals(0.0, failureCount(NON_RECOVERABLE), "可恢复失败不应计入不可恢复");
    assertNotNull(registry.status().lastFailureReason());
  }

  @Test
  @DisplayName("不可恢复失败计一次 non_recoverable")
  void nonRecoverableFailureIsCounted() {
    givenProviders(entity("p1", "model-v1"));
    LlmProviderRegistry registry = newRegistry(meterRegistry);
    assertTrue(registry.refresh().success());

    // 默认 provider 消失：配置本身有问题，重试没有意义
    givenProviders(entity("p2", "model-v2"));
    assertFalse(registry.refresh().success());

    assertEquals(1.0, failureCount(NON_RECOVERABLE), "不可恢复失败应计一次");
    assertEquals(0.0, failureCount(RECOVERABLE), "不可恢复失败不应计入可恢复");
  }

  @Test
  @DisplayName("重试耗尽计一次 retry_exhausted，且缓存进入 EXPIRED")
  void retryExhaustionIsCounted() {
    givenProviders(entity("p1", "model-v1"));
    LlmProviderRegistry registry = newRegistry(meterRegistry);
    assertTrue(registry.refresh().success());

    when(providerRepository.findByEnabledTrueOrderByIdAsc())
        .thenThrow(new QueryTimeoutException("read timed out"));
    assertFalse(registry.refresh().success());
    registry.markGraceExpired();

    assertEquals(1.0, failureCount(RETRY_EXHAUSTED), "重试耗尽应单独计一次");
    assertEquals(1.0, failureCount(RECOVERABLE), "耗尽前那次可恢复失败仍应保留");
    assertEquals(0.0, failureCount(NON_RECOVERABLE));
    assertEquals(LlmProviderRegistry.CacheState.EXPIRED, registry.status().state());
  }

  @Test
  @DisplayName("从未失败时标记宽限已过不计 retry_exhausted")
  void exhaustedCounterIgnoresSpuriousExpiry() {
    givenProviders(entity("p1", "model-v1"));
    LlmProviderRegistry registry = newRegistry(meterRegistry);
    assertTrue(registry.refresh().success());

    registry.markGraceExpired();

    assertEquals(0.0, failureCount(RETRY_EXHAUSTED), "没有待恢复的失败时不应计入重试耗尽");
    assertEquals(LlmProviderRegistry.CacheState.READY, registry.status().state());
  }

  @Test
  @DisplayName("重复标记宽限已过只计一次 retry_exhausted")
  void repeatedGraceExpiryCountsOnce() {
    givenProviders(entity("p1", "model-v1"));
    LlmProviderRegistry registry = newRegistry(meterRegistry);
    assertTrue(registry.refresh().success());

    when(providerRepository.findByEnabledTrueOrderByIdAsc())
        .thenThrow(new QueryTimeoutException("read timed out"));
    assertFalse(registry.refresh().success());

    registry.markGraceExpired();
    registry.markGraceExpired();
    registry.markGraceExpired();

    assertEquals(1.0, failureCount(RETRY_EXHAUSTED), "一次失败只应算一次重试耗尽");
  }

  @Test
  @DisplayName("缓存状态仪表：READY=0、REFRESHING=1、STALE=2、EXPIRED=3")
  void cacheStateIsExposedAsGauge() {
    givenProviders(entity("p1", "model-v1"));
    LlmProviderRegistry registry = newRegistry(meterRegistry);
    assertTrue(registry.refresh().success());
    assertEquals(0.0, stateGauge(), "READY 应映射为 0");

    when(providerRepository.findByEnabledTrueOrderByIdAsc())
        .thenThrow(new QueryTimeoutException("read timed out"));
    assertFalse(registry.refresh().success());
    assertEquals(2.0, stateGauge(), "STALE 应映射为 2");

    registry.markGraceExpired();
    assertEquals(3.0, stateGauge(), "EXPIRED 应映射为 3");
  }

  @Test
  @DisplayName("刷新中状态仪表为 REFRESHING=1，成功后回到 READY=0")
  void refreshingStateIsMapped() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    givenProviders(entity("p1", "model-v1"));
    when(encryptionService.decrypt("nonce", "cipher")).thenAnswer(invocation -> {
      entered.countDown();
      assertTrue(release.await(5, TimeUnit.SECONDS));
      return "key-1";
    });

    LlmProviderRegistry registry = newRegistry(meterRegistry);
    ExecutorService executor = newSingleThreadPool();
    try {
      Future<LlmProviderRegistry.RefreshResult> refresh = executor.submit(registry::refresh);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertEquals(1.0, stateGauge(), "REFRESHING 应映射为 1");

      release.countDown();
      assertTrue(refresh.get(5, TimeUnit.SECONDS).success());
      assertEquals(0.0, stateGauge(), "刷新成功后回到 READY=0");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("没有 MeterRegistry 时失败路径不抛异常")
  void failurePathsWorkWithoutMeterRegistry() {
    givenProviders(entity("p1", "model-v1"));
    LlmProviderRegistry registry = newRegistry(null);
    assertTrue(registry.refresh().success());

    when(providerRepository.findByEnabledTrueOrderByIdAsc())
        .thenThrow(new QueryTimeoutException("read timed out"));
    LlmProviderRegistry.RefreshResult result = assertDoesNotThrow(registry::refresh);

    assertFalse(result.success());
    assertTrue(result.retryable());
    assertEquals(LlmProviderRegistry.CacheState.STALE, registry.status().state());
    assertDoesNotThrow(registry::markGraceExpired);
    assertEquals(LlmProviderRegistry.CacheState.EXPIRED, registry.status().state());
  }

  @Test
  @DisplayName("每次失败输出一行 ERROR，包含 provider id、失败类型、状态和剩余宽限")
  void failureLogsProviderTypeStateAndGrace() {
    givenProviders(entity("p1", "model-v1"));
    LlmProviderRegistry registry = newRegistry(meterRegistry);
    assertTrue(registry.refresh().success());

    // p1 的 model 被清空：校验落在单个 provider 上，此时 provider id 是可得的
    givenProviders(entity("p1", null));

    ListAppender<ILoggingEvent> appender = attachAppender();
    List<ILoggingEvent> errors;
    try {
      assertFalse(registry.refresh().success());
      errors = appender.list.stream()
          .filter(event -> event.getLevel() == Level.ERROR)
          .toList();
    } finally {
      detachAppender(appender);
    }

    assertEquals(1, errors.size(), "一次失败只记一行 ERROR");
    String message = errors.getFirst().getFormattedMessage();
    assertTrue(message.contains("p1"), "日志应包含 provider id：" + message);
    assertTrue(message.contains(NON_RECOVERABLE), "日志应包含失败类型：" + message);
    assertTrue(message.contains("STALE"), "日志应包含当前状态：" + message);
    assertTrue(message.contains("graceRemainingMs"), "日志应包含剩余宽限时间：" + message);
    assertNotNull(errors.getFirst().getThrowableProxy(), "异常对象应作为最后一个参数传入");
  }

  @Test
  @DisplayName("兜底路径的意外异常也按可恢复/不可恢复计入失败指标")
  void escapedFailureIsCounted() {
    givenProviders(entity("p1", "model-v1"));
    LlmProviderRegistry registry = newRegistry(meterRegistry);
    assertTrue(registry.refresh().success());

    assertDoesNotThrow(() -> registry.recordEscapedRefreshFailure(new IllegalStateException("boom")));
    assertDoesNotThrow(() -> registry.recordEscapedRefreshFailure(
        new QueryTimeoutException("read timed out")));

    assertEquals(1.0, failureCount(NON_RECOVERABLE), "参数类异常应计入不可恢复");
    assertEquals(1.0, failureCount(RECOVERABLE), "读库超时应计入可恢复");
  }

  private LlmProviderRegistry newRegistry(MeterRegistry registry) {
    return new LlmProviderRegistry(
        properties,
        providerRepository,
        globalSettingRepository,
        encryptionService,
        null,
        null,
        null,
        Clock.systemUTC(),
        registry);
  }

  private void givenProviders(LlmProviderEntity... providers) {
    when(providerRepository.findByEnabledTrueOrderByIdAsc()).thenReturn(List.of(providers));
  }

  private double failureCount(String type) {
    Counter counter = meterRegistry.find(FAILURE_METRIC)
        .tag(FAILURE_TYPE_TAG, type)
        .counter();
    return counter == null ? 0.0 : counter.count();
  }

  private double stateGauge() {
    return meterRegistry.get(STATE_METRIC).gauge().value();
  }

  private static ListAppender<ILoggingEvent> attachAppender() {
    Logger logger = (Logger) LoggerFactory.getLogger(LlmProviderRegistry.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  private static void detachAppender(ListAppender<ILoggingEvent> appender) {
    Logger logger = (Logger) LoggerFactory.getLogger(LlmProviderRegistry.class);
    logger.detachAppender(appender);
    appender.stop();
  }

  /**
   * 并发测试用的显式线程池：守护线程 + 有界队列，替代 {@code Executors.newSingleThreadExecutor()}。
   */
  private static ExecutorService newSingleThreadPool() {
    return new ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(64),
        runnable -> {
          Thread thread = new Thread(runnable, "metrics-test-worker");
          thread.setDaemon(true);
          return thread;
        });
  }

  private static LlmGlobalSettingEntity globalSetting(String providerId) {
    LlmGlobalSettingEntity setting = new LlmGlobalSettingEntity();
    setting.setDefaultChatProviderId(providerId);
    setting.setDefaultEmbeddingProviderId(providerId);
    return setting;
  }

  private static LlmProviderEntity entity(String id, String model) {
    return LlmProviderEntity.builder()
        .id(id)
        .baseUrl("http://localhost:1234/v1")
        .apiKeyNonce("nonce")
        .apiKeyCiphertext("cipher")
        .model(model)
        .embeddingModel("text-embedding-v3")
        .embeddingDimensions(1024)
        .supportsEmbedding(true)
        .temperature(0.2)
        .enabled(true)
        .builtin(true)
        .build();
  }
}
