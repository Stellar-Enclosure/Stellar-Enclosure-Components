package top.egon.cola.component.rpc.consumer.invocation;

import top.egon.cola.component.rpc.consumer.channel.RpcChannelLease;
import top.egon.cola.component.rpc.consumer.reference.RpcReferencePolicy;
import top.egon.cola.component.rpc.consumer.reference.RpcReferenceStrategy;

import io.grpc.Deadline;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** Immutable executable contract for a bounded logical unary call to one trusted Service binding. */
public final class RpcInvocationPlan {

    @FunctionalInterface
    public interface UnaryInvoker {
        Attempt invoke(Object request, RpcChannelLease lease, Duration timeout, AttemptContextVO attempt) throws Exception;
    }

    @FunctionalInterface
    public interface InvocationFactory {
        UnaryInvoker create(Object request, String logicalCallId);
    }

    /** Per-attempt transport state; the logical identifier and absolute deadline cannot be replaced. */
    public static final class AttemptContextVO {
        private final String logicalCallId;
        private final int number;
        private final Deadline deadline;
        private final AtomicBoolean responseCommitted = new AtomicBoolean();

        public AttemptContextVO(String logicalCallId, int number, Deadline deadline) {
            this.logicalCallId = required(logicalCallId, "logicalCallId");
            if (number < 1 || number > 2) { throw new IllegalArgumentException("RPC attempt must be 1 or 2"); }
            this.number = number;
            this.deadline = Objects.requireNonNull(deadline, "deadline");
        }

        public String logicalCallId() { return logicalCallId; }
        public int number() { return number; }
        public Deadline deadline() { return deadline; }
        public void markResponseCommitted() { responseCommitted.set(true); }
        public boolean responseCommitted() { return responseCommitted.get(); }
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
    private final InvocationFactory invocationFactory;

    public RpcInvocationPlan(String serviceName, String fullMethodName, RpcReferencePolicy policy,
                             RpcReferenceStrategy strategy, Class<?> responseType, UnaryInvoker invoker) {
        this(serviceName, fullMethodName, policy, strategy, responseType, fixed(invoker));
    }

    public RpcInvocationPlan(String serviceName, String fullMethodName, RpcReferencePolicy policy,
                             RpcReferenceStrategy strategy, Class<?> responseType, InvocationFactory invocationFactory) {
        this.serviceName = required(serviceName, "serviceName");
        this.fullMethodName = required(fullMethodName, "fullMethodName");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.responseType = Objects.requireNonNull(responseType, "responseType");
        this.invocationFactory = Objects.requireNonNull(invocationFactory, "invocationFactory");
        if (!this.fullMethodName.startsWith(this.serviceName + "/")
                || !strategy.target().methods().contains(this.fullMethodName)) {
            throw new IllegalArgumentException("RPC method is not approved for the Service binding");
        }
        if (policy.retries() > 0 && !RpcReferencePolicy.APPROVED_READ_METHOD.equals(this.fullMethodName)) {
            throw new IllegalArgumentException("RPC retries are not approved for this exact method");
        }
    }

    public String serviceName() { return serviceName; }
    public String fullMethodName() { return fullMethodName; }
    public RpcReferencePolicy policy() { return policy; }
    public RpcReferenceStrategy strategy() { return strategy; }
    public Class<?> responseType() { return responseType; }
    public UnaryInvoker prepare(Object request, String logicalCallId) {
        return Objects.requireNonNull(invocationFactory.create(request, required(logicalCallId, "logicalCallId")),
                "RPC invocation factory returned no invoker");
    }

    private static InvocationFactory fixed(UnaryInvoker invoker) {
        Objects.requireNonNull(invoker, "invoker");
        return (request, logicalCallId) -> invoker;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
