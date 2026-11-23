package top.egon.cola.component.outbox.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionalOutboxPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void shouldExposeSafeDefaults() {
        contextRunner.run(context -> {
            TransactionalOutboxProperties properties =
                    context.getBean(TransactionalOutboxProperties.class);

            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getAnnotation().isEnabled()).isTrue();
            assertThat(properties.getAnnotation().getOrder())
                    .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 100);
            assertThat(properties.getPolling().getFixedDelay()).isEqualTo(Duration.ofSeconds(1));
            assertThat(properties.getPolling().getBatchSize()).isEqualTo(100);
            assertThat(properties.getPolling().getConcurrency()).isEqualTo(4);
            assertThat(properties.getDelivery().getTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.getDelivery().getLeaseDuration()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.getRetry().getMaxAttempts()).isEqualTo(10);
            assertThat(properties.getHttp().isEnabled()).isFalse();
            assertThat(properties.getRabbitmq().isEnabled()).isFalse();
            assertThat(properties.getCleanup().isEnabled()).isFalse();
        });
    }

    @Test
    void shouldBindNestedDestinationsAndBeanNames() {
        contextRunner.withPropertyValues(
                "egon.cola.component.transactional-outbox.storage.data-source-bean-name=businessDataSource",
                "egon.cola.component.transactional-outbox.storage.transaction-manager-bean-name=businessTx",
                "egon.cola.component.transactional-outbox.http.enabled=true",
                "egon.cola.component.transactional-outbox.http.destinations.order-callback.uri=https://orders.test/callback",
                "egon.cola.component.transactional-outbox.http.destinations.order-callback.method=PUT",
                "egon.cola.component.transactional-outbox.rabbitmq.destinations.order-created.exchange=order.events",
                "egon.cola.component.transactional-outbox.rabbitmq.destinations.order-created.routing-key=order.created"
        ).run(context -> {
            TransactionalOutboxProperties properties =
                    context.getBean(TransactionalOutboxProperties.class);

            assertThat(properties.getStorage().getDataSourceBeanName())
                    .isEqualTo("businessDataSource");
            assertThat(properties.getStorage().getTransactionManagerBeanName())
                    .isEqualTo("businessTx");
            assertThat(properties.getHttp().getDestinations())
                    .containsKey("order-callback");
            assertThat(properties.getRabbitmq().getDestinations())
                    .containsKey("order-created");
        });
    }

    @Test
    void shouldExposeCoordinatedDispatchDefaultsWithoutActivatingThem() throws Exception {
        TransactionalOutboxProperties properties = new TransactionalOutboxProperties();
        Object dispatch = property(properties, "getDispatch");
        Object coordination = property(properties, "getCoordination");

        assertThat(property(dispatch, "getMode").toString()).isEqualTo("LEGACY");
        assertThat(property(dispatch, "getMode").getClass().getSimpleName()).isEqualTo("DispatchMode");
        assertThat(property(dispatch, "isConsumptionEnabled")).isEqualTo(true);
        assertThat(property(dispatch, "getMaxClaimBatch")).isEqualTo(1);
        assertThat(property(dispatch, "getMaxConcurrentShards")).isEqualTo(2);
        assertThat(property(dispatch, "getAckReserve")).isEqualTo(Duration.ofSeconds(1));
        assertThat(property(coordination, "getHeartbeatInterval")).isEqualTo(Duration.ofSeconds(5));
        assertThat(property(coordination, "getLeaseDuration")).isEqualTo(Duration.ofSeconds(20));
        assertThat(property(coordination, "getMembershipTtl")).isEqualTo(Duration.ofSeconds(30));
        assertThat(property(coordination, "getClaimTimeout")).isEqualTo(Duration.ofSeconds(2));
        assertThat(property(coordination, "getMemberRetention")).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void shouldBindDispatchAndCoordinationAlongsideExistingPollingKeys() {
        contextRunner.withPropertyValues(
                "egon.cola.component.transactional-outbox.dispatch.mode=COORDINATED",
                "egon.cola.component.transactional-outbox.dispatch.consumption-enabled=false",
                "egon.cola.component.transactional-outbox.dispatch.max-claim-batch=4",
                "egon.cola.component.transactional-outbox.dispatch.max-concurrent-shards=3",
                "egon.cola.component.transactional-outbox.dispatch.ack-reserve=2s",
                "egon.cola.component.transactional-outbox.coordination.heartbeat-interval=6s",
                "egon.cola.component.transactional-outbox.coordination.lease-duration=24s",
                "egon.cola.component.transactional-outbox.coordination.membership-ttl=36s",
                "egon.cola.component.transactional-outbox.coordination.claim-timeout=3s",
                "egon.cola.component.transactional-outbox.coordination.member-retention=2h",
                "egon.cola.component.transactional-outbox.polling.batch-size=20"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            TransactionalOutboxProperties properties = context.getBean(TransactionalOutboxProperties.class);
            Object dispatch = property(properties, "getDispatch");
            Object coordination = property(properties, "getCoordination");

            assertThat(property(dispatch, "getMode").toString()).isEqualTo("COORDINATED");
            assertThat(property(dispatch, "isConsumptionEnabled")).isEqualTo(false);
            assertThat(property(dispatch, "getMaxClaimBatch")).isEqualTo(4);
            assertThat(property(dispatch, "getMaxConcurrentShards")).isEqualTo(3);
            assertThat(property(dispatch, "getAckReserve")).isEqualTo(Duration.ofSeconds(2));
            assertThat(property(coordination, "getHeartbeatInterval")).isEqualTo(Duration.ofSeconds(6));
            assertThat(property(coordination, "getLeaseDuration")).isEqualTo(Duration.ofSeconds(24));
            assertThat(property(coordination, "getMembershipTtl")).isEqualTo(Duration.ofSeconds(36));
            assertThat(property(coordination, "getClaimTimeout")).isEqualTo(Duration.ofSeconds(3));
            assertThat(property(coordination, "getMemberRetention")).isEqualTo(Duration.ofHours(2));
            assertThat(properties.getPolling().getBatchSize()).isEqualTo(20);
        });
    }

    private static Object property(Object target, String getter) throws ReflectiveOperationException {
        return target.getClass().getMethod(getter).invoke(target);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TransactionalOutboxProperties.class)
    static class PropertiesConfiguration {
    }
}
