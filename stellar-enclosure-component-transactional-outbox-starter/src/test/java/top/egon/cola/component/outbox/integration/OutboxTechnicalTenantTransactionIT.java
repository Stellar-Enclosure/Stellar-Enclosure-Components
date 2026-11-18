package top.egon.cola.component.outbox.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.aop.support.AopUtils;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.outbox.persistence.OutboxTechnicalContextExecutor;
import top.egon.cola.component.outbox.persistence.dao.OutboxMessageDAO;
import top.egon.cola.component.outbox.persistence.po.OutboxMessagePO;
import top.egon.cola.component.outbox.store.MybatisPlusOutboxStore;
import top.egon.cola.component.outbox.store.OutboxRecord;
import top.egon.cola.component.outbox.store.OutboxStatus;
import top.egon.cola.component.outbox.store.OutboxStore;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Explicit, unskipped PostgreSQL 18.3 test; the caller supplies a fresh owned loopback outbox_it_ database. */
class OutboxTechnicalTenantTransactionIT {
    // Composition deliberately avoids the support superclass's environment/container assumption running first.
    private static final PostgresqlOutboxTestSupport SUPPORT = new PostgresqlOutboxTestSupport() { };
    private static final String BUSINESS_TENANT = "91";
    private static final String BUSINESS_ACTOR = "business-auditor";
    private static boolean initializedByThisClass;
    private Map<String, String> previousMdc;
    private String tenantKey;
    private String actorKey;
    private OutboxStore store;
    private OutboxMessageDAO dao;
    private OutboxTechnicalContextExecutor technical;

    @BeforeAll
    static void requireAndInitializeOwnedPostgresql() throws Exception {
        String url = System.getProperty("egon.outbox.test.postgres.url");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/outbox_it_[a-z0-9_]{1,40}")) {
            throw new IllegalStateException("Explicit technical-tenant IT requires -Degon.outbox.test.postgres.url="
                    + "jdbc:postgresql://127.0.0.1:<owned-port>/outbox_it_<fresh-database>");
        }
        initializedByThisClass = true;
        PostgresqlOutboxTestSupport.initializePostgresql();
        assertThat(PostgresqlOutboxTestSupport.physicalJdbcTemplate.queryForObject(
                "SELECT current_setting('server_version_num')::integer", Integer.class)).isEqualTo(180003);
    }

    @AfterAll
    static void closeOwnedTestContext() throws Exception {
        if (initializedByThisClass) { PostgresqlOutboxTestSupport.closePostgresqlContext(); }
    }

    @BeforeEach
    void establishBusinessCaller() {
        previousMdc = MDC.getCopyOfContextMap();
        SUPPORT.cleanOutboxTables();
        var properties = PostgresqlOutboxTestSupport.context.getBean(EgonColaMybatisPlusProperties.class);
        tenantKey = properties.getTenantId().getMdcKey();
        actorKey = properties.getAudit().getUserIdMdcKey();
        MDC.put(tenantKey, BUSINESS_TENANT);
        MDC.put(actorKey, BUSINESS_ACTOR);
        MDC.put("outbox-it-marker", "original-marker");
        store = PostgresqlOutboxTestSupport.outboxStore();
        dao = PostgresqlOutboxTestSupport.context.getBean(OutboxMessageDAO.class);
        technical = PostgresqlOutboxTestSupport.context.getBean(OutboxTechnicalContextExecutor.class);
        assertThat(AopUtils.getTargetClass(store)).isEqualTo(MybatisPlusOutboxStore.class);
    }

    @AfterEach
    void restoreCallingThread() {
        if (previousMdc == null) { MDC.clear(); }
        else { MDC.setContextMap(previousMdc); }
    }

    @Test
    void scopedTechnicalInsertLoadAndCasKeepTenantZeroAndRestoreTheBusinessCaller() {
        store.enqueue(PostgresqlOutboxTestSupport.newRecord("technical-cas"));
        assertBusinessCallerRestored();
        OutboxMessagePO inserted = loadTechnically("technical-cas");
        assertThat(inserted.getTenantId()).isZero();
        assertThat(inserted.getVersion()).isZero();
        assertThat(inserted.getCreateUserId()).isEqualTo("system:outbox");
        assertBusinessCallerRestored();

        OutboxRecord claimed = store.claimByMessageIds(List.of("technical-cas"), 1,
                "technical-it-worker", Duration.ofSeconds(60)).getFirst();
        assertThat(claimed.status()).isEqualTo(OutboxStatus.PROCESSING);
        assertBusinessCallerRestored();
        assertThat(store.markSucceeded(claimed.id(), "different-worker")).isFalse();
        assertThat(store.markSucceeded(claimed.id(), "technical-it-worker")).isTrue();
        assertThat(store.markSucceeded(claimed.id(), "technical-it-worker")).isFalse();
        OutboxMessagePO succeeded = loadTechnically("technical-cas");
        assertThat(succeeded.getTenantId()).isZero();
        assertThat(succeeded.getVersion()).isEqualTo(2);
        assertThat(succeeded.getStatus()).isEqualTo(OutboxStatus.SUCCEEDED);
        assertThat(succeeded.getUpdateUserId()).isEqualTo("system:outbox");
        assertBusinessCallerRestored();
    }

    @Test
    void settingMdcToZeroCannotForgeTheScopedTechnicalMapperCapability() {
        store.enqueue(PostgresqlOutboxTestSupport.newRecord("legitimate-zero"));
        OutboxMessagePO forged = loadTechnically("legitimate-zero");
        forged.setId(forged.getId() + 1);
        forged.setMessageId("forged-zero");
        forged.setIdempotencyKey("key-forged-zero");
        MDC.put(tenantKey, "0");
        MDC.put(actorKey, "system:outbox");
        try {
            assertThatThrownBy(() -> dao.insertMessage(forged)).isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("EgonColaModelValidationInterceptor");
            assertThatThrownBy(() -> dao.selectReplicaByMessageId("legitimate-zero"))
                    .isInstanceOf(RuntimeException.class).hasStackTraceContaining("EgonColaModelValidationInterceptor");
        } finally {
            MDC.put(tenantKey, BUSINESS_TENANT);
            MDC.put(actorKey, BUSINESS_ACTOR);
        }
        assertThat(PostgresqlOutboxTestSupport.physicalJdbcTemplate.queryForObject(
                "SELECT count(*) FROM egon_outbox.egon_cola_outbox_message", Integer.class)).isEqualTo(1);
        assertBusinessCallerRestored();
    }

    @Test
    void businessIntentAndTechnicalOutboxInsertHaveTheSamePhysicalPostgresqlTransactionId() {
        new TransactionTemplate(PostgresqlOutboxTestSupport.transactionManager).executeWithoutResult(status -> {
            PostgresqlOutboxTestSupport.jdbcTemplate.update(
                    "INSERT INTO public.outbox_test_order(id,state) VALUES (1,'CREATED')");
            store.enqueue(PostgresqlOutboxTestSupport.newRecord("same-physical-transaction"));
            assertBusinessCallerRestored();
        });
        // xmin is assigned by PostgreSQL itself: equal insertion XIDs prove both rows used the same physical transaction.
        String businessXid = PostgresqlOutboxTestSupport.physicalJdbcTemplate.queryForObject(
                "SELECT xmin::text FROM public.outbox_test_order WHERE id=1", String.class);
        String outboxXid = PostgresqlOutboxTestSupport.physicalJdbcTemplate.queryForObject(
                "SELECT xmin::text FROM egon_outbox.egon_cola_outbox_message WHERE message_id=?",
                String.class, "same-physical-transaction");
        assertThat(businessXid).isNotBlank().isEqualTo(outboxXid);
        assertThat(loadTechnically("same-physical-transaction").getTenantId()).isZero();
        assertBusinessCallerRestored();
    }

    @Test
    void technicalFailureRestoresMdcAndRollsBackBothBusinessAndOutboxRows() {
        IllegalStateException expected = new IllegalStateException("controlled technical callback failure");
        assertThatThrownBy(() -> new TransactionTemplate(PostgresqlOutboxTestSupport.transactionManager)
                .executeWithoutResult(status -> {
                    PostgresqlOutboxTestSupport.jdbcTemplate.update(
                            "INSERT INTO public.outbox_test_order(id,state) VALUES (2,'CREATED')");
                    store.enqueue(PostgresqlOutboxTestSupport.newRecord("rollback-both"));
                    assertBusinessCallerRestored();
                    technical.execute(() -> {
                        assertTechnicalCaller();
                        throw expected;
                    });
                })).isSameAs(expected);
        assertThat(PostgresqlOutboxTestSupport.physicalJdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.outbox_test_order", Integer.class)).isZero();
        assertThat(PostgresqlOutboxTestSupport.physicalJdbcTemplate.queryForObject(
                "SELECT count(*) FROM egon_outbox.egon_cola_outbox_message", Integer.class)).isZero();
        assertBusinessCallerRestored();
        // Create a real row after the rollback so a leaked capability cannot hide behind an empty result.
        store.enqueue(PostgresqlOutboxTestSupport.newRecord("after-error"));
        MDC.put(tenantKey, "0");
        MDC.put(actorKey, "system:outbox");
        try {
            assertThatThrownBy(() -> dao.selectReplicaByMessageId("after-error"))
                    .isInstanceOf(RuntimeException.class).hasStackTraceContaining("EgonColaModelValidationInterceptor");
        } finally {
            MDC.put(tenantKey, BUSINESS_TENANT);
            MDC.put(actorKey, BUSINESS_ACTOR);
        }
        assertBusinessCallerRestored();
    }

    private OutboxMessagePO loadTechnically(String messageId) {
        return technical.execute(() -> {
            assertTechnicalCaller();
            return dao.selectReplicaByMessageId(messageId).getFirst();
        });
    }

    private void assertTechnicalCaller() {
        assertThat(MDC.get(tenantKey)).isEqualTo("0");
        assertThat(MDC.get(actorKey)).isEqualTo("system:outbox");
        assertThat(MDC.get("outbox-it-marker")).isEqualTo("original-marker");
    }

    private void assertBusinessCallerRestored() {
        assertThat(MDC.get(tenantKey)).isEqualTo(BUSINESS_TENANT);
        assertThat(MDC.get(actorKey)).isEqualTo(BUSINESS_ACTOR);
        assertThat(MDC.get("outbox-it-marker")).isEqualTo("original-marker");
    }
}
