package top.egon.cola.component.common.mybatis.sharding.strategy;

import org.springframework.stereotype.Component;
import top.egon.cola.component.common.mybatis.exception.EgonColaMybatisPlusConfigurationException;
import top.egon.cola.component.common.mybatis.routing.EgonColaPhysicalTargetBO;
import top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO;
import top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO.PartitionKeyBO;
import top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO.TableKindEnum;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.PhysicalDataSourceProperties;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.TableProperties;
import top.egon.cola.component.common.mybatis.sharding.algorithm.EgonColaGroupShardAlgorithm;
import top.egon.cola.component.common.mybatis.sharding.algorithm.EgonColaGroupShardSlots;

import java.util.List;
import java.util.Map;

/** Routes a technical table to one explicitly selected primary group. */
@Component("TECHNICAL_GROUP")
public final class EgonColaTechnicalGroupShardingStrategy implements EgonColaShardingStrategy {

    @Override
    public String type() { return "TECHNICAL_GROUP"; }

    @Override
    public void validateTable(TableProperties table) {
        EgonColaSingleTableShardingStrategy.requireType(table, type());
        if (table.dataSource() == null || table.dataSource().isBlank()
                || table.shardGroups() == null || table.shardGroups().isEmpty()
                || !table.shardGroups().contains(table.dataSource())
                || table.shardingColumn() != null && !"shard_slot".equals(table.shardingColumn())) {
            throw new EgonColaMybatisPlusConfigurationException("TECHNICAL_SHARD_GROUP_INVALID");
        }
    }

    @Override
    public String toRulesFragment(String logicalTable, TableProperties table, List<PhysicalDataSourceProperties> sources) {
        EgonColaRoutingProfileBO profile = toRoutingProfile(logicalTable, table, sources);
        String nodes = EgonColaShardingStrategyNodes.joinNodes(profile.actualNodes().get(new PartitionKeyBO(0, 0)));
        return "tables:\n  " + logicalTable + ":\n    actualDataNodes: " + nodes
                + "\n    databaseStrategy:\n      standard:\n        shardingColumn: shard_slot\n"
                + "        shardingAlgorithmName: " + logicalTable + "_database\n"
                + "shardingAlgorithms:\n  " + logicalTable + "_database:\n    type: CLASS_BASED\n    props:\n"
                + "      strategy: STANDARD\n      algorithmClassName: " + EgonColaGroupShardAlgorithm.class.getName()
                + "\n      slot-targets: " + EgonColaGroupShardSlots.encode(profile.tenantSlotMap()) + "\n";
    }

    @Override
    public EgonColaRoutingProfileBO toRoutingProfile(String logicalTable, TableProperties table,
                                                     List<PhysicalDataSourceProperties> sources) {
        validateTable(table);
        List<String> groups = EgonColaShardingStrategyNodes.primaryGroups(sources, table);
        Map<Integer, String> slots = EgonColaGroupShardSlots.resolve(groups, table.dataSource());
        List<EgonColaPhysicalTargetBO> nodes = groups.stream()
                .map(group -> new EgonColaPhysicalTargetBO(group, table.schema(), logicalTable)).toList();
        return new EgonColaRoutingProfileBO(logicalTable, TableKindEnum.TECHNICAL_GROUP, "group-slot-v1",
                1, 1, slots, null, null, EgonColaShardingStrategyNodes.SEED,
                Map.of(new PartitionKeyBO(0, 0), nodes), 1, null);
    }
}
