package top.egon.cola.component.common.mybatis.sharding;

import jakarta.validation.Validation;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.hint.HintManager;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.common.mybatis.routing.EgonColaRouteQuery;
import top.egon.cola.component.common.mybatis.routing.EgonColaTwoLevelRouteStrategy;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.ConfigStyleEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.DataSourceRoleEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.ModeEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.PhysicalDataSourceProperties;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.TableProperties;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingTopologyValidator;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaRouteHintGuardedDataSource;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingYamlLoader;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaShardingStrategyFactory;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaComplexTenantThenBusinessShardingStrategy;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaBroadcastReadOnlyShardingStrategy;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaSingleTableShardingStrategy;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaStandardTenantIdShardingStrategy;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaTechnicalGroupShardingStrategy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EgonColaShardingReadwriteIntegrationTest {

    @Test
    void exactTechnicalSlotsRouteEachReplicaReadAndPrimaryWriteToItsOwnGroup() throws Exception {
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            var validation = new ValidationUtils(validators.getValidator());
            var properties = EgonColaShardingProperties.builder().mode(ModeEnum.SHARDING_READWRITE)
                    .configStyle(ConfigStyleEnum.STRATEGY)
                    .dataSources(List.of(source("shard_0_primary", "shard_0", DataSourceRoleEnum.PRIMARY),
                            source("shard_0_replica", "shard_0", DataSourceRoleEnum.REPLICA),
                            source("shard_1_primary", "shard_1", DataSourceRoleEnum.PRIMARY),
                            source("shard_1_replica", "shard_1", DataSourceRoleEnum.REPLICA)))
                    .tables(Map.of("outbox_test", new TableProperties("TECHNICAL_GROUP", "shard_0", "shard_slot",
                            null, null, "public", List.of("shard_0", "shard_1")))).build();
            var factory = new EgonColaShardingStrategyFactory(validation,
                    Map.of("TECHNICAL_GROUP", new EgonColaTechnicalGroupShardingStrategy()));
            var loader = new EgonColaShardingYamlLoader(factory, new DefaultResourceLoader(), new StandardEnvironment());
            var topology = new EgonColaShardingTopologyValidator(validation).validate(properties, loader.load(properties));
            Map<Integer, String> slots = topology.profiles().get("outbox_test").tenantSlotMap();
            assertThat(slots).containsEntry(0, "shard_0").containsValue("shard_1");
            Map<String, DataSource> physical = new LinkedHashMap<>();
            String run = UUID.randomUUID().toString().replace("-", "");
            for (var source : properties.getDataSources()) {
                JdbcDataSource ds = new JdbcDataSource();
                ds.setURL("jdbc:h2:mem:" + run + source.name() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
                int slot = slots.entrySet().stream().filter(entry -> entry.getValue().equals(source.logicalName()))
                        .map(Map.Entry::getKey).findFirst().orElseThrow();
                try (Connection connection = ds.getConnection(); var statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE outbox_test (id BIGINT, shard_slot INT, marker VARCHAR(100))");
                    statement.execute("INSERT INTO outbox_test VALUES (1, " + slot + ", '" + source.name() + "')");
                }
                physical.put(source.name(), ds);
            }
            byte[] h2Yaml = new String(topology.yaml(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace(".public.outbox_test", ".outbox_test")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            DataSource logical = new EgonColaRouteHintGuardedDataSource(
                    YamlShardingSphereDataSourceFactory.createDataSource(physical, h2Yaml));
            try {
                try (Connection connection = logical.getConnection()) {
                    for (var slot : slots.entrySet()) {
                        try (var read = connection.prepareStatement("SELECT marker FROM outbox_test WHERE shard_slot = ?")) {
                            read.setInt(1, slot.getKey());
                            try (var rows = read.executeQuery()) {
                                assertThat(rows.next()).isTrue();
                                assertThat(rows.getString(1)).isEqualTo(slot.getValue() + "_replica");
                            }
                        }
                        try (var write = connection.prepareStatement("UPDATE outbox_test SET marker = 'written' WHERE shard_slot = ?")) {
                            write.setInt(1, slot.getKey());
                            assertThat(write.executeUpdate()).isEqualTo(1);
                        }
                    }
                    int secondSlot = slots.entrySet().stream().filter(entry -> entry.getValue().equals("shard_1"))
                            .map(Map.Entry::getKey).findFirst().orElseThrow();
                    try (var insert = connection.prepareStatement(
                            "INSERT INTO outbox_test (id, shard_slot, marker) VALUES (?, ?, ?)")) {
                        insert.setLong(1, 2L);
                        insert.setInt(2, secondSlot);
                        insert.setString(3, "inserted");
                        assertThat(insert.executeUpdate()).isEqualTo(1);
                    }
                    try (Connection first = physical.get("shard_0_primary").getConnection();
                         var rows = first.createStatement().executeQuery("SELECT count(*) FROM outbox_test WHERE id = 2")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong(1)).isZero();
                    }
                    try (Connection second = physical.get("shard_1_primary").getConnection();
                         var rows = second.createStatement().executeQuery("SELECT count(*) FROM outbox_test WHERE id = 2")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong(1)).isEqualTo(1);
                    }
                    try (var delete = connection.prepareStatement("DELETE FROM outbox_test WHERE id = ? AND shard_slot = ?")) {
                        delete.setLong(1, 2L);
                        delete.setInt(2, secondSlot);
                        assertThat(delete.executeUpdate()).isEqualTo(1);
                    }
                }
                for (var slot : slots.entrySet()) {
                    try (Connection connection = physical.get(slot.getValue() + "_primary").getConnection();
                         var rows = connection.createStatement().executeQuery("SELECT marker FROM outbox_test")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(1)).isEqualTo("written");
                    }
                }
            } finally {
                ((AutoCloseable) logical).close();
            }
        }
    }

    @Test
    void shardingSphereRoutesReadsAndWritesToTheSelectedGroupsPhysicalNodes() throws Exception {
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            var validation = new ValidationUtils(validators.getValidator());
            var properties = EgonColaShardingProperties.builder().mode(ModeEnum.SHARDING_READWRITE)
                    .configStyle(ConfigStyleEnum.STRATEGY)
                    .dataSources(List.of(source("shard_0_primary", "shard_0", DataSourceRoleEnum.PRIMARY),
                            source("shard_0_replica", "shard_0", DataSourceRoleEnum.REPLICA),
                            source("shard_1_primary", "shard_1", DataSourceRoleEnum.PRIMARY),
                            source("shard_1_replica", "shard_1", DataSourceRoleEnum.REPLICA)))
                    .tables(Map.of("orders", new TableProperties("STANDARD_TENANT_ID", null, "tenant_id", null, null),
                            "deliveries", new TableProperties("COMPLEX_TENANT_THEN_BUSINESS", null, "tenant_id",
                                    List.of("tenant_id", "order_id"), "order_id"),
                            "categories", new TableProperties("SINGLE", "shard_0", null, null, null),
                            "regions", new TableProperties("BROADCAST", null, null, null, null))).build();
            var factory = new EgonColaShardingStrategyFactory(validation,
                    Map.of("STANDARD_TENANT_ID", new EgonColaStandardTenantIdShardingStrategy(),
                            "COMPLEX_TENANT_THEN_BUSINESS", new EgonColaComplexTenantThenBusinessShardingStrategy(
                                    new EgonColaTwoLevelRouteStrategy(validation)),
                            "SINGLE", new EgonColaSingleTableShardingStrategy(),
                            "BROADCAST", new EgonColaBroadcastReadOnlyShardingStrategy()));
            var loader = new EgonColaShardingYamlLoader(factory, new DefaultResourceLoader(), new StandardEnvironment());
            var topology = new EgonColaShardingTopologyValidator(validation).validate(properties, loader.load(properties));
            Map<String, DataSource> physical = new LinkedHashMap<>();
            String run = UUID.randomUUID().toString().replace("-", "");
            for (var source : properties.getDataSources()) {
                JdbcDataSource ds = new JdbcDataSource();
                ds.setURL("jdbc:h2:mem:" + run + source.name() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
                int shard = source.logicalName().endsWith("0") ? 0 : 1;
                try (Connection connection = ds.getConnection(); var statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE orders_t" + shard + " (tenant_id BIGINT, marker VARCHAR(100))");
                    statement.execute("INSERT INTO orders_t" + shard + " VALUES (" + (shard == 0 ? 2 : 1)
                            + ", '" + source.name() + "')");
                    for (int bucket = 0; bucket < 2; bucket++) {
                        String table = "deliveries_t" + shard + "_b" + bucket;
                        statement.execute("CREATE TABLE " + table + " (tenant_id BIGINT, order_id BIGINT, marker VARCHAR(100))");
                        statement.execute("INSERT INTO " + table + " VALUES (1, 42, '" + source.name() + "')");
                    }
                    statement.execute("CREATE TABLE categories (tenant_id BIGINT, marker VARCHAR(100))");
                    statement.execute("INSERT INTO categories VALUES (1, '" + source.name() + "')");
                    statement.execute("CREATE TABLE regions (tenant_id BIGINT, marker VARCHAR(100))");
                    statement.execute("INSERT INTO regions VALUES (1, '" + source.name() + "')");
                }
                physical.put(source.name(), ds);
            }
            // H2's single-table dialect uses two-part nodes; PostgreSQL uses group.schema.table.
            byte[] h2Yaml = new String(topology.yaml(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("shard_0.public.categories", "shard_0.categories")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            DataSource logical = new EgonColaRouteHintGuardedDataSource(
                    YamlShardingSphereDataSourceFactory.createDataSource(physical, h2Yaml));
            try {
                try (Connection connection = logical.getConnection(); var statement = connection.createStatement()) {
                    try (var result = statement.executeQuery("SELECT marker FROM orders WHERE tenant_id = 1")) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getString(1)).isEqualTo("shard_1_replica");
                    }
                    assertThat(statement.executeUpdate("UPDATE orders SET marker = 'written' WHERE tenant_id = 1")).isEqualTo(1);
                    var target = EgonColaTwoLevelRouteStrategy.routeCandidates(topology.profiles().get("deliveries"),
                            new EgonColaRouteQuery("deliveries", 1L, List.of(42L),
                                    EgonColaRouteQuery.OperationEnum.ROUTE_CANDIDATES, false)).targets().getFirst();
                    try (var result = statement.executeQuery("SELECT marker FROM deliveries WHERE tenant_id = 1 AND order_id = 42")) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getString(1)).isEqualTo(target.group() + "_replica");
                    }
                    try (var result = statement.executeQuery("SELECT marker FROM categories WHERE tenant_id = 1")) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getString(1)).isEqualTo("shard_0_replica");
                    }
                    try (HintManager hint = HintManager.getInstance()) {
                        hint.setDataSourceName("shard_1_replica");
                        assertThatThrownBy(() -> statement.executeQuery("SELECT marker FROM categories WHERE tenant_id = 1"))
                                .hasMessageContaining("READWRITE_HINT_FORBIDDEN");
                    }
                    assertThatThrownBy(() -> statement.executeQuery("/* ShardingSphere hint: DATA_SOURCE_NAME=shard_1_replica */ "
                            + "SELECT marker FROM categories WHERE tenant_id = 1"))
                            .hasMessageContaining("READWRITE_HINT_FORBIDDEN");
                    for (int i = 0; i < 20; i++) {
                        try (var result = statement.executeQuery("SELECT marker FROM regions WHERE tenant_id = 1")) {
                            assertThat(result.next()).isTrue();
                            assertThat(result.getString(1)).endsWith("_replica");
                        }
                    }
                }
                try (Connection connection = logical.getConnection()) {
                    connection.setAutoCommit(false);
                    try (var statement = connection.createStatement()) {
                        statement.executeUpdate("UPDATE orders SET marker = 'transactional' WHERE tenant_id = 1");
                        try (var result = statement.executeQuery("SELECT marker FROM orders WHERE tenant_id = 1")) {
                            assertThat(result.next()).isTrue();
                            assertThat(result.getString(1)).isEqualTo("shard_1_replica");
                        }
                        for (int i = 0; i < 20; i++) {
                            try (var result = statement.executeQuery("SELECT marker FROM regions WHERE tenant_id = 1")) {
                                assertThat(result.next()).isTrue();
                                assertThat(result.getString(1)).endsWith("_replica");
                            }
                        }
                    }
                    connection.rollback();
                }
                var springTransaction = new TransactionTemplate(new DataSourceTransactionManager(logical));
                springTransaction.executeWithoutResult(status -> {
                    Connection connection = DataSourceUtils.getConnection(logical);
                    try (var statement = connection.createStatement()) {
                        assertThat(statement.executeUpdate("UPDATE orders SET marker = 'spring' WHERE tenant_id = 1")).isEqualTo(1);
                        try (var result = statement.executeQuery("SELECT marker FROM orders WHERE tenant_id = 1")) {
                            assertThat(result.next()).isTrue();
                            assertThat(result.getString(1)).isEqualTo("shard_1_replica");
                        }
                        status.setRollbackOnly();
                    } catch (java.sql.SQLException failure) {
                        throw new IllegalStateException(failure);
                    } finally {
                        DataSourceUtils.releaseConnection(connection, logical);
                    }
                });
                try (Connection connection = physical.get("shard_1_primary").getConnection();
                     var result = connection.createStatement().executeQuery("SELECT marker FROM orders_t1 WHERE tenant_id = 1")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("written");
                }
                try (Connection connection = physical.get("shard_0_primary").getConnection();
                     var result = connection.createStatement().executeQuery("SELECT marker FROM orders_t0 WHERE tenant_id = 2")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("shard_0_primary");
                }
            } finally {
                ((AutoCloseable) logical).close();
            }
        }
    }

    private static PhysicalDataSourceProperties source(String name, String group, DataSourceRoleEnum role) {
        return new PhysicalDataSourceProperties(name, group, role, "org.postgresql.Driver",
                "jdbc:postgresql://localhost/" + name, "test", "test");
    }
}
