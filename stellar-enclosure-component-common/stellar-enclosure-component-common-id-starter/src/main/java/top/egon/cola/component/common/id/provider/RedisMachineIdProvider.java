package top.egon.cola.component.common.id.provider;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;

/**
 * Allocates a persistent, authority-bound Snowflake slot from an explicitly provisioned pool.
 * An expired active owner is never a free slot. A drained close leaves its reserved timestamp
 * upper bound as a permanent watermark; only a later Redis time may allocate that slot again.
 */
@Slf4j
public final class RedisMachineIdProvider implements MachineIdProvider, AutoCloseable {

    static final String KEY_PREFIX = "egon:cola:id";
    static final int SLOT_COUNT = 1024;
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final long MAX_LEASE_MILLIS = TimeUnit.NANOSECONDS.toMillis(Long.MAX_VALUE / 4);

    private static final String LUA_COMMON = """
            local MAX = 9007199254740991
            local function integer(value)
                if type(value) ~= 'string' or not string.match(value, '^%d+$') then return nil end
                local n = tonumber(value)
                if not n or n < 0 or n > MAX or n ~= math.floor(n) then return nil end
                return n
            end
            local function authority()
                local value = redis.call('GET', KEYS[1])
                if not value or redis.call('PTTL', KEYS[1]) ~= -1 then return nil, nil, -2 end
                local marker, position = string.match(value, '^V1|([a-f0-9]+:%d+)|(%d+)$')
                local index = integer(position)
                if not marker or not index or index >= 1024
                    or position ~= '0' and not string.match(position, '^[1-9]%d*$') then return nil, nil, -4 end
                local run = string.match(redis.call('INFO', 'server'), 'run_id:([a-f0-9]+)')
                if not run or #run ~= 40 then return nil, nil, -3 end
                local epoch = nil
                for line in string.gmatch(redis.call('CLUSTER', 'NODES'), '[^\\r\\n]+') do
                    local fields = {}
                    for field in string.gmatch(line, '%S+') do fields[#fields + 1] = field end
                    local flags = ',' .. (fields[3] or '') .. ','
                    if string.find(flags, ',myself,', 1, true) then
                        if epoch or not string.find(flags, ',master,', 1, true) or fields[4] ~= '-'
                            or fields[8] ~= 'connected' or string.find(flags, ',fail', 1, true)
                            or string.find(flags, ',handshake,', 1, true) or string.find(flags, ',noaddr,', 1, true)
                            or not integer(fields[7])
                            or fields[7] ~= '0' and not string.match(fields[7], '^[1-9]%d*$') then
                            return nil, nil, -3
                        end
                        epoch = fields[7]
                    end
                end
                local identity = epoch and (run .. ':' .. epoch) or nil
                if not identity or marker ~= identity then return nil, nil, -3 end
                return identity, index, 0
            end
            local function slot(value)
                if value == 'F|0' then return 'F', '', 0, 0, 0 end
                if not value then return nil end
                local released = string.match(value, '^R|(%d+)$')
                if released then
                    local upper = integer(released)
                    if upper then return 'R', '', 0, 0, upper end
                    return nil
                end
                local owner, expiryText, floorText, upperText = string.match(value, '^A|([^|]+)|(%d+)|(%d+)|(%d+)$')
                local expiry, lower, upper = integer(expiryText), integer(floorText), integer(upperText)
                if not owner or #owner > 128 or string.find(owner, '[^!-~]')
                    or not expiry or not lower or not upper or expiry ~= upper or lower >= upper then return nil end
                return 'A', owner, expiry, lower, upper
            end
            local function nowMillis()
                local time = redis.call('TIME')
                return tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            end
            local function validOwner(owner)
                return owner and #owner > 0 and #owner <= 128 and not string.find(owner, '[^!-~]')
                    and not string.find(owner, '|', 1, true)
            end
            """;

    static final String ACQUIRE_SCRIPT = """
            -- common-id:machine-lease:acquire
            """ + LUA_COMMON + """
            if #KEYS ~= 1025 then return {-2} end
            local run, start, error = authority()
            if error ~= 0 then return {error} end
            local owner, lease = ARGV[1], integer(ARGV[2])
            if not validOwner(owner) or not lease or lease <= 0 then return {-4} end
            local candidates = {}
            local prefix = string.sub(KEYS[1], 1, -7) .. 'slot:'
            for i = 0, 1023 do
                local key = KEYS[i + 2]
                if key ~= prefix .. tostring(i) then return {-4} end
                local value = redis.call('GET', key)
                if not value or redis.call('PTTL', key) ~= -1 then return {-2} end
                local state, ignoredOwner, ignoredExpiry, ignoredFloor, upper = slot(value)
                if not state then return {-4} end
                candidates[i + 1] = {state, upper}
            end
            local now = nowMillis()
            if now <= 0 or now + lease > MAX then return {-5} end
            for i = 0, 1023 do
                local candidate = (start + i) % 1024
                local record = candidates[candidate + 1]
                if record[1] ~= 'A' and now > record[2] then
                    local lower = record[1] == 'F' and 0 or record[2] + 1
                    local upper = now + lease
                    local value = 'A|' .. owner .. '|' .. tostring(upper) .. '|' .. tostring(lower) .. '|' .. tostring(upper)
                    redis.call('MSET', KEYS[1], 'V1|' .. run .. '|' .. tostring((candidate + 1) % 1024),
                        KEYS[candidate + 2], value)
                    return {1, candidate, now, upper, lower, upper, run}
                end
            end
            return {0}
            """;

    static final String RENEW_SCRIPT = """
            -- common-id:machine-lease:renew
            """ + LUA_COMMON + """
            if #KEYS ~= 2 then return {-2} end
            local run, ignored, error = authority()
            if error ~= 0 then return {error} end
            if ARGV[3] ~= run then return {-3} end
            local owner, lease, machine = ARGV[1], integer(ARGV[2]), integer(ARGV[4])
            if not validOwner(owner) or not lease or lease <= 0 or not machine or machine >= 1024
                or KEYS[2] ~= string.sub(KEYS[1], 1, -7) .. 'slot:' .. tostring(machine) then return {-4} end
            local value = redis.call('GET', KEYS[2])
            if not value or redis.call('PTTL', KEYS[2]) ~= -1 then return {-2} end
            local state, current, expiry, lower, oldUpper = slot(value)
            if not state then return {-4} end
            if state ~= 'A' or current ~= owner then return {0} end
            local now = nowMillis()
            if now >= expiry then return {0} end
            if now < lower or now + lease < oldUpper or now + lease > MAX then return {-5} end
            local upper = now + lease
            redis.call('SET', KEYS[2], 'A|' .. owner .. '|' .. tostring(upper) .. '|' .. tostring(lower) .. '|' .. tostring(upper))
            return {1, machine, now, upper, lower, upper, run}
            """;

    static final String RELEASE_SCRIPT = """
            -- common-id:machine-lease:release
            """ + LUA_COMMON + """
            if #KEYS ~= 2 then return -2 end
            local run, ignored, error = authority()
            if error ~= 0 then return error end
            if ARGV[2] ~= run then return -3 end
            local value = redis.call('GET', KEYS[2])
            if not value or redis.call('PTTL', KEYS[2]) ~= -1 then return -2 end
            local state, owner, ignoredExpiry, ignoredFloor, upper = slot(value)
            if not state then return -4 end
            if state ~= 'A' or owner ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[2], 'R|' .. tostring(upper))
            return 1
            """;

    private final RedissonClient redissonClient;
    private final String env;
    private final String app;
    private final long leaseMillis;
    private final long leaseNanos;
    private final long renewMillis;
    private final String instanceId;
    private final String cursorKey;
    private final String slotKeyPrefix;
    private final List<Object> acquisitionKeys;
    private final LongSupplier nanoClock;
    private final LongSupplier wallClock;
    private final ReentrantReadWriteLock issuanceLock = new ReentrantReadWriteLock(true);
    private LeaseStateEnum state = LeaseStateEnum.NEW;
    private Long allocatedMachineId;
    private LeaseReplyVO confirmed;
    private long localDeadlineNanos;
    private ScheduledExecutorService renewScheduler;

    public RedisMachineIdProvider(RedissonClient client, String env, String app, Duration lease, Duration renew) {
        this(client, env, app, lease, renew, UUID.randomUUID().toString());
    }

    public RedisMachineIdProvider(RedissonClient client, String env, String app, Duration lease, Duration renew,
                                  String instanceId) {
        this(client, env, app, lease, renew, instanceId, System::nanoTime, Clock.systemUTC()::millis);
    }

    /** Package-local deterministic clocks; production construction always uses the process clocks. */
    RedisMachineIdProvider(RedissonClient client, String env, String app, Duration lease, Duration renew,
                           String instanceId, LongSupplier nanoClock, LongSupplier wallClock) {
        if (client == null) { throw configurationFailure("RedissonClient is required for Redis machine-id allocation"); }
        redissonClient = client;
        this.env = validateSegment("env", env);
        this.app = validateSegment("app", app);
        leaseMillis = validatePositiveDuration("lease-duration", lease);
        leaseNanos = TimeUnit.MILLISECONDS.toNanos(leaseMillis);
        renewMillis = validatePositiveDuration("renew-interval", renew);
        if (renewMillis >= leaseMillis) {
            throw configurationFailure("egon.cola.component.id.redis.renew-interval must be smaller than lease-duration");
        }
        if (instanceId == null || instanceId.isBlank() || instanceId.length() > 128
                || !instanceId.chars().allMatch(value -> value >= 0x21 && value <= 0x7e && value != '|')) {
            throw configurationFailure("instanceId must be bounded printable ASCII without a pipe");
        }
        this.instanceId = instanceId;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
        String tag = "{" + this.env + ":" + this.app + "}";
        cursorKey = KEY_PREFIX + ":" + tag + ":cursor";
        slotKeyPrefix = KEY_PREFIX + ":" + tag + ":slot:";
        List<Object> keys = new ArrayList<>(SLOT_COUNT + 1);
        keys.add(cursorKey);
        for (int slot = 0; slot < SLOT_COUNT; slot++) { keys.add(slotKey(slot)); }
        acquisitionKeys = List.copyOf(keys);
    }

    @Override
    public long getMachineId() {
        issuanceLock.writeLock().lock();
        try {
            if (state == LeaseStateEnum.ACTIVE) {
                requireAvailable();
                return allocatedMachineId;
            }
            if (state != LeaseStateEnum.NEW) { throw unavailable(); }
            long sent = nanoClock.getAsLong();
            try {
                Object raw = script(ACQUIRE_SCRIPT, RScript.ReturnType.LIST, acquisitionKeys,
                        instanceId, Long.toString(leaseMillis));
                LeaseReplyVO reply = decode(raw);
                long received = nanoClock.getAsLong();
                long deadline = sent + leaseNanos;
                if (received - sent < 0 || deadline - received <= 0) { throw unavailable(); }
                allocatedMachineId = reply.machineId();
                confirmed = reply;
                localDeadlineNanos = deadline;
                state = LeaseStateEnum.ACTIVE;
                requireAvailable();
                startRenewScheduler();
                log.info("Allocated Redis Snowflake slot env={} app={} machineId={}", env, app, allocatedMachineId);
                return allocatedMachineId;
            } catch (RuntimeException failure) {
                terminal();
                if (failure instanceof CommonException common) { throw common; }
                throw new CommonException(ResultCode.SYSTEM_ERROR.getCode(), ResultCode.SYSTEM_ERROR.getStatus(),
                        "Failed to allocate Snowflake machine-id from Redis for env=" + env + ", app=" + app, failure);
            }
        } finally {
            issuanceLock.writeLock().unlock();
        }
    }

    @Override
    public void assertIssuanceAvailable() {
        issuanceLock.writeLock().lock();
        try { requireAvailable(); }
        finally { issuanceLock.writeLock().unlock(); }
    }

    @Override
    public long issue(LongSupplier generator, LongUnaryOperator timestampDecoder) {
        Objects.requireNonNull(generator, "generator");
        Objects.requireNonNull(timestampDecoder, "timestampDecoder");
        boolean invalid = false;
        issuanceLock.readLock().lock();
        try {
            if (!available()) { invalid = true; throw unavailable(); }
            long id = generator.getAsLong();
            long timestamp;
            try { timestamp = timestampDecoder.applyAsLong(id); }
            catch (RuntimeException failure) { invalid = true; throw unavailable(); }
            if (!available() || timestamp < confirmed.floorMillis() || timestamp > confirmed.upperMillis()) {
                invalid = true;
                throw unavailable();
            }
            return id;
        } finally {
            invalid = invalid || state == LeaseStateEnum.ACTIVE && !available();
            issuanceLock.readLock().unlock();
            if (invalid) {
                issuanceLock.writeLock().lock();
                try { terminal(); }
                finally { issuanceLock.writeLock().unlock(); }
            }
        }
    }

    public String instanceId() { return instanceId; }
    public String cursorKey() { return cursorKey; }
    public String slotKey(long machineId) {
        if (machineId < 0 || machineId >= SLOT_COUNT) { throw configurationFailure("machineId must be in 0..1023"); }
        return slotKeyPrefix + machineId;
    }

    /** A denied renewal is terminal; no missing or expired owner is recreated. */
    int renewOnce() {
        issuanceLock.writeLock().lock();
        try {
            if (state != LeaseStateEnum.ACTIVE) { return -1; }
            if (!available()) { terminal(); return -1; }
            long oldDeadline = localDeadlineNanos;
            long sent = nanoClock.getAsLong();
            try {
                Object raw = script(RENEW_SCRIPT, RScript.ReturnType.LIST,
                        List.of(cursorKey, slotKey(allocatedMachineId)), instanceId, Long.toString(leaseMillis),
                        confirmed.authorityRunId(), Long.toString(allocatedMachineId));
                if (!(raw instanceof List<?> tuple) || tuple.isEmpty() || integer(tuple.getFirst()) != 1) {
                    terminal();
                    return 0;
                }
                LeaseReplyVO reply = decode(raw);
                long received = nanoClock.getAsLong();
                if (received - sent < 0 || oldDeadline - received <= 0
                        || reply.machineId() != allocatedMachineId || !reply.authorityRunId().equals(confirmed.authorityRunId())
                        || reply.floorMillis() != confirmed.floorMillis() || reply.redisMillis() < confirmed.redisMillis()
                        || reply.upperMillis() < confirmed.upperMillis()) {
                    terminal();
                    return 0;
                }
                long delta = reply.upperMillis() - confirmed.upperMillis();
                if (delta > leaseMillis) { terminal(); return 0; }
                long fromSend = sent + leaseNanos;
                long fromPrior = oldDeadline + TimeUnit.MILLISECONDS.toNanos(delta);
                long deadline = fromSend - received < fromPrior - received ? fromSend : fromPrior;
                if (deadline - received <= 0) { terminal(); return 0; }
                confirmed = reply;
                localDeadlineNanos = deadline;
                requireAvailable();
                return 1;
            } catch (RuntimeException failure) {
                terminal();
                log.warn("Redis Snowflake renewal failed; issuance stopped env={} app={} machineId={}",
                        env, app, allocatedMachineId);
                return -1;
            }
        } finally {
            issuanceLock.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        ScheduledExecutorService scheduler;
        Long machine;
        LeaseReplyVO lease;
        issuanceLock.writeLock().lock();
        try {
            if (state == LeaseStateEnum.CLOSED) { return; }
            state = LeaseStateEnum.CLOSED;
            scheduler = renewScheduler;
            renewScheduler = null;
            machine = allocatedMachineId;
            lease = confirmed;
        } finally {
            issuanceLock.writeLock().unlock();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            try { scheduler.awaitTermination(1, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        if (machine != null && lease != null) {
            try {
                Object released = script(RELEASE_SCRIPT, RScript.ReturnType.LONG,
                        List.of(cursorKey, slotKey(machine)), instanceId, lease.authorityRunId());
                if (!(released instanceof Number number) || number.longValue() != 1) {
                    log.warn("Redis Snowflake release was not confirmed; pool state retained env={} app={} machineId={}",
                            env, app, machine);
                }
            } catch (RuntimeException failure) {
                log.warn("Redis Snowflake release failed; pool state retained env={} app={} machineId={}", env, app, machine);
            }
        }
    }

    private Object script(String lua, RScript.ReturnType result, List<Object> keys, Object... args) {
        return redissonClient.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, lua, result, keys, args);
    }

    private LeaseReplyVO decode(Object raw) {
        if (!(raw instanceof List<?> values) || values.isEmpty()) { throw unavailable(); }
        long status = integer(values.getFirst());
        if (status == 0) {
            throw configurationFailure("No available Snowflake machine-id slot (0..1023) in Redis for env=" + env + ", app=" + app);
        }
        if (status != 1 || values.size() != 7 || !(values.get(6) instanceof String run)
                || !run.matches("[a-f0-9]{40}:(0|[1-9][0-9]{0,15})")) { throw unavailable(); }
        long machine = integer(values.get(1)), now = integer(values.get(2)), expiry = integer(values.get(3));
        long lower = integer(values.get(4)), upper = integer(values.get(5));
        if (machine < 0 || machine >= SLOT_COUNT || now <= 0 || expiry <= now || expiry - now != leaseMillis
                || lower < 0 || lower > now || upper != expiry || upper > MAX_SAFE_INTEGER) { throw unavailable(); }
        return new LeaseReplyVO(machine, now, expiry, lower, upper, run);
    }

    private static long integer(Object value) {
        if (!(value instanceof Number number)) { throw unavailable(); }
        long integer = number.longValue();
        if (number.doubleValue() != (double) integer) { throw unavailable(); }
        return integer;
    }

    private boolean available() {
        if (state != LeaseStateEnum.ACTIVE || confirmed == null || allocatedMachineId == null
                || localDeadlineNanos - nanoClock.getAsLong() <= 0) { return false; }
        long wall = wallClock.getAsLong();
        return wall >= confirmed.floorMillis() && wall <= confirmed.upperMillis();
    }

    private void requireAvailable() {
        if (!available()) { terminal(); throw unavailable(); }
    }

    private void terminal() {
        if (state != LeaseStateEnum.CLOSED) { state = LeaseStateEnum.TERMINAL; }
        if (renewScheduler != null) { renewScheduler.shutdown(); }
    }

    private void startRenewScheduler() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task ->
                Thread.ofPlatform().daemon(true).name("egon-cola-id-lease-" + env + "-" + app).unstarted(task));
        renewScheduler = scheduler;
        scheduler.scheduleWithFixedDelay(this::renewOnce, renewMillis, renewMillis, TimeUnit.MILLISECONDS);
    }

    private static String validateSegment(String name, String value) {
        if (value == null || value.isBlank()) {
            throw configurationFailure("egon.cola.component.id.redis." + name + " must not be blank");
        }
        String trimmed = value.trim();
        for (int index = 0; index < trimmed.length(); index++) {
            char ch = trimmed.charAt(index);
            if (ch == ':' || ch == '{' || ch == '}' || Character.isWhitespace(ch) || Character.isISOControl(ch)) {
                throw configurationFailure("egon.cola.component.id.redis." + name + " contains invalid character");
            }
        }
        return trimmed;
    }

    private static long validatePositiveDuration(String name, Duration duration) {
        long millis;
        try { millis = duration == null ? 0 : duration.toMillis(); }
        catch (ArithmeticException overflow) { millis = 0; }
        if (millis <= 0 || millis > MAX_LEASE_MILLIS) {
            throw configurationFailure("egon.cola.component.id.redis." + name + " must be positive and bounded");
        }
        return millis;
    }

    private static CommonException unavailable() {
        return configurationFailure("Redis Snowflake issuance lease is unavailable; authority recovery requires a new provider");
    }

    private static CommonException configurationFailure(String message) {
        return new CommonException(ResultCode.SYSTEM_ERROR.getCode(), ResultCode.SYSTEM_ERROR.getStatus(), message);
    }

    private enum LeaseStateEnum { NEW, ACTIVE, TERMINAL, CLOSED }
    private record LeaseReplyVO(long machineId, long redisMillis, long expiryMillis,
                                long floorMillis, long upperMillis, String authorityRunId) { }
}
