package top.egon.cola.component.common.id.provider;

import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed {@link MachineIdProvider} that leases a unique {@code 0..1023} slot
 * scoped by {@code env} and {@code app}.
 *
 * <p>Allocation uses a persistent ring cursor so newly started pods advance around
 * the {@code 0..1023} ring instead of immediately reusing a recently released slot.
 * A background daemon thread renews the slot TTL periodically and self-heals in place
 * if a transient network partition caused the key to expire while remaining unclaimed.
 * Closing the provider stops the renewal task and releases the slot if still owned by
 * this instance.</p>
 */
public final class RedisMachineIdProvider implements MachineIdProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisMachineIdProvider.class);

    static final String KEY_PREFIX = "egon:cola:id";
    static final int SLOT_COUNT = 1024;

    static final String ACQUIRE_SCRIPT = """
            -- common-id:machine-lease:acquire
            local start = tonumber(redis.call('GET', KEYS[1]) or '0')
            if not start or start < 0 or start >= 1024 then
                start = 0
            end
            local prefix = ARGV[1]
            local instanceId = ARGV[2]
            local leaseMillis = tonumber(ARGV[3])
            for i = 0, 1023 do
                local candidate = (start + i) % 1024
                local slotKey = prefix .. candidate
                if redis.call('SET', slotKey, instanceId, 'NX', 'PX', leaseMillis) then
                    redis.call('SET', KEYS[1], tostring((candidate + 1) % 1024))
                    return candidate
                end
            end
            return -1
            """;

    static final String RENEW_SCRIPT = """
            -- common-id:machine-lease:renew
            local current = redis.call('GET', KEYS[1])
            local instanceId = ARGV[1]
            local leaseMillis = tonumber(ARGV[2])
            if current == instanceId then
                redis.call('PEXPIRE', KEYS[1], leaseMillis)
                return 1
            elseif not current then
                redis.call('SET', KEYS[1], instanceId, 'PX', leaseMillis)
                return 2
            else
                return 0
            end
            """;

    static final String RELEASE_SCRIPT = """
            -- common-id:machine-lease:release
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private final RedissonClient redissonClient;
    private final String env;
    private final String app;
    private final long leaseMillis;
    private final long renewMillis;
    private final String instanceId;
    private final String cursorKey;
    private final String slotKeyPrefix;

    private volatile Long allocatedMachineId;
    private volatile ScheduledExecutorService renewScheduler;
    private volatile boolean closed;

    /**
     * Creates a Redis machine-ID provider with a random UUID instance identifier.
     *
     * @param redissonClient Redisson client connected to Redis
     * @param env            environment segment isolating the pool
     * @param app            application segment isolating the pool
     * @param leaseDuration  TTL of the leased slot in Redis
     * @param renewInterval  background renewal period, strictly less than {@code leaseDuration}
     */
    public RedisMachineIdProvider(
            RedissonClient redissonClient,
            String env,
            String app,
            Duration leaseDuration,
            Duration renewInterval) {
        this(redissonClient, env, app, leaseDuration, renewInterval, UUID.randomUUID().toString());
    }

    /**
     * Creates a Redis machine-ID provider with an explicit instance identifier.
     *
     * @param redissonClient Redisson client connected to Redis
     * @param env            environment segment isolating the pool
     * @param app            application segment isolating the pool
     * @param leaseDuration  TTL of the leased slot in Redis
     * @param renewInterval  background renewal period, strictly less than {@code leaseDuration}
     * @param instanceId     unique process/instance token written to the slot key
     */
    public RedisMachineIdProvider(
            RedissonClient redissonClient,
            String env,
            String app,
            Duration leaseDuration,
            Duration renewInterval,
            String instanceId) {
        if (redissonClient == null) {
            throw configurationFailure("RedissonClient is required for Redis machine-id allocation");
        }
        this.redissonClient = redissonClient;
        this.env = validateSegment("env", env);
        this.app = validateSegment("app", app);
        this.leaseMillis = validatePositiveDuration("lease-duration", leaseDuration);
        this.renewMillis = validatePositiveDuration("renew-interval", renewInterval);
        if (this.renewMillis >= this.leaseMillis) {
            throw configurationFailure(
                    "egon.cola.component.id.redis.renew-interval must be smaller than lease-duration: renewInterval="
                            + renewInterval + ", leaseDuration=" + leaseDuration);
        }
        if (instanceId == null || instanceId.isBlank()) {
            throw configurationFailure("instanceId must not be blank");
        }
        this.instanceId = instanceId.trim();
        String scopeTag = "{" + this.env + ":" + this.app + "}";
        this.cursorKey = KEY_PREFIX + ":" + scopeTag + ":cursor";
        this.slotKeyPrefix = KEY_PREFIX + ":" + scopeTag + ":slot:";
    }

    @Override
    public long getMachineId() {
        Long current = allocatedMachineId;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (closed) {
                throw configurationFailure("RedisMachineIdProvider is already closed");
            }
            if (allocatedMachineId != null) {
                return allocatedMachineId;
            }
            long acquired = acquireSlot();
            allocatedMachineId = acquired;
            startRenewScheduler();
            return acquired;
        }
    }

    /**
     * Returns the unique instance identifier associated with this provider's lease.
     *
     * @return non-blank instance identifier
     */
    public String instanceId() {
        return instanceId;
    }

    /**
     * Returns the Redis cursor key for this {@code env + app} scope.
     *
     * @return Redis cursor key
     */
    public String cursorKey() {
        return cursorKey;
    }

    /**
     * Returns the Redis slot key for the given machine ID in this {@code env + app} scope.
     *
     * @param machineId machine identifier in {@code 0..1023}
     * @return Redis slot key
     */
    public String slotKey(long machineId) {
        return slotKeyPrefix + machineId;
    }

    /**
     * Executes one lease renewal cycle. Package-private for deterministic testing.
     *
     * @return {@code 1} when renewed normally, {@code 2} when self-healed after expiration,
     *         {@code 0} when stolen by another instance, or {@code -1} when not active
     */
    int renewOnce() {
        Long machineId = allocatedMachineId;
        if (closed || machineId == null) {
            return -1;
        }
        String key = slotKey(machineId);
        try {
            Object raw = redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE,
                    RENEW_SCRIPT,
                    RScript.ReturnType.LONG,
                    List.of(key),
                    instanceId,
                    Long.toString(leaseMillis));
            int status = raw instanceof Number number ? number.intValue() : 0;
            if (status == 2) {
                log.warn("Re-acquired expired Redis Snowflake machine-id slot in place: env={}, app={}, machineId={}, instanceId={}",
                        env, app, machineId, instanceId);
            } else if (status == 0) {
                log.error("Redis Snowflake machine-id slot lease conflict detected (slot owned by another instance): env={}, app={}, machineId={}, instanceId={}",
                        env, app, machineId, instanceId);
            }
            return status;
        } catch (RuntimeException exception) {
            log.warn("Failed to renew Redis Snowflake machine-id lease: env={}, app={}, machineId={}, instanceId={}",
                    env, app, machineId, instanceId, exception);
            return -1;
        }
    }

    @Override
    public void close() {
        Long machineId;
        ScheduledExecutorService scheduler;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            machineId = allocatedMachineId;
            scheduler = renewScheduler;
            renewScheduler = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        if (machineId != null) {
            try {
                redissonClient.getScript(StringCodec.INSTANCE).eval(
                        RScript.Mode.READ_WRITE,
                        RELEASE_SCRIPT,
                        RScript.ReturnType.LONG,
                        List.of(slotKey(machineId)),
                        instanceId);
            } catch (RuntimeException exception) {
                log.warn("Best-effort release of Redis Snowflake machine-id slot failed on shutdown: env={}, app={}, machineId={}",
                        env, app, machineId, exception);
            }
        }
    }

    private long acquireSlot() {
        final Object raw;
        try {
            raw = redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE,
                    ACQUIRE_SCRIPT,
                    RScript.ReturnType.LONG,
                    List.of(cursorKey),
                    slotKeyPrefix,
                    instanceId,
                    Long.toString(leaseMillis));
        } catch (RuntimeException exception) {
            throw new CommonException(
                    ResultCode.SYSTEM_ERROR.getCode(),
                    ResultCode.SYSTEM_ERROR.getStatus(),
                    "Failed to allocate Snowflake machine-id from Redis for env=" + env + ", app=" + app,
                    exception);
        }
        long slot = raw instanceof Number number ? number.longValue() : -1L;
        if (slot < 0L || slot >= SLOT_COUNT) {
            throw configurationFailure(
                    "No available Snowflake machine-id slot (0..1023) in Redis for env=" + env + ", app=" + app);
        }
        log.info("Allocated Snowflake machine-id={} from Redis for env={}, app={}, instanceId={}",
                slot, env, app, instanceId);
        return slot;
    }

    private void startRenewScheduler() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = Thread.ofPlatform()
                    .daemon(true)
                    .name("egon-cola-id-lease-" + env + "-" + app)
                    .unstarted(runnable);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(
                this::renewOnce,
                renewMillis,
                renewMillis,
                TimeUnit.MILLISECONDS);
        this.renewScheduler = scheduler;
    }

    private static String validateSegment(String name, String value) {
        if (value == null || value.isBlank()) {
            throw configurationFailure("egon.cola.component.id.redis." + name + " must not be blank");
        }
        String trimmed = value.trim();
        for (int index = 0; index < trimmed.length(); index++) {
            char ch = trimmed.charAt(index);
            if (ch == ':' || ch == '{' || ch == '}' || Character.isWhitespace(ch) || Character.isISOControl(ch)) {
                throw configurationFailure(
                        "egon.cola.component.id.redis." + name + " contains invalid character in: " + trimmed);
            }
        }
        return trimmed;
    }

    private static long validatePositiveDuration(String name, Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero() || duration.toMillis() <= 0L) {
            throw configurationFailure("egon.cola.component.id.redis." + name + " must be positive: " + duration);
        }
        return duration.toMillis();
    }

    private static CommonException configurationFailure(String message) {
        return new CommonException(
                ResultCode.SYSTEM_ERROR.getCode(),
                ResultCode.SYSTEM_ERROR.getStatus(),
                message);
    }
}
