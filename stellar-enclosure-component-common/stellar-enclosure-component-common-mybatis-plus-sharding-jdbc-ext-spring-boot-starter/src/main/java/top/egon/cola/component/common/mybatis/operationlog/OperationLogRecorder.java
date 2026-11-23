package top.egon.cola.component.common.mybatis.operationlog;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.validation.Valid;
import jakarta.validation.groups.Default;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.common.mybatis.routing.EgonColaTransactionWriteTarget;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Validated
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class OperationLogRecorder {

    private static final Object SOURCE_SCOPE = new Object();
    private static final String CONTEXT_INVALID = "OPERATION_LOG_CONTEXT_INVALID";
    private static final String ROUTE_MISMATCH = "OPERATION_LOG_ROUTE_MISMATCH";

    @Qualifier("egonOperationLogProperties")
    private final EgonOperationLogProperties properties;
    @Qualifier("dataSource")
    private final DataSource dataSource;
    @Qualifier("operationLogDestination")
    private final OperationLogDestination destination;
    @Qualifier("operationLogContextProvider")
    private final Supplier<OperationLogContextDTO> contextProvider;
    @Qualifier("egonColaValidationUtils")
    private final ValidationUtils validation;
    @Qualifier("operationLogClock")
    private final Clock clock;
    @Qualifier("operationLogIdGenerator")
    private final LongSupplier ids;
    @Qualifier("operationLogSourceService")
    private final String sourceService;

    public void recordInTransaction(@Valid OperationLogEvent event) {
        recordInTransaction(event, null);
    }

    /** Allows a transaction-template caller to supply its actual application method. */
    public void recordInTransaction(@Valid OperationLogEvent event, Method sourceMethod) {
        if (!properties.isEnabled()) {
            return;
        }
        SourceScope scope = sourceScope();
        if (!scope.advising) {
            record(scope, event, snapshot(), sourceMethod == null ? null : signature(sourceMethod), null);
        }
    }

    SourceScope sourceScope() {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || EgonColaTransactionWriteTarget.capture(dataSource) == null) {
            rollbackSource();
            throw new IllegalStateException(ROUTE_MISMATCH);
        }
        ConnectionHolder holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource);
        SourceScope scope = (SourceScope) TransactionSynchronizationManager.getResource(SOURCE_SCOPE);
        if (scope == null) {
            scope = new SourceScope(holder);
            TransactionSynchronizationManager.bindResource(SOURCE_SCOPE, scope);
            TransactionSynchronizationManager.registerSynchronization(scope);
        } else if (scope.holder != holder) {
            rollbackSource();
            throw new IllegalStateException(ROUTE_MISMATCH);
        }
        return scope;
    }

    OperationLogContextDTO snapshot() {
        try {
            return validation.validate(contextProvider.get());
        } catch (RuntimeException invalid) {
            rollbackSource();
            throw new IllegalStateException(CONTEXT_INVALID);
        }
    }

    void record(SourceScope scope, OperationLogEvent candidate, OperationLogContextDTO context,
            String handlerMethod, Long measuredDuration) {
        try {
            if (candidate == null) {
                throw new IllegalStateException(CONTEXT_INVALID);
            }
            String operationId = candidate.getOperationId();
            if (operationId != null && candidate.getPhase() == OperationLogPhaseEnum.COMMAND
                    && scope.commandIds.contains(operationId)) {
                return;
            }
            long eventId = ids.getAsLong();
            if (operationId == null) {
                operationId = Long.toString(eventId);
            }
            OperationLogEvent event = candidate.toBuilder()
                    .schemaVersion(OperationLogEvent.SCHEMA_VERSION)
                    .eventId(eventId)
                    .operationId(operationId)
                    .tenantId(context.tenantId())
                    .sourceService(sourceService)
                    .actorType(context.actorType())
                    .actorId(context.actorId())
                    .actorName(context.actorName())
                    .triggerType(context.triggerType())
                    .httpMethod(context.httpMethod())
                    .requestUrl(context.requestUrl())
                    .routePattern(context.routePattern())
                    .handlerMethod(handlerMethod == null ? context.handlerMethod() : handlerMethod)
                    .traceId(context.traceId())
                    .requestId(context.requestId())
                    .occurredAt(clock.instant().truncatedTo(ChronoUnit.MICROS))
                    .durationMs(measuredDuration != null ? measuredDuration
                            : candidate.getDurationMs() == null ? 0L : candidate.getDurationMs())
                    .details(candidate.getDetails() == null ? JsonNodeFactory.instance.objectNode()
                            : candidate.getDetails().deepCopy())
                    .build();
            validateEvent(event);
            destination.append(event);
            if (event.getPhase() == OperationLogPhaseEnum.COMMAND) {
                scope.commandIds.add(operationId);
            }
        } catch (RuntimeException failure) {
            rollbackSource();
            throw failure;
        }
    }

    void rollbackSource() {
        EgonColaTransactionWriteTarget.markRollbackOnly(dataSource);
    }

    private void validateEvent(OperationLogEvent event) {
        try {
            validation.validate(event, Default.class, OperationLogEvent.Recorded.class);
        } catch (RuntimeException invalid) {
            log.debug("Operation log projection rejected");
            throw new IllegalStateException(CONTEXT_INVALID);
        }
    }

    static String signature(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName()
                + Arrays.stream(method.getParameterTypes()).map(Class::getTypeName)
                        .collect(Collectors.joining(",", "(", ")"));
    }

    @RequiredArgsConstructor
    static final class SourceScope implements TransactionSynchronization {

        private final ConnectionHolder holder;
        private final Set<String> commandIds = new HashSet<>();
        boolean advising;

        @Override
        public void suspend() {
            TransactionSynchronizationManager.unbindResourceIfPossible(SOURCE_SCOPE);
        }

        @Override
        public void resume() {
            TransactionSynchronizationManager.bindResource(SOURCE_SCOPE, this);
        }

        @Override
        public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(SOURCE_SCOPE);
        }
    }
}
