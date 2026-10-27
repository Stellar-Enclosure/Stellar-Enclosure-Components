package top.egon.cola.component.common.mybatis.sharding.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.egon.cola.component.common.mybatis.routing.EgonColaTransactionWriteTarget;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.DataSourceRoleEnum;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.Map;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.PhysicalDataSourceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class EgonColaRoleGuardedDataSourceTest {

    @Test
    void rejectsQueriesOnPrimaryAndWritesOnReplicaEvenWhenRoutingIsBypassed() throws Exception {
        Fixture primary = fixture(false);
        Connection writer = new EgonColaRoleGuardedDataSource(primary.source(), DataSourceRoleEnum.PRIMARY,
                new AtomicBoolean(true)).getConnection();
        assertThatThrownBy(() -> writer.prepareStatement("SELECT id FROM orders WHERE id = ?"))
                .isInstanceOf(SQLException.class).hasMessageContaining("READWRITE_SQL_ROLE_MISMATCH");
        assertThatThrownBy(() -> writer.prepareStatement("SELECT id FROM orders WHERE id = ? FOR UPDATE"))
                .isInstanceOf(SQLException.class).hasMessageContaining("READWRITE_SQL_ROLE_MISMATCH");
        assertThatThrownBy(() -> writer.prepareStatement("UPDATE orders SET name = 'x'; SELECT id FROM orders"))
                .isInstanceOf(SQLException.class).hasMessageContaining("READWRITE_SQL_UNSUPPORTED");
        writer.prepareStatement("UPDATE orders SET name = ? WHERE id = ?").executeUpdate();
        verify(primary.prepared()).executeUpdate();

        Fixture replica = fixture(true);
        Connection reader = new EgonColaRoleGuardedDataSource(replica.source(), DataSourceRoleEnum.REPLICA,
                new AtomicBoolean(true)).getConnection();
        reader.prepareStatement("SELECT id FROM orders WHERE id = ?").executeQuery();
        assertThatThrownBy(() -> reader.createStatement().execute("DELETE FROM orders WHERE id = 1"))
                .isInstanceOf(SQLException.class).hasMessageContaining("READWRITE_SQL_ROLE_MISMATCH");
        verify(replica.connection()).setReadOnly(true);
    }

    @Test
    void technicalReadPolicyDoesNotPermitOtherPrimaryQueriesOrWritesOnReplica() throws Exception {
        Fixture primary = fixture(false);
        Connection writer = new EgonColaRoleGuardedDataSource(primary.source(), DataSourceRoleEnum.PRIMARY,
                new AtomicBoolean(true), null,
                select -> "SELECT id FROM outbox WHERE id = ? FOR UPDATE".equals(select.toString())).getConnection();
        writer.prepareStatement("SELECT id FROM outbox WHERE id = ? FOR UPDATE").executeQuery();
        assertThatThrownBy(() -> writer.prepareStatement("SELECT id FROM orders WHERE id = ? FOR UPDATE"))
                .hasMessageContaining("READWRITE_SQL_ROLE_MISMATCH");
        assertThatThrownBy(() -> writer.prepareStatement("SELECT id FROM outbox WHERE id = ?"))
                .hasMessageContaining("READWRITE_SQL_ROLE_MISMATCH");
    }

    @Test
    void physicalWritesPinOneGroupAndRejectAnotherEvenFromAnExecutorThread() throws Exception {
        Fixture first = fixture(false);
        Fixture second = fixture(false);
        DataSource logical = mock(DataSource.class);
        ConnectionHolder holder = new ConnectionHolder(mock(Connection.class));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.bindResource(logical, holder);
        try {
            var primaryA = new EgonColaRoleGuardedDataSource(first.source(), DataSourceRoleEnum.PRIMARY,
                    new AtomicBoolean(true), null, select -> false, "jdbc:postgresql://localhost/a", "test",
                    "group_a", () -> logical);
            var primaryB = new EgonColaRoleGuardedDataSource(second.source(), DataSourceRoleEnum.PRIMARY,
                    new AtomicBoolean(true), null, select -> false, "jdbc:postgresql://localhost/b", "test",
                    "group_b", () -> logical);
            Connection connectionA = primaryA.getConnection();
            Connection connectionB = primaryB.getConnection();
            connectionA.prepareStatement("UPDATE orders SET state = ? WHERE id = ?");
            assertThat(EgonColaTransactionWriteTarget.currentGroup(logical)).isEqualTo("group_a");
            try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                var result = executor.submit(() -> connectionB.prepareStatement("UPDATE orders SET state = ? WHERE id = ?"));
                assertThatThrownBy(() -> result.get(5, java.util.concurrent.TimeUnit.SECONDS))
                        .hasStackTraceContaining("LOCAL_WRITE_TARGET_MISMATCH");
            }
            assertThat(holder.isRollbackOnly()).isTrue();
            verify(second.connection(), never()).prepareStatement(anyString());
        } finally {
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            TransactionSynchronizationManager.unbindResourceIfPossible(logical);
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @Test
    void checksTheActualPostgresRoleWhenBorrowingEachConnection() throws Exception {
        Fixture promoted = fixture(false);
        var replica = new EgonColaRoleGuardedDataSource(promoted.source(), DataSourceRoleEnum.REPLICA,
                new AtomicBoolean(true));
        assertThatThrownBy(replica::getConnection).isInstanceOf(SQLException.class)
                .hasMessageContaining("READWRITE_PHYSICAL_ROLE_MISMATCH");
        verify(promoted.connection()).close();
    }

    @Test
    void rejectsAReplicaPromotedAfterTheConnectionWasBorrowed() throws Exception {
        Fixture replica = fixture(true);
        when(replica.identity().getBoolean(1)).thenReturn(true, false);
        Connection reader = new EgonColaRoleGuardedDataSource(replica.source(), DataSourceRoleEnum.REPLICA,
                new AtomicBoolean(true)).getConnection();
        PreparedStatement query = reader.prepareStatement("SELECT id FROM orders");
        assertThatThrownBy(query::executeQuery).isInstanceOf(SQLException.class)
                .hasMessageContaining("READWRITE_PHYSICAL_ROLE_MISMATCH");
        verify(replica.prepared(), never()).executeQuery();
    }

    @Test
    void rejectsAReplicaFromAnotherPostgresClusterEvenWhenItIsInRecovery() throws Exception {
        Fixture primary = fixture(false);
        Fixture replica = fixture(true);
        when(replica.identity().getString(2)).thenReturn("other-cluster");
        var sources = List.of(source("primary", DataSourceRoleEnum.PRIMARY), source("replica", DataSourceRoleEnum.REPLICA));
        assertThatThrownBy(() -> EgonColaRoleGuardedDataSource.verifyTopology(sources,
                Map.of("primary", primary.source(), "replica", replica.source())))
                .isInstanceOf(SQLException.class).hasMessageContaining("READWRITE_CLUSTER_IDENTITY_MISMATCH");
    }

    @Test
    void guardsPreparedExecutionAndDoesNotExposeTheUnguardedConnection() throws Exception {
        Fixture primary = fixture(false);
        AtomicBoolean active = new AtomicBoolean();
        Connection connection = new EgonColaRoleGuardedDataSource(primary.source(), DataSourceRoleEnum.PRIMARY, active)
                .getConnection();
        PreparedStatement preparedDuringBootstrap = connection.prepareStatement("SELECT id FROM orders");
        active.set(true);
        assertThatThrownBy(preparedDuringBootstrap::executeQuery).hasMessageContaining("READWRITE_SQL_ROLE_MISMATCH");
        assertThatThrownBy(() -> connection.unwrap(primary.connection().getClass())).hasMessageContaining("READWRITE_UNWRAP_UNSUPPORTED");
    }

    @Test
    void resultSetAndMetadataCannotExposeAnUnguardedPhysicalConnection() throws Exception {
        Fixture replica = fixture(true);
        ResultSet raw = mock(ResultSet.class);
        when(replica.prepared().executeQuery()).thenReturn(raw);
        Connection connection = new EgonColaRoleGuardedDataSource(replica.source(), DataSourceRoleEnum.REPLICA,
                new AtomicBoolean(true)).getConnection();
        PreparedStatement statement = connection.prepareStatement("SELECT id FROM orders");
        ResultSet result = statement.executeQuery();

        assertThat(connection.equals(connection)).isTrue();
        assertThat(statement.equals(statement)).isTrue();
        assertThat(result.equals(result)).isTrue();
        assertThat(result.getStatement()).isSameAs(statement);
        assertThat(result.getStatement().getConnection()).isSameAs(connection);
        assertThatThrownBy(() -> result.unwrap(raw.getClass())).hasMessageContaining("READWRITE_UNWRAP_UNSUPPORTED");

        ResultSet metadataRows = mock(ResultSet.class);
        Statement rawMetadataStatement = mock(Statement.class);
        when(metadataRows.getStatement()).thenReturn(rawMetadataStatement);
        when(replica.connection().getMetaData().getTables(null, null, "orders", null)).thenReturn(metadataRows);
        ResultSet tables = connection.getMetaData().getTables(null, null, "orders", null);
        assertThat(tables.getStatement().getConnection()).isSameAs(connection);
        assertThatThrownBy(() -> tables.getStatement().execute("DELETE FROM orders"))
                .hasMessageContaining("READWRITE_SQL_ROLE_MISMATCH");
    }

    @Test
    void acceptsCommonPostgresqlJsonArrayAndReturningStatementsOnTheCorrectRole() throws Exception {
        Fixture replica = fixture(true);
        Connection reader = new EgonColaRoleGuardedDataSource(replica.source(), DataSourceRoleEnum.REPLICA,
                new AtomicBoolean(true)).getConnection();
        reader.prepareStatement("SELECT id FROM orders WHERE attributes @> ?::jsonb AND tags && ARRAY[?]::text[]").executeQuery();

        Fixture primary = fixture(false);
        Connection writer = new EgonColaRoleGuardedDataSource(primary.source(), DataSourceRoleEnum.PRIMARY,
                new AtomicBoolean(true)).getConnection();
        writer.prepareStatement("INSERT INTO orders (id, attributes) VALUES (?, ?::jsonb) "
                + "ON CONFLICT (id) DO UPDATE SET attributes = EXCLUDED.attributes RETURNING id").execute();
        writer.prepareStatement("UPDATE orders SET tags = ?::text[] WHERE id = ? RETURNING id").execute();
        verify(primary.prepared(), times(2)).execute();
    }

    private static Fixture fixture(boolean recovery) throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        Statement statement = mock(Statement.class);
        PreparedStatement prepared = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT pg_is_in_recovery(), (pg_control_system()).system_identifier::text, current_database()"))
                .thenReturn(result);
        when(statement.executeQuery("SELECT pg_is_in_recovery()")).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(recovery);
        when(result.getString(2)).thenReturn("cluster-1");
        when(result.getString(3)).thenReturn("app");
        when(connection.prepareStatement(anyString())).thenReturn(prepared);
        return new Fixture(source, connection, prepared, result);
    }

    private static PhysicalDataSourceProperties source(String name, DataSourceRoleEnum role) {
        return new PhysicalDataSourceProperties(name, "group_0", role, "org.postgresql.Driver",
                "jdbc:postgresql://localhost/test", "test", "test");
    }

    private record Fixture(DataSource source, Connection connection, PreparedStatement prepared, ResultSet identity) {
    }
}
