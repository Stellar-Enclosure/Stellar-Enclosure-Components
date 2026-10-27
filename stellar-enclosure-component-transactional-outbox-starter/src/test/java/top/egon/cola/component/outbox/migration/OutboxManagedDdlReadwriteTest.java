package top.egon.cola.component.outbox.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.common.mybatis.ddl.EgonColaPostgreDdlRunner;
import top.egon.cola.component.common.mybatis.ddl.EgonColaDdlResult;
import top.egon.cola.component.common.mybatis.ddl.EgonColaDdlTargetBO;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.ConfigStyleEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.DataSourceRoleEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.ModeEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.PhysicalDataSourceProperties;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.TableProperties;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingTopologyValidator;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingYamlLoader;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaShardingStrategyFactory;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaSingleTableShardingStrategy;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaTechnicalGroupShardingStrategy;
import top.egon.cola.component.outbox.api.OutboxShardIdentity;
import top.egon.cola.component.outbox.autoconfigure.OutboxMpStorageProperties;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;

class OutboxManagedDdlReadwriteTest {

    @Test
    void preparesEveryPrimaryAndRejectsRowsWithAnIncorrectGlobalIdentity() throws Exception {
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            ValidationUtils validation = new ValidationUtils(validators.getValidator());
            var sources = List.of(source("writer", "shard_0", DataSourceRoleEnum.PRIMARY),
                    source("reader", "shard_0", DataSourceRoleEnum.REPLICA),
                    source("writer_1", "shard_1", DataSourceRoleEnum.PRIMARY),
                    source("reader_1", "shard_1", DataSourceRoleEnum.REPLICA));
            var message = new TableProperties("TECHNICAL_GROUP", "shard_0", "shard_slot", null, null,
                    "egon_outbox", List.of("shard_0", "shard_1"));
            var history = new TableProperties("SINGLE", "shard_0", null, null, null, "egon_outbox");
            var properties = EgonColaShardingProperties.builder().mode(ModeEnum.SHARDING_READWRITE)
                    .configStyle(ConfigStyleEnum.STRATEGY).dataSources(sources)
                    .tables(Map.of("egon_cola_outbox_message", message, "ddl_history", history)).build();
            var strategy = new EgonColaShardingStrategyFactory(validation, Map.of(
                    "SINGLE", new EgonColaSingleTableShardingStrategy(),
                    "TECHNICAL_GROUP", new EgonColaTechnicalGroupShardingStrategy()));
            var loader = new EgonColaShardingYamlLoader(strategy, new DefaultResourceLoader(), new StandardEnvironment());
            var validator = new EgonColaShardingTopologyValidator(validation);
            byte[] yaml = loader.load(properties);
            var topology = validator.validate(properties, yaml);
            int slotB = topology.profiles().get("egon_cola_outbox_message").tenantSlotMap().entrySet().stream()
                    .filter(entry -> entry.getValue().equals("shard_1")).map(Map.Entry::getKey).findFirst().orElseThrow();
            var primaryA = primary(0, "legacy-id");
            var primaryB = primary(slotB, OutboxShardIdentity.forGroup("shard_1", "shard_0", "new-id"));
            DataSource replicaA = replica(), replicaB = replica();
            EgonColaPostgreDdlRunner runner = mock(EgonColaPostgreDdlRunner.class);
            when(runner.run(anyList())).thenAnswer(invocation -> {
                List<EgonColaDdlTargetBO> targets = invocation.getArgument(0);
                return targets.stream().sorted(java.util.Comparator.comparing(EgonColaDdlTargetBO::alias))
                        .flatMap(target -> target.manifest().scripts().stream().map(script -> new EgonColaDdlResult(
                                target.alias(), target.schema(), script.version(), script.sha256(),
                                EgonColaDdlResult.StatusEnum.APPLIED, Duration.ZERO))).toList();
            });
            var storage = new OutboxMpStorageProperties().setPreviousRouteFingerprints(List.of("a".repeat(64)));
            var initializer = new OutboxManagedDdlInitializer(runner, validator, validation, properties, storage, new ObjectMapper());
            Map<String, DataSource> physical = Map.of("writer", primaryA.dataSource(), "reader", replicaA,
                    "writer_1", primaryB.dataSource(), "reader_1", replicaB);

            initializer.initialize(physical, yaml);

            assertThat(initializer.physicalMetadataDataSources(topology.fingerprint()))
                    .containsEntry("shard_0", primaryA.dataSource()).containsEntry("shard_1", primaryB.dataSource());
            org.mockito.ArgumentCaptor<List<EgonColaDdlTargetBO>> captured = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(runner).run(captured.capture());
            assertThat(captured.getValue()).hasSize(2).allSatisfy(target ->
                    assertThat(target.compatibleRouteFingerprints()).containsExactly("a".repeat(64)));

            when(primaryA.rows().next()).thenReturn(true, false);
            when(primaryB.rows().next()).thenReturn(true, false);
            when(primaryB.rows().getString(2)).thenReturn("old-unscoped-id");
            assertThatThrownBy(() -> initializer.initialize(physical, yaml))
                    .hasMessageContaining("OUTBOX_SHARD_DATA_MISMATCH");
            assertThat(initializer.isReadyFor(primaryA.dataSource(), topology.fingerprint())).isFalse();
        }
    }

    @Test
    void resolvesOutboxSingleTablesToTheirLogicalGroupAndPhysicalPrimary() throws Exception {
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            ValidationUtils validation = new ValidationUtils(validators.getValidator());
            var primary = source("writer", DataSourceRoleEnum.PRIMARY);
            var replica = source("reader", DataSourceRoleEnum.REPLICA);
            var table = new TableProperties("SINGLE", "shard_0", null, null, null, "egon_outbox");
            var properties = EgonColaShardingProperties.builder().mode(ModeEnum.SHARDING_READWRITE)
                    .configStyle(ConfigStyleEnum.STRATEGY).dataSources(List.of(primary, replica))
                    .tables(Map.of("egon_cola_outbox_message", table, "ddl_history", table)).build();
            var strategy = new EgonColaShardingStrategyFactory(validation,
                    Map.of("SINGLE", new EgonColaSingleTableShardingStrategy()));
            var loader = new EgonColaShardingYamlLoader(strategy, new DefaultResourceLoader(), new StandardEnvironment());
            var validator = new EgonColaShardingTopologyValidator(validation);
            byte[] yaml = loader.load(properties);
            String fingerprint = validator.validate(properties, yaml).fingerprint();
            EgonColaPostgreDdlRunner runner = mock(EgonColaPostgreDdlRunner.class);
            var storage = new OutboxMpStorageProperties().setAutoInitializeDdl(false);
            var initializer = new OutboxManagedDdlInitializer(runner, validator, validation, properties,
                    storage, new ObjectMapper());
            DataSource writer = mock(DataSource.class);
            DataSource reader = mock(DataSource.class);
            Connection writerConnection = mock(Connection.class);
            Connection readerConnection = mock(Connection.class);
            Statement lsnStatement = mock(Statement.class);
            PreparedStatement replayStatement = mock(PreparedStatement.class);
            ResultSet lsn = mock(ResultSet.class);
            ResultSet replay = mock(ResultSet.class);
            when(writer.getConnection()).thenReturn(writerConnection);
            when(writerConnection.createStatement()).thenReturn(lsnStatement);
            when(lsnStatement.executeQuery("SELECT pg_current_wal_lsn()::text")).thenReturn(lsn);
            when(lsn.next()).thenReturn(true);
            when(lsn.getString(1)).thenReturn("0/100");
            when(reader.getConnection()).thenReturn(readerConnection);
            when(readerConnection.prepareStatement("SELECT pg_is_in_recovery(), pg_last_wal_replay_lsn() >= ?::pg_lsn"))
                    .thenReturn(replayStatement);
            when(replayStatement.executeQuery()).thenReturn(replay);
            when(replay.next()).thenReturn(true);
            when(replay.getBoolean(1)).thenReturn(true);
            when(replay.getBoolean(2)).thenReturn(true);

            initializer.initialize(Map.of("writer", writer, "reader", reader), yaml);

            assertThat(initializer.physicalMetadataDataSource(fingerprint)).isSameAs(writer);
            verify(replayStatement).setString(1, "0/100");
            verifyNoInteractions(runner);

            clearInvocations(writer, runner);
            storage.setAutoInitializeDdl(true);
            when(runner.run(anyList())).thenAnswer(invocation -> {
                EgonColaDdlTargetBO target = (EgonColaDdlTargetBO) ((List<?>) invocation.getArgument(0)).getFirst();
                return target.manifest().scripts().stream().map(script -> new EgonColaDdlResult(
                        target.alias(), target.schema(), script.version(), script.sha256(),
                        EgonColaDdlResult.StatusEnum.APPLIED, Duration.ZERO)).toList();
            });
            initializer.initialize(Map.of("writer", writer, "reader", reader), yaml);
            var order = inOrder(runner, writer);
            order.verify(runner).run(anyList());
            order.verify(writer).getConnection();
            assertThat(initializer.isReadyFor(writer, fingerprint)).isTrue();

            when(replay.getBoolean(1)).thenReturn(false);
            assertThatThrownBy(() -> initializer.initialize(Map.of("writer", writer, "reader", reader), yaml))
                    .hasMessageContaining("DDL_REPLICA_ROLE_MISMATCH");
            assertThat(initializer.isReadyFor(writer, fingerprint)).isFalse();
        }
    }

    private static PhysicalDataSourceProperties source(String name, DataSourceRoleEnum role) {
        return source(name, "shard_0", role);
    }

    private static PhysicalDataSourceProperties source(String name, String group, DataSourceRoleEnum role) {
        return new PhysicalDataSourceProperties(name, group, role, "org.postgresql.Driver",
                "jdbc:postgresql://localhost/outbox_test", "test", "test");
    }

    private static Primary primary(int slot, String messageId) throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet lsn = mock(ResultSet.class), rows = mock(ResultSet.class);
        PreparedStatement audit = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT pg_current_wal_lsn()::text")).thenReturn(lsn);
        when(lsn.next()).thenReturn(true);
        when(lsn.getString(1)).thenReturn("0/100");
        when(connection.prepareStatement("SELECT shard_slot, message_id, idempotency_key FROM egon_outbox.egon_cola_outbox_message"))
                .thenReturn(audit);
        when(audit.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getInt(1)).thenReturn(slot);
        when(rows.getString(2)).thenReturn(messageId);
        return new Primary(source, rows);
    }

    private static DataSource replica() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_is_in_recovery(), pg_last_wal_replay_lsn() >= ?::pg_lsn"))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getBoolean(1)).thenReturn(true);
        when(rows.getBoolean(2)).thenReturn(true);
        return source;
    }

    private record Primary(DataSource dataSource, ResultSet rows) {
    }
}
