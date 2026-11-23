package top.egon.cola.component.common.mybatis.operationlog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.groups.Default;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.common.mybatis.business.EgonColaTenantIdProvider;
import top.egon.cola.component.common.mybatis.model.EgonColaModelValidationGroups.Query;
import top.egon.cola.component.common.mybatis.routing.EgonColaTransactionWriteTarget;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Validated
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class OperationLogRepository {

    static final String FACT_TABLE = "egon_cola_operation_log";
    static final String RECEIPT_TABLE = "egon_cola_operation_log_receipt";

    @Qualifier("operationLogDataSource")
    private final DataSource dataSource;
    @Qualifier("operationLogDAO")
    private final OperationLogDAO facts;
    @Qualifier("operationLogReceiptDAO")
    private final OperationLogReceiptDAO receipts;
    @Qualifier("operationLogWriteTransaction")
    private final TransactionTemplate transaction;
    @Qualifier("operationLogConverter")
    private final OperationLogConverter converter;
    @Qualifier("egonColaValidationUtils")
    private final ValidationUtils validation;
    @Qualifier("operationLogJsonMapper")
    private final ObjectMapper json;
    @Qualifier("operationLogClock")
    private final Clock clock;
    @Qualifier("operationLogMaxAge")
    private final Duration maxAge;

    /** Colocated source mode. The caller's transaction owns both business and audit state. */
    public void append(@Valid OperationLogEvent event) {
        requireTenant(event.getTenantId());
        var route = EgonColaTransactionWriteTarget.capture(dataSource);
        if (route == null || route.group() == null) {
            EgonColaTransactionWriteTarget.markRollbackOnly(dataSource);
            throw new IllegalStateException("OPERATION_LOG_ROUTE_MISMATCH");
        }
        validation.validate(event, Default.class, OperationLogEvent.Recorded.class);
        insertFact(event);
    }

    /** A separately committed acceptance can be ACKed only after this method returns. */
    public void accept(@Valid OperationLogEvent event) {
        Objects.requireNonNull(receipts, "OPERATION_LOG_ISOLATED_TARGET_REQUIRED");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OPERATION_LOG_SOURCE_TRANSACTION_ACTIVE");
        }
        validation.validate(event, Default.class, OperationLogEvent.Recorded.class);
        requireTenant(event.getTenantId());
        if (event.getOccurredAt().isBefore(clock.instant().minus(maxAge))) {
            throw new PermanentRejectionException("OPERATION_LOG_EVENT_EXPIRED");
        }
        String digest = fingerprint(event);
        transaction.executeWithoutResult(status -> {
            OperationLogReceiptPO key = OperationLogReceiptPO.builder()
                    .id(event.getEventId()).tenantId(event.getTenantId())
                    .occurredAt(event.getOccurredAt()).payloadSha256(digest).build();
            int inserted = receipts.insertIfAbsent(key);
            if (inserted == 1) {
                insertFact(event);
                return;
            }
            OperationLogReceiptDAO.ReceiptConfirmation existing = inserted == 0 ? receipts.confirm(event.getTenantId(), event.getEventId()) : null;
            if (existing == null) {
                throw new IllegalStateException("OPERATION_LOG_RECEIPT_CONFIRMATION_FAILED");
            }
            if (!event.getOccurredAt().equals(existing.occurredAt()) || !digest.equals(existing.payloadSha256())) {
                log.warn("Operation log receipt conflict for event {}", event.getEventId());
                throw new PermanentRejectionException("OPERATION_LOG_RECEIPT_CONFLICT");
            }
            log.debug("Operation log duplicate confirmed for event {}", event.getEventId());
        });
    }

    public List<PageRow> page(@Valid WindowQuery query) {
        requireTenant(query.tenantId());
        requireUnboundRead();
        validation.validate(query, Query.class);
        return facts.page(query);
    }

    public Optional<OperationLogPO> findDetail(Long tenantId, Long id, Instant occurredAt) {
        requireTenant(tenantId);
        requireUnboundRead();
        if (id == null || id <= 0 || occurredAt == null || occurredAt.getNano() % 1000 != 0) {
            throw new IllegalArgumentException("OPERATION_LOG_DETAIL_LOCATOR_INVALID");
        }
        return Optional.ofNullable(facts.findDetail(tenantId, id, occurredAt));
    }

    private void insertFact(OperationLogEvent event) {
        if (facts.append(converter.toTarget(event)) != 1) {
            throw new IllegalStateException("OPERATION_LOG_FACT_NOT_APPENDED");
        }
    }

    private String fingerprint(OperationLogEvent event) {
        try {
            byte[] normalized = json.writer().with(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
                    .writeValueAsBytes(json.valueToTree(event));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized));
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("OPERATION_LOG_FINGERPRINT_FAILED", failure);
        }
    }

    private static void requireTenant(Long id) {
        if (id == null || id <= 0 || !id.equals(EgonColaTenantIdProvider.currentTenantId())) {
            throw new PermanentRejectionException("OPERATION_LOG_TENANT_MISMATCH");
        }
    }

    private static void requireUnboundRead() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OPERATION_LOG_READ_REQUIRES_UNBOUND_REPLICA_ROUTE");
        }
    }

    public record PageRow(Long id, Long tenantId, String operationId, Instant occurredAt,
            String sourceService, String moduleCode, String actionCode, String resourceType,
            String resourceId, String actorType, String actorId, String actorName,
            OperationLogPhaseEnum phase, OperationLogOutcomeEnum outcome, String routePattern) {}

    public record WindowQuery(
            @NotNull(groups = Query.class) @Positive(groups = Query.class) Long tenantId,
            @NotNull(groups = Query.class) Instant from,
            @NotNull(groups = Query.class) Instant to,
            Instant beforeTime,
            @Positive(groups = Query.class) Long beforeId,
            @Min(value = 2, groups = Query.class) @Max(value = 101, groups = Query.class) int fetchLimit,
            Map<String, String> filters) {

        private static final Set<String> ALLOWED_FILTERS = Set.of(
                "actionCode", "actorId", "resourceType", "resourceId", "bizCode", "namespaceCode",
                "env", "appCode", "outcome");

        public WindowQuery {
            filters = filters == null ? Map.of() : Map.copyOf(filters);
        }

        @AssertTrue(groups = Query.class)
        public boolean isBounded() {
            return from != null && to != null && from.isBefore(to) && from.getNano() % 1_000 == 0
                    && to.getNano() % 1_000 == 0 && Duration.between(from, to).compareTo(Duration.ofDays(31)) <= 0
                    && (beforeTime == null) == (beforeId == null)
                    && (beforeTime == null || beforeTime.getNano() % 1_000 == 0
                            && !beforeTime.isBefore(from) && beforeTime.isBefore(to))
                    && ALLOWED_FILTERS.containsAll(filters.keySet());
        }
    }

    public static final class PermanentRejectionException extends CommonException {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        public PermanentRejectionException(String code) {
            super(ResultCode.VALIDATION_ERROR.getCode(), code, code);
        }
    }

    static TransactionTemplate readCommitted(org.springframework.transaction.PlatformTransactionManager manager,
            Duration timeout) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(Math.toIntExact(timeout.toSeconds()));
        return template;
    }
}
