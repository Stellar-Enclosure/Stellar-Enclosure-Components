package top.egon.cola.component.accessguard.store.redisson;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import top.egon.cola.component.accessguard.common.exception.StoreOperationException;
import top.egon.cola.component.accessguard.store.LogicalQuotaAcquireCommand;
import top.egon.cola.component.accessguard.store.LogicalQuotaDecisionVO;
import top.egon.cola.component.accessguard.store.LogicalQuotaDecisionVO.Reason;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RedissonLogicalQuotaBackendTest {

    private static final String DIGEST = "a".repeat(64);

    private final RedissonClient client = mock(RedissonClient.class);
    private final RScript script = mock(RScript.class);
    private final AccessGuardRedisKeyFactory keyFactory =
            new AccessGuardRedisKeyFactory("egon:access-guard:test", "identity-directory");
    private final RedissonLogicalQuotaBackend backend =
            new RedissonLogicalQuotaBackend(client, keyFactory);

    @BeforeEach
    void configureClient() {
        when(client.getScript(StringCodec.INSTANCE)).thenReturn(script);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void usesOneEvaluationWithBothKeysAndExplicitPolicyArguments() {
        LogicalQuotaAcquireCommand command = command(1);
        respond(List.of(1L, 0L, 2L, 1L));

        LogicalQuotaDecisionVO decision = backend.acquire(command);

        assertThat(decision).isEqualTo(new LogicalQuotaDecisionVO(true, Reason.ALLOWED, 2, 1));
        ArgumentCaptor<String> lua = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(script).eval(eq(RScript.Mode.READ_WRITE), lua.capture(), eq(RScript.ReturnType.LIST),
                keys.capture(), arguments.capture());
        assertThat(keys.getValue()).containsExactly(
                keyFactory.logicalQuotaState(command), keyFactory.logicalQuotaDecision(command));
        assertThat(arguments.getValue()).containsExactly(DIGEST, 1, 2, 3L, 1L, 2_000L);
        assertThat(lua.getValue()).contains("redis.call('TYPE'", "redis.call('TIME')",
                "redis.call('MGET'", "redis.call('MSET'", "redis.call('PEXPIREAT'",
                "attempt <= lastAttempt", "now >= deadline", "storedCapacity ~= capacity");
        assertThat(lua.getValue().split("redis.call\\('MSET'", -1)).hasSize(2);
        assertThat(lua.getValue()).doesNotContain("redis.call('DECR'", "redis.call('INCR'");
        verifyNoMoreInteractions(script);
    }

    @ParameterizedTest
    @EnumSource(Reason.class)
    void decodesEveryExplicitReasonWithoutUsingOrdinal(Reason reason) {
        boolean allowed = reason == Reason.ALLOWED || reason == Reason.REPLAY_ALLOWED;
        respond(List.of(allowed ? 1L : 0L, (long) reason.getCode(), 2L, allowed ? 2L : 0L));

        LogicalQuotaDecisionVO decision = backend.acquire(command(2));

        assertThat(decision).isEqualTo(new LogicalQuotaDecisionVO(
                allowed, reason, 2, allowed ? 2 : 0));
        verify(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.LIST),
                anyList(), any(Object[].class));
        verifyNoMoreInteractions(script);
    }

    @Test
    void policyMismatchCanReportTheExistingAuthorityBalance() {
        respond(List.of(0L, (long) Reason.POLICY_MISMATCH.getCode(), 25L, 0L));

        assertThat(backend.acquire(command(1))).isEqualTo(
                new LogicalQuotaDecisionVO(false, Reason.POLICY_MISMATCH, 25, 0));
    }

    @Test
    void tooCostlyRequestReachesAtomicAdmissionAndReturnsExhausted() {
        LogicalQuotaAcquireCommand base = command(1);
        LogicalQuotaAcquireCommand tooCostly = new LogicalQuotaAcquireCommand(
                base.tenantId(), base.clientId(), base.resource(), base.quota(), base.fullMethodName(),
                base.logicalCallId(), base.requestDigest(), 1, 2, 3, 4, base.replayWindow());
        respond(List.of(0L, (long) Reason.EXHAUSTED.getCode(), 3L, 0L));

        assertThat(backend.acquire(tooCostly)).isEqualTo(
                new LogicalQuotaDecisionVO(false, Reason.EXHAUSTED, 3, 0));
        verify(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.LIST),
                anyList(), eq(DIGEST), eq(1), eq(2), eq(3L), eq(4L), eq(2_000L));
        verifyNoMoreInteractions(script);
    }

    @Test
    void rejectsMalformedOrContradictoryRedisResponsesWithoutAnotherAttempt() {
        Object[] invalid = {
                null, "invalid", List.of(), List.of(1L, 0L, 2L),
                List.of(1L, 99L, 2L, 1L), List.of(2L, 0L, 2L, 1L),
                List.of(1L, 2L, 2L, 1L), List.of(0L, 0L, 2L, 0L),
                List.of(1L, 0L, -1L, 1L), List.of(1L, 0L, 4L, 1L),
                List.of(1L, 0L, 2L, 2L), List.of(0L, 2L, 2L, 1L),
                List.of(1.1D, 0L, 2L, 1L), List.of("1", 0L, 2L, 1L)
        };
        for (Object value : invalid) {
            respond(value);
            assertThatThrownBy(() -> backend.acquire(command(1)))
                    .isInstanceOf(StoreOperationException.class)
                    .hasMessage("LOGICAL_QUOTA_STORE_INVALID_RESPONSE");
        }
        verify(script, org.mockito.Mockito.times(invalid.length)).eval(
                eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.LIST),
                anyList(), any(Object[].class));
        verifyNoMoreInteractions(script);
    }

    @Test
    void redisFailureIsAStoreFailureWithoutRetryOrLocalFallback() {
        RuntimeException failure = new IllegalStateException("Redis unavailable");
        when(script.eval(any(), anyString(), any(), anyList(), any(Object[].class)))
                .thenThrow(failure);

        assertThatThrownBy(() -> backend.acquire(command(1)))
                .isInstanceOf(StoreOperationException.class)
                .hasMessage("LOGICAL_QUOTA_STORE_FAILED")
                .hasCause(failure);
        verify(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.LIST),
                anyList(), any(Object[].class));
        verifyNoMoreInteractions(script);
    }

    @Test
    void nullCommandDoesNotContactRedis() {
        assertThatThrownBy(() -> backend.acquire(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("command");
        verifyNoInteractions(client, script);
    }

    private void respond(Object value) {
        when(script.eval(any(), anyString(), any(), anyList(), any(Object[].class))).thenReturn(value);
    }

    private LogicalQuotaAcquireCommand command(int attempt) {
        return new LogicalQuotaAcquireCommand(42, "tianquan-jianshen", "identity-directory",
                "membership-v1", "identity.Directory/GetTenantMembership", "call-1", DIGEST,
                attempt, 2, 3, 1, Duration.ofSeconds(2));
    }
}
