package top.egon.cola.component.common.mybatis.sharding.strategy;

import top.egon.cola.component.common.mybatis.exception.EgonColaMybatisPlusConfigurationException;
import top.egon.cola.component.common.mybatis.routing.EgonColaPhysicalTargetBO;
import top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO;
import top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO.PartitionKeyBO;
import top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO.TableKindEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.DataSourceRoleEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.PhysicalDataSourceProperties;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.TableProperties;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

/**
 * Shared PRIMARY-group derivation for table-kind strategies.
 */
public final class EgonColaShardingStrategyNodes {

    public static final long SEED = 0x9e3779b97f4a7c15L;

    private EgonColaShardingStrategyNodes() {
    }

    static List<String> primaryGroups(List<PhysicalDataSourceProperties> dataSources) {
        if (dataSources == null || dataSources.isEmpty()) {
            return List.of();
        }
        return dataSources.stream()
                .filter(source -> source.role() == DataSourceRoleEnum.PRIMARY)
                .map(PhysicalDataSourceProperties::logicalName)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    static List<String> primaryGroups(List<PhysicalDataSourceProperties> dataSources, TableProperties table) {
        List<String> available = primaryGroups(dataSources);
        if (table.shardGroups() == null || table.shardGroups().isEmpty()) {
            return available;
        }
        var selected = new LinkedHashSet<>(table.shardGroups());
        if (selected.size() != table.shardGroups().size() || !available.containsAll(selected)) {
            throw new EgonColaMybatisPlusConfigurationException("SHARDING_GROUP_INVALID");
        }
        return List.copyOf(selected);
    }

    static void requirePrimary(List<PhysicalDataSourceProperties> dataSources, String logicalName) {
        boolean found = primaryGroups(dataSources).contains(logicalName);
        if (!found) {
            throw new EgonColaMybatisPlusConfigurationException("SINGLE_NODE_REQUIRED");
        }
    }

    public static EgonColaRoutingProfileBO simple(String logicalTable, TableKindEnum kind,
                                                  List<EgonColaPhysicalTargetBO> nodes) {
        return new EgonColaRoutingProfileBO(
                logicalTable,
                kind,
                "static-v1",
                1,
                1,
                Map.of(),
                null,
                null,
                SEED,
                Map.of(new PartitionKeyBO(0, 0), nodes),
                1,
                null);
    }

    static String joinNodes(List<EgonColaPhysicalTargetBO> nodes) {
        return nodes.stream()
                .map(node -> node.group() + "." + node.schema() + "." + node.table())
                .collect(Collectors.joining(","));
    }
}
