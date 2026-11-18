package top.egon.cola.component.common.id.autoconfigure;

import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
        AutoCloseable createdProvider = null;
        if (configuredMachineId == null) {
            MachineIdProvider customProvider = machineIdProviders.getIfAvailable();
            if (customProvider != null) {
                configuredMachineId = customProvider.getMachineId();
            } else if (properties.getRedis().isEnabled()
                    && ClassUtils.isPresent(REDISSON_CLIENT_CLASS, getClass().getClassLoader())) {
                RedisMachineIdProvider redisProvider = RedissonMachineIdAllocator.createIfAvailable(
                        properties, beanFactory, environment);
                if (redisProvider != null) {
                    try {
                        configuredMachineId = redisProvider.getMachineId();
                        createdProvider = redisProvider;
                    } catch (RuntimeException exception) {
                        redisProvider.close();
                        throw exception;
                    }
                }
            }
        }
        this.managedProvider = createdProvider;
        try {
            SnowflakeIdGenerator.initialize(configuredMachineId, properties.getMaxClockBackward());
        } catch (RuntimeException exception) {
            closeManagedProvider();
            throw exception;
        }
    }

    @Override
    public void destroy() {
        closeManagedProvider();
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

    private static final class RedissonMachineIdAllocator {

        private RedissonMachineIdAllocator() {
        }

        static RedisMachineIdProvider createIfAvailable(
                IdGeneratorProperties properties,
                BeanFactory beanFactory,
                Environment environment) {
            RedissonClient redissonClient = resolveRedissonClient(beanFactory);
            if (redissonClient == null) {
                return null;
            }
            IdGeneratorProperties.Redis redis = properties.getRedis();
            String env = resolveEnv(redis.getEnv(), environment);
            String app = resolveApp(redis.getApp(), environment);
            return new RedisMachineIdProvider(
                    redissonClient,
                    env,
                    app,
                    redis.getLeaseDuration(),
                    redis.getRenewInterval());
        }

        private static RedissonClient resolveRedissonClient(BeanFactory beanFactory) {
            if (beanFactory.containsBean(REDISSON_CLIENT_BEAN_NAME)) {
                Object candidate = beanFactory.getBean(REDISSON_CLIENT_BEAN_NAME);
                if (candidate instanceof RedissonClient client) {
                    return client;
                }
            }
            return beanFactory.getBeanProvider(RedissonClient.class).getIfUnique();
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
