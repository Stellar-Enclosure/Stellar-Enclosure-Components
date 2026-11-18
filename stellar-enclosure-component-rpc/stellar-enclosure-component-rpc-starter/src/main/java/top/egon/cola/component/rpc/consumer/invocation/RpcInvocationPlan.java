package top.egon.cola.component.rpc.consumer.invocation;

import top.egon.cola.component.rpc.consumer.channel.RpcChannelLease;
import top.egon.cola.component.rpc.consumer.reference.RpcReferencePolicy;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceStrategy;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Immutable executable contract for one unary call to one trusted Service binding. */
public final class RpcInvocationPlan {

    @FunctionalInterface
    public interface UnaryInvoker {
        Attempt invoke(Object request, RpcChannelLease lease, Duration timeout, String invocationId) throws Exception;
    }

    public record Attempt(CompletionStage<Object> completion, Runnable cancel) {
        public Attempt {
            completion = Objects.requireNonNull(completion, "completion");
            cancel = cancel == null ? () -> { } : cancel;
        }
    }

    private final String serviceName;
    private final String fullMethodName;
    private final RpcReferencePolicy policy;
    private final RpcReferenceStrategy strategy;
    private final Class<?> responseType;
    private final UnaryInvoker invoker;

    public RpcInvocationPlan(String serviceName, String fullMethodName, RpcReferencePolicy policy,
                             RpcReferenceStrategy strategy, Class<?> responseType, UnaryInvoker invoker) {
        this.serviceName = required(serviceName, "serviceName");
        this.fullMethodName = required(fullMethodName, "fullMethodName");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.responseType = Objects.requireNonNull(responseType, "responseType");
        this.invoker = Objects.requireNonNull(invoker, "invoker");
        if (!this.fullMethodName.startsWith(this.serviceName + "/")
                || !strategy.target().methods().contains(this.fullMethodName)) {
            throw new IllegalArgumentException("RPC method is not approved for the Service binding");
        }
    }

    public String serviceName() { return serviceName; }
    public String fullMethodName() { return fullMethodName; }
    public RpcReferencePolicy policy() { return policy; }
    public RpcReferenceStrategy strategy() { return strategy; }
    public Class<?> responseType() { return responseType; }
    public UnaryInvoker invoker() { return invoker; }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
