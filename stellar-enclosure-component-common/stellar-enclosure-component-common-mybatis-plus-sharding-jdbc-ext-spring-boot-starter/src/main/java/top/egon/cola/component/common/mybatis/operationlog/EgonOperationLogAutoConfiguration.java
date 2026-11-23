package top.egon.cola.component.common.mybatis.operationlog;

import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusAutoConfiguration;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@AutoConfiguration(after = EgonColaMybatisPlusAutoConfiguration.class)
@EnableConfigurationProperties
public class EgonOperationLogAutoConfiguration {

    @Bean("egonOperationLogProperties")
    @ConfigurationProperties(EgonOperationLogProperties.PREFIX)
    EgonOperationLogProperties egonOperationLogProperties() {
        return new EgonOperationLogProperties();
    }

    @Bean("operationLogRecorder")
    OperationLogRecorder operationLogRecorder(
            @Qualifier("egonOperationLogProperties") EgonOperationLogProperties properties,
            @Qualifier("dataSource") ObjectProvider<DataSource> source,
            ObjectProvider<OperationLogDestination> destinations,
            @Qualifier("operationLogContextProvider") ObjectProvider<Supplier<OperationLogContextDTO>> context,
            @Qualifier("egonColaValidationUtils") ObjectProvider<ValidationUtils> validation,
            @Qualifier("egonColaMybatisPlusClock") ObjectProvider<Clock> clock,
            @Qualifier("operationLogIdGenerator") ObjectProvider<LongSupplier> ids,
            Environment environment,
            @Qualifier("defaultValidator") ObjectProvider<Validator> defaultValidator) {
        if (!properties.isEnabled()) {
            return new OperationLogRecorder(properties, null, null, null, null, null, null, null);
        }
        List<OperationLogDestination> selected = destinations.stream()
                .filter(destination -> destination.mode() == properties.getWriteMode()).toList();
        if (selected.size() != 1 || source.getIfAvailable() == null || context.getIfAvailable() == null) {
            throw new IllegalStateException("OPERATION_LOG_CONFIGURATION_INVALID");
        }
        String service = properties.getSourceService() == null
                ? environment.getProperty("spring.application.name") : properties.getSourceService();
        if (service == null || service.isBlank() || service.length() > 128) {
            throw new IllegalStateException("OPERATION_LOG_SOURCE_SERVICE_INVALID");
        }
        return new OperationLogRecorder(properties, source.getObject(), selected.getFirst(), context.getObject(),
                validation.getIfAvailable(() -> new ValidationUtils(defaultValidator.getObject())),
                clock.getIfAvailable(Clock::systemUTC), ids.getIfAvailable(() -> SnowflakeIdGenerator::nextLongId), service);
    }

    @Bean("operationLogAdvisor")
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnProperty(prefix = EgonOperationLogProperties.PREFIX, name = "enabled", havingValue = "true")
    OperationLogAdvisor operationLogAdvisor(
            @Qualifier("operationLogRecorder") OperationLogRecorder recorder, ConfigurableListableBeanFactory beans) {
        return new OperationLogAdvisor(recorder, beans);
    }

    @Bean("operationLogAdvisorValidation")
    @ConditionalOnProperty(prefix = EgonOperationLogProperties.PREFIX, name = "enabled", havingValue = "true")
    SmartInitializingSingleton operationLogAdvisorValidation(
            @Qualifier("operationLogAdvisor") OperationLogAdvisor advisor, ConfigurableListableBeanFactory beans) {
        return () -> {
            for (String name : beans.getSingletonNames()) {
                Object bean = beans.getSingleton(name);
                if (bean != null) {
                    advisor.verify(bean);
                }
            }
        };
    }
}
