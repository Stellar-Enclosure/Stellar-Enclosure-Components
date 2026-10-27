package top.egon.cola.component.outbox.migration;

import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.DataSourceRoleEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.ModeEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.PhysicalDataSourceProperties;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Validates the primary/replica groups available to the outbox. */
public final class OutboxStorageTopology {

    private OutboxStorageTopology() {
    }

    public static PhysicalDataSourceProperties singlePrimary(EgonColaShardingProperties properties) {
        Map<String, PhysicalDataSourceProperties> primaries = primaries(properties);
        if (primaries.size() != 1) {
            throw new IllegalStateException("OUTBOX_SINGLE_PRIMARY_REQUIRED");
        }
        return primaries.values().iterator().next();
    }

    public static Map<String, PhysicalDataSourceProperties> primaries(EgonColaShardingProperties properties) {
        List<PhysicalDataSourceProperties> sources = properties.getDataSources();
        if (sources == null || sources.isEmpty()) {
            throw new IllegalStateException("OUTBOX_SINGLE_PRIMARY_REQUIRED");
        }
        if (properties.getMode() == ModeEnum.SHARDING) {
            if (sources.size() != 1 || sources.getFirst().role() != DataSourceRoleEnum.PRIMARY) {
                throw new IllegalStateException("OUTBOX_SINGLE_PRIMARY_REQUIRED");
            }
            return Map.of(sources.getFirst().name(), sources.getFirst());
        }
        if (properties.getMode() != ModeEnum.SHARDING_READWRITE) {
            throw new IllegalStateException("OUTBOX_SINGLE_PRIMARY_REQUIRED");
        }
        Map<String, PhysicalDataSourceProperties> primaries = new TreeMap<>();
        Map<String, Long> replicas = new TreeMap<>();
        for (PhysicalDataSourceProperties source : sources) {
            if (source.role() == DataSourceRoleEnum.PRIMARY) {
                if (primaries.putIfAbsent(source.logicalName(), source) != null) {
                    throw new IllegalStateException("OUTBOX_SHARD_TOPOLOGY_INVALID");
                }
            } else if (source.role() == DataSourceRoleEnum.REPLICA) {
                replicas.merge(source.logicalName(), 1L, Long::sum);
            } else {
                throw new IllegalStateException("OUTBOX_SHARD_TOPOLOGY_INVALID");
            }
        }
        if (primaries.isEmpty() || !primaries.keySet().equals(replicas.keySet())) {
            throw new IllegalStateException("OUTBOX_SHARD_TOPOLOGY_INVALID");
        }
        return Map.copyOf(primaries);
    }
}
