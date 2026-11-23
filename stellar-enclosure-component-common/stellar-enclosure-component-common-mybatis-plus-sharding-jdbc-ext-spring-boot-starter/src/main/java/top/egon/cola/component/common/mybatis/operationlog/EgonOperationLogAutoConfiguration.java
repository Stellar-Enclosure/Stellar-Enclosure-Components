package top.egon.cola.component.common.mybatis.operationlog;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties.Ddl;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties.ManagedDdlTarget;
import top.egon.cola.component.common.mybatis.ddl.EgonColaDdlTargetBO;
import top.egon.cola.component.common.mybatis.ddl.EgonColaManagedDdlInitializer;
import top.egon.cola.component.common.mybatis.ddl.EgonColaPostgreDdlRunner;
import top.egon.cola.component.common.mybatis.interceptor.EgonColaLocalWriteGuardInnerInterceptor;
import top.egon.cola.component.common.mybatis.interceptor.EgonColaModelValidationInterceptor;
import top.egon.cola.component.common.mybatis.interceptor.EgonColaOriginalSqlGuardInterceptor;
import top.egon.cola.component.common.mybatis.interceptor.EgonColaTenantIdGuardInnerInterceptor;
import top.egon.cola.component.common.mybatis.model.EgonColaIdentifierGenerator;
import top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO;
import top.egon.cola.component.common.mybatis.sharding.resolver.EgonColaShardingWriteTargetResolver;
import top.egon.cola.component.common.mybatis.routing.EgonColaTwoLevelRouteStrategy;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.ConfigStyleEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.DataSourceRoleEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.ModeEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.PhysicalDataSourceProperties;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.TableProperties;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaPhysicalDataSourceFactory;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingDataSourceBootstrapper;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingTopologyValidator;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingYamlLoader;

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
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.time.Clock;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@AutoConfiguration(after = EgonColaMybatisPlusAutoConfiguration.class,
        afterName = "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration")
@EnableConfigurationProperties
public class EgonOperationLogAutoConfiguration {

    private static final String FACT_SQL = "classpath:mapper/operationlog/OperationLogMapper.xml";
    private static final String RECEIPT_SQL = "classpath:mapper/operationlog/OperationLogReceiptMapper.xml";

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
    @Configuration(value = "operationLogIsolatedTargetConfiguration", proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = EgonOperationLogProperties.PREFIX + ".target", name = "enabled", havingValue = "true")
    static class IsolatedTarget {

        @Bean("operationLogTargetProperties")
        @ConfigurationProperties(EgonOperationLogProperties.PREFIX + ".target")
        TargetProperties targetProperties() {
            return new TargetProperties();
        }

        @Bean(value = "operationLogTargetBootstrapper", defaultCandidate = false)
        EgonColaShardingDataSourceBootstrapper bootstrapper(
                @Qualifier("operationLogTargetProperties") TargetProperties properties,
                @Qualifier("egonColaPhysicalDataSourceFactory") EgonColaPhysicalDataSourceFactory pools,
                @Qualifier("egonColaShardingYamlLoader") EgonColaShardingYamlLoader loader,
                @Qualifier("egonColaShardingTopologyValidator") EgonColaShardingTopologyValidator validator,
                @Qualifier("egonColaPostgreDdlRunner") EgonColaPostgreDdlRunner runner, ResourceLoader resources) {
            Map<String, Long> primaryCounts = properties.getDataSources().stream()
                    .filter(source -> source.role() == DataSourceRoleEnum.PRIMARY)
                    .collect(Collectors.groupingBy(PhysicalDataSourceProperties::logicalName, Collectors.counting()));
            if (primaryCounts.size() != 2 || primaryCounts.values().stream().anyMatch(count -> count != 1L)) {
                throw new IllegalStateException("OPERATION_LOG_TWO_PRIMARY_GROUPS_REQUIRED");
            }
            List<String> groups = primaryCounts.keySet().stream().sorted().toList();
            properties.getDdl().setTargets(List.of(
                    migrationTarget(groups.get(0), 0), migrationTarget(groups.get(1), 1)));
            var initializer = new EgonColaManagedDdlInitializer(properties.getDdl(), runner, resources);
            return new EgonColaShardingDataSourceBootstrapper(pools, loader, validator, new TargetSqlPolicy(), initializer);
        }

        @Bean(value = "operationLogDataSource", defaultCandidate = false, destroyMethod = "close")
        DataSource operationLogDataSource(
                @Qualifier("operationLogTargetProperties") TargetProperties properties,
                @Qualifier("operationLogTargetBootstrapper") EgonColaShardingDataSourceBootstrapper bootstrapper) {
            List<String> groups = properties.getDataSources().stream()
                    .filter(source -> source.role() == DataSourceRoleEnum.PRIMARY)
                    .map(PhysicalDataSourceProperties::logicalName).sorted().toList();
            var rule = new TableProperties("COMPLEX_TENANT_THEN_BUSINESS", null, "tenant_id",
                    List.of("tenant_id", "id"), "id", "public", groups);
            return bootstrapper.createDataSource(EgonColaShardingProperties.builder()
                    .mode(ModeEnum.SHARDING_READWRITE).configStyle(ConfigStyleEnum.STRATEGY)
                    .dataSources(properties.getDataSources())
                    .tables(Map.of(OperationLogRepository.FACT_TABLE, rule, OperationLogRepository.RECEIPT_TABLE, rule))
                    .build());
        }

        @Bean(value = "operationLogSqlSessionFactory", defaultCandidate = false)
        SqlSessionFactory operationLogSqlSessionFactory(
                @Qualifier("operationLogDataSource") DataSource target,
                @Qualifier("operationLogTargetBootstrapper") EgonColaShardingDataSourceBootstrapper bootstrapper,
                @Qualifier("mybatisPlusInterceptor") MybatisPlusInterceptor originalOuter,
                @Qualifier("egonColaTenantIdGuardInnerInterceptor") EgonColaTenantIdGuardInnerInterceptor sqlGuard,
                @Qualifier("egonColaTwoLevelRouteStrategy") EgonColaTwoLevelRouteStrategy routeStrategy,
                @Qualifier("egonColaValidationUtils") ValidationUtils validation,
                @Qualifier("egonColaModelValidationInterceptor") EgonColaModelValidationInterceptor modelValidation,
                @Qualifier("egonColaOriginalSqlGuardInterceptor") EgonColaOriginalSqlGuardInterceptor originalSql,
                @Qualifier("egonColaMetaObjectHandler") MetaObjectHandler meta,
                @Qualifier("egonColaIdentifierGenerator") EgonColaIdentifierGenerator ids,
                ResourceLoader resources) throws Exception {
            var profiles = bootstrapper.profiles();
            var resolver = new EgonColaShardingWriteTargetResolver(profiles, routeStrategy, validation, bootstrapper.fingerprint());
            var targetGuard = new EgonColaLocalWriteGuardInnerInterceptor(resolver, sqlGuard, routeStrategy, profiles);
            var targetOuter = new MybatisPlusInterceptor();
            targetOuter.setInterceptors(originalOuter.getInterceptors().stream()
                    .map(inner -> inner instanceof EgonColaLocalWriteGuardInnerInterceptor ? targetGuard : inner).toList());

            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setConfiguration(configuration);
            factory.setDataSource(target);
            factory.setGlobalConfig(GlobalConfigUtils.defaults().setMetaObjectHandler(meta).setIdentifierGenerator(ids));
            factory.setPlugins(modelValidation, targetOuter, originalSql);
            factory.setTransactionFactory(new SpringManagedTransactionFactory());
            factory.setMapperLocations(resources.getResource(FACT_SQL), resources.getResource(RECEIPT_SQL));
            return factory.getObject();
        }

        @Bean(value = "operationLogTransactionManager", defaultCandidate = false)
        DataSourceTransactionManager operationLogTransactionManager(@Qualifier("operationLogDataSource") DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean("operationLogRepository")
        OperationLogRepository operationLogRepository(
                @Qualifier("operationLogDataSource") DataSource source,
                @Qualifier("operationLogSqlSessionFactory") SqlSessionFactory factory,
                @Qualifier("operationLogTransactionManager") DataSourceTransactionManager transactions,
                @Qualifier("operationLogTargetProperties") TargetProperties properties,
                @Qualifier("egonColaValidationUtils") ValidationUtils validation,
                @Qualifier("egonColaMybatisPlusClock") ObjectProvider<Clock> clock,
                ObjectMapper json) {
            if (properties.getMaxAge() == null || properties.getMaxAge().isNegative()
                    || properties.getMaxAge().isZero() || properties.getTransactionTimeout().toSeconds() < 1
                    || properties.getTransactionTimeout().toSeconds() > 60) {
                throw new IllegalStateException("OPERATION_LOG_TARGET_BUDGET_INVALID");
            }
            var sessions = new SqlSessionTemplate(factory);
            return new OperationLogRepository(source, sessions.getMapper(OperationLogDAO.class),
                    sessions.getMapper(OperationLogReceiptDAO.class),
                    OperationLogRepository.readCommitted(transactions, properties.getTransactionTimeout()),
                    new OperationLogConverterImpl(), validation, json,
                    clock.getIfAvailable(Clock::systemUTC), properties.getMaxAge());
        }

        private static ManagedDdlTarget migrationTarget(String group, int slot) {
            return new ManagedDdlTarget(group, "public", EgonColaDdlTargetBO.RoleEnum.SHARD,
                    "classpath:db/egon-operation-log/manifest-slot-" + slot + ".json");
        }
    }

    @Configuration(value = "operationLogSourceLocalConfiguration", proxyBeanMethods = false)
    @ConditionalOnBean(name = {"sqlSessionFactory", "egonColaRoutingProfiles"})
    @ConditionalOnProperty(prefix = EgonOperationLogProperties.PREFIX, name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = EgonOperationLogProperties.PREFIX, name = "write-mode", havingValue = "SOURCE_LOCAL", matchIfMissing = true)
    static class SourceLocal {

        @Bean("operationLogSourceLocalDestination")
        OperationLogDestination sourceLocalDestination(
                @Qualifier("dataSource") DataSource source,
                @Qualifier("sqlSessionFactory") SqlSessionFactory factory,
                @Qualifier("egonColaValidationUtils") ValidationUtils validation,
                @Qualifier("egonColaRoutingProfiles") Map<String, EgonColaRoutingProfileBO> profiles,
                ResourceLoader resources) throws IOException {
            if (!profiles.containsKey(OperationLogRepository.FACT_TABLE)
                    || factory.getConfiguration().getEnvironment().getDataSource() != source) {
                throw new IllegalStateException("OPERATION_LOG_SOURCE_LOCAL_ROUTE_REQUIRED");
            }
            if (!factory.getConfiguration().hasMapper(OperationLogDAO.class)) {
                try (var sql = resources.getResource(FACT_SQL).getInputStream()) {
                    var configuration = factory.getConfiguration();
                    new XMLMapperBuilder(sql, configuration, FACT_SQL, configuration.getSqlFragments()).parse();
                }
            }
            var mapper = new SqlSessionTemplate(factory).getMapper(OperationLogDAO.class);
            var repository = new OperationLogRepository(source, mapper, null, null,
                    new OperationLogConverterImpl(), validation, null, null, null);
            return new OperationLogDestination() {
                @Override
                public OperationLogWriteModeEnum mode() {
                    return OperationLogWriteModeEnum.SOURCE_LOCAL;
                }

                @Override
                public void append(OperationLogEvent event) {
                    repository.append(event);
                }
            };
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    @Validated
    public static class TargetProperties {

        @Builder.Default
        @NotEmpty
        private List<@Valid PhysicalDataSourceProperties> dataSources = new ArrayList<>();
        @Builder.Default
        @Valid
        @NotNull
        private Ddl ddl = new Ddl();
        @NotNull
        private Duration maxAge;
        @Builder.Default
        @NotNull
        private Duration transactionTimeout = Duration.ofSeconds(5);

        @Override
        public String toString() {
            return "TargetProperties[dataSourceCount=" + dataSources.size() + "]";
        }
    }

    static final class TargetSqlPolicy implements EgonColaShardingDataSourceBootstrapper.LogicalDataSourceFactory {

        private static final Pattern RECEIPT = Pattern.compile("egon_cola_operation_log_receipt_t[01]_b[01]");
        private static final Pattern RECEIPT_PREDICATE = Pattern.compile(
                "(?:tenant_id|id) = (?:\\?|[0-9]+)|deleted_at IS NULL", Pattern.CASE_INSENSITIVE);

        @Override
        public DataSource create(Map<String, DataSource> physical, byte[] yaml) throws IOException {
            try {
                return YamlShardingSphereDataSourceFactory.createDataSource(physical, yaml);
            } catch (SQLException failure) {
                throw new IOException("OPERATION_LOG_TARGET_STARTUP_FAILED", failure);
            }
        }

        @Override
        public boolean enforceSingleWriteGroup() {
            return true;
        }

        @Override
        public void validateLogicalSql(String sql) throws SQLException {
            try {
                var statement = CCJSqlParserUtil.parse(sql);
                if (!(statement instanceof Select || statement instanceof Insert)) {
                    throw new SQLException("OPERATION_LOG_IMMUTABLE");
                }
            } catch (JSQLParserException error) {
                throw new SQLException("OPERATION_LOG_SQL_SHAPE_INVALID", error);
            }
        }

        @Override
        public boolean permitsPrimaryRead(Select query) {
            if (!(query instanceof PlainSelect plain) || !(plain.getFromItem() instanceof Table table)
                    || !RECEIPT.matcher(table.getName()).matches()
                    || table.getAlias() != null || plain.getDistinct() != null
                    || plain.getJoins() != null || plain.getWithItemsList() != null
                    || plain.getForMode() != null || plain.getGroupBy() != null || plain.getWhere() == null
                    || !plain.getSelectItems().stream().map(Object::toString).toList()
                            .equals(List.of("occurred_at", "payload_sha256"))) {
                return false;
            }
            String[] predicates = plain.getWhere().toString().replace("(", "").replace(")", "").split("(?i) AND ");
            return predicates.length >= 3 && predicates.length <= 4
                    && Arrays.stream(predicates).allMatch(value -> RECEIPT_PREDICATE.matcher(value).matches())
                    && Arrays.stream(predicates).anyMatch(value -> value.startsWith("tenant_id = "))
                    && Arrays.stream(predicates).anyMatch(value -> value.startsWith("id = "))
                    && Arrays.stream(predicates).anyMatch(value -> value.equalsIgnoreCase("deleted_at IS NULL"));
        }
    }

}
