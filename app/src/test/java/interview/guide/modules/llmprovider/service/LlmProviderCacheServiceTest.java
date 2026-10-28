package interview.guide.modules.llmprovider.service;

import interview.guide.common.ai.LlmProviderCacheRefresher;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.LlmProviderRegistry.CacheState;
import interview.guide.common.ai.LlmProviderRegistry.CacheStatus;
import interview.guide.common.ai.LlmProviderRegistry.RefreshResult;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.Result;
import interview.guide.modules.llmprovider.dto.LlmProviderCacheStatusResponse;
import interview.guide.modules.llmprovider.dto.LlmProviderReloadResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LLM Provider 缓存服务")
class LlmProviderCacheServiceTest {

    @Mock
    private LlmProviderCacheRefresher refresher;

    @Mock
    private LlmProviderRegistry registry;

    @InjectMocks
    private LlmProviderCacheService cacheService;

    @Test
    @DisplayName("手动刷新成功时返回结果并作废在途重试")
    void shouldReturnSuccessAndStopRetries() {
        Instant builtAt = Instant.parse("2026-10-28T08:00:00Z");
        RefreshResult refreshed = new RefreshResult(true, builtAt, null, false);
        when(refresher.refreshAndScheduleRetry()).thenReturn(refreshed);

        Result<LlmProviderReloadResponse> result = cacheService.reloadProviders();

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().success()).isTrue();
        assertThat(result.getData().builtAt()).isEqualTo(builtAt);
        assertThat(result.getData().failureReason()).isNull();
        verify(refresher).refreshAndScheduleRetry();
    }

    @Test
    @DisplayName("手动刷新失败时仍带回失败数据和真实的 retryable")
    void shouldReturnFailureReasonAndKeepOldCache() {
        RefreshResult refreshed = new RefreshResult(false, null, "读取 Provider 配置失败", true);
        when(refresher.refreshAndScheduleRetry()).thenReturn(refreshed);

        Result<LlmProviderReloadResponse> result = cacheService.reloadProviders();

        assertThat(result.getCode()).isEqualTo(ErrorCode.PROVIDER_CONFIG_READ_FAILED.getCode());
        assertThat(result.getMessage()).isEqualTo("读取 Provider 配置失败");
        assertThat(result.getData()).isNotNull();
        assertThat(result.getData().success()).isFalse();
        assertThat(result.getData().builtAt()).isNull();
        assertThat(result.getData().failureReason()).isEqualTo("读取 Provider 配置失败");
        assertThat(result.getData().retryable()).isTrue();
        verify(refresher).refreshAndScheduleRetry();
    }

    @Test
    @DisplayName("刷新失败但没有原因时补一个可展示的默认文案")
    void shouldDefaultFailureReasonWhenRegistryReturnsNone() {
        when(refresher.refreshAndScheduleRetry())
            .thenReturn(new RefreshResult(false, null, null, true));

        Result<LlmProviderReloadResponse> result = cacheService.reloadProviders();

        assertThat(result.getData().success()).isFalse();
        assertThat(result.getData().failureReason())
            .isEqualTo("LLM Provider 缓存刷新失败，原因未知");
        assertThat(result.getData().retryable()).isTrue();
    }

    @Test
    @DisplayName("查询缓存状态时透传状态名与宽限剩余秒数")
    void shouldExposeCacheStatusNameAndGrace() {
        Instant lastSuccessAt = Instant.parse("2026-10-28T08:00:00Z");
        when(registry.status()).thenReturn(
            new CacheStatus(CacheState.EXPIRED, lastSuccessAt, "密钥无法解密", 0L, false));

        LlmProviderCacheStatusResponse status = cacheService.cacheStatus();

        assertThat(status.state()).isEqualTo("EXPIRED");
        assertThat(status.lastSuccessAt()).isEqualTo(lastSuccessAt);
        assertThat(status.lastFailureReason()).isEqualTo("密钥无法解密");
        assertThat(status.graceSecondsRemaining()).isZero();
        assertThat(status.retryable()).isFalse();
    }
}
