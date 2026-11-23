package top.egon.cola.component.outbox.dispatch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import top.egon.cola.component.outbox.autoconfigure.TransactionalOutboxProperties;
import top.egon.cola.component.outbox.cleanup.OutboxCleanupJob;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

@Slf4j
public class OutboxPoller implements SmartLifecycle {

    private final OutboxDispatcher dispatcher;
    private final OutboxCleanupJob cleanupJob;
    private final TaskScheduler taskScheduler;
    private final TransactionalOutboxProperties properties;
    private final DoubleSupplier randomSource;
    private final Clock clock;
    private volatile boolean running;
    private OutboxQueueCoordinator queueCoordinator;
    private ScheduledFuture<?> pollingFuture;
    private ScheduledFuture<?> cleanupFuture;

    public OutboxPoller(
            OutboxDispatcher dispatcher,
            OutboxCleanupJob cleanupJob,
            TaskScheduler taskScheduler,
            TransactionalOutboxProperties properties
    ) {
        this(
                dispatcher,
                cleanupJob,
                taskScheduler,
                properties,
                () -> ThreadLocalRandom.current().nextDouble(),
                Clock.systemUTC()
        );
    }

    OutboxPoller(
            OutboxDispatcher dispatcher,
            OutboxCleanupJob cleanupJob,
            TaskScheduler taskScheduler,
            TransactionalOutboxProperties properties,
            DoubleSupplier randomSource,
            Clock clock
    ) {
        this.dispatcher = dispatcher;
        this.cleanupJob = cleanupJob;
        this.taskScheduler = taskScheduler;
        this.properties = properties;
        this.randomSource = Objects.requireNonNull(randomSource, "randomSource must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public OutboxPoller withQueueCoordinator(OutboxQueueCoordinator coordinator) {
        this.queueCoordinator = coordinator;
        return this;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (properties.getDispatch().isConsumptionEnabled()
                && properties.getDispatch().getMode() != TransactionalOutboxProperties.DispatchMode.LEGACY) {
            dispatcher.startSafeDispatch(queueCoordinator);
        }
        running = true;
        if (properties.getDispatch().isConsumptionEnabled() && properties.getPolling().isEnabled()) {
            double jitter = properties.getPolling().getJitter();
            if (jitter <= 0.0) {
                pollingFuture = taskScheduler.scheduleWithFixedDelay(
                        this::submitDueWhenRunning,
                        properties.getPolling().getFixedDelay()
                );
            } else {
                scheduleJitteredPoll(nextInitialDelayMillis(jitter));
            }
        }
        if (properties.getCleanup().isEnabled()) {
            cleanupFuture = taskScheduler.scheduleWithFixedDelay(
                    this::cleanupWhenRunning,
                    properties.getCleanup().getFixedDelay()
            );
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        cancel(pollingFuture);
        cancel(cleanupFuture);
        pollingFuture = null;
        cleanupFuture = null;
        if (properties.getDispatch().getMode() != TransactionalOutboxProperties.DispatchMode.LEGACY) {
            dispatcher.stopSafeDispatch();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void submitDueWhenRunning() {
        if (running) {
            dispatcher.submitDue();
        }
    }

    private void runJitteredPoll() {
        if (!running) {
            return;
        }
        try {
            dispatcher.submitDue();
        } catch (RuntimeException ex) {
            log.warn("Outbox polling iteration failed; rescheduling next poll", ex);
        } finally {
            if (running) {
                scheduleJitteredPoll(nextSubsequentDelayMillis(properties.getPolling().getJitter()));
            }
        }
    }

    private synchronized void scheduleJitteredPoll(long delayMillis) {
        if (!running) {
            return;
        }
        Instant nextRunTime = clock.instant().plusMillis(delayMillis);
        pollingFuture = taskScheduler.schedule(this::runJitteredPoll, nextRunTime);
    }

    private long nextInitialDelayMillis(double jitter) {
        long baseMillis = properties.getPolling().getFixedDelay().toMillis();
        double sample = clampUnitSample(randomSource.getAsDouble());
        return Math.max(0L, Math.round(baseMillis * jitter * sample));
    }

    private long nextSubsequentDelayMillis(double jitter) {
        long baseMillis = properties.getPolling().getFixedDelay().toMillis();
        double sample = clampUnitSample(randomSource.getAsDouble());
        return Math.max(1L, Math.round(baseMillis * (1.0 + jitter * sample)));
    }

    private double clampUnitSample(double sample) {
        if (!Double.isFinite(sample) || sample <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, sample);
    }

    private void cleanupWhenRunning() {
        if (running) {
            cleanupJob.runOnce();
        }
    }

    private void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }
}
