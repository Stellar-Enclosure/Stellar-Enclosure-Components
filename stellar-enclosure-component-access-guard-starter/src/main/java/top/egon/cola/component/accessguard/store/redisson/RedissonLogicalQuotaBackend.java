package top.egon.cola.component.accessguard.store.redisson;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import top.egon.cola.component.accessguard.common.exception.StoreOperationException;
import top.egon.cola.component.accessguard.store.LogicalQuotaAcquireCommand;
import top.egon.cola.component.accessguard.store.LogicalQuotaBackend;
import top.egon.cola.component.accessguard.store.LogicalQuotaDecisionVO;
import top.egon.cola.component.accessguard.store.LogicalQuotaDecisionVO.Reason;

import java.util.List;
import java.util.Objects;

/** Atomic hard-quota admission, separate from physical rate limiting and its fallback policies. */
@Slf4j
@RequiredArgsConstructor
public final class RedissonLogicalQuotaBackend implements LogicalQuotaBackend {

    private static final long MAX_EXACT_LUA_INTEGER = 9_007_199_254_740_991L;

    private static final String ACQUIRE_SCRIPT = """
            -- access-guard:logical-quota:acquire:v1
            -- State: v1|capacity|remaining. It never expires or refills implicitly.
            -- Decision: v1|digest|cost|capacity|maxAttempts|window|created|deadline|allowed|lastAttempt.
            local maximumInteger = 9007199254740991
            local function integer(value)
                local number = tonumber(value)
                if not number or number < 0 or number > maximumInteger
                        or number ~= math.floor(number) then
                    return nil
                end
                return number
            end
            local function text(number)
                return string.format('%.0f', number)
            end
            if #KEYS ~= 2 or KEYS[1] == KEYS[2] or #ARGV ~= 6 then
                return redis.error_reply('LOGICAL_QUOTA_INVALID_ARGUMENTS')
            end
            local digest = ARGV[1]
            local attempt = integer(ARGV[2])
            local maxAttempts = integer(ARGV[3])
            local capacity = integer(ARGV[4])
            local cost = integer(ARGV[5])
            local window = integer(ARGV[6])
            if #digest ~= 64 or not string.match(digest, '^[0-9a-f]+$')
                    or not attempt or attempt < 1
                    or not maxAttempts or maxAttempts < 1 or maxAttempts > 2
                    or not capacity or capacity < 1
                    or not cost or cost < 1
                    or not window or window < 1 then
                return redis.error_reply('LOGICAL_QUOTA_INVALID_ARGUMENTS')
            end
            -- MGET alone returns nil for non-string values. Never treat a wrong type as new credit.
            for index = 1, 2 do
                local kind = redis.call('TYPE', KEYS[index]).ok
                if kind ~= 'none' and kind ~= 'string' then
                    return redis.error_reply('LOGICAL_QUOTA_INVALID_STATE_TYPE')
                end
            end
            local values = redis.call('MGET', KEYS[1], KEYS[2])
            local storedCapacity = capacity
            local remaining = capacity
            if values[1] then
                local rawCapacity, rawRemaining = string.match(values[1], '^v1|(%d+)|(%d+)$')
                storedCapacity = integer(rawCapacity)
                remaining = integer(rawRemaining)
                if not storedCapacity or storedCapacity < 1 or not remaining
                        or remaining > storedCapacity then
                    return redis.error_reply('LOGICAL_QUOTA_INVALID_STATE')
                end
            elseif values[2] then
                return redis.error_reply('LOGICAL_QUOTA_MISSING_BALANCE')
            end
            local clock = redis.call('TIME')
            local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
            if not integer(now) or not integer(now + window * 2) then
                return redis.error_reply('LOGICAL_QUOTA_INVALID_TIME')
            end
            local function denied(reason)
                return {0, reason, remaining, 0}
            end
            if storedCapacity ~= capacity then
                return denied(7)
            end
            if attempt > maxAttempts then
                return denied(6)
            end
            local created = now
            local deadline = now + window
            local allowed = 0
            local reason = 2
            if values[2] then
                local oldDigest, rawCost, rawCapacity, rawMaximum, rawWindow,
                        rawCreated, rawDeadline, rawAllowed, rawAttempt = string.match(values[2],
                        '^v1|([0-9a-f]+)|(%d+)|(%d+)|(%d+)|(%d+)|(%d+)|(%d+)|([01])|(%d+)$')
                local oldCost = integer(rawCost)
                local oldCapacity = integer(rawCapacity)
                local oldMaximum = integer(rawMaximum)
                local oldWindow = integer(rawWindow)
                created = integer(rawCreated)
                deadline = integer(rawDeadline)
                allowed = integer(rawAllowed)
                local lastAttempt = integer(rawAttempt)
                if not oldDigest or #oldDigest ~= 64
                        or not oldCost or oldCost < 1
                        or not oldCapacity or oldCapacity < 1
                        or not oldMaximum or oldMaximum < 1 or oldMaximum > 2
                        or not oldWindow or oldWindow < 1
                        or not created or not deadline or deadline ~= created + oldWindow
                        or not allowed or allowed > 1
                        or (allowed == 1 and oldCost > oldCapacity)
                        or not lastAttempt or lastAttempt < 1 or lastAttempt > oldMaximum then
                    return redis.error_reply('LOGICAL_QUOTA_INVALID_DECISION')
                end
                if oldDigest ~= digest or oldCost ~= cost then
                    return denied(4)
                end
                if oldCapacity ~= capacity or oldMaximum ~= maxAttempts or oldWindow ~= window then
                    return denied(7)
                end
                -- Redis time is authoritative. Replay never extends the original deadline.
                if now < created or now >= deadline then
                    return denied(5)
                end
                if attempt <= lastAttempt then
                    return denied(6)
                end
                reason = allowed == 1 and 1 or 3
            elseif remaining >= cost then
                allowed = 1
                remaining = remaining - cost
                reason = 0
            end
            local cleanupAt = deadline + window
            if not integer(cleanupAt) then
                return redis.error_reply('LOGICAL_QUOTA_INVALID_TIME')
            end
            local state = 'v1|' .. text(capacity) .. '|' .. text(remaining)
            local decision = 'v1|' .. digest .. '|' .. text(cost) .. '|' .. text(capacity)
                    .. '|' .. text(maxAttempts) .. '|' .. text(window) .. '|' .. text(created)
                    .. '|' .. text(deadline) .. '|' .. text(allowed) .. '|' .. text(attempt)
            -- All reads, validation and serialization precede the only admission write.
            redis.call('MSET', KEYS[1], state, KEYS[2], decision)
            -- Expiry is only bounded cleanup. A failure retains a time-checked stale decision;
            -- it must not restore credit or turn an uncertain result into a successful response.
            redis.call('PEXPIREAT', KEYS[2], text(cleanupAt))
            return {allowed, reason, remaining, allowed == 1 and attempt or 0}
            """;

    @NonNull
    private final RedissonClient client;

    @NonNull
    private final AccessGuardRedisKeyFactory keyFactory;

    @Override
    public LogicalQuotaDecisionVO acquire(LogicalQuotaAcquireCommand command) {
        Objects.requireNonNull(command, "command");
        long windowMillis;
        try {
            windowMillis = command.replayWindow().toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("replayWindow is outside supported bounds", exception);
        }
        if (windowMillis < 1 || windowMillis > MAX_EXACT_LUA_INTEGER
                || command.capacity() > MAX_EXACT_LUA_INTEGER
                || command.cost() > MAX_EXACT_LUA_INTEGER) {
            throw new IllegalArgumentException("logical quota values are outside supported bounds");
        }
        String stateKey = keyFactory.logicalQuotaState(command);
        String decisionKey = keyFactory.logicalQuotaDecision(command);
        if (stateKey.equals(decisionKey)) {
            throw new StoreOperationException("LOGICAL_QUOTA_STORE_INVALID_KEYS");
        }
        try {
            Object result = client.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE,
                    ACQUIRE_SCRIPT,
                    RScript.ReturnType.LIST,
                    List.of(stateKey, decisionKey),
                    command.requestDigest(), command.attempt(), command.maxAttempts(),
                    command.capacity(), command.cost(), windowMillis);
            LogicalQuotaDecisionVO decision = decode(result, command);
            log.debug("Logical quota admission allowed={} reason={}", decision.allowed(), decision.reason());
            return decision;
        } catch (StoreOperationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new StoreOperationException("LOGICAL_QUOTA_STORE_FAILED", exception);
        }
    }

    private LogicalQuotaDecisionVO decode(Object result, LogicalQuotaAcquireCommand command) {
        if (!(result instanceof List<?> values) || values.size() != 4) {
            throw invalidResponse();
        }
        long allowedValue = number(values.get(0));
        long reasonValue = number(values.get(1));
        long remaining = number(values.get(2));
        long acceptedAttempt = number(values.get(3));
        Reason reason = null;
        for (Reason candidate : Reason.values()) {
            if (candidate.getCode() == reasonValue) {
                reason = candidate;
                break;
            }
        }
        boolean allowed = allowedValue == 1;
        if ((allowedValue != 0 && allowedValue != 1) || reason == null
                || allowed != (reason == Reason.ALLOWED || reason == Reason.REPLAY_ALLOWED)
                || remaining < 0 || remaining > MAX_EXACT_LUA_INTEGER
                || (reason != Reason.POLICY_MISMATCH && remaining > command.capacity())
                || (allowed && (acceptedAttempt != command.attempt()
                || acceptedAttempt > command.maxAttempts()))
                || (!allowed && acceptedAttempt != 0)) {
            throw invalidResponse();
        }
        return new LogicalQuotaDecisionVO(allowed, reason, remaining, (int) acceptedAttempt);
    }

    private long number(Object value) {
        if (value instanceof Long number) {
            return number;
        }
        if (value instanceof Integer number) {
            return number.longValue();
        }
        throw invalidResponse();
    }

    private StoreOperationException invalidResponse() {
        return new StoreOperationException("LOGICAL_QUOTA_STORE_INVALID_RESPONSE");
    }
}
