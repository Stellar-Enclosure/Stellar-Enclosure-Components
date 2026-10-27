package top.egon.cola.component.common.mybatis.sharding.algorithm;

import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

import java.util.Collection;
import java.util.Map;
import java.util.Properties;

/** Exact technical shard slot routing; missing or unknown slots never fan out on writes. */
public final class EgonColaGroupShardAlgorithm implements StandardShardingAlgorithm<Comparable<?>> {

    private volatile Map<Integer, String> slots = Map.of();

    @Override
    public void init(Properties properties) {
        slots = EgonColaGroupShardSlots.decode(properties == null ? null : properties.getProperty("slot-targets"));
    }

    @Override
    public String doSharding(Collection<String> available, PreciseShardingValue<Comparable<?>> value) {
        if (value == null || value.getValue() == null || available == null
                || !value.getValue().toString().matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("TECHNICAL_SHARD_SLOT_REQUIRED");
        }
        int slot = Integer.parseInt(value.getValue().toString());
        String group = slots.get(slot);
        if (group == null || !available.contains(group)) {
            throw new IllegalArgumentException("TECHNICAL_SHARD_SLOT_UNKNOWN");
        }
        return group;
    }

    @Override
    public Collection<String> doSharding(Collection<String> available, RangeShardingValue<Comparable<?>> value) {
        throw new UnsupportedOperationException("TECHNICAL_SHARD_RANGE_FORBIDDEN");
    }
}
