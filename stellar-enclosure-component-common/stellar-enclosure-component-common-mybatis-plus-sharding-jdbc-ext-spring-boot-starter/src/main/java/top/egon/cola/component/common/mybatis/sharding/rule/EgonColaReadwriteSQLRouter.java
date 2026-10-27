package top.egon.cola.component.common.mybatis.sharding.rule;

import org.apache.shardingsphere.infra.config.props.ConfigurationProperties;
import org.apache.shardingsphere.infra.metadata.database.ShardingSphereDatabase;
import org.apache.shardingsphere.infra.route.context.RouteContext;
import org.apache.shardingsphere.infra.route.context.RouteMapper;
import org.apache.shardingsphere.infra.route.context.RouteUnit;
import org.apache.shardingsphere.infra.route.lifecycle.DecorateSQLRouter;
import org.apache.shardingsphere.infra.session.query.QueryContext;
import org.apache.shardingsphere.readwritesplitting.route.ReadwriteSplittingSQLRouter;
import org.apache.shardingsphere.readwritesplitting.rule.ReadwriteSplittingRule;
import org.apache.shardingsphere.broadcast.rule.BroadcastRule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Normalizes broadcast physical routes to groups before the normal read/write router runs. */
public final class EgonColaReadwriteSQLRouter implements DecorateSQLRouter<ReadwriteSplittingRule> {

    private final ReadwriteSplittingSQLRouter delegate = new ReadwriteSplittingSQLRouter();

    @Override
    public void decorateRouteContext(RouteContext context, QueryContext query, ShardingSphereDatabase database,
                                     ReadwriteSplittingRule rule, Collection<String> tableNames,
                                     ConfigurationProperties properties) {
        boolean broadcast = database.getRuleMetaData().findSingleRule(BroadcastRule.class)
                .map(value -> !value.getBroadcastTableNames(tableNames).isEmpty()).orElse(false);
        if (!broadcast) {
            delegate.decorateRouteContext(context, query, database, rule, tableNames, properties);
            return;
        }
        List<RouteUnit> removed = new ArrayList<>();
        List<RouteUnit> added = new ArrayList<>();
        for (RouteUnit unit : context.getRouteUnits()) {
            String actual = unit.getDataSourceMapper().getActualName();
            if (rule.findDataSourceGroupRule(actual).isPresent()) {
                continue;
            }
            String matchedGroup = null;
            for (var entry : rule.getDataSourceRuleGroups().entrySet()) {
                var group = entry.getValue();
                if (group.getWriteDataSource().equalsIgnoreCase(actual)
                        || group.getReadwriteSplittingGroup().getReadDataSources().stream().anyMatch(actual::equalsIgnoreCase)) {
                    if (matchedGroup != null && !matchedGroup.equalsIgnoreCase(entry.getKey())) {
                        matchedGroup = null;
                        break;
                    }
                    matchedGroup = entry.getKey();
                }
            }
            if (matchedGroup != null) {
                removed.add(unit);
                added.add(new RouteUnit(new RouteMapper(matchedGroup, matchedGroup), unit.getTableMappers()));
            }
        }
        context.getRouteUnits().removeAll(removed);
        context.getRouteUnits().addAll(added);
        delegate.decorateRouteContext(context, query, database, rule, tableNames, properties);
    }

    @Override
    public Type getType() {
        return Type.DATA_SOURCE;
    }

    @Override
    public int getOrder() {
        // The ordered SPI loader selects the last router for the same rule type.
        return 46;
    }

    @Override
    public Class<ReadwriteSplittingRule> getTypeClass() {
        return ReadwriteSplittingRule.class;
    }
}
