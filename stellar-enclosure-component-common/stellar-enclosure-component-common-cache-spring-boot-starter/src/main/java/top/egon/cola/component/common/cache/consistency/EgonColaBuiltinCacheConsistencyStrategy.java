package top.egon.cola.component.common.cache.consistency;

/** The built-in policies differ only in the ordering around the database write and commit. */
public enum EgonColaBuiltinCacheConsistencyStrategy implements EgonColaCacheConsistencyStrategy {
    DELAYED_DOUBLE_DELETE {
        @Override
        public void beforeWrite(EgonColaCacheMutation mutation) {
            mutation.evictNow();
        }

        @Override
        public void afterWrite(EgonColaCacheMutation mutation) {
            mutation.evictAfterCommitWithDelay();
        }
    },
    DELETE_BEFORE_WRITE {
        @Override
        public void beforeWrite(EgonColaCacheMutation mutation) {
            mutation.evictNow();
        }
    },
    WRITE_BEFORE_DELETE {
        @Override
        public void afterWrite(EgonColaCacheMutation mutation) {
            mutation.evictAfterCommit();
        }
    }
}
