package top.egon.cola.component.outbox.autoconfigure;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Uses Boot's relaxed enum binding for both storage providers. */
final class OutboxStorageTypeCondition {
    private static TransactionalOutboxProperties.Storage.StorageType selected(ConditionContext context) {
        return Binder.get(context.getEnvironment()).bind("egon.cola.component.transactional-outbox.storage.type",
                        TransactionalOutboxProperties.Storage.StorageType.class)
                .orElse(TransactionalOutboxProperties.Storage.StorageType.MYBATIS_PLUS);
    }

    static final class MybatisPlus implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return selected(context) == TransactionalOutboxProperties.Storage.StorageType.MYBATIS_PLUS;
        }
    }

    static final class Jdbc implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return selected(context) == TransactionalOutboxProperties.Storage.StorageType.JDBC;
        }
    }

    private OutboxStorageTypeCondition() { }
}
