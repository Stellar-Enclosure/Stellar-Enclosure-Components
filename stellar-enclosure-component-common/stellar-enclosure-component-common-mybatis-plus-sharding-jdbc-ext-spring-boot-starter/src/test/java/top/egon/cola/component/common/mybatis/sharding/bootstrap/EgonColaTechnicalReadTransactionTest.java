package top.egon.cola.component.common.mybatis.sharding.bootstrap;

import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.DataSourceRoleEnum;
import top.egon.cola.component.common.mybatis.routing.EgonColaTransactionWriteTarget;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Uses real ShardingSphere routing and H2 transactions; only PostgreSQL role probes are simulated. */
class EgonColaTechnicalReadTransactionTest {

    @Test
    void aFanoutJdbcWriteCannotCommitAcrossTwoPhysicalPrimaries() throws Exception {
        String run = UUID.randomUUID().toString().replace("-", "");
        JdbcDataSource primaryA = h2(run + "a", "primary-a");
        JdbcDataSource primaryB = h2(run + "b", "primary-b");
        AtomicBoolean active = new AtomicBoolean();
        var reference = new java.util.concurrent.atomic.AtomicReference<DataSource>();
        Map<String, DataSource> sources = Map.of(
                "a", guarded(primaryA, false, active, "group_a", reference),
                "ar", guarded(h2(run + "ar", "replica-a"), true, active, "group_a", reference),
                "b", guarded(primaryB, false, active, "group_b", reference),
                "br", guarded(h2(run + "br", "replica-b"), true, active, "group_b", reference));
        byte[] yaml = """
                databaseName: egon
                rules:
                  - !SHARDING
                    tables:
                      business_tx:
                        actualDataNodes: group_a.business_tx,group_b.business_tx
                  - !READWRITE_SPLITTING
                    dataSourceGroups:
                      group_a: { writeDataSourceName: a, readDataSourceNames: [ar], transactionalReadQueryStrategy: DYNAMIC, loadBalancerName: rr }
                      group_b: { writeDataSourceName: b, readDataSourceNames: [br], transactionalReadQueryStrategy: DYNAMIC, loadBalancerName: rr }
                    loadBalancers:
                      rr: { type: ROUND_ROBIN }
                transaction: { defaultType: LOCAL }
                """.getBytes(StandardCharsets.UTF_8);
        DataSource logical = new EgonColaRouteHintGuardedDataSource(
                YamlShardingSphereDataSourceFactory.createDataSource(sources, yaml), sql -> { }, () -> true);
        reference.set(logical);
        active.set(true);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(logical);
            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(logical));
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                try {
                    jdbc.update("UPDATE business_tx SET marker = 'fanout' WHERE id = 1");
                } catch (org.springframework.dao.DataAccessException expected) {
                    assertThat(expected).hasStackTraceContaining("LOCAL_WRITE_TARGET_MISMATCH");
                }
            })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
            assertThat(new JdbcTemplate(primaryA).queryForObject("SELECT marker FROM business_tx WHERE id = 1", String.class))
                    .isEqualTo("primary-a");
            assertThat(new JdbcTemplate(primaryB).queryForObject("SELECT marker FROM business_tx WHERE id = 1", String.class))
                    .isEqualTo("primary-b");
        } finally {
            ((AutoCloseable) logical).close();
        }
    }

    @Test
    void crossGroupJdbcWritesMarkTheSpringTransactionRollbackOnly() throws Exception {
        String run = UUID.randomUUID().toString().replace("-", "");
        JdbcDataSource primaryA = h2(run + "a", "primary-a");
        JdbcDataSource primaryB = h2(run + "b", "primary-b");
        JdbcDataSource replicaA = h2(run + "ar", "replica-a");
        JdbcDataSource replicaB = h2(run + "br", "replica-b");
        AtomicBoolean active = new AtomicBoolean();
        var reference = new java.util.concurrent.atomic.AtomicReference<DataSource>();
        Map<String, DataSource> sources = Map.of(
                "a", guarded(primaryA, false, active, "group_a", reference),
                "ar", guarded(replicaA, true, active, "group_a", reference),
                "b", guarded(primaryB, false, active, "group_b", reference),
                "br", guarded(replicaB, true, active, "group_b", reference));
        byte[] yaml = """
                databaseName: egon
                rules:
                  - !SINGLE
                    tables: [group_a.business_tx, group_b.outbox_tx]
                  - !READWRITE_SPLITTING
                    dataSourceGroups:
                      group_a: { writeDataSourceName: a, readDataSourceNames: [ar], transactionalReadQueryStrategy: DYNAMIC, loadBalancerName: rr }
                      group_b: { writeDataSourceName: b, readDataSourceNames: [br], transactionalReadQueryStrategy: DYNAMIC, loadBalancerName: rr }
                    loadBalancers:
                      rr: { type: ROUND_ROBIN }
                transaction: { defaultType: LOCAL }
                """.getBytes(StandardCharsets.UTF_8);
        DataSource logical = new EgonColaRouteHintGuardedDataSource(
                YamlShardingSphereDataSourceFactory.createDataSource(sources, yaml), sql -> { }, () -> true);
        reference.set(logical);
        active.set(true);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(logical);
            var manager = new DataSourceTransactionManager(logical);
            TransactionTemplate transaction = new TransactionTemplate(manager);
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE business_tx SET marker = 'changed-a' WHERE id = 1");
                assertThat(EgonColaTransactionWriteTarget.currentGroup(logical)).isEqualTo("group_a");
                try {
                    jdbc.update("UPDATE outbox_tx SET marker = 'changed-b' WHERE id = 1");
                } catch (org.springframework.dao.DataAccessException expected) {
                    assertThat(expected).hasStackTraceContaining("LOCAL_WRITE_TARGET_MISMATCH");
                }
            })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
            assertThat(new JdbcTemplate(primaryA).queryForObject("SELECT marker FROM business_tx WHERE id = 1", String.class))
                    .isEqualTo("primary-a");
            assertThat(new JdbcTemplate(primaryB).queryForObject("SELECT marker FROM outbox_tx WHERE id = 1", String.class))
                    .isEqualTo("primary-b");
            TransactionTemplate requiresNew = new TransactionTemplate(manager);
            requiresNew.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE business_tx SET marker = 'outer' WHERE id = 1");
                assertThat(EgonColaTransactionWriteTarget.currentGroup(logical)).isEqualTo("group_a");
                requiresNew.executeWithoutResult(inner -> {
                    jdbc.update("UPDATE outbox_tx SET marker = 'inner' WHERE id = 1");
                    assertThat(EgonColaTransactionWriteTarget.currentGroup(logical)).isEqualTo("group_b");
                });
                assertThat(EgonColaTransactionWriteTarget.currentGroup(logical)).isEqualTo("group_a");
                status.setRollbackOnly();
            });
            assertThat(new JdbcTemplate(primaryA).queryForObject("SELECT marker FROM business_tx WHERE id = 1", String.class))
                    .isEqualTo("primary-a");
            assertThat(new JdbcTemplate(primaryB).queryForObject("SELECT marker FROM outbox_tx WHERE id = 1", String.class))
                    .isEqualTo("inner");
        } finally {
            ((AutoCloseable) logical).close();
        }
    }

    private static DataSource guarded(JdbcDataSource source, boolean replica, AtomicBoolean active, String group,
                                      java.util.concurrent.atomic.AtomicReference<DataSource> reference) {
        return new EgonColaRoleGuardedDataSource(new RoleProbeDataSource(source, replica, active),
                replica ? DataSourceRoleEnum.REPLICA : DataSourceRoleEnum.PRIMARY, active, null, select -> false,
                source.getURL(), "sa", group, reference::get);
    }

    @Test
    void technicalLockSeesPrimaryTransactionWhileBusinessReadsStayOnReplica() throws Exception {
        String run = UUID.randomUUID().toString().replace("-", "");
        JdbcDataSource primary = h2(run + "primary", "primary");
        JdbcDataSource replica = h2(run + "replica", "replica");
        AtomicBoolean active = new AtomicBoolean();
        var policy = (java.util.function.Predicate<net.sf.jsqlparser.statement.select.Select>) select ->
                "SELECT marker FROM outbox_tx WHERE id = ? FOR UPDATE".equals(select.toString())
                        || "SELECT marker FROM outbox_tx WHERE id = ? FOR UPDATE SKIP LOCKED".equals(select.toString());
        DataSource guardedPrimary = new EgonColaRoleGuardedDataSource(
                new RoleProbeDataSource(primary, false, active), DataSourceRoleEnum.PRIMARY, active, null, policy,
                primary.getURL(), "sa");
        DataSource guardedReplica = new EgonColaRoleGuardedDataSource(
                new RoleProbeDataSource(replica, true, active), DataSourceRoleEnum.REPLICA, active, null, policy,
                replica.getURL(), "sa");
        byte[] yaml = """
                databaseName: egon
                rules:
                  - !SINGLE
                    tables:
                      - group0.business_tx
                      - group0.outbox_tx
                  - !READWRITE_SPLITTING
                    dataSourceGroups:
                      group0:
                        writeDataSourceName: primary
                        readDataSourceNames: [replica]
                        transactionalReadQueryStrategy: DYNAMIC
                        loadBalancerName: round_robin
                    loadBalancers:
                      round_robin:
                        type: ROUND_ROBIN
                transaction:
                  defaultType: LOCAL
                """.getBytes(StandardCharsets.UTF_8);
        DataSource logical = new EgonColaRouteHintGuardedDataSource(
                YamlShardingSphereDataSourceFactory.createDataSource(
                        Map.of("primary", guardedPrimary, "replica", guardedReplica), yaml), sql -> { }, () -> true);
        active.set(true);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(logical);
            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(logical));
            transaction.executeWithoutResult(status -> {
                assertThat(jdbc.queryForObject("SELECT marker FROM business_tx WHERE id = ?", String.class, 1L))
                        .isEqualTo("replica");
                assertThat(jdbc.update("UPDATE business_tx SET marker = 'business-write' WHERE id = ?", 1L)).isEqualTo(1);
                assertThat(jdbc.update("UPDATE outbox_tx SET marker = 'claimed' WHERE id = ?", 1L)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT marker FROM outbox_tx WHERE id = ? FOR UPDATE", String.class, 1L))
                        .isEqualTo("claimed");
                assertThat(jdbc.queryForObject("SELECT marker FROM outbox_tx WHERE id = ? FOR UPDATE SKIP LOCKED", String.class, 1L))
                        .isEqualTo("claimed");
                assertThat(jdbc.queryForObject("SELECT marker FROM business_tx WHERE id = ?", String.class, 1L))
                        .isEqualTo("replica");
                status.setRollbackOnly();
            });
            assertThat(new JdbcTemplate(primary).queryForObject("SELECT marker FROM business_tx WHERE id = 1", String.class))
                    .isEqualTo("primary");
            assertThat(new JdbcTemplate(primary).queryForObject("SELECT marker FROM outbox_tx WHERE id = 1", String.class))
                    .isEqualTo("primary");
            assertThatThrownBy(() -> jdbc.queryForObject("SELECT marker FROM business_tx WHERE id = ? FOR UPDATE", String.class, 1L))
                    .hasStackTraceContaining("READWRITE_SQL_ROLE_MISMATCH");

            transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE business_tx SET marker = 'committed' WHERE id = ?", 1L);
                jdbc.update("UPDATE outbox_tx SET marker = 'committed-outbox' WHERE id = ?", 1L);
                assertThat(jdbc.queryForObject("SELECT marker FROM outbox_tx WHERE id = ? FOR UPDATE", String.class, 1L))
                        .isEqualTo("committed-outbox");
            });
            assertThat(new JdbcTemplate(primary).queryForObject("SELECT marker FROM business_tx WHERE id = 1", String.class))
                    .isEqualTo("committed");
            assertThat(new JdbcTemplate(primary).queryForObject("SELECT marker FROM outbox_tx WHERE id = 1", String.class))
                    .isEqualTo("committed-outbox");
            assertThat(new JdbcTemplate(replica).queryForObject("SELECT marker FROM outbox_tx WHERE id = 1", String.class))
                    .isEqualTo("replica");
            assertThatThrownBy(() -> springUnmanagedConnection(transaction, jdbc, logical))
                    .isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
            assertThat(new JdbcTemplate(primary).queryForObject("SELECT marker FROM business_tx WHERE id = 1", String.class))
                    .isEqualTo("committed");
        } finally {
            ((AutoCloseable) logical).close();
        }
    }

    private static void springUnmanagedConnection(TransactionTemplate transaction, JdbcTemplate jdbc, DataSource logical) {
        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE business_tx SET marker = 'must-rollback' WHERE id = 1");
            assertThatThrownBy(logical::getConnection).hasMessageContaining("READWRITE_UNMANAGED_TRANSACTION_CONNECTION");
        });
    }

    private static JdbcDataSource h2(String name, String marker) throws SQLException {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            for (String table : new String[]{"business_tx", "outbox_tx"}) {
                statement.execute("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY, marker VARCHAR(100))");
                statement.execute("INSERT INTO " + table + " VALUES (1, '" + marker + "')");
            }
        }
        return source;
    }

    private static final class RoleProbeDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final boolean replica;
        private final AtomicBoolean active;

        private RoleProbeDataSource(DataSource delegate, boolean replica, AtomicBoolean active) {
            this.delegate = delegate;
            this.replica = replica;
            this.active = active;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        Object result = invoke(connection, method, args);
                        if (result instanceof DatabaseMetaData metadata && method.getName().equals("getMetaData")) {
                            return Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
                                    (ignored, metadataMethod, metadataArgs) -> active.get()
                                            && metadataMethod.getName().equals("getDatabaseProductName")
                                            ? "PostgreSQL" : invoke(metadata, metadataMethod, metadataArgs));
                        }
                        if (result instanceof Statement statement && method.getName().equals("createStatement")) {
                            return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                                    (ignored, statementMethod, statementArgs) -> {
                                        if (statementMethod.getName().equals("executeQuery") && statementArgs != null
                                                && statementArgs[0] instanceof String sql && sql.startsWith("SELECT pg_is_in_recovery()")) {
                                            ResultSet probe = mock(ResultSet.class);
                                            when(probe.next()).thenReturn(true);
                                            when(probe.getBoolean(1)).thenReturn(replica);
                                            when(probe.getString(2)).thenReturn("cluster");
                                            when(probe.getString(3)).thenReturn("test");
                                            return probe;
                                        }
                                        return invoke(statement, statementMethod, statementArgs);
                                    });
                        }
                        return result;
                    });
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }
}
