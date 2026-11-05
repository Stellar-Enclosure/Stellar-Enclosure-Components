package top.egon.cola.component.agentflow.compatibility;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.Plugin;
import com.google.adk.sessions.InMemorySessionService;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import top.egon.cola.component.agentflow.runtime.AgentFlowSessionRunner;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static top.egon.cola.component.agentflow.compatibility.AdkCompatibilityFixtures.*;

@Timeout(10)
class AgentFlowSessionRunnerTest {
    @Test
    void supplied_session_service_survives_runner_replacement_and_supplies_prior_history() {
        var sessions = new InMemorySessionService(InMemorySessionService.DuplicateSessionIdBehavior.REJECT);
        var session = sessions.createSession(APP, USER, java.util.Map.of(), "shared-session").blockingGet();
        var firstModel = new ScriptedChatModel();
        AtomicInteger governedCalls = new AtomicInteger();
        com.google.adk.plugins.Plugin plugin = new com.google.adk.plugins.Plugin() {
            @Override public String getName() { return "fixture-governance"; }
            @Override public io.reactivex.rxjava3.core.Maybe<LlmResponse> beforeModelCallback(
                    com.google.adk.agents.CallbackContext context, com.google.adk.models.LlmRequest.Builder request) {
                governedCalls.incrementAndGet();
                return io.reactivex.rxjava3.core.Maybe.empty();
            }
        };
        var first = new AgentFlowSessionRunner(agent("assistant", firstModel), APP, sessions, List.of(plugin));
        try {
            assertThat(first.sessionService()).isSameAs(sessions);
            first.runAsync(USER, session.id(), Content.fromParts(Part.fromText("first question"))).toList().blockingGet();
            assertThat(governedCalls).hasValue(1);
        } finally {
            first.close().blockingAwait();
        }
        var secondModel = new ScriptedChatModel();
        var second = new AgentFlowSessionRunner(agent("assistant", secondModel), APP, sessions, List.of());
        try {
            second.runAsync(USER, session.id(), Content.fromParts(Part.fromText("second question"))).toList().blockingGet();
            assertThat(secondModel.prompts).hasSize(1);
            assertThat(secondModel.prompts.getFirst().getInstructions().stream().map(message -> message.getText()).toList())
                    .contains("first question", "fixture answer.", "second question");
        } finally {
            second.close().blockingAwait();
        }
    }

    @Test
    void explicit_resume_reuses_the_ordinary_producers_exact_invocation_without_a_second_user_event() throws Exception {
        var sessions = new InMemorySessionService(InMemorySessionService.DuplicateSessionIdBehavior.REJECT);
        var session = sessions.createSession(APP, USER).blockingGet();
        var config = explicitConfig();
        String invocation = ordinaryProducerCut(sessions, session.id(), config);
        var model = new ScriptedChatModel();
        var second = AgentFlowSessionRunner.withExplicitResume(agent("assistant", model), APP, sessions, List.of());
        try {
            var output = second.runAsync(USER, session.id(), invocation, null, config, null)
                    .timeout(3, TimeUnit.SECONDS).toList().blockingGet();
            assertThat(output).isNotEmpty().allMatch(event -> invocation.equals(event.invocationId()));
            assertThat(model.prompts).hasSize(1);
            var history = sessions.getSession(APP, USER, session.id(), Optional.empty()).blockingGet().immutableEvents();
            assertThat(history.stream().filter(event -> "user".equals(event.author())).toList()).hasSize(1);
            assertThat(history).allMatch(event -> invocation.equals(event.invocationId()));
            assertThat(second.runAsync(USER, session.id(), invocation, null, config, null)
                    .timeout(3, TimeUnit.SECONDS).toList().blockingGet()).isEmpty();
            assertThat(model.prompts).hasSize(1);
        } finally {
            assertThat(second.close().blockingAwait(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void explicit_resume_cancels_the_cached_model_subscription() throws Exception {
        for (boolean closeRunner : List.of(false, true)) {
            var sessions = new InMemorySessionService(InMemorySessionService.DuplicateSessionIdBehavior.REJECT);
            var session = sessions.createSession(APP, USER).blockingGet();
            var config = explicitConfig();
            String invocation = ordinaryProducerCut(sessions, session.id(), config);
            var model = new ScriptedChatModel();
            var tenant = new AtomicReference<String>();
            model.streaming = prompt -> Flux.defer(() -> {
                tenant.set(MDC.get("tenantId"));
                return Flux.<ChatResponse>never();
            });
            var runner = AgentFlowSessionRunner.withExplicitResume(agent("assistant", model), APP, sessions, List.of());
            MDC.put("tenantId", "tenant-explicit");
            TestSubscriber<Event> active = runner.runAsync(USER, session.id(), invocation, null, config, null).test();
            try {
                assertThat(model.streamStarted.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(tenant).hasValue("tenant-explicit");
                if (closeRunner) {
                    assertThat(runner.close().blockingAwait(3, TimeUnit.SECONDS)).isTrue();
                } else {
                    active.cancel();
                }
                assertThat(model.streamCancelled.await(3, TimeUnit.SECONDS))
                        .as("the cached native model must be disposed").isTrue();
                assertThat(MDC.get("tenantId")).isEqualTo("tenant-explicit");
            } finally {
                active.cancel();
                assertThat(runner.close().blockingAwait(3, TimeUnit.SECONDS)).isTrue();
                MDC.remove("tenantId");
            }
        }
    }

    @Test
    void explicit_resume_rejects_new_input_state_and_auto_creation() {
        var sessions = new InMemorySessionService(InMemorySessionService.DuplicateSessionIdBehavior.REJECT);
        var session = sessions.createSession(APP, USER).blockingGet();
        var model = new ScriptedChatModel();
        var runner = AgentFlowSessionRunner.withExplicitResume(agent("assistant", model), APP, sessions, List.of());
        try {
            var config = explicitConfig();
            runner.runAsync(USER, session.id(), "invocation", input(), config, null).test().assertError(IllegalArgumentException.class);
            runner.runAsync(USER, session.id(), "invocation", null, config, Map.of("state", 1))
                    .test().assertError(IllegalArgumentException.class);
            runner.runAsync(USER, session.id(), "invocation", null, RunConfig.builder().setAutoCreateSession(true).build(), null)
                    .test().assertError(IllegalArgumentException.class);
            runner.runAsync(USER, session.id(), "", null, config, null).test().assertError(IllegalArgumentException.class);
            runner.runAsync(USER, session.id(), "unknown-invocation", null, config, null)
                    .timeout(3, TimeUnit.SECONDS).test().awaitDone(3, TimeUnit.SECONDS)
                    .assertNoValues().assertError(IllegalArgumentException.class);
            runner.runAsync(USER, "missing-session", "invocation", null, config, null)
                    .timeout(3, TimeUnit.SECONDS).test().awaitDone(3, TimeUnit.SECONDS)
                    .assertNoValues().assertError(IllegalArgumentException.class);
            assertThat(model.prompts).isEmpty();
            assertThat(sessions.getSession(APP, USER, session.id(), Optional.empty()).blockingGet().immutableEvents()).isEmpty();
        } finally {
            assertThat(runner.close().blockingAwait(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static RunConfig explicitConfig() {
        return RunConfig.builder().setStreamingMode(RunConfig.StreamingMode.SSE).setAutoCreateSession(false).build();
    }

    private static String ordinaryProducerCut(InMemorySessionService sessions, String sessionId, RunConfig config)
            throws InterruptedException {
        var originalModel = new ScriptedChatModel();
        var cut = new CountDownLatch(1);
        var pause = new Plugin() {
            @Override public String getName() { return "fixture-pre-model-cut"; }
            @Override public Maybe<LlmResponse> beforeModelCallback(CallbackContext context, LlmRequest.Builder request) {
                return Maybe.defer(() -> {
                    cut.countDown();
                    return Maybe.never();
                });
            }
        };
        var runner = new AgentFlowSessionRunner(agent("assistant", originalModel), APP, sessions, List.of(pause));
        TestSubscriber<Event> active = runner.runAsync(USER, sessionId, input(), config).test();
        try {
            assertThat(cut.await(3, TimeUnit.SECONDS)).isTrue();
            var history = sessions.getSession(APP, USER, sessionId, Optional.empty()).blockingGet().immutableEvents();
            assertThat(history).hasSize(1);
            assertThat(history.getFirst().author()).isEqualTo("user");
            assertThat(originalModel.prompts).isEmpty();
            String invocation = history.getFirst().invocationId();
            assertThat(invocation).isNotBlank();
            return invocation;
        } finally {
            active.cancel();
            assertThat(runner.close().blockingAwait(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void supplied_session_runner_propagates_trace_and_cancels_the_model_subscription() throws InterruptedException {
        var sessions = new InMemorySessionService(InMemorySessionService.DuplicateSessionIdBehavior.REJECT);
        var session = sessions.createSession(APP, USER).blockingGet();
        var model = new ScriptedChatModel();
        AtomicReference<String> tenant = new AtomicReference<>();
        model.streaming = prompt -> Flux.defer(() -> {
            tenant.set(MDC.get("tenantId"));
            return Flux.<ChatResponse>never();
        });
        var runner = new AgentFlowSessionRunner(agent("assistant", model), APP, sessions, List.of());
        MDC.put("tenantId", "tenant-101");
        TestSubscriber<Event> subscriber = runner.runAsync(USER, session.id(), input(),
                RunConfig.builder().setStreamingMode(RunConfig.StreamingMode.SSE).build()).test();
        try {
            assertThat(model.streamStarted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(tenant).hasValue("tenant-101");
            subscriber.cancel();
            assertThat(model.streamCancelled.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(MDC.get("tenantId")).isEqualTo("tenant-101");
        } finally {
            subscriber.cancel();
            assertThat(runner.close().blockingAwait(3, TimeUnit.SECONDS)).isTrue();
            MDC.remove("tenantId");
        }
    }
}
