package top.egon.cola.component.outbox.autoconfigure;

import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.apache.ibatis.binding.MapperProxyFactory;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.statemachine.StateMachine;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusAutoConfiguration;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.common.mybatis.ddl.EgonColaPostgreDdlRunner;
import top.egon.cola.component.common.mybatis.model.EgonModel;
import top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingTopologyValidator;
import top.egon.cola.component.outbox.delivery.DeliveryHandlerRegistry;
import top.egon.cola.component.outbox.persistence.dao.OutboxMessageDAO;
import top.egon.cola.component.outbox.persistence.po.OutboxMessagePO;
import top.egon.cola.component.outbox.persistence.repository.OutboxMessageRepository;
import top.egon.cola.component.outbox.persistence.OutboxTechnicalContextExecutor;
import top.egon.cola.component.outbox.store.MybatisPlusOutboxStore;
import top.egon.cola.component.outbox.store.OutboxStore;
import top.egon.cola.component.outbox.autoconfigure.TransactionalOutboxProperties.DispatchMode;
import top.egon.cola.component.outbox.common.exception.OutboxConfigurationException;
import top.egon.cola.component.outbox.dispatch.OutboxQueueCoordinator;
import top.egon.cola.component.outbox.statemachine.OutboxLifecycleService;

import javax.sql.DataSource;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class OutboxMybatisPlusAutoConfigurationTest {

    private static final String COMMON_MP_PROPERTIES_BEAN =
            "egon.cola.component.mybatis-plus-top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties";
    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    EgonColaMybatisPlusAutoConfiguration.class,
                    OutboxMybatisPlusAutoConfiguration.class,
                    OutboxMetricsAutoConfiguration.class,
                    TransactionalOutboxAutoConfiguration.class,
                    OutboxStateMachineAutoConfiguration.class))
            .withUserConfiguration(TestInfrastructureConfiguration.class)
            .withBean(Validator.class, VALIDATOR_FACTORY::getValidator)
            .withPropertyValues(
                    "egon.cola.component.mybatis-plus.tenant-id.ignored-tables[0]=egon_cola_outbox_message",
                    "egon.cola.component.transactional-outbox.storage.validate-schema=false",
                    "egon.cola.component.transactional-outbox.polling.enabled=false"
            );

    @Test
    void disabledOutboxDoesNotInstallStorageOrOverrideTheLogicalDatasourceFactory() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OutboxMybatisPlusAutoConfiguration.class))
                .withPropertyValues("egon.cola.component.transactional-outbox.enabled=false")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean("outboxManagedDdlInitializer")
                        .doesNotHaveBean("egonColaShardingLogicalDataSourceFactory")
                        .doesNotHaveBean("outboxMpStorageProperties")
                        .doesNotHaveBean(OutboxMessageDAO.class)
                        .doesNotHaveBean(OutboxStore.class));
    }

    @Test
    void defaultRuntimeUsesManagedMpStoreAndTheSelectedFactory() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(OutboxStore.class)
                    .hasSingleBean(MybatisPlusOutboxStore.class)
                    .hasSingleBean(OutboxLifecycleService.class);
            assertThat(context).doesNotHaveBean("outboxJdbcTemplate")
                    .doesNotHaveBean("outboxNamedParameterJdbcTemplate");
            assertThat(context.getBean("outboxStore")).isSameAs(context.getBean(OutboxStore.class));
            assertThat(context.getBean("outboxStateMachineObjectMapper"))
                    .isSameAs(context.getBean(ObjectMapper.class));
            assertThat(context.getBean(DataSource.class))
                    .isSameAs(context.getBean(SqlSessionFactory.class).getConfiguration().getEnvironment().getDataSource());
            assertThat(context.getBean(SqlSessionFactory.class).getConfiguration()
                    .hasStatement("top.egon.cola.component.outbox.persistence.dao.OutboxMessageDAO.insertMessage", false))
                    .isTrue();
            assertThat(context.getBean(OutboxStateMachineProperties.class).getBusiness().isEnabled()).isFalse();
            assertThat(context.getBean(TransactionalOutboxProperties.class).getDispatch().getMode())
                    .isEqualTo(DispatchMode.LEGACY);
            assertThat(context).doesNotHaveBean(OutboxQueueCoordinator.class);
            assertThatThrownBy(() -> context.getBean(DeliveryHandlerRegistry.class).required("statemachine"))
                    .hasMessageContaining("No transactional outbox handler");
        });
    }

    @Test
    void coordinated_mp_consumer_rejects_message_only_tenant_exclusions_without_falling_back() {
        contextRunner.withPropertyValues(
                "egon.cola.component.transactional-outbox.dispatch.mode=COORDINATED",
                "egon.cola.component.transactional-outbox.storage.validate-schema=true",
                "test.outbox.coordination-routing=true",
                "egon.cola.component.transactional-outbox.polling.enabled=true",
                "egon.cola.component.transactional-outbox.cleanup.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable cause = context.getStartupFailure();
                    while (cause.getCause() != null) cause = cause.getCause();
                    assertThat(cause).isInstanceOf(OutboxConfigurationException.class)
                            .hasMessageContaining("OUTBOX_TENANT_TABLE_IGNORE_REQUIRED");
                });
    }

    @Test
    void safe_mp_consumer_modes_reject_missing_queue_routes_even_when_all_tenant_exclusions_are_present() {
        for (String mode : List.of("LOCAL", "COORDINATED")) {
            contextRunner.withPropertyValues(
                    "egon.cola.component.transactional-outbox.dispatch.mode=" + mode,
                    "egon.cola.component.transactional-outbox.storage.validate-schema=true",
                    "egon.cola.component.transactional-outbox.polling.enabled=true",
                    "egon.cola.component.transactional-outbox.cleanup.enabled=false",
                    "egon.cola.component.mybatis-plus.tenant-id.ignored-tables[1]=egon_cola_outbox_queue_member",
                    "egon.cola.component.mybatis-plus.tenant-id.ignored-tables[2]=egon_cola_outbox_queue_lease")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        Throwable cause = context.getStartupFailure();
                        while (cause.getCause() != null) cause = cause.getCause();
                        assertThat(cause).isInstanceOf(OutboxConfigurationException.class)
                                .hasMessageContaining("OUTBOX_QUEUE_TOPOLOGY_MISMATCH");
                    });
        }
    }

    @Test
    void safe_mp_consumer_modes_cannot_disable_required_schema_validation() {
        for (String mode : List.of("LOCAL", "COORDINATED")) {
            contextRunner.withPropertyValues(
                    "egon.cola.component.transactional-outbox.dispatch.mode=" + mode,
                    "egon.cola.component.transactional-outbox.polling.enabled=true",
                    "egon.cola.component.transactional-outbox.cleanup.enabled=false")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).hasStackTraceContaining(
                                "OUTBOX_MP_SAFE_DISPATCH_REQUIRES_SCHEMA_AND_SINGLE_CLAIM");
                    });
        }
    }

    @Test
    void legacy_mp_pause_is_wired_to_the_built_in_store_without_requiring_queue_profiles() {
        contextRunner.withPropertyValues("egon.cola.component.transactional-outbox.dispatch.consumption-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MybatisPlusOutboxStore.class);
                    assertThatThrownBy(() -> context.getBean(OutboxStore.class)
                            .claimDue(1, "paused", java.time.Duration.ofMinutes(1)))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("OUTBOX_LEGACY_CLAIM_DISABLED");
                });
    }

    @Test
    void acceptsValidatedStrategyProfileForOutboxStore() {
        contextRunner.withPropertyValues("test.outbox.strategy-config-style=true")
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(OutboxStore.class)
                        .hasSingleBean(MybatisPlusOutboxStore.class));
    }

    @Test
    void technicalOutboxRestoreMethodIsAnUnsupportedMapperDefault() throws Exception {
        var method = OutboxMessageDAO.class.getMethod("restoreVersionedById", EgonModel.class);
        assertThat(method.isDefault()).isTrue();
        OutboxMessageDAO mapper = new MapperProxyFactory<>(OutboxMessageDAO.class)
                .newInstance(mock(SqlSession.class));

        assertThatThrownBy(() -> mapper.restoreVersionedById(new OutboxMessagePO()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("OUTBOX_RESTORE_UNSUPPORTED");
    }

    @Test
    void generatedConstructorsRetainTheExactCommonMpPropertiesQualifier() {
        assertThat(propertiesQualifier(OutboxMessageRepository.class,
                EgonColaMybatisPlusProperties.class)).isEqualTo(COMMON_MP_PROPERTIES_BEAN);
        assertThat(propertiesQualifier(OutboxTechnicalContextExecutor.class,
                EgonColaMybatisPlusProperties.class)).isEqualTo(COMMON_MP_PROPERTIES_BEAN);
    }

    private static String propertiesQualifier(Class<?> type, Class<?> dependency) {
        Constructor<?> constructor = Arrays.stream(type.getConstructors())
                .filter(candidate -> Arrays.asList(candidate.getParameterTypes()).contains(dependency))
                .findFirst().orElseThrow();
        Parameter parameter = Arrays.stream(constructor.getParameters())
                .filter(candidate -> candidate.getType().equals(dependency))
                .findFirst().orElseThrow();
        Qualifier qualifier = parameter.getAnnotation(Qualifier.class);
        return qualifier == null ? null : qualifier.value();
    }

    @Test
    void mismatchedSessionFactoryDataSourceFailsWithoutJdbcFallback() {
        contextRunner.withPropertyValues("test.outbox.factory-datasource-mismatch=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().toString())
                            .contains("OUTBOX_SQL_SESSION_FACTORY_MISMATCH");
                });
    }

    @Test
    void multiplePrimaryDatasourcesFailClosed() {
        contextRunner.withPropertyValues("test.outbox.multiple-primary=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().toString())
                            .contains("OUTBOX_SINGLE_PRIMARY_REQUIRED");
                });
    }

    @Test
    void primaryReplicaGroupsStartWhenTheTechnicalTableCoversAllGroups() {
        contextRunner.withPropertyValues("test.outbox.readwrite=true")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(MybatisPlusOutboxStore.class));
        contextRunner.withPropertyValues("test.outbox.readwrite=true", "test.outbox.multiple-primary=true")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(MybatisPlusOutboxStore.class));
    }

    @Test
    void multiGroupRejectsACustomFactoryThatDoesNotInstallTheOutboxGuard() {
        contextRunner.withPropertyValues("test.outbox.readwrite=true", "test.outbox.multiple-primary=true")
                .withBean("egonColaShardingLogicalDataSourceFactory",
                        top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingDataSourceBootstrapper.LogicalDataSourceFactory.class,
                        () -> (physical, yaml) -> mock(DataSource.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().toString()).contains("OUTBOX_SHARD_GUARD_REQUIRED");
                });
    }

    @Test
    void missingMapperXmlFailsInsteadOfCreatingAJdbcStore() {
        contextRunner.withPropertyValues("test.outbox.mapper-xml-missing=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().toString())
                            .contains("OUTBOX_MAPPER_XML_STATEMENT_REQUIRED");
                });
    }

    @Test
    void jdbcMpStoreWorksWhenOrmOrPersistenceApiIsAbsent() throws Exception {
        for (String hidden : List.of("org.springframework.orm", "jakarta.persistence")) {
            try (var loader = new org.springframework.boot.test.context.FilteredClassLoader(hidden)) {
                contextRunner.withClassLoader(loader).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MybatisPlusOutboxStore.class);
                    org.mockito.Mockito.verify(context.getBean(DataSource.class), org.mockito.Mockito.never()).getConnection();
                });
            }
        }
    }

    @Test
    void unsupportedManagerWithOptionalTypesAbsentFailsWithControlledConfigurationError() throws Exception {
        for (String hidden : List.of("org.springframework.orm", "jakarta.persistence")) {
            try (var loader = new org.springframework.boot.test.context.FilteredClassLoader(hidden)) {
                contextRunner.withClassLoader(loader).withPropertyValues("test.outbox.unknown-manager=true").run(context -> {
                    assertThat(context).hasFailed(); Throwable cause = context.getStartupFailure();
                    while (cause.getCause() != null && cause.getCause() != cause) { cause = cause.getCause(); }
                    assertThat(cause).isInstanceOf(top.egon.cola.component.outbox.common.exception.OutboxConfigurationException.class)
                            .hasMessageContaining("OUTBOX_DATASOURCE_TRANSACTION_MANAGER_REQUIRED");
                });
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {

        @Bean("outboxManagedDdlInitializer")
        top.egon.cola.component.outbox.migration.OutboxManagedDdlInitializer outboxManagedDdlInitializer(
                @org.springframework.beans.factory.annotation.Value("${test.outbox.readwrite:false}") boolean readwrite,
                @org.springframework.beans.factory.annotation.Value("${test.outbox.multiple-primary:false}") boolean multiplePrimary) {
            var initializer = mock(top.egon.cola.component.outbox.migration.OutboxManagedDdlInitializer.class);
            org.mockito.Mockito.when(initializer.isMultiGroupMode()).thenReturn(readwrite && multiplePrimary);
            return initializer;
        }

        @Bean("dataSource")
        DataSource dataSource() {
            return mock(DataSource.class);
        }

        @Bean("transactionManager")
        org.springframework.transaction.PlatformTransactionManager transactionManager(@Qualifier("dataSource") DataSource dataSource,
                @org.springframework.beans.factory.annotation.Value("${test.outbox.unknown-manager:false}") boolean unknown) {
            return unknown ? mock(org.springframework.transaction.PlatformTransactionManager.class) : new DataSourceTransactionManager(dataSource);
        }

        @Bean("objectMapper")
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean("egonColaRoutingProfiles")
        Map<String, EgonColaRoutingProfileBO> routingProfiles(
                @Qualifier("egon.cola.component.mybatis-plus.sharding-top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties")
                EgonColaShardingProperties properties,
                @org.springframework.beans.factory.annotation.Value("${test.outbox.coordination-routing:false}") boolean coordinationRouting) {
            List<String> groups = properties.getDataSources().stream()
                    .filter(source -> source.role() == EgonColaShardingProperties.DataSourceRoleEnum.PRIMARY)
                    .map(EgonColaShardingProperties.PhysicalDataSourceProperties::logicalName).toList();
            Map<String, EgonColaRoutingProfileBO> profiles = new java.util.LinkedHashMap<>();
            List<String> tables = coordinationRouting ? List.of("egon_cola_outbox_message",
                    "egon_cola_outbox_queue_member", "egon_cola_outbox_queue_lease") : List.of("egon_cola_outbox_message");
            for (String logicalTable : tables) {
                if (properties.getMode() == EgonColaShardingProperties.ModeEnum.SHARDING_READWRITE && groups.size() > 1) {
                    var table = new EgonColaShardingProperties.TableProperties("TECHNICAL_GROUP", groups.getFirst(),
                            "shard_slot", null, null, "egon_outbox", groups);
                    profiles.put(logicalTable, new top.egon.cola.component.common.mybatis.sharding.strategy
                            .EgonColaTechnicalGroupShardingStrategy().toRoutingProfile(logicalTable, table, properties.getDataSources()));
                } else {
                    var node = new top.egon.cola.component.common.mybatis.routing.EgonColaPhysicalTargetBO(
                            "primary_a", "egon_outbox", logicalTable);
                    profiles.put(logicalTable, top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaShardingStrategyNodes.simple(
                            logicalTable, EgonColaRoutingProfileBO.TableKindEnum.SINGLE, List.of(node)));
                }
            }
            return Map.copyOf(profiles);
        }

        @Bean("egonColaWriteTargetResolver")
        top.egon.cola.component.common.mybatis.routing.EgonColaWriteTargetResolver writeTargetResolver() {
            return query -> {
                throw new IllegalStateException("SHARDING_REQUIRED");
            };
        }

        @Bean("egonColaShardingRouteFingerprint")
        String routeFingerprint() {
            return "a".repeat(64);
        }

        @Bean("egon.cola.component.mybatis-plus.sharding-top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties")
        EgonColaShardingProperties shardingProperties(
                @org.springframework.beans.factory.annotation.Value("${test.outbox.multiple-primary:false}") boolean multiplePrimary,
                @org.springframework.beans.factory.annotation.Value("${test.outbox.strategy-config-style:false}") boolean strategyConfigStyle,
                @org.springframework.beans.factory.annotation.Value("${test.outbox.readwrite:false}") boolean readwrite
        ) {
            List<EgonColaShardingProperties.PhysicalDataSourceProperties> sources = readwrite
                    ? (multiplePrimary ? List.of(primary("primary_a"), replica("replica_a", "primary_a"),
                    primary("primary_b"), replica("replica_b", "primary_b"))
                    : List.of(primary("primary_a"), replica("replica_a", "primary_a"))) : multiplePrimary
                    ? List.of(primary("primary_a"), primary("primary_b"))
                    : List.of(primary("primary_a"));
            return EgonColaShardingProperties.builder()
                    .mode(readwrite ? EgonColaShardingProperties.ModeEnum.SHARDING_READWRITE
                            : EgonColaShardingProperties.ModeEnum.SHARDING)
                    .configStyle(strategyConfigStyle
                            ? EgonColaShardingProperties.ConfigStyleEnum.STRATEGY
                            : EgonColaShardingProperties.ConfigStyleEnum.NATIVE)
                    .transactionDefaultType("LOCAL")
                    .dataSources(sources)
                    .tables(Map.of())
                    .build();
        }

        @Bean("egonColaShardingTopologyValidator")
        EgonColaShardingTopologyValidator shardingTopologyValidator() {
            return mock(EgonColaShardingTopologyValidator.class);
        }

        @Bean("sqlSessionFactory")
        SqlSessionFactory sqlSessionFactory(
                @Qualifier("dataSource") DataSource dataSource,
                @Qualifier("egon.cola.component.mybatis-plus-top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties")
                EgonColaMybatisPlusProperties properties,
                org.springframework.core.env.Environment environment,
                MybatisPlusInterceptor outer,
                top.egon.cola.component.common.mybatis.interceptor.EgonColaModelValidationInterceptor validation,
                top.egon.cola.component.common.mybatis.interceptor.EgonColaOriginalSqlGuardInterceptor original,
                top.egon.cola.component.common.mybatis.model.EgonColaIdentifierGenerator idGenerator,
                com.baomidou.mybatisplus.core.handlers.MetaObjectHandler metaObjectHandler
        ) throws IOException {
            boolean mismatch = environment.getProperty("test.outbox.factory-datasource-mismatch", Boolean.class, false);
            boolean missingXml = environment.getProperty("test.outbox.mapper-xml-missing", Boolean.class, false);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("outbox-context-test",
                    new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),
                    mismatch ? mock(DataSource.class) : dataSource));
            configuration.addInterceptor(outer);
            configuration.addInterceptor(validation);
            configuration.addInterceptor(original);
            GlobalConfig global = new GlobalConfig();
            global.setDbConfig(new GlobalConfig.DbConfig());
            global.setMetaObjectHandler(metaObjectHandler);
            global.setIdentifierGenerator(idGenerator);
            GlobalConfigUtils.setGlobalConfig(configuration, global);
            configuration.addMapper(top.egon.cola.component.outbox.persistence.dao.OutboxMessageDAO.class);
            if (!missingXml) {
                try (InputStream mapperXml = new ClassPathResource("mapper/outbox/OutboxMessageMapper.xml").getInputStream()) {
                    new XMLMapperBuilder(mapperXml, configuration, "mapper/outbox/OutboxMessageMapper.xml",
                            configuration.getSqlFragments()).parse();
                }
            }
            return new DefaultSqlSessionFactory(configuration);
        }

        private static EgonColaShardingProperties.PhysicalDataSourceProperties primary(String name) {
            return new EgonColaShardingProperties.PhysicalDataSourceProperties(name, name,
                    EgonColaShardingProperties.DataSourceRoleEnum.PRIMARY, "org.postgresql.Driver",
                    "jdbc:postgresql://localhost/outbox_test", "test", "test");
        }

        private static EgonColaShardingProperties.PhysicalDataSourceProperties replica(String name, String group) {
            return new EgonColaShardingProperties.PhysicalDataSourceProperties(name, group,
                    EgonColaShardingProperties.DataSourceRoleEnum.REPLICA, "org.postgresql.Driver",
                    "jdbc:postgresql://localhost/outbox_test", "test", "test");
        }
    }
}
