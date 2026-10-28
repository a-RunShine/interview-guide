package interview.guide.common.ai;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

import java.lang.reflect.Field;
import java.net.ConnectException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LLM Provider Registry Test")
class LlmProviderRegistryTest {

    @Mock
    private LlmProviderProperties properties;

    @Mock
    private org.springframework.ai.model.tool.ToolCallingManager toolCallingManager;

    @Mock
    private io.micrometer.observation.ObservationRegistry observationRegistry;

    @InjectMocks
    private LlmProviderRegistry registry;

    @BeforeEach
    void setUp() {
        // No need to manually create registry when using @InjectMocks
    }

    @Test
    @DisplayName("Successfully get ChatClient for a valid provider")
    void testGetChatClient_Success() {
        // Given
        String providerId = "test-provider";
        ProviderConfig config = new ProviderConfig();
        config.setBaseUrl("http://localhost:1234/v1");
        config.setApiKey("test-key");
        config.setModel("test-model");

        Map<String, ProviderConfig> providers = new HashMap<>();
        providers.put(providerId, config);

        when(properties.getProviders()).thenReturn(providers);

        // When
        ChatClient client = registry.getChatClient(providerId);

        // Then
        assertNotNull(client);
    }

    @Test
    @DisplayName("Verify that ChatClients are cached")
    void testGetChatClient_Caching() {
        // Given
        String providerId = "test-provider";
        ProviderConfig config = new ProviderConfig();
        config.setBaseUrl("http://localhost:1234/v1");
        config.setApiKey("test-key");
        config.setModel("test-model");

        Map<String, ProviderConfig> providers = new HashMap<>();
        providers.put(providerId, config);

        when(properties.getProviders()).thenReturn(providers);

        // When
        ChatClient client1 = registry.getChatClient(providerId);
        ChatClient client2 = registry.getChatClient(providerId);

        // Then
        assertSame(client1, client2, "Clients should be cached and returned as the same instance");
    }

    @Test
    @DisplayName("不同 ChatClient 配方共享同一个 provider 级 ChatModel")
    void chatClientVariantsShareProviderChatModel() {
        String providerId = "test-provider";
        ProviderConfig config = new ProviderConfig();
        config.setBaseUrl("http://localhost:1234/v1");
        config.setApiKey("test-key");
        config.setModel("test-model");

        Map<String, ProviderConfig> providers = new HashMap<>();
        providers.put(providerId, config);

        when(properties.getProviders()).thenReturn(providers);

        ChatClient defaultClient = registry.getChatClient(providerId);
        ChatClient plainClient = registry.getPlainChatClient(providerId);
        ChatClient voiceClient = registry.getVoiceChatClient(providerId);

        assertNotNull(defaultClient);
        assertNotNull(plainClient);
        assertNotNull(voiceClient);
        assertNotSame(defaultClient, plainClient, "Default and plain clients should keep advisor recipes isolated");
        assertNotSame(defaultClient, voiceClient, "Default and voice clients should keep advisor recipes isolated");
        verify(properties, times(1)).getProviders();
    }

    @Test
    @DisplayName("Throw exception for unknown provider")
    void testGetChatClient_UnknownProvider() {
        // Given
        when(properties.getProviders()).thenReturn(new HashMap<>());

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> registry.getChatClient("unknown"));
    }

    @Test
    @DisplayName("Successfully get default ChatClient")
    void testGetDefaultChatClient() {
        // Given
        String defaultProviderId = "dashscope";
        ProviderConfig config = new ProviderConfig();
        config.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        config.setApiKey("dashscope-key");
        config.setModel("qwen3.5-flash");

        Map<String, ProviderConfig> providers = new HashMap<>();
        providers.put(defaultProviderId, config);

        when(properties.getDefaultProvider()).thenReturn(defaultProviderId);
        when(properties.getProviders()).thenReturn(providers);

        // When
        ChatClient client = registry.getDefaultChatClient();

        // Then
        assertNotNull(client);
    }

    @Test
    @DisplayName("reload clears cache and allows re-creation")
    void testReload() {
        String providerId = "test-provider";
        ProviderConfig config = new ProviderConfig();
        config.setBaseUrl("http://localhost:1234/v1");
        config.setApiKey("test-key");
        config.setModel("test-model");

        Map<String, ProviderConfig> providers = new HashMap<>();
        providers.put(providerId, config);

        when(properties.getProviders()).thenReturn(providers);

        ChatClient client1 = registry.getChatClient(providerId);
        registry.refresh();
        ChatClient client2 = registry.getChatClient(providerId);

        assertNotNull(client1);
        assertNotNull(client2);
        assertNotSame(client1, client2, "After reload, new client should be created");
    }

    @Nested
    @DisplayName("getChatClientOrDefault 回退逻辑")
    class GetChatClientOrDefault {

        private ProviderConfig defaultConfig;
        private ProviderConfig explicitConfig;
        private Map<String, ProviderConfig> providers;

        @BeforeEach
        void setUpProviders() {
            defaultConfig = new ProviderConfig();
            defaultConfig.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
            defaultConfig.setApiKey("default-key");
            defaultConfig.setModel("qwen3.5-flash");

            explicitConfig = new ProviderConfig();
            explicitConfig.setBaseUrl("https://api.moonshot.cn/v1");
            explicitConfig.setApiKey("kimi-key");
            explicitConfig.setModel("kimi-latest");

            providers = new HashMap<>();
            providers.put("dashscope", defaultConfig);
            providers.put("kimi", explicitConfig);

            lenient().when(properties.getDefaultProvider()).thenReturn("dashscope");
            when(properties.getProviders()).thenReturn(providers);
        }

        @Test
        @DisplayName("非 null providerId 委托给 getChatClient")
        void explicitProvider() {
            ChatClient client = registry.getChatClientOrDefault("kimi");

            assertNotNull(client);
            // Should be cached under "kimi" key, not default
            assertSame(client, registry.getChatClient("kimi"));
        }

        @Test
        @DisplayName("null providerId 回退到默认 provider")
        void nullProviderFallsBackToDefault() {
            ChatClient client = registry.getChatClientOrDefault(null);

            assertNotNull(client);
            assertSame(client, registry.getChatClient("dashscope"));
        }

        @Test
        @DisplayName("空白 providerId 回退到默认 provider")
        void blankProviderFallsBackToDefault() {
            ChatClient client = registry.getChatClientOrDefault("   ");

            assertNotNull(client);
            assertSame(client, registry.getChatClient("dashscope"));
        }

        @Test
        @DisplayName("default providerId 作为默认 provider 别名")
        void defaultAliasFallsBackToDefault() {
            ChatClient client = registry.getChatClientOrDefault("default");

            assertNotNull(client);
            assertSame(client, registry.getChatClient("dashscope"));
        }
    }

    @Test
    @org.junit.jupiter.api.Disabled(
        "Pending: ProviderConfig.enabled flag + PROVIDER_DISABLED error code not yet implemented"
    )
    @DisplayName("getChatClient 对 disabled provider 应抛 PROVIDER_DISABLED（占位，待实现）")
    void testGetChatClient_disabledProvider() {
        // 占位测试：等 ProviderConfig 补齐 enabled 字段 + Registry 实现禁用分支后恢复断言。
        // 不写任何 mock，避免 Mockito 严格模式把占位记为异常失败。
    }

    @Test
    @DisplayName("默认 Provider ID 只查询一次数据库，reload 后重新查询")
    void defaultProviderIdIsCachedUntilReload() {
        // Given：模拟 DB 里没有全局设置，让它回退到 properties 的默认 provider
        LlmGlobalSettingRepository globalRepo = mock(LlmGlobalSettingRepository.class);
        when(globalRepo.findById(LlmGlobalSettingEntity.SINGLETON_ID)).thenReturn(Optional.empty());
        when(properties.getDefaultProvider()).thenReturn("test-provider");

        ProviderConfig config = new ProviderConfig();
        config.setBaseUrl("http://localhost:1234/v1");
        config.setApiKey("test-key");
        config.setModel("test-model");
        when(properties.getProviders()).thenReturn(Map.of("test-provider", config));

        LlmProviderRegistry registry =
            new LlmProviderRegistry(properties, null, globalRepo, null, null, null, null);

        // When：连续调用两次默认 ChatClient
        registry.getDefaultChatClient();
        registry.getDefaultChatClient();

        // Then：第一次查了 DB，第二次命中缓存，所以只查了 1 次
        verify(globalRepo, times(1)).findById(LlmGlobalSettingEntity.SINGLETON_ID);

        // When：清缓存后再调用一次
        registry.refresh();
        registry.getDefaultChatClient();

        // Then：reload 之后会重新查一次 DB，总共 2 次
        verify(globalRepo, times(2)).findById(LlmGlobalSettingEntity.SINGLETON_ID);
    }

    @Test
    @DisplayName("reload 与 getChatClient 并发时，新一代客户端使用 reload 之后的模型")
    void reloadDoesNotMixChatClientWithPreviousChatModel() throws Exception {
        String providerId = "test-provider";
        ProviderConfig config = new ProviderConfig();
        config.setBaseUrl("http://localhost:1234/v1");
        config.setApiKey("test-key");
        config.setModel("model-0");
        when(properties.getProviders()).thenReturn(Map.of(providerId, config));

        registry.getChatClient(providerId);

        AtomicBoolean stop = new AtomicBoolean(false);
        ExecutorService readers = Executors.newFixedThreadPool(8);
        List<Future<?>> inFlight = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            inFlight.add(readers.submit(() -> {
                while (!stop.get()) {
                    assertNotNull(modelOf(registry.getChatClient(providerId)));
                }
            }));
        }

        int reloads = 200;
        for (int i = 1; i <= reloads; i++) {
            config.setModel("model-" + i);
            registry.refresh();
        }
        stop.set(true);
        readers.shutdown();
        assertTrue(readers.awaitTermination(10, TimeUnit.SECONDS));
        for (Future<?> future : inFlight) {
            future.get(5, TimeUnit.SECONDS);
        }

        assertEquals("model-" + reloads, modelOf(registry.getChatClient(providerId)));
    }

    @Test
    @DisplayName("reload 期间尚未写回的默认 provider 不会污染下一代")
    void reloadDropsInFlightDefaultProviderId() throws Exception {
        LlmGlobalSettingRepository globalRepo = mock(LlmGlobalSettingRepository.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(globalRepo.findById(LlmGlobalSettingEntity.SINGLETON_ID)).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return Optional.of(globalSetting("provider-a"));
            }
            return Optional.of(globalSetting("provider-b"));
        });

        ProviderConfig providerA = new ProviderConfig();
        providerA.setBaseUrl("http://localhost:1234/v1");
        providerA.setApiKey("key-a");
        providerA.setModel("model-a");
        ProviderConfig providerB = new ProviderConfig();
        providerB.setBaseUrl("http://localhost:1234/v1");
        providerB.setApiKey("key-b");
        providerB.setModel("model-b");
        when(properties.getProviders()).thenReturn(Map.of("provider-a", providerA, "provider-b", providerB));

        LlmProviderRegistry isolated =
            new LlmProviderRegistry(properties, null, globalRepo, null, null, null, null);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ChatClient> inFlight = executor.submit(isolated::getDefaultChatClient);
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            isolated.refresh();
            release.countDown();

            assertEquals("model-a", modelOf(inFlight.get(5, TimeUnit.SECONDS)));
            assertEquals("model-b", modelOf(isolated.getDefaultChatClient()));
            verify(globalRepo, times(2)).findById(LlmGlobalSettingEntity.SINGLETON_ID);
        } finally {
            executor.shutdownNow();
        }
    }

    @Nested
    @DisplayName("refresh 先离线校验再整代替换")
    class Refresh {

        private LlmProviderRepository providerRepository;
        private LlmGlobalSettingRepository globalSettingRepository;
        private ApiKeyEncryptionService encryptionService;

        @BeforeEach
        void setUpCollaborators() {
            providerRepository = mock(LlmProviderRepository.class);
            globalSettingRepository = mock(LlmGlobalSettingRepository.class);
            encryptionService = mock(ApiKeyEncryptionService.class);
        }

        @Test
        @DisplayName("校验成功后新一代立即生效，请求不再回读数据库")
        void successAppliesImmediately() {
            when(providerRepository.findByEnabledTrueOrderByIdAsc())
                .thenReturn(List.of(entity("p1", "model-v1")));
            when(encryptionService.decrypt("nonce", "cipher")).thenReturn("key-1");
            when(globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(globalSetting("p1", "p1")));

            LlmProviderRegistry registry = newRegistry();

            assertTrue(registry.refresh().success(), "校验成功应返回成功结果");
            assertEquals(LlmProviderRegistry.CacheState.READY, registry.status().state());
            assertEquals("model-v1", modelOf(registry.getChatClient("p1")));

            // 校验通过后请求只用校验时读到的那一代，不再回查数据库
            verify(providerRepository, never()).findById(anyString());
        }

        private LlmGlobalSettingEntity globalSetting(String chatProviderId, String embeddingProviderId) {
            LlmGlobalSettingEntity setting = new LlmGlobalSettingEntity();
            setting.setDefaultChatProviderId(chatProviderId);
            setting.setDefaultEmbeddingProviderId(embeddingProviderId);
            return setting;
        }

        @Test
        @DisplayName("可恢复失败保留旧值，宽限期内请求照常可用")
        void retryableFailureKeepsOldGenerationWithinGrace() {
            when(providerRepository.findByEnabledTrueOrderByIdAsc())
                .thenReturn(List.of(entity("p1", "model-v1")));
            when(encryptionService.decrypt("nonce", "cipher")).thenReturn("key-1");
            when(globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(globalSetting("p1", "p1")));

            LlmProviderRegistry registry = newRegistry();
            assertTrue(registry.refresh().success());
            ChatClient before = registry.getChatClient("p1");

            // 数据库读超时：可恢复失败
            when(providerRepository.findByEnabledTrueOrderByIdAsc())
                .thenThrow(new QueryTimeoutException("read timed out"));

            LlmProviderRegistry.RefreshResult result = registry.refresh();

            assertFalse(result.success(), "校验失败不应报告成功");
            assertTrue(result.retryable(), "读库超时应判为可恢复");
            assertEquals(LlmProviderRegistry.CacheState.STALE, registry.status().state());
            assertSame(before, registry.getChatClient("p1"), "宽限期内应继续服务旧一代");
            assertTrue(registry.status().graceSecondsRemaining() > 0);
        }

        @Test
        @DisplayName("宽限期结束后请求抛 PROVIDER_UNAVAILABLE 业务异常")
        void graceExpiryThrowsBusinessError() {
            when(providerRepository.findByEnabledTrueOrderByIdAsc())
                .thenReturn(List.of(entity("p1", "model-v1")));
            when(encryptionService.decrypt("nonce", "cipher")).thenReturn("key-1");
            when(globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(globalSetting("p1", "p1")));

            MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
            LlmProviderRegistry registry = newRegistry(clock);
            assertTrue(registry.refresh().success());

            when(providerRepository.findByEnabledTrueOrderByIdAsc())
                .thenThrow(new QueryTimeoutException("read timed out"));
            clock.advance(Duration.ofSeconds(1));
            assertFalse(registry.refresh().success());
            assertEquals(LlmProviderRegistry.CacheState.STALE, registry.status().state());

            // 宽限期（默认 60s）内仍可服务
            clock.advance(Duration.ofSeconds(30));
            assertNotNull(registry.getChatClient("p1"));

            // 超过宽限期后请求被拒绝
            clock.advance(Duration.ofSeconds(40));
            assertEquals(LlmProviderRegistry.CacheState.EXPIRED, registry.status().state());
            BusinessException ex = assertThrows(
                BusinessException.class, () -> registry.getChatClient("p1"));
            assertEquals(ErrorCode.PROVIDER_UNAVAILABLE.getCode(), ex.getCode());
        }

        @Test
        @DisplayName("不可恢复失败（provider 被禁用）不进入重试，且保留旧值")
        void nonRetryableFailureIsNotRetryable() {
            when(providerRepository.findByEnabledTrueOrderByIdAsc())
                .thenReturn(List.of(entity("p1", "model-v1")));
            when(encryptionService.decrypt("nonce", "cipher")).thenReturn("key-1");
            when(globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(globalSetting("p1", "p1")));

            LlmProviderRegistry registry = newRegistry();
            assertTrue(registry.refresh().success());
            ChatClient before = registry.getChatClient("p1");

            // 默认 provider 消失 / 被禁用：不可恢复
            when(providerRepository.findByEnabledTrueOrderByIdAsc())
                .thenReturn(List.of(entity("p2", "model-v2")));

            LlmProviderRegistry.RefreshResult result = registry.refresh();

            assertFalse(result.success());
            assertFalse(result.retryable(), "provider 被禁用应判为不可恢复，不安排重试");
            assertFalse(registry.status().retryable());
            assertSame(before, registry.getChatClient("p1"), "旧值仍保留，宽限期内继续服务");
        }

        @Test
        @DisplayName("密钥解密失败判为不可恢复")
        void decryptionFailureIsNotRetryable() {
            when(providerRepository.findByEnabledTrueOrderByIdAsc())
                .thenReturn(List.of(entity("p1", "model-v1")));
            when(encryptionService.decrypt("nonce", "cipher"))
                .thenThrow(new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED, "解密失败"));

            LlmProviderRegistry.RefreshResult result = newRegistry().refresh();

            assertFalse(result.success());
            assertFalse(result.retryable(), "密钥解密失败应判为不可恢复");
        }

        @Test
        @DisplayName("并发请求期间切换不会看到混代的客户端")
        void concurrentRequestsNeverSeeMixedGenerations() throws Exception {
            // 第 n 代：a.model=a-n，b.model=b-n，默认 provider 在 a / b 之间交替。
            // 一次请求拿到的模型形如 "<provider>-<n>"，只有默认 provider 与模型来自同一代才自洽。
            when(providerRepository.findByEnabledTrueOrderByIdAsc())
                .thenReturn(List.of(entity("a", "a-0"), entity("b", "b-0")));
            when(encryptionService.decrypt("nonce", "cipher")).thenReturn("key-1");
            when(globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID))
                .thenAnswer(invocation -> Optional.of(globalSetting("a", "a")));

            LlmProviderRegistry registry = newRegistry();
            assertTrue(registry.refresh().success());

            AtomicBoolean stop = new AtomicBoolean(false);
            AtomicReference<String> mixed = new AtomicReference<>();
            ExecutorService readers = Executors.newFixedThreadPool(8);
            List<Future<?>> inFlight = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                inFlight.add(readers.submit(() -> {
                    while (!stop.get()) {
                        String model = modelOf(registry.getDefaultChatClient());
                        int separator = model.lastIndexOf('-');
                        String provider = model.substring(0, separator);
                        int generation = Integer.parseInt(model.substring(separator + 1));
                        String expectedDefault = generation % 2 == 0 ? "a" : "b";
                        if (!expectedDefault.equals(provider)) {
                            mixed.compareAndSet(null, model + " 与第 " + generation + " 代的默认 provider 不一致");
                        }
                    }
                }));
            }

            for (int i = 1; i <= 60; i++) {
                when(providerRepository.findByEnabledTrueOrderByIdAsc())
                    .thenReturn(List.of(entity("a", "a-" + i), entity("b", "b-" + i)));
                String defaultProvider = i % 2 == 0 ? "a" : "b";
                when(globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID))
                    .thenAnswer(invocation -> Optional.of(globalSetting(defaultProvider, defaultProvider)));
                assertTrue(registry.refresh().success());
            }
            stop.set(true);
            readers.shutdown();
            assertTrue(readers.awaitTermination(10, TimeUnit.SECONDS));
            for (Future<?> future : inFlight) {
                future.get(5, TimeUnit.SECONDS);
            }

            assertNull(mixed.get(), "并发期间不应观察到混代：" + mixed.get());
            // 第 60 代默认 provider 是 a（偶数代用 a），模型与默认 provider 自洽
            assertEquals("a-60", modelOf(registry.getDefaultChatClient()));
        }

        @Test
        @DisplayName("重试次数耗尽后标记宽限已过，状态进入 EXPIRED 且请求抛业务异常")
        void graceCanBeExpiredBeforeDeadline() {
            when(providerRepository.findByEnabledTrueOrderByIdAsc())
                .thenReturn(List.of(entity("p1", "model-v1")));
            when(encryptionService.decrypt("nonce", "cipher")).thenReturn("key-1");
            when(globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(globalSetting("p1", "p1")));

            MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
            LlmProviderRegistry registry = newRegistry(clock);
            assertTrue(registry.refresh().success());

            when(providerRepository.findByEnabledTrueOrderByIdAsc())
                .thenThrow(new QueryTimeoutException("read timed out"));
            clock.advance(Duration.ofSeconds(1));
            assertFalse(registry.refresh().success());
            assertEquals(LlmProviderRegistry.CacheState.STALE, registry.status().state());

            // 重试耗尽：不等宽限自然到期，直接进入 EXPIRED
            registry.markGraceExpired();

            assertEquals(LlmProviderRegistry.CacheState.EXPIRED, registry.status().state());
            assertEquals(0L, registry.status().graceSecondsRemaining());
            BusinessException ex = assertThrows(
                BusinessException.class, () -> registry.getChatClient("p1"));
            assertEquals(ErrorCode.PROVIDER_UNAVAILABLE.getCode(), ex.getCode());

            // 之后任意一次成功刷新仍能恢复
            doReturn(List.of(entity("p1", "model-v2")))
                .when(providerRepository).findByEnabledTrueOrderByIdAsc();
            assertTrue(registry.refresh().success());
            assertEquals(LlmProviderRegistry.CacheState.READY, registry.status().state());
            assertEquals("model-v2", modelOf(registry.getChatClient("p1")));
        }

        @Test
        @DisplayName("从未失败时标记宽限已过不会把缓存变成 EXPIRED")
        void graceExpiryIgnoredWithoutFailure() {
            when(providerRepository.findByEnabledTrueOrderByIdAsc())
                .thenReturn(List.of(entity("p1", "model-v1")));
            when(encryptionService.decrypt("nonce", "cipher")).thenReturn("key-1");
            when(globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(globalSetting("p1", "p1")));

            LlmProviderRegistry registry = newRegistry();
            assertTrue(registry.refresh().success());

            registry.markGraceExpired();

            assertEquals(LlmProviderRegistry.CacheState.READY, registry.status().state());
            assertNotNull(registry.getChatClient("p1"));
        }

        private LlmProviderRegistry newRegistry() {
            return newRegistry(Clock.systemUTC());
        }

        private LlmProviderRegistry newRegistry(Clock clock) {
            return new LlmProviderRegistry(
                properties,
                providerRepository,
                globalSettingRepository,
                encryptionService,
                null,
                null,
                null,
                clock);
        }

        @Test
        @DisplayName("状态查询返回状态名、最近成功时间、失败原因、宽限剩余与可重试标记")
        void statusReportsAllFields() {
            when(providerRepository.findByEnabledTrueOrderByIdAsc())
                .thenReturn(List.of(entity("p1", "model-v1")));
            when(encryptionService.decrypt("nonce", "cipher")).thenReturn("key-1");
            when(globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(globalSetting("p1", "p1")));

            MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
            LlmProviderRegistry registry = newRegistry(clock);

            LlmProviderRegistry.CacheStatus ready = registry.status();
            assertEquals("READY", ready.stateName(), "状态名对外暴露原样枚举名");
            assertNull(ready.lastFailureReason());
            assertEquals(0L, ready.graceSecondsRemaining());

            assertTrue(registry.refresh().success());
            LlmProviderRegistry.CacheStatus afterSuccess = registry.status();
            assertEquals("READY", afterSuccess.stateName());
            assertEquals(Instant.parse("2026-01-01T00:00:00Z"), afterSuccess.lastSuccessAt());

            when(providerRepository.findByEnabledTrueOrderByIdAsc())
                .thenThrow(new QueryTimeoutException("read timed out"));
            assertFalse(registry.refresh().success(), "首次失败进入 STALE");
            clock.advance(Duration.ofSeconds(10));
            LlmProviderRegistry.CacheStatus stale = registry.status();

            assertEquals("STALE", stale.stateName());
            assertTrue(stale.retryable(), "读库超时应标记为可重试");
            assertEquals(50L, stale.graceSecondsRemaining(), "默认宽限 60 秒，已过 10 秒");
            assertNotNull(stale.lastFailureReason());
            assertEquals(Instant.parse("2026-01-01T00:00:00Z"), stale.lastSuccessAt());
        }

        @Test
        @DisplayName("宽限时长由配置项决定，默认 60 秒")
        void graceDurationIsConfigurable() {
            when(providerRepository.findByEnabledTrueOrderByIdAsc())
                .thenThrow(new QueryTimeoutException("read timed out"));
            when(properties.getRefreshGraceDuration()).thenReturn(Duration.ofSeconds(5));

            MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
            LlmProviderRegistry registry = newRegistry(clock);

            assertFalse(registry.refresh().success());
            assertEquals(5L, registry.status().graceSecondsRemaining(), "应使用配置的宽限时长");

            clock.advance(Duration.ofSeconds(6));
            assertEquals(LlmProviderRegistry.CacheState.EXPIRED, registry.status().state());
        }

        @Test
        @DisplayName("失败分类集中在一处：参数类不可恢复，读库 / 连接类可恢复")
        void failureClassificationIsCentralised() {
            assertTrue(LlmProviderRegistry.FailureClassifier.isRetryable(
                new QueryTimeoutException("read timed out")));
            assertTrue(LlmProviderRegistry.FailureClassifier.isRetryable(
                new DataAccessResourceFailureException("connection refused")));
            assertTrue(LlmProviderRegistry.FailureClassifier.isRetryable(
                new RuntimeException("wrapped", new ConnectException("connect refused"))));

            assertFalse(LlmProviderRegistry.FailureClassifier.isRetryable(
                new BusinessException(ErrorCode.PROVIDER_NOT_FOUND, "Provider 'p1' 已被禁用")));
            assertFalse(LlmProviderRegistry.FailureClassifier.isRetryable(
                new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED, "解密失败")));
            assertFalse(LlmProviderRegistry.FailureClassifier.isRetryable(
                new IllegalArgumentException("Unknown LLM provider: p1")));
        }

        @Test
        @DisplayName("刷新进行中时状态对外暴露为 REFRESHING")
        void reportsRefreshingWhileBuilding() throws Exception {
            CountDownLatch decrypting = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger decryptCalls = new AtomicInteger();
            when(providerRepository.findByEnabledTrueOrderByIdAsc())
                .thenReturn(List.of(entity("p1", "model-v1")));
            when(encryptionService.decrypt("nonce", "cipher")).thenAnswer(invocation -> {
                if (decryptCalls.incrementAndGet() > 1) {
                    decrypting.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                }
                return "key-1";
            });
            when(globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID))
                .thenReturn(Optional.of(globalSetting("p1", "p1")));

            LlmProviderRegistry registry = newRegistry();
            assertTrue(registry.refresh().success());
            ChatClient before = registry.getChatClient("p1");

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<LlmProviderRegistry.RefreshResult> refresh = executor.submit(registry::refresh);
                assertTrue(decrypting.await(5, TimeUnit.SECONDS));

                // 构建已开始但尚未替换：状态是 REFRESHING，请求仍拿到旧一代
                assertEquals(LlmProviderRegistry.CacheState.REFRESHING, registry.status().state());
                assertSame(before, registry.getChatClient("p1"));

                release.countDown();
                assertTrue(refresh.get(5, TimeUnit.SECONDS).success());
                assertEquals(LlmProviderRegistry.CacheState.READY, registry.status().state());
            } finally {
                executor.shutdownNow();
            }
        }

        private static final class MutableClock extends Clock {
            private final AtomicReference<Instant> now;

            private MutableClock(Instant start) {
                this.now = new AtomicReference<>(start);
            }

            private void advance(Duration duration) {
                now.updateAndGet(current -> current.plus(duration));
            }

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        }

        private LlmProviderEntity entity(String id, String model) {
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

    private static LlmGlobalSettingEntity globalSetting(String providerId) {
        LlmGlobalSettingEntity entity = new LlmGlobalSettingEntity();
        entity.setDefaultChatProviderId(providerId);
        return entity;
    }

    private static String modelOf(ChatClient client) {
        try {
            Field requestField = client.getClass().getDeclaredField("defaultChatClientRequest");
            requestField.setAccessible(true);
            Object request = requestField.get(client);
            Field modelField = request.getClass().getDeclaredField("chatModel");
            modelField.setAccessible(true);
            OpenAiChatModel model = (OpenAiChatModel) modelField.get(request);
            return model.getOptions().getModel();
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("无法读取 ChatClient 持有的模型", ex);
        }
    }
}
