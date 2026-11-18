package top.egon.cola.component.common.id.autoconfigure;

import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import top.egon.cola.component.common.id.generator.IdGenerator;
import top.egon.cola.component.common.id.generator.LongIdGenerator;
import top.egon.cola.component.common.id.provider.MachineIdProvider;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IdGeneratorAutoConfigurationTest {

    private static final String BOUND_MACHINE_ID = "0";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdGeneratorAutoConfiguration.class));

    /** Binding happens once per process, so every successful context in this JVM uses one configuration. */
    private final ApplicationContextRunner bindingContextRunner = contextRunner
            .withPropertyValues("egon.cola.component.id.machine-id=" + BOUND_MACHINE_ID);

    @Test
    void validConfigurationStartsWithoutPublishingAGeneratorBean() {
        bindingContextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(IdGeneratorProperties.class);
            context.getBean(IdGeneratorAutoConfiguration.class).assertIssuanceAvailable();
            assertThat(context).doesNotHaveBean(SnowflakeIdGenerator.class);
            assertThat(context).doesNotHaveBean(LongIdGenerator.class);
        });
    }

    @Test
    void repeatedContextWithTheSameConfigurationRebindsNothing() {
        bindingContextRunner.run(first -> assertThat(first).hasNotFailed());
        bindingContextRunner.run(second -> assertThat(second).hasNotFailed());
    }

    @Test
    void disabledConfigurationCreatesNoPropertiesAndNoBinding() {
        contextRunner.withPropertyValues("egon.cola.component.id.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(IdGeneratorProperties.class);
                    assertThat(context).doesNotHaveBean(IdGenerator.class);
                });
    }

    @Test
    void missingMachineIdFailsFast() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "egon.cola.component.id.machine-id must be configured when enabled=true");
        });
    }

    @Test
    void machineIdOutsideRangeFailsFast() {
        contextRunner.withPropertyValues("egon.cola.component.id.machine-id=-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "egon.cola.component.id.machine-id must be between 0 and 1023: -1");
                });
        contextRunner.withPropertyValues("egon.cola.component.id.machine-id=1024")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "egon.cola.component.id.machine-id must be between 0 and 1023: 1024");
                });
    }

    @Test
    void negativeClockBackwardFailsFast() {
        contextRunner.withPropertyValues(
                        "egon.cola.component.id.machine-id=1",
                        "egon.cola.component.id.max-clock-backward=-1ms")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "egon.cola.component.id.max-clock-backward must not be negative: PT-0.001S");
                });
    }

    @Test
    void defaultAndConfiguredDurationAreBound() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesBindingConfiguration.class)
                .run(context -> {
                    IdGeneratorProperties properties = context.getBean(IdGeneratorProperties.class);
                    assertThat(properties.getMaxClockBackward()).isEqualTo(Duration.ofMillis(5));
                    assertThat(properties.getRedis().isEnabled()).isTrue();
                    assertThat(properties.getRedis().getLeaseDuration()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(properties.getRedis().getRenewInterval()).isEqualTo(Duration.ofMinutes(1));
                });
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesBindingConfiguration.class)
                .withPropertyValues(
                        "egon.cola.component.id.max-clock-backward=7ms",
                        "egon.cola.component.id.redis.env=prod",
                        "egon.cola.component.id.redis.app=order-service",
                        "egon.cola.component.id.redis.lease-duration=10m",
                        "egon.cola.component.id.redis.renew-interval=2m")
                .run(context -> {
                    IdGeneratorProperties properties = context.getBean(IdGeneratorProperties.class);
                    assertThat(properties.getMaxClockBackward()).isEqualTo(Duration.ofMillis(7));
                    assertThat(properties.getRedis().getEnv()).isEqualTo("prod");
                    assertThat(properties.getRedis().getApp()).isEqualTo("order-service");
                    assertThat(properties.getRedis().getLeaseDuration()).isEqualTo(Duration.ofMinutes(10));
                    assertThat(properties.getRedis().getRenewInterval()).isEqualTo(Duration.ofMinutes(2));
                });
    }

    @Test
    void customMachineIdProviderSuppliesMachineIdWhenStaticPropertyIsAbsent() {
        IsolatedEngine engine = isolatedEngine();
        AtomicBoolean invoked = new AtomicBoolean();
        MachineIdProvider provider = () -> {
            invoked.set(true);
            return 0L;
        };

        engine.runner().withBean(MachineIdProvider.class, () -> provider)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(invoked).isTrue();
                    assertThat(engine.nextLongId()).isPositive();
                });
    }

    @Test
    void explicitStaticMachineIdTakesPrecedenceOverRedisAndCustomProvider() {
        MachineIdProvider provider = mock(MachineIdProvider.class);
        RedissonClient redissonClient = mock(RedissonClient.class);

        bindingContextRunner
                .withBean(MachineIdProvider.class, () -> provider)
                .withBean("redissonClient", RedissonClient.class, () -> redissonClient)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(IdGeneratorAutoConfiguration.class).assertIssuanceAvailable();
                    verifyNoInteractions(provider, redissonClient);
                });
    }

    @Test
    void redisLeaseAllocatesOnStartupAndReleasesSlotWhenContextCloses() {
        IsolatedEngine engine = isolatedEngine();
        AtomicReference<Object> configuration = new AtomicReference<>();
        FakeRedisLeaseEval fakeRedis = new FakeRedisLeaseEval();
        String slotKey = "egon:cola:id:{prod:order-service}:slot:0";

        engine.runner()
                .withBean("redissonClient", RedissonClient.class, fakeRedis::client)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "spring.application.name=order-service")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    configuration.set(context.getBean(engine.configuration()));
                    assertThat(configuration.get().getClass().getClassLoader())
                            .isSameAs(engine.snowflake().getClassLoader());
                    engine.assertIssuanceAvailable(configuration.get());
                    assertThat(fakeRedis.slots.get(slotKey)).startsWith("A|");
                    assertThat(engine.nextLongId()).isPositive();
                });

        assertThat(fakeRedis.slots.get(slotKey)).startsWith("R|");
        assertThat(fakeRedis.releases.get()).isEqualTo(1);
        assertThatThrownBy(() -> engine.assertIssuanceAvailable(configuration.get()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(engine::nextLongId).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(engine::nextId).isInstanceOf(RuntimeException.class);
    }

    @Test
    void customProviderHasPriorityOverRedisAndRetainsItsLiveIssuanceCheck() {
        IsolatedEngine engine = isolatedEngine();
        MutableMachineProvider provider = new MutableMachineProvider();
        RedissonClient redis = mock(RedissonClient.class);
        engine.runner().withBean(MachineIdProvider.class, () -> provider)
                .withBean("redissonClient", RedissonClient.class, () -> redis)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Object configuration = context.getBean(engine.configuration());
                    engine.assertIssuanceAvailable(configuration);
                    engine.assertIssuanceAvailable(configuration);
                    assertThat(provider.issues.get()).isZero();
                    assertThat(engine.nextLongId()).isPositive();
                    assertThat(provider.issues.get()).isEqualTo(1);
                    provider.available.set(false);
                    assertThatThrownBy(() -> engine.assertIssuanceAvailable(configuration))
                            .hasMessage("custom issuance fenced");
                    assertThat(provider.issues.get()).isEqualTo(1);
                    assertThatThrownBy(engine::nextLongId).hasMessage("custom issuance fenced");
                    assertThatThrownBy(engine::nextId).hasMessage("custom issuance fenced");
                    verifyNoInteractions(redis);
                });
        // Spring owns a custom provider bean; the auto-configuration must not close it a second time.
        assertThat(provider.closes.get()).isEqualTo(1);
    }

    @Test
    void exactNamedRedissonClientWinsOverOtherCandidates() {
        IsolatedEngine engine = isolatedEngine();
        FakeRedisLeaseEval selected = new FakeRedisLeaseEval();
        RedissonClient other = mock(RedissonClient.class);
        engine.runner().withBean("redissonClient", RedissonClient.class, selected::client)
                .withBean("otherRedis", RedissonClient.class, () -> other)
                .withPropertyValues("egon.cola.component.id.redis.env=prod",
                        "egon.cola.component.id.redis.app=orders")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(engine.nextLongId()).isPositive();
                    verifyNoInteractions(other);
                });
        assertThat(selected.releases.get()).isEqualTo(1);
    }

    @Test
    void uniqueUnnamedRedissonClientAndExplicitPrimaryAreSupported() {
        FakeRedisLeaseEval unique = new FakeRedisLeaseEval();
        isolatedEngine().runner().withBean("dedicatedIdRedis", RedissonClient.class, unique::client)
                .withPropertyValues("egon.cola.component.id.redis.env=prod",
                        "egon.cola.component.id.redis.app=orders")
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(unique.releases.get()).isEqualTo(1);

        FakeRedisLeaseEval primary = new FakeRedisLeaseEval();
        RedissonClient other = mock(RedissonClient.class);
        isolatedEngine().runner().withBean("idRedis", RedissonClient.class, primary::client,
                        definition -> definition.setPrimary(true))
                .withBean("otherRedis", RedissonClient.class, () -> other)
                .withPropertyValues("egon.cola.component.id.redis.env=prod",
                        "egon.cola.component.id.redis.app=orders")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    verifyNoInteractions(other);
                });
        assertThat(primary.releases.get()).isEqualTo(1);
    }

    @Test
    void ambiguousRedissonClientsAndIncorrectlyTypedNamedClientFailClosed() {
        RedissonClient first = mock(RedissonClient.class);
        RedissonClient second = mock(RedissonClient.class);
        isolatedEngine().runner().withBean("firstRedis", RedissonClient.class, () -> first)
                .withBean("secondRedis", RedissonClient.class, () -> second)
                .withPropertyValues("spring.application.name=orders")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("NoUniqueBeanDefinitionException");
                    verifyNoInteractions(first, second);
                });
        isolatedEngine().runner().withBean("redissonClient", String.class, () -> "incorrect bean type")
                .withBean("otherRedis", RedissonClient.class, () -> first)
                .withPropertyValues("spring.application.name=orders")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "Bean 'redissonClient' must be a RedissonClient for Redis machine-id allocation");
                    verifyNoInteractions(first);
                });
    }

    @Test
    void unavailableRedisAndExhaustedPoolCannotStartIssuance() {
        IsolatedEngine unavailable = isolatedEngine();
        RedissonClient redis = mock(RedissonClient.class);
        when(redis.getScript(any(Codec.class))).thenThrow(new IllegalStateException("isolated Redis unavailable"));
        unavailable.runner().withBean("redissonClient", RedissonClient.class, () -> redis)
                .withPropertyValues("egon.cola.component.id.redis.env=prod",
                        "egon.cola.component.id.redis.app=orders")
                .run(context -> assertThat(context).hasFailed());
        assertThatThrownBy(unavailable::nextLongId).isInstanceOf(RuntimeException.class);

        IsolatedEngine exhausted = isolatedEngine();
        FakeRedisLeaseEval full = new FakeRedisLeaseEval();
        full.exhausted = true;
        exhausted.runner().withBean("redissonClient", RedissonClient.class, full::client)
                .withPropertyValues("egon.cola.component.id.redis.env=prod",
                        "egon.cola.component.id.redis.app=orders")
                .run(context -> assertThat(context).hasFailed());
        assertThat(full.slots).isEmpty();
        assertThatThrownBy(exhausted::nextId).isInstanceOf(RuntimeException.class);
    }

    @Test
    void initializationFailureReleasesOnlyItsAcquiredLease() {
        FakeRedisLeaseEval redis = new FakeRedisLeaseEval();
        isolatedEngine().runner().withBean("redissonClient", RedissonClient.class, redis::client)
                .withPropertyValues("egon.cola.component.id.redis.env=prod",
                        "egon.cola.component.id.redis.app=orders",
                        "egon.cola.component.id.max-clock-backward=-1ms")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "egon.cola.component.id.max-clock-backward must not be negative: PT-0.001S");
                });
        assertThat(redis.releases.get()).isEqualTo(1);
        assertThat(redis.slots.values()).allMatch(value -> value.startsWith("R|"));
    }

    @Test
    void releaseTransportFailureStillRevokesTheClosedEnginesIssuance() {
        IsolatedEngine engine = isolatedEngine();
        AtomicReference<Object> configuration = new AtomicReference<>();
        FakeRedisLeaseEval redis = new FakeRedisLeaseEval();
        engine.runner().withBean("redissonClient", RedissonClient.class, redis::client)
                .withPropertyValues("egon.cola.component.id.redis.env=prod",
                        "egon.cola.component.id.redis.app=orders")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    configuration.set(context.getBean(engine.configuration()));
                    engine.assertIssuanceAvailable(configuration.get());
                    assertThat(engine.nextLongId()).isPositive();
                    redis.failRelease = true;
                });
        assertThat(redis.releases.get()).isEqualTo(1);
        assertThatThrownBy(() -> engine.assertIssuanceAvailable(configuration.get()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(engine::nextLongId).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(engine::nextId).isInstanceOf(RuntimeException.class);
    }

    @Test
    void redisAllocationFailsFastWhenAppCannotBeResolved() {
        FakeRedisLeaseEval fakeRedis = new FakeRedisLeaseEval();

        contextRunner
                .withBean("redissonClient", RedissonClient.class, fakeRedis::client)
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "egon.cola.component.id.redis.app must not be blank");
                });
    }

    @Test
    void redisDisabledWithoutStaticMachineIdFailsFast() {
        FakeRedisLeaseEval fakeRedis = new FakeRedisLeaseEval();

        contextRunner
                .withBean("redissonClient", RedissonClient.class, fakeRedis::client)
                .withPropertyValues(
                        "egon.cola.component.id.redis.enabled=false",
                        "spring.application.name=order-service")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "egon.cola.component.id.machine-id must be configured when enabled=true");
                });
    }

    @Test
    void customStringGeneratorMakesDefaultBackOff() {
        IdGenerator custom = () -> "custom";

        contextRunner.withBean(IdGenerator.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(IdGenerator.class)).isSameAs(custom);
                });
    }

    @Test
    void customLongGeneratorMakesDefaultBackOff() {
        LongIdGenerator custom = new FixedLongIdGenerator();

        contextRunner.withBean(LongIdGenerator.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LongIdGenerator.class)).isSameAs(custom);
                });
    }

    @Test
    void bootMetadataRegistersAutoConfigurationWithoutComponentScanning() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()))
                .contains(IdGeneratorAutoConfiguration.class.getName());
        assertNull(IdGeneratorAutoConfiguration.class.getAnnotation(ComponentScan.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IdGeneratorProperties.class)
    static class PropertiesBindingConfiguration {
    }

    /** {@link LongIdGenerator} is no longer a functional interface, so the seam needs a named implementation. */
    private static final class FixedLongIdGenerator implements LongIdGenerator {

        @Override
        public long nextLongId() {
            return 42L;
        }

        @Override
        public String nextId() {
            return Long.toString(nextLongId());
        }
    }

    /** Production has no reset: each dynamic Spring context gets an independent real static binding. */
    private static IsolatedEngine isolatedEngine() {
        String configurationName = IdGeneratorAutoConfiguration.class.getName();
        String snowflakePackage = SnowflakeIdGenerator.class.getPackageName() + ".";
        ClassLoader loader = new ClassLoader(IdGeneratorAutoConfigurationTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(configurationName) && !name.startsWith(configurationName + "$")
                        && !name.startsWith(snowflakePackage)) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        try (InputStream bytes = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (bytes == null) {
                                throw new ClassNotFoundException(name);
                            }
                            byte[] definition = bytes.readAllBytes();
                            loaded = defineClass(name, definition, 0, definition.length);
                        } catch (IOException failure) {
                            throw new ClassNotFoundException(name, failure);
                        }
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
        };
        try {
            return new IsolatedEngine(loader.loadClass(configurationName),
                    loader.loadClass(SnowflakeIdGenerator.class.getName()));
        } catch (ClassNotFoundException failure) {
            throw new AssertionError("isolated common-id production classes must be available", failure);
        }
    }

    private record IsolatedEngine(Class<?> configuration, Class<?> snowflake) {
        ApplicationContextRunner runner() {
            ClassLoader loader = snowflake.getClassLoader();
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            // Boot sorts the auto-configurations by name before the runner installs its class loader.
            thread.setContextClassLoader(loader);
            try {
                return new ApplicationContextRunner().withClassLoader(loader)
                        .withConfiguration(AutoConfigurations.of(configuration));
            } finally {
                thread.setContextClassLoader(previous);
            }
        }

        long nextLongId() {
            return (long) invoke("nextLongId");
        }

        String nextId() {
            return (String) invoke("nextId");
        }

        void assertIssuanceAvailable(Object configurationBean) {
            invoke(configuration, configurationBean, "assertIssuanceAvailable");
        }

        private Object invoke(String method) {
            return invoke(snowflake, null, method);
        }

        private Object invoke(Class<?> type, Object target, String method) {
            try {
                return type.getMethod(method).invoke(target);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new AssertionError(failure.getCause());
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static final class MutableMachineProvider implements MachineIdProvider, AutoCloseable {
        private final AtomicBoolean available = new AtomicBoolean(true);
        private final AtomicInteger closes = new AtomicInteger();
        private final AtomicInteger issues = new AtomicInteger();

        @Override
        public long getMachineId() {
            return 0L;
        }

        @Override
        public void assertIssuanceAvailable() {
            if (!available.get()) {
                throw new IllegalStateException("custom issuance fenced");
            }
        }

        @Override
        public long issue(LongSupplier generator, LongUnaryOperator timestampDecoder) {
            issues.incrementAndGet();
            return MachineIdProvider.super.issue(generator, timestampDecoder);
        }

        @Override
        public void close() {
            available.set(false);
            closes.incrementAndGet();
        }
    }

    private static final class FakeRedisLeaseEval {

        private final Map<String, Integer> cursors = new HashMap<>();
        private final Map<String, String> slots = new HashMap<>();
        private final AtomicInteger releases = new AtomicInteger();
        private boolean exhausted;
        private boolean failRelease;

        private RedissonClient client() {
            RedissonClient client = mock(RedissonClient.class);
            RScript script = mock(RScript.class);
            when(client.getScript(any(Codec.class))).thenReturn(script);
            when(script.eval(any(), anyString(), any(), anyList(), any(Object[].class)))
                    .thenAnswer(invocation -> {
                        Object[] raw = invocation.getArguments();
                        String lua = invocation.getArgument(1);
                        List<Object> keys = invocation.getArgument(3);
                        Object[] args = Arrays.copyOfRange(raw, 4, raw.length);
                        String key = keys.getFirst().toString();
                        if (lua.contains("common-id:machine-lease:acquire")) {
                            assertThat(keys).hasSize(1025);
                            String instanceId = args[0].toString();
                            long now = System.currentTimeMillis();
                            long expiry = now + Long.parseLong(args[1].toString());
                            if (exhausted) {
                                return List.of(0L);
                            }
                            int start = cursors.getOrDefault(key, 0);
                            for (int i = 0; i < 1024; i++) {
                                int candidate = (start + i) % 1024;
                                String slotKey = keys.get(candidate + 1).toString();
                                String previous = slots.get(slotKey);
                                long floor = 0L;
                                if (previous != null && previous.startsWith("R|")) {
                                    long priorUpper = Long.parseLong(previous.substring(2));
                                    if (now <= priorUpper) {
                                        continue;
                                    }
                                    floor = priorUpper + 1L;
                                }
                                if (previous == null || previous.startsWith("R|")) {
                                    slots.put(slotKey, "A|" + instanceId + "|" + expiry + "|" + floor + "|" + expiry);
                                    cursors.put(key, (candidate + 1) % 1024);
                                    return List.of(1L, (long) candidate, now, expiry, floor, expiry, "a".repeat(40) + ":1");
                                }
                            }
                            return List.of(0L);
                        }
                        if (lua.contains("common-id:machine-lease:release")) {
                            assertThat(keys).hasSize(2);
                            releases.incrementAndGet();
                            if (failRelease) {
                                throw new IllegalStateException("isolated release transport failure");
                            }
                            String instanceId = args[0].toString();
                            String slotKey = keys.get(1).toString();
                            String state = slots.get(slotKey);
                            if (state != null && state.startsWith("A|" + instanceId + "|")) {
                                slots.put(slotKey, "R|" + state.substring(state.lastIndexOf('|') + 1));
                                return 1L;
                            }
                            return 0L;
                        }
                        if (lua.contains("common-id:machine-lease:renew")) {
                            assertThat(keys).hasSize(2);
                            long now = System.currentTimeMillis();
                            long expiry = now + Long.parseLong(args[1].toString());
                            String slotKey = keys.get(1).toString();
                            String[] previous = slots.get(slotKey).split("\\|");
                            long floor = Long.parseLong(previous[3]);
                            slots.put(slotKey, "A|" + args[0] + "|" + expiry + "|" + floor + "|" + expiry);
                            return List.of(1L, Long.parseLong(args[3].toString()), now, expiry, floor,
                                    expiry, "a".repeat(40) + ":1");
                        }
                        throw new AssertionError("unexpected common-id script");
                    });
            return client;
        }
    }
}
