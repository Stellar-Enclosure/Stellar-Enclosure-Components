package top.egon.cola.component.common.cache.consistency;

/** A named cache invalidation policy surrounding a business write. */
public interface EgonColaCacheConsistencyStrategy {

    String name();

    default void beforeWrite(EgonColaCacheMutation mutation) {
    }

    default void afterWrite(EgonColaCacheMutation mutation) {
    }
}
