package top.egon.cola.component.rpc.provider.server;

import com.google.protobuf.Message;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import top.egon.cola.component.accessguard.api.RateLimitGuard;
import top.egon.cola.component.accessguard.store.LogicalQuotaAcquireCommand;
import top.egon.cola.component.accessguard.store.LogicalQuotaBackend;
import top.egon.cola.component.accessguard.store.LogicalQuotaDecisionVO;
import top.egon.cola.component.accessguard.store.RateLimitBackend;
import top.egon.cola.component.accessguard.store.RateLimitDecision;
import top.egon.cola.component.accessguard.store.RateLimitRequest;
import top.egon.cola.component.rpc.config.EgonRpcProperties;
import top.egon.cola.component.rpc.consumer.reference.RpcReferencePolicy;
import top.egon.cola.component.rpc.context.invocation.RpcFailureStage;
import top.egon.cola.component.rpc.context.invocation.RpcInvocationMetadata;
import top.egon.cola.component.rpc.context.invocation.RpcMetadataKeys;
import top.egon.cola.component.rpc.provider.binding.RpcProviderMethodBinding;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Resource-side admission: authenticated identity, physical rate, process capacity, then hard quota. */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class RpcProviderAdmissionComponent {

    @NonNull
    private final RpcAdmissionIdentityResolver identityResolver;

    @NonNull
    private final RateLimitBackend rateLimitBackend;

    @NonNull
    private final LogicalQuotaBackend logicalQuotaBackend;

    @NonNull
    private final Map<String, MethodPolicyVO> methods;

    @NonNull
    private final Semaphore concurrency;

    private volatile boolean methodsValidated;

    /** Compiles mutable deployment properties once; this constructor is called by the owning Bean factory. */
    public RpcProviderAdmissionComponent(RpcAdmissionIdentityResolver identityResolver,
                                         RateLimitBackend rateLimitBackend,
                                         LogicalQuotaBackend logicalQuotaBackend,
                                         EgonRpcProperties.RpcProviderAdmissionConfig configuration) {
        this(identityResolver, rateLimitBackend, logicalQuotaBackend,
                compileMethods(configuration), new Semaphore(configuration.getMaxConcurrent()));
    }

    public synchronized void validateMethods(Collection<RpcProviderMethodBinding> bindings) {
        methodsValidated = false;
        Objects.requireNonNull(bindings, "bindings");
        Map<String, RpcProviderMethodBinding> actual = new LinkedHashMap<>();
        for (RpcProviderMethodBinding binding : bindings) {
            Objects.requireNonNull(binding, "binding");
            String fullMethodName = binding.method().fullMethodName();
            if (actual.putIfAbsent(fullMethodName, binding) != null) {
                throw new IllegalArgumentException("RPC admission requires an unambiguous native method");
            }
        }
        methods.forEach((name, policy) -> {
            RpcProviderMethodBinding binding = actual.get(name);
            if (binding == null) {
                throw new IllegalArgumentException("RPC admission refers to an unknown native method: " + name);
            }
            if (policy.maxAttempts() == 2
                    && (!RpcReferencePolicy.APPROVED_READ_METHOD.equals(name)
                    || !binding.method().idempotent())) {
                throw new IllegalArgumentException("RPC repeated admission requires the approved idempotent read");
            }
            Class<?> targetClass = AopUtils.getTargetClass(binding.provider().bean());
            Method contractMethod = binding.method().javaMethod();
            Method targetMethod = AopUtils.getMostSpecificMethod(contractMethod, targetClass);
            if (AnnotatedElementUtils.findMergedAnnotation(targetMethod, RateLimitGuard.class) != null
                    || AnnotatedElementUtils.findMergedAnnotation(contractMethod, RateLimitGuard.class) != null) {
                throw new IllegalArgumentException("RPC admission cannot duplicate a proxied RateLimitGuard: " + name);
            }
        });
        methodsValidated = true;
    }

    public Permit acquire(String fullMethodName, Message request) {
        if (!methodsValidated) {
            throw rejected(Status.UNAVAILABLE, "admission-config", "RPC admission is not ready");
        }
        MethodPolicyVO policy = methods.get(fullMethodName);
        if (policy == null) {
            throw rejected(Status.PERMISSION_DENIED, "admission-policy", "RPC method admission is not approved");
        }
        if (request == null) {
            throw rejected(Status.INVALID_ARGUMENT, "request", "RPC request is required");
        }
        RpcAdmissionIdentityVO identity = resolveIdentity(fullMethodName, request);
        RpcInvocationMetadata invocation = RpcInvocationMetadata.current();
        if (invocation == null || !invocation.hasAttemptMetadata()
                || invocation.logicalCallId() == null || invocation.logicalCallId().length() > 128) {
            throw rejected(Status.INVALID_ARGUMENT, "metadata", "RPC logical attempt metadata is required");
        }
        try {
            RpcInvocationMetadata.validateLogicalCallId(invocation.logicalCallId());
            RpcInvocationMetadata.validateAttempt(invocation.attempt());
            RpcInvocationMetadata.validateRequestDigest(invocation.requestDigest());
        } catch (IllegalArgumentException invalidMetadata) {
            throw rejected(Status.INVALID_ARGUMENT, "metadata", "RPC logical attempt metadata is invalid");
        }
        String digest;
        try {
            digest = RpcInvocationMetadata.canonicalRequestDigest(request);
        } catch (RuntimeException failure) {
            throw rejected(Status.INTERNAL, "request", "RPC request digest could not be computed");
        }
        acquirePhysical(identity, fullMethodName, policy);
        if (!concurrency.tryAcquire()) {
            throw rejected(Status.RESOURCE_EXHAUSTED, "concurrency", "RPC provider concurrency is exhausted");
        }
        boolean transferred = false;
        try {
            LogicalQuotaAcquireCommand command = new LogicalQuotaAcquireCommand(
                    identity.tenantId(), identity.clientId(), identity.resource(), policy.quota(),
                    fullMethodName, invocation.logicalCallId(), digest, invocation.attempt(),
                    policy.maxAttempts(), policy.quotaCapacity(), policy.quotaCost(), policy.replayWindow());
            LogicalQuotaDecisionVO decision;
            try {
                decision = Objects.requireNonNull(logicalQuotaBackend.acquire(command), "quota decision");
                if (decision.allowed() && decision.acceptedAttempt() != invocation.attempt()) {
                    throw new IllegalStateException("quota admitted a different physical attempt");
                }
            } catch (RuntimeException unavailable) {
                log.warn("RPC hard-quota admission unavailable failureType={}", unavailable.getClass().getSimpleName());
                throw rejected(Status.UNAVAILABLE, RpcMetadataKeys.QUOTA_STORE_ERROR_TYPE,
                        "RPC quota store is unavailable");
            }
            if (!decision.allowed()) {
                throw quotaRejection(decision.reason());
            }
            Permit permit = new Permit(concurrency);
            transferred = true;
            return permit;
        } finally {
            if (!transferred) {
                concurrency.release();
            }
        }
    }

    private RpcAdmissionIdentityVO resolveIdentity(String fullMethodName, Message request) {
        RpcAdmissionIdentityVO identity;
        try {
            identity = identityResolver.resolve(fullMethodName, request);
        } catch (StatusRuntimeException authenticationFailure) {
            Status status = switch (authenticationFailure.getStatus().getCode()) {
                case UNAUTHENTICATED -> Status.UNAUTHENTICATED;
                case PERMISSION_DENIED -> Status.PERMISSION_DENIED;
                default -> Status.UNAVAILABLE;
            };
            throw rejected(status, "authentication", status == Status.UNAVAILABLE
                    ? "RPC authentication is unavailable" : "RPC authentication or authorization failed");
        } catch (RuntimeException authenticationFailure) {
            log.warn("RPC admission identity unavailable failureType={}",
                    authenticationFailure.getClass().getSimpleName());
            throw rejected(Status.UNAVAILABLE, "authentication", "RPC authentication is unavailable");
        }
        if (identity == null) {
            throw rejected(Status.UNAUTHENTICATED, "authentication", "RPC authenticated identity is required");
        }
        return identity;
    }

    private void acquirePhysical(RpcAdmissionIdentityVO identity, String fullMethodName, MethodPolicyVO policy) {
        StringBuilder scope = new StringBuilder().append(identity.tenantId()).append(':');
        for (String value : List.of(identity.clientId(), identity.resource(), policy.quota(), fullMethodName)) {
            scope.append(value.length()).append(':').append(value);
        }
        String keyHash = RpcInvocationMetadata.requestDigest(scope.toString().getBytes(StandardCharsets.UTF_8));
        RateLimitDecision decision;
        try {
            decision = Objects.requireNonNull(rateLimitBackend.acquire(new RateLimitRequest(
                    "rpc-admission", "physical-v1", keyHash, policy.physicalCapacity(),
                    policy.physicalCapacity(), policy.physicalWindow(), 1)), "physical rate decision");
        } catch (RuntimeException unavailable) {
            log.warn("RPC physical-rate admission unavailable failureType={}", unavailable.getClass().getSimpleName());
            throw rejected(Status.UNAVAILABLE, "rate-limit-store", "RPC physical rate store is unavailable");
        }
        if (!decision.allowed()) {
            throw rejected(Status.RESOURCE_EXHAUSTED, "rate-limit", "RPC provider rate is exhausted");
        }
    }

    private StatusRuntimeException quotaRejection(LogicalQuotaDecisionVO.Reason reason) {
        return switch (reason) {
            case EXHAUSTED, REPLAY_DENIED ->
                    rejected(Status.RESOURCE_EXHAUSTED, "quota", "RPC quota is exhausted");
            case CONFLICT -> rejected(Status.ALREADY_EXISTS, "quota-conflict", "RPC logical call conflicts");
            case WINDOW_EXPIRED, ATTEMPT_REJECTED ->
                    rejected(Status.FAILED_PRECONDITION, "quota-attempt", "RPC logical attempt is not admissible");
            case POLICY_MISMATCH ->
                    rejected(Status.FAILED_PRECONDITION, "quota-policy", "RPC quota policy does not match");
            default -> rejected(Status.UNAVAILABLE, RpcMetadataKeys.QUOTA_STORE_ERROR_TYPE,
                    "RPC quota store returned an invalid decision");
        };
    }

    private StatusRuntimeException rejected(Status status, String errorType, String description) {
        Metadata trailers = new Metadata();
        RpcFailureStage.PROVIDER.put(trailers);
        trailers.put(RpcMetadataKeys.ERROR_TYPE, errorType);
        return status.withDescription(description).asRuntimeException(trailers);
    }

    private static Map<String, MethodPolicyVO> compileMethods(
            EgonRpcProperties.RpcProviderAdmissionConfig configuration) {
        Objects.requireNonNull(configuration, "configuration");
        if (!configuration.isConfigurationValid()) {
            throw new IllegalArgumentException("RPC admission configuration is invalid");
        }
        Map<String, MethodPolicyVO> compiled = new LinkedHashMap<>();
        configuration.getMethods().forEach((name, policy) -> compiled.put(name, new MethodPolicyVO(
                policy.getQuota(), policy.getQuotaCapacity(), policy.getQuotaCost(), policy.getMaxAttempts(),
                policy.getReplayWindow(), policy.getPhysicalCapacity(), policy.getPhysicalWindow())));
        return Map.copyOf(compiled);
    }

    private record MethodPolicyVO(String quota, long quotaCapacity, long quotaCost, int maxAttempts,
                                  Duration replayWindow, long physicalCapacity, Duration physicalWindow) { }

    /** Ownership transfers to the actual invocation, including its asynchronous completion path. */
    public static final class Permit implements AutoCloseable {

        private final Semaphore concurrency;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(Semaphore concurrency) {
            this.concurrency = concurrency;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                concurrency.release();
            }
        }
    }
}
