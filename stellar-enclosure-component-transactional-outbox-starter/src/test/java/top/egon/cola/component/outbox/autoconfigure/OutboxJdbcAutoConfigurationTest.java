package top.egon.cola.component.outbox.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.outbox.common.exception.OutboxTransactionMismatchException;
import top.egon.cola.component.outbox.persistence.converter.OutboxHeadersConverter;
import top.egon.cola.component.outbox.statemachine.OutboxLifecycleService;
import top.egon.cola.component.outbox.store.JdbcOutboxStore;
import top.egon.cola.component.outbox.store.OutboxStore;
import top.egon.cola.component.outbox.transaction.OutboxTransactionGuard;
import top.egon.cola.component.outbox.validation.OutboxMessageValidator;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class OutboxJdbcAutoConfigurationTest {
    private final DataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:outbox_jdbc_assembly", "sa", "");
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withClassLoader(new FilteredClassLoader("top.egon.cola.component.common.mybatis", "com.baomidou",
                    "org.apache.ibatis", "org.mybatis"))
            .withConfiguration(AutoConfigurations.of(TransactionalOutboxAutoConfiguration.class,
                    OutboxJdbcAutoConfiguration.class, OutboxStateMachineAutoConfiguration.class))
            .withBean(DataSource.class, () -> dataSource)
            .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues("egon.cola.component.transactional-outbox.storage.type=jdbc",
                    "egon.cola.component.transactional-outbox.storage.validate-schema=false",
                    "egon.cola.component.transactional-outbox.polling.enabled=false",
                    "egon.cola.component.transactional-outbox.cleanup.enabled=false",
                    "egon.cola.component.transactional-outbox.annotation.enabled=false");

    @Test
    void jdbc_mode_assembles_without_mp_or_sharding_classes() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OutboxStore.class);
            assertThat(context.getBean(OutboxStore.class)).isInstanceOf(JdbcOutboxStore.class);
            assertThat(context).doesNotHaveBean("outboxMessageRepository");
            assertThat(context).doesNotHaveBean("egonColaShardingLogicalDataSourceFactory");
            assertThat(context).hasBean("outboxStateMachineClock");
        });
    }

    @Test
    void invalid_schema_and_disabled_component_fail_safely() {
        runner.withPropertyValues("egon.cola.component.transactional-outbox.storage.jdbc.schema=unsafe-name")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("egon.cola.component.transactional-outbox.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(OutboxStore.class);
                });
    }

    @Test
    void worker_transaction_must_bind_the_same_data_source() {
        DataSource other = new DriverManagerDataSource("jdbc:h2:mem:outbox_other_transaction", "sa", "");
        var worker = new TransactionTemplate(new DataSourceTransactionManager(other));
        worker.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var store = new JdbcOutboxStore(dataSource, "egon_outbox", worker, new OutboxTransactionGuard(dataSource),
                mock(OutboxLifecycleService.class), mock(OutboxHeadersConverter.class),
                mock(OutboxMessageValidator.class), mock(ValidationUtils.class));
        assertThrows(OutboxTransactionMismatchException.class, store::countBacklog);
    }
}
