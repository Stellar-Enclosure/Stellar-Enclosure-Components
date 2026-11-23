package top.egon.cola.component.outbox.observability;

import java.time.Duration;

public interface OutboxMetrics {

    default void dispatchStage(int shardSlot, String channel, String stage) { }

    default void dispatchStageDuration(int shardSlot, String channel, String stage, Duration duration) { }

    void enqueue(boolean created);

    void claimed(int count);

    void delivery(String channel, String result, Duration duration);

    void retry(String channel);

    void dead(String channel);

    void leaseLost();

    void wakeupRejected();

    void updateBacklog(long value);
}
