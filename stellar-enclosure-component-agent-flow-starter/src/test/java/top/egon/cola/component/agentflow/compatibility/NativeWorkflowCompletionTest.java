package top.egon.cola.component.agentflow.compatibility;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LoopAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.parallel.ResourceLock;
import top.egon.cola.component.agentflow.api.AgentFlowExecutionCommand;
import top.egon.cola.component.agentflow.api.AgentFlowSessionCommand;
import top.egon.cola.component.agentflow.autoconfigure.AgentFlowProperties;
import top.egon.cola.component.agentflow.common.exception.AgentFlowExecutionTimeoutException;
import top.egon.cola.component.agentflow.execution.AgentFlowSessionExecutionGuard;
import top.egon.cola.component.agentflow.execution.DefaultAgentFlowService;
import top.egon.cola.component.agentflow.runtime.AgentFlowRuntimeBO;
import top.egon.cola.component.agentflow.runtime.DefaultAgentFlowRegistry;
import top.egon.cola.component.common.core.validation.ValidationUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static top.egon.cola.component.agentflow.compatibility.AdkCompatibilityFixtures.*;

/** Native workflow probes plus readiness checks for the existing facade's stream/timeout policy. */
@Isolated("This test class temporarily installs a virtual RxJava computation scheduler")
@ResourceLock("rxjava-computation-scheduler")
@Timeout(10)
class NativeWorkflowCompletionTest {

    @Test
    void w01_sequential_runs_all_children_and_each_child_can_emit_a_final_response() {
        ScriptedChatModel first = new ScriptedChatModel();
        ScriptedChatModel second = new ScriptedChatModel();
        first.response = prompt -> text("first result.", "stop", true);
        second.response = prompt -> text("second result.", "stop", true);
        BaseAgent root = SequentialAgent.builder().name("sequence")
                .subAgents(agent("first", first), agent("second", second)).build();

        try (NativeRun run = new NativeRun(root)) {
            List<Event> events = run.run(false).toList().timeout(3, TimeUnit.SECONDS).blockingGet();

            assertThat(first.calls).hasValue(1);
            assertThat(second.calls).hasValue(1);
            assertThat(events.stream().filter(Event::finalResponse).map(Event::author).toList())
                    .containsExactly("first", "second");
            assertThat(run.storedSession().state())
                    .containsEntry("first_output", "first result.")
                    .containsEntry("second_output", "second result.");
        }
    }

    @Test
    void w02_parallel_runs_both_children_and_retains_distinct_output_keys() {
        ScriptedChatModel first = new ScriptedChatModel();
        ScriptedChatModel second = new ScriptedChatModel();
        BaseAgent root = ParallelAgent.builder().name("parallel")
                .subAgents(agent("first", first), agent("second", second)).build();

        try (NativeRun run = new NativeRun(root)) {
            List<Event> events = run.run(false).toList().timeout(3, TimeUnit.SECONDS).blockingGet();

            assertThat(first.calls).hasValue(1);
            assertThat(second.calls).hasValue(1);
            assertThat(events.stream().filter(Event::finalResponse).map(Event::author).toList())
                    .containsExactlyInAnyOrder("first", "second");
            assertThat(run.storedSession().state()).containsKeys("first_output", "second_output");
        }
    }

    @Test
    void w03_loop_obeys_native_max_iterations() {
        ScriptedChatModel model = new ScriptedChatModel();
        BaseAgent root = LoopAgent.builder().name("loop").maxIterations(3)
                .subAgents(agent("loop_child", model)).build();

        try (NativeRun run = new NativeRun(root)) {
            List<Event> events = run.run(false).toList().timeout(3, TimeUnit.SECONDS).blockingGet();

            assertThat(model.calls).hasValue(3);
            assertThat(events.stream().filter(Event::finalResponse).count()).isEqualTo(3);
        }
    }

    @Test
    void w04_existing_execute_stream_uses_the_model_streaming_api() {
        ScriptedChatModel model = new ScriptedChatModel();
        InMemoryRunner runner = new InMemoryRunner(agent("facade_stream", model), APP);
        try (var validators = Validation.buildDefaultValidatorFactory();
             DefaultAgentFlowService service = service(runner, Duration.ofSeconds(2), validators.getValidator())) {
            var session = service.createSession(new AgentFlowSessionCommand(APP, USER, null));
            service.executeStream(new AgentFlowExecutionCommand(APP, USER, session.sessionId(), input()))
                    .test().awaitDone(3, TimeUnit.SECONDS).assertComplete().assertNoErrors();

            assertThat(model.streams).as("executeStream must request ADK StreamingMode.SSE").hasValue(1);
            assertThat(model.calls).hasValue(0);
        }
    }

    @Test
    void w05_execution_timeout_is_a_total_deadline_despite_continuing_progress() {
        TestScheduler scheduler = new TestScheduler();
        var previous = RxJavaPlugins.getComputationSchedulerHandler();
        RxJavaPlugins.setComputationSchedulerHandler(ignored -> scheduler);
        InMemoryRunner runner = new InMemoryRunner(new ProgressAgent(scheduler), APP);
        try (var validators = Validation.buildDefaultValidatorFactory();
             DefaultAgentFlowService service = service(runner, Duration.ofMillis(20), validators.getValidator())) {
            var session = service.createSession(new AgentFlowSessionCommand(APP, USER, null));
            var subscriber = service.executeStream(
                    new AgentFlowExecutionCommand(APP, USER, session.sessionId(), input())).test();
            try {
                scheduler.advanceTimeBy(65, TimeUnit.MILLISECONDS);
                subscriber.assertError(AgentFlowExecutionTimeoutException.class);
            } finally {
                subscriber.cancel();
            }
        } finally {
            RxJavaPlugins.setComputationSchedulerHandler(previous);
        }
    }

    private static DefaultAgentFlowService service(InMemoryRunner runner, Duration timeout, Validator validator) {
        Clock clock = Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC);
        AgentFlowRuntimeBO runtime = new AgentFlowRuntimeBO(
                APP, runner.agent().name(), "fixtureChatModel", MODEL, runner.agent(), runner, clock.instant());
        DefaultAgentFlowRegistry registry = new DefaultAgentFlowRegistry(
                Map.of(APP, runtime), Duration.ofSeconds(1));
        return new DefaultAgentFlowService(registry, new AgentFlowSessionExecutionGuard(),
                new AgentFlowProperties(true, timeout, Duration.ofSeconds(1), Map.of()),
                new ValidationUtils(validator), clock);
    }

    private static final class ProgressAgent extends BaseAgent {
        private final TestScheduler scheduler;

        private ProgressAgent(TestScheduler scheduler) {
            super("progress_agent", "Bounded progress fixture.", List.of(), List.of(), List.of());
            this.scheduler = scheduler;
        }

        @Override
        protected Flowable<Event> runAsyncImpl(InvocationContext context) {
            return Flowable.interval(10, TimeUnit.MILLISECONDS, scheduler).take(6)
                    .map(index -> Event.builder().id("progress_" + index).author(name())
                            .invocationId(context.invocationId()).partial(true)
                            .content(Content.fromParts(Part.fromText("progress"))).build());
        }

        @Override
        protected Flowable<Event> runLiveImpl(InvocationContext context) {
            return Flowable.error(new UnsupportedOperationException("fixture has no live mode"));
        }
    }
}
