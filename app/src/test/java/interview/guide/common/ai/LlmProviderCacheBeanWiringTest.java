package interview.guide.common.ai;

import interview.guide.modules.llmprovider.listener.LlmProviderCacheInvalidationListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * 回归测试：Provider 缓存三个组件必须能被 Spring 真正实例化。
 *
 * <p>{@link LlmProviderRefreshRetrier} 曾经同时暴露"生产构造器"和一个包级可见的测试构造器，
 * 两者都没有 {@code @Autowired}，Spring 找不到默认构造器就直接
 * {@code BeanCreationException: No default constructor found}，应用无法启动。
 * 已有的单元测试全部 mock 掉 retrier，因此没有任何测试覆盖到真实的 Bean 装配。
 *
 * <p>这里启动一个最小 Spring 上下文，注册的是<strong>真实</strong>的
 * {@link LlmProviderRefreshRetrier}、{@link LlmProviderCacheRefresher} 和
 * {@link LlmProviderCacheInvalidationListener}，只把叶子依赖（注册表）换成 mock，
 * 这样"装配方式回归"会像生产环境一样失败。
 */
@DisplayName("LLM Provider 缓存组件可被 Spring 实例化")
class LlmProviderCacheBeanWiringTest {

  @Test
  @DisplayName("最小上下文能创建重试器、刷新接缝与 AFTER_COMMIT 监听器")
  void springContextCreatesCacheComponents() {
    LlmProviderRegistry registry = mock(LlmProviderRegistry.class);

    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(LlmProviderRegistry.class, () -> registry);
      context.register(
          LlmProviderRefreshRetrier.class,
          LlmProviderCacheRefresher.class,
          LlmProviderCacheInvalidationListener.class);
      context.refresh();

      LlmProviderRefreshRetrier retrier = context.getBean(LlmProviderRefreshRetrier.class);
      LlmProviderCacheRefresher refresher = context.getBean(LlmProviderCacheRefresher.class);
      LlmProviderCacheInvalidationListener listener =
          context.getBean(LlmProviderCacheInvalidationListener.class);

      assertNotNull(retrier, "重试器必须是真实 Bean，而不是被 mock 掉");
      assertNotNull(refresher);
      assertSame(refresher, context.getBean(LlmProviderCacheRefresher.class),
          "刷新接缝应是单例，监听器与手动刷新共用同一条路径");
      assertNotNull(listener);
    }
    // try-with-resources 关闭上下文时会走 @PreDestroy，顺带验证重试执行器能被正常关停。
  }
}
