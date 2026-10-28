package interview.guide.common.ai;

import com.openai.client.OpenAIClient;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.AdvisorConfig;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Registry for managing and caching LLM providers.
 * Supports dynamic creation of ChatClient based on provider configurations.
 */
@Component
@Slf4j
public class LlmProviderRegistry {

    /** 刷新失败计数，按失败类型分标签。运维告警规则依赖这个名字。 */
    private static final String METRIC_REFRESH_FAILURES = "app.ai.llm_provider.refresh.failures";
    /** 当前缓存状态，数值见 {@link #stateCode(CacheState)}。 */
    private static final String METRIC_CACHE_STATE = "app.ai.llm_provider.cache.state";
    private static final String TAG_FAILURE_TYPE = "type";

    /** 可恢复失败：读库超时、连接异常等，调度方会重试。 */
    static final String FAILURE_TYPE_RECOVERABLE = "recoverable";
    /** 不可恢复失败：配置本身有问题，重试没有意义。 */
    static final String FAILURE_TYPE_NON_RECOVERABLE = "non_recoverable";
    /** 重试次数耗尽：宽限被提前收拢，缓存停止服务。 */
    static final String FAILURE_TYPE_RETRY_EXHAUSTED = "retry_exhausted";

    private final LlmProviderProperties properties;
    private final AtomicReference<Generation> generation = new AtomicReference<>();
    private final AtomicReference<Instant> graceDeadline = new AtomicReference<>();
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final LlmProviderRepository providerRepository;
    private final LlmGlobalSettingRepository globalSettingRepository;
    private final ApiKeyEncryptionService encryptionService;

    private final ToolCallingManager toolCallingManager;
    private final ObservationRegistry observationRegistry;
    private final ToolCallback interviewSkillsToolCallback;
    private final Clock clock;
    private final Duration graceDuration;
    /** 允许缺失：单测环境没有 Micrometer 时全部指标静默跳过。 */
    private final MeterRegistry meterRegistry;

    private volatile boolean refreshing;
    private volatile String lastFailureReason;
    private volatile boolean lastFailureRetryable;
    /** 本次构建失败时正在校验的 provider id，只用于日志，不参与业务判断。 */
    private volatile String failedProviderId;
    /** 当前这一轮失败是否已经计过"重试耗尽"，保证一次失败只报警一次。 */
    private final AtomicBoolean graceExpiryCounted = new AtomicBoolean();
    private static final Map<String, String> RECOMMENDED_EMBEDDING_MODELS = Map.of(
        "dashscope", "text-embedding-v3",
        "glm", "embedding-3",
        "zhipu", "embedding-3",
        "baidu", "Embedding-V1",
        "minimax", "embo-01"
    );

    @Autowired
    public LlmProviderRegistry(
            LlmProviderProperties properties,
            LlmProviderRepository providerRepository,
            LlmGlobalSettingRepository globalSettingRepository,
            ApiKeyEncryptionService encryptionService,
            @Autowired(required = false) ToolCallingManager toolCallingManager,
            @Autowired(required = false) ObservationRegistry observationRegistry,
            @Autowired(required = false) @Qualifier("interviewSkillsToolCallback") ToolCallback interviewSkillsToolCallback,
            @Autowired(required = false) MeterRegistry meterRegistry) {
        this(properties, providerRepository, globalSettingRepository, encryptionService, toolCallingManager,
            observationRegistry, interviewSkillsToolCallback, Clock.systemUTC(), meterRegistry);
    }

    LlmProviderRegistry(
            LlmProviderProperties properties,
            LlmProviderRepository providerRepository,
            LlmGlobalSettingRepository globalSettingRepository,
            ApiKeyEncryptionService encryptionService,
            ToolCallingManager toolCallingManager,
            ObservationRegistry observationRegistry,
            ToolCallback interviewSkillsToolCallback) {
        this(properties, providerRepository, globalSettingRepository, encryptionService, toolCallingManager,
            observationRegistry, interviewSkillsToolCallback, Clock.systemUTC(), null);
    }

    LlmProviderRegistry(
            LlmProviderProperties properties,
            LlmProviderRepository providerRepository,
            LlmGlobalSettingRepository globalSettingRepository,
            ApiKeyEncryptionService encryptionService,
            ToolCallingManager toolCallingManager,
            ObservationRegistry observationRegistry,
            ToolCallback interviewSkillsToolCallback,
            Clock clock) {
        this(properties, providerRepository, globalSettingRepository, encryptionService, toolCallingManager,
            observationRegistry, interviewSkillsToolCallback, clock, null);
    }

    LlmProviderRegistry(
            LlmProviderProperties properties,
            LlmProviderRepository providerRepository,
            LlmGlobalSettingRepository globalSettingRepository,
            ApiKeyEncryptionService encryptionService,
            ToolCallingManager toolCallingManager,
            ObservationRegistry observationRegistry,
            ToolCallback interviewSkillsToolCallback,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.providerRepository = providerRepository;
        this.globalSettingRepository = globalSettingRepository;
        this.encryptionService = encryptionService;
        this.toolCallingManager = toolCallingManager;
        this.observationRegistry = observationRegistry;
        this.interviewSkillsToolCallback = interviewSkillsToolCallback;
        this.clock = clock != null ? clock : Clock.systemUTC();
        // 懒加载的一代在构造时就带上构建时间：首次 refresh() 之前状态已经是 READY，
        // 状态查询必须能给出时间戳，否则前端会显示"最新"却没有最近成功时间。
        this.generation.set(Generation.lazy(this.clock.instant()));
        this.graceDuration = resolveGraceDuration(properties);
        this.meterRegistry = meterRegistry;
        registerCacheStateGauge();
    }

    public LlmProviderRegistry(
            LlmProviderProperties properties,
            ToolCallingManager toolCallingManager,
            ObservationRegistry observationRegistry,
            ToolCallback interviewSkillsToolCallback) {
        this(properties, null, null, null, toolCallingManager, observationRegistry,
            interviewSkillsToolCallback, Clock.systemUTC(), null);
    }

    private static Duration resolveGraceDuration(LlmProviderProperties properties) {
        Duration configured = properties != null ? properties.getRefreshGraceDuration() : null;
        if (configured == null || configured.isNegative() || configured.isZero()) {
            // properties 未绑定（例如直接 new 出来的测试实例）时退回配置属性类自己声明的默认值，
            // 60 秒只在 LlmProviderProperties 里出现一次。
            return LlmProviderProperties.DEFAULT_REFRESH_GRACE_DURATION;
        }
        return configured;
    }

    /**
     * Get a ChatClient for the specified provider ID.
     * If the client is not in the cache, it will be created based on the provider's configuration.
     *
     * <p>缓存状态为 {@link CacheState#EXPIRED}（宽限期已过）时抛
     * {@link BusinessException} + {@link ErrorCode#PROVIDER_UNAVAILABLE}；
     * 宽限期内仍返回旧一代的客户端。
     *
     * @param providerId The ID of the provider (e.g., "dashscope", "lmstudio")
     * @return A ChatClient instance
     * @throws IllegalArgumentException if the providerId is unknown
     */
    public ChatClient getChatClient(String providerId) {
        Generation current = currentServingGeneration();
        return chatClient(current, providerId);
    }

    /**
     * Get the default ChatClient based on app.ai.default-provider.
     *
     * @return The default ChatClient instance
     */
    public ChatClient getDefaultChatClient() {
        Generation current = currentServingGeneration();
        return chatClient(current, resolveDefaultChatProviderId(current));
    }

    /**
     * 获取默认 provider 的不带 SkillsTool 的 ChatClient，用于纯粹的摘要 / 结构化文本场景。
     * 与 {@link #getDefaultChatClient()} 的区别在于不挂 Skill 工具与记忆 Advisor，避免无关上下文干扰。
     */
    public ChatClient getPlainChatClient() {
        Generation current = currentServingGeneration();
        return plainChatClient(current, resolveDefaultChatProviderId(current));
    }

    /**
     * Get a ChatClient for the specified provider, falling back to the default if null, blank, or
     * the legacy "default" alias.
     */
    public ChatClient getChatClientOrDefault(String providerId) {
        Generation current = currentServingGeneration();
        return chatClient(current, resolveProviderId(current, providerId));
    }

    /**
     * 获取不带 SkillsTool 的 ChatClient，用于结构化输出场景（出题、简历评分等）。
     * 这些场景要求模型一次性返回可解析 JSON，不应混入工具调用消息。
     */
    public ChatClient getPlainChatClient(String providerId) {
        Generation current = currentServingGeneration();
        return plainChatClient(current, resolveProviderId(current, providerId));
    }

    /**
     * 获取语音面试专用 ChatClient：SkillsTool + ToolCallAdvisor（流式）。
     * 不加 Memory Advisor（语音面试手动管理对话历史）。
     */
    public ChatClient getVoiceChatClient(String providerId) {
        Generation current = currentServingGeneration();
        return voiceChatClient(current, resolveProviderId(current, providerId));
    }

    /**
     * 先离线校验、再整代替换。校验只读库、解密密钥、组装模型参数，不发起任何网络调用。
     *
     * <p>全部校验通过才把预校验结果装进新一代并原子替换，状态回到 {@link CacheState#READY}；
     * 任一失败则保留旧一代，首次失败时设置宽限截止时间并进入 {@link CacheState#STALE}。
     * 宽限到期后状态变为 {@link CacheState#EXPIRED}，请求入口开始抛业务异常。
     *
     * <p>失败结果里的 {@code retryable} 就是重试调度（#4）的接缝：可重试的失败由调度方
     * 再次调用本方法，不可重试的失败不安排重试。
     */
    public RefreshResult refresh() {
        refreshLock.lock();
        try {
            refreshing = true;
            try {
                Generation candidate = buildGeneration();
                generation.set(candidate);
                clearGrace();
                log.info("[LlmProviderRegistry] Refresh succeeded with {} providers",
                    candidate.validatedSnapshots.size());
                return RefreshResult.success(candidate.builtAt);
            } catch (RuntimeException e) {
                return handleRefreshFailure(e);
            } finally {
                refreshing = false;
            }
        } finally {
            refreshLock.unlock();
        }
    }

    private RefreshResult handleRefreshFailure(RuntimeException failure) {
        boolean retryable = FailureClassifier.isRetryable(failure);
        String failureType = retryable ? FAILURE_TYPE_RECOVERABLE : FAILURE_TYPE_NON_RECOVERABLE;
        Instant now = clock.instant();
        Instant deadline = graceDeadline.updateAndGet(
            existing -> existing != null ? existing : now.plus(graceDuration));
        lastFailureReason = failure.getMessage();
        lastFailureRetryable = retryable;
        recordFailure(failureType);
        log.error("[LlmProviderRegistry] Refresh failed, provider={}, failureType={}, state={}, "
                + "graceRemainingMs={}, retryable={}",
            failedProviderId, failureType, CacheState.STALE, Duration.between(now, deadline).toMillis(),
            retryable, failure);
        return RefreshResult.failure(failure.getMessage(), retryable);
    }

    /**
     * 重试次数耗尽时提前结束宽限：把宽限截止时间收拢到当前时刻，状态立即变为
     * {@link CacheState#EXPIRED}，请求入口开始抛业务异常。
     *
     * <p>只在已经失败（存在宽限截止时间）时生效，避免把从未失败过的缓存放进 EXPIRED。
     * 之后任意一次成功的 {@link #refresh()} 仍会把状态带回 {@link CacheState#READY}。
     */
    public void markGraceExpired() {
        Instant now = clock.instant();
        Instant deadline = graceDeadline.updateAndGet(
            existing -> existing == null ? null : now);
        if (deadline == null) {
            log.warn("[LlmProviderRegistry] Grace expiry requested without a pending failure, ignored");
            return;
        }
        // 一次失败只算一次"重试耗尽"报警：重复调用不再重复计数
        if (!graceExpiryCounted.compareAndSet(false, true)) {
            log.debug("[LlmProviderRegistry] Grace already marked exhausted, skipping duplicate count");
            return;
        }
        recordFailure(FAILURE_TYPE_RETRY_EXHAUSTED);
        log.error("[LlmProviderRegistry] Grace exhausted, provider={}, failureType={}, state={}, "
                + "graceRemainingMs=0, lastFailureReason={}",
            failedProviderId, FAILURE_TYPE_RETRY_EXHAUSTED, CacheState.EXPIRED, lastFailureReason);
    }

    /**
     * 查询当前缓存状态：状态名、最近成功时间、最近失败原因、宽限剩余秒数、失败是否可重试。
     */
    public CacheStatus status() {
        Instant deadline = graceDeadline.get();
        long graceRemainingMillis = deadline == null
            ? 0L
            : Duration.between(clock.instant(), deadline).toMillis();
        return new CacheStatus(
            currentState(deadline, graceRemainingMillis),
            generation.get().builtAt,
            lastFailureReason,
            Math.max(0L, graceRemainingMillis / 1000L),
            lastFailureRetryable);
    }

    private CacheState currentState(Instant deadline, long graceRemainingMillis) {
        if (deadline != null && graceRemainingMillis <= 0) {
            // 宽限已过：请求已经不可用，即使正在刷新也如实报告 EXPIRED
            return CacheState.EXPIRED;
        }
        if (refreshing) {
            return CacheState.REFRESHING;
        }
        if (deadline == null) {
            return CacheState.READY;
        }
        return CacheState.STALE;
    }

    private void clearGrace() {
        graceDeadline.set(null);
        lastFailureReason = null;
        lastFailureRetryable = false;
        failedProviderId = null;
        graceExpiryCounted.set(false);
    }

    /**
     * 缓存状态到数值的映射：监控只需比较阈值就能判断"当前是否过期"。
     * 顺序按严重程度递增：{@code READY=0 < REFRESHING=1 < STALE=2 < EXPIRED=3}。
     */
    static int stateCode(CacheState state) {
        return switch (state) {
            case READY -> 0;
            case REFRESHING -> 1;
            case STALE -> 2;
            case EXPIRED -> 3;
        };
    }

    /**
     * 注册缓存状态仪表。仪表在抓取时通过 {@link #status()} 采样，因此状态变化无需额外推送；
     * 没有 {@link MeterRegistry}（单测环境）时什么都不做。
     */
    private void registerCacheStateGauge() {
        if (meterRegistry == null) {
            return;
        }
        Gauge.builder(METRIC_CACHE_STATE, this, registry -> stateCode(registry.status().state()))
            .description("LLM provider cache state: 0=READY, 1=REFRESHING, 2=STALE, 3=EXPIRED")
            .register(meterRegistry);
    }

    /** 按失败类型累加一次计数。没有 {@link MeterRegistry} 时静默跳过。 */
    private void recordFailure(String failureType) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(METRIC_REFRESH_FAILURES, TAG_FAILURE_TYPE, failureType).increment();
    }

    /**
     * 刷新入口之外的兜底路径（监听器捕获到的 Throwable）也要计入失败指标，
     * 否则"只记日志和指标"的保证只兑现了一半。
     *
     * <p>沿用已有的失败类型词汇（recoverable / non_recoverable），不引入新的标签值，
     * 现有告警规则不需要改动就能覆盖这条路径。
     */
    public void recordEscapedRefreshFailure(Throwable failure) {
        String failureType = FailureClassifier.isRetryable(failure)
            ? FAILURE_TYPE_RECOVERABLE
            : FAILURE_TYPE_NON_RECOVERABLE;
        recordFailure(failureType);
        log.error("[LlmProviderRegistry] Refresh aborted with an escaped throwable, failureType={}",
            failureType, failure);
    }

    /**
     * 请求入口守卫：宽限已过（{@link CacheState#EXPIRED}）时抛业务异常。
     * 宽限期内照常返回旧一代，不打断正在进行的对话。
     */
    private Generation currentServingGeneration() {
        Instant deadline = graceDeadline.get();
        if (deadline != null && !clock.instant().isBefore(deadline)) {
            throw new BusinessException(
                ErrorCode.PROVIDER_UNAVAILABLE,
                "LLM 配置已过期且超出宽限期，服务暂不可用" + reasonSuffix());
        }
        return generation.get();
    }

    private String reasonSuffix() {
        return lastFailureReason == null ? "" : "，最近失败原因：" + lastFailureReason;
    }

    private Generation buildGeneration() {
        failedProviderId = null;
        Map<String, ProviderSnapshot> snapshots = providerRepository == null
            ? buildSnapshotsFromProperties()
            : buildSnapshotsFromDatabase();
        if (snapshots.isEmpty()) {
            throw new BusinessException(ErrorCode.PROVIDER_NOT_FOUND, "没有任何已启用的 LLM Provider");
        }
        Instant builtAt = clock.instant();
        if (providerRepository == null) {
            // 配置文件驱动的旧路径：默认 provider 仍按原有懒解析行为决定，避免改变启动语义。
            return Generation.validated(snapshots, null, null, builtAt);
        }
        Optional<LlmGlobalSettingEntity> setting =
            globalSettingRepository == null
                ? Optional.empty()
                : globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID);
        String chatProviderId = requireEnabledProvider(
            snapshots,
            setting.map(LlmGlobalSettingEntity::getDefaultChatProviderId)
                .filter(id -> !isBlank(id))
                .orElse(properties.getDefaultProvider()),
            "默认聊天");
        String embeddingProviderId = requireEnabledProvider(
            snapshots,
            setting.map(LlmGlobalSettingEntity::getDefaultEmbeddingProviderId)
                .filter(id -> !isBlank(id))
                .orElseGet(this::defaultEmbeddingProviderFromProperties),
            "默认向量");
        return Generation.validated(snapshots, chatProviderId, embeddingProviderId, builtAt);
    }

    private String requireEnabledProvider(
            Map<String, ProviderSnapshot> snapshots, String providerId, String role) {
        if (!snapshots.containsKey(providerId)) {
            failedProviderId = providerId;
            throw new BusinessException(
                ErrorCode.PROVIDER_NOT_FOUND,
                role + " Provider '" + providerId + "' 不存在或已被禁用");
        }
        return providerId;
    }

    private Map<String, ProviderSnapshot> buildSnapshotsFromDatabase() {
        List<LlmProviderEntity> providers = providerRepository.findByEnabledTrueOrderByIdAsc();
        Map<String, ProviderSnapshot> snapshots = new LinkedHashMap<>();
        if (providers == null) {
            return snapshots;
        }
        for (LlmProviderEntity entity : providers) {
            snapshots.put(entity.getId(), validate(entity));
        }
        return snapshots;
    }

    private Map<String, ProviderSnapshot> buildSnapshotsFromProperties() {
        Map<String, ProviderConfig> providers = properties.getProviders();
        Map<String, ProviderSnapshot> snapshots = new LinkedHashMap<>();
        if (providers == null) {
            return snapshots;
        }
        providers.forEach((id, config) -> snapshots.put(id, loadProviderFromPropertiesOrThrow(id)));
        return snapshots;
    }

    /**
     * 离线校验单个已启用 provider：只读配置、解密密钥、校验参数，不发起网络调用。
     */
    private ProviderSnapshot validate(LlmProviderEntity entity) {
        failedProviderId = entity.getId();
        if (!entity.isEnabled()) {
            throw new BusinessException(
                ErrorCode.PROVIDER_NOT_FOUND,
                "Provider '" + entity.getId() + "' 已被禁用");
        }
        if (isBlank(entity.getBaseUrl())) {
            throw new BusinessException(
                ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider '" + entity.getId() + "' 的 baseUrl 未配置");
        }
        if (isBlank(entity.getModel())) {
            throw new BusinessException(
                ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider '" + entity.getId() + "' 的 model 未配置");
        }
        if (entity.isSupportsEmbedding() && isBlank(entity.getEmbeddingModel())) {
            throw new BusinessException(
                ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider '" + entity.getId() + "' 支持 Embedding 但未配置 embeddingModel");
        }
        if (encryptionService == null) {
            throw new BusinessException(
                ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider '" + entity.getId() + "' 的密钥无法解密：加密服务不可用");
        }
        String apiKey = encryptionService.decrypt(entity.getApiKeyNonce(), entity.getApiKeyCiphertext());
        return new ProviderSnapshot(
            entity.getId(),
            entity.getBaseUrl(),
            apiKey,
            entity.getModel(),
            entity.getEmbeddingModel(),
            entity.getEmbeddingDimensions(),
            entity.isSupportsEmbedding(),
            entity.getTemperature());
    }

    /**
     * 获取 EmbeddingModel，供知识库向量化使用。
     */
    public EmbeddingModel getEmbeddingModel(String providerId) {
        Generation current = currentServingGeneration();
        return embeddingModel(current, providerId);
    }

    public EmbeddingModel getDefaultEmbeddingModel() {
        Generation current = currentServingGeneration();
        return embeddingModel(current, resolveDefaultEmbeddingProviderId(current));
    }

    private ChatClient chatClient(Generation current, String providerId) {
        return current.clients.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new client for provider: {}", id);
            return createChatClient(current, id);
        });
    }

    private ChatClient plainChatClient(Generation current, String providerId) {
        return current.clients.computeIfAbsent(
            providerId + ":plain", key -> createPlainChatClient(current, providerId));
    }

    private ChatClient voiceChatClient(Generation current, String providerId) {
        return current.clients.computeIfAbsent(
            providerId + ":voice", key -> createVoiceChatClient(current, providerId));
    }

    private EmbeddingModel embeddingModel(Generation current, String providerId) {
        return current.embeddingModels.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new embedding model for provider: {}", id);
            return createEmbeddingModel(current, id);
        });
    }

    private ChatClient createChatClient(Generation current, String providerId) {
        OpenAiChatModel chatModel = getChatModel(current, providerId);

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (interviewSkillsToolCallback != null) {
            builder.defaultTools(interviewSkillsToolCallback);
        }
        List<Advisor> advisors = buildDefaultAdvisors(providerId);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors);
            log.info("[LlmProviderRegistry] Applied {} advisors for provider {}", advisors.size(), providerId);
        }

        return builder.build();
    }

    private ChatClient createPlainChatClient(Generation current, String providerId) {
        OpenAiChatModel chatModel = getChatModel(current, providerId);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        buildSafeGuardAdvisor().ifPresent(advisor -> builder.defaultAdvisors(List.of(advisor)));
        log.info("[LlmProviderRegistry] Created plain ChatClient (no tools) for {}", providerId);
        return builder.build();
    }

    private ChatClient createVoiceChatClient(Generation current, String providerId) {
        OpenAiChatModel chatModel = getChatModel(current, providerId);

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (interviewSkillsToolCallback != null) {
            builder.defaultTools(interviewSkillsToolCallback);
        }
        List<Advisor> advisors = new ArrayList<>();
        if (toolCallingManager != null) {
            advisors.add(buildToolCallAdvisor(true));
        }
        buildSafeGuardAdvisor().ifPresent(advisors::add);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors);
        }
        log.info("[LlmProviderRegistry] Created voice ChatClient (SkillsTool + streaming ToolCall) for {}", providerId);
        return builder.build();
    }

    private OpenAiChatModel getChatModel(Generation current, String providerId) {
        return current.chatModels.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new ChatModel for provider: {}", id);
            return buildChatModel(current, id);
        });
    }

    private OpenAiChatModel buildChatModel(Generation current, String providerId) {
        ProviderSnapshot config = loadProviderOrThrow(current, providerId);
        log.info("[LlmProviderRegistry] Building ChatModel - Provider: {}, BaseUrl: {}, Model: {}",
                 providerId, config.baseUrl(), config.model());

        OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(config.baseUrl(), config.apiKey());

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.model())
                .temperature(config.temperature() != null ? config.temperature() : 0.2)
                .build();

        return OpenAiChatModel.builder()
            .openAiClient(openAiClient)
            .openAiClientAsync(openAiClient.async())
            .options(options)
            .observationRegistry(observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP)
            .build();
    }

    private EmbeddingModel createEmbeddingModel(Generation current, String providerId) {
        ProviderSnapshot config = loadProviderOrThrow(current, providerId);
        if (!config.supportsEmbedding() || isBlank(config.embeddingModel())) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider '" + providerId + "' 未配置可用的 Embedding 模型，无法执行知识库向量化");
        }
        if (looksLikeChatModel(config.embeddingModel())) {
            String recommendation = RECOMMENDED_EMBEDDING_MODELS.get(providerId.toLowerCase());
            String suffix = recommendation != null
                ? "，推荐填写 " + recommendation
                : "，请填写该厂商真实的 Embedding 模型名";
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider '" + providerId + "' 的 Embedding Model 配成了聊天模型 '"
                    + config.embeddingModel() + "'" + suffix);
        }
        log.info("[LlmProviderRegistry] Building EmbeddingModel - Provider: {}, BaseUrl: {}, Model: {}",
            providerId, config.baseUrl(), config.embeddingModel());

        OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(config.baseUrl(), config.apiKey());
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
            .model(config.embeddingModel())
            .dimensions(resolveEmbeddingDimensions(config.embeddingDimensions()))
            .build();

        return OpenAiEmbeddingModel.builder()
            .openAiClient(openAiClient)
            .metadataMode(MetadataMode.EMBED)
            .options(options)
            .observationRegistry(observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP)
            .build();
    }

    private List<Advisor> buildDefaultAdvisors(String providerId) {
        AdvisorConfig config = properties.getAdvisors();
        if (config == null || !config.isEnabled()) {
            return List.of();
        }

        List<Advisor> advisors = new ArrayList<>();

        if (config.isToolCallEnabled()) {
            if (toolCallingManager != null) {
                advisors.add(buildToolCallAdvisor(config.isToolCallConversationHistoryEnabled()));
            } else {
                log.warn("[LlmProviderRegistry] ToolCallAdvisor skipped: ToolCallingManager unavailable, provider={}", providerId);
            }
        }

        if (config.isMessageChatMemoryEnabled()) {
            int maxMessages = Math.max(20, config.getMessageChatMemoryMaxMessages());
            MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(
                MessageWindowChatMemory.builder()
                    .maxMessages(maxMessages)
                    .build()
            ).build();
            advisors.add(memoryAdvisor);
        }

        if (config.isSimpleLoggerEnabled()) {
            advisors.add(new SimpleLoggerAdvisor());
        }

        buildSafeGuardAdvisor().ifPresent(advisors::add);

        return advisors;
    }

    private ToolCallingAdvisor buildToolCallAdvisor(boolean conversationHistoryEnabled) {
        return ToolCallingAdvisor.builder()
            .toolCallingManager(toolCallingManager)
            .conversationHistoryEnabled(conversationHistoryEnabled)
            .build();
    }

    private Optional<SafeGuardAdvisor> buildSafeGuardAdvisor() {
        AdvisorConfig config = properties.getAdvisors();
        if (config == null || !config.isSafeguardEnabled()) {
            return Optional.empty();
        }
        SafeGuardAdvisor advisor = SafeGuardAdvisor.builder()
            .sensitiveWords(config.getSafeguardWords())
            .failureResponse("抱歉，我只能协助面试相关的任务。")
            .order(100)
            .build();
        return Optional.of(advisor);
    }

    private String resolveProviderId(Generation current, String providerId) {
        if (providerId == null || providerId.isBlank() || "default".equalsIgnoreCase(providerId.trim())) {
            return resolveDefaultChatProviderId(current);
        }
        return providerId;
    }

    private String resolveDefaultChatProviderId(Generation current) {
        String cachedId = current.defaultChatProviderId;
        if (cachedId != null) {
            return cachedId;
        }
        if (globalSettingRepository == null) {
            return properties.getDefaultProvider();
        }
        String resolved = globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
            .map(LlmGlobalSettingEntity::getDefaultChatProviderId)
            .filter(id -> !isBlank(id))
            .orElse(properties.getDefaultProvider());
        current.defaultChatProviderId = resolved;
        return resolved;
    }

    private String resolveDefaultEmbeddingProviderId(Generation current) {
        if (current.defaultEmbeddingProviderId != null) {
            return current.defaultEmbeddingProviderId;
        }
        if (globalSettingRepository == null) {
            return defaultEmbeddingProviderFromProperties();
        }
        return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
            .map(LlmGlobalSettingEntity::getDefaultEmbeddingProviderId)
            .filter(id -> !isBlank(id))
            .orElseGet(this::defaultEmbeddingProviderFromProperties);
    }

    private String defaultEmbeddingProviderFromProperties() {
        return !isBlank(properties.getDefaultEmbeddingProvider())
            ? properties.getDefaultEmbeddingProvider()
            : properties.getDefaultProvider();
    }

    private ProviderSnapshot loadProviderOrThrow(Generation current, String providerId) {
        if (current.validatedSnapshots != null) {
            ProviderSnapshot snapshot = current.validatedSnapshots.get(providerId);
            if (snapshot == null) {
                log.error("[LlmProviderRegistry] Provider config not found: {}", providerId);
                throw new IllegalArgumentException("Unknown LLM provider: " + providerId);
            }
            return snapshot;
        }
        return current.lazySnapshots.computeIfAbsent(providerId, this::loadSeedSnapshot);
    }

    /**
     * 首次 {@link #refresh()} 之前的懒加载路径：只解析被请求的那一个 provider，保持启动期原有语义。
     */
    private ProviderSnapshot loadSeedSnapshot(String providerId) {
        if (providerRepository == null) {
            return loadProviderFromPropertiesOrThrow(providerId);
        }
        LlmProviderEntity entity = providerRepository.findById(providerId)
            .filter(LlmProviderEntity::isEnabled)
            .orElseThrow(() -> new IllegalArgumentException("Unknown LLM provider: " + providerId));
        return new ProviderSnapshot(
            entity.getId(),
            entity.getBaseUrl(),
            encryptionService.decrypt(entity.getApiKeyNonce(), entity.getApiKeyCiphertext()),
            entity.getModel(),
            entity.getEmbeddingModel(),
            entity.getEmbeddingDimensions(),
            entity.isSupportsEmbedding(),
            entity.getTemperature()
        );
    }

    private ProviderSnapshot loadProviderFromPropertiesOrThrow(String providerId) {
        ProviderConfig config = properties.getProviders().get(providerId);
        if (config == null) {
            log.error("[LlmProviderRegistry] Provider config not found: {}", providerId);
            throw new IllegalArgumentException("Unknown LLM provider: " + providerId);
        }
        boolean supportsEmbedding = Boolean.TRUE.equals(config.getSupportsEmbedding())
            || !isBlank(config.getEmbeddingModel());
        return new ProviderSnapshot(
            providerId,
            config.getBaseUrl(),
            config.getApiKey(),
            config.getModel(),
            config.getEmbeddingModel(),
            config.getEmbeddingDimensions(),
            supportsEmbedding,
            config.getTemperature()
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
        if (configuredDimensions != null && configuredDimensions > 0) {
            return configuredDimensions;
        }
        return properties.getEmbeddingDimensions();
    }

    private boolean looksLikeChatModel(String model) {
        String lower = model.toLowerCase();
        return lower.startsWith("glm-")
            || lower.startsWith("deepseek")
            || lower.startsWith("kimi")
            || lower.startsWith("moonshot")
            || lower.startsWith("qwen")
            || lower.startsWith("ernie");
    }

    /**
     * 缓存状态名。对外暴露的字符串就是枚举的 {@code name()}，前端只负责翻译展示。
     */
    public enum CacheState {
        /** 最新：当前一代已通过离线校验。 */
        READY,
        /** 刷新中：正在构建新一代，尚未替换。 */
        REFRESHING,
        /** 旧值 + 宽限期内：刷新失败，仍可继续使用旧一代。 */
        STALE,
        /** 停止服务：宽限期已过，请求入口开始报错。 */
        EXPIRED
    }

    /**
     * {@link #refresh()} 的结果，供手动刷新接口同步返回成功 / 失败与原因。
     */
    public record RefreshResult(boolean success, Instant builtAt, String failureReason, boolean retryable) {

        static RefreshResult success(Instant builtAt) {
            return new RefreshResult(true, builtAt, null, false);
        }

        static RefreshResult failure(String failureReason, boolean retryable) {
            return new RefreshResult(false, null, failureReason, retryable);
        }
    }

    /**
     * 缓存状态快照：状态名、最近成功时间、最近失败原因、宽限剩余秒数、最近失败是否可重试。
     */
    public record CacheStatus(
        CacheState state,
        Instant lastSuccessAt,
        String lastFailureReason,
        long graceSecondsRemaining,
        boolean retryable
    ) {

        /** 对外暴露的状态名。 */
        public String stateName() {
            return state.name();
        }
    }

    /**
     * 失败分类的唯一入口：配置本身有问题判为不可恢复，读库 / 连接类异常判为可恢复。
     * 只要异常链上任意一层是超时 / 连接类异常就算可恢复，其余一律按不可恢复处理。
     */
    static final class FailureClassifier {

        private FailureClassifier() {
        }

        static boolean isRetryable(Throwable failure) {
            for (Throwable current = failure; current != null; current = current.getCause()) {
                if (isRetryableType(current)) {
                    return true;
                }
                if (current.getCause() == current) {
                    break;
                }
            }
            return false;
        }

        private static boolean isRetryableType(Throwable failure) {
            return failure instanceof TransientDataAccessException
                || failure instanceof DataAccessResourceFailureException
                || failure instanceof SQLTransientException
                || failure instanceof SQLTimeoutException
                || failure instanceof TimeoutException
                || failure instanceof SocketTimeoutException
                || failure instanceof ConnectException
                || failure instanceof UnknownHostException
                || failure instanceof NoRouteToHostException;
        }
    }

    /**
     * 同一代缓存。ChatClient、ChatModel、EmbeddingModel、预校验快照和默认 provider ID 一起切换，
     * 避免分步清空时把上一代模型写进下一代客户端。
     */
    private static final class Generation {
        private final Map<String, ChatClient> clients = new ConcurrentHashMap<>();
        private final Map<String, OpenAiChatModel> chatModels = new ConcurrentHashMap<>();
        private final Map<String, EmbeddingModel> embeddingModels = new ConcurrentHashMap<>();
        /** 校验通过的一代：全部 provider 的预解析快照，请求路径不再回读数据库。 */
        private final Map<String, ProviderSnapshot> validatedSnapshots;
        /** 首次校验前的懒加载缓存，只在 {@code validatedSnapshots == null} 时使用。 */
        private final Map<String, ProviderSnapshot> lazySnapshots = new ConcurrentHashMap<>();
        private final Instant builtAt;
        private volatile String defaultChatProviderId;
        private final String defaultEmbeddingProviderId;

        private Generation(
                Map<String, ProviderSnapshot> validatedSnapshots,
                String defaultChatProviderId,
                String defaultEmbeddingProviderId,
                Instant builtAt) {
            this.validatedSnapshots = validatedSnapshots == null ? null : Map.copyOf(validatedSnapshots);
            this.defaultChatProviderId = defaultChatProviderId;
            this.defaultEmbeddingProviderId = defaultEmbeddingProviderId;
            this.builtAt = builtAt;
        }

        private static Generation lazy(Instant builtAt) {
            return new Generation(null, null, null, builtAt);
        }

        private static Generation validated(
                Map<String, ProviderSnapshot> snapshots,
                String defaultChatProviderId,
                String defaultEmbeddingProviderId,
                Instant builtAt) {
            return new Generation(snapshots, defaultChatProviderId, defaultEmbeddingProviderId, builtAt);
        }
    }

    private record ProviderSnapshot(
        String id,
        String baseUrl,
        String apiKey,
        String model,
        String embeddingModel,
        Integer embeddingDimensions,
        boolean supportsEmbedding,
        Double temperature
    ) {
    }
}
