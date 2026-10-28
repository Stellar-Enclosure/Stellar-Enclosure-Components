package top.egon.cola.component.outbox.integration;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxMpSharedContractIntegrationTest extends PostgresqlOutboxTestSupport {
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
