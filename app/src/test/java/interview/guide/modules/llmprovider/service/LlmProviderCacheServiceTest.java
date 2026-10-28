package interview.guide.modules.llmprovider.service;

import interview.guide.common.ai.LlmProviderRefreshRetrier;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.LlmProviderRegistry.CacheState;
import interview.guide.common.ai.LlmProviderRegistry.CacheStatus;
import interview.guide.common.ai.LlmProviderRegistry.RefreshResult;
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
    private LlmProviderRegistry registry;

    @Mock
    private LlmProviderRefreshRetrier retrier;

    @InjectMocks
    private LlmProviderCacheService cacheService;

    @Test
    @DisplayName("手动刷新成功时返回结果并作废在途重试")
    void shouldReturnSuccessAndStopRetries() {
        Instant builtAt = Instant.parse("2026-10-28T08:00:00Z");
        RefreshResult refreshed = new RefreshResult(true, builtAt, null, false);
        when(registry.refresh()).thenReturn(refreshed);

        LlmProviderReloadResponse response = cacheService.reloadProviders();

        assertThat(response.success()).isTrue();
        assertThat(response.builtAt()).isEqualTo(builtAt);
        assertThat(response.failureReason()).isNull();
        verify(retrier).onRefreshResult(refreshed);
    }

    @Test
    @DisplayName("手动刷新失败时返回失败原因并保持原缓存")
    void shouldReturnFailureReasonAndKeepOldCache() {
        RefreshResult refreshed = new RefreshResult(false, null, "读取 Provider 配置失败", true);
        when(registry.refresh()).thenReturn(refreshed);

        LlmProviderReloadResponse response = cacheService.reloadProviders();

        assertThat(response.success()).isFalse();
        assertThat(response.builtAt()).isNull();
        assertThat(response.failureReason()).isEqualTo("读取 Provider 配置失败");
        assertThat(response.retryable()).isTrue();
        verify(retrier).onRefreshResult(refreshed);
    }

    @Test
    @DisplayName("刷新失败但没有原因时补一个可展示的默认文案")
    void shouldDefaultFailureReasonWhenRegistryReturnsNone() {
        when(registry.refresh()).thenReturn(new RefreshResult(false, null, null, true));

        LlmProviderReloadResponse response = cacheService.reloadProviders();

        assertThat(response.success()).isFalse();
        assertThat(response.failureReason()).isEqualTo("LLM Provider 缓存刷新失败，原因未知");
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
