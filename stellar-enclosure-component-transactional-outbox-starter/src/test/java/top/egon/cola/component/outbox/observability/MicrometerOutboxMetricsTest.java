package top.egon.cola.component.outbox.observability;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerOutboxMetricsTest {

    @Test
    void shouldRecordOnlyLowCardinalityTagsAndCachedBacklog() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerOutboxMetrics metrics = new MicrometerOutboxMetrics(registry);

        metrics.enqueue(true);
        metrics.claimed(2);
        metrics.delivery("http", "success", Duration.ofMillis(12));
        metrics.updateBacklog(7);

        assertThat(registry.get("egon.cola.outbox.enqueue")
                .tag("result", "created").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("egon.cola.outbox.claim").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("egon.cola.outbox.delivery")
                .tag("channel", "http").tag("result", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("egon.cola.outbox.backlog").gauge().value())
                .isEqualTo(7.0);
        assertThat(registry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .extracting(Tag::getKey)
                .doesNotContain("messageId", "idempotencyKey", "destination", "url");
    }

    @Test
    void keepsHandlerOutcomeAndDatabaseAcknowledgementSeparate() {
        var registry = new SimpleMeterRegistry();
        try {
            OutboxMetrics metrics = new MicrometerOutboxMetrics(registry);
            metrics.dispatchStage(1, "http", "claim_committed");
            metrics.dispatchStage(1, "http", "handoff");
            metrics.dispatchStage(1, "http", "handler_started");
            metrics.delivery("http", "success", Duration.ofMillis(12));
            metrics.dispatchStage(1, "http", "indeterminate");
            assertThat(registry.find("egon.cola.outbox.dispatch").tag("stage", "ack_confirmed").counter())
                    .isNull();
            metrics.dispatchStage(1, "http", "ack_confirmed");
            metrics.dispatchStageDuration(1, "http", "claim_to_start", Duration.ofMillis(2));
            metrics.dispatchStageDuration(1, "http", "result_to_ack", Duration.ofMillis(4));

            for (String stage : java.util.List.of("claim_committed", "handoff", "handler_started",
                    "indeterminate", "ack_confirmed")) {
                assertThat(registry.get("egon.cola.outbox.dispatch")
                        .tags("shard", "1", "channel", "http", "stage", stage).counter().count()).isEqualTo(1);
            }
            assertThat(registry.get("egon.cola.outbox.dispatch.duration")
                    .tags("shard", "1", "channel", "http", "stage", "result_to_ack").timer().count()).isEqualTo(1);
            assertThat(registry.find("egon.cola.outbox.dispatch").meters())
                    .allSatisfy(meter -> assertThat(meter.getId().getTags())
                            .extracting(Tag::getKey).containsExactlyInAnyOrder("shard", "channel", "stage"));
        } finally {
            registry.close();
        }
        OutboxMetrics noop = new NoopOutboxMetrics();
        noop.dispatchStage(1, "http", "ack_confirmed");
        noop.dispatchStageDuration(1, "http", "result_to_ack", Duration.ofMillis(4));
    }
}
