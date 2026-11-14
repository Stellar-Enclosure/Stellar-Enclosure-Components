package top.egon.cola.component.agentflow.agentic;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.agentflow.agentic.AgenticPlanCompiler.LeafOutcomeVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanCompiler.TransferVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.ConditionOpEnum;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.DependencyRefVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.LeafVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.LinkVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.LiteralVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.MergeEnum;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.OperationVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.PathVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.PredicateVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.RootTerminalVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.StrategyEnum;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.TerminationModeEnum;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.TerminationVO;
import top.egon.cola.component.agentflow.api.capability.CallPermitVO;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionContextVO;
import top.egon.cola.component.agentflow.api.session.SessionStore;
import top.egon.cola.component.agentflow.context.DefaultContextAssembler;
import top.egon.cola.component.agentflow.execution.DefaultAgentExecutor;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand.SchemaValidator;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand.TechnicalLimitsVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand.TemplateVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionResult.SideEffectOutcomeEnum;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionResult.StatusEnum;
import top.egon.cola.component.agentflow.execution.DefaultAgentExecutor.ExecutionInvocation;
import top.egon.cola.component.agentflow.execution.DefaultAgentExecutor.ExecutionRequestVO;
import top.egon.cola.component.common.core.exception.CommonException;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgenticPlanTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final Duration LIMIT = Duration.ofSeconds(10);

    private final AgenticPlanCompiler compiler = new AgenticPlanCompiler(CLOCK);

    @Test
    void executesEachOrderedLeafWithItsOwnReferencesAndMergesOutputs() {
        var order = new ArrayList<LeafVO>();
        var plan = collect(StrategyEnum.SEQUENTIAL, 1);

        var result = compiler.execute(plan, invocation(2), (leaf, round, shared) -> {
            order.add(leaf);
            assertThat(round).isEqualTo(1);
            return completed(Map.of("key", leaf.agentKey()), true);
        }, AgenticPlanTest::noShare);

        assertThat(order).containsExactlyElementsOf(plan.agents());
        assertThat(order).extracting(LeafVO::modelRef).containsExactly("left-model", "right-model");
        assertThat(order).extracting(LeafVO::instructionRef).doesNotHaveDuplicates();
        assertThat(order).extracting(LeafVO::toolRefs).doesNotHaveDuplicates();
        assertThat(result.status()).isEqualTo(StatusEnum.COMPLETED);
        assertThat(result.output()).containsExactlyInAnyOrderEntriesOf(
                Map.of("left", Map.of("key", "left"), "right", Map.of("key", "right")));
    }

    @Test
    void waitsForEveryParallelLeafAndHonorsEffectiveConcurrency() {
        var both = new CountDownLatch(2);
        var plan = collect(StrategyEnum.PARALLEL, 2);

        var result = compiler.execute(plan, invocation(2), (leaf, round, shared) -> {
            both.countDown();
            try {
                assertThat(both.await(3, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Parallel fixture interrupted", failure);
            }
            return completed(Map.of("key", leaf.agentKey()), true);
        }, AgenticPlanTest::noShare);
        assertThat(result.output()).containsOnlyKeys("left", "right");

        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        compiler.execute(plan, invocation(1), (leaf, round, shared) -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            Thread.yield();
            active.decrementAndGet();
            return completed(Map.of(), true);
        }, AgenticPlanTest::noShare);
        assertThat(peak.get()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidPathSegmentsDuplicatedEdgesAndIllegalMerge() {
        assertThatThrownBy(() -> leaf("left.output")).isInstanceOf(IllegalArgumentException.class);
        var edge = new LinkVO("left", "right");
        assertThatThrownBy(() -> output(StrategyEnum.HANDOFF, 2, List.of(edge, edge), List.of(), root()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> output(StrategyEnum.HANDOFF, 2, List.of(new LinkVO("left", "unknown")),
                List.of(), root())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgenticPlanVO(StrategyEnum.BOUNDED_LOOP, leaves(), List.of(), 2, 1,
                null, MergeEnum.COLLECT_BY_AGENT, List.of(), root())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> output(StrategyEnum.BOUNDED_LOOP, null, List.of(), List.of(), root()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsRootTerminalOnLastRoundBeforeApplyingBound() {
        var plan = output(StrategyEnum.BOUNDED_LOOP, 2, List.of(), List.of(), root());
        var observed = new ArrayList<String>();
        var terminal = compiler.execute(plan, invocation(1), (leaf, round, shared) -> {
            observed.add(round + ":" + leaf.agentKey());
            return completed(Map.of("round", round), round == 2 && "right".equals(leaf.agentKey()));
        }, AgenticPlanTest::noShare);
        assertThat(observed).containsExactly("1:left", "1:right", "2:left", "2:right");
        assertThat(terminal.status()).isEqualTo(StatusEnum.COMPLETED);
        assertThat(terminal.output()).containsEntry("round", 2L);
        assertThat(compiler.execute(plan, invocation(1), (leaf, round, shared) -> completed(Map.of(), false),
                AgenticPlanTest::noShare).errorCode()).isEqualTo("AGENTIC_LIMIT_EXCEEDED");
    }

    @Test
    void followsOnlyTrustedTypedHandoffsAndRequiresTheSelectedCompletedOutput() {
        var plan = output(StrategyEnum.HANDOFF, 2, List.of(new LinkVO("left", "right")), List.of(), root());
        var observed = new ArrayList<String>();
        var terminal = compiler.execute(plan, invocation(1), (leaf, round, shared) -> {
            observed.add(leaf.agentKey());
            if (round == 1) {
                return new LeafOutcomeVO(StatusEnum.COMPLETED, Map.of(), null, false,
                        new TransferVO("left", "right"), SideEffectOutcomeEnum.NONE);
            }
            return completed(Map.of("selected", leaf.agentKey()), true);
        }, AgenticPlanTest::noShare);

        assertThat(terminal.output()).containsEntry("selected", "right");
        assertThat(observed).containsExactly("left", "right");
        assertThat(compiler.execute(plan, invocation(1), (leaf, round, shared) ->
                completed(Map.of("text", "Transfer to right"), false), AgenticPlanTest::noShare).errorCode())
                .isEqualTo("INVALID_OUTPUT");
        assertThat(compiler.execute(plan, invocation(1), (leaf, round, shared) ->
                completed(Map.of(), true), AgenticPlanTest::noShare).errorCode())
                .isEqualTo("INVALID_OUTPUT");
        assertThat(compiler.execute(plan, invocation(1), (leaf, round, shared) ->
                new LeafOutcomeVO(StatusEnum.COMPLETED, Map.of(), null, false,
                        new TransferVO("right", "left"), SideEffectOutcomeEnum.NONE),
                AgenticPlanTest::noShare).errorCode()).isEqualTo("INVALID_OUTPUT");
    }

    @Test
    void explicitNullMatchesButMissingIsNotAPredicateMatchAndTypeMismatchFails() {
        var equalNull = new PredicateVO(TerminationModeEnum.PREDICATE,
                new OperationVO(ConditionOpEnum.EQ, List.of(new PathVO("agents.right.output.value"), new LiteralVO(null))));
        var plan = output(StrategyEnum.BOUNDED_LOOP, 2, List.of(), List.of(), equalNull);
        var pathChecks = new ArrayList<String>();
        var terminal = compiler.execute(plan, invocation(1), (leaf, round, shared) -> {
            if (round == 2 && "right".equals(leaf.agentKey())) {
                return completed(java.util.Collections.singletonMap("value", null), false);
            }
            return completed(Map.of(), false);
        }, (invocation, path) -> pathChecks.add(path));

        assertThat(terminal.status()).isEqualTo(StatusEnum.COMPLETED);
        assertThat(terminal.output()).containsEntry("value", null);
        assertThat(plan.valuePaths()).containsExactly("agents.right.output.value");
        assertThat(pathChecks).containsExactly("agents.right.output.value", "agents.right.output.value");
        var wrong = new PredicateVO(TerminationModeEnum.PREDICATE, new OperationVO(ConditionOpEnum.GT,
                List.of(new PathVO("agents.right.output.value"), new LiteralVO(1))));
        assertThat(compiler.execute(output(StrategyEnum.BOUNDED_LOOP, 2, List.of(), List.of(), wrong), invocation(1),
                (leaf, round, shared) -> completed(Map.of("value", "2"), false),
                (invocation, path) -> {}).errorCode()).isEqualTo("INVALID_OUTPUT");
    }

    @Test
    void revalidatesEverySharedReadAndDoesNotDispatchAfterRevocation() {
        var plan = new AgenticPlanVO(StrategyEnum.SEQUENTIAL, leaves(), List.of(), null, 1, "right",
                MergeEnum.OUTPUT_AGENT, List.of("input.items[0].value", "agents.left.output.draft"), root());
        var invocation = invocation(1, Map.of("items", List.of(Map.of("value", "safe"))));
        var checks = new ArrayList<String>();
        var calls = new ArrayList<String>();
        var terminal = compiler.execute(plan, invocation, (leaf, round, shared) -> {
            calls.add(leaf.agentKey());
            assertThat(shared).containsExactlyEntriesOf(Map.of("input.items[0].value", "safe"));
            return completed(Map.of("draft", "withheld"), true);
        }, (run, path) -> {
            assertThat(run.scope().tenantKey()).isEqualTo("tenant");
            checks.add(path);
            if ("agents.left.output.draft".equals(path)) {
                throw new CommonException(1, "PERMIT_REVOKED", "PERMIT_REVOKED");
            }
        });

        assertThat(calls).containsExactly("left");
        assertThat(checks).containsExactly("input.items[0].value", "input.items[0].value", "agents.left.output.draft");
        assertThat(terminal.status()).isEqualTo(StatusEnum.FAILED);
        assertThat(terminal.errorCode()).isEqualTo("PERMIT_REVOKED");
    }

    @Test
    void authorizesAnAvailableSourceBeforeTraversingAnAbsentField() {
        var predicate = new PredicateVO(TerminationModeEnum.PREDICATE,
                new OperationVO(ConditionOpEnum.AND, List.of(
                        new OperationVO(ConditionOpEnum.EQ, List.of(new PathVO("input.missing"), new LiteralVO(null))),
                        new OperationVO(ConditionOpEnum.GE, List.of(new PathVO("round"), new LiteralVO(1))))));
        var plan = output(StrategyEnum.BOUNDED_LOOP, 2, List.of(), List.of(), predicate);
        assertThat(plan.valuePaths()).containsExactly("input.missing");
        var result = compiler.execute(plan, invocation(1), (leaf, round, shared) -> completed(Map.of(), false),
                (invocation, path) -> { throw new CommonException(1, "PERMIT_REVOKED", "PERMIT_REVOKED"); });
        assertThat(result.errorCode()).isEqualTo("PERMIT_REVOKED");
    }

    @Test
    void failedLeafStopsFurtherSequentialDispatchAndPreservesOutcome() {
        var calls = new AtomicInteger();
        var result = compiler.execute(collect(StrategyEnum.SEQUENTIAL, 1), invocation(1), (leaf, round, shared) -> {
            calls.incrementAndGet();
            return new LeafOutcomeVO(StatusEnum.TIMED_OUT, null, "TOOL_TIMEOUT", false, null,
                    SideEffectOutcomeEnum.UNKNOWN);
        }, AgenticPlanTest::noShare);

        assertThat(calls).hasValue(1);
        assertThat(result.status()).isEqualTo(StatusEnum.TIMED_OUT);
        assertThat(result.sideEffectOutcome()).isEqualTo(SideEffectOutcomeEnum.UNKNOWN);
    }

    @Test
    void stopsBeforeDispatchWhenCurrentRunIsCancelled() {
        var invocation = invocation(1);
        doThrow(new CommonException(1, "EXECUTION_STOPPED", "EXECUTION_STOPPED")).when(invocation).requireRunning();
        var calls = new AtomicInteger();
        var result = compiler.execute(collect(StrategyEnum.SEQUENTIAL, 1), invocation, (leaf, round, shared) -> {
            calls.incrementAndGet();
            return completed(Map.of(), true);
        }, AgenticPlanTest::noShare);

        assertThat(calls).hasValue(0);
        assertThat(result.status()).isEqualTo(StatusEnum.CANCELLED);
    }

    @Test
    void requiresTrustedRootForSingleLeafAndReauthorizesBeforeEveryDispatch() {
        var plan = new AgenticPlanVO(StrategyEnum.REASONING, List.of(leaf("left")), List.of(), null, 1, "left",
                MergeEnum.OUTPUT_AGENT, List.of(), root());
        var invocation = invocation(1);
        assertThat(compiler.execute(plan, invocation, (leaf, round, shared) -> completed(Map.of("answer", "yes"), true),
                AgenticPlanTest::noShare).output()).containsEntry("answer", "yes");
        verify(invocation).requireCurrentCapability("fake", "EXECUTE");
        assertThat(compiler.execute(plan, invocation(1), (leaf, round, shared) -> completed(Map.of(), false),
                AgenticPlanTest::noShare).errorCode()).isEqualTo("INVALID_OUTPUT");
    }

    @Test
    void parallelSharingUsesCompletedPriorCohortsInsteadOfCompletionOrder() {
        var plan = new AgenticPlanVO(StrategyEnum.PARALLEL, List.of(leaf("left"), leaf("right"), leaf("later")),
                List.of(), null, 2, null, MergeEnum.COLLECT_BY_AGENT,
                List.of("agents.left.output.answer", "agents.right.output.answer"), root());
        var rightFinished = new CountDownLatch(1);
        var result = compiler.execute(plan, invocation(2), (leaf, round, shared) -> {
            if ("left".equals(leaf.agentKey())) {
                await(rightFinished);
                assertThat(shared).isEmpty();
            } else if ("right".equals(leaf.agentKey())) {
                assertThat(shared).isEmpty();
                rightFinished.countDown();
            } else {
                assertThat(shared.keySet()).containsExactly("agents.left.output.answer", "agents.right.output.answer");
                assertThat(shared).containsExactlyInAnyOrderEntriesOf(Map.of(
                        "agents.left.output.answer", "left", "agents.right.output.answer", "right"));
            }
            return completed(Map.of("answer", leaf.agentKey()), true);
        }, (invocation, path) -> {});
        assertThat(result.output()).containsOnlyKeys("left", "right", "later");
    }

    @Test
    void parallelFailureDoesNotStartQueuedLeafAndInterruptsEffectWithoutLosingFailure() throws Exception {
        var toolStarted = new CountDownLatch(1);
        var toolInterrupted = new CountDownLatch(1);
        var allowToolReturn = new CountDownLatch(1);
        var unexpected = new AtomicInteger();
        var plan = new AgenticPlanVO(StrategyEnum.PARALLEL, List.of(leaf("left"), leaf("right"), leaf("queued")),
                List.of(), null, 2, null, MergeEnum.COLLECT_BY_AGENT, List.of(), root());
        var fixtureRequest = invocation(2).request();
        try (var validatorFactory = Validation.buildDefaultValidatorFactory();
             var timers = Executors.newSingleThreadScheduledExecutor()) {
            var validator = validatorFactory.getValidator();
            var executor = new DefaultAgentExecutor(CLOCK, validator, new DefaultContextAssembler(CLOCK, validator),
                    timers, mock(SessionStore.class), Map.of("fake", invocation ->
                            compiler.execute(plan, invocation, (leaf, round, shared) -> {
                                if ("left".equals(leaf.agentKey())) {
                                    await(toolStarted);
                                    return new LeafOutcomeVO(StatusEnum.FAILED, null, "DEPENDENCY_UNAVAILABLE", false,
                                            null, SideEffectOutcomeEnum.NONE);
                                }
                                if ("right".equals(leaf.agentKey())) {
                                    invocation.toolCall("write", true, () -> {
                                        toolStarted.countDown();
                                        waitForFixtureRelease(allowToolReturn, toolInterrupted);
                                        return "late";
                                    });
                                } else {
                                    unexpected.incrementAndGet();
                                }
                                return completed(Map.of(), true);
                            }, AgenticPlanTest::noShare)));
            var scope = new ContextScopeVO("test", "tenant", "principal", "instance", "session", "execute");
            var host = new AgentExecutionContextVO(scope, CLOCK.instant().plus(LIMIT), new CompletableFuture<>(),
                    (query, exactScope, permit) -> { throw new AssertionError("Unexpected context read"); }, null,
                    (exactScope, ref, purpose) -> new CallPermitVO("permit", ref, exactScope.scopeHash(), 1, null,
                            CLOCK.instant().plus(LIMIT), () -> false, (permit, checkedPurpose) -> true));
            var command = new AgentExecutionCommand(fixtureRequest.executionId(), fixtureRequest.template(),
                    fixtureRequest.input(), fixtureRequest.parameters(), fixtureRequest.limits());
            try {
                var handle = executor.execute(command, host);
                var result = handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                await(toolInterrupted);
                assertThat(result.status()).isEqualTo(StatusEnum.FAILED);
                assertThat(result.errorCode()).isEqualTo("DEPENDENCY_UNAVAILABLE");
                assertThat(result.sideEffectOutcome()).isEqualTo(SideEffectOutcomeEnum.UNKNOWN);
                assertThat(unexpected).hasValue(0);
            } finally {
                allowToolReturn.countDown();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Agentic fixture interrupted", failure);
        }
    }

    private static void waitForFixtureRelease(CountDownLatch release, CountDownLatch interruptedSignal) {
        boolean interrupted = false;
        while (true) {
            try {
                awaitInterruptibly(release);
                break;
            } catch (InterruptedException stop) {
                interrupted = true;
                interruptedSignal.countDown();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitInterruptibly(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static AgenticPlanVO collect(StrategyEnum strategy, int parallelism) {
        return new AgenticPlanVO(strategy, leaves(), List.of(), null, parallelism, null, MergeEnum.COLLECT_BY_AGENT,
                List.of(), root());
    }

    private static AgenticPlanVO output(StrategyEnum strategy, Integer rounds, List<LinkVO> links, List<String> shared,
                                        TerminationVO termination) {
        return new AgenticPlanVO(strategy, leaves(), links, rounds, 1, "right", MergeEnum.OUTPUT_AGENT,
                shared, termination);
    }

    private static List<LeafVO> leaves() {
        return List.of(leaf("left"), leaf("right"));
    }

    private static LeafVO leaf(String key) {
        return new LeafVO(key, new DependencyRefVO(key + "-instructions", 1, "a".repeat(64)), key + "-model",
                List.of(new DependencyRefVO(key + "-tool", 1, "b".repeat(64))));
    }

    private static RootTerminalVO root() {
        return new RootTerminalVO(TerminationModeEnum.ROOT_TERMINAL);
    }

    private static LeafOutcomeVO completed(Map<String, Object> output, boolean trustedRootTerminal) {
        return new LeafOutcomeVO(StatusEnum.COMPLETED, output, null, trustedRootTerminal, null,
                SideEffectOutcomeEnum.NONE);
    }

    private static ExecutionInvocation invocation(int parallelism) {
        return invocation(parallelism, Map.of());
    }

    private static ExecutionInvocation invocation(int parallelism, Map<String, Object> input) {
        ExecutionInvocation invocation = mock(ExecutionInvocation.class);
        var template = new TemplateVO("template", 1, "c".repeat(64), "fake", Map.of(), mock(SchemaValidator.class));
        var limits = new TechnicalLimitsVO(LIMIT, LIMIT, LIMIT, LIMIT, 32, 32, parallelism, 1024, 128);
        when(invocation.scope()).thenReturn(new ContextScopeVO("test", "tenant", "principal", "instance",
                "session", "execute"));
        when(invocation.deadline()).thenReturn(CLOCK.instant().plus(LIMIT));
        when(invocation.request()).thenReturn(new ExecutionRequestVO("run", template, input, Map.of(), limits));
        return invocation;
    }

    private static void noShare(ExecutionInvocation invocation, String path) {
        throw new AssertionError("Unexpected shared read: " + path);
    }
}
