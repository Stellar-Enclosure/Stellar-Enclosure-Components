package top.egon.cola.component.agentflow.compatibility;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.MDC;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Sinks;
import top.egon.cola.component.agentflow.api.AgentFlowExecutionCommand;
import top.egon.cola.component.agentflow.api.AgentFlowSessionCommand;
import top.egon.cola.component.agentflow.autoconfigure.AgentFlowProperties;
import top.egon.cola.component.agentflow.common.exception.AgentFlowExecutionTimeoutException;
import top.egon.cola.component.agentflow.common.exception.AgentFlowExecutionException;
import top.egon.cola.component.agentflow.config.AgentConfigDTO;
import top.egon.cola.component.agentflow.config.AgentFlowConfigDTO;
import top.egon.cola.component.agentflow.execution.AgentFlowSessionExecutionGuard;
import top.egon.cola.component.agentflow.execution.DefaultAgentFlowService;
import top.egon.cola.component.agentflow.runtime.AgentFlowFactory;
import top.egon.cola.component.agentflow.runtime.AgentFlowInMemoryRunner;
import top.egon.cola.component.agentflow.runtime.AgentFlowRuntimeBO;
import top.egon.cola.component.agentflow.runtime.DefaultAgentFlowRegistry;
import top.egon.cola.component.agentflow.runtime.SpringAiModelAdapterFactory;
import top.egon.cola.component.agentflow.workflow.AgentWorkflowStrategyFactory;
import top.egon.cola.component.agentflow.workflow.LoopAgentWorkflowBuilderStrategy;
import top.egon.cola.component.agentflow.workflow.ParallelAgentWorkflowBuilderStrategy;
import top.egon.cola.component.agentflow.workflow.SequentialAgentWorkflowBuilderStrategy;
import top.egon.cola.component.common.core.validation.ValidationUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static top.egon.cola.component.agentflow.compatibility.AdkCompatibilityFixtures.*;

/** Regression tests for the starter's real factory/runner wiring and stream boundaries. */
@Timeout(10)
@Isolated("This test class temporarily installs a Reactor dropped-error hook")
class AgentFlowAdapterRegressionTest {

    @Test
    void explicit_empty_tool_list_does_not_register_host_model_callbacks() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.options = ToolCallingChatOptions.builder().model(MODEL)
                .toolCallbacks(callback(new AtomicInteger(), new AtomicReference<>(), new AtomicReference<>(), false))
                .build();
        var root = factory().compileRoot(APP, config("entry"), Map.of("fixtureChatModel", model), List.of());
        assertThat(((com.google.adk.agents.LlmAgent) root).tools().blockingGet()).isEmpty();
    }

    @Test
    void configured_host_tool_executes_with_scoped_host_context() throws Exception {
        ScriptedChatModel model = new ScriptedChatModel();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> tenant = new AtomicReference<>();
        AtomicReference<org.springframework.ai.chat.model.ToolContext> received = new AtomicReference<>();
        ToolCallback callback = callback(calls, received, tenant, false);
        model.options = ToolCallingChatOptions.builder().model(MODEL).toolCallbacks(callback)
                .toolContext("host_reference", "fixture")
                .toolContext("adk_tool_context", "must_not_leave_process").build();
        model.response = prompt -> model.calls.get() == 1
                ? toolCall("host_lookup") : text("host tool completed.", "stop", true);

        MDC.put("tenantId", "101");
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            AgentFlowFactory.AgentFlowCompilationBO compilation = factory().create(
                    APP, config("entry"), Map.of("fixtureChatModel", model));
            try (DefaultAgentFlowService service = service(compilation, Duration.ofSeconds(2),
                    validators.getValidator())) {
                var session = service.createSession(new AgentFlowSessionCommand(APP, USER, null));
                List<Event> events = service.execute(
                        new AgentFlowExecutionCommand(APP, USER, session.sessionId(), input()));

                assertThat(calls).hasValue(1);
                assertThat(received.get().getContext()).containsEntry("host_reference", "fixture");
                assertThat(received.get().getContext()).doesNotContainKey("adk_tool_context");
                assertThat(tools.jackson.databind.json.JsonMapper.builder().build()
                        .writeValueAsString(received.get().getContext())).doesNotContain("must_not_leave_process");
                assertThat(tenant.get()).isEqualTo("101");
                assertThat(MDC.get("tenantId")).isEqualTo("101");
                assertThat(events).anyMatch(event -> eventText(event).equals("host tool completed."));
                assertThat(model.prompts.getLast().getInstructions()).anyMatch(
                        message -> message instanceof org.springframework.ai.chat.messages.ToolResponseMessage);
            }
        } finally {
            MDC.remove("tenantId");
        }
    }

    @Test
    void host_model_cannot_execute_a_tool_without_the_native_adk_tool_context() {
        ScriptedChatModel model = new ScriptedChatModel();
        AtomicInteger calls = new AtomicInteger();
        model.options = ToolCallingChatOptions.builder().model(MODEL)
                .toolCallbacks(callback(calls, new AtomicReference<>(), new AtomicReference<>(), false)).build();
        model.response = prompt -> {
            ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
            options.getToolCallbacks().getFirst().call("{\"value\":7}",
                    new org.springframework.ai.chat.model.ToolContext(Map.of()));
            return text("unexpected.", "stop", true);
        };
        var compilation = factory().create(APP, config("entry"), Map.of("fixtureChatModel", model));
        try (var validators = Validation.buildDefaultValidatorFactory();
             DefaultAgentFlowService service = service(compilation, Duration.ofSeconds(2), validators.getValidator())) {
            var session = service.createSession(new AgentFlowSessionCommand(APP, USER, null));
            assertThatThrownBy(() -> service.execute(
                    new AgentFlowExecutionCommand(APP, USER, session.sessionId(), input())))
                    .isInstanceOf(AgentFlowExecutionException.class);
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void streaming_flow_executes_a_configured_host_tool_and_returns_a_complete_final_answer() {
        ScriptedChatModel model = new ScriptedChatModel();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<org.springframework.ai.chat.model.ToolContext> received = new AtomicReference<>();
        model.options = ToolCallingChatOptions.builder().model(MODEL)
                .toolCallbacks(callback(calls, received, new AtomicReference<>(), false)).build();
        model.streaming = prompt -> model.streams.get() == 1
                ? Flux.just(toolCall("host_lookup"))
                : Flux.just(text("host ", "", false), text("completed.", "stop", true));
        var compilation = factory().create(APP, config("entry"), Map.of("fixtureChatModel", model));
        try (var validators = Validation.buildDefaultValidatorFactory();
             DefaultAgentFlowService service = service(compilation, Duration.ofSeconds(2), validators.getValidator())) {
            var session = service.createSession(new AgentFlowSessionCommand(APP, USER, null));
            List<Event> events = service.executeStream(
                    new AgentFlowExecutionCommand(APP, USER, session.sessionId(), input()))
                    .toList().timeout(3, TimeUnit.SECONDS).blockingGet();

            assertThat(calls).hasValue(1);
            assertThat(model.streams).hasValue(2);
            assertThat(received.get().getContext()).doesNotContainKey("adk_tool_context");
            assertThat(events).anyMatch(event -> event.finalResponse()
                    && eventText(event).equals("host completed."));
            assertThat(hasToolResponse(model.prompts.getLast())).isTrue();
        }
    }

    @Test
    void return_direct_host_tool_stops_the_native_loop_after_its_result() {
        ScriptedChatModel model = new ScriptedChatModel();
        AtomicInteger calls = new AtomicInteger();
        model.options = ToolCallingChatOptions.builder().model(MODEL)
                .toolCallbacks(callback(calls, new AtomicReference<>(), new AtomicReference<>(), true)).build();
        model.response = prompt -> toolCall("host_lookup");
        var compilation = factory().create(APP, config("entry"), Map.of("fixtureChatModel", model));
        try (var validators = Validation.buildDefaultValidatorFactory();
             DefaultAgentFlowService service = service(compilation, Duration.ofSeconds(2), validators.getValidator())) {
            var session = service.createSession(new AgentFlowSessionCommand(APP, USER, null));
            List<Event> events = service.execute(
                    new AgentFlowExecutionCommand(APP, USER, session.sessionId(), input()));

            assertThat(calls).hasValue(1);
            assertThat(model.calls).hasValue(1);
            assertThat(events).anyMatch(event -> !event.functionResponses().isEmpty()
                    && event.actions().skipSummarization().orElse(false));
        }
    }

    @Test
    void synchronous_execute_preserves_a_legacy_runner_three_argument_override() {
        AtomicInteger calls = new AtomicInteger();
        InMemoryRunner runner = new InMemoryRunner(agent("legacy", new ScriptedChatModel()), APP) {
            @Override
            public Flowable<Event> runAsync(String userId, String sessionId, Content message) {
                calls.incrementAndGet();
                return Flowable.just(Event.builder().id("legacy").author("legacy")
                        .content(Content.fromParts(Part.fromText("legacy result."))).build());
            }
        };
        var compilation = new AgentFlowFactory.AgentFlowCompilationBO(APP, runner.agent(), runner);
        try (var validators = Validation.buildDefaultValidatorFactory();
             DefaultAgentFlowService service = service(compilation, Duration.ofSeconds(2), validators.getValidator())) {
            var session = service.createSession(new AgentFlowSessionCommand(APP, USER, null));
            List<Event> events = service.execute(
                    new AgentFlowExecutionCommand(APP, USER, session.sessionId(), input()));
            assertThat(calls).hasValue(1);
            assertThat(events).extracting(Event::id).containsExactly("legacy");
        }
    }

    @Test
    void native_usage_only_tail_keeps_full_final_text_and_usage() {
        ScriptedChatModel model = new ScriptedChatModel();
        ChatResponse usageOnly = new ChatResponse(List.of(), ChatResponseMetadata.builder()
                .usage(new DefaultUsage(10, 3, 13)).build());
        model.streaming = prompt -> Flux.just(text("hel", "", false),
                text("lo.", "stop", false), usageOnly);
        try (NativeRun run = new NativeRun(agent("usage_tail", model))) {
            List<Event> events = run.run(true).toList().timeout(3, TimeUnit.SECONDS).blockingGet();

            Event last = events.getLast();
            assertThat(last.finalResponse()).isTrue();
            assertThat(eventText(last)).isEqualTo("hello.");
            assertThat(last.usageMetadata()).hasValueSatisfying(value ->
                    assertThat(value.totalTokenCount()).contains(13));
            assertThat(run.storedSession().state()).containsEntry("usage_tail_output", "hello.");
        }
    }

    @Test
    void missing_usage_counts_are_not_fabricated_as_zero() {
        ScriptedChatModel model = new ScriptedChatModel();
        Usage partialUsage = new Usage() {
            @Override public Integer getPromptTokens() { return null; }
            @Override public Integer getCompletionTokens() { return 3; }
            @Override public Integer getTotalTokens() { return null; }
            @Override public Object getNativeUsage() { return Map.of(); }
        };
        ChatResponse usageOnly = new ChatResponse(List.of(), ChatResponseMetadata.builder().usage(partialUsage).build());
        model.streaming = prompt -> Flux.just(text("answer.", "stop", false), usageOnly);

        List<LlmResponse> responses = bridge(model).generateContent(request(), true)
                .toList().timeout(3, TimeUnit.SECONDS).blockingGet();
        var usage = responses.getLast().usageMetadata().orElseThrow();
        assertThat(usage.promptTokenCount()).isEmpty();
        assertThat(usage.candidatesTokenCount()).contains(3);
        assertThat(usage.totalTokenCount()).isEmpty();
    }

    @Test
    void duplicate_streamed_tool_call_ids_fail_before_tool_execution() {
        ScriptedChatModel model = new ScriptedChatModel();
        ProbeTool tool = new ProbeTool();
        model.streaming = prompt -> Flux.just(toolCall(tool.name()), toolCall(tool.name()));
        var root = com.google.adk.agents.LlmAgent.builder().name("duplicate")
                .instruction("Use the fixture tool.").model(bridge(model)).tools(tool).build();
        try (NativeRun run = new NativeRun(root)) {
            run.run(true).test().awaitDone(3, TimeUnit.SECONDS)
                    .assertError(error -> messageInCause(error, "duplicate tool call ids"));
            assertThat(tool.calls).hasValue(0);
        }
    }

    @Test
    void oversized_streamed_text_fails_instead_of_emitting_a_truncated_final() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.streaming = prompt -> Flux.just(text("x".repeat(4_000_001), "stop", false));

        bridge(model).generateContent(request(), true).test().awaitDone(3, TimeUnit.SECONDS)
                .assertNoValues().assertError(error -> messageInCause(error, "response size limit"));
    }

    @Test
    void endless_empty_stream_frames_are_bounded() {
        ScriptedChatModel model = new ScriptedChatModel();
        ChatResponse empty = new ChatResponse(List.of());
        model.streaming = prompt -> Flux.range(0, 100_001).map(ignored -> empty);

        bridge(model).generateContent(request(), true).test().awaitDone(5, TimeUnit.SECONDS)
                .assertNoValues().assertError(error -> messageInCause(error, "frame limit"));
    }

    @Test
    void cancelling_one_session_does_not_cancel_another_session() throws InterruptedException {
        ScriptedChatModel model = new ScriptedChatModel();
        Sinks.Many<ChatResponse> firstSource = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<ChatResponse> secondSource = Sinks.many().unicast().onBackpressureBuffer();
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch firstCancelled = new CountDownLatch(1);
        AtomicInteger secondCancelled = new AtomicInteger();
        model.streaming = prompt -> prompt.getInstructions().stream()
                .anyMatch(message -> message.getText().contains("first"))
                ? firstSource.asFlux().doOnSubscribe(ignored -> started.countDown())
                        .doOnCancel(firstCancelled::countDown)
                : secondSource.asFlux().doOnSubscribe(ignored -> started.countDown())
                        .doOnCancel(secondCancelled::incrementAndGet);
        AgentFlowInMemoryRunner runner = new AgentFlowInMemoryRunner(agent("shared", model), APP);
        try {
            Session first = runner.sessionService().createSession(APP, "first_user").blockingGet();
            Session second = runner.sessionService().createSession(APP, "second_user").blockingGet();
            RunConfig config = RunConfig.builder().setStreamingMode(RunConfig.StreamingMode.SSE).build();
            TestSubscriber<Event> one = runner.runAsync("first_user", first.id(),
                    Content.fromParts(Part.fromText("first")), config).test();
            TestSubscriber<Event> two = runner.runAsync("second_user", second.id(),
                    Content.fromParts(Part.fromText("second")), config).test();
            try {
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                one.cancel();
                assertThat(firstCancelled.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(secondCancelled).hasValue(0);
                assertThat(secondSource.tryEmitNext(text("second result.", "stop", true)).isSuccess()).isTrue();
                assertThat(secondSource.tryEmitComplete().isSuccess()).isTrue();
                two.awaitDone(3, TimeUnit.SECONDS).assertComplete().assertNoErrors();
                assertThat(two.values()).anyMatch(event -> event.finalResponse()
                        && eventText(event).equals("second result."));
            } finally {
                one.cancel();
                two.cancel();
                firstSource.tryEmitComplete();
                secondSource.tryEmitComplete();
            }
        } finally {
            assertThat(runner.close().blockingAwait(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void closing_the_runner_cancels_an_active_model_stream() throws InterruptedException {
        ScriptedChatModel model = new ScriptedChatModel();
        Sinks.Many<ChatResponse> source = Sinks.many().unicast().onBackpressureBuffer();
        model.streaming = prompt -> source.asFlux();
        AgentFlowInMemoryRunner runner = new AgentFlowInMemoryRunner(agent("closing", model), APP);
        Session session = runner.sessionService().createSession(APP, USER).blockingGet();
        RunConfig config = RunConfig.builder().setStreamingMode(RunConfig.StreamingMode.SSE).build();
        TestSubscriber<Event> subscriber = runner.runAsync(USER, session.id(), input(), config).test();
        try {
            assertThat(model.streamStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(source.tryEmitNext(text("partial", "", false)).isSuccess()).isTrue();
            assertThat(subscriber.values()).anyMatch(event -> eventText(event).contains("partial"));
            assertThat(runner.close().blockingAwait(3, TimeUnit.SECONDS)).isTrue();
            assertThat(model.streamCancelled.await(2, TimeUnit.SECONDS)).isTrue();
            subscriber.awaitDone(3, TimeUnit.SECONDS).assertComplete().assertNoErrors();
            assertThat(model.streams).hasValue(1);
            assertThat(subscriber.values()).noneMatch(event -> event.finalResponse()
                    && eventText(event).equals("partial"));
        } finally {
            subscriber.cancel();
            source.tryEmitComplete();
            runner.close().blockingAwait(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void cancelling_after_a_tool_call_event_prevents_starting_the_tool() throws InterruptedException {
        ScriptedChatModel model = new ScriptedChatModel();
        ProbeTool tool = new ProbeTool();
        model.streaming = prompt -> Flux.just(toolCall(tool.name()));
        var root = com.google.adk.agents.LlmAgent.builder().name("cancel_tool")
                .instruction("Use the fixture tool.").model(bridge(model)).tools(tool).build();
        try (NativeRun run = new NativeRun(root)) {
            List<Event> events = run.run(true).take(1).toList().timeout(3, TimeUnit.SECONDS).blockingGet();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().functionCalls()).isNotEmpty();
            assertThat(tool.called.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(tool.calls).hasValue(0);
        }
    }

    @Test
    void absolute_deadline_returns_when_a_synchronous_model_blocks() throws InterruptedException {
        ScriptedChatModel model = new ScriptedChatModel();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        model.response = prompt -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("fixture model interrupted", interrupted);
            }
            return text("late response.", "stop", true);
        };
        var compilation = factory().create(APP, config("entry"), Map.of("fixtureChatModel", model));
        try (var validators = Validation.buildDefaultValidatorFactory();
             DefaultAgentFlowService service = service(compilation, Duration.ofMillis(200), validators.getValidator())) {
            var session = service.createSession(new AgentFlowSessionCommand(APP, USER, null));
            long startedAt = System.nanoTime();
            try {
                assertThatThrownBy(() -> service.execute(
                        new AgentFlowExecutionCommand(APP, USER, session.sessionId(), input())))
                        .isInstanceOf(AgentFlowExecutionTimeoutException.class);
                assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void cancelling_a_direct_blocking_model_call_does_not_drop_its_interruption_as_an_error()
            throws InterruptedException {
        ScriptedChatModel model = new ScriptedChatModel();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch errorDropped = new CountDownLatch(1);
        AtomicReference<Throwable> dropped = new AtomicReference<>();
        model.response = prompt -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                interrupted.countDown();
                throw new IllegalStateException("fixture model interrupted", failure);
            }
            return text("late response.", "stop", true);
        };
        Hooks.onErrorDropped(failure -> {
            dropped.set(failure);
            errorDropped.countDown();
        });
        TestSubscriber<LlmResponse> subscriber = bridge(model).generateContent(request(), false).test();
        try {
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            subscriber.cancel();
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(errorDropped.await(250, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(dropped.get()).isNull();
        } finally {
            subscriber.cancel();
            release.countDown();
            Hooks.resetOnErrorDropped();
        }
    }

    @Test
    void deadline_returns_even_if_a_model_temporarily_ignores_interruption() throws InterruptedException {
        ScriptedChatModel model = new ScriptedChatModel();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        model.response = prompt -> {
            started.countDown();
            boolean interrupted = false;
            try {
                while (release.getCount() > 0) {
                    try {
                        release.await();
                    } catch (InterruptedException failure) {
                        interrupted = true;
                    }
                }
                return text("late response.", "stop", true);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                finished.countDown();
            }
        };
        var compilation = factory().create(APP, config("entry"), Map.of("fixtureChatModel", model));
        try (var validators = Validation.buildDefaultValidatorFactory();
             DefaultAgentFlowService service = service(compilation, Duration.ofMillis(200), validators.getValidator())) {
            var session = service.createSession(new AgentFlowSessionCommand(APP, USER, null));
            long startedAt = System.nanoTime();
            try {
                assertThatThrownBy(() -> service.execute(
                        new AgentFlowExecutionCommand(APP, USER, session.sessionId(), input())))
                        .isInstanceOf(AgentFlowExecutionTimeoutException.class);
                assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
            } finally {
                release.countDown();
            }
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static AgentFlowFactory factory() {
        return new AgentFlowFactory(new SpringAiModelAdapterFactory(),
                new AgentWorkflowStrategyFactory(List.of(new SequentialAgentWorkflowBuilderStrategy(),
                        new ParallelAgentWorkflowBuilderStrategy(), new LoopAgentWorkflowBuilderStrategy())));
    }

    private static AgentFlowConfigDTO config(String agentName) {
        return new AgentFlowConfigDTO("fixtureChatModel", MODEL, agentName,
                List.of(new AgentConfigDTO(agentName, "fixture", "Use the fixture tool.", "output")), List.of());
    }

    private static DefaultAgentFlowService service(AgentFlowFactory.AgentFlowCompilationBO compilation,
                                                   Duration timeout, jakarta.validation.Validator validator) {
        Clock clock = Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC);
        var runtime = new AgentFlowRuntimeBO(APP, compilation.rootAgent().name(), "fixtureChatModel", MODEL,
                compilation.rootAgent(), compilation.runner(), clock.instant());
        var registry = new DefaultAgentFlowRegistry(Map.of(APP, runtime), Duration.ofSeconds(1));
        return new DefaultAgentFlowService(registry, new AgentFlowSessionExecutionGuard(),
                new AgentFlowProperties(true, timeout, Duration.ofSeconds(1), Map.of()),
                new ValidationUtils(validator), clock);
    }

    private static ToolCallback callback(AtomicInteger calls,
                                         AtomicReference<org.springframework.ai.chat.model.ToolContext> received,
                                         AtomicReference<String> tenant, boolean returnDirect) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("host_lookup").description("Read a fixture.")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"integer\"}}}")
                        .build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(returnDirect).build();
            }

            @Override
            public String call(String arguments) {
                throw new AssertionError("the ADK tool context must be provided");
            }

            @Override
            public String call(String arguments, org.springframework.ai.chat.model.ToolContext context) {
                calls.incrementAndGet();
                received.set(context);
                tenant.set(MDC.get("tenantId"));
                return "receipt_1";
            }
        };
    }

    private static boolean messageInCause(Throwable error, String message) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(message)) {
                return true;
            }
        }
        return false;
    }
}
