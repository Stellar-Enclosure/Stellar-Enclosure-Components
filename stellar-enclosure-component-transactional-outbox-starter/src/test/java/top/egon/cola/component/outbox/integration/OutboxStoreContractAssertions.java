package top.egon.cola.component.outbox.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.outbox.api.OutboxReceipt;
import top.egon.cola.component.outbox.common.exception.OutboxIdempotencyConflictException;
import top.egon.cola.component.outbox.store.NewOutboxRecord;
import top.egon.cola.component.outbox.store.OutboxRecord;
import top.egon.cola.component.outbox.store.OutboxStore;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Shared externally observable lifecycle contract for each PostgreSQL store implementation. */
final class OutboxStoreContractAssertions {
    static void verify(OutboxStore store, TransactionTemplate transactions, JdbcTemplate physical, String qualifiedTable) {
        OutboxReceipt first = transactions.execute(status -> store.enqueue(record("contract-1", "contract-key", "a".repeat(64))));
        assertTrue(first.created());
        OutboxReceipt duplicate = transactions.execute(status -> store.enqueue(record("contract-2", "contract-key", "a".repeat(64))));
        assertFalse(duplicate.created());
        assertEquals(first.messageId(), duplicate.messageId());
        assertThrows(OutboxIdempotencyConflictException.class, () -> transactions.execute(status ->
                store.enqueue(record("contract-3", "contract-key", "b".repeat(64)))));
        assertEquals(1, store.countBacklog());

        OutboxRecord claimed = store.claimDue(1, "worker-a", Duration.ofMinutes(1)).getFirst();
        assertEquals(1, claimed.attemptCount());
        assertFalse(store.markSucceeded(claimed.id(), "worker-b", claimed.shardSlot()));
        physical.update("UPDATE " + qualifiedTable + " SET locked_until=clock_timestamp()-interval '1 second' WHERE id=?", claimed.id());
        assertFalse(store.markSucceeded(claimed.id(), "worker-a", claimed.shardSlot()));
        OutboxRecord reclaimed = store.claimDue(1, "worker-b", Duration.ofMinutes(1)).getFirst();
        assertEquals(claimed.id(), reclaimed.id());
        assertEquals(2, reclaimed.attemptCount());
        assertFalse(store.markSucceeded(reclaimed.id(), "worker-a", reclaimed.shardSlot()));
        assertTrue(store.markSucceeded(reclaimed.id(), "worker-b", reclaimed.shardSlot()));
        assertEquals(0, store.countBacklog());
        assertEquals(1, store.deleteSucceeded(Duration.ZERO, 10));
    }

    static void verifyLockedRowsAreSkipped(OutboxStore store, TransactionTemplate transactions,
                                            JdbcTemplate physical, String qualifiedTable) throws Exception {
        transactions.executeWithoutResult(status -> {
            store.enqueue(record("locked-first", "locked-key-1", "a".repeat(64)));
            store.enqueue(record("available-second", "locked-key-2", "b".repeat(64)));
        });
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var holder = workers.submit(() -> new TransactionTemplate(new DataSourceTransactionManager(
                    physical.getDataSource())).execute(status -> {
                physical.queryForObject("SELECT id FROM " + qualifiedTable + " WHERE message_id=? FOR UPDATE",
                        Long.class, "locked-first");
                locked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("lock release timeout"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                return true;
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            try {
                var claim = workers.submit(() -> store.claimDue(2, "worker-skip", Duration.ofMinutes(1)));
                var available = claim.get(5, TimeUnit.SECONDS);
                assertEquals(1, available.size());
                assertEquals("available-second", available.getFirst().messageId());
                assertEquals("PROCESSING", physical.queryForObject("SELECT status FROM " + qualifiedTable + " WHERE id=?",
                        String.class, available.getFirst().id()));
                assertEquals("worker-skip", physical.queryForObject("SELECT locked_by FROM " + qualifiedTable + " WHERE id=?",
                        String.class, available.getFirst().id()));
                assertTrue(store.markSucceeded(available.getFirst().id(), "worker-skip", available.getFirst().shardSlot()));
            } finally {
                release.countDown();
            }
            assertTrue(holder.get(5, TimeUnit.SECONDS));
            var remaining = store.claimDue(2, "worker-after", Duration.ofMinutes(1));
            assertEquals(1, remaining.size());
            assertEquals("locked-first", remaining.getFirst().messageId());
            assertTrue(store.markSucceeded(remaining.getFirst().id(), "worker-after", remaining.getFirst().shardSlot()));
        }
    }

    private static NewOutboxRecord record(String id, String key, String fingerprint) {
        return new NewOutboxRecord(id, key, fingerprint, "test", "orders", "{}", "application/json",
                "1", "{}", null, Instant.now().minusSeconds(1), 3);
    }

    private OutboxStoreContractAssertions() { }
}
