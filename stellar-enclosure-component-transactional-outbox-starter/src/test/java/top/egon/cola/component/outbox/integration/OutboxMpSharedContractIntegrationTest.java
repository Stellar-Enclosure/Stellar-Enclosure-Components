package top.egon.cola.component.outbox.integration;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.outbox.autoconfigure.TransactionalOutboxProperties.DispatchMode;
import top.egon.cola.component.outbox.delivery.DeliveryResult;
import top.egon.cola.component.outbox.store.OutboxFinishStatusEnum;
import top.egon.cola.component.outbox.store.OutboxRecord;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxMpSharedContractIntegrationTest extends PostgresqlOutboxTestSupport {
    @Test
    void advertises_safe_dispatch_support_after_explicit_configuration() {
        assertThat(safeMpOutboxStore(DispatchMode.COORDINATED).supportsDispatchMode(DispatchMode.COORDINATED)).isTrue();
        assertThat(safeMpOutboxStore(DispatchMode.LOCAL).supportsDispatchMode(DispatchMode.LOCAL)).isTrue();
    }

    @Test
    void queue_takeover_fences_old_claims_but_preserves_a_valid_message_ack() {
        var safe = safeMpOutboxStore(DispatchMode.COORDINATED);
        safe.enqueue(newRecord("safe-inflight"));
        var first = safe.coordinate(coordinateCommand("instance-a", Set.of("test"))).getLeases().get("test");
        assertThat(first).isNotNull();
        assertThat(first.epoch()).isEqualTo(1);
        var competitor = safe.coordinate(coordinateCommand("instance-b", Set.of("test")));
        assertThat(competitor.getMembers()).hasSize(2);
        assertThat(competitor.getLeases()).containsEntry("test", first);
        OutboxRecord running = safe.claim(safeClaim("original-message-token", first)).getRecords().getFirst();
        assertThat(running.shardSlot()).isZero();
        assertThat(running.attemptCount()).isEqualTo(1);

        physicalJdbcTemplate.update("UPDATE egon_outbox.egon_cola_outbox_queue_lease "
                + "SET expires_at=clock_timestamp()-interval '1 second' WHERE shard_slot=0 AND channel='test'");
        var successor = safe.coordinate(coordinateCommand("instance-b", Set.of("test"))).getLeases().get("test");
        assertThat(successor.epoch()).isEqualTo(first.epoch()+1);
        assertThat(safe.claim(safeClaim("stale-owner-token", first)).isNotOwner()).isTrue();
        assertThat(safe.finishDelivery(safeFinish(running, DeliveryResult.success()))).isEqualTo(OutboxFinishStatusEnum.APPLIED);
        assertThat(safe.finishDelivery(safeFinish(running, DeliveryResult.success()))).isEqualTo(OutboxFinishStatusEnum.ALREADY_TERMINAL);
        assertThatThrownBy(() -> safe.coordinate(coordinateCommand("instance-a", Set.of()).setChannels(Set.of())
                .setRenew(List.of(first))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OUTBOX_COORDINATION_CAPABILITY_REQUIRED");
        var staleRenewal = safe.coordinate(coordinateCommand("instance-a", Set.of()).setRenew(List.of(first)));
        assertThat(staleRenewal.getLeases()).containsEntry("test", successor);
        var renewed = safe.coordinate(coordinateCommand("instance-b", Set.of()).setRenew(List.of(successor)));
        assertThat(renewed.getLeases().get("test").epoch()).isEqualTo(successor.epoch());
        safe.coordinate(coordinateCommand("instance-b", Set.of()).setRelease(List.of(successor)).setDraining(true));
        var reacquired = safe.coordinate(coordinateCommand("instance-a", Set.of("test"))).getLeases().get("test");
        assertThat(reacquired.epoch()).isEqualTo(successor.epoch()+1);
        assertThat(physicalJdbcTemplate.queryForObject("SELECT count(*) FROM egon_outbox.egon_cola_outbox_queue_lease",
                Integer.class)).isEqualTo(1);
        assertThat(physicalJdbcTemplate.queryForObject("SELECT count(*) FROM egon_outbox.egon_cola_outbox_queue_member "
                + "WHERE tenant_id<>0 OR deleted_at IS NOT NULL OR version<0", Integer.class)).isZero();
    }

    @Test
    void safe_finish_reads_persisted_budget_and_wrong_identity_has_no_effect() {
        var safe = safeMpOutboxStore(DispatchMode.LOCAL);
        safe.enqueue(newRecord("safe-budget", "safe-budget-key", "c".repeat(64), 3));
        OutboxRecord row = safe.claim(safeClaim("safe-budget-token", null)).getRecords().getFirst();
        var before = physicalJdbcTemplate.queryForMap("SELECT status,attempt_count,version,locked_by "
                + "FROM egon_outbox.egon_cola_outbox_message WHERE id=?", row.id());
        assertThat(safe.finishDelivery(safeFinish(row, DeliveryResult.success()).setMessageId("wrong-message")))
                .isEqualTo(OutboxFinishStatusEnum.LEASE_LOST);
        assertThat(physicalJdbcTemplate.queryForMap("SELECT status,attempt_count,version,locked_by "
                + "FROM egon_outbox.egon_cola_outbox_message WHERE id=?", row.id())).isEqualTo(before);
        physicalJdbcTemplate.update("UPDATE egon_outbox.egon_cola_outbox_message SET max_attempts=1 WHERE id=?", row.id());
        assertThat(safe.finishDelivery(safeFinish(row, DeliveryResult.retryableFailure("TEMP", "retry failed"))))
                .isEqualTo(OutboxFinishStatusEnum.APPLIED);
        assertThat(physicalJdbcTemplate.queryForMap("SELECT status,last_error_code,locked_by,locked_until "
                + "FROM egon_outbox.egon_cola_outbox_message WHERE id=?", row.id()))
                .containsEntry("status", "DEAD").containsEntry("last_error_code", "OUTBOX_RETRY_EXHAUSTED")
                .containsEntry("locked_by", null).containsEntry("locked_until", null);
    }

    @Test
    void satisfies_the_shared_postgresql_store_contract() {
        OutboxStoreContractAssertions.verify(outboxStore(), new TransactionTemplate(transactionManager),
                physicalJdbcTemplate, "egon_outbox.egon_cola_outbox_message");
    }

    @Test
    void skips_a_row_locked_by_another_transaction() throws Exception {
        OutboxStoreContractAssertions.verifyLockedRowsAreSkipped(outboxStore(), new TransactionTemplate(transactionManager),
                physicalJdbcTemplate, "egon_outbox.egon_cola_outbox_message");
    }
}
