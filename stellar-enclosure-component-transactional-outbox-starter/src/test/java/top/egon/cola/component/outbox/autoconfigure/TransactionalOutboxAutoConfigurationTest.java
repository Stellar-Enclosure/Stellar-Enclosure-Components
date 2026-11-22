package top.egon.cola.component.outbox.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import top.egon.cola.component.common.id.autoconfigure.IdGeneratorAutoConfiguration;
import top.egon.cola.component.outbox.aop.TransactionalMessageAop;
import top.egon.cola.component.outbox.api.TransactionalOutbox;
import top.egon.cola.component.outbox.delivery.DeliveryHandler;
import top.egon.cola.component.outbox.dispatch.OutboxDispatcher;
import top.egon.cola.component.outbox.dispatch.OutboxPoller;
import top.egon.cola.component.outbox.store.OutboxStore;
import top.egon.cola.component.outbox.statemachine.OutboxLifecycleService;
import top.egon.cola.component.outbox.transaction.OutboxAfterCommitBuffer;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionalOutboxAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    OutboxMetricsAutoConfiguration.class,
                    TransactionalOutboxAutoConfiguration.class,
                    OutboxStateMachineAutoConfiguration.class,
                    OutboxHttpAutoConfiguration.class,
                    OutboxRabbitAutoConfiguration.class
            ));

    @Test
    void shouldBackOffWithoutDataSource() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean(TransactionalOutbox.class)
                .doesNotHaveBean(OutboxDispatcher.class));
    }

    @Test
    void shouldCreateOneCoreRuntimeForUniqueInfrastructure() {
        configuredContext().run(context -> {
            assertThat(context)
                        .hasSingleBean(TransactionalOutbox.class)
                        .hasSingleBean(OutboxDispatcher.class)
                        .hasSingleBean(OutboxAfterCommitBuffer.class)
                        .hasSingleBean(TransactionalMessageAop.class);
            assertThat(context.getBean("outboxStateMachineOutboxProperties"))
                    .isSameAs(context.getBean(TransactionalOutboxProperties.class));
            assertThat(context).hasSingleBean(OutboxLifecycleService.class);
        });
    }

    @Test
    void shouldUseTheMicrometerMetricsVariantForTheDispatcher() {
        configuredContext().withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(OutboxDispatcher.class)
                            .hasSingleBean(top.egon.cola.component.outbox.observability.OutboxMetrics.class);
                    assertThat(context.getBean(top.egon.cola.component.outbox.observability.OutboxMetrics.class))
                            .isInstanceOf(top.egon.cola.component.outbox.observability.MicrometerOutboxMetrics.class)
                            .isSameAs(context.getBean("outboxMetrics"))
                            .isSameAs(context.getBean("micrometerOutboxMetrics"));
                });
    }

    @Test
    void shouldDisableEveryRuntimeBean() {
        configuredContext()
                .withPropertyValues(
                        "egon.cola.component.transactional-outbox.enabled=false"
                )
                .run(context -> assertThat(context)
                        .doesNotHaveBean(TransactionalOutbox.class)
                        .doesNotHaveBean(OutboxDispatcher.class)
                        .doesNotHaveBean(OutboxPoller.class));
    }

    @Test
    void shouldBackOffAnnotationAdvisorOnly() {
        configuredContext()
                .withPropertyValues(
                        "egon.cola.component.transactional-outbox.annotation.enabled=false"
                )
                .run(context -> assertThat(context)
                        .hasSingleBean(TransactionalOutbox.class)
                        .doesNotHaveBean(TransactionalMessageAop.class));
    }

    @Test
    void shouldBackOffForCustomTransactionalOutbox() {
        TransactionalOutbox custom = mock(TransactionalOutbox.class);
        configuredContext()
                .withBean(TransactionalOutbox.class, () -> custom)
                .run(context -> assertThat(context)
                        .getBean(TransactionalOutbox.class)
                        .isSameAs(custom));
    }

    @Test
    void managedDispatchChecksTheCurrentAuthorityForBothWakeupPaths() {
        var authority = mock(IdGeneratorAutoConfiguration.class);
        doNothing().doNothing().doThrow(new IllegalStateException("lease lost"))
                .when(authority).assertRedisLeaseAvailable();
        managedContext().withBean(IdGeneratorAutoConfiguration.class.getName(),
                        IdGeneratorAutoConfiguration.class, () -> authority)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var dispatcher = context.getBean(OutboxDispatcher.class);
                    var store = context.getBean(OutboxStore.class);

                    dispatcher.submitDue();
                    dispatcher.submitMessageIds(List.of("message-1"));
                    dispatcher.submitDue();
                    dispatcher.submitMessageIds(List.of("message-2"));

                    verify(authority, times(4)).assertRedisLeaseAvailable();
                    verify(store).claimDue(eq(Set.of("test-channel")), anyInt(), anyString(), any());
                    verify(store).claimByMessageIds(eq(List.of("message-1")), eq(Set.of("test-channel")),
                            anyInt(), anyString(), any());
                    verify(store, never()).claimByMessageIds(eq(List.of("message-2")), any(), anyInt(), anyString(), any());
                });
    }

    @Test
    void missingManagedAuthorityStopsRecoveryAndBothWakeupPaths() {
        managedContext().withPropertyValues("egon.cola.component.transactional-outbox.retry.recover-exhausted=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var dispatcher = context.getBean(OutboxDispatcher.class);
                    var store = context.getBean(OutboxStore.class);

                    dispatcher.submitDue();
                    dispatcher.submitMessageIds(List.of("message-1"));

                    verify(store, never()).recoverExhausted(anyInt(), any());
                    verify(store, never()).claimDue(any(), anyInt(), anyString(), any());
                    verify(store, never()).claimByMessageIds(any(), any(), anyInt(), anyString(), any());
                });
    }

    @Test
    void dispatchChecksTheLeaseAgainAfterRecoveryBeforeClaiming() {
        var authority = mock(IdGeneratorAutoConfiguration.class);
        doNothing().doThrow(new IllegalStateException("lease lost"))
                .when(authority).assertRedisLeaseAvailable();
        managedContext().withPropertyValues("egon.cola.component.transactional-outbox.retry.recover-exhausted=true")
                .withBean(IdGeneratorAutoConfiguration.class.getName(), IdGeneratorAutoConfiguration.class, () -> authority)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var dispatcher = context.getBean(OutboxDispatcher.class);
                    var store = context.getBean(OutboxStore.class);

                    dispatcher.submitDue();

                    verify(store).recoverExhausted(anyInt(), any());
                    verify(store, never()).claimDue(any(), anyInt(), anyString(), any());
                });
    }

    private ApplicationContextRunner managedContext() {
        var handler = mock(DeliveryHandler.class);
        when(handler.channel()).thenReturn("test-channel");
        return configuredContext()
                .withBean(DeliveryHandler.class, () -> handler)
                .withBean("outboxDeliveryExecutor", SyncTaskExecutor.class, SyncTaskExecutor::new)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("stellar-systemEnvironment",
                                Map.of("EGON_COLA_COMPONENT_ID_REDIS_REQUIRELEASE", "true"))));
    }

    private ApplicationContextRunner configuredContext() {
        return contextRunner
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(
                        PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class)
                )
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean("egonColaMybatisPlusClock", Clock.class, Clock::systemUTC)
                .withBean(OutboxStore.class, () -> mock(OutboxStore.class))
                .withPropertyValues(
                        "egon.cola.component.transactional-outbox.storage.validate-schema=false",
                        "egon.cola.component.transactional-outbox.polling.enabled=false"
                );
    }
}
