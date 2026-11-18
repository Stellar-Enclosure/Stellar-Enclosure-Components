package top.egon.cola.component.rpc.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import top.egon.cola.component.rpc.consumer.channel.RpcConsumerChannelFactory;

import static org.assertj.core.api.Assertions.assertThat;

class RpcTransportSecurityTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EgonRpcAutoConfig.class))
            .withPropertyValues(
                    "spring.profiles.active=prod",
                    "spring.application.name=rpc-h2c-test",
                    "egon.cola.component.rpc.enabled=true",
                    "egon.cola.component.rpc.consumer.enabled=true");

    @Test
    void productionConsumerStartsWithoutApplicationCertificatesOrDirectory() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RpcConsumerChannelFactory.class);
            assertThat(context.getBean(EgonRpcProperties.class).getTransport()).isEqualTo("h2c");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"tls.enabled=true", "tls.development-plaintext=true",
            "consumer.gateway-service-name=legacy", "provider.registration-mode=required", "transport=tls"})
    void productionRejectsFormerTransportAndDiscoverySettings(String setting) {
        contextRunner.withPropertyValues("egon.cola.component.rpc." + setting)
                .run(context -> assertThat(context).hasFailed());
    }
}
