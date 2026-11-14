package top.egon.cola.component.agentflow.agentic;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.ConditionVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.LeafVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.LinkVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.LiteralVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.OperationVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.PathVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.PredicateVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionResult;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionResult.SideEffectOutcomeEnum;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionResult.StatusEnum;
import top.egon.cola.component.agentflow.execution.DefaultAgentExecutor.ExecutionInvocation;
import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Compiles and executes a closed plan through a trusted, per-leaf adapter. */
@Slf4j
@Validated
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class AgenticPlanCompiler {

    private static final Object MISSING = new Object();
    private static final Pattern PATH_SEGMENT = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)|\\[([0-9]+)\\]");
    private static final String INVALID_OUTPUT = "INVALID_OUTPUT";
    private static final String LIMIT_EXCEEDED = "AGENTIC_LIMIT_EXCEEDED";
    private static final Map<AgenticPlanVO.StrategyEnum, PlanStrategy> STRATEGIES = strategies();

    @Qualifier("agentExecutionClock")
    private final Clock clock;

    public AgentExecutionResult execute(@Valid @NotNull AgenticPlanVO plan, @NotNull ExecutionInvocation invocation,
                                        @NotNull LeafExecutor leafExecutor, @NotNull SharedValueAuthority authority) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(leafExecutor, "leafExecutor");
        Objects.requireNonNull(authority, "authority");

        var state = new PlanRun(plan, invocation, leafExecutor, authority, clock);
        try {
            state.requireRunning();
            LeafOutcomeVO outcome = STRATEGIES.get(plan.strategy()).execute(state);
            if (outcome.status() != StatusEnum.COMPLETED) {
                return state.result(outcome.status(), null, outcome.errorCode());
            }
            state.requireRunning();
            return state.result(StatusEnum.COMPLETED, state.mergeOutput(), null);
        } catch (CommonException failure) {
            String status = failure.getStatus();
            String code = validErrorCode(status) ? status : INVALID_OUTPUT;
            return switch (code) {
                case "DEADLINE_EXCEEDED" -> state.result(StatusEnum.TIMED_OUT, null, code);
                case "EXECUTION_STOPPED" -> state.result(StatusEnum.CANCELLED, null, "CANCELLED");
                default -> {
                    log.debug("Agentic plan stopped with errorCode={}", code);
                    yield state.result(StatusEnum.FAILED, null, code);
                }
            };
        } catch (IllegalArgumentException failure) {
            return state.result(StatusEnum.FAILED, null, INVALID_OUTPUT);
        }
    }

    @FunctionalInterface
    public interface LeafExecutor {

        @Valid @NotNull LeafOutcomeVO execute(@Valid @NotNull LeafVO leaf, int round, Map<String, Object> sharedValues);
    }

    @FunctionalInterface
    public interface SharedValueAuthority {

        /** Verify the current grant for the original source and exact path in this run. */
        void authorize(ExecutionInvocation invocation, String path);
    }

    public record LeafOutcomeVO(@NotNull StatusEnum status, Map<String, Object> output, String errorCode,
                                boolean trustedRootTerminal, @Valid TransferVO transfer,
                                @NotNull SideEffectOutcomeEnum sideEffectOutcome) {

        public LeafOutcomeVO {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(sideEffectOutcome, "sideEffectOutcome");
            if (status == StatusEnum.COMPLETED) {
                if (output == null || errorCode != null || (trustedRootTerminal && transfer != null)) {
                    throw invalidOutput();
                }
                output = AgentExecutionCommand.JsonValues.copyObject(output, AgentExecutionCommand.MAX_OUTPUT_BYTES);
            } else if (output != null || !validErrorCode(errorCode) || trustedRootTerminal || transfer != null) {
                throw invalidOutput();
            }
        }
    }

    public record TransferVO(@NotNull String source, @NotNull String target) {

        public TransferVO {
            if (!AgenticPlanVO.validAgentKey(source) || !AgenticPlanVO.validAgentKey(target) || source.equals(target)) {
                throw invalidOutput();
            }
        }
    }

    private static Map<AgenticPlanVO.StrategyEnum, PlanStrategy> strategies() {
        var strategies = new EnumMap<AgenticPlanVO.StrategyEnum, PlanStrategy>(AgenticPlanVO.StrategyEnum.class);
        strategies.put(AgenticPlanVO.StrategyEnum.REASONING, PlanRun::sequential);
        strategies.put(AgenticPlanVO.StrategyEnum.SEQUENTIAL, PlanRun::sequential);
        strategies.put(AgenticPlanVO.StrategyEnum.PARALLEL, PlanRun::parallel);
        strategies.put(AgenticPlanVO.StrategyEnum.BOUNDED_LOOP, PlanRun::boundedLoop);
        strategies.put(AgenticPlanVO.StrategyEnum.HANDOFF, PlanRun::handoff);
        return Map.copyOf(strategies);
    }

    @FunctionalInterface
    private interface PlanStrategy {

        LeafOutcomeVO execute(PlanRun run);
    }

    private static final class PlanRun {

        private final AgenticPlanVO plan;
        private final ExecutionInvocation invocation;
        private final LeafExecutor executor;
        private final SharedValueAuthority authority;
        private final Clock clock;
        private final Map<String, Map<String, Object>> outputs = new HashMap<>();
        private final AtomicBoolean abandoned = new AtomicBoolean();
        private final AtomicReference<SideEffectOutcomeEnum> effect = new AtomicReference<>(SideEffectOutcomeEnum.NONE);

        private PlanRun(AgenticPlanVO plan, ExecutionInvocation invocation, LeafExecutor executor,
                        SharedValueAuthority authority, Clock clock) {
            this.plan = plan;
            this.invocation = invocation;
            this.executor = executor;
            this.authority = authority;
            this.clock = clock;
        }

        private LeafOutcomeVO sequential() {
            LeafOutcomeVO last = null;
            for (LeafVO leaf : plan.agents()) {
                last = executeLeaf(leaf, 1, outputs);
                if (!completedRoot(last)) {
                    return last;
                }
                outputs.put(leaf.agentKey(), last.output());
            }
            return Objects.requireNonNull(last);
        }

        private LeafOutcomeVO parallel() {
            int parallelism = Math.min(plan.maxParallelism(), invocation.request().limits().maxParallelism());
            var workers = Executors.newFixedThreadPool(parallelism, Thread.ofVirtual().name("agentic-leaf-", 0).factory());
            var completion = new ExecutorCompletionService<CompletedLeafVO>(workers);
            var queued = plan.agents().iterator();
            int outstanding = 0;
            try {
                while (queued.hasNext() && outstanding < parallelism) {
                    submitLeaf(completion, queued.next(), Map.of());
                    outstanding++;
                }
                LeafOutcomeVO last = null;
                while (outstanding > 0) {
                    requireRunning();
                    CompletedLeafVO completed = completion.take().get();
                    outstanding--;
                    last = completed.outcome();
                    if (!completedRoot(last)) {
                        return last;
                    }
                    outputs.put(completed.agentKey(), last.output());

                    if (outstanding == 0 && queued.hasNext()) {
                        // A parallel cohort sees only completed outputs from earlier cohorts.
                        Map<String, Map<String, Object>> visible = Map.copyOf(outputs);
                        while (queued.hasNext() && outstanding < parallelism) {
                            submitLeaf(completion, queued.next(), visible);
                            outstanding++;
                        }
                    }
                }
                return Objects.requireNonNull(last);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                requireRunning();
                throw new CommonException(ResultCode.SYSTEM_ERROR.getCode(), "EXECUTION_STOPPED", "EXECUTION_STOPPED");
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof RuntimeException originatingFailure) {
                    throw originatingFailure;
                }
                if (failure.getCause() instanceof Error unexpectedFailure) {
                    throw unexpectedFailure;
                }
                throw new IllegalStateException("Agentic leaf execution failed");
            } finally {
                // Stop sibling dispatch before interrupting active adapters. The core owns their call fences.
                if (outstanding > 0 || queued.hasNext()) {
                    abandoned.set(true);
                }
                workers.shutdownNow();
            }
        }

        private void submitLeaf(ExecutorCompletionService<CompletedLeafVO> completion, LeafVO leaf,
                                Map<String, Map<String, Object>> visible) {
            completion.submit(() -> new CompletedLeafVO(leaf.agentKey(), executeLeaf(leaf, 1, visible)));
        }

        private LeafOutcomeVO boundedLoop() {
            for (int round = 1; round <= plan.maxRounds(); round++) {
                LeafOutcomeVO last = null;
                for (LeafVO leaf : plan.agents()) {
                    last = executeLeaf(leaf, round, outputs);
                    if (last.status() != StatusEnum.COMPLETED) {
                        return last;
                    }
                    if (last.transfer() != null) {
                        throw invalidOutput();
                    }
                    outputs.put(leaf.agentKey(), last.output());
                }
                last = Objects.requireNonNull(last);
                if (terminates(last, round)) {
                    return last;
                }
            }
            throw new CommonException(ResultCode.SYSTEM_ERROR.getCode(), LIMIT_EXCEEDED, LIMIT_EXCEEDED);
        }

        private LeafOutcomeVO handoff() {
            var leaves = new HashMap<String, LeafVO>();
            plan.agents().forEach(leaf -> leaves.put(leaf.agentKey(), leaf));
            LeafVO leaf = plan.agents().getFirst();
            for (int round = 1; round <= plan.maxRounds(); round++) {
                LeafOutcomeVO outcome = executeLeaf(leaf, round, outputs);
                if (outcome.status() != StatusEnum.COMPLETED) {
                    return outcome;
                }
                outputs.put(leaf.agentKey(), outcome.output());
                if (terminates(outcome, round)) {
                    return outcome;
                }
                if (round == plan.maxRounds()) {
                    throw new CommonException(ResultCode.SYSTEM_ERROR.getCode(), LIMIT_EXCEEDED, LIMIT_EXCEEDED);
                }
                TransferVO transfer = outcome.transfer();
                if (transfer == null || !leaf.agentKey().equals(transfer.source())
                        || !plan.links().contains(new LinkVO(transfer.source(), transfer.target()))) {
                    throw invalidOutput();
                }
                leaf = leaves.get(transfer.target());
            }
            throw invalidOutput();
        }

        private boolean terminates(LeafOutcomeVO outcome, int round) {
            requireRunning();
            if (plan.termination() instanceof PredicateVO predicate) {
                Object value = evaluate(predicate.condition(), round);
                return value != MISSING && booleanValue(value);
            }
            return outcome.trustedRootTerminal();
        }

        private boolean completedRoot(LeafOutcomeVO outcome) {
            if (outcome.status() != StatusEnum.COMPLETED) {
                return false;
            }
            if (!outcome.trustedRootTerminal() || outcome.transfer() != null) {
                throw invalidOutput();
            }
            return true;
        }

        private LeafOutcomeVO executeLeaf(LeafVO leaf, int round, Map<String, Map<String, Object>> visibleOutputs) {
            requireRunning();
            Map<String, Object> shared = new LinkedHashMap<>();
            for (String path : plan.sharedFields()) {
                Object value = resolve(path, visibleOutputs);
                if (value != MISSING) {
                    shared.put(path, value);
                }
            }
            requireRunning();
            invocation.requireCurrentCapability(invocation.request().template().executorRef(), "EXECUTE");
            LeafOutcomeVO outcome = executor.execute(leaf, round, Collections.unmodifiableMap(shared));
            if (outcome == null) {
                throw invalidOutput();
            }
            effect.accumulateAndGet(outcome.sideEffectOutcome(), AgenticPlanCompiler::mergeEffects);
            requireRunning();
            return outcome;
        }

        private void requireRunning() {
            if (!clock.instant().isBefore(invocation.deadline())) {
                throw new CommonException(ResultCode.SYSTEM_ERROR.getCode(), "DEADLINE_EXCEEDED", "DEADLINE_EXCEEDED");
            }
            if (abandoned.get() || Thread.currentThread().isInterrupted()) {
                throw new CommonException(ResultCode.SYSTEM_ERROR.getCode(), "EXECUTION_STOPPED", "EXECUTION_STOPPED");
            }
            invocation.requireRunning();
        }

        private Map<String, Object> mergeOutput() {
            Map<String, Object> merged;
            if (plan.merge() == AgenticPlanVO.MergeEnum.OUTPUT_AGENT) {
                merged = outputs.get(plan.outputAgentKey());
                if (merged == null) {
                    throw invalidOutput();
                }
            } else {
                merged = new LinkedHashMap<>();
                for (LeafVO leaf : plan.agents()) {
                    Map<String, Object> output = outputs.get(leaf.agentKey());
                    if (output == null) {
                        throw invalidOutput();
                    }
                    merged.put(leaf.agentKey(), output);
                }
            }
            Map<String, Object> frozen = AgentExecutionCommand.JsonValues.copyObject(merged, AgentExecutionCommand.MAX_OUTPUT_BYTES);
            try {
                invocation.request().template().schemaValidator().validateOutput(frozen);
            } catch (RuntimeException failure) {
                throw invalidOutput();
            }
            requireRunning();
            return frozen;
        }

        private AgentExecutionResult result(StatusEnum status, Map<String, Object> output, String errorCode) {
            return new AgentExecutionResult(invocation.request().executionId(), status, output, errorCode, effect.get(),
                    clock.instant().truncatedTo(ChronoUnit.MICROS));
        }

        private Object evaluate(ConditionVO condition, int round) {
            return switch (condition) {
                case PathVO path -> "round".equals(path.path()) ? (long) round : resolve(path.path(), outputs);
                case LiteralVO literal -> literal.literal();
                case OperationVO operation -> evaluateOperation(operation, round);
            };
        }

        private Object evaluateOperation(OperationVO operation, int round) {
            List<Object> arguments = operation.args().stream().map(child -> evaluate(child, round)).toList();
            if (arguments.contains(MISSING)) {
                return MISSING;
            }
            return switch (operation.op()) {
                case AND -> combineBooleans(arguments, true);
                case OR -> combineBooleans(arguments, false);
                case NOT -> !booleanValue(arguments.getFirst());
                case EQ -> equal(arguments.getFirst(), arguments.get(1));
                case NE -> !equal(arguments.getFirst(), arguments.get(1));
                case GT -> integer(arguments.getFirst()) > integer(arguments.get(1));
                case GE -> integer(arguments.getFirst()) >= integer(arguments.get(1));
                case LT -> integer(arguments.getFirst()) < integer(arguments.get(1));
                case LE -> integer(arguments.getFirst()) <= integer(arguments.get(1));
            };
        }

        private Object resolve(String path, Map<String, Map<String, Object>> visibleOutputs) {
            Object current;
            String relativePath;
            if (path.startsWith("input.")) {
                current = invocation.request().input();
                relativePath = path.substring(6);
            } else {
                int separator = path.indexOf('.', 7);
                current = visibleOutputs.get(path.substring(7, separator));
                if (current == null) {
                    return MISSING;
                }
                relativePath = path.substring(separator + 8);
            }

            requireRunning();
            authority.authorize(invocation, path);
            requireRunning();
            var segments = PATH_SEGMENT.matcher(relativePath);
            while (segments.find()) {
                String field = segments.group(1);
                if (field != null) {
                    if (!(current instanceof Map<?, ?> object)) {
                        throw invalidOutput();
                    }
                    if (!object.containsKey(field)) {
                        return MISSING;
                    }
                    current = object.get(field);
                } else {
                    int index = Integer.parseInt(segments.group(2));
                    if (!(current instanceof List<?> array)) {
                        throw invalidOutput();
                    }
                    if (index >= array.size()) {
                        return MISSING;
                    }
                    current = array.get(index);
                }
            }
            return current;
        }
    }

    private record CompletedLeafVO(String agentKey, LeafOutcomeVO outcome) {
    }

    private static boolean combineBooleans(List<Object> arguments, boolean conjunction) {
        boolean combined = conjunction;
        for (Object value : arguments) {
            boolean next = booleanValue(value);
            combined = conjunction ? combined && next : combined || next;
        }
        return combined;
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean result) {
            return result;
        }
        throw invalidOutput();
    }

    private static long integer(Object value) {
        if (value instanceof Long number) {
            return number;
        }
        throw invalidOutput();
    }

    private static boolean equal(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof Map<?, ?> && right instanceof Map<?, ?>
                || left instanceof List<?> && right instanceof List<?>
                || left.getClass().equals(right.getClass())) {
            return left.equals(right);
        }
        throw invalidOutput();
    }

    private static boolean validErrorCode(String code) {
        return code != null && code.matches("[A-Z][A-Z0-9_]{0,63}");
    }

    private static IllegalArgumentException invalidOutput() {
        return new IllegalArgumentException(INVALID_OUTPUT);
    }

    private static SideEffectOutcomeEnum mergeEffects(SideEffectOutcomeEnum left, SideEffectOutcomeEnum right) {
        if (left == SideEffectOutcomeEnum.UNKNOWN || right == SideEffectOutcomeEnum.UNKNOWN) {
            return SideEffectOutcomeEnum.UNKNOWN;
        }
        return left == SideEffectOutcomeEnum.CONFIRMED ? left : right;
    }
}
