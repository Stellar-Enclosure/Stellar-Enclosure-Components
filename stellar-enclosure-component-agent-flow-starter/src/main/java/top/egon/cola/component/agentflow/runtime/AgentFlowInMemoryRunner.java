package top.egon.cola.component.agentflow.runtime;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.Plugin;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.telemetry.Tracing;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import top.egon.cola.component.common.trace.TraceContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Carries one cancellation signal through ADK's invocation context. ADK 1.13 caches each
 * model step, so disposing the outer subscriber alone does not dispose the model subscription.
 */
public class AgentFlowInMemoryRunner extends InMemoryRunner {

    private static final ContextKey<InvocationCancellation> CANCELLATION_KEY =
            ContextKey.named("agent-flow-invocation-cancellation");
    private static final ContextKey<TraceContext> TRACE_KEY = ContextKey.named("agent-flow-trace-context");

    private final Object monitor = new Object();
    private final Set<InvocationCancellation> active = ConcurrentHashMap.newKeySet();
    private boolean closing;
    private final Completable closeOperation = Completable.defer(() -> {
        List<InvocationCancellation> pending;
        synchronized (monitor) {
            closing = true;
            pending = List.copyOf(active);
        }
        pending.forEach(InvocationCancellation::cancel);
        return super.close();
    }).cache();

    public AgentFlowInMemoryRunner(BaseAgent agent, String appName) {
        this(agent, appName, List.of());
    }

    /** Adds host governance plugins after the starter's cancellation guard. */
    public AgentFlowInMemoryRunner(BaseAgent agent, String appName, List<Plugin> plugins) {
        super(agent, appName, withCancellationPlugin(plugins));
    }

    private static List<Plugin> withCancellationPlugin(List<Plugin> plugins) {
        List<Plugin> all = new ArrayList<>();
        all.add(new CancellationPlugin());
        all.addAll(List.copyOf(plugins));
        return List.copyOf(all);
    }

    @Override
    public Flowable<Event> runAsync(String userId, String sessionId, Content message, RunConfig config,
                                    Map<String, Object> stateDelta) {
        return Flowable.defer(() -> {
            Context context = Context.current().with(TRACE_KEY, TraceContext.capture());
            try (Scope ignored = context.makeCurrent()) {
                return super.runAsync(userId, sessionId, message, config, stateDelta)
                        .compose(Tracing.withContext(context));
            }
        });
    }

    @Override
    protected Flowable<Event> runAsyncImpl(Session session, Content message, RunConfig config,
                                           Map<String, Object> stateDelta) {
        return Flowable.defer(() -> {
            TraceContext captured = Context.current().get(TRACE_KEY);
            InvocationCancellation cancellation = new InvocationCancellation(
                    captured == null ? TraceContext.capture() : captured);
            synchronized (monitor) {
                if (closing) {
                    return Flowable.error(new IllegalStateException("Agent Flow runner is closing"));
                }
                active.add(cancellation);
            }
            Context context = Context.current().with(CANCELLATION_KEY, cancellation);
            try (Scope ignored = context.makeCurrent()) {
                return super.runAsyncImpl(session, message, config, stateDelta)
                        .compose(Tracing.withContext(context))
                        .doFinally(() -> {
                            cancellation.cancel();
                            active.remove(cancellation);
                        });
            } catch (RuntimeException failure) {
                active.remove(cancellation);
                cancellation.cancel();
                throw failure;
            }
        });
    }

    @Override
    public Completable close() {
        return closeOperation;
    }

    static InvocationCancellation currentCancellation() {
        return Context.current().get(CANCELLATION_KEY);
    }

    static void requireActive() {
        InvocationCancellation cancellation = currentCancellation();
        if (cancellation != null && cancellation.cancelled.get()) {
            throw new CancellationException("Agent Flow invocation was cancelled");
        }
    }

    static final class InvocationCancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CompletableSubject signal = CompletableSubject.create();
        private final TraceContext traceContext;

        private InvocationCancellation(TraceContext traceContext) {
            this.traceContext = traceContext;
        }

        Completable signal() {
            return signal;
        }

        TraceContext traceContext() {
            return traceContext;
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        private void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                signal.onComplete();
            }
        }
    }

    private static final class CancellationPlugin implements Plugin {
        @Override
        public String getName() {
            return "agent-flow-cancellation";
        }

        @Override
        public Maybe<LlmResponse> beforeModelCallback(CallbackContext context, LlmRequest.Builder request) {
            return Maybe.defer(() -> {
                InvocationCancellation cancellation = currentCancellation();
                if (cancellation == null || !cancellation.isCancelled()) {
                    return Maybe.empty();
                }
                context.eventActions().setEndInvocation(true);
                return Maybe.just(LlmResponse.builder().interrupted(true).build());
            });
        }

        @Override
        public Maybe<Map<String, Object>> beforeToolCallback(
                BaseTool tool, Map<String, Object> arguments, ToolContext context) {
            return Maybe.defer(() -> {
                InvocationCancellation cancellation = currentCancellation();
                if (cancellation == null || !cancellation.isCancelled()) {
                    return Maybe.empty();
                }
                context.actions().setEndInvocation(true);
                return Maybe.just(Map.of("error", "AGENT_FLOW_CANCELLED"));
            });
        }
    }
}
