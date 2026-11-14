package top.egon.cola.component.agentflow.adapter.adk;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
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
import java.util.ArrayList;
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
    void actual_model_tool_uses_the_exact_host_callback_and_only_confirmed_sends_count_as_writes() throws Exception {
        var instruction = new AgenticPlanVO.DependencyRefVO("instruction", 1, "2".repeat(64));
        var ref = new AgenticPlanVO.DependencyRefVO("fixed-tool", 3, "3".repeat(64));
        var leaf = new AgenticPlanVO.LeafVO("answer", instruction, "fixed-model", List.of(ref));
        var plan = new AgenticPlanVO(AgenticPlanVO.StrategyEnum.REASONING, List.of(leaf), List.of(), null, 1, "answer",
                AgenticPlanVO.MergeEnum.OUTPUT_AGENT, List.of(),
                new AgenticPlanVO.RootTerminalVO(AgenticPlanVO.TerminationModeEnum.ROOT_TERMINAL));
        var observed = new AtomicInteger();
        var legacy = new AtomicInteger();
        var output = Map.<String, Object>of("accepted", true);
        var leakedBoundary = new java.util.concurrent.atomic.AtomicReference<AgentExecutionContextVO.ToolCallBoundary>();
        var tool = new AdkAgentExecutorAdapter.RegisteredToolVO(ref, FunctionDeclaration.builder().name("fixed_tool")
                .description("Apply the registered fixture effect.").parameters(Schema.builder().type("OBJECT")
                        .properties(Map.of("amount", Schema.builder().type("INTEGER").build())).required(List.of("amount")).build()).build(), true,
                arguments -> assertEquals(Map.of("amount", 7L), arguments),
                result -> {
                    if (!output.equals(result)) throw new IllegalArgumentException("Invalid output");
                    var retained = leakedBoundary.getAndSet(null);
                    if (retained != null) assertThrows(RuntimeException.class, retained::beforeSend);
                },
                arguments -> { legacy.incrementAndGet(); return output; }, null);
        var model = new ModelRegistrationVO("fixed-model", "host-model", testModel(prompt -> {
            if (prompt.getInstructions().stream().anyMatch(org.springframework.ai.chat.messages.ToolResponseMessage.class::isInstance)) {
                return Flux.just(response("{\"result\":\"complete\"}"));
            }
            var message = AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall("host-call", "function", "fixed_tool", "{\"amount\":7}"))).build();
            return Flux.just(new ChatResponse(List.of(new Generation(message,
                    ChatGenerationMetadata.builder().finishReason("tool_calls").build()))));
        }), 100, 12, prompt -> 10);
        var registration = new AdkAgentExecutorAdapter.RegisteredTemplateVO("registered", 1, "1".repeat(64), plan,
                Map.of("answer", new AdkAgentExecutorAdapter.RegisteredLeafVO(instruction, "Return one JSON object.",
                        "fixed-model", model, List.of(tool), (invocation, source, round) ->
                        new AdkAgentExecutorAdapter.LeafDecisionVO(true, null))), Map.of());
        var clock = Clock.systemUTC();
        var adapter = new AdkAgentExecutorAdapter(new top.egon.cola.component.agentflow.agentic.AgenticPlanCompiler(clock),
                new SpringAiExecutionModelAdapter(), new McpToolAdapter(), (expected, scope) -> registration);
        try (var fixture = new StreamFixture(prompt -> Flux.empty(), adapter)) {
            var result = fixture.execute(Duration.ofSeconds(2), Duration.ofSeconds(6), Duration.ofSeconds(3), null,
                    (request, boundary) -> {
                        observed.incrementAndGet();
                        assertEquals("stream-run", request.executionId());
                        assertEquals(new AgentExecutionContextVO.ToolReferenceVO(ref.key(), ref.version(), ref.contentSha256()), request.toolRef());
                        assertEquals(Map.of("amount", 7L), request.arguments());
                        assertTrue(request.write());
                        assertThrows(UnsupportedOperationException.class, () -> request.arguments().put("amount", 8L));
                        try (var transport = Executors.newVirtualThreadPerTaskExecutor()) {
                            transport.submit(boundary::beforeSend).get(1, TimeUnit.SECONDS);
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                        return boundary.verifyOutput(output);
                    }).completion().toCompletableFuture().get(8, TimeUnit.SECONDS);
            assertEquals(1, observed.get(), () -> result.status() + " / " + result.errorCode() + " / " + legacy.get());
            assertEquals(0, legacy.get());
            assertEquals(StatusEnum.COMPLETED, result.status(), result.errorCode());
            assertEquals(SideEffectOutcomeEnum.CONFIRMED, result.sideEffectOutcome());

            for (String mode : List.of("replay", "denied", "known-no-effect", "invalid-after-send", "unverified-after-send",
                    "duplicate-after-send")) {
                var execution = fixture.execute(Duration.ofSeconds(2), Duration.ofSeconds(6), Duration.ofSeconds(3), null,
                        (request, boundary) -> switch (mode) {
                            case "replay" -> {
                                leakedBoundary.set(boundary);
                                yield output;
                            }
                            case "denied" -> throw new IllegalArgumentException("No tool permission");
                            case "known-no-effect" -> {
                                boundary.beforeSend();
                                boundary.recordKnownNoEffect();
                                yield output;
                            }
                            case "invalid-after-send" -> {
                                boundary.beforeSend();
                                yield boundary.verifyOutput(Map.of("accepted", false));
                            }
                            case "unverified-after-send" -> {
                                boundary.beforeSend();
                                yield output;
                            }
                            case "duplicate-after-send" -> {
                                boundary.beforeSend();
                                boundary.beforeSend();
                                throw new AssertionError("Second mark allowed");
                            }
                            default -> throw new AssertionError(mode);
                        }).completion().toCompletableFuture().get(8, TimeUnit.SECONDS);
                assertEquals(mode.endsWith("after-send") ? SideEffectOutcomeEnum.UNKNOWN : SideEffectOutcomeEnum.NONE,
                        execution.sideEffectOutcome(), mode);
            }
            var marked = new CountDownLatch(1);
            var letTransportReturn = new CompletableFuture<Void>();
            var late = new CompletableFuture<Boolean>();
            var cancelled = fixture.execute(Duration.ofSeconds(2), Duration.ofSeconds(6), Duration.ofSeconds(3), null,
                    (request, boundary) -> {
                        boundary.beforeSend();
                        marked.countDown();
                        letTransportReturn.join();
                        try {
                            boundary.verifyOutput(output);
                            late.complete(false);
                        } catch (RuntimeException stopped) {
                            late.complete(true);
                        }
                        return output;
                    });
            try {
                assertTrue(marked.await(3, TimeUnit.SECONDS));
                cancelled.cancel("Host stopped execution");
                var outcome = cancelled.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(StatusEnum.CANCELLED, outcome.status());
                assertEquals(SideEffectOutcomeEnum.UNKNOWN, outcome.sideEffectOutcome());
            } finally {
                letTransportReturn.complete(null);
            }
            assertTrue(late.get(3, TimeUnit.SECONDS));
            assertEquals(0, legacy.get());
            var original = fixture.execute(Duration.ofSeconds(2), Duration.ofSeconds(6))
                    .completion().toCompletableFuture().get(8, TimeUnit.SECONDS);
            assertEquals(StatusEnum.COMPLETED, original.status(), original.errorCode());
            assertEquals(SideEffectOutcomeEnum.CONFIRMED, original.sideEffectOutcome());
            assertEquals(1, legacy.get());
        }
    }

    @Test
    void modelLifecycleBeginsBeforeProviderAndFinishesAfterValidatedResponse() throws Exception {
        var order = new ArrayList<String>();
        var requests = new ArrayList<AgentExecutionContextVO.ModelCallRequestVO>();
        var outcomes = new ArrayList<AgentExecutionContextVO.ModelCallOutcomeVO>();
        AgentExecutionContextVO.ModelCallLifecycle lifecycle = request -> {
            order.add("begin");
            requests.add(request);
            return outcome -> { outcomes.add(outcome); order.add("finish"); };
        };

        try (var fixture = new StreamFixture(prompt -> {
            order.add("provider");
            return Flux.just(response("validated"));
        })) {
            var result = fixture.execute(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(4), lifecycle)
                    .completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(StatusEnum.COMPLETED, result.status());
            assertEquals(List.of("begin", "provider", "finish"), order);
            assertEquals("stream-run", requests.getFirst().executionId());
            assertEquals("fixed-model", requests.getFirst().modelRef());
            assertTrue(requests.getFirst().inputSha256().matches("[0-9a-f]{64}"));
            assertTrue(outcomes.getFirst().resultSha256().matches("[0-9a-f]{64}"));
            assertNull(outcomes.getFirst().errorCode());
            assertNull(outcomes.getFirst().inputTokens());
            assertNull(outcomes.getFirst().outputTokens());
        }
    }

    @Test
    void failedPreparationNeverCallsProviderAndEmptyAggregateIsNeverSucceeded() throws Exception {
        var providerCalls = new AtomicInteger();
        var outcome = new CompletableFuture<AgentExecutionContextVO.ModelCallOutcomeVO>();
        try (var fixture = new StreamFixture(prompt -> {
            providerCalls.incrementAndGet();
            return Flux.empty();
        })) {
            var denied = fixture.execute(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(4), request -> {
                throw new IllegalStateException("no host receipt");
            }).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(StatusEnum.FAILED, denied.status());
            assertEquals(0, providerCalls.get());
            var invalid = fixture.execute(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(4), request -> outcome::complete)
                    .completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(StatusEnum.FAILED, invalid.status());
            assertEquals(1, providerCalls.get());
            assertNull(outcome.get(1, TimeUnit.SECONDS).resultSha256());
            assertEquals("MODEL_EMPTY_RESPONSE", outcome.getNow(null).errorCode());
        }
    }

    @Test
    void cancellationLeavesADispatchedCallOpenForTheHostToReconcile() throws Exception {
        var started = new CountDownLatch(1);
        var disposed = new CountDownLatch(1);
        var finished = new AtomicInteger();
        try (var fixture = new StreamFixture(prompt -> Flux.<ChatResponse>never()
                .doOnSubscribe(ignored -> started.countDown()).doOnCancel(disposed::countDown))) {
            var handle = fixture.execute(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(4),
                    request -> outcome -> finished.incrementAndGet());
            assertTrue(started.await(2, TimeUnit.SECONDS));
            handle.cancel("cancelled by host");
            assertEquals(StatusEnum.CANCELLED, handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
            assertTrue(disposed.await(2, TimeUnit.SECONDS));
            assertEquals(0, finished.get());
        }
    }

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
            return execute(idle, total, toolTimeout, null);
        }

        private top.egon.cola.component.agentflow.api.execution.AgentExecutionHandle execute(Duration idle, Duration total, Duration toolTimeout,
                AgentExecutionContextVO.ModelCallLifecycle lifecycle) {
            return execute(idle, total, toolTimeout, lifecycle, null);
        }

        private top.egon.cola.component.agentflow.api.execution.AgentExecutionHandle execute(Duration idle, Duration total, Duration toolTimeout,
                AgentExecutionContextVO.ModelCallLifecycle lifecycle, AgentExecutionContextVO.ToolCallHandler handler) {
            var command = command("stream-run", "stream", "1".repeat(64), idle, total);
            command.setLimits(new AgentExecutionCommand.TechnicalLimitsVO(total, total, idle, toolTimeout, 2, 2, 1, 200, 20));
            var host = host();
            return executor.execute(command, new AgentExecutionContextVO(host.scope(), host.deadline(), host.cancellation(),
                    host.contextSource(), host.sessionStore(), host.callPermitProvider(), lifecycle, handler));
        }

        @Override public void close() {
            timers.shutdownNow();
            factory.close();
        }
    }
}
