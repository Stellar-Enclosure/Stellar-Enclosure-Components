package top.egon.cola.component.rpc.consumer.reference;

import jakarta.validation.constraints.Positive;

/** Immutable deadline ceiling; retries require the later explicit approval contract. */
public record RpcReferencePolicy(@Positive long timeoutMs) {
    public RpcReferencePolicy {
        if (timeoutMs <= 0) throw new IllegalArgumentException("RPC reference timeout must be positive");
    }

    public int retries() {
        return 0;
    }
}
