package top.egon.cola.component.common.mybatis.sharding.bootstrap;

import org.apache.shardingsphere.infra.hint.HintManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class EgonColaRouteHintGuardedDataSourceTest {

    @AfterEach
    void clearHint() {
        HintManager.clear();
    }

    @Test
    void rejectsThreadAndSqlHintsBeforeShardingSphereCanBypassTheTableRule() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var prepared = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(prepared);
        Connection guarded = new EgonColaRouteHintGuardedDataSource(source).getConnection();
        PreparedStatement statement = guarded.prepareStatement("SELECT * FROM orders WHERE tenant_id = ?");
        try (HintManager hint = HintManager.getInstance()) {
            hint.setDataSourceName("other_replica");
            assertThatThrownBy(statement::executeQuery).isInstanceOf(SQLException.class)
                    .hasMessageContaining("READWRITE_HINT_FORBIDDEN");
        }
        assertThatThrownBy(() -> guarded.prepareStatement(
                "/* ShardingSphere hint: SHARDING_DATABASE_VALUE=2 */ SELECT * FROM orders"))
                .hasMessageContaining("READWRITE_HINT_FORBIDDEN");
        verify(prepared, never()).executeQuery();
    }

    @Test
    void resultSetsAndMetadataReturnTheGuardedLogicalConnection() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var prepared = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        var metadata = mock(java.sql.DatabaseMetaData.class);
        var metadataRows = mock(ResultSet.class);
        var metadataStatement = mock(Statement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(prepared);
        when(prepared.executeQuery()).thenReturn(rows);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getTables(null, null, "orders", null)).thenReturn(metadataRows);
        when(metadataRows.getStatement()).thenReturn(metadataStatement);
        Connection guarded = new EgonColaRouteHintGuardedDataSource(source).getConnection();
        PreparedStatement statement = guarded.prepareStatement("SELECT * FROM orders");

        assertThat(guarded.equals(guarded)).isTrue();
        assertThat(statement.equals(statement)).isTrue();
        assertThat(statement.executeQuery().getStatement()).isSameAs(statement);
        assertThat(statement.executeQuery().getStatement().getConnection()).isSameAs(guarded);
        assertThat(guarded.getMetaData().getTables(null, null, "orders", null).getStatement().getConnection()).isSameAs(guarded);
        assertThatThrownBy(() -> guarded.unwrap(connection.getClass())).hasMessageContaining("READWRITE_UNWRAP_UNSUPPORTED");
    }
}
