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

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
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
        AtomicBoolean invoked = new AtomicBoolean();
        MachineIdProvider provider = () -> {
            invoked.set(true);
            return 0L;
        };

        contextRunner.withBean(MachineIdProvider.class, () -> provider)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(invoked).isTrue();
                    assertThat(SnowflakeIdGenerator.nextLongId()).isPositive();
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
                    verifyNoInteractions(provider, redissonClient);
                });
    }

    @Test
    void redisLeaseAllocatesOnStartupAndReleasesSlotWhenContextCloses() {
        FakeRedisLeaseEval fakeRedis = new FakeRedisLeaseEval();
        String slotKey = "egon:cola:id:{prod:order-service}:slot:0";

        contextRunner
                .withBean("redissonClient", RedissonClient.class, fakeRedis::client)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "spring.application.name=order-service")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(fakeRedis.slots).containsKey(slotKey);
                });

        // After contextRunner.run(...) completes, the ApplicationContext is closed and slot 0 is released
        assertThat(fakeRedis.slots).doesNotContainKey(slotKey);
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

    private static final class FakeRedisLeaseEval {

        private final Map<String, Integer> cursors = new HashMap<>();
        private final Map<String, String> slots = new HashMap<>();

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
                            String prefix = args[0].toString();
                            String instanceId = args[1].toString();
                            int start = cursors.getOrDefault(key, 0);
                            for (int i = 0; i < 1024; i++) {
                                int candidate = (start + i) % 1024;
                                String slotKey = prefix + candidate;
                                if (!slots.containsKey(slotKey)) {
                                    slots.put(slotKey, instanceId);
                                    cursors.put(key, (candidate + 1) % 1024);
                                    return (long) candidate;
                                }
                            }
                            return -1L;
                        }
                        if (lua.contains("common-id:machine-lease:release")) {
                            String instanceId = args[0].toString();
                            if (instanceId.equals(slots.get(key))) {
                                slots.remove(key);
                                return 1L;
                            }
                            return 0L;
                        }
                        return 1L;
                    });
            return client;
        }
    }
}
