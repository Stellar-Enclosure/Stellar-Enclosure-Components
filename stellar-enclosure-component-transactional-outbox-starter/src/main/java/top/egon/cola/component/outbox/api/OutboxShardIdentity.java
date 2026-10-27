package top.egon.cola.component.outbox.api;

import top.egon.cola.component.common.mybatis.sharding.algorithm.EgonColaGroupShardSlots;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Stable shard affinity for globally unique message IDs and idempotency keys. */
public final class OutboxShardIdentity {

    private static final Pattern PREFIX = Pattern.compile("s1_([0-9a-f]{8})_(.+)", Pattern.DOTALL);

    private OutboxShardIdentity() {
    }

    public static String forGroup(String group, String legacyGroup, String value) {
        if (group == null || group.isBlank() || legacyGroup == null || legacyGroup.isBlank()) {
            throw new IllegalArgumentException("OUTBOX_SHARD_IDENTITY_INVALID");
        }
        Map<Integer, String> slots = EgonColaGroupShardSlots.resolve(
                group.equals(legacyGroup) ? List.of(group) : List.of(legacyGroup, group), legacyGroup);
        int slot = slots.entrySet().stream().filter(entry -> entry.getValue().equals(group))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        return forSlot(slot, value);
    }

    public static String forSlot(int slot, String value) {
        if (slot < 0 || value == null || value.isBlank()) {
            throw new IllegalArgumentException("OUTBOX_SHARD_IDENTITY_INVALID");
        }
        return "s1_" + String.format(java.util.Locale.ROOT, "%08x", slot) + '_' + value;
    }

    public static int slotOf(String value, Map<Integer, String> slots) {
        if (value == null || value.isBlank() || slots == null || !slots.containsKey(0)) {
            throw new IllegalArgumentException("OUTBOX_SHARD_IDENTITY_INVALID");
        }
        var matcher = PREFIX.matcher(value);
        if (!matcher.matches()) {
            return 0;
        }
        long parsed = Long.parseLong(matcher.group(1), 16);
        if (parsed > Integer.MAX_VALUE || !slots.containsKey((int) parsed)) {
            throw new IllegalArgumentException("OUTBOX_SHARD_IDENTITY_UNKNOWN");
        }
        return (int) parsed;
    }
}
