package top.egon.cola.component.common.cache.port;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import top.egon.cola.component.common.cache.autoconfigure.EgonColaCacheProperties;
import top.egon.cola.component.common.cache.core.EgonColaTwoLevelCacheManager;
import top.egon.cola.component.common.cache.event.EgonColaCacheChangedOperation;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EgonColaCachePortDelayedEvictionTest {

    @AfterEach
    void clearTenant() {
        MDC.clear();
    }

    @Test
    void delayedReplayPublishesASecondInvalidationForOtherNodes() {
        MDC.put("tenantId", "41");
        var manager = mock(EgonColaTwoLevelCacheManager.class);
        var scheduler = mock(ScheduledExecutorService.class);
        when(manager.scheduler()).thenReturn(scheduler);
        var properties = new EgonColaCacheProperties();
        properties.setSecondEvictDelay(Duration.ofMillis(250));
        new EgonColaTwoLevelCachePort(manager, properties)
                .registerEvictionAfterCommit("users", List.of("41:7"), List.of());
        var task = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(task.capture(), eq(250L), eq(TimeUnit.MILLISECONDS));
        task.getValue().run();
        verify(manager, times(2)).applyLocalEviction("users", List.of("41:7"));
        verify(manager, times(2)).publish("users", EgonColaCacheChangedOperation.EVICT, List.of("41:7"));
    }
}
