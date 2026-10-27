package top.egon.cola.component.outbox.migration;

import org.junit.jupiter.api.Test;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.DataSourceRoleEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.ModeEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.PhysicalDataSourceProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxStorageTopologyTest {

    @Test
    void acceptsOnePrimaryWithReplicasAndRetainsLegacySinglePrimary() {
        var primary = source("writer", "shard_0", DataSourceRoleEnum.PRIMARY);
        var replica = source("reader", "shard_0", DataSourceRoleEnum.REPLICA);
        assertThat(OutboxStorageTopology.singlePrimary(properties(ModeEnum.SHARDING_READWRITE, primary, replica)))
                .isEqualTo(primary);
        assertThat(OutboxStorageTopology.singlePrimary(properties(ModeEnum.SHARDING, primary))).isEqualTo(primary);
    }

    @Test
    void acceptsMultipleWriteGroupsAndRejectsAGroupWithoutReplica() {
        var primary = source("writer", "shard_0", DataSourceRoleEnum.PRIMARY);
        var replica = source("reader", "shard_0", DataSourceRoleEnum.REPLICA);
        var other = source("writer_1", "shard_1", DataSourceRoleEnum.PRIMARY);
        var otherReplica = source("reader_1", "shard_1", DataSourceRoleEnum.REPLICA);
        assertThat(OutboxStorageTopology.primaries(
                properties(ModeEnum.SHARDING_READWRITE, primary, replica, other, otherReplica)))
                .containsOnlyKeys("shard_0", "shard_1");
        assertThatThrownBy(() -> OutboxStorageTopology.primaries(
                properties(ModeEnum.SHARDING_READWRITE, primary, replica, other)))
                .hasMessage("OUTBOX_SHARD_TOPOLOGY_INVALID");
        assertThatThrownBy(() -> OutboxStorageTopology.primaries(
                properties(ModeEnum.SHARDING_READWRITE, primary)))
                .hasMessage("OUTBOX_SHARD_TOPOLOGY_INVALID");
    }

    private static EgonColaShardingProperties properties(ModeEnum mode, PhysicalDataSourceProperties... sources) {
        return EgonColaShardingProperties.builder().mode(mode)
                .configStyle(EgonColaShardingProperties.ConfigStyleEnum.STRATEGY)
                .dataSources(List.of(sources)).build();
    }

    private static PhysicalDataSourceProperties source(String name, String group, DataSourceRoleEnum role) {
        return new PhysicalDataSourceProperties(name, group, role, "org.postgresql.Driver",
                "jdbc:postgresql://localhost/test", "test", "test");
    }
}
