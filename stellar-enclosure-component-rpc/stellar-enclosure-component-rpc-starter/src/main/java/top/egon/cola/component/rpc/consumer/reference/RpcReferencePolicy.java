package top.egon.cola.component.rpc.consumer.reference;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

/** Immutable total and per-attempt budgets compiled from trusted deployment policy. */
public record RpcReferencePolicy(
        @Positive long timeoutMs,
        @Min(0) @Max(1) int retries,
        @Positive long attemptTimeoutMs) {

    public static final String APPROVED_READ_METHOD =
            "egon.tianquan.shoubing.v1.IdentityDirectoryService/GetTenantMembership";
    public static final long MAX_APPROVED_ATTEMPT_TIMEOUT_MS = 600;
    public static final long MAX_APPROVED_TOTAL_TIMEOUT_MS = 1500;

    public RpcReferencePolicy {
        if (timeoutMs <= 0 || attemptTimeoutMs <= 0 || attemptTimeoutMs > timeoutMs) {
            throw new IllegalArgumentException("RPC reference budgets must be positive and ordered");
        }
        if (retries < 0 || retries > 1) {
            throw new IllegalArgumentException("RPC reference permits at most one additional attempt");
        }
        if (retries == 1 && (attemptTimeoutMs > MAX_APPROVED_ATTEMPT_TIMEOUT_MS
                || timeoutMs > MAX_APPROVED_TOTAL_TIMEOUT_MS)) {
            throw new IllegalArgumentException("RPC retry budget exceeds the approved read limits");
        }
    }

    public RpcReferencePolicy(long timeoutMs) {
        this(timeoutMs, 0, timeoutMs);
    }
}
