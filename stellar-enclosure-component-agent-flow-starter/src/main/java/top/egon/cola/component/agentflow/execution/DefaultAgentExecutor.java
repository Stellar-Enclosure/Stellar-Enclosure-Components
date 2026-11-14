package top.egon.cola.component.agentflow.execution;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.agentflow.api.capability.CallPermitVO;
import top.egon.cola.component.agentflow.api.context.ContextQuery;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionContextVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionEvent;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionHandle;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionResult;
import top.egon.cola.component.agentflow.api.execution.AgentExecutor;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionResult.SideEffectOutcomeEnum;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionResult.StatusEnum;
import top.egon.cola.component.agentflow.api.session.SessionStateResult;
import top.egon.cola.component.agentflow.api.session.SessionStore;
import top.egon.cola.component.agentflow.api.session.SessionWriteCommand;
import top.egon.cola.component.agentflow.context.DefaultContextAssembler;
import top.egon.cola.component.agentflow.context.DefaultContextAssembler.ContextDataVO;
import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;

import java.lang.ref.WeakReference;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

@Slf4j
@Validated
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class DefaultAgentExecutor implements AgentExecutor {

    @Qualifier("agentExecutionClock")
    private final Clock clock;

    @Qualifier("defaultValidator")
    private final Validator validator;

    @Qualifier("agentContextAssembler")
    private final DefaultContextAssembler assembler;

    @Qualifier("agentExecutionTimers")
    private final ScheduledExecutorService timers;

    @Qualifier("agentSessionStore")
    private final SessionStore defaultSessionStore;

    @Qualifier("agentExecutionAdapters")
    private final Map<String, ExecutionAdapter> adapters;

    @Override
    public AgentExecutionHandle execute(AgentExecutionCommand command, AgentExecutionContextVO context) {
        Objects.requireNonNull(command, "command");
        validate(context);
        var boundary = new AgentExecutionCommand(command.getExecutionId(), command.getTemplate(), command.getInput(),
                command.getParameters(), command.getLimits());
        validate(boundary);
        ContextScopeVO.requireIdentifier(boundary.getExecutionId());

        var snapshot = new ExecutionRequestVO(boundary.getExecutionId(), boundary.getTemplate(),
                AgentExecutionCommand.JsonValues.copyObject(boundary.getInput(), AgentExecutionCommand.MAX_INPUT_BYTES),
                AgentExecutionCommand.JsonValues.copyObject(boundary.getParameters(),
                        AgentExecutionCommand.MAX_INPUT_BYTES), boundary.getLimits());
        snapshot.template().schemaValidator().validateInput(snapshot.input(), snapshot.parameters());
        Instant localDeadline = clock.instant().plus(snapshot.limits().totalTimeout());
        Instant deadline = localDeadline.isBefore(context.deadline()) ? localDeadline : context.deadline();
        var run = new ExecutionRun(snapshot, context, deadline);
        run.start(adapters.get(snapshot.template().executorRef()));
        return run;
    }

    private <T> void validate(T value) {
        Objects.requireNonNull(value, "Agent Flow boundary value");
        var violations = validator.validate(value);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    private static CommonException failure(String code) {
        return new CommonException(ResultCode.SYSTEM_ERROR.getCode(), code, code);
    }

    private static String safeErrorCode(Throwable failure) {
        if (failure instanceof CommonException common && common.getStatus() != null
                && common.getStatus().matches("[A-Z][A-Z0-9_]{0,63}")) {
            return common.getStatus();
        }
        return "EXECUTION_FAILED";
    }

    @FunctionalInterface
    public interface ExecutionAdapter {

        AgentExecutionResult execute(@NotNull @Valid ExecutionInvocation invocation);
    }

    public interface ExecutionInvocation {

        ExecutionRequestVO request();

        ContextScopeVO scope();

        Instant deadline();

        ContextDataVO context(@Valid ContextQuery query);

        SessionStateResult readSession();

        SessionStateResult writeSession(@Valid SessionWriteCommand command);

        <T> T modelCall(String capabilityRef, Supplier<T> call);

        default <T> T modelCallWithActivity(String capabilityRef, Function<ModelActivity, T> call) {
            throw failure("MODEL_ACTIVITY_UNAVAILABLE");
        }

        <T> T toolCall(String capabilityRef, boolean mayHaveSideEffects, Supplier<T> call);

        default <T> T toolCallWithDispatch(String capabilityRef, boolean mayHaveSideEffects,
                                           Function<ToolDispatch, T> call) {
            throw failure("TOOL_DISPATCH_UNAVAILABLE");
        }

        default void requireCurrentCapability(String capabilityRef, String purpose) {
            throw failure("PERMIT_UNAVAILABLE");
        }

        default void requireRunning() {
            throw failure("EXECUTION_STATE_UNAVAILABLE");
        }

        void emit(AgentExecutionEvent.TypeEnum type, Map<String, Object> payload);
    }

    @FunctionalInterface
    public interface ModelActivity {

        /** Call only when the provider sends a frame for this invocation. */
        void onFrame();
    }

    public interface ToolDispatch {

        Instant deadline();

        /** Recheck authorization and mark the business request immediately before sending it. */
        void beforeSend();
    }

    public record ExecutionRequestVO(String executionId, AgentExecutionCommand.TemplateVO template,
                                     Map<String, Object> input, Map<String, Object> parameters,
                                     AgentExecutionCommand.TechnicalLimitsVO limits) {
    }

    private final class ExecutionRun implements AgentExecutionHandle, ExecutionInvocation {

        private static final int BUFFER_SIZE = 64;

        private final ExecutionRequestVO request;
        private final AgentExecutionContextVO context;
        private final Instant deadline;
        private final Object stateLock = new Object();
        private final CompletableFuture<AgentExecutionResult> completed = new CompletableFuture<>();
        private final List<EventSubscription> subscribers = new ArrayList<>();
        private AgentExecutionResult terminal;
        private AgentExecutionEvent terminalEvent;
        private Thread worker;
        private ScheduledFuture<?> timeout;
        private long sequence;
        private int modelCalls;
        private int toolCalls;
        private final Set<ActiveCall> callsInFlight = new HashSet<>();
        private SideEffectOutcomeEnum observedEffect = SideEffectOutcomeEnum.NONE;
        private int contextBytes;

        private ExecutionRun(ExecutionRequestVO request, AgentExecutionContextVO context, Instant deadline) {
            this.request = request;
            this.context = context;
            this.deadline = deadline;
            this.contextBytes = (int) Math.min(64L * 1024, request.limits().contextWindowTokens());
        }

        private void start(ExecutionAdapter adapter) {
            // A host cancellation source can outlive completed runs.
            var reference = new WeakReference<>(this);
            context.cancellation().whenComplete((ignored, reason) -> {
                var run = reference.get();
                if (run != null) {
                    run.cancel("host cancellation");
                }
            });
            synchronized (stateLock) {
                if (terminal != null) {
                    return;
                }
                long delay = remainingNanos(clock.instant(), deadline);
                timeout = timers.schedule(() -> stop(StatusEnum.TIMED_OUT, "DEADLINE_EXCEEDED"), delay,
                        TimeUnit.NANOSECONDS);
                worker = Thread.ofVirtual().name("agent-flow-execution").unstarted(() -> executeAdapter(adapter));
                worker.start();
            }
        }

        private static long remainingNanos(Instant now, Instant deadline) {
            if (!now.isBefore(deadline)) {
                return 0;
            }
            return saturatedNanos(Duration.between(now, deadline));
        }

        private static long saturatedNanos(Duration duration) {
            try {
                return duration.toNanos();
            } catch (ArithmeticException overflow) {
                return Long.MAX_VALUE;
            }
        }

        private void executeAdapter(ExecutionAdapter adapter) {
            try {
                requireRunning();
                if (adapter == null) {
                    throw failure("EXECUTOR_UNAVAILABLE");
                }
                permit(request.template().executorRef(), "EXECUTE");
                var result = Objects.requireNonNull(adapter.execute(this), "root result");
                requireRunning();
                if (!request.executionId().equals(result.executionId())) {
                    throw failure("INVALID_ROOT_RESULT");
                }
                if (result.status() == StatusEnum.COMPLETED) {
                    try {
                        request.template().schemaValidator().validateOutput(result.output());
                    } catch (RuntimeException invalidOutput) {
                        throw failure("INVALID_OUTPUT");
                    }
                }
                requireRunning();
                finishRoot(result);
            } catch (RuntimeException failure) {
                String code = safeErrorCode(failure);
                log.debug("Agent execution ended with errorCode={}", code);
                finish(StatusEnum.FAILED, code, null, SideEffectOutcomeEnum.NONE, true, false);
            } catch (Error failure) {
                finish(StatusEnum.FAILED, "EXECUTION_FAILED", null, SideEffectOutcomeEnum.NONE, true, false);
                throw failure;
            }
        }

        @Override
        public Flow.Publisher<AgentExecutionEvent> events() {
            return this::subscribe;
        }

        @Override
        public CompletionStage<AgentExecutionResult> completion() {
            return completed.minimalCompletionStage();
        }

        @Override
        public void cancel(String reason) {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Cancellation requires a reason");
            }
            stop(StatusEnum.CANCELLED, "CANCELLED");
        }

        private void stop(StatusEnum status, String code) {
            finish(status, code, null, SideEffectOutcomeEnum.NONE, true, false);
        }

        @Override
        public ExecutionRequestVO request() {
            return request;
        }

        @Override
        public ContextScopeVO scope() {
            return context.scope();
        }

        @Override
        public Instant deadline() {
            return deadline;
        }

        @Override
        public ContextDataVO context(ContextQuery query) {
            Objects.requireNonNull(query, "query");
            var snapshot = new ContextQuery(query.getSourceRef(), query.getPurpose(), query.getMaxCharacters(),
                    query.getMaxFragments());
            validate(snapshot);
            CallPermitVO permit = permit(snapshot.getSourceRef(), snapshot.getPurpose());
            int budget;
            synchronized (stateLock) {
                budget = contextBytes;
            }
            ContextDataVO data = assembler.resolve(snapshot, scope(), context.contextSource(), permit, budget);
            requireRunning();
            synchronized (stateLock) {
                if (data.bytes() > contextBytes) {
                    throw failure("CONTEXT_LIMIT_EXCEEDED");
                }
                contextBytes -= data.bytes();
            }
            return data;
        }

        @Override
        public SessionStateResult readSession() {
            CallPermitVO permit = permit(SessionStore.CAPABILITY_REF, SessionStore.READ_PURPOSE);
            SessionStateResult result = selectedSessionStore().read(scope(), scope().sessionKey());
            permit.requireCurrent(scope(), SessionStore.CAPABILITY_REF, SessionStore.READ_PURPOSE, clock);
            requireRunning();
            validate(result);
            return result;
        }

        @Override
        public SessionStateResult writeSession(SessionWriteCommand command) {
            validate(command);
            CallPermitVO permit = permit(SessionStore.CAPABILITY_REF, SessionStore.WRITE_PURPOSE);
            SessionStateResult result = selectedSessionStore().compareAndSet(command, scope(), permit);
            requireRunning();
            validate(result);
            return result;
        }

        private SessionStore selectedSessionStore() {
            return context.sessionStore() == null ? defaultSessionStore : context.sessionStore();
        }

        @Override
        public <T> T modelCall(String capabilityRef, Supplier<T> call) {
            Objects.requireNonNull(call, "external call");
            return modelCallWithActivity(capabilityRef, activity -> call.get());
        }

        @Override
        public <T> T modelCallWithActivity(String capabilityRef, Function<ModelActivity, T> call) {
            Objects.requireNonNull(call, "external call");
            return dispatch(capabilityRef, "MODEL_CALL", true, false, active -> call.apply(() -> onModelFrame(active)));
        }

        @Override
        public <T> T toolCall(String capabilityRef, boolean mayHaveSideEffects, Supplier<T> call) {
            Objects.requireNonNull(call, "external call");
            return dispatch(capabilityRef, "TOOL_CALL", false, mayHaveSideEffects, active -> {
                markToolDispatched(active);
                return call.get();
            });
        }

        @Override
        public <T> T toolCallWithDispatch(String capabilityRef, boolean mayHaveSideEffects,
                                          Function<ToolDispatch, T> call) {
            Objects.requireNonNull(call, "external call");
            return dispatch(capabilityRef, "TOOL_CALL", false, mayHaveSideEffects, active -> call.apply(new ToolDispatch() {
                @Override
                public Instant deadline() {
                    return active.toolDeadline;
                }

                @Override
                public void beforeSend() {
                    permit(capabilityRef, "TOOL_CALL");
                    markToolDispatched(active);
                }
            }));
        }

        private <T> T dispatch(String capability, String purpose, boolean model, boolean mayHaveSideEffects,
                               Function<ActiveCall, T> call) {
            permit(capability, purpose);
            Duration limit = model ? request.limits().modelIdleTimeout() : request.limits().toolTimeout();
            var active = new ActiveCall(Thread.currentThread(), mayHaveSideEffects, model, saturatedNanos(limit));
            synchronized (stateLock) {
                requireActive();
                if (callsInFlight.size() >= request.limits().maxParallelism()
                        || (model && modelCalls >= request.limits().maxModelCalls())
                        || (!model && toolCalls >= request.limits().maxToolCalls())) {
                    throw failure("EXECUTION_LIMIT_EXCEEDED");
                }
                if (model) {
                    modelCalls++;
                } else {
                    toolCalls++;
                }
                callsInFlight.add(active);
            }

            boolean succeeded = false;
            try {
                requireRunning();
                synchronized (stateLock) {
                    requireActive();
                    if (!model) {
                        Instant now = clock.instant();
                        active.toolDeadline = Duration.between(now, deadline).compareTo(limit) <= 0
                                ? deadline : now.plus(limit);
                    }
                    active.lastActivityNanos = System.nanoTime();
                    scheduleCallTimeout(active, active.limitNanos);
                }
                T result = call.apply(active);
                succeeded = true;
                return result;
            } finally {
                FinishActions actions = null;
                synchronized (stateLock) {
                    if (terminal == null && active.timeout != null) {
                        actions = finishExpiredCallLocked(active, System.nanoTime());
                    }
                    callsInFlight.remove(active);
                    active.cancelTimeout();
                    if (active.dispatched && mayHaveSideEffects) {
                        observedEffect = mergeEffects(observedEffect,
                                succeeded ? SideEffectOutcomeEnum.CONFIRMED : SideEffectOutcomeEnum.UNKNOWN);
                    }
                }
                if (actions != null) {
                    publishCompletion(actions);
                }
                if (succeeded) {
                    requireRunning();
                }
            }
        }

        private void markToolDispatched(ActiveCall active) {
            FinishActions actions;
            synchronized (stateLock) {
                requireActive();
                if (!callsInFlight.contains(active) || active.dispatched) {
                    throw failure("INVALID_TOOL_DISPATCH");
                }
                actions = finishExpiredCallLocked(active, System.nanoTime());
                if (actions == null) {
                    active.dispatched = true;
                    return;
                }
            }
            publishCompletion(actions);
            throw failure("EXECUTION_STOPPED");
        }

        private void onModelFrame(ActiveCall active) {
            FinishActions actions;
            synchronized (stateLock) {
                requireActive();
                if (!active.model || !callsInFlight.contains(active)) {
                    throw failure("MODEL_CALL_STOPPED");
                }
                long observedAt = System.nanoTime();
                actions = finishExpiredCallLocked(active, observedAt);
                if (actions == null) {
                    active.lastActivityNanos = observedAt;
                    return;
                }
            }
            publishCompletion(actions);
            throw failure("EXECUTION_STOPPED");
        }

        private void scheduleCallTimeout(ActiveCall active, long delayNanos) {
            active.timeout = timers.schedule(() -> expireCall(active), delayNanos, TimeUnit.NANOSECONDS);
        }

        private void expireCall(ActiveCall active) {
            FinishActions actions;
            synchronized (stateLock) {
                if (terminal != null || !callsInFlight.contains(active)) {
                    return;
                }
                long nowNanos = System.nanoTime();
                actions = finishExpiredCallLocked(active, nowNanos);
                if (actions == null) {
                    scheduleCallTimeout(active, active.remainingNanos(nowNanos));
                }
            }
            if (actions != null) {
                publishCompletion(actions);
            }
        }

        private FinishActions finishExpiredCallLocked(ActiveCall active, long nowNanos) {
            if (!clock.instant().isBefore(deadline)) {
                return finishLocked(StatusEnum.TIMED_OUT, "DEADLINE_EXCEEDED", null, SideEffectOutcomeEnum.NONE,
                        true, false);
            }
            if (active.remainingNanos(nowNanos) <= 0) {
                String code = active.model ? "MODEL_IDLE_TIMEOUT" : "TOOL_TIMEOUT";
                return finishLocked(StatusEnum.TIMED_OUT, code, null, SideEffectOutcomeEnum.NONE, true, false);
            }
            return null;
        }

        @Override
        public void requireCurrentCapability(String capabilityRef, String purpose) {
            permit(capabilityRef, purpose);
        }

        private CallPermitVO permit(String capability, String purpose) {
            ContextScopeVO.requireIdentifier(capability);
            requireRunning();
            CallPermitVO permit = context.callPermitProvider().requirePermit(scope(), capability, purpose);
            if (permit == null) {
                throw failure("PERMIT_UNAVAILABLE");
            }
            permit.requireCurrent(scope(), capability, purpose, clock);
            requireRunning();
            return permit;
        }

        @Override
        public void requireRunning() {
            if (!clock.instant().isBefore(deadline)) {
                stop(StatusEnum.TIMED_OUT, "DEADLINE_EXCEEDED");
            }
            synchronized (stateLock) {
                requireActive();
            }
        }

        private void requireActive() {
            if (terminal != null) {
                throw failure("EXECUTION_STOPPED");
            }
        }

        @Override
        public void emit(AgentExecutionEvent.TypeEnum type, Map<String, Object> payload) {
            Objects.requireNonNull(type, "event type");
            if (type == AgentExecutionEvent.TypeEnum.TERMINAL) {
                throw failure("INVALID_ADAPTER_EVENT");
            }
            requireRunning();
            FinishActions actions = null;
            List<EventSubscription> toDrain;
            synchronized (stateLock) {
                requireActive();
                toDrain = List.copyOf(subscribers);
                if (toDrain.stream().anyMatch(subscription -> subscription.queue.size() >= BUFFER_SIZE)) {
                    actions = finishLocked(StatusEnum.FAILED, "BACKPRESSURE_LIMIT", null,
                            SideEffectOutcomeEnum.NONE, true, true);
                } else {
                    var event = new AgentExecutionEvent(request.executionId(), ++sequence, type, now(), payload);
                    for (EventSubscription subscription : toDrain) {
                        subscription.queue.addLast(event);
                    }
                }
            }
            if (actions != null) {
                publishCompletion(actions);
                throw failure("BACKPRESSURE_LIMIT");
            }
            toDrain.forEach(EventSubscription::startDrain);
        }

        private void finishRoot(AgentExecutionResult result) {
            FinishActions actions;
            synchronized (stateLock) {
                requireActive();
                if (!clock.instant().isBefore(deadline)) {
                    actions = finishLocked(StatusEnum.TIMED_OUT, "DEADLINE_EXCEEDED", null,
                            SideEffectOutcomeEnum.NONE, true, false);
                } else if (result.status() == StatusEnum.COMPLETED && !callsInFlight.isEmpty()) {
                    actions = finishLocked(StatusEnum.FAILED, "INVALID_ROOT_RESULT", null,
                            SideEffectOutcomeEnum.NONE, true, false);
                } else {
                    actions = finishLocked(result.status(), result.errorCode(), result.output(),
                            result.sideEffectOutcome(), !callsInFlight.isEmpty(), false);
                }
            }
            publishCompletion(actions);
        }

        private void finish(StatusEnum status, String code, Map<String, Object> output, SideEffectOutcomeEnum effect,
                            boolean interrupt, boolean dropPending) {
            FinishActions actions;
            synchronized (stateLock) {
                actions = finishLocked(status, code, output, effect, interrupt, dropPending);
            }
            if (actions != null) {
                publishCompletion(actions);
            }
        }

        private FinishActions finishLocked(StatusEnum status, String code, Map<String, Object> output,
                                           SideEffectOutcomeEnum effect, boolean interrupt, boolean dropPending) {
            if (terminal != null) {
                return null;
            }
            boolean unresolvedEffect = callsInFlight.stream().anyMatch(call -> call.dispatched
                    && call.mayHaveSideEffects);
            callsInFlight.forEach(ActiveCall::cancelTimeout);
            var combined = unresolvedEffect ? SideEffectOutcomeEnum.UNKNOWN : mergeEffects(observedEffect, effect);
            terminal = new AgentExecutionResult(request.executionId(), status, output, code, combined, now());
            var terminalPayload = new LinkedHashMap<String, Object>();
            terminalPayload.put("status", status.code());
            terminalPayload.put("errorCode", code);
            terminalPayload.put("sideEffectOutcome", combined.code());
            terminalEvent = new AgentExecutionEvent(request.executionId(), ++sequence,
                    AgentExecutionEvent.TypeEnum.TERMINAL, terminal.finishedAt(), terminalPayload);
            if (dropPending) {
                subscribers.forEach(subscription -> subscription.queue.clear());
            }
            var toInterrupt = new HashSet<Thread>();
            if (interrupt) {
                if (worker != null) {
                    toInterrupt.add(worker);
                }
                callsInFlight.forEach(call -> toInterrupt.add(call.thread));
            }
            return new FinishActions(terminal, List.copyOf(subscribers), timeout, Set.copyOf(toInterrupt));
        }

        private void publishCompletion(FinishActions actions) {
            if (actions.timeout() != null) {
                actions.timeout().cancel(false);
            }
            for (Thread thread : actions.toInterrupt()) {
                if (thread != Thread.currentThread()) {
                    thread.interrupt();
                }
            }
            actions.toDrain().forEach(EventSubscription::startDrain);
            // Completion callbacks are user code and must never block the shared deadline scheduler.
            Thread.startVirtualThread(() -> completed.complete(actions.result()));
        }

        private Instant now() {
            return clock.instant().truncatedTo(ChronoUnit.MICROS);
        }

        private void subscribe(Flow.Subscriber<? super AgentExecutionEvent> subscriber) {
            Objects.requireNonNull(subscriber, "subscriber");
            var subscription = new EventSubscription(subscriber);
            synchronized (stateLock) {
                subscribers.add(subscription);
            }
            try {
                subscriber.onSubscribe(subscription);
            } catch (RuntimeException failure) {
                subscription.cancel();
                return;
            }
            synchronized (stateLock) {
                subscription.subscribed = true;
            }
            subscription.startDrain();
        }

        private final class EventSubscription implements Flow.Subscription {

            private final Flow.Subscriber<? super AgentExecutionEvent> subscriber;
            private final ArrayDeque<AgentExecutionEvent> queue = new ArrayDeque<>();
            private long demand;
            private boolean subscribed;
            private boolean draining;
            private boolean stopped;
            private Throwable requestFailure;

            private EventSubscription(Flow.Subscriber<? super AgentExecutionEvent> subscriber) {
                this.subscriber = subscriber;
            }

            @Override
            public void request(long amount) {
                synchronized (stateLock) {
                    if (stopped || requestFailure != null) {
                        return;
                    }
                    if (amount <= 0) {
                        requestFailure = new IllegalArgumentException("Flow demand must be positive");
                        queue.clear();
                    } else {
                        demand = Long.MAX_VALUE - demand < amount ? Long.MAX_VALUE : demand + amount;
                    }
                }
                startDrain();
            }

            @Override
            public void cancel() {
                synchronized (stateLock) {
                    stopLocked();
                }
            }

            private void startDrain() {
                synchronized (stateLock) {
                    if (!subscribed || stopped || draining) {
                        return;
                    }
                    draining = true;
                }
                Thread.startVirtualThread(this::drain);
            }

            private void drain() {
                while (true) {
                    AgentExecutionEvent event;
                    boolean terminalDelivery = false;
                    Throwable badRequest;
                    synchronized (stateLock) {
                        badRequest = requestFailure;
                        if (stopped || (badRequest == null
                                && (demand == 0 || (queue.isEmpty() && terminalEvent == null)))) {
                            draining = false;
                            return;
                        }
                        if (badRequest != null) {
                            stopLocked();
                            event = null;
                        } else if (queue.isEmpty()) {
                            event = terminalEvent;
                            terminalDelivery = true;
                            stopLocked();
                        } else {
                            event = queue.removeFirst();
                            if (demand != Long.MAX_VALUE) {
                                demand--;
                            }
                        }
                    }
                    try {
                        if (badRequest != null) {
                            subscriber.onError(badRequest);
                            return;
                        }
                        subscriber.onNext(event);
                        if (terminalDelivery) {
                            subscriber.onComplete();
                            return;
                        }
                    } catch (RuntimeException failure) {
                        cancel();
                        return;
                    }
                }
            }

            private void stopLocked() {
                stopped = true;
                subscribers.remove(this);
                queue.clear();
            }
        }

        private record FinishActions(AgentExecutionResult result, List<EventSubscription> toDrain,
                                     ScheduledFuture<?> timeout, Set<Thread> toInterrupt) {
        }

        private static final class ActiveCall {

            private final Thread thread;
            private final boolean mayHaveSideEffects;
            private final boolean model;
            private final long limitNanos;
            private boolean dispatched;
            private long lastActivityNanos;
            private Instant toolDeadline;
            private ScheduledFuture<?> timeout;

            private ActiveCall(Thread thread, boolean mayHaveSideEffects, boolean model, long limitNanos) {
                this.thread = thread;
                this.mayHaveSideEffects = mayHaveSideEffects;
                this.model = model;
                this.limitNanos = limitNanos;
            }

            private long remainingNanos(long nowNanos) {
                return limitNanos - (nowNanos - lastActivityNanos);
            }

            private void cancelTimeout() {
                if (timeout != null) {
                    timeout.cancel(false);
                }
            }
        }
    }

    private static SideEffectOutcomeEnum mergeEffects(SideEffectOutcomeEnum left, SideEffectOutcomeEnum right) {
        if (left == SideEffectOutcomeEnum.UNKNOWN || right == SideEffectOutcomeEnum.UNKNOWN) {
            return SideEffectOutcomeEnum.UNKNOWN;
        }
        return left == SideEffectOutcomeEnum.CONFIRMED ? left : right;
    }
}
