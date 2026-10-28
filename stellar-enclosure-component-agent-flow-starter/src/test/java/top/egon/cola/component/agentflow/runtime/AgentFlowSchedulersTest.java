package top.egon.cola.component.agentflow.runtime;

import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentFlowSchedulersTest {
    @Test
    void restores_the_worker_context_after_each_tenant_task() throws Exception {
        try (var pool = Executors.newSingleThreadExecutor()) {
            Scheduler.Worker worker = AgentFlowSchedulers.contextAware(Schedulers.from(pool)).createWorker();
            try {
                AtomicReference<String> seen = new AtomicReference<>();
                CountDownLatch finished = new CountDownLatch(1);
                MDC.put("tenantId", "tenant-a");
                worker.schedule(() -> {
                    seen.set(MDC.get("tenantId"));
                    MDC.put("tenantId", "modified-in-worker");
                    finished.countDown();
                });
                assertThat(finished.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(seen.get()).isEqualTo("tenant-a");
                assertThat(pool.submit(() -> MDC.get("tenantId")).get(3, TimeUnit.SECONDS)).isNull();
                assertThat(MDC.get("tenantId")).isEqualTo("tenant-a");
            } finally {
                worker.dispose();
                MDC.remove("tenantId");
            }
        }
    }

    @Test
    void preserves_the_wrapped_schedulers_virtual_clock() {
        TestScheduler delegate = new TestScheduler();
        Scheduler wrapped = AgentFlowSchedulers.contextAware(delegate);
        Scheduler.Worker worker = wrapped.createWorker();
        try {
            delegate.advanceTimeBy(3, TimeUnit.SECONDS);
            assertThat(wrapped.now(TimeUnit.SECONDS)).isEqualTo(3);
            assertThat(worker.now(TimeUnit.SECONDS)).isEqualTo(3);
        } finally {
            worker.dispose();
        }
    }
}
