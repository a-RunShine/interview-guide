package interview.guide.modules.llmprovider.listener;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.llmprovider.event.LlmProviderCacheInvalidatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@SpringJUnitConfig(LlmProviderCacheInvalidationListenerTest.TestConfig.class)
@DisplayName("LlmProvider 缓存失效监听器")
class LlmProviderCacheInvalidationListenerTest {

  @Autowired
  private ApplicationEventPublisher eventPublisher;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Autowired
  private LlmProviderRegistry registry;

  @BeforeEach
  void resetRegistry() {
    reset(registry);
  }

  @Test
  @DisplayName("事务提交后才刷新缓存")
  void reloadsAfterCommit() {
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      eventPublisher.publishEvent(new LlmProviderCacheInvalidatedEvent());
      verify(registry, never()).reload();
    });

    verify(registry).reload();
  }

  @Test
  @DisplayName("事务回滚后不刷新缓存")
  void doesNotReloadAfterRollback() {
    TransactionTemplate template = new TransactionTemplate(transactionManager);

    assertThrows(IllegalStateException.class, () -> template.executeWithoutResult(status -> {
      eventPublisher.publishEvent(new LlmProviderCacheInvalidatedEvent());
      throw new IllegalStateException("rollback");
    }));

    verify(registry, never()).reload();
  }

  @Test
  @DisplayName("没有事务时立即刷新缓存")
  void reloadsImmediatelyWithoutTransaction() {
    eventPublisher.publishEvent(new LlmProviderCacheInvalidatedEvent());

    verify(registry).reload();
  }

  @Configuration
  @EnableTransactionManagement
  static class TestConfig {

    @Bean
    LlmProviderRegistry registry() {
      return mock(LlmProviderRegistry.class);
    }

    @Bean
    LlmProviderCacheInvalidationListener listener(LlmProviderRegistry registry) {
      return new LlmProviderCacheInvalidationListener(registry);
    }

    @Bean(destroyMethod = "shutdown")
    EmbeddedDatabase dataSource() {
      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }
  }
}
