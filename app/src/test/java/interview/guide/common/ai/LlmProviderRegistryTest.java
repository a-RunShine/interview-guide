package interview.guide.common.ai;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
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

import java.lang.reflect.Field;
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
        registry.reload();
        when(properties.getProviders()).thenReturn(providers);
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
        registry.reload();
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
            registry.reload();
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

            isolated.reload();
            release.countDown();

            assertEquals("model-a", modelOf(inFlight.get(5, TimeUnit.SECONDS)));
            assertEquals("model-b", modelOf(isolated.getDefaultChatClient()));
            verify(globalRepo, times(2)).findById(LlmGlobalSettingEntity.SINGLETON_ID);
        } finally {
            executor.shutdownNow();
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
