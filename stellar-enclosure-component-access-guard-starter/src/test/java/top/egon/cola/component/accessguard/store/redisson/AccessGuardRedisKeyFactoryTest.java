package top.egon.cola.component.accessguard.store.redisson;

import org.junit.jupiter.api.Test;
import top.egon.cola.component.accessguard.core.plan.AdmissionConfig;
import top.egon.cola.component.accessguard.store.LogicalQuotaAcquireCommand;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessGuardRedisKeyFactoryTest {

    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private final AccessGuardRedisKeyFactory factory =
            new AccessGuardRedisKeyFactory("egon:access-guard", "draw-service");

    @Test
    void stateKeysContainNamespaceApplicationPolicyVersionAndOnlyTheHash() {
        String key = factory.rateLimit("draw", "state-v2", HASH);

        assertThat(key)
                .isEqualTo("egon:access-guard:draw-service:draw:rate-limit:state-v2:" + HASH)
                .doesNotContain("raw-user-id");
    }

    @Test
    void keepsTokenBucketKeyUnchanged() {
        assertThat(factory.rateLimit(
                "draw", "state-v2", HASH, AdmissionConfig.RateLimitAlgorithm.TOKEN_BUCKET))
                .isEqualTo(factory.rateLimit("draw", "state-v2", HASH));
    }

    @Test
    void addsSafeLeakySuffix() {
        assertThat(factory.rateLimit(
                "draw", "state-v2", HASH, AdmissionConfig.RateLimitAlgorithm.LEAKY_BUCKET))
                .isEqualTo(factory.rateLimit("draw", "state-v2", HASH) + ":leaky-bucket");
    }

    @Test
    void addsSafeSlidingSuffix() {
        assertThat(factory.rateLimit(
                "draw", "state-v2", HASH, AdmissionConfig.RateLimitAlgorithm.SLIDING_WINDOW))
                .isEqualTo(factory.rateLimit("draw", "state-v2", HASH) + ":sliding-window");
    }

    @Test
    void listKeysUseTheirIndependentDataVersion() {
        assertThat(factory.allowList("draw", "allow-v3"))
                .isEqualTo("egon:access-guard:draw-service:draw:allow-list:allow-v3");
        assertThat(factory.denyList("draw", "deny-v4"))
                .isEqualTo("egon:access-guard:draw-service:draw:deny-list:deny-v4");
    }

    @Test
    void rejectsUnsafeSegmentsAndNonHashedIdentity() {
        assertThatThrownBy(() -> factory.rateLimit("draw:other", "v1", HASH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ruleId");
        assertThatThrownBy(() -> factory.rateLimit("draw", "v1", "raw-user-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyHash");
    }

    @Test
    void logicalStateAndDecisionShareOnlyTheHashedAuthorizationScope() {
        LogicalQuotaAcquireCommand command = command(42, "private-client", "https://resource.example/rpc",
                "private-quota", "sample.Service/Read", "private-logical-call", HASH, 1);
        String state = factory.logicalQuotaState(command);
        String decision = factory.logicalQuotaDecision(command);

        assertThat(state).matches("egon:access-guard:draw-service:logical-quota:\\{[0-9a-f]{64}\\}:state");
        assertThat(decision).matches(
                "egon:access-guard:draw-service:logical-quota:\\{[0-9a-f]{64}\\}:decision:[0-9a-f]{64}");
        assertThat(hashTag(state)).isEqualTo(hashTag(decision));
        for (String key : List.of(state, decision)) {
            assertThat(key).doesNotContain(command.clientId(), command.resource(), command.quota(),
                    command.fullMethodName(), command.logicalCallId(), command.requestDigest());
        }
    }

    @Test
    void logicalIdsChangeDecisionSuffixWithoutSplittingTheQuotaStateOrRedisSlot() {
        LogicalQuotaAcquireCommand first = command(42, "client", "https://resource.example/rpc", "quota",
                "sample.Service/Read", "call-one", HASH, 1);
        LogicalQuotaAcquireCommand otherCall = command(42, "client", "https://resource.example/rpc", "quota",
                "sample.Service/Read", "call-two", HASH, 1);
        LogicalQuotaAcquireCommand retry = command(42, "client", "https://resource.example/rpc", "quota",
                "sample.Service/Read", "call-one", "b".repeat(64), 2);

        assertThat(factory.logicalQuotaState(first)).isEqualTo(factory.logicalQuotaState(otherCall))
                .isEqualTo(factory.logicalQuotaState(retry));
        assertThat(factory.logicalQuotaDecision(first)).isNotEqualTo(factory.logicalQuotaDecision(otherCall))
                .isEqualTo(factory.logicalQuotaDecision(retry));
        assertThat(hashTag(factory.logicalQuotaDecision(first)))
                .isEqualTo(hashTag(factory.logicalQuotaDecision(otherCall)));
    }

    @Test
    void everyAuthorizationScopeFieldSeparatesQuotaState() {
        List<LogicalQuotaAcquireCommand> scopes = List.of(
                command(42, "client", "https://resource.example/rpc", "quota", "sample.Service/Read", "call", HASH, 1),
                command(43, "client", "https://resource.example/rpc", "quota", "sample.Service/Read", "call", HASH, 1),
                command(42, "other-client", "https://resource.example/rpc", "quota", "sample.Service/Read", "call", HASH, 1),
                command(42, "client", "https://other-resource.example/rpc", "quota", "sample.Service/Read", "call", HASH, 1),
                command(42, "client", "https://resource.example/rpc", "other-quota", "sample.Service/Read", "call", HASH, 1),
                command(42, "client", "https://resource.example/rpc", "quota", "sample.Service/Other", "call", HASH, 1));

        assertThat(scopes.stream().map(factory::logicalQuotaState).toList()).doesNotHaveDuplicates();
        assertThat(scopes.stream().map(factory::logicalQuotaDecision).toList()).doesNotHaveDuplicates();
    }

    @Test
    void lengthPrefixesDistinguishScopesThatWouldCollideWhenJoinedWithColons() {
        LogicalQuotaAcquireCommand first = command(42, "private-client:part", "resource", "quota",
                "sample.Service/Read", "call", HASH, 1);
        LogicalQuotaAcquireCommand second = command(42, "private-client", "part:resource", "quota",
                "sample.Service/Read", "call", HASH, 1);
        assertThat(first.clientId() + ':' + first.resource()).isEqualTo(second.clientId() + ':' + second.resource());
        assertThat(factory.logicalQuotaState(first)).isNotEqualTo(factory.logicalQuotaState(second));
        assertThat(hashTag(factory.logicalQuotaState(first)))
                .isNotEqualTo(hashTag(factory.logicalQuotaState(second)));
    }

    @Test
    void scopeAndLogicalIdHashTagsCannotOverrideTheAdministrativeKeySlot() {
        LogicalQuotaAcquireCommand command = command(42, "client{injected}", "resource{injected}",
                "quota{injected}", "sample.Service/Read", "call{injected}", HASH, 1);
        assertThat(factory.logicalQuotaState(command))
                .matches("egon:access-guard:draw-service:logical-quota:\\{[0-9a-f]{64}\\}:state")
                .doesNotContain("injected");
        assertThat(hashTag(factory.logicalQuotaDecision(command)))
                .isEqualTo(hashTag(factory.logicalQuotaState(command)));
    }

    @Test
    void quotaPolicyChangesDoNotAllocateFreshStateAndLogicalKeysCannotCollideWithPhysicalRateLimits() {
        LogicalQuotaAcquireCommand first = command(42, "client", "https://resource.example/rpc", "quota",
                "sample.Service/Read", "call", HASH, 1);
        LogicalQuotaAcquireCommand changedPolicy = new LogicalQuotaAcquireCommand(42, "client",
                "https://resource.example/rpc", "quota", "sample.Service/Read", "call", HASH,
                1, 1, 100, 2, Duration.ofSeconds(60));

        assertThat(factory.logicalQuotaState(first)).isEqualTo(factory.logicalQuotaState(changedPolicy));
        assertThat(factory.logicalQuotaDecision(first)).isEqualTo(factory.logicalQuotaDecision(changedPolicy));
        assertThat(factory.rateLimit("quota", "v1", HASH))
                .isEqualTo("egon:access-guard:draw-service:quota:rate-limit:v1:" + HASH)
                .doesNotContain("{")
                .isNotEqualTo(factory.logicalQuotaState(first))
                .isNotEqualTo(factory.logicalQuotaDecision(first));
    }

    @Test
    void rejectsRedisHashTagInjectionInAdministrativeNamespaceConfiguration() {
        for (String prefix : List.of("egon:{other}", "egon:guard{other}", "egon:guard}")) {
            assertThatThrownBy(() -> new AccessGuardRedisKeyFactory(prefix, "draw-service"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("keyPrefix");
        }
        assertThatThrownBy(() -> new AccessGuardRedisKeyFactory("egon:access-guard", "draw{other}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("application");
    }

    private LogicalQuotaAcquireCommand command(long tenantId, String clientId, String resource, String quota,
                                               String method, String logicalCallId, String digest, int attempt) {
        return new LogicalQuotaAcquireCommand(tenantId, clientId, resource, quota, method, logicalCallId, digest,
                attempt, 2, 10, 1, Duration.ofSeconds(30));
    }

    private String hashTag(String key) {
        int start = key.indexOf('{');
        int end = key.indexOf('}', start + 1);
        assertThat(start).isNotNegative();
        assertThat(end).isGreaterThan(start);
        return key.substring(start + 1, end);
    }
}
