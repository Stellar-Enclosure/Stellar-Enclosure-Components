package top.egon.cola.component.agentflow.adapter.adk;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import top.egon.cola.component.agentflow.adapter.springai.SpringAiExecutionModelAdapter;
import top.egon.cola.component.agentflow.adapter.mcp.McpToolAdapter;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionResult.SideEffectOutcomeEnum;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutor;
import top.egon.cola.component.agentflow.autoconfigure.AgentExecutionAutoConfiguration;
import top.egon.cola.component.agentflow.autoconfigure.AgentFlowAutoConfiguration;
import top.egon.cola.component.agentflow.adapter.springai.SpringAiExecutionModelAdapter.ModelRegistrationVO;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionContextVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionResult.StatusEnum;
import top.egon.cola.component.agentflow.context.DefaultContextAssembler;
import top.egon.cola.component.agentflow.execution.DefaultAgentExecutor;
import top.egon.cola.component.agentflow.session.InMemorySessionStore;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class AdkAgentExecutorAdapterTest {

    @Test
    void only_matching_root_structured_final_can_complete_and_terminates_subscription() {
        var disposed = new CountDownLatch(1);
        var events = Flowable.just(event("child", "current", "{\"result\":\"child\"}"),
                        event("root", "old", "{\"result\":\"old\"}"),
                        event("root", "current", "{\"result\":\"complete\"}"))
                .concatWith(Flowable.never()).doOnCancel(disposed::countDown);
        assertEquals(Map.of("result", "complete"), AdkAgentExecutorAdapter.awaitRoot(events, "root", "current"));
        assertEquals(0, disposed.getCount());
        assertThrows(RuntimeException.class, () -> AdkAgentExecutorAdapter.awaitRoot(
                Flowable.just(event("root", "current", "{\"result\":1,\"result\":2}")), "root", "current"));
        assertThrows(RuntimeException.class, () -> AdkAgentExecutorAdapter.awaitRoot(
                Flowable.just(event("child", "current", "{}")), "root", "current"));
    }

    @Test
    void actual_configured_executor_resolves_a_fresh_native_root_and_rejects_a_different_template() {
        var subscriptions = new AtomicInteger();
        var decisions = new AtomicInteger();
        var instruction = new AgenticPlanVO.DependencyRefVO("instruction", 1, "2".repeat(64));
        var leaf = new AgenticPlanVO.LeafVO("answer", instruction, "fixed-model", List.of());
        var plan = new AgenticPlanVO(AgenticPlanVO.StrategyEnum.REASONING, List.of(leaf), List.of(), null,
                1, "answer", AgenticPlanVO.MergeEnum.OUTPUT_AGENT, List.of(),
                new AgenticPlanVO.RootTerminalVO(AgenticPlanVO.TerminationModeEnum.ROOT_TERMINAL));
        var model = new ModelRegistrationVO("fixed-model", "host-model", testModel(prompt -> Flux.defer(() -> {
            subscriptions.incrementAndGet();
            return Flux.just(response("{\"result\":\"complete\"}"));
        })), 100, 12, request -> 10);
        assertThrows(IllegalArgumentException.class, () -> new AdkAgentExecutorAdapter.RegisteredLeafVO(
                instruction, "Return one JSON object.", "different-model", model,
                List.of(), (invocation, source, round) -> new AdkAgentExecutorAdapter.LeafDecisionVO(true, null)));
        var registration = new AdkAgentExecutorAdapter.RegisteredTemplateVO("registered", 1, "1".repeat(64),
                plan, Map.of("answer", new AdkAgentExecutorAdapter.RegisteredLeafVO(instruction, "Return one JSON object.",
                "fixed-model", model, List.of(), (invocation, source, round) -> {
                    decisions.incrementAndGet();
                    return new AdkAgentExecutorAdapter.LeafDecisionVO(true, null);
                })), Map.of());
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AgentExecutionAutoConfiguration.class,
                        AgentFlowAutoConfiguration.class, ValidationAutoConfiguration.class))
                .withPropertyValues("egon.cola.component.agent-flow.enabled=true",
                        "egon.cola.component.agent-flow-registry.enabled=false")
                .withBean("agentExecutionTemplateResolver", AdkAgentExecutorAdapter.TemplateResolver.class,
                        () -> (expected, scope) -> registration)
                .run(spring -> {
                    var executor = spring.getBean("agentExecutor", AgentExecutor.class);
                    var total = Duration.ofSeconds(5);
                    for (String id : List.of("first", "second")) {
                        var result = executor.execute(command(id, "adkAgentExecutorAdapter", "1".repeat(64),
                                        Duration.ofSeconds(2), total), host()).completion().toCompletableFuture().get(8, TimeUnit.SECONDS);
                        assertEquals(StatusEnum.COMPLETED, result.status(), result.errorCode());
                        assertEquals(Map.of("result", "complete"), result.output());
                    }
                    var wrong = executor.execute(command("other", "adkAgentExecutorAdapter", "3".repeat(64),
                                    Duration.ofSeconds(2), total), host()).completion().toCompletableFuture().get(8, TimeUnit.SECONDS);
                    assertEquals("TEMPLATE_REGISTRATION_UNAVAILABLE", wrong.errorCode());
                    assertEquals(2, subscriptions.get());
                    assertEquals(2, decisions.get());
                });
    }

    @Test
    void genuine_frames_refresh_model_idle_and_provider_receives_fixed_bounded_options() throws Exception {
        var frames = new AtomicInteger();
        var fixture = new StreamFixture(prompt -> {
            assertEquals("host-model", prompt.getOptions().getModel());
            assertEquals(12, prompt.getOptions().getMaxTokens());
            assertEquals(0.2, prompt.getOptions().getTemperature());
            assertFalse(prompt.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions);
            return Flux.interval(Duration.ZERO, Duration.ofMillis(80)).take(7).map(frame -> {
                frames.incrementAndGet();
                return response("a");
            });
        });
        try (fixture) {
            var handle = fixture.execute(Duration.ofMillis(350), Duration.ofSeconds(3));
            assertEquals(StatusEnum.COMPLETED, handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
            assertEquals(7, frames.get());
        }
    }

    @Test
    void silence_and_total_deadline_dispose_the_provider_and_cancellation_disposes_it_too() throws Exception {
        for (String mode : List.of("silence", "total", "cancel")) {
            var subscribed = new CountDownLatch(1);
            var disposed = new CountDownLatch(1);
            try (var fixture = new StreamFixture(prompt -> ("total".equals(mode)
                    ? Flux.interval(Duration.ZERO, Duration.ofMillis(60)).map(frame -> response("a"))
                    : Flux.<ChatResponse>never()).doOnSubscribe(ignored -> subscribed.countDown()).doOnCancel(disposed::countDown))) {
                var handle = fixture.execute(Duration.ofMillis(350), Duration.ofSeconds("total".equals(mode) ? 1 : 5));
                assertTrue(subscribed.await(3, TimeUnit.SECONDS));
                if ("cancel".equals(mode)) handle.cancel("test cancellation");
                var result = handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals("cancel".equals(mode) ? StatusEnum.CANCELLED : StatusEnum.TIMED_OUT, result.status());
                if (!"cancel".equals(mode)) assertEquals("total".equals(mode) ? "DEADLINE_EXCEEDED" : "MODEL_IDLE_TIMEOUT", result.errorCode());
                assertTrue(disposed.await(3, TimeUnit.SECONDS), mode);
            }
        }
    }

    @Test
    void mcp_session_protocol_failure_leaves_effect_clear_and_sent_disconnect_stays_unknown() throws Exception {
        var adapter = new McpToolAdapter();
        for (boolean reachesCall : List.of(false, true)) {
            var sent = new AtomicInteger();
            var disposed = new AtomicInteger();
            var fixture = new StreamFixture(prompt -> Flux.empty(), invocation -> {
                adapter.call(invocation, new McpToolAdapter.RegistrationVO(
                        "fixed-tool", URI.create("https://fixed.invalid/mcp"), "fixed_tool", true,
                        (scope, deadline) -> { }, arguments -> { }, output -> { }, request -> switch (request.method()) {
                    case "initialize" -> new McpToolAdapter.ResponseVO(200, "owned-session", ("""
                            {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"%s","capabilities":{"tools":{}},"serverInfo":{"name":"fixed","version":"1"}}}
                            """.formatted(reachesCall ? "2025-11-25" : "wrong")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    case "notifications/initialized" -> new McpToolAdapter.ResponseVO(202, null, new byte[0]);
                    case "tools/call" -> {
                        sent.incrementAndGet();
                        throw new IllegalStateException("private details");
                    }
                    case "DELETE" -> {
                        disposed.incrementAndGet();
                        yield new McpToolAdapter.ResponseVO(204, null, new byte[0]);
                    }
                    default -> throw new AssertionError(request.method());
                }), Map.of());
                throw new AssertionError("No result may be returned");
            });
            try (fixture) {
                var result = fixture.execute(Duration.ofMillis(350), Duration.ofSeconds(3))
                        .completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(StatusEnum.FAILED, result.status());
                assertEquals(reachesCall ? "MCP_TOOL_OUTCOME_UNKNOWN" : "MCP_PROTOCOL_UNAVAILABLE", result.errorCode());
                assertEquals(reachesCall ? SideEffectOutcomeEnum.UNKNOWN : SideEffectOutcomeEnum.NONE, result.sideEffectOutcome());
                assertEquals(reachesCall ? 1 : 0, sent.get());
                assertEquals(1, disposed.get());
            }
        }
    }

    @Test
    void mcp_initialization_timeout_never_dispatches_a_write() throws Exception {
        var pending = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var fixture = new StreamFixture(prompt -> Flux.empty(), invocation -> {
            var adapter = new McpToolAdapter();
            adapter.call(invocation, new McpToolAdapter.RegistrationVO("fixed-tool", URI.create("https://fixed.invalid/mcp"),
                    "fixed_tool", true, (scope, deadline) -> { }, input -> { }, output -> { }, request -> {
                assertEquals("initialize", request.method());
                assertTrue(request.deadline().isBefore(invocation.deadline()));
                pending.countDown();
                try {
                    new CountDownLatch(1).await();
                    throw new AssertionError("Initialization must be interrupted");
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    cancelled.countDown();
                    throw new IllegalStateException("Stopped");
                }
            }), Map.of());
            throw new AssertionError("No result may be returned");
        });
        try (fixture) {
            var handle = fixture.execute(Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofMillis(300));
            assertTrue(pending.await(3, TimeUnit.SECONDS));
            var result = handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(StatusEnum.TIMED_OUT, result.status());
            assertEquals("TOOL_TIMEOUT", result.errorCode());
            assertEquals(SideEffectOutcomeEnum.NONE, result.sideEffectOutcome());
            assertTrue(cancelled.await(3, TimeUnit.SECONDS));
        }
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static Event event(String author, String invocation, String text) {
        return Event.builder().author(author).invocationId(invocation)
                .content(Content.builder().role("model").parts(List.of(Part.fromText(text))).build())
                .turnComplete(true).build();
    }

    private static ChatModel testModel(Function<Prompt, Flux<ChatResponse>> source) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new AssertionError("stream required"); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return source.apply(prompt); }
            @Override public ChatOptions getOptions() { return ChatOptions.builder().model("host-model").temperature(0.2).build(); }
        };
    }

    private static AgentExecutionCommand command(String id, String executorRef, String digest, Duration idle, Duration total) {
        var template = new AgentExecutionCommand.TemplateVO("registered", 1, digest, executorRef,
                Map.of(), new AgentExecutionCommand.SchemaValidator() {
            @Override public void validateInput(Map<String, Object> input, Map<String, Object> parameters) { }
            @Override public void validateOutput(Map<String, Object> output) { }
        });
        var limits = new AgentExecutionCommand.TechnicalLimitsVO(total, total, idle, total, 2, 2, 1, 200, 20);
        return new AgentExecutionCommand(id, template, Map.of(), Map.of(), limits);
    }

    private static AgentExecutionContextVO host() {
        var scope = new ContextScopeVO("test", "tenant", "principal", "instance", "session", "decision");
        return new AgentExecutionContextVO(scope, Clock.systemUTC().instant().plusSeconds(9),
                new CompletableFuture<>(), (query, scoped, permit) ->
                new top.egon.cola.component.agentflow.api.context.ContextFragmentResult(List.of(), null), null,
                (scoped, capability, purpose) -> new top.egon.cola.component.agentflow.api.capability.CallPermitVO(
                        "permit", capability, scoped.scopeHash(), 1, null, Clock.systemUTC().instant().plusSeconds(9),
                        () -> false, (permit, currentPurpose) -> true));
    }

    private static final class StreamFixture implements AutoCloseable {
        private final java.util.concurrent.ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
        private final jakarta.validation.ValidatorFactory factory =
                jakarta.validation.Validation.buildDefaultValidatorFactory();
        private final SpringAiExecutionModelAdapter adapter = new SpringAiExecutionModelAdapter();
        private final DefaultAgentExecutor executor;

        private StreamFixture(Function<Prompt, Flux<ChatResponse>> source) {
            this(source, null);
        }

        private StreamFixture(Function<Prompt, Flux<ChatResponse>> source, DefaultAgentExecutor.ExecutionAdapter override) {
            Clock clock = Clock.systemUTC();
            var validator = factory.getValidator();
            var registration = new ModelRegistrationVO("fixed-model", "host-model", testModel(source), 100, 12, prompt -> 10);
            executor = new DefaultAgentExecutor(clock, validator, new DefaultContextAssembler(clock, validator), timers,
                    new InMemorySessionStore(clock, validator), Map.of("stream", override != null ? override : invocation -> {
                var forged = org.springframework.ai.model.tool.ToolCallingChatOptions.builder().model("wrong")
                        .temperature(0.75).maxTokens(1).toolContext("escape", true).build();
                var result = adapter.generate(invocation, registration, new Prompt("input", forged));
                assertNull(result.inputTokens());
                assertNull(result.outputTokens());
                return new top.egon.cola.component.agentflow.api.execution.AgentExecutionResult(invocation.request().executionId(),
                        StatusEnum.COMPLETED, Map.of(), null,
                        top.egon.cola.component.agentflow.api.execution.AgentExecutionResult.SideEffectOutcomeEnum.NONE, clock.instant());
            }));
        }

        private top.egon.cola.component.agentflow.api.execution.AgentExecutionHandle execute(Duration idle, Duration total) {
            return execute(idle, total, total);
        }

        private top.egon.cola.component.agentflow.api.execution.AgentExecutionHandle execute(Duration idle, Duration total, Duration toolTimeout) {
            var command = command("stream-run", "stream", "1".repeat(64), idle, total);
            command.setLimits(new AgentExecutionCommand.TechnicalLimitsVO(total, total, idle, toolTimeout, 2, 2, 1, 200, 20));
            return executor.execute(command, host());
        }

        @Override public void close() {
            timers.shutdownNow();
            factory.close();
        }
    }
}
