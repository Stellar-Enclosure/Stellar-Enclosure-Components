package top.egon.cola.component.common.mybatis.sharding;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.apache.shardingsphere.driver.yaml.YamlJDBCConfiguration;
import org.apache.shardingsphere.infra.util.yaml.YamlEngine;
import org.apache.shardingsphere.readwritesplitting.yaml.config.YamlReadwriteSplittingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.yaml.config.YamlShardingRuleConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.common.mybatis.routing.EgonColaRouteQuery;
import top.egon.cola.component.common.mybatis.routing.EgonColaTwoLevelRouteStrategy;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.ConfigStyleEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.DataSourceRoleEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.ModeEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.PhysicalDataSourceProperties;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.TableProperties;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingTopologyValidator;
import top.egon.cola.component.common.mybatis.sharding.algorithm.EgonColaStandardTenantIdShardingAlgorithm;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingYamlLoader;
import top.egon.cola.component.common.mybatis.sharding.resolver.EgonColaShardingWriteTargetResolver;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaComplexTenantThenBusinessShardingStrategy;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaShardingStrategy;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaShardingStrategyFactory;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaSingleTableShardingStrategy;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaStandardTenantIdShardingStrategy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EgonColaReadwriteTopologyTest {

    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private final ValidationUtils validation = new ValidationUtils(VALIDATORS.getValidator());
    private final EgonColaTwoLevelRouteStrategy route = new EgonColaTwoLevelRouteStrategy(validation);
    private final Map<String, EgonColaShardingStrategy> strategies = Map.of(
            "SINGLE", new EgonColaSingleTableShardingStrategy(),
            "STANDARD_TENANT_ID", new EgonColaStandardTenantIdShardingStrategy(),
            "COMPLEX_TENANT_THEN_BUSINESS", new EgonColaComplexTenantThenBusinessShardingStrategy(route));
    private final EgonColaShardingYamlLoader loader = new EgonColaShardingYamlLoader(
            new EgonColaShardingStrategyFactory(validation, strategies), new DefaultResourceLoader(), new StandardEnvironment());
    private final EgonColaShardingTopologyValidator validator = new EgonColaShardingTopologyValidator(validation);

    @AfterAll
    static void closeValidators() {
        VALIDATORS.close();
    }

    @Test
    void readwriteRulesUseOnePrimaryAndOnlyTheReplicasOfEachGroup() {
        var properties = properties(Map.of("orders", standard()));
        byte[] yaml = loader.load(properties);
        var topology = validator.validate(properties, yaml);
        var configuration = YamlEngine.unmarshal(new String(topology.yaml(), StandardCharsets.UTF_8), YamlJDBCConfiguration.class);
        var rw = configuration.getRules().stream().filter(YamlReadwriteSplittingRuleConfiguration.class::isInstance)
                .map(YamlReadwriteSplittingRuleConfiguration.class::cast).findFirst().orElseThrow();
        assertThat(rw.getDataSourceGroups().get("shard_0").getWriteDataSourceName()).isEqualTo("shard_0_primary");
        assertThat(rw.getDataSourceGroups().get("shard_0").getReadDataSourceNames()).containsExactly("shard_0_replica");
        assertThat(rw.getDataSourceGroups().get("shard_1").getReadDataSourceNames()).containsExactly("shard_1_replica");
        assertThat(rw.getDataSourceGroups().values()).allSatisfy(group ->
                assertThat(group.getTransactionalReadQueryStrategy()).isEqualTo("DYNAMIC"));
        var resolver = new EgonColaShardingWriteTargetResolver(topology.profiles(), route, validation, topology.fingerprint());
        assertThat(resolver.resolve(new EgonColaRouteQuery("orders", 1L, List.of(),
                EgonColaRouteQuery.OperationEnum.COMMAND, false)).targets().getFirst().group()).isEqualTo("shard_1");
    }

    @Test
    void complexRulesAreMergedAndCarryTheSameRouteProfileForBothAlgorithms() {
        var properties = properties(Map.of("orders", new TableProperties("COMPLEX_TENANT_THEN_BUSINESS",
                null, "tenant_id", List.of("tenant_id", "order_id"), "order_id")));
        var topology = validator.validate(properties, loader.load(properties));
        assertThat(topology.profiles()).containsKey("orders");
        assertThat(topology.profiles().get("orders").actualNodes()).hasSize(4);
    }

    @Test
    void rejectsComplexColumnsThatWouldOtherwiseBeSilentlyIgnored() {
        var wrongDatabaseKey = properties(Map.of("orders", new TableProperties("COMPLEX_TENANT_THEN_BUSINESS",
                null, "account_id", List.of("tenant_id", "order_id"), "order_id")));
        assertThatThrownBy(() -> loader.load(wrongDatabaseKey)).hasMessageContaining("SHARDING_KEY");
        var extraColumn = properties(Map.of("orders", new TableProperties("COMPLEX_TENANT_THEN_BUSINESS",
                null, "tenant_id", List.of("tenant_id", "order_id", "account_id"), "order_id")));
        assertThatThrownBy(() -> loader.load(extraColumn)).hasMessageContaining("SHARDING_KEY");
    }

    @Test
    void rejectsIncompleteOrMisassignedPrimaryReplicaGroups() {
        var bad = properties(Map.of("orders", standard()));
        bad.setDataSources(List.of(source("shard_0_primary", "shard_0", DataSourceRoleEnum.PRIMARY)));
        assertThatThrownBy(() -> loader.load(bad)).hasMessageContaining("READWRITE_GROUP_REQUIRES_PRIMARY_AND_REPLICA");

        var properties = properties(Map.of("orders", standard()));
        String yaml = new String(loader.load(properties), StandardCharsets.UTF_8)
                .replace("writeDataSourceName: shard_0_primary", "writeDataSourceName: shard_0_replica");
        assertThatThrownBy(() -> validator.validate(properties, yaml.getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("READWRITE_GROUP_MISMATCH");
    }

    @Test
    void generatedAlgorithmAndWriteGuardUseTheSameOrderWhenSourcesAreReordered() {
        var properties = properties(Map.of("orders", standard()));
        properties.setDataSources(List.of(source("shard_1_primary", "shard_1", DataSourceRoleEnum.PRIMARY),
                source("shard_1_replica", "shard_1", DataSourceRoleEnum.REPLICA),
                source("shard_0_primary", "shard_0", DataSourceRoleEnum.PRIMARY),
                source("shard_0_replica", "shard_0", DataSourceRoleEnum.REPLICA)));
        var topology = validator.validate(properties, loader.load(properties));
        var yaml = YamlEngine.unmarshal(new String(topology.yaml(), StandardCharsets.UTF_8), YamlJDBCConfiguration.class);
        var sharding = yaml.getRules().stream().filter(YamlShardingRuleConfiguration.class::isInstance)
                .map(YamlShardingRuleConfiguration.class::cast).findFirst().orElseThrow();
        var algorithm = new EgonColaStandardTenantIdShardingAlgorithm();
        algorithm.init(sharding.getShardingAlgorithms().get("orders_database").getProps());
        var actual = algorithm.doSharding(List.of("shard_0", "shard_1"),
                new PreciseShardingValue<Comparable<?>>("orders", "tenant_id", null, 1));
        var resolver = new EgonColaShardingWriteTargetResolver(topology.profiles(), route, validation, topology.fingerprint());
        var guarded = resolver.resolve(new EgonColaRouteQuery("orders", 1L, List.of(),
                EgonColaRouteQuery.OperationEnum.COMMAND, false)).targets().getFirst().group();
        assertThat(actual).isEqualTo(guarded);
    }

    @Test
    void strictNativeRulesRejectAnAlgorithmOrderThatDoesNotMatchTheGuard() {
        var properties = properties(Map.of("orders", standard()));
        String yaml = new String(loader.load(properties), StandardCharsets.UTF_8)
                .replace("target-order: shard_0,shard_1", "target-order: shard_1,shard_0");
        properties.setConfigStyle(ConfigStyleEnum.NATIVE);
        properties.setTables(Map.of());
        properties.setNativeRulesResource("classpath:test-native.yml");
        assertThatThrownBy(() -> validator.validate(properties, yaml.getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("SHARDING_GUARD_ALGORITHM_UNSUPPORTED");
    }

    @Test
    void missingDatabaseTargetDoesNotChangeTheModuloAndSilentlyMoveATenant() {
        var algorithm = new EgonColaStandardTenantIdShardingAlgorithm();
        var properties = new Properties();
        properties.setProperty("target-order", "shard_0,shard_1");
        properties.setProperty("require-complete-targets", "true");
        algorithm.init(properties);
        var value = new PreciseShardingValue<Comparable<?>>("orders", "tenant_id", null, 1);
        assertThatThrownBy(() -> algorithm.doSharding(List.of("shard_0"), value))
                .hasMessageContaining("SHARDING_TARGET_UNAVAILABLE");
    }

    @Test
    void tableCanSelectItsShardGroupsWithoutIncludingTheMetadataPrimary() {
        var table = new TableProperties("STANDARD_TENANT_ID", null, "tenant_id", null, null,
                "public", List.of("shard_0", "shard_1"));
        var properties = properties(Map.of("orders", table));
        properties.setDataSources(List.of(source("metadata_primary", "metadata", DataSourceRoleEnum.PRIMARY),
                source("metadata_replica", "metadata", DataSourceRoleEnum.REPLICA),
                source("shard_0_primary", "shard_0", DataSourceRoleEnum.PRIMARY),
                source("shard_0_replica", "shard_0", DataSourceRoleEnum.REPLICA),
                source("shard_1_primary", "shard_1", DataSourceRoleEnum.PRIMARY),
                source("shard_1_replica", "shard_1", DataSourceRoleEnum.REPLICA)));
        var topology = validator.validate(properties, loader.load(properties));
        assertThat(topology.profiles().get("orders").actualNodes().values().stream()
                .flatMap(List::stream).map(node -> node.group()).toList()).containsExactly("shard_0", "shard_1");
        var unknown = new TableProperties("STANDARD_TENANT_ID", null, "tenant_id", null, null,
                "public", List.of("missing"));
        properties.setTables(Map.of("orders", unknown));
        assertThatThrownBy(() -> loader.load(properties)).hasMessageContaining("SHARDING_GROUP_INVALID");
    }

    private static EgonColaShardingProperties properties(Map<String, TableProperties> tables) {
        return EgonColaShardingProperties.builder().mode(ModeEnum.SHARDING_READWRITE).configStyle(ConfigStyleEnum.STRATEGY)
                .dataSources(List.of(source("shard_0_primary", "shard_0", DataSourceRoleEnum.PRIMARY),
                        source("shard_0_replica", "shard_0", DataSourceRoleEnum.REPLICA),
                        source("shard_1_primary", "shard_1", DataSourceRoleEnum.PRIMARY),
                        source("shard_1_replica", "shard_1", DataSourceRoleEnum.REPLICA)))
                .tables(tables).build();
    }

    private static TableProperties standard() {
        return new TableProperties("STANDARD_TENANT_ID", null, "tenant_id", null, null);
    }

    private static PhysicalDataSourceProperties source(String name, String group, DataSourceRoleEnum role) {
        return new PhysicalDataSourceProperties(name, group, role, "org.postgresql.Driver",
                "jdbc:postgresql://localhost/" + name, "test", "test");
    }
}
