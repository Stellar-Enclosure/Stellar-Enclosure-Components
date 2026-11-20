package top.egon.cola.component.common.id.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;

import java.time.Duration;

/**
 * Configuration properties for Snowflake ID generation. Machine identifiers
 * are either explicitly configured via {@code machine-id} or dynamically leased
 * per {@code env + app} scope via Redis when {@code machine-id} is absent.
 * Values are validated by {@link SnowflakeIdGenerator#initialize(Long, Duration)}
 * when auto-configuration binds the process-wide engine.
 */
@ConfigurationProperties(prefix = IdGeneratorProperties.PREFIX)
public class IdGeneratorProperties {

    /** Configuration prefix for the common ID Starter. */
    public static final String PREFIX = "egon.cola.component.id";

    private boolean enabled = true;
    private Long machineId;
    private Duration maxClockBackward = SnowflakeIdGenerator.DEFAULT_MAX_CLOCK_BACKWARD;
    private final Redis redis = new Redis();

    /**
     * Returns whether Snowflake auto-configuration is enabled.
     *
     * @return {@code true} when enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables Snowflake auto-configuration.
     *
     * @param enabled whether generation is enabled
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns the explicitly assigned machine identifier.
     *
     * @return machine identifier from 0 to 1023, or {@code null} when absent
     */
    public Long getMachineId() {
        return machineId;
    }

    /**
     * Sets the explicitly assigned machine identifier.
     *
     * @param machineId machine identifier from 0 to 1023
     */
    public void setMachineId(Long machineId) {
        this.machineId = machineId;
    }

    /**
     * Returns the largest clock rollback that may be waited out.
     *
     * @return non-negative rollback tolerance
     */
    public Duration getMaxClockBackward() {
        return maxClockBackward;
    }

    /**
     * Sets the largest clock rollback that may be waited out.
     *
     * @param maxClockBackward non-negative rollback tolerance
     */
    public void setMaxClockBackward(Duration maxClockBackward) {
        this.maxClockBackward = maxClockBackward;
    }

    /**
     * Returns the Redis machine-ID lease settings used when {@code machine-id} is not explicitly set.
     *
     * @return Redis lease configuration
     */
    public Redis getRedis() {
        return redis;
    }

    /**
     * Redis lease allocation properties scoped by {@code env} and {@code app}.
     */
    public static class Redis {

        /** Default duration of the issuance lease recorded in Redis. */
        public static final Duration DEFAULT_LEASE_DURATION = Duration.ofMinutes(5);

        /** Default background heartbeat renewal interval. */
        public static final Duration DEFAULT_RENEW_INTERVAL = Duration.ofMinutes(1);

        private boolean enabled = true;
        private boolean requireLease;
        private String env;
        private String app;
        private Duration leaseDuration = DEFAULT_LEASE_DURATION;
        private Duration renewInterval = DEFAULT_RENEW_INTERVAL;

        /**
         * Returns whether Redis machine-ID lease allocation is enabled when static {@code machine-id} is absent.
         *
         * @return {@code true} when Redis allocation is enabled
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Enables or disables Redis machine-ID lease allocation.
         *
         * @param enabled whether Redis allocation is enabled
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** Returns whether this runtime must obtain a Redis lease from an explicit ID scope. */
        public boolean isRequireLease() {
            return requireLease;
        }

        /** Selects the managed lease path for a registered workload. */
        public void setRequireLease(boolean requireLease) {
            this.requireLease = requireLease;
        }

        /**
         * Returns the environment segment isolating the Redis machine-ID pool.
         *
         * @return environment code, or {@code null} to fall back to standard environment properties
         */
        public String getEnv() {
            return env;
        }

        /**
         * Sets the environment segment isolating the Redis machine-ID pool.
         *
         * @param env environment code
         */
        public void setEnv(String env) {
            this.env = env;
        }

        /**
         * Returns the application segment isolating the Redis machine-ID pool.
         *
         * @return application code, or {@code null} to fall back to standard application name properties
         */
        public String getApp() {
            return app;
        }

        /**
         * Sets the application segment isolating the Redis machine-ID pool.
         *
         * @param app application code
         */
        public void setApp(String app) {
            this.app = app;
        }

        /**
         * Returns the duration of the issuance lease. The slot record remains persistent.
         *
         * @return positive lease duration
         */
        public Duration getLeaseDuration() {
            return leaseDuration;
        }

        /**
         * Sets the duration of the issuance lease. The slot record remains persistent.
         *
         * @param leaseDuration positive lease duration
         */
        public void setLeaseDuration(Duration leaseDuration) {
            this.leaseDuration = leaseDuration;
        }

        /**
         * Returns the background renewal interval for the leased machine-ID slot.
         *
         * @return positive renewal interval strictly smaller than {@code leaseDuration}
         */
        public Duration getRenewInterval() {
            return renewInterval;
        }

        /**
         * Sets the background renewal interval for the leased machine-ID slot.
         *
         * @param renewInterval positive renewal interval strictly smaller than {@code leaseDuration}
         */
        public void setRenewInterval(Duration renewInterval) {
            this.renewInterval = renewInterval;
        }
    }
}
