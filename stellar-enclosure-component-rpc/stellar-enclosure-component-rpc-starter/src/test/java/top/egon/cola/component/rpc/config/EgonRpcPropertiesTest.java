package top.egon.cola.component.rpc.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class EgonRpcPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EgonRpcAutoConfig.class))
            .withPropertyValues("egon.cola.component.rpc.enabled=true");

    @Test
    void preservesTransportCapacityAndProcessIdentityDefaults() {
        EgonRpcProperties properties = new EgonRpcProperties();
        assertThat(properties.getProvider().getMaxInboundMessageSize()).isEqualTo(4 * 1024 * 1024);
        assertThat(properties.getConsumer().getMaxInboundMessageSize()).isEqualTo(4 * 1024 * 1024);
        assertThat(properties.getConsumer().getGenericCacheMaxEntries()).isEqualTo(256);
        assertThat(properties.getConsumer().getGenericCacheIdleTimeoutMs()).isEqualTo(600_000L);
        assertThat(properties.getConsumer().getBindings()).isEmpty();
        assertThat(properties.getIdentity().getEnv()).isEqualTo("default");
        assertThat(properties.getIdentity().getHost()).isNull();
        assertThat(properties.getIdentity().getInstanceId()).isNull();
    }

    @Test
    void validatesConfigurationLimitsInTheBootBinder() {
        contextRunner.withPropertyValues("egon.cola.component.rpc.consumer.generic-cache-max-entries=4097")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("genericCacheMaxEntries");
                });
    }

    @Test
    void rejectsAnIncompleteBindingEvenWithoutAnyReferenceBean() {
        contextRunner.withPropertyValues(
                "egon.cola.component.rpc.consumer.bindings.echo.version=1",
                "egon.cola.component.rpc.consumer.bindings.echo.namespace=permission-prod",
                "egon.cola.component.rpc.consumer.bindings.echo.service=echo-rpc",
                "egon.cola.component.rpc.consumer.bindings.echo.port=18122")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("methods");
                });
    }
}
