package top.egon.cola.component.accessguard.autoconfigure;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.egon.cola.component.accessguard.store.LogicalQuotaAcquireCommand;
import top.egon.cola.component.accessguard.store.LogicalQuotaBackend;
import top.egon.cola.component.accessguard.store.LogicalQuotaDecisionVO;
import top.egon.cola.component.accessguard.store.RateLimitBackend;
import top.egon.cola.component.accessguard.store.local.LocalRateLimitBackend;
import top.egon.cola.component.accessguard.store.redisson.AccessGuardRedisKeyFactory;
import top.egon.cola.component.accessguard.store.redisson.RedissonLogicalQuotaBackend;
import top.egon.cola.component.accessguard.store.redisson.RedissonRateLimitBackend;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AccessGuardRedissonAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    Jackson2AutoConfiguration.class,
                    AccessGuardCoreAutoConfiguration.class,
                    AccessGuardLocalStoreAutoConfiguration.class,
                    AccessGuardRedissonAutoConfiguration.class,
                    AccessGuardTimeLimitAutoConfiguration.class))
            .withPropertyValues("spring.application.name=test");

    @Test
    void missingConfiguredClientFailsInsteadOfSilentlyUsingLocalStorage() {
        contextRunner.withPropertyValues(
                        "egon.cola.component.access-guard.storage=REDISSON",
                        "egon.cola.component.access-guard.redisson.client-bean-name=guardRedis")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("guardRedis"));
    }

    @Test
    void configuredNameMustResolveToARedissonClient() {
        contextRunner.withUserConfiguration(WrongClientConfiguration.class)
                .withPropertyValues(
                        "egon.cola.component.access-guard.storage=REDISSON",
                        "egon.cola.component.access-guard.redisson.client-bean-name=guardRedis")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("RedissonClient"));
    }

    @Test
    void blankClientNameMustResolveExactlyOneCandidate() {
        contextRunner.withUserConfiguration(TwoRedissonClientsConfiguration.class)
                .withPropertyValues(
                        "egon.cola.component.access-guard.storage=REDISSON",
                        "egon.cola.component.access-guard.redisson.client-bean-name=")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("exactly one RedissonClient")
                        .hasMessageContaining("found 2"));
    }

    @Test
    void selectedRedissonStoresArePrimaryWhileLocalFallbackRemainsAvailable() {
        contextRunner.withUserConfiguration(RedissonClientConfiguration.class)
                .withPropertyValues("egon.cola.component.access-guard.storage=REDISSON")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RateLimitBackend.class))
                            .isInstanceOf(RedissonRateLimitBackend.class);
                    assertThat(context).hasSingleBean(LocalRateLimitBackend.class);
                    assertThat(context).hasBean("accessGuardLogicalQuotaBackend");
                    assertThat(context).hasSingleBean(LogicalQuotaBackend.class);
                    assertThat(context.getBean("accessGuardLogicalQuotaBackend"))
                            .isSameAs(context.getBean(LogicalQuotaBackend.class))
                            .isInstanceOf(RedissonLogicalQuotaBackend.class);
                });
    }

    @Test
    void logicalQuotaUsesTheConfiguredRedissonSelectionWithNoSeparateClient() {
        contextRunner.withUserConfiguration(TwoRedissonClientsConfiguration.class)
                .withPropertyValues("egon.cola.component.access-guard.storage=REDISSON",
                        "egon.cola.component.access-guard.redisson.client-bean-name=secondRedissonClient")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AccessGuardRedissonAutoConfiguration.SelectedRedissonClient.class).client())
                            .isSameAs(context.getBean("secondRedissonClient"));
                    assertThat(context.getBean(LogicalQuotaBackend.class))
                            .isInstanceOf(RedissonLogicalQuotaBackend.class);
                    assertThat(context.getBeansOfType(RedissonClient.class)).hasSize(2);
                });
    }

    @Test
    void localStorageDoesNotPretendToProvideDistributedLogicalQuota() {
        contextRunner.withPropertyValues("egon.cola.component.access-guard.storage=LOCAL")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("accessGuardLogicalQuotaBackend");
                    assertThat(context).doesNotHaveBean(LogicalQuotaBackend.class);
                });
    }

    @Test
    void explicitLogicalQuotaBackendRemainsTheOnlyImplementation() {
        contextRunner.withUserConfiguration(RedissonClientConfiguration.class, CustomLogicalQuotaConfiguration.class)
                .withPropertyValues("egon.cola.component.access-guard.storage=REDISSON")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LogicalQuotaBackend.class);
                    assertThat(context).doesNotHaveBean("accessGuardLogicalQuotaBackend");
                    assertThat(context.getBean(LogicalQuotaBackend.class))
                            .isSameAs(context.getBean("customLogicalQuotaBackend"));
                });
    }

    @Test
    void stableAndCanaryShareKeysWhenTheyDeclareTheSameQuotaApplication() {
        AtomicReference<String> stateKey = new AtomicReference<>();
        AtomicReference<String> decisionKey = new AtomicReference<>();
        for (String deployment : List.of("identity-stable", "identity-canary")) {
            contextRunner.withUserConfiguration(RedissonClientConfiguration.class)
                    .withPropertyValues("egon.cola.component.access-guard.storage=REDISSON",
                            "spring.application.name=" + deployment,
                            "egon.cola.component.access-guard.redisson.application=identity")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        AccessGuardRedisKeyFactory factory = context.getBean(AccessGuardRedisKeyFactory.class);
                        String state = factory.logicalQuotaState(command());
                        String decision = factory.logicalQuotaDecision(command());
                        assertThat(state).startsWith("egon:access-guard:identity:logical-quota:");
                        if (stateKey.get() == null) {
                            stateKey.set(state);
                            decisionKey.set(decision);
                        } else {
                            assertThat(state).isEqualTo(stateKey.get());
                            assertThat(decision).isEqualTo(decisionKey.get());
                        }
                    });
        }
    }

    private LogicalQuotaAcquireCommand command() {
        return new LogicalQuotaAcquireCommand(42, "client", "https://resource.example/rpc", "quota",
                "sample.Service/Read", "call", "a".repeat(64), 1, 2, 10, 1, Duration.ofSeconds(30));
    }

    @Configuration(proxyBeanMethods = false)
    static class WrongClientConfiguration {

        @Bean
        String guardRedis() {
            return "wrong";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RedissonClientConfiguration {

        @Bean
        RedissonClient redissonClient() {
            return mock(RedissonClient.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoRedissonClientsConfiguration {

        @Bean
        RedissonClient firstRedissonClient() {
            return mock(RedissonClient.class);
        }

        @Bean
        RedissonClient secondRedissonClient() {
            return mock(RedissonClient.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomLogicalQuotaConfiguration {

        @Bean
        LogicalQuotaBackend customLogicalQuotaBackend() {
            return command -> new LogicalQuotaDecisionVO(true, LogicalQuotaDecisionVO.Reason.ALLOWED, 9, 1);
        }
    }
}
