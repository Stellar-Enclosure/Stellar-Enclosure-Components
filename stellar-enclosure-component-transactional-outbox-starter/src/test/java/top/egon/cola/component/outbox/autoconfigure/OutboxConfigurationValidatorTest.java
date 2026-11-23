package top.egon.cola.component.outbox.autoconfigure;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.outbox.common.exception.OutboxConfigurationException;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxConfigurationValidatorTest {

    private static final ValidationUtils VALIDATION_UTILS =
            new ValidationUtils(Validation.buildDefaultValidatorFactory().getValidator());

    private final OutboxConfigurationValidator validator = new OutboxConfigurationValidator(VALIDATION_UTILS);

    @Test
    void shouldRejectLeaseThatCannotCoverDeliveryWindow() {
        TransactionalOutboxProperties properties = new TransactionalOutboxProperties();
        properties.getDelivery().setTimeout(Duration.ofSeconds(10));
        properties.getDelivery().setLeaseDuration(Duration.ofSeconds(11));
        properties.getPolling().setFixedDelay(Duration.ofSeconds(1));

        assertThatThrownBy(() -> validator.validate(properties))
                .isInstanceOf(OutboxConfigurationException.class)
                .hasMessageContaining("lease-duration");
    }

    @Test
    void shouldRejectInvalidRetryAndCapacityRanges() {
        TransactionalOutboxProperties properties = new TransactionalOutboxProperties();
        properties.getRetry().setJitter(1.1);

        assertThatThrownBy(() -> validator.validate(properties))
                .isInstanceOf(OutboxConfigurationException.class)
                .hasMessageContaining("retry.jitter");
    }

    @Test
    void shouldRejectInvalidPollingJitterAndContentionRetries() {
        for (double invalidJitter : new double[]{-0.1, 1.1, Double.NaN}) {
            TransactionalOutboxProperties properties = new TransactionalOutboxProperties();
            properties.getPolling().setJitter(invalidJitter);

            assertThatThrownBy(() -> validator.validate(properties))
                    .isInstanceOf(OutboxConfigurationException.class)
                    .hasMessageContaining("polling.jitter");
        }

        for (int invalidRetries : new int[]{0, -1}) {
            TransactionalOutboxProperties properties = new TransactionalOutboxProperties();
            properties.getPolling().setMaxClaimContentionRetries(invalidRetries);

            assertThatThrownBy(() -> validator.validate(properties))
                    .isInstanceOf(OutboxConfigurationException.class)
                    .hasMessageContaining("polling.max-claim-contention-retries");
        }

        TransactionalOutboxProperties validLower = new TransactionalOutboxProperties();
        validLower.getPolling().setJitter(0.0);
        validLower.getPolling().setMaxClaimContentionRetries(1);
        validator.validate(validLower);

        TransactionalOutboxProperties validUpper = new TransactionalOutboxProperties();
        validUpper.getPolling().setJitter(1.0);
        validator.validate(validUpper);
    }

    @Test
    void shouldRejectSensitiveFixedHeaderWithoutExposingDestinationValue() {
        TransactionalOutboxProperties properties = new TransactionalOutboxProperties();
        TransactionalOutboxProperties.HttpDestination destination =
                new TransactionalOutboxProperties.HttpDestination();
        destination.setUri(URI.create("https://orders.test/callback"));
        destination.getFixedHeaders().put("Authorization", "secret");
        properties.getHttp().getDestinations().put("order-callback", destination);

        assertThatThrownBy(() -> validator.validate(properties))
                .isInstanceOf(OutboxConfigurationException.class)
                .hasMessageContaining("order-callback")
                .hasMessageNotContaining("orders.test")
                .hasMessageNotContaining("secret");
    }

    @Test
    void safeConsumersRequirePollingAndExcludeAutomaticDeadLetterRecovery() throws Exception {
        for (String mode : new String[]{"LOCAL", "COORDINATED"}) {
            TransactionalOutboxProperties noPolling = safeProperties(mode);
            noPolling.getPolling().setEnabled(false);
            assertThatThrownBy(() -> validator.validate(noPolling))
                    .isInstanceOf(OutboxConfigurationException.class)
                    .hasMessageContaining("polling.enabled");

            TransactionalOutboxProperties deadRecovery = safeProperties(mode);
            deadRecovery.getRetry().setRecoverExhausted(true);
            assertThatThrownBy(() -> validator.validate(deadRecovery))
                    .isInstanceOf(OutboxConfigurationException.class)
                    .hasMessageContaining("recover-exhausted");
        }
    }

    @Test
    void safeConsumersBoundClaimBatchAndAcknowledgementReserve() throws Exception {
        TransactionalOutboxProperties oversizedBatch = safeProperties("LOCAL");
        setProperty(property(oversizedBatch, "getDispatch"), "setMaxClaimBatch", int.class, 101);
        assertThatThrownBy(() -> validator.validate(oversizedBatch))
                .isInstanceOf(OutboxConfigurationException.class)
                .hasMessageContaining("max-claim-batch");

        TransactionalOutboxProperties noShardCapacity = safeProperties("COORDINATED");
        setProperty(property(noShardCapacity, "getDispatch"), "setMaxConcurrentShards", int.class, 0);
        assertThatThrownBy(() -> validator.validate(noShardCapacity))
                .isInstanceOf(OutboxConfigurationException.class)
                .hasMessageContaining("max-concurrent-shards");

        for (Duration reserve : new Duration[]{Duration.ZERO, Duration.ofSeconds(50)}) {
            TransactionalOutboxProperties unsafeReserve = safeProperties("LOCAL");
            setProperty(property(unsafeReserve, "getDispatch"), "setAckReserve", Duration.class, reserve);
            assertThatThrownBy(() -> validator.validate(unsafeReserve))
                    .isInstanceOf(OutboxConfigurationException.class);
        }
    }

    @Test
    void coordinatedConsumersValidateLeaseMembershipAndTransactionBoundaries() throws Exception {
        TransactionalOutboxProperties shortLease = safeProperties("COORDINATED");
        setProperty(property(shortLease, "getCoordination"), "setLeaseDuration", Duration.class, Duration.ofSeconds(14));
        assertThatThrownBy(() -> validator.validate(shortLease))
                .isInstanceOf(OutboxConfigurationException.class)
                .hasMessageContaining("lease-duration");

        TransactionalOutboxProperties shortMembership = safeProperties("COORDINATED");
        setProperty(property(shortMembership, "getCoordination"), "setMembershipTtl", Duration.class, Duration.ofSeconds(24));
        assertThatThrownBy(() -> validator.validate(shortMembership))
                .isInstanceOf(OutboxConfigurationException.class)
                .hasMessageContaining("membership-ttl");

        TransactionalOutboxProperties longClaim = safeProperties("COORDINATED");
        setProperty(property(longClaim, "getCoordination"), "setClaimTimeout", Duration.class, Duration.ofSeconds(5));
        assertThatThrownBy(() -> validator.validate(longClaim))
                .isInstanceOf(OutboxConfigurationException.class)
                .hasMessageContaining("claim-timeout");

        TransactionalOutboxProperties shortRetention = safeProperties("COORDINATED");
        setProperty(property(shortRetention, "getCoordination"), "setMemberRetention", Duration.class, Duration.ofSeconds(30));
        assertThatThrownBy(() -> validator.validate(shortRetention))
                .isInstanceOf(OutboxConfigurationException.class)
                .hasMessageContaining("member-retention");
    }

    @Test
    void documentedCoordinationLowerBoundsAreAccepted() throws Exception {
        TransactionalOutboxProperties properties = safeProperties("COORDINATED");
        Object coordination = property(properties, "getCoordination");
        setProperty(coordination, "setLeaseDuration", Duration.class, Duration.ofSeconds(15));
        setProperty(coordination, "setMembershipTtl", Duration.class, Duration.ofSeconds(20));

        validator.validate(properties);
    }

    @Test
    void legacyStillAllowsItsHistoricalPollingAndRecoveryConfiguration() {
        TransactionalOutboxProperties properties = new TransactionalOutboxProperties();
        properties.getPolling().setEnabled(false);
        properties.getRetry().setRecoverExhausted(true);

        validator.validate(properties);
    }

    private static TransactionalOutboxProperties safeProperties(String mode) throws ReflectiveOperationException {
        TransactionalOutboxProperties properties = new TransactionalOutboxProperties();
        Object dispatch = property(properties, "getDispatch");
        Class<?> modeType = dispatch.getClass().getMethod("getMode").getReturnType();
        Object modeValue = Arrays.stream(modeType.getEnumConstants())
                .filter(value -> value.toString().equals(mode)).findFirst().orElseThrow();
        setProperty(dispatch, "setMode", modeType, modeValue);
        return properties;
    }

    private static Object property(Object target, String getter) throws ReflectiveOperationException {
        return target.getClass().getMethod(getter).invoke(target);
    }

    private static void setProperty(Object target, String setter, Class<?> type, Object value)
            throws ReflectiveOperationException {
        target.getClass().getMethod(setter, type).invoke(target, value);
    }
}
