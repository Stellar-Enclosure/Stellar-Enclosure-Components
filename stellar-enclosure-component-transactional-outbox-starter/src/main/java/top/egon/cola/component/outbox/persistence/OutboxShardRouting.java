package top.egon.cola.component.outbox.persistence;

import top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO;
import top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO.PartitionKeyBO;
import top.egon.cola.component.common.mybatis.routing.EgonColaTransactionWriteTarget;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties;
import top.egon.cola.component.outbox.api.OutboxShardIdentity;
import top.egon.cola.component.outbox.common.exception.OutboxConfigurationException;
import top.egon.cola.component.outbox.migration.OutboxStorageTopology;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Resolves the technical slot from the transaction's already-pinned physical write group. */
public final class OutboxShardRouting {

    private final DataSource dataSource;
    private final Map<Integer, String> slots;

    public OutboxShardRouting(DataSource dataSource, EgonColaShardingProperties properties,
                              Map<String, EgonColaRoutingProfileBO> profiles) {
        this.dataSource = dataSource;
        EgonColaRoutingProfileBO profile = profiles.get("egon_cola_outbox_message");
        if (profile == null) {
            throw new OutboxConfigurationException("OUTBOX_SHARD_TOPOLOGY_REQUIRED");
        }
        Map<Integer, String> resolved = switch (profile.kind()) {
            case SINGLE -> Map.of(0, profile.actualNodes().get(new PartitionKeyBO(0, 0)).getFirst().group());
            case TECHNICAL_GROUP -> profile.tenantSlotMap();
            default -> throw new OutboxConfigurationException("OUTBOX_SHARD_TOPOLOGY_REQUIRED");
        };
        Set<String> primaries = OutboxStorageTopology.primaries(properties).keySet();
        if (!Set.copyOf(resolved.values()).equals(primaries)
                || resolved.size() > 1 && properties.getMode() != EgonColaShardingProperties.ModeEnum.SHARDING_READWRITE) {
            throw new OutboxConfigurationException("OUTBOX_SHARD_TOPOLOGY_MISMATCH");
        }
        this.slots = Map.copyOf(new TreeMap<>(resolved));
    }

    /** Compatibility for direct construction of a single-source store. */
    public OutboxShardRouting() {
        this.dataSource = null;
        this.slots = Map.of(0, "legacy");
    }

    public int currentSlot() {
        if (slots.size() == 1) {
            return 0;
        }
        String group = EgonColaTransactionWriteTarget.currentGroup(dataSource);
        if (group != null) {
            for (var entry : slots.entrySet()) {
                if (entry.getValue().equals(group)) {
                    return entry.getKey();
                }
            }
        }
        EgonColaTransactionWriteTarget.markRollbackOnly(dataSource);
        throw new OutboxConfigurationException("OUTBOX_BUSINESS_WRITE_TARGET_REQUIRED");
    }

    public void selectGroup(String group) {
        if (group == null || !slots.containsValue(group)) {
            EgonColaTransactionWriteTarget.markRollbackOnly(dataSource);
            throw new IllegalArgumentException("OUTBOX_SHARD_GROUP_UNKNOWN");
        }
        var target = EgonColaTransactionWriteTarget.capture(dataSource);
        if (target == null) {
            throw new OutboxConfigurationException("OUTBOX_TRANSACTION_REQUIRED");
        }
        target.pin(group, null);
    }

    public String generatedIdentity(String generated) {
        if (slots.size() == 1) {
            return generated;
        }
        try {
            String result = OutboxShardIdentity.forSlot(currentSlot(), generated);
            if (result.length() <= 64) {
                return result;
            }
            throw new IllegalArgumentException("OUTBOX_SHARD_MESSAGE_ID_TOO_LONG");
        } catch (IllegalArgumentException failure) {
            EgonColaTransactionWriteTarget.markRollbackOnly(dataSource);
            throw failure;
        }
    }

    public void validateIdentity(int slot, String messageId, String idempotencyKey) {
        if (slots.size() == 1) {
            return;
        }
        try {
            if (OutboxShardIdentity.slotOf(messageId, slots) == slot
                    && (idempotencyKey == null || OutboxShardIdentity.slotOf(idempotencyKey, slots) == slot)) {
                return;
            }
            throw new IllegalArgumentException("OUTBOX_SHARD_IDENTITY_MISMATCH");
        } catch (IllegalArgumentException failure) {
            EgonColaTransactionWriteTarget.markRollbackOnly(dataSource);
            throw failure;
        }
    }

    public List<Integer> slots() {
        return slots.keySet().stream().sorted().toList();
    }

    public void requireSlot(int slot) {
        if (!slots.containsKey(slot)) {
            throw new IllegalArgumentException("OUTBOX_SHARD_SLOT_UNKNOWN");
        }
    }
}
