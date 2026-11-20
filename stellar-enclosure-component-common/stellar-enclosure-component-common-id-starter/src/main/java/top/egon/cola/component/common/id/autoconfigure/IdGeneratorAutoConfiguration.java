package top.egon.cola.component.common.id.autoconfigure;

import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import top.egon.cola.component.common.id.generator.IdGenerator;
import top.egon.cola.component.common.id.provider.MachineIdProvider;
import top.egon.cola.component.common.id.provider.RedisMachineIdProvider;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;

/**
 * Spring Boot auto-configuration that binds the static Snowflake ID engine during the configuration
 * phase.
 *
 * <p>When {@code egon.cola.component.id.machine-id} is explicitly configured, that value is bound
 * directly. When absent, the auto-configuration consults a user-supplied {@link MachineIdProvider}
 * bean or falls back to a Redis lease provider scoped by {@code env} and {@code app}. Generation
 * remains a static entry point on {@link SnowflakeIdGenerator}, so no generator bean is published.
 * Supplying any {@link IdGenerator} bean, or disabling the Starter, leaves the static engine
 * unbound.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(IdGeneratorProperties.class)
@ConditionalOnProperty(prefix = IdGeneratorProperties.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = true)
@ConditionalOnMissingBean(IdGenerator.class)
public class IdGeneratorAutoConfiguration implements DisposableBean {

    private static final String REDISSON_CLIENT_CLASS = "org.redisson.api.RedissonClient";
    private static final String REDISSON_CLIENT_BEAN_NAME = "redissonClient";

    private final AutoCloseable managedProvider;
    private final MachineIdProvider issuanceProvider;

    /**
     * Validates the bound configuration and binds the process-wide engine exactly once.
     *
     * @param properties         bound common ID properties
     * @param machineIdProviders optional custom machine-ID provider bean
     * @param beanFactory        Spring bean factory for optional Redisson lookup
     * @param environment        Spring environment for {@code env} and {@code app} fallbacks
     */
    public IdGeneratorAutoConfiguration(
            IdGeneratorProperties properties,
            ObjectProvider<MachineIdProvider> machineIdProviders,
            BeanFactory beanFactory,
            Environment environment) {
        Long configuredMachineId = properties.getMachineId();
        IdGeneratorProperties.Redis redis = properties.getRedis();
        boolean requireLease = redis.isRequireLease();
        if (requireLease && (configuredMachineId != null || !redis.isEnabled())) {
            throw new IllegalStateException("Managed ID issuance requires a Redis lease");
        }
        if (requireLease && (!RedissonMachineIdAllocator.hasText(redis.getEnv())
                || !RedissonMachineIdAllocator.hasText(redis.getApp()))) {
            throw new IllegalStateException("Managed ID issuance requires an explicit Redis scope");
        }
        MachineIdProvider selectedProvider = null;
        AutoCloseable createdProvider = null;
        if (configuredMachineId == null) {
            MachineIdProvider customProvider = machineIdProviders.getIfAvailable();
            if (customProvider != null) {
                if (requireLease) {
                    throw new IllegalStateException("Managed ID issuance requires the starter-owned Redis lease provider");
                }
                configuredMachineId = customProvider.getMachineId();
                selectedProvider = customProvider;
            } else if (properties.getRedis().isEnabled()
                    && ClassUtils.isPresent(REDISSON_CLIENT_CLASS, getClass().getClassLoader())) {
                RedisAllocation allocation = RedissonMachineIdAllocator.createIfAvailable(properties, beanFactory, environment);
                if (allocation != null) {
                    try {
                        configuredMachineId = allocation.provider().getMachineId();
                        selectedProvider = allocation.provider();
                        createdProvider = allocation;
                    } catch (RuntimeException | Error failure) {
                        allocation.close();
                        throw failure;
                    }
                }
            }
        }
        if (requireLease && createdProvider == null) {
            throw new IllegalStateException("Managed ID issuance requires an available Redis client");
        }
        this.managedProvider = createdProvider;
        this.issuanceProvider = selectedProvider;
        try {
            SnowflakeIdGenerator.initialize(configuredMachineId, properties.getMaxClockBackward(), selectedProvider);
        } catch (RuntimeException exception) {
            closeManagedProvider();
            throw exception;
        }
    }

    @Override
    public void destroy() {
        closeManagedProvider();
    }

    /** Checks a dynamic provider's current authority without generating or consuming an ID. */
    public void assertIssuanceAvailable() {
        if (issuanceProvider != null) {
            issuanceProvider.assertIssuanceAvailable();
        }
    }

    /** Checks the starter-owned Redis lease without generating an ID or contacting Redis. */
    public void assertRedisLeaseAvailable() {
        if (!(issuanceProvider instanceof RedisMachineIdProvider provider)) {
            throw new IllegalStateException("Managed Redis ID lease is unavailable");
        }
        provider.assertIssuanceAvailable();
    }

    @Configuration(value = "commonIdLeaseHealthConfiguration", proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnProperty(prefix = IdGeneratorProperties.PREFIX + ".redis", name = "require-lease", havingValue = "true")
    static class LeaseHealthConfiguration {
        @Bean("commonIdLease")
        HealthIndicator commonIdLease(IdGeneratorAutoConfiguration generator) {
            return () -> {
                try {
                    generator.assertRedisLeaseAvailable();
                    return Health.up().build();
                } catch (RuntimeException unavailable) {
                    return Health.down().build();
                }
            };
        }
    }

    private void closeManagedProvider() {
        if (managedProvider != null) {
            try {
                managedProvider.close();
            } catch (Exception ignored) {
                // Best-effort lease release on context shutdown.
            }
        }
    }

    private record RedisAllocation(RedisMachineIdProvider provider, RedissonClient ownedClient) implements AutoCloseable {
        @Override
        public void close() {
            try {
                provider.close();
            } finally {
                if (ownedClient != null) {
                    ownedClient.shutdown();
                }
            }
        }
    }

    private static final class RedissonMachineIdAllocator {

        private RedissonMachineIdAllocator() {
        }

        static RedisAllocation createIfAvailable(
                IdGeneratorProperties properties,
                BeanFactory beanFactory,
                Environment environment) {
            IdGeneratorProperties.Redis redis = properties.getRedis();
            boolean owned = redis.isRequireLease();
            RedissonClient client = owned
                    ? RedisLeaseAuthority.open(environment.getProperty(RedisLeaseAuthority.CONFIG_FILE),
                            environment.getProperty(RedisLeaseAuthority.REGISTERED_NODES))
                    : resolveRedissonClient(beanFactory);
            if (client == null) {
                return null;
            }
            try {
                String env = resolveEnv(redis.getEnv(), environment);
                String app = resolveApp(redis.getApp(), environment);
                var provider = new RedisMachineIdProvider(client, env, app, redis.getLeaseDuration(), redis.getRenewInterval());
                return new RedisAllocation(provider, owned ? client : null);
            } catch (RuntimeException | Error failure) {
                if (owned) {
                    client.shutdown();
                }
                throw failure;
            }
        }

        private static RedissonClient resolveRedissonClient(BeanFactory beanFactory) {
            if (beanFactory.containsBean(REDISSON_CLIENT_BEAN_NAME)) {
                Object candidate = beanFactory.getBean(REDISSON_CLIENT_BEAN_NAME);
                if (candidate instanceof RedissonClient client) {
                    return client;
                }
                throw new IllegalStateException("Bean '" + REDISSON_CLIENT_BEAN_NAME
                        + "' must be a RedissonClient for Redis machine-id allocation");
            }
            return beanFactory.getBeanProvider(RedissonClient.class).getIfAvailable();
        }

        private static String resolveEnv(String configuredEnv, Environment environment) {
            if (hasText(configuredEnv)) {
                return configuredEnv.trim();
            }
            String tianshuEnv = environment.getProperty("egon.cola.component.tianshu.env");
            if (hasText(tianshuEnv)) {
                return tianshuEnv.trim();
            }
            String[] activeProfiles = environment.getActiveProfiles();
            for (String profile : activeProfiles) {
                if (hasText(profile)) {
                    return profile.trim();
                }
            }
            String activeProfileProperty = environment.getProperty("spring.profiles.active");
            if (hasText(activeProfileProperty)) {
                String first = activeProfileProperty.split(",")[0];
                if (hasText(first)) {
                    return first.trim();
                }
            }
            return "default";
        }

        private static String resolveApp(String configuredApp, Environment environment) {
            if (hasText(configuredApp)) {
                return configuredApp.trim();
            }
            String tianshuApp = environment.getProperty("egon.cola.component.tianshu.app-code");
            if (hasText(tianshuApp)) {
                return tianshuApp.trim();
            }
            String springAppName = environment.getProperty("spring.application.name");
            if (hasText(springAppName)) {
                return springAppName.trim();
            }
            return null;
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }
}
