package top.egon.cola.component.outbox.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.statemachine.config.StateMachineBuilder;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.transaction.PlatformTransactionManager;
import top.egon.cola.component.outbox.common.exception.OutboxConfigurationException;
import top.egon.cola.component.outbox.delivery.DeliveryHandler;
import top.egon.cola.component.outbox.delivery.DeliveryHandlerRegistry;
import top.egon.cola.component.outbox.delivery.DeliveryResult;
import top.egon.cola.component.outbox.dispatch.OutboxDispatcher;
import top.egon.cola.component.outbox.store.OutboxClaimBatchBO;
import top.egon.cola.component.outbox.store.OutboxClaimCommand;
import top.egon.cola.component.outbox.store.OutboxFinishCommand;
import top.egon.cola.component.outbox.store.OutboxFinishStatusEnum;
import top.egon.cola.component.outbox.store.OutboxRecord;
import top.egon.cola.component.outbox.store.OutboxStatus;
import top.egon.cola.component.outbox.delivery.statemachine.BusinessStateMachineDeliveryHandler;
import top.egon.cola.component.outbox.statemachine.BusinessStateMachineContextExecutor;
import top.egon.cola.component.outbox.statemachine.BusinessStateMachineDefinitionStrategy;
import top.egon.cola.component.outbox.statemachine.BusinessStateMachineRepository;
import top.egon.cola.component.outbox.statemachine.BusinessStateMachineService;
import top.egon.cola.component.outbox.store.OutboxStore;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxBusinessStateMachineAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = configuredContext(mock(OutboxStore.class));

    private ApplicationContextRunner configuredContext(OutboxStore selectedStore) {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    OutboxMetricsAutoConfiguration.class,
                    TransactionalOutboxAutoConfiguration.class,
                    OutboxStateMachineAutoConfiguration.class,
                    OutboxBusinessStateMachineAutoConfiguration.class))
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean("egonColaMybatisPlusClock", Clock.class, Clock::systemUTC)
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
            .withBean(OutboxStore.class, () -> selectedStore)
            .withPropertyValues(
                    "egon.cola.component.transactional-outbox.storage.validate-schema=false",
                    "egon.cola.component.transactional-outbox.polling.enabled=false"
            );
    }

    @Test
    void businessAdapterIsAbsentByDefault() {
        contextRunner.run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(BusinessStateMachineService.class)
                .doesNotHaveBean(BusinessStateMachineDeliveryHandler.class));
    }

    @Test
    void enabledBusinessAdapterFailsFastWithoutDefinitionOrContextExecutor() {
        contextRunner
                .withPropertyValues("egon.cola.component.transactional-outbox.state-machine.business.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(OutboxConfigurationException.class);
                });
    }

    @Test
    void enabledBusinessAdapterRejectsMultipleContextExecutors() {
        contextRunner
                .withPropertyValues("egon.cola.component.transactional-outbox.state-machine.business.enabled=true")
                .withBean("orderPaymentDefinition", BusinessStateMachineDefinitionStrategy.class,
                        TestDefinitionStrategy::new)
                .withBean("firstContextExecutor", BusinessStateMachineContextExecutor.class,
                        () -> mock(BusinessStateMachineContextExecutor.class))
                .withBean("secondContextExecutor", BusinessStateMachineContextExecutor.class,
                        () -> mock(BusinessStateMachineContextExecutor.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(OutboxConfigurationException.class);
                });
    }

    @Test
    void enabledBusinessAdapterRegistersTheNamedServiceAndHandlerForValidSpi() {
        BusinessStateMachineDefinitionStrategy definition = new TestDefinitionStrategy();
        contextRunner
                .withPropertyValues("egon.cola.component.transactional-outbox.state-machine.business.enabled=true")
                .withBean("orderPaymentDefinition", BusinessStateMachineDefinitionStrategy.class, () -> definition)
                .withBean("outboxBusinessStateMachineContextExecutor", BusinessStateMachineContextExecutor.class,
                        () -> mock(BusinessStateMachineContextExecutor.class))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasBean("outboxBusinessStateMachineDefinitions")
                        .hasBean("outboxBusinessStateMachineTransaction")
                        .hasBean("outboxBusinessStateMachineService")
                        .hasSingleBean(BusinessStateMachineDeliveryHandler.class)
                        .hasSingleBean(DeliveryHandler.class));
    }

    @Test
    void enabledBusinessAdapterRejectsDuplicateDestinations() {
        contextRunner
                .withPropertyValues("egon.cola.component.transactional-outbox.state-machine.business.enabled=true")
                .withBean("firstDefinition", BusinessStateMachineDefinitionStrategy.class,
                        TestDefinitionStrategy::new)
                .withBean("secondDefinition", BusinessStateMachineDefinitionStrategy.class,
                        TestDefinitionStrategy::new)
                .withBean("outboxBusinessStateMachineContextExecutor", BusinessStateMachineContextExecutor.class,
                        () -> mock(BusinessStateMachineContextExecutor.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(OutboxConfigurationException.class);
                });
    }

    @Test
    void enabledBusinessAdapterRejectsAFactoryThatReusesItsMachine() throws Exception {
        TestDefinitionStrategy definition = new TestDefinitionStrategy(true);
        contextRunner
                .withPropertyValues("egon.cola.component.transactional-outbox.state-machine.business.enabled=true")
                .withBean("reusedDefinition", BusinessStateMachineDefinitionStrategy.class, () -> definition)
                .withBean("outboxBusinessStateMachineContextExecutor", BusinessStateMachineContextExecutor.class,
                        () -> mock(BusinessStateMachineContextExecutor.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(OutboxConfigurationException.class);
                });
    }

    @Test
    void legal_missing_definitions_keep_the_configured_classification_through_jdk_and_class_proxies() {
        for (boolean classProxy : List.of(false, true)) {
            for (boolean retry : List.of(false, true)) {
                verifyProxiedDelivery(classProxy, retry, "11",
                        retry ? DeliveryResult.Kind.RETRYABLE_FAILURE : DeliveryResult.Kind.PERMANENT_FAILURE,
                        "OUTBOX_SM_DEFINITION_MISSING");
            }
        }
    }

    @Test
    void a_missing_definition_does_not_bypass_the_tenant_header_check() {
        verifyProxiedDelivery(false, true, "99", DeliveryResult.Kind.PERMANENT_FAILURE,
                "OUTBOX_SM_TENANT_REJECTED");
    }

    private void verifyProxiedDelivery(boolean classProxy, boolean retry, String tenantHeader,
                                       DeliveryResult.Kind expectedKind, String expectedCode) {
        OutboxStore store = mock(OutboxStore.class);
        when(store.supportsDispatchMode(any())).thenReturn(true);
        when(store.dispatchSlots()).thenReturn(List.of(0));
        AtomicBoolean available = new AtomicBoolean(true);
        AtomicReference<OutboxFinishCommand> confirmed = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        when(store.claim(any())).thenAnswer(invocation -> {
            OutboxClaimCommand command = invocation.getArgument(0);
            Instant now = Instant.now();
            List<OutboxRecord> records = available.compareAndSet(true, false)
                    ? List.of(new OutboxRecord(1, "missing-definition", "missing-definition-key", "a".repeat(64),
                    "statemachine", "order-payment:2", """
                    {"eventId":"business-event-1","tenantId":11,"machineKey":"order-payment",
                     "definitionVersion":2,"businessId":"order-1","eventType":"COMPLETE",
                     "occurredAt":"2026-11-23T00:00:00Z","payload":{}}
                    """, "application/json", "1", Map.of("egon-event-id", "business-event-1",
                    "egon-machine-key", "order-payment", "egon-definition-version", "2", "egon-tenant-id", tenantHeader),
                    null, OutboxStatus.PROCESSING, 1, 3, now, command.getClaimToken(), now.plusSeconds(60),
                    null, null, now, now, null)) : List.of();
            return OutboxClaimBatchBO.builder().databaseTime(now).records(records).build();
        });
        when(store.finishDelivery(any())).thenAnswer(invocation -> {
            confirmed.set(invocation.getArgument(0));
            finished.countDown();
            return OutboxFinishStatusEnum.APPLIED;
        });
        BusinessStateMachineContextExecutor executor = mock(BusinessStateMachineContextExecutor.class);
        configuredContext(store)
                .withBean("businessHandlerProxy", BeanPostProcessor.class, () -> new BeanPostProcessor() {
                    @Override
                    public Object postProcessAfterInitialization(Object bean, String name) {
                        if (!name.equals("outboxBusinessStateMachineDeliveryHandler")) return bean;
                        ProxyFactory proxy = new ProxyFactory(bean);
                        proxy.setInterfaces(DeliveryHandler.class);
                        proxy.setProxyTargetClass(classProxy);
                        return proxy.getProxy();
                    }
                })
                .withBean("orderPaymentDefinition", BusinessStateMachineDefinitionStrategy.class, TestDefinitionStrategy::new)
                .withBean("outboxBusinessStateMachineContextExecutor", BusinessStateMachineContextExecutor.class, () -> executor)
                .withPropertyValues(
                        "egon.cola.component.transactional-outbox.dispatch.mode=LOCAL",
                        "egon.cola.component.transactional-outbox.polling.enabled=true",
                        "egon.cola.component.transactional-outbox.polling.concurrency=1",
                        "egon.cola.component.transactional-outbox.polling.jitter=0",
                        "egon.cola.component.transactional-outbox.polling.fixed-delay=1s",
                        "egon.cola.component.transactional-outbox.cleanup.enabled=false",
                        "egon.cola.component.transactional-outbox.state-machine.business.enabled=true",
                        "egon.cola.component.transactional-outbox.state-machine.business.retry-on-missing-definition=" + retry)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DeliveryHandler handler = context.getBean(DeliveryHandlerRegistry.class).required("statemachine");
                    assertThat(AopUtils.isAopProxy(handler)).isTrue();
                    assertThatThrownBy(() -> handler.validateDestination("order-payment:2"))
                            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OUTBOX_SM_DEFINITION_MISSING");
                    context.getBean(OutboxDispatcher.class).submitDue();
                    assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(confirmed.get().getDeliveryResult().kind()).isEqualTo(expectedKind);
                    assertThat(confirmed.get().getDeliveryResult().code()).isEqualTo(expectedCode);
                    verifyNoInteractions(executor);
                });
    }

    private static final class TestDefinitionStrategy implements BusinessStateMachineDefinitionStrategy {

        private final StateMachineFactory<String, String> factory;
        private final String destination;

        private TestDefinitionStrategy() {
            this("order-payment:1", false);
        }

        private TestDefinitionStrategy(boolean reuseMachine) throws Exception {
            this("order-payment:1", reuseMachine);
        }

        private TestDefinitionStrategy(String destination, boolean reuseMachine) {
            this.destination = destination;
            try {
                factory = mock(StateMachineFactory.class);
                if (reuseMachine) {
                    var machine = testMachine();
                    when(factory.getStateMachine()).thenReturn(machine);
                } else {
                    when(factory.getStateMachine()).thenAnswer(invocation -> testMachine());
                }
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to create test business state machine", exception);
            }
        }

        private static org.springframework.statemachine.StateMachine<String, String> testMachine() throws Exception {
            StateMachineBuilder.Builder<String, String> builder = StateMachineBuilder.builder();
            builder.configureConfiguration().withConfiguration().autoStartup(false);
            builder.configureStates().withStates().initial("WAITING").states(Set.of("WAITING", "DONE"));
            builder.configureTransitions().withExternal().source("WAITING").target("DONE").event("COMPLETE");
            return builder.build();
        }

        @Override
        public String destination() {
            return destination;
        }

        @Override
        public StateMachineFactory<String, String> stateMachineFactory() {
            return factory;
        }

        @Override
        public BusinessStateMachineRepository repository() {
            return mock(BusinessStateMachineRepository.class);
        }

        @Override
        public void validateEvent(top.egon.cola.component.outbox.statemachine.BusinessStateMachineEvent event) {
        }
    }
}
