package top.egon.cola.component.common.cache.port;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.common.cache.autoconfigure.EgonColaCacheProperties;
import top.egon.cola.component.common.cache.core.EgonColaTwoLevelCacheManager;
import top.egon.cola.component.common.cache.event.EgonColaCacheChangedOperation;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class EgonColaCachePortNestedTransactionTest {

    private final EgonColaTwoLevelCacheManager manager = mock(EgonColaTwoLevelCacheManager.class);
    private final EgonColaTwoLevelCachePort port = new EgonColaTwoLevelCachePort(manager, new EgonColaCacheProperties());

    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    @Test
    void innerCommitIsNotDiscardedWhenOuterTransactionRollsBack() {
        MDC.put("tenantId", "41");
        when(manager.scheduler()).thenReturn(mock(ScheduledExecutorService.class));
        var transactionManager = new ThreadBoundTransactionManager();
        var outer = new TransactionTemplate(transactionManager);
        var inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outer.executeWithoutResult(status -> {
            port.registerEvictionAfterCommit("users", List.of("41:1"), List.of());
            inner.executeWithoutResult(innerStatus ->
                    port.registerEvictionAfterCommit("users", List.of("41:2"), List.of()));
            verify(manager).applyLocalEviction("users", List.of("41:2"));
            verify(manager, never()).applyLocalEviction("users", List.of("41:1"));
            status.setRollbackOnly();
        });

        verify(manager, never()).applyLocalEviction("users", List.of("41:1"));
        verify(manager, times(1)).applyLocalEviction("users", List.of("41:2"));
    }

    @Test
    void innerRollbackDoesNotDiscardOrPublishTheOuterTransactionKeys() {
        MDC.put("tenantId", "41");
        when(manager.scheduler()).thenReturn(mock(ScheduledExecutorService.class));
        var transactionManager = new ThreadBoundTransactionManager();
        var outer = new TransactionTemplate(transactionManager);
        var inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outer.executeWithoutResult(status -> {
            port.registerEvictionAfterCommit("users", List.of("41:1"), List.of());
            inner.executeWithoutResult(innerStatus -> {
                port.registerEvictionAfterCommit("users", List.of("41:2"), List.of());
                innerStatus.setRollbackOnly();
            });
            verify(manager, never()).applyLocalEviction(anyString(), any());
        });

        verify(manager).applyLocalEviction("users", List.of("41:1"));
        verify(manager, never()).applyLocalEviction("users", List.of("41:2"));
    }

    @Test
    void activeTransactionWithoutSynchronizationIsRejected() {
        MDC.put("tenantId", "41");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> port.registerEvictionAfterCommit("users", List.of("41:1"), List.of()))
                    .hasMessageContaining("CACHE_TRANSACTION_SYNCHRONIZATION_REQUIRED");
            verifyNoInteractions(manager);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void oneTransactionPublishesSeparateEventsForEachTenant() {
        when(manager.scheduler()).thenReturn(mock(ScheduledExecutorService.class));
        var transaction = new TransactionTemplate(new ThreadBoundTransactionManager());
        transaction.executeWithoutResult(status -> {
            MDC.put("tenantId", "41");
            port.registerEvictionAfterCommit("users", List.of("41:1"), List.of());
            MDC.put("tenantId", "42");
            port.registerEvictionAfterCommit("users", List.of("42:2"), List.of());
        });

        verify(manager).publish("users", EgonColaCacheChangedOperation.EVICT, List.of("41:1"));
        verify(manager).publish("users", EgonColaCacheChangedOperation.EVICT, List.of("42:2"));
        verify(manager, never()).publish("users", EgonColaCacheChangedOperation.EVICT, List.of("41:1", "42:2"));
    }

    private static final class ThreadBoundTransactionManager extends AbstractPlatformTransactionManager {

        private final ThreadLocal<Object> current = new ThreadLocal<>();

        @Override
        protected Object doGetTransaction() {
            return new Transaction(current.get() != null);
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return ((Transaction) transaction).existing();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            current.set(new Object());
        }

        @Override
        protected Object doSuspend(Object transaction) {
            Object suspended = current.get();
            current.remove();
            return suspended;
        }

        @Override
        protected void doResume(Object transaction, Object suspendedResources) {
            current.set(suspendedResources);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            current.remove();
        }

        private record Transaction(boolean existing) {
        }
    }
}
