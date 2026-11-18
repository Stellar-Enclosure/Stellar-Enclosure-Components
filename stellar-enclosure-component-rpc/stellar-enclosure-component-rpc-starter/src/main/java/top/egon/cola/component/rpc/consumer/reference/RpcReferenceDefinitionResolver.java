package top.egon.cola.component.rpc.consumer.reference;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.lang.Nullable;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.rpc.annotation.EgonRpcMethod;
import top.egon.cola.component.rpc.annotation.EgonRpcReference;
import top.egon.cola.component.rpc.annotation.EgonRpcService;
import top.egon.cola.component.rpc.annotation.EgonServiceMeta;
import top.egon.cola.component.rpc.common.enums.EgonRpcErrorCode;
import top.egon.cola.component.rpc.common.exception.EgonRpcException;
import top.egon.cola.component.rpc.config.EgonRpcProperties;
import top.egon.cola.component.rpc.contract.descriptor.RpcContractDescriptor;
import top.egon.cola.component.rpc.contract.descriptor.RpcMethodDescriptor;
import top.egon.cola.component.rpc.contract.identity.RpcServiceIdentity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Compiles trusted deployment bindings while retaining native descriptor identity. */
@Slf4j
@Validated
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class RpcReferenceDefinitionResolver {
    @NonNull
    @Qualifier("egon.cola.component.rpc-top.egon.cola.component.rpc.config.EgonRpcProperties")
    private final EgonRpcProperties properties;
    @Nullable
    @Qualifier("applicationContext")
    private final ApplicationContext applicationContext;
    private volatile Map<String, RpcServiceTargetVO> targets;
    private Map<String, RpcReferencePolicy> retryApprovals;
    private long timeoutCeilingMs;

    public RpcReferenceDefinitionResolver(EgonRpcProperties properties) {
        this(properties, null);
    }

    @PostConstruct
    public synchronized void initialize() {
        if (targets != null) {
            return;
        }
        var consumer = properties.getConsumer();
        if (consumer == null || consumer.getBindings() == null || consumer.getDefaultTimeoutMs() <= 0) {
            throw invalid("RPC consumer bindings and a positive timeout are required");
        }
        if (!consumer.isRetryApprovalConfigurationValid()) {
            throw invalid("RPC deployment retry approval is invalid or unbound");
        }
        Map<String, RpcServiceTargetVO> configured = new LinkedHashMap<>();
        for (var entry : consumer.getBindings().entrySet()) {
            var binding = entry.getValue();
            if (binding == null) {
                throw invalid("RPC reference deployment binding is invalid");
            }
            try {
                configured.put(entry.getKey(), new RpcServiceTargetVO(entry.getKey(), binding.getVersion(),
                        binding.getNamespace(), binding.getService(), binding.getPort(), binding.getMethods()));
            } catch (IllegalArgumentException exception) {
                throw invalid("RPC reference deployment binding is invalid");
            }
        }
        Map<String, RpcReferencePolicy> approved = new LinkedHashMap<>();
        for (var entry : consumer.getRetryApprovals().entrySet()) {
            var approval = entry.getValue();
            approved.put(entry.getKey(), new RpcReferencePolicy(approval.getTotalTimeoutMs(),
                    approval.getRetries(), approval.getAttemptTimeoutMs()));
        }
        timeoutCeilingMs = consumer.getDefaultTimeoutMs();
        retryApprovals = Map.copyOf(approved);
        targets = Map.copyOf(configured);
    }

    public long defaultTimeoutMs() {
        initialize();
        return timeoutCeilingMs;
    }

    public RpcReferenceDefinition resolve(@NotNull Field field, @NotNull RpcContractDescriptor descriptor) {
        if (field == null || descriptor == null) {
            throw invalid("RPC reference field and descriptor are required");
        }
        if (!field.getType().equals(descriptor.contractType())) {
            throw invalid("RPC reference field type does not match contract");
        }
        EgonRpcReference reference = field.getAnnotation(EgonRpcReference.class);
        if (reference == null) {
            throw invalid("RPC reference field must declare @EgonRpcReference");
        }
        if (!reference.group().isEmpty() && !reference.group().equals(descriptor.group())
                || !reference.version().isEmpty() && !reference.version().equals(descriptor.version())) {
            throw invalid("RPC reference logical identity does not match its native contract");
        }
        RpcServiceIdentity identity = RpcServiceIdentity.from(descriptor);
        Set<String> methods = descriptor.methods().stream()
                .map(RpcMethodDescriptor::fullMethodName).collect(Collectors.toUnmodifiableSet());
        RpcServiceTargetVO target = resolve(annotationBindingId(reference.bindingId()), identity, methods).target();
        EgonRpcService service = descriptor.contractType().getAnnotation(EgonRpcService.class);
        EgonServiceMeta typeMeta = descriptor.contractType().getAnnotation(EgonServiceMeta.class);
        Map<Method, RpcReferencePolicy> policies = new LinkedHashMap<>();
        for (RpcMethodDescriptor method : descriptor.methods()) {
            var methodMeta = method.javaMethod().getAnnotation(EgonServiceMeta.class);
            var rpcMethod = method.javaMethod().getAnnotation(EgonRpcMethod.class);
            validateRetryHints(reference.retries(), service == null ? 0 : service.retries(), typeMeta == null ? 0 : typeMeta.retries(),
                    methodMeta == null ? 0 : methodMeta.retries(), rpcMethod == null ? 0 : rpcMethod.retries());
            long timeoutMs = timeout(
                    defaultTimeoutMs(), reference.timeoutMs(), service == null ? -1 : service.timeoutMs(),
                    typeMeta == null ? -1 : typeMeta.timeoutMs(), methodMeta == null ? -1 : methodMeta.timeoutMs(),
                    rpcMethod == null ? -1 : rpcMethod.timeoutMs());
            policies.put(method.javaMethod(), policy(method.fullMethodName(), timeoutMs, reference.retries(),
                    rpcMethod == null ? -1 : rpcMethod.retries()));
        }
        return new RpcReferenceDefinition(target, identity, policies);
    }

    public RpcReferenceDefinition resolve(String bindingId, @NotNull RpcServiceIdentity identity, @NotNull Set<String> fullMethods) {
        initialize();
        if (identity == null || fullMethods == null || fullMethods.isEmpty()
                || identity.serviceName() == null || !logicalPart(identity.group()) || !logicalPart(identity.version())) {
            throw invalid("RPC logical identity and methods are required");
        }
        for (String method : fullMethods) {
            if (!RpcServiceTargetVO.isNativeMethodName(method) || !method.startsWith(identity.serviceName() + "/")) {
                throw invalid("RPC method does not match the native service identity");
            }
        }
        if (!RpcServiceTargetVO.isBindingId(bindingId)) {
            throw invalid("RPC reference requires a valid deployment binding ID");
        }
        RpcServiceTargetVO target = targets.get(bindingId);
        if (target == null) {
            throw invalid("RPC reference binding is not configured");
        }
        if (!target.methods().containsAll(fullMethods)) {
            throw invalid("RPC method is not approved by the deployment binding");
        }
        return new RpcReferenceDefinition(target, identity, Map.of());
    }

    private String annotationBindingId(String value) {
        if (value != null && value.contains("${")) {
            if (applicationContext == null) {
                throw invalid("RPC binding placeholder requires an application context");
            }
            try {
                value = applicationContext.getEnvironment().resolveRequiredPlaceholders(value);
            } catch (IllegalArgumentException exception) {
                throw invalid("RPC binding placeholder is unresolved");
            }
        }
        if (!RpcServiceTargetVO.isBindingId(value)) {
            throw invalid("RPC reference requires a valid deployment binding ID");
        }
        return value;
    }

    private static long timeout(long processDefault, long... values) {
        if (processDefault <= 0) {
            throw invalid("RPC consumer timeout must be positive");
        }
        long resolved = processDefault;
        for (long value : values) {
            if (value == -1) {
                continue;
            }
            if (value <= 0) {
                throw invalid("RPC reference timeout must be -1 or positive");
            }
            resolved = Math.min(resolved, value);
        }
        return resolved;
    }

    private RpcReferencePolicy policy(String fullMethodName, long timeoutMs, int requestedRetries, int methodRetryCap) {
        RpcReferencePolicy approved = retryApprovals.get(fullMethodName);
        int allowedRetries = approved == null ? 0 : approved.retries();
        if (requestedRetries > allowedRetries) {
            throw invalid("RPC reference retry exceeds its deployment approval");
        }
        if (approved == null) {
            return new RpcReferencePolicy(timeoutMs);
        }
        long totalTimeoutMs = Math.min(timeoutMs, approved.timeoutMs());
        return new RpcReferencePolicy(totalTimeoutMs, requestedRetries == 0 || methodRetryCap == 0 ? 0 : allowedRetries,
                Math.min(totalTimeoutMs, approved.attemptTimeoutMs()));
    }

    private static void validateRetryHints(int... values) {
        for (int value : values) {
            if (value < -1 || value > 1) {
                throw invalid("RPC retry hints must be -1, 0 or 1");
            }
        }
    }

    private static boolean logicalPart(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.+-]{0,127}");
    }

    private static EgonRpcException invalid(String message) {
        return new EgonRpcException(EgonRpcErrorCode.RPC_INVALID_CONTRACT, message);
    }
}
