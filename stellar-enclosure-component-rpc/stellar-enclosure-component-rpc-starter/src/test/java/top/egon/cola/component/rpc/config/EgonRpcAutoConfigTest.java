package top.egon.cola.component.rpc.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import top.egon.cola.component.rpc.consumer.channel.RpcConsumerChannelPool;
import top.egon.cola.component.rpc.consumer.generic.RpcGenericInvoker;
import top.egon.cola.component.rpc.consumer.lifecycle.RpcConsumerLifecycleCoordinator;
import top.egon.cola.component.rpc.consumer.lifecycle.RpcConsumerRuntimeState;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceDefinitionResolver;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceStrategyFactory;
import top.egon.cola.component.rpc.contract.identity.RpcServiceIdentity;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EgonRpcAutoConfigTest {

    private static final String SERVICE = "egon.rpc.fixture.v1.UnaryFixtureService";
    private static final String METHOD = SERVICE + "/Echo";

    @Test
    void startsOnlyTheStableServiceConsumerUnderTheCanonicalBeanNames() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EgonRpcAutoConfig.class))
                .withPropertyValues(
                        "spring.application.name=rpc-test",
                        "spring.profiles.active=prod",
                        "egon.cola.component.rpc.enabled=true",
                        "egon.cola.component.rpc.consumer.enabled=true",
                        "egon.cola.component.rpc.consumer.bindings.echo.version=2",
                        "egon.cola.component.rpc.consumer.bindings.echo.namespace=permission-prod",
                        "egon.cola.component.rpc.consumer.bindings.echo.service=echo-rpc",
                        "egon.cola.component.rpc.consumer.bindings.echo.port=18122",
                        "egon.cola.component.rpc.consumer.bindings.echo.methods[0]=" + METHOD)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("rpcConsumerChannelPool")).isInstanceOf(RpcConsumerChannelPool.class);
                    assertThat(context.getBean("rpcReferenceDefinitionResolver"))
                            .isInstanceOf(RpcReferenceDefinitionResolver.class);
                    assertThat(context.getBean("rpcReferenceStrategyFactory"))
                            .isInstanceOf(RpcReferenceStrategyFactory.class);
                    var definition = context.getBean(RpcReferenceDefinitionResolver.class)
                            .resolve("echo", new RpcServiceIdentity(SERVICE, "default", "1.0.0"), Set.of(METHOD));
                    assertThat(definition.target().dnsTarget())
                            .isEqualTo("dns:///echo-rpc.permission-prod.svc.cluster.local:18122");
                    assertThat(context.getBean("rpcGenericInvoker")).isInstanceOf(RpcGenericInvoker.class);
                    assertThat(context).doesNotHaveBean("rpcConsumerGatewayManager");
                    assertThat(context).doesNotHaveBean("rpcConsumerProviderManager");
                    assertThat(context.getBean(RpcConsumerLifecycleCoordinator.class).state())
                            .isEqualTo(RpcConsumerRuntimeState.READY);
                });
    }
}
