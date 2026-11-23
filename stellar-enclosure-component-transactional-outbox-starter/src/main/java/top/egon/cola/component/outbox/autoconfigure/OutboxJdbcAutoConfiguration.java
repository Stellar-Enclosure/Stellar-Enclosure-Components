package top.egon.cola.component.outbox.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.outbox.common.exception.OutboxConfigurationException;
import top.egon.cola.component.outbox.persistence.converter.OutboxHeadersConverter;
import top.egon.cola.component.outbox.retry.OutboxRetryPolicy;
import top.egon.cola.component.outbox.statemachine.OutboxLifecycleService;
import top.egon.cola.component.outbox.store.JdbcOutboxStore;
import top.egon.cola.component.outbox.store.OutboxStore;
import top.egon.cola.component.outbox.transaction.OutboxTransactionGuard;
import top.egon.cola.component.outbox.validation.OutboxMessageValidator;

import java.time.Clock;

/** Uses the selected host DataSource without creating or migrating a database schema. */
@AutoConfiguration(after = TransactionalOutboxAutoConfiguration.class, before = OutboxStateMachineAutoConfiguration.class)
@ConditionalOnBean(OutboxInfrastructure.class)
@ConditionalOnProperty(prefix = "egon.cola.component.transactional-outbox", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Conditional(OutboxStorageTypeCondition.Jdbc.class)
public class OutboxJdbcAutoConfiguration {
    @Bean("outboxJdbcClock")
    @ConditionalOnMissingBean(name = {"outboxJdbcClock", "egonColaMybatisPlusClock"})
    Clock outboxJdbcClock() {
        return Clock.systemUTC();
    }

    @Bean("outboxStore")
    @ConditionalOnMissingBean(OutboxStore.class)
    OutboxStore outboxStore(OutboxInfrastructure infrastructure, TransactionalOutboxProperties properties,
                            OutboxTransactionGuard guard, OutboxLifecycleService lifecycle,
                            OutboxMessageValidator validator, ObjectProvider<ObjectMapper> mappers,
                            @Qualifier("egonColaValidationUtils") ValidationUtils validationUtils,
                            ConfigurableListableBeanFactory beanFactory,
                            @Qualifier("outboxRetryPolicy") OutboxRetryPolicy retryPolicy) {
        if (!OutboxInfrastructureResolver.supportsSelectedLocalTransaction(infrastructure.transactionManager(),
                infrastructure.dataSource(), beanFactory.getBeanClassLoader())) {
            throw new OutboxConfigurationException("OUTBOX_DATASOURCE_TRANSACTION_MANAGER_REQUIRED");
        }
        ObjectMapper mapper = mappers.getIfUnique();
        if (mapper == null) throw new OutboxConfigurationException("Transactional outbox requires exactly one ObjectMapper bean");
        TransactionTemplate worker = new TransactionTemplate(infrastructure.transactionManager());
        worker.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var store = new JdbcOutboxStore(infrastructure.dataSource(), properties.getStorage().getJdbc().getSchema(),
                worker, guard, lifecycle, new OutboxHeadersConverter(mapper, validator), validator, validationUtils,
                properties, mapper, retryPolicy);
        var dispatch = properties.getDispatch();
        if (dispatch.getMode() != TransactionalOutboxProperties.DispatchMode.LEGACY
                && dispatch.isConsumptionEnabled() && !properties.getStorage().isValidateSchema()) {
            store.validateSchema();
        }
        return store;
    }
}
