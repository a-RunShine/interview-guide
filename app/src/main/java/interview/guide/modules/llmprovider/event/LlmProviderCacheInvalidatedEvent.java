package interview.guide.modules.llmprovider.event;

/**
 * Provider 配置已写入。监听器在事务提交后再刷新缓存，避免新一代缓存读到未提交数据。
 */
public record LlmProviderCacheInvalidatedEvent() {
}
