package top.egon.cola.component.common.mybatis.sharding;

import jakarta.validation.Validation;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.ConfigStyleEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.DataSourceRoleEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.ModeEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.PhysicalDataSourceProperties;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.TableProperties;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaPhysicalDataSourceFactory;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingDataSourceBootstrapper;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingTopologyValidator;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingYamlLoader;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaShardingStrategyFactory;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaStandardTenantIdShardingStrategy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in only: the supplied URLs must point to two dedicated physical PostgreSQL replication groups. */
@EnabledIfEnvironmentVariable(named = "EGON_MP_PG_SHARDING_IT", matches = "true")
class EgonColaPostgreSqlReadwriteIT {

    @Test
    void selectsReplicasAndWritesOnlyToTheSelectedPhysicalPrimary() throws Exception {
        String username = required("EGON_MP_PG_USER");
        String password = required("EGON_MP_PG_PASSWORD");
        String[] urls = {required("EGON_MP_PG_SHARD_0_PRIMARY_URL"), required("EGON_MP_PG_SHARD_0_REPLICA_URL"),
                required("EGON_MP_PG_SHARD_1_PRIMARY_URL"), required("EGON_MP_PG_SHARD_1_REPLICA_URL")};
        String table = "it_orders_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        List<Integer> created = new ArrayList<>();
        DataSource logical = null;
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            for (int shard = 0; shard < 2; shard++) {
                try (Connection connection = DriverManager.getConnection(urls[shard * 2], username, password);
                     var statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE public." + table + "_t" + shard + " (tenant_id BIGINT NOT NULL, marker TEXT NOT NULL)");
                    created.add(shard);
                    statement.execute("INSERT INTO public." + table + "_t" + shard + " VALUES (" + (shard == 0 ? 2 : 1) + ", 'initial')");
                }
                awaitReplica(urls[shard * 2 + 1], username, password, table + "_t" + shard, "initial");
            }
            var validation = new ValidationUtils(validators.getValidator());
            var properties = EgonColaShardingProperties.builder().mode(ModeEnum.SHARDING_READWRITE)
                    .configStyle(ConfigStyleEnum.STRATEGY)
                    .dataSources(List.of(source("shard_0_primary", "shard_0", DataSourceRoleEnum.PRIMARY, urls[0], username, password),
                            source("shard_0_replica", "shard_0", DataSourceRoleEnum.REPLICA, urls[1], username, password),
                            source("shard_1_primary", "shard_1", DataSourceRoleEnum.PRIMARY, urls[2], username, password),
                            source("shard_1_replica", "shard_1", DataSourceRoleEnum.REPLICA, urls[3], username, password)))
                    .tables(Map.of(table, new TableProperties("STANDARD_TENANT_ID", null, "tenant_id", null, null))).build();
            var strategyFactory = new EgonColaShardingStrategyFactory(validation,
                    Map.of("STANDARD_TENANT_ID", new EgonColaStandardTenantIdShardingStrategy()));
            var loader = new EgonColaShardingYamlLoader(strategyFactory, new DefaultResourceLoader(), new StandardEnvironment());
            var bootstrapper = new EgonColaShardingDataSourceBootstrapper(new EgonColaPhysicalDataSourceFactory(), loader,
                    new EgonColaShardingTopologyValidator(validation), YamlShardingSphereDataSourceFactory::createDataSource);
            logical = bootstrapper.createDataSource(properties);
            try (Connection connection = logical.getConnection()) {
                try (var read = connection.prepareStatement("SELECT marker FROM " + table + " WHERE tenant_id = ?")) {
                    read.setLong(1, 1L);
                    try (var result = read.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getString(1)).isEqualTo("initial");
                    }
                }
                try (var write = connection.prepareStatement("UPDATE " + table + " SET marker = ? WHERE tenant_id = ?")) {
                    write.setString(1, "updated");
                    write.setLong(2, 1L);
                    assertThat(write.executeUpdate()).isEqualTo(1);
                }
            }
            try (Connection primary = DriverManager.getConnection(urls[2], username, password);
                 var result = primary.createStatement().executeQuery("SELECT marker FROM public." + table + "_t1 WHERE tenant_id = 1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("updated");
            }
            awaitReplica(urls[3], username, password, table + "_t1", "updated");
        } finally {
            try {
                if (logical instanceof AutoCloseable closeable) {
                    closeable.close();
                }
            } finally {
                for (int shard : created) {
                    try (Connection connection = DriverManager.getConnection(urls[shard * 2], username, password);
                         var statement = connection.createStatement()) {
                        statement.execute("DROP TABLE IF EXISTS public." + table + "_t" + shard);
                    }
                }
            }
        }
    }

    private static void awaitReplica(String url, String user, String password, String table, String expected) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        SQLException last = null;
        while (System.nanoTime() < deadline) {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT pg_is_in_recovery(), marker FROM public." + table + " LIMIT 1")) {
                if (result.next() && result.getBoolean(1) && expected.equals(result.getString(2))) {
                    return;
                }
            } catch (SQLException failure) {
                last = failure;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Dedicated replica did not replay the test table in time", last);
    }

    private static PhysicalDataSourceProperties source(String name, String group, DataSourceRoleEnum role,
                                                       String url, String user, String password) {
        return new PhysicalDataSourceProperties(name, group, role, "org.postgresql.Driver", url, user, password);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing dedicated test setting: " + name);
        }
        return value;
    }
}
