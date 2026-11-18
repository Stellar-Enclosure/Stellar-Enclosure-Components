package top.egon.cola.component.rpc.consumer.reference;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import top.egon.cola.component.rpc.contract.identity.RpcServiceIdentity;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;

/** Immutable deployment target and native logical identity compiled at injection time. */
public record RpcReferenceDefinition(
        @NotNull @Valid RpcServiceTargetVO target,
        @NotNull RpcServiceIdentity serviceIdentity,
        @NotNull Map<Method, @Valid RpcReferencePolicy> typedPolicies
) {
    public RpcReferenceDefinition {
        target = Objects.requireNonNull(target, "target");
        serviceIdentity = Objects.requireNonNull(serviceIdentity, "serviceIdentity");
        typedPolicies = typedPolicies == null ? Map.of() : Map.copyOf(typedPolicies);
    }

    public RpcReferencePolicy policyFor(Method method) {
        RpcReferencePolicy policy = typedPolicies.get(method);
        if (policy == null) throw new IllegalArgumentException("RPC method is not part of the resolved reference");
        return policy;
    }

    public String queryIdentity() {
        return target.bindingId() + "@" + target.version();
    }
}
