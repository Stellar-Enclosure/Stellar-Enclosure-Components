package top.egon.cola.component.outbox.autoconfigure;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.outbox.common.exception.OutboxConfigurationException;

import java.net.URI;
import java.time.Duration;

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
}
