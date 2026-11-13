package top.egon.cola.component.agentflow.contract;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import top.egon.cola.component.agentflow.autoconfigure.AgentExecutionAutoConfiguration;
import top.egon.cola.component.agentflow.autoconfigure.AgentFlowAutoConfiguration;
import top.egon.cola.component.agentflow.api.execution.AgentExecutor;
import top.egon.cola.component.agentflow.api.context.ContextQuery;
import top.egon.cola.component.agentflow.api.session.SessionStateResult;
import top.egon.cola.component.common.core.exception.CommonException;

import top.egon.cola.component.agentflow.api.capability.CallPermitVO;
import top.egon.cola.component.agentflow.api.context.ContextFragmentResult;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionContextVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionEvent;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionHandle;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionResult;
import top.egon.cola.component.agentflow.api.session.SessionStore;
import top.egon.cola.component.agentflow.api.session.SessionWriteCommand;
import top.egon.cola.component.agentflow.context.DefaultContextAssembler;
import top.egon.cola.component.agentflow.execution.DefaultAgentExecutor;
import top.egon.cola.component.agentflow.session.InMemorySessionStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentExecutorConformanceTest {

    private final ApplicationContextRunner spring = new ApplicationContextRunner()
            .withClassLoader(new FilteredClassLoader("org.springframework.jdbc", "org.postgresql",
                    "top.egon.cola.component.rag", "top.egon.cola.platform.tianji"))
            .withConfiguration(AutoConfigurations.of(AgentExecutionAutoConfiguration.class,
                    AgentFlowAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withPropertyValues("egon.cola.component.agent-flow.enabled=true",
                    "egon.cola.component.agent-flow-registry.enabled=false");

    @Test
    void discoversNamedFakeAdapterAndDefaultSessionInSpringHost() {
        var clock = Clock.systemUTC();
        spring.withBean("fake", DefaultAgentExecutor.ExecutionAdapter.class,
                () -> invocation -> {
                    assertThat(invocation.readSession().found()).isFalse();
                    assertThat(invocation.writeSession(sessionWrite("create", 0, "session")).revision()).isEqualTo(1);
                    return completed(clock);
                }).run(context -> {
                    assertThat(context).hasNotFailed().hasBean("agentExecutor").hasBean("agentSessionStore")
                            .hasBean("agentContextAssembler").hasBean("agentFlowFactory");
                    var handle = context.getBean(AgentExecutor.class).execute(command(),
                            context(scope("tenant-a"), clock, false));
                    var subscriber = new RecordingSubscriber(true);
                    handle.events().subscribe(subscriber);
                    assertThat(result(handle).output()).containsEntry("answer", "ok");
                    await(subscriber.finished);
                    assertThat(subscriber.events).extracting(AgentExecutionEvent::type)
                            .containsExactly(AgentExecutionEvent.TypeEnum.TERMINAL);
                });
    }

    @Test
    void onlyEnablesNeutralBeansWhenHostOptsInAndFailsExecutionWithoutAdapter() {
        spring.withPropertyValues("egon.cola.component.agent-flow.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AgentExecutor.class));
        spring.run(context -> {
            assertThat(context).hasNotFailed();
            var handle = context.getBean(AgentExecutor.class).execute(command(),
                    context(scope("tenant-a"), Clock.systemUTC(), false));
            assertThat(result(handle).errorCode()).isEqualTo("EXECUTOR_UNAVAILABLE");
        });
    }

    @Test
    void usesExactConfiguredSessionStoreAndRejectsWrongType() {
        var reads = new AtomicInteger();
        var clock = Clock.systemUTC();
        var namedStore = new SessionStore() {
            @Override
            public SessionStateResult read(ContextScopeVO scope, String sessionId) {
                reads.incrementAndGet();
                return SessionStateResult.absent();
            }

            @Override
            public SessionStateResult compareAndSet(SessionWriteCommand command, ContextScopeVO scope,
                                                     CallPermitVO permit) {
                throw new AssertionError("unexpected write");
            }
        };
        spring.withPropertyValues("egon.cola.component.agent-flow.session.store-ref=chosenStore")
                .withBean("chosenStore", SessionStore.class, () -> namedStore)
                .withBean("fake", DefaultAgentExecutor.ExecutionAdapter.class, () -> invocation -> {
                    invocation.readSession();
                    return completed(clock);
                }).run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean("agentSessionStore");
                    var handle = context.getBean(AgentExecutor.class).execute(command(),
                            context(scope("tenant-a"), clock, false));
                    assertThat(result(handle).status()).isEqualTo(AgentExecutionResult.StatusEnum.COMPLETED);
                    assertThat(reads.get()).isEqualTo(1);
                });
        spring.withPropertyValues("egon.cola.component.agent-flow.session.store-ref=chosenStore")
                .withBean("chosenStore", String.class, () -> "wrong")
                .run(context -> assertThat(context).hasFailed());
        spring.withPropertyValues("egon.cola.component.agent-flow.session.store-ref=inMemory")
                .withBean("agentSessionStore", SessionStore.class, () -> namedStore)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("agentSessionStore")).isSameAs(namedStore);
                });
    }

    @Test
    void slowSubscriberStopsExecutionWithoutAnotherModelCall() throws Exception {
        var calls = new AtomicInteger();
        var started = new CountDownLatch(1);
        var subscribed = new CountDownLatch(1);
        var subscriber = new RecordingSubscriber(false);
        var scope = scope("tenant-a");
        try (var factory = Validation.buildDefaultValidatorFactory();
             var timers = Executors.newSingleThreadScheduledExecutor()) {
            var clock = Clock.systemUTC();
            var validator = factory.getValidator();
            var executor = new DefaultAgentExecutor(clock, validator, new DefaultContextAssembler(clock, validator),
                    timers, new InMemorySessionStore(clock, validator), Map.of("fake", invocation -> {
                        started.countDown();
                        await(subscribed);
                        for (int event = 0; event < 65; event++) {
                            invocation.modelCall("fake-model", calls::incrementAndGet);
                            invocation.emit(AgentExecutionEvent.TypeEnum.PROGRESS, Map.of("step", event));
                        }
                        invocation.modelCall("fake-model", calls::incrementAndGet);
                        return completed(clock);
                    }));
            var handle = executor.execute(command(), context(scope, clock, false));
            await(started);
            handle.events().subscribe(subscriber);
            subscribed.countDown();

            assertThat(result(handle).errorCode()).isEqualTo("BACKPRESSURE_LIMIT");
            assertThat(calls.get()).isEqualTo(65);
            subscriber.subscription.request(Long.MAX_VALUE);
            await(subscriber.finished);
            assertThat(subscriber.events).extracting(AgentExecutionEvent::type)
                    .containsExactly(AgentExecutionEvent.TypeEnum.TERMINAL);
        }
    }

    @Test
    void rejectsRevokedPermitBeforeAnyModelCallAndFinishesOnce() throws Exception {
        var calls = new AtomicInteger();
        var scope = scope("tenant-a");
        try (var factory = Validation.buildDefaultValidatorFactory();
             var timers = Executors.newSingleThreadScheduledExecutor()) {
            var clock = Clock.systemUTC();
            var validator = factory.getValidator();
            var executor = new DefaultAgentExecutor(clock, validator, new DefaultContextAssembler(clock, validator),
                    timers, new InMemorySessionStore(clock, validator), Map.of("fake", invocation -> {
                        invocation.modelCall("fake-model", calls::incrementAndGet);
                        return completed(clock);
                    }));
            var handle = executor.execute(command(), context(scope, clock, true));
            var first = result(handle);
            handle.cancel("late cancellation");

            assertThat(first.errorCode()).isEqualTo("PERMIT_REVOKED");
            assertThat(result(handle)).isSameAs(first);
            assertThat(calls.get()).isZero();
        }
    }

    @Test
    void sessionCasReplaysOriginalRevisionAndIsolatesScopes() {
        var clock = Clock.systemUTC();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var store = new InMemorySessionStore(clock, factory.getValidator());
            var firstScope = scope("tenant-a");
            var secondScope = scope("tenant-b");
            var firstCommand = sessionWrite("create", 0, "first");
            var first = store.compareAndSet(firstCommand, firstScope,
                    permit(firstScope, SessionStore.CAPABILITY_REF, clock, false));
            var updated = store.compareAndSet(sessionWrite("update", 1, "next"), firstScope,
                    permit(firstScope, SessionStore.CAPABILITY_REF, clock, false));

            assertThat(first.revision()).isEqualTo(1);
            assertThat(updated.revision()).isEqualTo(2);
            assertThat(store.compareAndSet(firstCommand, firstScope,
                    permit(firstScope, SessionStore.CAPABILITY_REF, clock, false))).isEqualTo(first);
            assertThat(store.read(firstScope, firstScope.sessionKey())).isEqualTo(updated);
            assertThat(store.read(secondScope, secondScope.sessionKey()).found()).isFalse();
            assertThatThrownBy(() -> store.compareAndSet(sessionWrite("create", 0, "changed"), firstScope,
                    permit(firstScope, SessionStore.CAPABILITY_REF, clock, false)))
                    .hasMessageContaining("COMMAND_CONFLICT");
            assertThatThrownBy(() -> store.compareAndSet(sessionWrite("stale", 1, "stale"), firstScope,
                    permit(firstScope, SessionStore.CAPABILITY_REF, clock, false)))
                    .hasMessageContaining("VERSION_CONFLICT");
            assertThatThrownBy(() -> store.compareAndSet(firstCommand, firstScope,
                    permit(secondScope, SessionStore.CAPABILITY_REF, clock, false)))
                    .hasMessageContaining("PERMIT_MISMATCH");
        }
    }

    @Test
    void onlyOneConcurrentDifferentCommandCanWinTheSameRevision() throws Exception {
        var clock = Clock.systemUTC();
        var scope = scope("tenant-a");
        try (var factory = Validation.buildDefaultValidatorFactory();
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var store = new InMemorySessionStore(clock, factory.getValidator());
            var competing = new CyclicBarrier(2);
            List<java.util.concurrent.Future<String>> attempts = new ArrayList<>();
            for (String key : List.of("a", "b")) {
                attempts.add(workers.submit(() -> {
                    competing.await(5, TimeUnit.SECONDS);
                    try {
                        store.compareAndSet(sessionWrite(key, 0, key), scope,
                                permit(scope, SessionStore.CAPABILITY_REF, clock, false));
                        return "COMMITTED";
                    } catch (CommonException failure) {
                        return failure.getStatus();
                    }
                }));
            }
            assertThat(List.of(attempts.get(0).get(5, TimeUnit.SECONDS), attempts.get(1).get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("COMMITTED", "VERSION_CONFLICT");
            assertThat(store.read(scope, scope.sessionKey()).revision()).isEqualTo(1);
        }
    }

    @Test
    void checksEveryFragmentAndDoesNotTrustProviderMutationsToQuery() throws Exception {
        var clock = Clock.systemUTC();
        var scope = scope("tenant-a");
        String content = "data only";
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
        var valid = new ContextFragmentResult.FragmentVO("one", "source", 1, content, digest, clock.instant());
        var wrong = new ContextFragmentResult.FragmentVO("two", "other", 1, content, digest, clock.instant());
        var query = new ContextQuery("source", "retrieve", 32, 1);
        var permit = permit(scope, "source", clock, false);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var assembler = new DefaultContextAssembler(clock, factory.getValidator());
            assertThat(assembler.resolve(query, scope, (received, exactScope, exactPermit) -> {
                received.setSourceRef("other").setMaxCharacters(1);
                assertThat(exactScope).isEqualTo(scope);
                assertThat(exactPermit).isSameAs(permit);
                return new ContextFragmentResult(List.of(valid), null);
            }, permit, 1024).fragments()).containsExactly(valid);
            assertThat(assembler.resolve(query, scope,
                    (received, exactScope, exactPermit) -> new ContextFragmentResult(List.of(valid), null),
                    permit, 16).fragments()).isEmpty();
            assertThatThrownBy(() -> assembler.resolve(query, scope,
                    (received, exactScope, exactPermit) -> new ContextFragmentResult(List.of(valid, wrong), null),
                    permit, 1024)).hasMessageContaining("INVALID_CONTEXT_FRAGMENT");
        }
    }

    @Test
    void cancellationAndExpiryStopCallsAndLateCompletion() throws Exception {
        var scope = scope("tenant-a");
        var clock = Clock.systemUTC();
        var started = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var factory = Validation.buildDefaultValidatorFactory();
             var timers = Executors.newSingleThreadScheduledExecutor()) {
            var validator = factory.getValidator();
            var executor = new DefaultAgentExecutor(clock, validator, new DefaultContextAssembler(clock, validator),
                    timers, new InMemorySessionStore(clock, validator), Map.of("fake", invocation -> {
                        started.countDown();
                        try {
                            TimeUnit.MINUTES.sleep(1);
                        } catch (InterruptedException interrupted) {
                            try {
                                invocation.modelCall("fake-model", calls::incrementAndGet);
                            } finally {
                                stopped.countDown();
                            }
                        }
                        return completed(clock);
                    }));
            var handle = executor.execute(command(), context(scope, clock, false));
            await(started);
            handle.cancel("first");
            var first = result(handle);
            handle.cancel("second");
            await(stopped);
            assertThat(first.status()).isEqualTo(AgentExecutionResult.StatusEnum.CANCELLED);
            assertThat(result(handle)).isSameAs(first);
            assertThat(calls.get()).isZero();
            var expired = new AgentExecutionContextVO(scope, clock.instant(), new CompletableFuture<>(),
                    (query, exactScope, permit) -> new ContextFragmentResult(List.of(), null), null,
                    (exactScope, capability, purpose) -> {
                        calls.incrementAndGet();
                        return permit(scope, capability, clock, false);
                    });
            assertThat(result(executor.execute(command(), expired)).status())
                    .isEqualTo(AgentExecutionResult.StatusEnum.TIMED_OUT);
            assertThat(calls.get()).isZero();
        }
    }

    @Test
    void rootCannotCompleteWhileAParallelToolIsStillInFlight() {
        var clock = Clock.systemUTC();
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        spring.withBean("fake", DefaultAgentExecutor.ExecutionAdapter.class, () -> invocation -> {
            Thread.startVirtualThread(() -> {
                try {
                    invocation.toolCall("write-tool", true, () -> {
                        started.countDown();
                        try {
                            TimeUnit.MINUTES.sleep(1);
                        } catch (InterruptedException expected) {
                            interrupted.countDown();
                        }
                        return "late";
                    });
                } catch (CommonException expected) {
                    assertThat(expected.getStatus()).isEqualTo("EXECUTION_STOPPED");
                }
            });
            await(started);
            return completed(clock);
        }).run(context -> {
            var handle = context.getBean(AgentExecutor.class).execute(command(),
                    context(scope("tenant-a"), clock, false));
            var terminal = result(handle);
            await(interrupted);
            assertThat(terminal.status()).isEqualTo(AgentExecutionResult.StatusEnum.FAILED);
            assertThat(terminal.errorCode()).isEqualTo("INVALID_ROOT_RESULT");
            assertThat(terminal.sideEffectOutcome()).isEqualTo(AgentExecutionResult.SideEffectOutcomeEnum.UNKNOWN);
        });
    }

    @Test
    void rejectsRootJacksonUseOfTrustedHostObjects() {
        var jackson2 = new com.fasterxml.jackson.databind.ObjectMapper();
        var jackson3 = tools.jackson.databind.json.JsonMapper.builder().build();
        var clock = Clock.systemUTC();
        var scope = scope("tenant-a");
        var host = context(scope, clock, false);
        var authorization = permit(scope, "session", clock, false);
        for (Object secret : List.of(host, authorization)) {
            assertThatThrownBy(() -> jackson2.writeValueAsString(secret))
                    .hasMessageContaining("host objects cannot be serialized");
            assertThatThrownBy(() -> jackson3.writeValueAsString(secret))
                    .hasMessageContaining("host objects cannot be serialized");
            assertThatThrownBy(() -> jackson2.readValue("{}", secret.getClass()))
                    .hasMessageContaining("host objects cannot be deserialized");
            assertThatThrownBy(() -> jackson3.readValue("{}", secret.getClass()))
                    .hasMessageContaining("host objects cannot be deserialized");
        }
    }

    @Test
    void rejectsInvalidInputBeforeDispatchAndRejectsRootOutputAgainstFrozenSchema() throws Exception {
        var clock = Clock.systemUTC();
        var calls = new AtomicInteger();
        spring.withBean("fake", DefaultAgentExecutor.ExecutionAdapter.class, () -> invocation -> {
            calls.incrementAndGet();
            return new AgentExecutionResult("run", AgentExecutionResult.StatusEnum.COMPLETED,
                    Map.of("unexpected", true), null, AgentExecutionResult.SideEffectOutcomeEnum.NONE, clock.instant());
        }).run(context -> {
            var executor = context.getBean(AgentExecutor.class);
            var host = context(scope("tenant-a"), clock, false);
            assertThatThrownBy(() -> executor.execute(command().setParameters(Map.of("unregistered", "value")), host))
                    .hasMessageContaining("Published input or parameter schema mismatch");
            assertThat(calls.get()).isZero();
            assertThat(result(executor.execute(command(), host)).errorCode()).isEqualTo("INVALID_OUTPUT");
            assertThat(calls.get()).isEqualTo(1);
        });
    }

    @Test
    void dispatchedToolTimeoutInterruptsCallAndPreservesUnknownAfterLateResult() throws Exception {
        var clock = Clock.systemUTC();
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var seconds = Duration.ofSeconds(10);
        var command = command().setLimits(new AgentExecutionCommand.TechnicalLimitsVO(seconds, seconds, seconds,
                Duration.ofMillis(250), 2, 2, 2, 1024, 128));
        spring.withBean("fake", DefaultAgentExecutor.ExecutionAdapter.class,
                () -> invocation -> {
                    invocation.toolCall("write-tool", true, () -> {
                        started.countDown();
                        try {
                            TimeUnit.MINUTES.sleep(1);
                        } catch (InterruptedException expected) {
                            interrupted.countDown();
                        }
                        return "late";
                    });
                    return completed(clock);
                }).run(context -> {
                    var handle = context.getBean(AgentExecutor.class).execute(command,
                            context(scope("tenant-a"), clock, false));
                    await(started);
                    var terminal = result(handle);
                    await(interrupted);
                    assertThat(terminal.status()).isEqualTo(AgentExecutionResult.StatusEnum.TIMED_OUT);
                    assertThat(terminal.errorCode()).isEqualTo("TOOL_TIMEOUT");
                    assertThat(terminal.sideEffectOutcome()).isEqualTo(AgentExecutionResult.SideEffectOutcomeEnum.UNKNOWN);
                    assertThat(result(handle)).isSameAs(terminal);
                });
    }

    @Test
    void doesNotExposeSessionAfterHostRevokesReadInFlight() throws Exception {
        var clock = Clock.systemUTC();
        var revoked = new AtomicBoolean();
        var scope = scope("tenant-a");
        SessionStore store = new SessionStore() {
            @Override
            public SessionStateResult read(ContextScopeVO scope, String sessionId) {
                revoked.set(true);
                return new SessionStateResult(true, 1, sessionWrite("create", 0, "withheld").getState());
            }

            @Override
            public SessionStateResult compareAndSet(SessionWriteCommand command, ContextScopeVO scope,
                                                     CallPermitVO permit) {
                throw new AssertionError("unexpected write");
            }
        };
        var host = new AgentExecutionContextVO(scope, clock.instant().plusSeconds(10), new CompletableFuture<>(),
                (query, exactScope, permit) -> new ContextFragmentResult(List.of(), null), store,
                (exactScope, capability, purpose) -> new CallPermitVO("permit", capability,
                        exactScope.scopeHash(), 1, null, clock.instant().plusSeconds(10),
                        () -> false, (permit, recheckedPurpose) -> !revoked.get()));
        spring.withBean("fake", DefaultAgentExecutor.ExecutionAdapter.class, () -> invocation -> {
            invocation.readSession();
            throw new AssertionError("Revoked session escaped into execution");
        }).run(context -> {
            var executor = context.getBean(AgentExecutor.class);
            assertThat(result(executor.execute(command(), host)).errorCode()).isEqualTo("PERMIT_REVOKED");
        });
    }

    private static ContextScopeVO scope(String tenant) {
        return new ContextScopeVO("test", tenant, "principal", "instance", "session", "execute");
    }

    private static AgentExecutionContextVO context(ContextScopeVO scope, Clock clock, boolean revoked) {
        return new AgentExecutionContextVO(scope, clock.instant().plusSeconds(10), new CompletableFuture<>(),
                (query, scoped, permit) -> new ContextFragmentResult(List.of(), null), null,
                (scoped, capability, purpose) -> permit(scoped, capability, clock, revoked && "MODEL_CALL".equals(purpose)));
    }

    private static CallPermitVO permit(ContextScopeVO scope, String capability, Clock clock, boolean revoked) {
        return new CallPermitVO("permit", capability, scope.scopeHash(), 1, null,
                clock.instant().plusSeconds(10), () -> false, (permit, purpose) -> !revoked);
    }

    private static AgentExecutionCommand command() {
        var duration = Duration.ofSeconds(10);
        return AgentExecutionCommand.builder()
                .executionId("run")
                .template(new AgentExecutionCommand.TemplateVO("template", 1, "a".repeat(64), "fake", Map.of(),
                        new AgentExecutionCommand.SchemaValidator() {
                            @Override
                            public void validateInput(Map<String, Object> input, Map<String, Object> parameters) {
                                if (!input.isEmpty() || !parameters.isEmpty()) {
                                    throw new IllegalArgumentException("Published input or parameter schema mismatch");
                                }
                            }

                            @Override
                            public void validateOutput(Map<String, Object> output) {
                                if (!output.equals(Map.of("answer", "ok"))) {
                                    throw new IllegalArgumentException("Published output schema mismatch");
                                }
                            }
                        }))
                .input(Map.of())
                .limits(new AgentExecutionCommand.TechnicalLimitsVO(duration, duration, duration, duration,
                        100, 100, 1, 1024, 128))
                .build();
    }

    private static AgentExecutionResult completed(Clock clock) {
        return new AgentExecutionResult("run", AgentExecutionResult.StatusEnum.COMPLETED,
                Map.of("answer", "ok"), null, AgentExecutionResult.SideEffectOutcomeEnum.NONE, clock.instant());
    }

    private static AgentExecutionResult result(AgentExecutionHandle handle) throws Exception {
        return handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static SessionWriteCommand sessionWrite(String commandId, long revision, String content) {
        return new SessionWriteCommand("session", revision, commandId,
                Map.of("schemaVersion", 1, "messages", List.of(content), "localState", Map.of()));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Fixture interrupted", failure);
        }
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<AgentExecutionEvent> {

        private final List<AgentExecutionEvent> events = new ArrayList<>();
        private final CountDownLatch finished = new CountDownLatch(1);
        private final boolean eager;
        private Flow.Subscription subscription;

        private RecordingSubscriber(boolean eager) {
            this.eager = eager;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (eager) {
                subscription.request(Long.MAX_VALUE);
            }
        }

        @Override
        public void onNext(AgentExecutionEvent event) {
            events.add(event);
        }

        @Override
        public void onError(Throwable failure) {
            finished.countDown();
        }

        @Override
        public void onComplete() {
            finished.countDown();
        }
    }
}
