package top.egon.cola.component.outbox.api;

import org.junit.jupiter.api.Test;
import top.egon.cola.component.common.mybatis.sharding.algorithm.EgonColaGroupShardSlots;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxShardIdentityTest {

    @Test
    void oldIdsBelongToTheLegacyGroupAndNewGroupTokensRemainStableWhenGroupsAreAdded() {
        var before = EgonColaGroupShardSlots.resolve(List.of("group_a", "group_b"), "group_a");
        var after = EgonColaGroupShardSlots.resolve(List.of("group_c", "group_b", "group_a"), "group_a");
        String scoped = OutboxShardIdentity.forGroup("group_b", "group_a", "order-123");
        int slot = OutboxShardIdentity.slotOf(scoped, before);
        assertThat(before.get(slot)).isEqualTo("group_b");
        assertThat(OutboxShardIdentity.slotOf(scoped, after)).isEqualTo(slot);
        assertThat(OutboxShardIdentity.slotOf("old-message-id", after)).isZero();
        assertThat(OutboxShardIdentity.forGroup("group_a", "group_a", "key"))
                .isEqualTo("s1_00000000_key");
    }

    @Test
    void unknownReservedGroupTokensNeverFallBackToTheLegacyGroup() {
        var groups = EgonColaGroupShardSlots.resolve(List.of("group_a", "group_b"), "group_a");
        assertThatThrownBy(() -> OutboxShardIdentity.slotOf("s1_7fffffff_message", groups))
                .hasMessage("OUTBOX_SHARD_IDENTITY_UNKNOWN");
    }
}
