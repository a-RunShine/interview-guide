package interview.guide.modules.llmprovider.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LLM Provider 缓存响应序列化")
class LlmProviderCacheResponseSerializationTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("刷新响应使用 camelCase 字段，时间序列化为 ISO-8601")
    void shouldSerializeReloadResponse() {
        String json = jsonMapper.writeValueAsString(
            new LlmProviderReloadResponse(true, Instant.parse("2026-10-28T08:00:00Z"), null, false));

        assertThat(json).isEqualTo(
            "{\"success\":true,\"builtAt\":\"2026-10-28T08:00:00Z\",\"failureReason\":null,"
                + "\"retryable\":false}");
    }

    @Test
    @DisplayName("状态响应字段名与前端约定的五个字段一致")
    void shouldSerializeCacheStatusResponse() {
        String json = jsonMapper.writeValueAsString(new LlmProviderCacheStatusResponse(
            "STALE", Instant.parse("2026-10-28T08:00:00Z"), "boom", 42L, true));

        assertThat(json).isEqualTo(
            "{\"state\":\"STALE\",\"lastSuccessAt\":\"2026-10-28T08:00:00Z\","
                + "\"lastFailureReason\":\"boom\",\"graceSecondsRemaining\":42,\"retryable\":true}");
    }
}
