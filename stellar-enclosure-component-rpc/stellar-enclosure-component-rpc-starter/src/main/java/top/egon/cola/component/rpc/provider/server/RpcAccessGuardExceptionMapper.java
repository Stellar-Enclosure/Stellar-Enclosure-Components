package top.egon.cola.component.rpc.provider.server;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import top.egon.cola.component.accessguard.common.exception.AccessGuardRejectedException;
import top.egon.cola.component.accessguard.common.exception.StoreOperationException;
import top.egon.cola.component.accessguard.core.GuardDecision;
import top.egon.cola.component.rpc.context.invocation.RpcFailureStage;
import top.egon.cola.component.rpc.context.invocation.RpcMetadataKeys;

import java.util.Optional;

/** Keeps admission exhaustion distinct from an unavailable authoritative quota store. */
public final class RpcAccessGuardExceptionMapper
        implements RpcProviderExceptionMapper {

    @Override
    public Optional<StatusRuntimeException> map(Throwable throwable) {
        if (throwable instanceof StoreOperationException) {
            return Optional.of(failure(Status.UNAVAILABLE, RpcMetadataKeys.QUOTA_STORE_ERROR_TYPE,
                    "RPC provider quota store is unavailable"));
        }
        if (!(throwable instanceof AccessGuardRejectedException rejected)
                || rejected.outcome().decision() != GuardDecision.RATE_LIMITED) {
            return Optional.empty();
        }
        boolean quota = "quota".equals(rejected.outcome().policy());
        return Optional.of(failure(Status.RESOURCE_EXHAUSTED, quota ? "quota" : "rate-limit",
                quota ? "RPC provider quota exhausted" : "RPC provider rate limited"));
    }

    private StatusRuntimeException failure(Status status, String errorType, String description) {
        Metadata trailers = new Metadata();
        RpcFailureStage.PROVIDER.put(trailers);
        trailers.put(RpcMetadataKeys.ERROR_TYPE, errorType);
        return status.withDescription(description).asRuntimeException(trailers);
    }
}
