package interview.guide.modules.llmprovider.controller;

import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.Result;
import interview.guide.modules.llmprovider.dto.LlmProviderCacheStatusResponse;
import interview.guide.modules.llmprovider.dto.LlmProviderReloadResponse;
import interview.guide.modules.llmprovider.service.LlmProviderCacheService;
import interview.guide.modules.llmprovider.service.LlmProviderConfigService;
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
@DisplayName("LLM Provider 缓存接口")
class LlmProviderControllerCacheTest {

    @Mock
    private LlmProviderConfigService configService;

    @Mock
    private LlmProviderCacheService cacheService;

    @InjectMocks
    private LlmProviderController controller;

    @Test
    @DisplayName("手动刷新成功时应返回新的构建时间")
    void shouldReturnBuiltAtWhenReloadSucceeds() {
        Instant builtAt = Instant.parse("2026-10-28T08:00:00Z");
        Result<LlmProviderReloadResponse> serviceResult =
            Result.success(new LlmProviderReloadResponse(true, builtAt, null, false));
        when(cacheService.reloadProviders()).thenReturn(serviceResult);

        Result<LlmProviderReloadResponse> result = controller.reloadProviders();

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().success()).isTrue();
        assertThat(result.getData().builtAt()).isEqualTo(builtAt);
        verify(cacheService).reloadProviders();
    }

    @Test
    @DisplayName("手动刷新失败时透传 Service 的结果而不是自己编错误码")
    void shouldDelegateFailureResultToService() {
        String reason = "Provider 'dashscope' 的 baseUrl 未配置";
        LlmProviderReloadResponse failed = new LlmProviderReloadResponse(false, null, reason, true);
        Result<LlmProviderReloadResponse> serviceResult =
            Result.error(ErrorCode.PROVIDER_CONFIG_READ_FAILED, reason, failed);
        when(cacheService.reloadProviders()).thenReturn(serviceResult);

        Result<LlmProviderReloadResponse> result = controller.reloadProviders();

        assertThat(result.getCode()).isEqualTo(ErrorCode.PROVIDER_CONFIG_READ_FAILED.getCode());
        assertThat(result.getMessage()).isEqualTo(reason);
        assertThat(result.getData()).isSameAs(failed);
        assertThat(result.getData().success()).isFalse();
        assertThat(result.getData().retryable()).isTrue();
        verify(cacheService).reloadProviders();
    }

    @Test
    @DisplayName("查询缓存状态时应返回五个字段")
    void shouldReturnAllCacheStatusFields() {
        Instant lastSuccessAt = Instant.parse("2026-10-28T08:00:00Z");
        when(cacheService.cacheStatus()).thenReturn(new LlmProviderCacheStatusResponse(
            "STALE", lastSuccessAt, "读取 Provider 配置失败", 42L, true));

        Result<LlmProviderCacheStatusResponse> result = controller.getCacheStatus();

        assertThat(result.getCode()).isEqualTo(200);
        LlmProviderCacheStatusResponse status = result.getData();
        assertThat(status.state()).isEqualTo("STALE");
        assertThat(status.lastSuccessAt()).isEqualTo(lastSuccessAt);
        assertThat(status.lastFailureReason()).isEqualTo("读取 Provider 配置失败");
        assertThat(status.graceSecondsRemaining()).isEqualTo(42L);
        assertThat(status.retryable()).isTrue();
    }
}
