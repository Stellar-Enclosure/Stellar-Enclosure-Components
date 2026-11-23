package top.egon.cola.component.outbox.integration;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.outbox.api.OutboxReceipt;
import top.egon.cola.component.outbox.common.exception.OutboxIdempotencyConflictException;
import top.egon.cola.component.outbox.store.NewOutboxRecord;
import top.egon.cola.component.outbox.store.OutboxStore;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresqlOutboxQueryPlanIntegrationTest extends PostgresqlOutboxTestSupport {

    @Test
    void shouldEnforceMessageAndIdempotencyUniquenessUnderConcurrentInsert()
            throws Exception {
        OutboxStore store = outboxStore();
        List<InsertOutcome> messageOutcomes = race(
                store,
                newRecord("same-message", "key-1", "a".repeat(64), 10),
                newRecord("same-message", "key-2", "a".repeat(64), 10)
        );
        assertThat(messageOutcomes).containsExactlyInAnyOrder(
                InsertOutcome.CREATED,
                InsertOutcome.EXISTING
        );

        jdbcTemplate.execute("truncate table egon_outbox.egon_cola_outbox_message restart identity");
        List<InsertOutcome> idempotencyOutcomes = race(
                store,
                newRecord("message-1", "same-key", "a".repeat(64), 10),
                newRecord("message-2", "same-key", "b".repeat(64), 10)
        );
        assertThat(idempotencyOutcomes).contains(
                InsertOutcome.CREATED,
                InsertOutcome.CONFLICT
        );
    }

    @Test
    void shouldExposeRequiredSchemaAndUseClaimIndexes() throws Exception {
        OutboxStore store = outboxStore();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                IntStream.range(0, 1_000).forEach(index ->
                        store.enqueue(newRecord("plan-" + index))));
        physicalJdbcTemplate.update("""
                with numbered as (
                    select id, row_number() over (order by id) as position
                    from egon_outbox.egon_cola_outbox_message
                )
                update egon_outbox.egon_cola_outbox_message message
                set status = 'PROCESSING', locked_by = 'expired',
                    locked_until = clock_timestamp() - interval '1 second'
                from numbered where message.id = numbered.id and numbered.position % 2 = 0
                """);
        physicalJdbcTemplate.execute("analyze egon_outbox.egon_cola_outbox_message");

        Set<String> indexes = Set.copyOf(physicalJdbcTemplate.queryForList("""
                select indexname
                from pg_indexes
                where schemaname = current_schema()
                  and tablename = 'egon_cola_outbox_message'
                """, String.class));
        assertThat(indexes).contains(
                "uk_outbox_message_id",
                "uk_outbox_idempotency_key",
                "idx_outbox_claim",
                "idx_outbox_reclaim",
                "idx_outbox_cleanup"
        );
        assertThat(physicalJdbcTemplate.queryForList("""
                select column_name
                from information_schema.columns
                where table_schema = current_schema()
                  and table_name = 'egon_cola_outbox_message'
                """, String.class)).contains(
                "message_id",
                "message_fingerprint",
                "status",
                "locked_by",
                "locked_until",
                "completed_at"
        );

        String duePlan = explain("""
                select id
                from egon_outbox.egon_cola_outbox_message
                where status in ('PENDING', 'RETRY_WAIT')
                  and next_attempt_at <= clock_timestamp()
                order by next_attempt_at, id
                limit 100
                """);
        String channelDuePlan = explain("""
                select id
                from egon_outbox.egon_cola_outbox_message
                where channel in ('http', 'state-machine')
                  and status in ('PENDING', 'RETRY_WAIT')
                  and next_attempt_at <= clock_timestamp()
                order by next_attempt_at, id
                limit 100
                """);
        String reclaimPlan = explain("""
                select id
                from egon_outbox.egon_cola_outbox_message
                where status = 'PROCESSING'
                  and locked_until < clock_timestamp()
                order by locked_until, id
                limit 100
                """);
        assertThat(duePlan).contains("idx_outbox_claim");
        assertThat(channelDuePlan).contains("idx_outbox_claim");
        assertThat(reclaimPlan).contains("idx_outbox_reclaim");
    }

    @Test
    void safeCandidateShapesUsePartialIndexesWithBoundedReturnedRows() throws Exception {
        OutboxStore store = outboxStore();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                IntStream.range(0, 1_200).forEach(index -> store.enqueue(newRecord("safe-plan-" + index))));
        physicalJdbcTemplate.update("""
                with numbered as (
                    select id, row_number() over (order by id) as position
                    from egon_outbox.egon_cola_outbox_message
                )
                update egon_outbox.egon_cola_outbox_message message
                set status = 'PROCESSING', locked_by = 'expired',
                    locked_until = clock_timestamp() - interval '1 second'
                from numbered where message.id = numbered.id and numbered.position % 2 = 0
                """);
        physicalJdbcTemplate.execute("analyze egon_outbox.egon_cola_outbox_message");

        for (int limit : List.of(1, 2, 4)) {
            for (boolean expired : List.of(false, true)) {
                assertCandidatePlan(limit, expired, false);
                if (limit == 1) {
                    assertCandidatePlan(limit, expired, true);
                }
            }
        }
    }

    private void assertCandidatePlan(int limit, boolean expired, boolean mp) throws Exception {
        String clock = mp ? "statement_timestamp()" : "clock_timestamp()";
        String timeColumn = expired ? "locked_until" : "next_attempt_at";
        String statuses = expired ? "status = 'PROCESSING'" : "status IN ('PENDING','RETRY_WAIT')";
        String scope = mp ? " AND shard_slot=0 AND tenant_id=0 AND deleted_at IS NULL" : "";
        String query = "SELECT * FROM egon_outbox.egon_cola_outbox_message WHERE channel='test'" + scope
                + " AND " + statuses + " AND " + timeColumn + " <= " + clock
                + " ORDER BY " + timeColumn + ", id LIMIT " + limit
                + (mp ? " FOR UPDATE NOWAIT" : " FOR UPDATE SKIP LOCKED");
        var result = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(explain(query, true)).get(0);
        var plan = result.get("Plan");
        var index = expired ? "idx_outbox_reclaim" : "idx_outbox_claim";
        assertThat(plan.findValuesAsText("Index Name")).contains(index);
        assertThat(plan.get("Actual Rows").asInt()).isEqualTo(limit);
        System.out.printf("Outbox plan store=%s branch=%s limit=%d rows=%d buffers=%d executionMs=%.3f%n",
                mp ? "MP" : "JDBC", expired ? "expired" : "due", limit, plan.get("Actual Rows").asInt(),
                plan.get("Shared Hit Blocks").asInt() + plan.get("Shared Read Blocks").asInt(),
                result.get("Execution Time").asDouble());
    }

    private String explain(String query) throws SQLException {
        return explain(query, false);
    }

    private String explain(String query, boolean analyze) throws SQLException {
        try (Connection connection = physicalJdbcTemplate.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL enable_seqscan = off");
                try (ResultSet result = statement.executeQuery((analyze ? "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " : "EXPLAIN (FORMAT JSON) ") + query)) {
                    String plan = result.next() ? result.getString(1) : "";
                    connection.rollback();
                    return plan;
                }
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private List<InsertOutcome> race(
            OutboxStore store,
            NewOutboxRecord first,
            NewOutboxRecord second
    ) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<InsertOutcome> firstOutcome =
                    executor.submit(() -> insert(store, first, barrier));
            Future<InsertOutcome> secondOutcome =
                    executor.submit(() -> insert(store, second, barrier));
            return List.of(
                    firstOutcome.get(5, TimeUnit.SECONDS),
                    secondOutcome.get(5, TimeUnit.SECONDS)
            );
        }
    }

    private InsertOutcome insert(
            OutboxStore store,
            NewOutboxRecord record,
            CyclicBarrier barrier
    ) throws Exception {
        barrier.await();
        try {
            OutboxReceipt receipt = store.enqueue(record);
            return receipt.created() ? InsertOutcome.CREATED : InsertOutcome.EXISTING;
        } catch (OutboxIdempotencyConflictException exception) {
            return InsertOutcome.CONFLICT;
        }
    }

    private enum InsertOutcome {
        CREATED,
        EXISTING,
        CONFLICT
    }
}
