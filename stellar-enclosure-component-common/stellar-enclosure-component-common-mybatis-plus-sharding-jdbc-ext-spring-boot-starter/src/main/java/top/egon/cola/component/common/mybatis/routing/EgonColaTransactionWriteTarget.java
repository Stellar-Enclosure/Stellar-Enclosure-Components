package top.egon.cola.component.common.mybatis.routing;

import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;

/** One physical write group per Spring transaction, shared by MP and the physical datasource guard. */
public final class EgonColaTransactionWriteTarget {

    private static final Object RESOURCE_KEY = new Object();

    private EgonColaTransactionWriteTarget() {
    }

    public static State capture(DataSource dataSource) {
        if (dataSource == null || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return null;
        }
        Object resource = TransactionSynchronizationManager.getResource(dataSource);
        if (!(resource instanceof ConnectionHolder holder)) {
            return null;
        }
        State current = (State) TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        if (current != null) {
            if (current.dataSource != dataSource || current.holder != holder) {
                current.holder.setRollbackOnly();
                holder.setRollbackOnly();
                throw new IllegalStateException("LOCAL_WRITE_TARGET_MISMATCH");
            }
            return current;
        }
        State state = new State(dataSource, holder);
        TransactionSynchronizationManager.bindResource(RESOURCE_KEY, state);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void suspend() { TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE_KEY); }
            @Override
            public void resume() { TransactionSynchronizationManager.bindResource(RESOURCE_KEY, state); }
            @Override
            public void afterCompletion(int status) { TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE_KEY); }
        });
        return state;
    }

    public static String currentGroup(DataSource dataSource) {
        State state = capture(dataSource);
        return state == null ? null : state.group();
    }

    public static void markRollbackOnly(DataSource dataSource) {
        Object state = TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        if (state instanceof State current) {
            current.holder.setRollbackOnly();
        }
        if (dataSource != null && TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder holder) {
            holder.setRollbackOnly();
        }
    }

    public static final class State {
        private final DataSource dataSource;
        private final ConnectionHolder holder;
        private String group;
        private Object factory;

        private State(DataSource dataSource, ConnectionHolder holder) {
            this.dataSource = dataSource;
            this.holder = holder;
        }

        public synchronized String group() {
            return group;
        }

        public synchronized void pin(String requestedGroup, Object requestedFactory) {
            if (requestedGroup == null || requestedGroup.isBlank()) {
                holder.setRollbackOnly();
                throw new IllegalStateException("LOCAL_WRITE_TARGET_MISMATCH");
            }
            if (group != null && !group.equals(requestedGroup)
                    || factory != null && requestedFactory != null && factory != requestedFactory) {
                holder.setRollbackOnly();
                throw new IllegalStateException("LOCAL_WRITE_TARGET_MISMATCH");
            }
            group = requestedGroup;
            if (requestedFactory != null) {
                factory = requestedFactory;
            }
        }
    }
}
