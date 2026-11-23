package top.egon.cola.component.common.mybatis.operationlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationLogCaptureTest {

    private static final String AUTO_CONFIG = "top.egon.cola.component.common.mybatis.operationlog.EgonOperationLogAutoConfiguration";
    private static final String ON = "egon.cola.component.mybatis-plus.operation-log.enabled=true";
    private static final String SERVICE = "spring.application.name=tianshu-admin";
    private static final Instant NOW = Instant.parse("2026-11-23T00:05:03.001001999Z");

    @Test
    void disabledNeverWritesAndRequiresNoHostDependencies() {
        runner(false).run(context -> {
            assertThat(context).hasNotFailed().hasBean("operationLogRecorder").doesNotHaveBean("operationLogAdvisor");
            record(context.getBeanFactory(), fact("disabled"));
        });
    }

    @Test
    void proxyAndExplicitWriteOncePerOperation() {
        runner(true).withUserConfiguration(Host.class).withPropertyValues(ON, SERVICE).run(context -> {
            assertThat(context).hasNotFailed().hasBean("operationLogAdvisor");
            assertThat(context.getBean(OuterCommand.class).run("run-1")).isEqualTo("run-1");
            var destination = context.getBean(RecordingDestination.class);
            assertThat(destination.count()).isEqualTo(1);
            assertThat(destination.last.get().getHandlerMethod())
                    .isEqualTo(OuterCommand.class.getName() + "#run(java.lang.String)");
            assertThat(destination.last.get().getOccurredAt()).isEqualTo(Instant.parse("2026-11-23T00:05:03.001001Z"));

            var transaction = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
            transaction.executeWithoutResult(status -> {
                record(context.getBeanFactory(), fact("explicit"));
                record(context.getBeanFactory(), fact("explicit"));
            });
            assertThat(destination.count()).isEqualTo(2);
        });
    }

    @Test
    void outerRollbackLeavesNoCommittedAudit() {
        runner(true).withUserConfiguration(Host.class).withPropertyValues(ON, SERVICE).run(context -> {
            assertThat(context).hasNotFailed();
            new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status -> {
                context.getBean(OuterCommand.class).run("will-rollback");
                status.setRollbackOnly();
            });
            assertThat(context.getBean(RecordingDestination.class).count()).isZero();
        });
    }

    @Test
    void missingTransactionOrUnsafeContextFailsBeforeWriting() {
        runner(true).withUserConfiguration(Host.class).withPropertyValues(ON, SERVICE).run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> record(context.getBeanFactory(), fact("no-tx")))
                    .hasMessageContaining("OPERATION_LOG_ROUTE_MISMATCH");
            context.getBean(HostContext.class).value.set(new OperationLogContextDTO(
                    23L, "USER", "user-352", null, "HTTP", "POST",
                    "https://tianshu.example.invalid/api?access_token=demo", null, null, null, null));
            assertThatThrownBy(() -> context.getBean(OuterCommand.class).run("unsafe"))
                    .hasMessageContaining("OPERATION_LOG_CONTEXT_INVALID");
            assertThat(context.getBean(RecordingDestination.class).count()).isZero();
        });
    }

    @Test
    void rejectsEnableWithoutMatchingDestination() {
        runner(true).withPropertyValues(ON, SERVICE).run(context -> assertThat(context).hasFailed()
                .getFailure().hasRootCauseMessage("OPERATION_LOG_CONFIGURATION_INVALID"));
    }

    @Test
    void resolvesBridgeMethodsAndRefusesMissingTransactionAdvice() {
        runner(true).withUserConfiguration(Host.class, BridgeHost.class).withPropertyValues(ON, SERVICE).run(context -> {
            assertThat(context).hasNotFailed();
            @SuppressWarnings("unchecked")
            GenericCommand<String> command = context.getBean("bridgeOperationLogCommand", GenericCommand.class);
            assertThat(command.run("bridge")).isEqualTo("bridge");
            assertThat(context.getBean(RecordingDestination.class).last.get().getHandlerMethod())
                    .isEqualTo(StringCommand.class.getName() + "#run(java.lang.String)");
        });
        runner(true).withUserConfiguration(Host.class, InvalidHost.class).withPropertyValues(ON, SERVICE).run(context ->
                assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("OPERATION_LOG_TRANSACTION_ADVICE_ORDER_INVALID: run"));
    }

    @Test
    void eventWireKeepsBigIntAndInstantWithoutExposingConstraintProperties() throws Exception {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var event = fact("wire").setEventId(118230000100000001L).setTenantId(23L)
                .setOccurredAt(Instant.parse("2026-11-23T00:05:03.001001Z"));
        var json = mapper.readTree(mapper.writeValueAsString(event));
        assertThat(json.get("eventId").textValue()).isEqualTo("118230000100000001");
        assertThat(json.get("tenantId").textValue()).isEqualTo("23");
        assertThat(json.get("phase").textValue()).isEqualTo("COMMAND");
        assertThat(json.get("occurredAt").textValue()).isEqualTo("2026-11-23T00:05:03.001001Z");
        assertThat(json.has("recordedFormatValid")).isFalse();
        assertThat(mapper.treeToValue(json, OperationLogEvent.class).getEventId()).isEqualTo(event.getEventId());
    }

    @Test
    void generatedConstructorsKeepNamedDependencies() {
        for (String typeName : new String[]{"OperationLogRecorder", "OperationLogAdvisor"}) {
            Class<?> type = ClassUtils.resolveClassName(getClass().getPackageName() + "." + typeName,
                    getClass().getClassLoader());
            assertThat(Arrays.stream(type.getConstructors()).filter(c -> c.isAnnotationPresent(Autowired.class)))
                    .singleElement().satisfies(constructor ->
                            assertThat(constructor.getParameters())
                                    .allSatisfy(parameter -> assertThat(parameter.getAnnotation(Qualifier.class)).isNotNull()));
        }
    }

    private static ApplicationContextRunner runner(boolean validation) {
        Class<?> config = ClassUtils.resolveClassName(AUTO_CONFIG, OperationLogCaptureTest.class.getClassLoader());
        return new ApplicationContextRunner().withConfiguration(validation
                ? AutoConfigurations.of(ValidationAutoConfiguration.class, config)
                : AutoConfigurations.of(config));
    }

    private static void record(ConfigurableListableBeanFactory beans, OperationLogEvent event) {
        Object recorder = beans.getBean("operationLogRecorder");
        try {
            recorder.getClass().getMethod("recordInTransaction", OperationLogEvent.class).invoke(recorder, event);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static OperationLogEvent fact(String operationId) {
        return OperationLogEvent.builder().operationId(operationId).moduleCode("CONFIG")
                .actionCode("UPDATE").resourceType("CONFIG").phase(OperationLogPhaseEnum.COMMAND)
                .outcome(OperationLogOutcomeEnum.SUCCEEDED).build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 1)
    static class Host {

        @Bean("dataSource")
        DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean("transactionManager")
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean("egonColaMybatisPlusClock")
        Clock auditClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean("operationLogIdGenerator")
        LongSupplier operationLogIdGenerator() {
            return new AtomicLong(118230000100000000L)::incrementAndGet;
        }

        @Bean("hostAuditContext")
        HostContext hostContext() {
            return new HostContext();
        }

        @Bean("operationLogContextProvider")
        Supplier<OperationLogContextDTO> operationLogContextProvider(HostContext host) {
            return host.value::get;
        }

        @Bean("recordingDestination")
        RecordingDestination recordingDestination(DataSource source) {
            return new RecordingDestination(source);
        }

        @Bean("testOperationLogProjection")
        OperationLogProjection projection() {
            return (arguments, result) -> Optional.of(fact((String) result)
                    .setTenantId(999L).setActorId("untrusted").setEventId(1L));
        }

        @Bean("outerCommand")
        OuterCommand outerCommand(InnerCommand inner) {
            return new OuterCommand(inner);
        }

        @Bean("innerCommand")
        InnerCommand innerCommand(ConfigurableListableBeanFactory factory) {
            return new InnerCommand(factory);
        }
    }

    static final class HostContext {

        private final AtomicReference<OperationLogContextDTO> value = new AtomicReference<>(
                new OperationLogContextDTO(23L, "USER", "user-352", null, "HTTP", "POST",
                        "https://tianshu.example.invalid/api/configs/42", "/api/configs/{id}",
                        OuterCommand.class.getName() + "#run(java.lang.String)", null, null));
    }

    static class OuterCommand {

        private final InnerCommand inner;

        OuterCommand(InnerCommand inner) {
            this.inner = inner;
        }

        @Transactional
        @OperationLog(actionCode = "UPDATE", resourceType = "CONFIG", projector = "testOperationLogProjection")
        public String run(String operationId) {
            inner.run(operationId);
            return operationId;
        }
    }

    static class InnerCommand {

        private final ConfigurableListableBeanFactory beans;

        InnerCommand(ConfigurableListableBeanFactory beans) {
            this.beans = beans;
        }

        @Transactional
        @OperationLog(actionCode = "UPDATE", resourceType = "CONFIG", projector = "testOperationLogProjection")
        public String run(String operationId) {
            record(beans, fact(operationId));
            return operationId;
        }
    }

    interface GenericCommand<T> {

        T run(T value);
    }

    static class StringCommand implements GenericCommand<String> {

        @Override
        @Transactional
        @OperationLog(actionCode = "UPDATE", resourceType = "CONFIG", projector = "testOperationLogProjection")
        public String run(String value) {
            return value;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BridgeHost {

        @Bean("bridgeOperationLogCommand")
        StringCommand command() {
            return new StringCommand();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class InvalidHost {

        @Bean("untransactionalOperationLogCommand")
        GenericCommand<String> command() {
            return new GenericCommand<>() {
                @Override
                @OperationLog(actionCode = "UPDATE", resourceType = "CONFIG", projector = "testOperationLogProjection")
                public String run(String value) {
                    return value;
                }
            };
        }
    }

    static class RecordingDestination implements OperationLogDestination {

        private final JdbcTemplate jdbc;
        private final AtomicReference<OperationLogEvent> last = new AtomicReference<>();

        RecordingDestination(DataSource source) {
            jdbc = new JdbcTemplate(source);
            jdbc.execute("create table recorded_operation (id bigint primary key)");
        }

        @Override
        public OperationLogWriteModeEnum mode() {
            return OperationLogWriteModeEnum.SOURCE_LOCAL;
        }

        @Override
        public void append(OperationLogEvent event) {
            assertThat(event.getTenantId()).isEqualTo(23L);
            assertThat(event.getActorId()).isEqualTo("user-352");
            assertThat(event.getEventId()).isGreaterThan(1L);
            jdbc.update("insert into recorded_operation values (?)", event.getEventId());
            last.set(event);
        }

        int count() {
            return jdbc.queryForObject("select count(*) from recorded_operation", Integer.class);
        }
    }
}
