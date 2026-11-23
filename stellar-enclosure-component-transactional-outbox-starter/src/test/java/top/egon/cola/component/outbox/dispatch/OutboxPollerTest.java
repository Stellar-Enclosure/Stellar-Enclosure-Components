package top.egon.cola.component.outbox.dispatch;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import top.egon.cola.component.outbox.autoconfigure.TransactionalOutboxProperties;
import top.egon.cola.component.outbox.cleanup.OutboxCleanupJob;
import top.egon.cola.component.outbox.autoconfigure.TransactionalOutboxProperties.DispatchMode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class OutboxPollerTest {

    @Test
    void safe_local_lifecycle_starts_ready_workers_and_periodic_polling_discovers_remote_commits() throws Exception {
        OutboxDispatcher dispatcher = mock(OutboxDispatcher.class);
        OutboxCleanupJob cleanupJob = mock(OutboxCleanupJob.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> pollFuture = mock(ScheduledFuture.class);
        TransactionalOutboxProperties properties = new TransactionalOutboxProperties();
        properties.getDispatch().setMode(DispatchMode.LOCAL);
        properties.getPolling().setJitter(0);
        properties.getCleanup().setEnabled(false);
        ArgumentCaptor<Runnable> pollTask = ArgumentCaptor.forClass(Runnable.class);
        doReturn(pollFuture).when(scheduler).scheduleWithFixedDelay(pollTask.capture(), any(Duration.class));
        OutboxPoller poller = withCoordinator(new OutboxPoller(dispatcher, cleanupJob, scheduler, properties), null);

        poller.start();
        poller.start();
        assertThat(poller.isRunning()).isTrue();
        assertThat(invocations(dispatcher, "startSafeDispatch")).isEqualTo(1);
        assertThat(org.mockito.Mockito.mockingDetails(dispatcher).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("startSafeDispatch"))
                .findFirst().orElseThrow().getArguments()).containsExactly(new Object[]{null});
        pollTask.getValue().run();
        verify(dispatcher).submitDue();
        poller.stop();
        assertThat(invocations(dispatcher, "stopSafeDispatch")).isEqualTo(1);
        verify(pollFuture).cancel(false);
        pollTask.getValue().run();
        verify(dispatcher, times(1)).submitDue();
        verify(cleanupJob, never()).runOnce();
    }

    @Test
    void coordinated_lifecycle_passes_the_configured_coordinator_to_safe_dispatch() throws Exception {
        OutboxDispatcher dispatcher = mock(OutboxDispatcher.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> pollFuture = mock(ScheduledFuture.class);
        doReturn(pollFuture).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
        TransactionalOutboxProperties properties = new TransactionalOutboxProperties();
        properties.getDispatch().setMode(DispatchMode.COORDINATED);
        properties.getPolling().setJitter(0);
        properties.getCleanup().setEnabled(false);
        Class<?> coordinatorType = Class.forName("top.egon.cola.component.outbox.dispatch.OutboxQueueCoordinator");
        Object coordinator = mock(coordinatorType);
        OutboxPoller poller = withCoordinator(new OutboxPoller(dispatcher, mock(OutboxCleanupJob.class), scheduler, properties), coordinator);

        poller.start();
        Object[] parameters = org.mockito.Mockito.mockingDetails(dispatcher).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("startSafeDispatch"))
                .findFirst().orElseThrow().getArguments();
        assertThat(parameters).containsExactly(coordinator);
        poller.stop();
        assertThat(invocations(dispatcher, "stopSafeDispatch")).isEqualTo(1);
    }

    private static OutboxPoller withCoordinator(OutboxPoller poller, Object coordinator) throws Exception {
        Class<?> type = Class.forName("top.egon.cola.component.outbox.dispatch.OutboxQueueCoordinator");
        return (OutboxPoller) OutboxPoller.class.getMethod("withQueueCoordinator", type).invoke(poller, coordinator);
    }

    private static long invocations(OutboxDispatcher dispatcher, String method) {
        return org.mockito.Mockito.mockingDetails(dispatcher).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals(method)).count();
    }

    @Test
    void shouldScheduleFixedDelayPollingWhenJitterIsZeroAndCancelBothOnStop() {
        OutboxDispatcher dispatcher = mock(OutboxDispatcher.class);
        OutboxCleanupJob cleanupJob = mock(OutboxCleanupJob.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> pollFuture = mock(ScheduledFuture.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> cleanupFuture = mock(ScheduledFuture.class);
        TransactionalOutboxProperties properties = new TransactionalOutboxProperties();
        properties.getPolling().setJitter(0.0);
        properties.getCleanup().setEnabled(true);
        doReturn(pollFuture).when(scheduler).scheduleWithFixedDelay(
                any(Runnable.class),
                eq(properties.getPolling().getFixedDelay())
        );
        doReturn(cleanupFuture).when(scheduler).scheduleWithFixedDelay(
                any(Runnable.class),
                eq(properties.getCleanup().getFixedDelay())
        );
        OutboxPoller poller = new OutboxPoller(dispatcher, cleanupJob, scheduler, properties);

        poller.start();

        assertThat(poller.isRunning()).isTrue();
        poller.stop();
        assertThat(poller.isRunning()).isFalse();
        verify(pollFuture).cancel(false);
        verify(cleanupFuture).cancel(false);
    }

    @Test
    void shouldNotRunCapturedPollTaskAfterStopOrScheduleDisabledCleanup() {
        OutboxDispatcher dispatcher = mock(OutboxDispatcher.class);
        OutboxCleanupJob cleanupJob = mock(OutboxCleanupJob.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> pollFuture = mock(ScheduledFuture.class);
        TransactionalOutboxProperties properties = new TransactionalOutboxProperties();
        properties.getPolling().setJitter(0.0);
        properties.getCleanup().setEnabled(false);
        ArgumentCaptor<Runnable> pollTask = ArgumentCaptor.forClass(Runnable.class);
        doReturn(pollFuture).when(scheduler).scheduleWithFixedDelay(
                pollTask.capture(),
                any(Duration.class)
        );
        OutboxPoller poller = new OutboxPoller(dispatcher, cleanupJob, scheduler, properties);

        poller.start();
        poller.stop();
        pollTask.getValue().run();

        verify(dispatcher, never()).submitDue();
        verify(cleanupJob, never()).runOnce();
    }

    @Test
    void shouldSchedulePollingWithJitterAndContinueAfterFailure() {
        OutboxDispatcher dispatcher = mock(OutboxDispatcher.class);
        OutboxCleanupJob cleanupJob = mock(OutboxCleanupJob.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> pollFuture = mock(ScheduledFuture.class);
        TransactionalOutboxProperties properties = new TransactionalOutboxProperties();
        properties.getPolling().setFixedDelay(Duration.ofMillis(1000));
        properties.getPolling().setJitter(0.2);
        properties.getCleanup().setEnabled(false);
        Instant now = Instant.parse("2026-11-16T12:00:00Z");
        Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        ArgumentCaptor<Runnable> pollTask = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> nextRun = ArgumentCaptor.forClass(Instant.class);
        doReturn(pollFuture).when(scheduler).schedule(pollTask.capture(), nextRun.capture());
        doThrow(new IllegalStateException("transient poll error")).when(dispatcher).submitDue();
        OutboxPoller poller = new OutboxPoller(dispatcher, cleanupJob, scheduler, properties, () -> 0.5, fixedClock);

        poller.start();
        assertThat(nextRun.getValue()).isEqualTo(now.plusMillis(100));

        pollTask.getValue().run();
        verify(dispatcher, times(1)).submitDue();
        verify(scheduler, times(2)).schedule(any(Runnable.class), nextRun.capture());
        assertThat(nextRun.getAllValues().get(1)).isEqualTo(now.plusMillis(1100));

        poller.stop();
        pollTask.getAllValues().get(1).run();
        verify(dispatcher, times(1)).submitDue();
        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }
}
