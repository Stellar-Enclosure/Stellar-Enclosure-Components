package top.egon.cola.component.agentflow.runtime;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import top.egon.cola.component.common.trace.TraceContext;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Preserves invocation context when ADK hands parallel branches to a scheduler. */
public final class AgentFlowSchedulers {
    private static final Scheduler CONTEXT_IO = contextAware(Schedulers.io());

    private AgentFlowSchedulers() {
    }

    public static Scheduler contextAwareIo() {
        return CONTEXT_IO;
    }

    public static Scheduler contextAware(Scheduler delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new Scheduler() {
            @Override
            public Worker createWorker() {
                Worker worker = delegate.createWorker();
                return new Worker() {
                    @Override
                    public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
                        Context otel = Context.current();
                        AgentFlowInMemoryRunner.InvocationCancellation invocation =
                                AgentFlowInMemoryRunner.currentCancellation();
                        TraceContext trace = invocation == null ? TraceContext.capture() : invocation.traceContext();
                        return worker.schedule(() -> {
                            try (Scope ignored = otel.makeCurrent(); TraceContext.Scope scope = trace.open()) {
                                task.run();
                            }
                        }, delay, unit);
                    }

                    @Override public void dispose() { worker.dispose(); }
                    @Override public boolean isDisposed() { return worker.isDisposed(); }
                };
            }
        };
    }
}
