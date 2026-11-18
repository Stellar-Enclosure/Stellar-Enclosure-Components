package top.egon.cola.component.accessguard.store;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogicalQuotaAcquireCommandTest {

    private static final String DIGEST = "a".repeat(64);
    private static final String METHOD = "egon.tianquan.shoubing.v1.IdentityDirectoryService/GetTenantMembership";

    @Test
    void retainsTrustedScopeIdentityWithoutWhitespaceNormalizationOrProtocolCoupling() {
        var command = scoped(" client A ", "tenant resource", "read quota", "POST /internal/v1/resource", "logical-call-1", DIGEST);
        assertThat(command.clientId()).isEqualTo(" client A ");
        assertThat(command.resource()).isEqualTo("tenant resource");
        assertThat(command.fullMethodName()).isEqualTo("POST /internal/v1/resource");
        assertThat(command.logicalCallId()).isEqualTo("logical-call-1");
        assertThat(command.toString()).doesNotContain("client A", "tenant resource", "read quota",
                "/internal/v1/resource", "logical-call-1", DIGEST);
    }

    @Test
    void permitsBusinessDenialsForTheBackendInsteadOfRejectingTheirCommandShape() {
        var overCapacity = numeric(1, 1, 1, 1, 2, Duration.ofMillis(1500));
        assertThat(overCapacity.cost()).isGreaterThan(overCapacity.capacity());
        var overAttempts = numeric(1, 2, 1, 10, 1, Duration.ofMillis(300_000));
        assertThat(overAttempts.attempt()).isGreaterThan(overAttempts.maxAttempts());
        var exactIntegerBoundary = numeric(Long.MAX_VALUE, 2, 2, 9_007_199_254_740_991L,
                9_007_199_254_740_991L, Duration.ofMillis(300_000));
        assertThat(exactIntegerBoundary.capacity()).isEqualTo(9_007_199_254_740_991L);
    }

    @Test
    void rejectsUnsafeScopeAndDigestShapesAtEveryCallBoundary() {
        for (String invalid : new String[]{null, "", " ", "line\nbreak", "tab\tvalue", "non-ascii-é", "delete-\u007f"}) {
            assertInvalid(() -> scoped(invalid, "resource", "quota", METHOD, "logical", DIGEST));
            assertInvalid(() -> scoped("client", invalid, "quota", METHOD, "logical", DIGEST));
            assertInvalid(() -> scoped("client", "resource", invalid, METHOD, "logical", DIGEST));
            assertInvalid(() -> scoped("client", "resource", "quota", invalid, "logical", DIGEST));
            assertInvalid(() -> scoped("client", "resource", "quota", METHOD, invalid, DIGEST));
        }
        assertInvalid(() -> scoped("x".repeat(257), "resource", "quota", METHOD, "logical", DIGEST));
        assertInvalid(() -> scoped("client", "x".repeat(257), "quota", METHOD, "logical", DIGEST));
        assertInvalid(() -> scoped("client", "resource", "x".repeat(257), METHOD, "logical", DIGEST));
        assertInvalid(() -> scoped("client", "resource", "quota", "x".repeat(513), "logical", DIGEST));
        assertInvalid(() -> scoped("client", "resource", "quota", METHOD, "x".repeat(129), DIGEST));
        for (String digest : new String[]{null, "a".repeat(63), "a".repeat(65), "A".repeat(64), "z".repeat(64)}) {
            assertInvalid(() -> scoped("client", "resource", "quota", METHOD, "logical", digest));
        }
        assertThat(scoped("x".repeat(256), "r".repeat(256), "q".repeat(256), "m".repeat(512),
                "l".repeat(128), DIGEST)).isNotNull();
    }

    @Test
    void rejectsNonPositiveUnsafeNumericValuesAndUnsupportedAttemptShapes() {
        for (long tenant : new long[]{0, -1}) {
            assertInvalid(() -> numeric(tenant, 1, 2, 10, 1, Duration.ofMillis(1500)));
        }
        for (long value : new long[]{0, -1, 9_007_199_254_740_992L, Long.MAX_VALUE}) {
            assertInvalid(() -> numeric(1, 1, 2, value, 1, Duration.ofMillis(1500)));
            assertInvalid(() -> numeric(1, 1, 2, 10, value, Duration.ofMillis(1500)));
        }
        for (int value : new int[]{0, -1, 3}) {
            assertInvalid(() -> numeric(1, value, 2, 10, 1, Duration.ofMillis(1500)));
            assertInvalid(() -> numeric(1, 1, value, 10, 1, Duration.ofMillis(1500)));
        }
    }

    @Test
    void rejectsTruncatedFractionalAndOverflowingReplayWindows() {
        for (Duration window : Arrays.asList(null, Duration.ZERO, Duration.ofMillis(-1), Duration.ofMillis(1499),
                Duration.ofMillis(300_001), Duration.ofMillis(1500).plusNanos(1), Duration.ofSeconds(Long.MAX_VALUE))) {
            assertInvalid(() -> numeric(1, 1, 2, 10, 1, window));
        }
    }

    @Test
    void keepsReasonCodesStableAndRejectsAnInconsistentAllowedFlag() {
        assertThat(LogicalQuotaDecisionVO.Reason.values()).extracting(LogicalQuotaDecisionVO.Reason::getCode)
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
        for (var reason : LogicalQuotaDecisionVO.Reason.values()) {
            boolean allowed = reason == LogicalQuotaDecisionVO.Reason.ALLOWED
                    || reason == LogicalQuotaDecisionVO.Reason.REPLAY_ALLOWED;
            assertThat(new LogicalQuotaDecisionVO(allowed, reason, 0, allowed ? 1 : 0).allowed()).isEqualTo(allowed);
            assertThat(reason.getMessage()).isNotBlank();
            assertInvalid(() -> new LogicalQuotaDecisionVO(!allowed, reason, 0, 1));
        }
        assertInvalid(() -> new LogicalQuotaDecisionVO(false, null, 0, 0));
        assertInvalid(() -> new LogicalQuotaDecisionVO(false, LogicalQuotaDecisionVO.Reason.EXHAUSTED, -1, 0));
        assertInvalid(() -> new LogicalQuotaDecisionVO(false, LogicalQuotaDecisionVO.Reason.EXHAUSTED, 0, 3));
        assertInvalid(() -> new LogicalQuotaDecisionVO(true, LogicalQuotaDecisionVO.Reason.ALLOWED, 0, 0));
        assertInvalid(() -> new LogicalQuotaDecisionVO(false, LogicalQuotaDecisionVO.Reason.EXHAUSTED, 0, 1));
    }

    private static LogicalQuotaAcquireCommand scoped(String clientId, String resource, String quota,
                                                     String fullMethodName, String logicalCallId, String digest) {
        return new LogicalQuotaAcquireCommand(1, clientId, resource, quota, fullMethodName, logicalCallId,
                digest, 1, 2, 10, 1, Duration.ofMillis(1500));
    }

    private static LogicalQuotaAcquireCommand numeric(long tenantId, int attempt, int maxAttempts,
                                                      long capacity, long cost, Duration replayWindow) {
        return new LogicalQuotaAcquireCommand(tenantId, "client", "resource", "quota", METHOD, "logical", DIGEST,
                attempt, maxAttempts, capacity, cost, replayWindow);
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(IllegalArgumentException.class);
    }
}
