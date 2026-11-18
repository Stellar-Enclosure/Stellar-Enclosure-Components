package top.egon.cola.component.rpc.consumer.lifecycle;

import org.junit.jupiter.api.Test;
import top.egon.cola.component.rpc.consumer.channel.RpcConsumerChannelPool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RpcConsumerLifecycleCoordinatorTest {

    @Test
    void startsInOrderAndStopsInReverseOwnershipOrderOnce() {
        RpcConsumerChannelPool pool = mock(RpcConsumerChannelPool.class);
        when(pool.isRunning()).thenReturn(true);
        List<String> events = new ArrayList<>();
        RpcConsumerLifecycleCoordinator coordinator = new RpcConsumerLifecycleCoordinator(
                pool, List.of(() -> events.add("strategy.close"), () -> events.add("cache.close")));

        coordinator.start();
        assertThat(coordinator.state()).isEqualTo(RpcConsumerRuntimeState.READY);
        coordinator.requireAccepting();
        AtomicInteger callbacks = new AtomicInteger();
        coordinator.stop(callbacks::incrementAndGet);
        coordinator.stop(callbacks::incrementAndGet);

        assertThat(coordinator.state()).isEqualTo(RpcConsumerRuntimeState.STOPPED);
        assertThat(callbacks).hasValue(1);
        verify(pool).start();
        assertThat(events).containsExactly("cache.close", "strategy.close");
        verify(pool).close();
    }

    @Test
    void startupFailureClosesAlreadyStartedResourcesAndRejectsCalls() {
        RpcConsumerChannelPool pool = mock(RpcConsumerChannelPool.class);
        RuntimeException failure = new RuntimeException("pool-start");
        when(pool.isRunning()).thenThrow(failure);
        RpcConsumerLifecycleCoordinator coordinator = new RpcConsumerLifecycleCoordinator(
                pool, List.of());

        assertThatThrownBy(coordinator::start).isSameAs(failure);
        assertThat(coordinator.state()).isEqualTo(RpcConsumerRuntimeState.FAILED);
        assertThatThrownBy(coordinator::requireAccepting)
                .isInstanceOf(RuntimeException.class);
        verify(pool).close();
    }
}
