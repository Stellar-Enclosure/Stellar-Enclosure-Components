package top.egon.cola.component.outbox.autoconfigure;

import top.egon.cola.component.common.core.validation.BaseValidator;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.outbox.common.exception.OutboxConfigurationException;
import top.egon.cola.component.outbox.validation.OutboxMessageValidator;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class OutboxConfigurationValidator extends BaseValidator {

    private static final Duration LEASE_SAFETY_MARGIN = Duration.ofSeconds(1);
    private static final Set<String> STANDARD_HTTP_METHODS = Set.of(
            "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE"
    );

    private final ValidationUtils validationUtils;

    public OutboxConfigurationValidator(ValidationUtils validationUtils) {
        this.validationUtils = Objects.requireNonNull(validationUtils, "validationUtils");
    }

    @Override
    protected ValidationUtils getValidationUtils() {
        return validationUtils;
    }

    public void validate(TransactionalOutboxProperties properties) {
        if (properties == null) {
            throw invalid("properties");
        }
        validateCore(properties);
        validateDispatch(properties);
        validateBean(properties);
        validateHttp(properties.getHttp());
        validateRabbitmq(properties.getRabbitmq());
    }

    private void validateCore(TransactionalOutboxProperties properties) {
        if (properties.getStorage().getType() == null) {
            throw invalid("storage.type");
        }
        if (properties.getStorage().getType() == TransactionalOutboxProperties.Storage.StorageType.JDBC
                && (properties.getStorage().getJdbc().getSchema() == null
                    || !properties.getStorage().getJdbc().getSchema().matches("[a-z_][a-z0-9_]{0,62}"))) {
            throw invalid("storage.jdbc.schema");
        }
        positive(properties.getPolling().getFixedDelay(), "polling.fixed-delay");
        positive(properties.getPolling().getBatchSize(), "polling.batch-size");
        positive(properties.getPolling().getConcurrency(), "polling.concurrency");
        if (!Double.isFinite(properties.getPolling().getJitter())
                || properties.getPolling().getJitter() < 0.0
                || properties.getPolling().getJitter() > 1.0) {
            throw invalid("polling.jitter");
        }
        positive(
                properties.getPolling().getMaxClaimContentionRetries(),
                "polling.max-claim-contention-retries"
        );
        positive(properties.getDelivery().getTimeout(), "delivery.timeout");
        positive(properties.getDelivery().getLeaseDuration(), "delivery.lease-duration");
        positive(properties.getDelivery().getQueueCapacity(), "delivery.queue-capacity");
        positive(properties.getRetry().getMaxAttempts(), "retry.max-attempts");
        positive(properties.getRetry().getInitialDelay(), "retry.initial-delay");
        positive(properties.getRetry().getMaxDelay(), "retry.max-delay");
        positive(properties.getRetry().getExhaustedRetryDelay(), "retry.exhausted-retry-delay");
        if (properties.getRetry().getExhaustedRetryDelay().toMillis() < 1) {
            throw invalid("retry.exhausted-retry-delay");
        }
        if (!Double.isFinite(properties.getRetry().getMultiplier())
                || properties.getRetry().getMultiplier() < 1.0) {
            throw invalid("retry.multiplier");
        }
        if (!Double.isFinite(properties.getRetry().getJitter())
                || properties.getRetry().getJitter() < 0.0
                || properties.getRetry().getJitter() > 1.0) {
            throw invalid("retry.jitter");
        }
        if (properties.getRetry().getMaxDelay().compareTo(properties.getRetry().getInitialDelay()) < 0) {
            throw invalid("retry.max-delay");
        }
        positive(properties.getPayload().getMaxBytes(), "payload.max-bytes");
        positive(properties.getPayload().getMaxHeaderCount(), "payload.max-header-count");
        positive(properties.getPayload().getMaxHeaderBytes(), "payload.max-header-bytes");
        positive(properties.getCleanup().getSuccessRetention(), "cleanup.success-retention");
        positive(properties.getCleanup().getFixedDelay(), "cleanup.fixed-delay");
        positive(properties.getCleanup().getBatchSize(), "cleanup.batch-size");
        positive(properties.getShutdown().getGracePeriod(), "shutdown.grace-period");

        Duration minimumLease = properties.getDelivery().getTimeout()
                .plus(properties.getPolling().getFixedDelay())
                .plus(LEASE_SAFETY_MARGIN);
        if (properties.getDelivery().getLeaseDuration().compareTo(minimumLease) <= 0) {
            throw invalid("delivery.lease-duration");
        }
        if (properties.getNodeId() != null && properties.getNodeId().length() > 80) {
            throw invalid("node-id");
        }
    }

    private void validateDispatch(TransactionalOutboxProperties properties) {
        var dispatch = properties.getDispatch();
        var coordination = properties.getCoordination();
        if (dispatch.getMode() == null) {
            throw invalid("dispatch.mode");
        }
        positive(dispatch.getMaxClaimBatch(), "dispatch.max-claim-batch");
        positive(dispatch.getMaxConcurrentShards(), "dispatch.max-concurrent-shards");
        positive(dispatch.getAckReserve(), "dispatch.ack-reserve");
        positive(coordination.getHeartbeatInterval(), "coordination.heartbeat-interval");
        positive(coordination.getLeaseDuration(), "coordination.lease-duration");
        positive(coordination.getMembershipTtl(), "coordination.membership-ttl");
        positive(coordination.getClaimTimeout(), "coordination.claim-timeout");
        positive(coordination.getMemberRetention(), "coordination.member-retention");

        if (dispatch.getMode() == TransactionalOutboxProperties.DispatchMode.LEGACY
                || !dispatch.isConsumptionEnabled()) {
            return;
        }
        if (!properties.getPolling().isEnabled()) {
            throw invalid("polling.enabled");
        }
        if (properties.getRetry().isRecoverExhausted()) {
            throw invalid("retry.recover-exhausted");
        }
        if (dispatch.getMaxClaimBatch() > Math.min(10_000, properties.getPolling().getBatchSize())) {
            throw invalid("dispatch.max-claim-batch");
        }

        Duration timeBeforeAck = properties.getDelivery().getLeaseDuration().minus(dispatch.getAckReserve());
        if (timeBeforeAck.compareTo(properties.getDelivery().getTimeout()) <= 0) {
            throw invalid("dispatch.ack-reserve");
        }
        Duration heartbeat = coordination.getHeartbeatInterval();
        Duration claim = coordination.getClaimTimeout();
        Duration queueLease = coordination.getLeaseDuration();
        if (claim.compareTo(heartbeat) >= 0 || claim.compareTo(queueLease) >= 0
                || claim.compareTo(timeBeforeAck) >= 0) {
            throw invalid("coordination.claim-timeout");
        }
        if (queueLease.dividedBy(3).compareTo(heartbeat) < 0) {
            throw invalid("coordination.lease-duration");
        }
        if (coordination.getMembershipTtl().minus(queueLease).compareTo(heartbeat) < 0) {
            throw invalid("coordination.membership-ttl");
        }
        if (coordination.getMemberRetention().compareTo(coordination.getMembershipTtl()) <= 0) {
            throw invalid("coordination.member-retention");
        }
    }

    private void validateHttp(TransactionalOutboxProperties.Http http) {
        http.getDestinations().forEach((key, destination) -> {
            requireDestinationKey(key, "http");
            if (destination == null) {
                throw invalidDestination("http", key);
            }
            URI uri = destination.getUri();
            String scheme = uri == null ? null : uri.getScheme();
            if (scheme == null
                    || !Set.of("http", "https").contains(scheme.toLowerCase(Locale.ROOT))
                    || uri.getUserInfo() != null) {
                throw invalidDestination("http", key);
            }
            String method = destination.getMethod();
            if (method == null || !STANDARD_HTTP_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
                throw invalidDestination("http", key);
            }
            positiveDestination(destination.getConnectTimeout(), "http", key);
            positiveDestination(destination.getReadTimeout(), "http", key);
            validateFixedHeaders(destination.getFixedHeaders(), "http", key);
        });
    }

    private void validateRabbitmq(TransactionalOutboxProperties.Rabbitmq rabbitmq) {
        positive(rabbitmq.getConfirmTimeout(), "rabbitmq.confirm-timeout");
        rabbitmq.getDestinations().forEach((key, destination) -> {
            requireDestinationKey(key, "rabbitmq");
            if (destination == null
                    || isBlank(destination.getExchange())
                    || isBlank(destination.getRoutingKey())
                    || !destination.isMandatory()) {
                throw invalidDestination("rabbitmq", key);
            }
            Duration confirmTimeout = destination.getConfirmTimeout() == null
                    ? rabbitmq.getConfirmTimeout()
                    : destination.getConfirmTimeout();
            positiveDestination(confirmTimeout, "rabbitmq", key);
            validateFixedHeaders(destination.getFixedHeaders(), "rabbitmq", key);
        });
    }

    private void validateFixedHeaders(Map<String, String> headers, String channel, String key) {
        if (headers == null) {
            throw invalidDestination(channel, key);
        }
        headers.forEach((name, value) -> {
            if (isBlank(name) || value == null || OutboxMessageValidator.isForbiddenHeader(name)) {
                throw invalidDestination(channel, key);
            }
        });
    }

    private void requireDestinationKey(String key, String channel) {
        if (isBlank(key)) {
            throw invalid(channel + ".destinations");
        }
    }

    private void positive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw invalid(property);
        }
    }

    private void positive(org.springframework.util.unit.DataSize value, String property) {
        if (value == null || value.toBytes() <= 0) {
            throw invalid(property);
        }
    }

    private void positive(int value, String property) {
        if (value <= 0) {
            throw invalid(property);
        }
    }

    private void positiveDestination(Duration value, String channel, String key) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw invalidDestination(channel, key);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private OutboxConfigurationException invalid(String property) {
        return new OutboxConfigurationException("Invalid transactional outbox configuration: " + property);
    }

    private OutboxConfigurationException invalidDestination(String channel, String key) {
        return new OutboxConfigurationException(
                "Invalid transactional outbox " + channel + " destination: " + key
        );
    }
}
