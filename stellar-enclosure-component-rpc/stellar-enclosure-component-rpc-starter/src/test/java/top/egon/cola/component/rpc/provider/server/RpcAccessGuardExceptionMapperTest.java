package top.egon.cola.component.rpc.provider.server;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.accessguard.common.exception.AccessGuardRejectedException;
import top.egon.cola.component.accessguard.common.exception.StoreOperationException;
import top.egon.cola.component.accessguard.core.GuardDecision;
import top.egon.cola.component.accessguard.core.GuardOutcome;
import top.egon.cola.component.rpc.context.invocation.RpcFailureStage;
import top.egon.cola.component.rpc.context.invocation.RpcMetadataKeys;

import static org.assertj.core.api.Assertions.assertThat;

class RpcAccessGuardExceptionMapperTest {

    private final RpcAccessGuardExceptionMapper mapper =
            new RpcAccessGuardExceptionMapper();

    @Test
    void mapsRateLimitRejectionToProviderResourceExhausted() {
        StatusRuntimeException mapped = mapper.map(new AccessGuardRejectedException(
                GuardOutcome.rejected(
                        "rpc.order.create",
                        GuardDecision.RATE_LIMITED,
                        "rate-limit",
                        1L)))
                .orElseThrow();

        assertThat(mapped.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        assertThat(mapped.getStatus().getDescription())
                .isEqualTo("RPC provider rate limited");
        assertThat(mapped.getTrailers()).isNotNull();
        assertThat(RpcFailureStage.from(mapped.getTrailers()))
                .contains(RpcFailureStage.PROVIDER);
        assertThat(mapped.getTrailers().get(RpcMetadataKeys.ERROR_TYPE))
                .isEqualTo("rate-limit");
    }

    @Test
    void mapsQuotaExhaustionToASeparateProviderResourceExhaustedType() {
        StatusRuntimeException mapped = mapper.map(new AccessGuardRejectedException(GuardOutcome.rejected(
                "sensitive-rule", GuardDecision.RATE_LIMITED, "quota", 1L))).orElseThrow();
        assertThat(mapped.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        assertThat(mapped.getStatus().getDescription()).isEqualTo("RPC provider quota exhausted");
        assertThat(RpcFailureStage.from(mapped.getTrailers())).contains(RpcFailureStage.PROVIDER);
        assertThat(mapped.getTrailers().get(RpcMetadataKeys.ERROR_TYPE)).isEqualTo("quota");
        assertThat(mapped.getMessage()).doesNotContain("sensitive-rule");
    }

    @Test
    void unavailableQuotaStoreDoesNotExposeTheRawErrorOrLookLikeExhaustedQuota() {
        StatusRuntimeException mapped = mapper.map(new StoreOperationException(
                "sensitive-redis-address", new IllegalStateException("secret credential"))).orElseThrow();
        assertThat(mapped.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(mapped.getStatus().getDescription()).isEqualTo("RPC provider quota store is unavailable");
        assertThat(RpcFailureStage.from(mapped.getTrailers())).contains(RpcFailureStage.PROVIDER);
        assertThat(mapped.getTrailers().get(RpcMetadataKeys.ERROR_TYPE))
                .isEqualTo(RpcMetadataKeys.QUOTA_STORE_ERROR_TYPE);
        assertThat(mapped.getMessage()).doesNotContain("sensitive-redis-address", "secret credential");
        assertThat(mapped.getCause()).isNull();
    }

    @Test
    void delegatesOtherGuardDecisionsAndUnknownFailures() {
        assertThat(mapper.map(new AccessGuardRejectedException(GuardOutcome.rejected(
                "rpc.order.create",
                GuardDecision.DENY_LIST_HIT,
                "deny-list",
                1L)))).isEmpty();
        assertThat(mapper.map(new IllegalStateException("business failure")))
                .isEmpty();
    }
}
