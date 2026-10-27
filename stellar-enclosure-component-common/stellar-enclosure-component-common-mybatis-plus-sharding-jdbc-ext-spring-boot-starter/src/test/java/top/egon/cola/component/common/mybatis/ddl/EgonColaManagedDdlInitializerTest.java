package top.egon.cola.component.common.mybatis.ddl;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties.ManagedDdlTarget;
import top.egon.cola.component.common.mybatis.ddl.EgonColaDdlTargetBO.RoleEnum;
import top.egon.cola.component.common.mybatis.routing.EgonColaPhysicalTargetBO;
import top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO.TableKindEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.DataSourceRoleEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.ModeEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.PhysicalDataSourceProperties;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingTopologyValidator.TopologyBO;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingTopologyValidator;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaShardingStrategyNodes;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class EgonColaManagedDdlInitializerTest {

    @Test
    void migratesPrimaryAndWaitsForEachReplicaToReplayBeforeReturning() throws Exception {
        var ddl = ddl();
        var runner = mock(EgonColaPostgreDdlRunner.class);
        var resources = resources();
        var primary = mock(DataSource.class);
        var replica = mock(DataSource.class);
        var mainConnection = mock(Connection.class);
        var replicaConnection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        var tables = mock(ResultSet.class);
        var lsnQuery = mock(Statement.class);
        var lsn = mock(ResultSet.class);
        var replayQuery = mock(PreparedStatement.class);
        var replay = mock(ResultSet.class);
        when(primary.getConnection()).thenReturn(mainConnection);
        when(mainConnection.getMetaData()).thenReturn(metadata);
        when(metadata.getTables(any(), anyString(), anyString(), any())).thenReturn(tables);
        when(tables.next()).thenReturn(true);
        when(tables.getString("TABLE_SCHEM")).thenReturn("public");
        when(tables.getString("TABLE_NAME")).thenReturn("orders");
        when(mainConnection.createStatement()).thenReturn(lsnQuery);
        when(lsnQuery.executeQuery(anyString())).thenReturn(lsn);
        when(lsn.next()).thenReturn(true);
        when(lsn.getString(1)).thenReturn("0/100");
        when(replica.getConnection()).thenReturn(replicaConnection);
        when(replicaConnection.prepareStatement(anyString())).thenReturn(replayQuery);
        when(replayQuery.executeQuery()).thenReturn(replay);
        when(replay.next()).thenReturn(true);
        when(replay.getBoolean(1)).thenReturn(true);
        when(replay.getBoolean(2)).thenReturn(true);
        var initializer = new EgonColaManagedDdlInitializer(ddl, runner, resources);

        initializer.initialize(sharding(), Map.of("p0", primary, "r0", replica), topology());

        @SuppressWarnings("unchecked")
        var targets = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(runner).run(targets.capture());
        assertThat((List<EgonColaDdlTargetBO>) targets.getValue()).singleElement()
                .satisfies(target -> assertThat(target.dataSource()).isSameAs(primary));
        verify(replayQuery).setString(1, "0/100");
        verify(replayQuery).executeQuery();
    }

    @Test
    void rejectsMissingTargetCoverageBeforeAnyMigration() {
        var ddl = ddl();
        ddl.setTargets(List.of(new ManagedDdlTarget("shard_0", "other", RoleEnum.SHARD,
                "classpath:ddl/test-manifest.json")));
        var runner = mock(EgonColaPostgreDdlRunner.class);
        var initializer = new EgonColaManagedDdlInitializer(ddl, runner, resources());
        assertThatThrownBy(() -> initializer.initialize(sharding(), Map.of("p0", mock(DataSource.class),
                "r0", mock(DataSource.class)), topology())).hasMessageContaining("DDL_PRIMARY_COVERAGE_INCOMPLETE");
        verifyNoInteractions(runner);
    }

    @Test
    void doesNotAcceptAMetadataPatternMatchForTheWrongPhysicalTable() throws Exception {
        var runner = mock(EgonColaPostgreDdlRunner.class);
        var primary = mock(DataSource.class);
        var replica = mock(DataSource.class);
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        var tables = mock(ResultSet.class);
        when(primary.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getTables(any(), anyString(), anyString(), any())).thenReturn(tables);
        when(tables.next()).thenReturn(true, false);
        when(tables.getString("TABLE_SCHEM")).thenReturn("public");
        when(tables.getString("TABLE_NAME")).thenReturn("orders_other");
        var initializer = new EgonColaManagedDdlInitializer(ddl(), runner, resources());

        assertThatThrownBy(() -> initializer.initialize(sharding(), Map.of("p0", primary, "r0", replica), topology()))
                .hasMessageContaining("DDL_PHYSICAL_TABLE_MISSING");
        verify(runner).run(any());
        verifyNoInteractions(replica);
    }

    @Test
    void derivesCompatibleFingerprintOnlyByRemovingDeclaredNewTables() throws Exception {
        var ddl = ddl();
        ddl.setPreviousRouteExclusions(List.of(List.of("new_orders")));
        var runner = mock(EgonColaPostgreDdlRunner.class);
        var primary = mock(DataSource.class);
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        when(primary.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getTables(any(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            ResultSet result = mock(ResultSet.class);
            when(result.next()).thenReturn(true);
            when(result.getString("TABLE_SCHEM")).thenReturn("public");
            when(result.getString("TABLE_NAME")).thenReturn(invocation.getArgument(2));
            return result;
        });
        var existing = EgonColaShardingStrategyNodes.simple("orders", TableKindEnum.SINGLE,
                List.of(new EgonColaPhysicalTargetBO("shard_0", "public", "orders")));
        var added = EgonColaShardingStrategyNodes.simple("new_orders", TableKindEnum.SINGLE,
                List.of(new EgonColaPhysicalTargetBO("shard_0", "public", "new_orders")));
        var schemas = Map.of("shard_0", "public");
        var profiles = Map.of("orders", existing, "new_orders", added);
        var topology = new TopologyBO(new byte[]{1}, profiles, schemas, Set.of(),
                EgonColaShardingTopologyValidator.fingerprint(profiles, schemas));
        var singlePrimary = EgonColaShardingProperties.builder().mode(ModeEnum.SHARDING)
                .configStyle(EgonColaShardingProperties.ConfigStyleEnum.STRATEGY)
                .dataSources(List.of(source("p0", DataSourceRoleEnum.PRIMARY))).build();

        new EgonColaManagedDdlInitializer(ddl, runner, resources()).initialize(singlePrimary, Map.of("p0", primary), topology);

        @SuppressWarnings("unchecked")
        var targets = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(runner).run(targets.capture());
        assertThat((List<EgonColaDdlTargetBO>) targets.getValue()).singleElement().satisfies(target ->
                assertThat(target.compatibleRouteFingerprints()).containsExactly(
                        EgonColaShardingTopologyValidator.fingerprint(Map.of("orders", existing), schemas)));
    }

    @Test
    void rejectsUnknownOrDuplicateExcludedTablesBeforeMigration() {
        var ddl = ddl();
        ddl.setPreviousRouteExclusions(List.of(List.of("missing")));
        var runner = mock(EgonColaPostgreDdlRunner.class);
        var initializer = new EgonColaManagedDdlInitializer(ddl, runner, resources());
        assertThatThrownBy(() -> initializer.initialize(sharding(), Map.of("p0", mock(DataSource.class),
                "r0", mock(DataSource.class)), topology())).hasMessageContaining("DDL_ROUTE_TRANSITION_INVALID");
        verifyNoInteractions(runner);
    }

    @Test
    void bindsHistoricalRouteExclusionsFromIndexedConfiguration() {
        var source = new MapConfigurationPropertySource(Map.of(
                "ddl.previous-route-exclusions[0][0]", "new_orders",
                "ddl.previous-route-exclusions[0][1]", "new_items",
                "ddl.previous-route-exclusions[1][0]", "new_items"));
        var ddl = new Binder(source).bind("ddl", Bindable.of(EgonColaMybatisPlusProperties.Ddl.class))
                .orElseThrow(() -> new IllegalStateException("DDL settings are missing"));
        assertThat(ddl.getPreviousRouteExclusions())
                .containsExactly(List.of("new_orders", "new_items"), List.of("new_items"));
    }

    @Test
    void boundsReplicaReplayQueriesAndFailsWhenReplayDoesNotCatchUp() throws Exception {
        var ddl = ddl();
        ddl.setTopologyReadyTimeout(Duration.ofMillis(150));
        var runner = mock(EgonColaPostgreDdlRunner.class);
        var primary = mock(DataSource.class);
        var replica = mock(DataSource.class);
        var mainConnection = mock(Connection.class);
        var replicaConnection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        var tables = mock(ResultSet.class);
        var lsnQuery = mock(Statement.class);
        var lsn = mock(ResultSet.class);
        var replayQuery = mock(PreparedStatement.class);
        var replay = mock(ResultSet.class);
        when(primary.getConnection()).thenReturn(mainConnection);
        when(mainConnection.getMetaData()).thenReturn(metadata);
        when(metadata.getTables(any(), anyString(), anyString(), any())).thenReturn(tables);
        when(tables.next()).thenReturn(true);
        when(tables.getString("TABLE_SCHEM")).thenReturn("public");
        when(tables.getString("TABLE_NAME")).thenReturn("orders");
        when(mainConnection.createStatement()).thenReturn(lsnQuery);
        when(lsnQuery.executeQuery(anyString())).thenReturn(lsn);
        when(lsn.next()).thenReturn(true);
        when(lsn.getString(1)).thenReturn("0/100");
        when(replica.getConnection()).thenReturn(replicaConnection);
        when(replicaConnection.prepareStatement(anyString())).thenReturn(replayQuery);
        when(replayQuery.executeQuery()).thenReturn(replay);
        when(replay.next()).thenReturn(true);
        when(replay.getBoolean(1)).thenReturn(true);
        when(replay.getBoolean(2)).thenReturn(false);

        var initializer = new EgonColaManagedDdlInitializer(ddl, runner, resources());
        assertThatThrownBy(() -> initializer.initialize(sharding(), Map.of("p0", primary, "r0", replica), topology()))
                .hasMessageContaining("DDL_REPLICA_REPLAY_TIMEOUT");
        verify(replayQuery, atLeastOnce()).setQueryTimeout(anyInt());
    }

    private static EgonColaMybatisPlusProperties.Ddl ddl() {
        var ddl = new EgonColaMybatisPlusProperties.Ddl();
        ddl.setEnabled(true);
        ddl.setAutoMigrate(true);
        ddl.setTopologyReadyTimeout(Duration.ofSeconds(1));
        ddl.setTargets(List.of(new ManagedDdlTarget("shard_0", "public", RoleEnum.SHARD,
                "classpath:ddl/test-manifest.json")));
        return ddl;
    }

    private static ResourceLoader resources() {
        ResourceLoader loader = mock(ResourceLoader.class);
        String json = "{\"family\":\"light\",\"scripts\":[{\"version\":\"20261027_001\","
                + "\"path\":\"ddl/initial-test.sql\",\"sha256\":\"" + "a".repeat(64) + "\"}]}";
        when(loader.getResource(anyString())).thenReturn(new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)));
        return loader;
    }

    private static EgonColaShardingProperties sharding() {
        return EgonColaShardingProperties.builder().mode(ModeEnum.SHARDING_READWRITE)
                .configStyle(EgonColaShardingProperties.ConfigStyleEnum.STRATEGY)
                .dataSources(List.of(source("p0", DataSourceRoleEnum.PRIMARY), source("r0", DataSourceRoleEnum.REPLICA))).build();
    }

    private static PhysicalDataSourceProperties source(String name, DataSourceRoleEnum role) {
        return new PhysicalDataSourceProperties(name, "shard_0", role, "org.postgresql.Driver",
                "jdbc:postgresql://localhost/test", "test", "test");
    }

    private static TopologyBO topology() {
        var node = new EgonColaPhysicalTargetBO("shard_0", "public", "orders");
        var profile = EgonColaShardingStrategyNodes.simple("orders", TableKindEnum.SINGLE, List.of(node));
        return new TopologyBO(new byte[]{1}, Map.of("orders", profile), Map.of("shard_0", "public"), Set.of(), "a".repeat(64));
    }
}
