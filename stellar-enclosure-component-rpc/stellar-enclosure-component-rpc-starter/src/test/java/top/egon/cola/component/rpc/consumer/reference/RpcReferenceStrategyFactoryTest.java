package top.egon.cola.component.rpc.consumer.reference;

import org.junit.jupiter.api.Test;
import top.egon.cola.component.rpc.consumer.channel.RpcChannelLease;
import top.egon.cola.component.rpc.consumer.channel.RpcConsumerChannelPool;
import top.egon.cola.component.rpc.contract.identity.RpcServiceIdentity;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RpcReferenceStrategyFactoryTest {
    @Test
    void retainsStableTargetUntilCloseAndAcquiresIndependentCallLeases() {
        var pool = mock(RpcConsumerChannelPool.class);
        var anchor = mock(RpcChannelLease.class);
        var call = mock(RpcChannelLease.class);
        var definition = definition();
        when(pool.acquire(definition.target())).thenReturn(anchor, call);
        try (var factory = new RpcReferenceStrategyFactory(pool)) {
            var strategy = factory.create(definition);
            assertThat(strategy).isInstanceOf(KubernetesServiceRpcReferenceStrategy.class);
            assertThat(strategy.target()).isEqualTo(definition.target());
            assertThat(strategy.revision()).isEqualTo(7);
            assertThat(strategy.queryIdentity()).isEqualTo(definition.queryIdentity());
            try (var acquired = strategy.acquire()) { assertThat(acquired).isSameAs(call); }
            verify(call).close();
            strategy.close();
            strategy.close();
            assertThatThrownBy(strategy::acquire).isInstanceOf(IllegalStateException.class);
        }
        verify(pool, times(2)).acquire(definition.target());
        verify(anchor).close();
    }

    @Test
    void closesAllOwnedStrategiesAndRejectsNewReferencesAfterShutdown() {
        var pool = mock(RpcConsumerChannelPool.class);
        var first = mock(RpcChannelLease.class);
        var second = mock(RpcChannelLease.class);
        when(pool.acquire(definition().target())).thenReturn(first, second);
        var factory = new RpcReferenceStrategyFactory(pool);
        factory.create(definition());
        factory.create(definition());
        factory.close();
        factory.close();
        verify(first).close();
        verify(second).close();
        assertThatThrownBy(() -> factory.create(definition())).isInstanceOf(IllegalStateException.class);
    }

    private static RpcReferenceDefinition definition() {
        var target = new RpcServiceTargetVO("identity-directory", 7, "identity-prod", "identity-directory", 9090,
                Set.of("egon.rpc.fixture.v1.UnaryFixtureService/Echo"));
        return new RpcReferenceDefinition(target,
                new RpcServiceIdentity("egon.rpc.fixture.v1.UnaryFixtureService", "test", "1.0.0"), Map.of());
    }
}
