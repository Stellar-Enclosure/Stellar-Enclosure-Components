package top.egon.cola.component.common.cache.consistency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.egon.cola.component.common.cache.autoconfigure.EgonColaCacheProperties;
import top.egon.cola.component.common.cache.core.EgonColaTwoLevelCacheManager;
import top.egon.cola.component.common.cache.event.EgonColaCacheChangedOperation;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EgonColaCacheMutationExecutorTest {

    private final EgonColaTwoLevelCacheManager manager = mock(EgonColaTwoLevelCacheManager.class);
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final EgonColaCacheProperties properties = new EgonColaCacheProperties();

    @BeforeEach
    void setUp() {
        MDC.put("tenantId", "41");
        when(manager.scheduler()).thenReturn(scheduler);
        properties.setSecondEvictDelay(Duration.ofMillis(250));
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN));
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        MDC.clear();
    }

    @Test
    void writeBeforeDeleteRunsAfterSuccessfulCommitAndNotOnRollback() {
        var executor = executor();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        assertThat(executor.execute("users", List.of("41:7"), () -> "written")).isEqualTo("written");
        verify(manager, never()).applyLocalEviction(anyString(), anyList());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(manager).applyLocalEviction("users", List.of("41:7"));
        verify(manager).publish("users", EgonColaCacheChangedOperation.EVICT, List.of("41:7"));
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        executor.execute("users", List.of("41:8"), () -> "rolled-back");
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(manager, never()).applyLocalEviction("users", List.of("41:8"));
    }

    @Test
    void deleteBeforeWriteRunsBeforeTheBusinessOperation() {
        properties.setConsistencyStrategy("DELETE_BEFORE_WRITE");
        var executor = executor();
        executor.execute("users", List.of("41:7"), () -> {
            verify(manager).applyLocalEviction("users", List.of("41:7"));
            return true;
        });
        verify(manager, times(1)).applyLocalEviction("users", List.of("41:7"));
    }

    @Test
    void delayedDoubleDeleteIsScheduledOnlyAfterCommitAndBroadcastsBothTimes() {
        properties.setConsistencyStrategy("DELAYED_DOUBLE_DELETE");
        var executor = executor();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        executor.execute("users", List.of("41:7"), () -> true);
        verify(manager).applyLocalEviction("users", List.of("41:7"));
        verifyNoInteractions(scheduler);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        var task = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(task.capture(), eq(250L), eq(TimeUnit.MILLISECONDS));
        task.getValue().run();
        verify(manager, times(2)).applyLocalEviction("users", List.of("41:7"));
        verify(manager, times(2)).publish("users", EgonColaCacheChangedOperation.EVICT, List.of("41:7"));
    }

    @Test
    void supportsHostStrategiesAndRejectsCrossTenantKeysBeforeWriting() {
        AtomicInteger calls = new AtomicInteger();
        EgonColaCacheConsistencyStrategy custom = new EgonColaCacheConsistencyStrategy() {
            @Override public String name() { return "CUSTOM"; }
            @Override public void afterWrite(EgonColaCacheMutation mutation) { calls.incrementAndGet(); }
        };
        properties.setConsistencyStrategy("CUSTOM");
        var executor = new EgonColaCacheMutationExecutor(manager, properties, List.of(custom));
        executor.execute("users", List.of("41:7"), () -> true);
        assertThat(calls).hasValue(1);
        assertThatThrownBy(() -> executor.execute("users", List.of("42:7"), () -> true))
                .hasMessageContaining("CACHE_KEY_TENANT_MISMATCH");
        assertThat(calls).hasValue(1);
    }

    @Test
    void regionOverrideAndPerCallStrategyTakePrecedenceOverTheGlobalSetting() {
        properties.setConsistencyStrategy("DELETE_BEFORE_WRITE");
        var region = new EgonColaCacheProperties.RegionTtl();
        region.setConsistencyStrategy("WRITE_BEFORE_DELETE");
        properties.getRegions().put("users", region);
        var executor = executor();
        executor.execute("users", List.of("41:7"), () -> {
            verify(manager, never()).applyLocalEviction(anyString(), anyList());
            return true;
        });
        verify(manager).applyLocalEviction("users", List.of("41:7"));
        executor.execute("DELETE_BEFORE_WRITE", "users", List.of("41:8"), List.of(), () -> {
            verify(manager).applyLocalEviction("users", List.of("41:8"));
            return true;
        });
    }

    @Test
    void delayedDeleteDoesNotScheduleAfterFailedWriteOrRollback() {
        properties.setConsistencyStrategy("DELAYED_DOUBLE_DELETE");
        var executor = executor();
        assertThatThrownBy(() -> executor.execute("users", List.of("41:7"), () -> {
            throw new IllegalStateException("write failed");
        })).hasMessageContaining("write failed");
        verifyNoInteractions(scheduler);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        executor.execute("users", List.of("41:8"), () -> true);
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(scheduler);
    }

    @Test
    void rejectsUnsynchronizedTransactionsBeforeExecutingBusinessWrite() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        AtomicInteger writes = new AtomicInteger();
        assertThatThrownBy(() -> executor().execute("users", List.of("41:7"), writes::incrementAndGet))
                .hasMessageContaining("CACHE_TRANSACTION_SYNCHRONIZATION_REQUIRED");
        assertThat(writes).hasValue(0);
        verifyNoInteractions(manager);
    }

    private EgonColaCacheMutationExecutor executor() {
        return new EgonColaCacheMutationExecutor(manager, properties, List.of());
    }
}
