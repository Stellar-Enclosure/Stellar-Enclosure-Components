package top.egon.cola.component.rpc.consumer.generic;

import top.egon.cola.component.rpc.common.enums.EgonRpcErrorCode;
import top.egon.cola.component.rpc.common.exception.EgonRpcException;

import java.util.Arrays;
import java.util.regex.Pattern;

/** Immutable raw unary command. A binding identifier selects the trusted Service target. */
public final class RpcGenericInvocation {

    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:+-]{0,127}");
    private static final Pattern METHOD_SEGMENT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.]{0,127}");

    private final String bindingId;
    private final String serviceName;
    private final String group;
    private final String version;
    private final String fullMethodName;
    private final byte[] requestPayload;
    private final long timeoutMs;

    public RpcGenericInvocation(String bindingId, String serviceName, String group, String version,
                                String fullMethodName, byte[] requestPayload, long timeoutMs) {
        this.bindingId = requiredSegment(bindingId, "bindingId");
        this.serviceName = requiredSegment(serviceName, "serviceName");
        this.group = requiredSegment(group, "group");
        this.version = requiredSegment(version, "version");
        this.fullMethodName = canonicalFullMethodName(this.serviceName, fullMethodName);
        if (requestPayload == null) { throw invalid("generic request payload is required"); }
        this.requestPayload = Arrays.copyOf(requestPayload, requestPayload.length);
        if (timeoutMs <= 0) { throw invalid("generic timeout must be positive"); }
        this.timeoutMs = timeoutMs;
    }

    public String bindingId() { return bindingId; }
    public String serviceName() { return serviceName; }
    public String group() { return group; }
    public String version() { return version; }
    public String fullMethodName() { return fullMethodName; }
    public byte[] requestPayload() { return Arrays.copyOf(requestPayload, requestPayload.length); }
    public long timeoutMs() { return timeoutMs; }

    private static String canonicalFullMethodName(
            String serviceName,
            String fullMethodName) {
        if (fullMethodName == null) {
            throw invalid("generic fullMethodName is required");
        }
        String normalized = fullMethodName.trim();
        int slash = normalized.indexOf('/');
        if (slash <= 0 || slash != normalized.lastIndexOf('/')
                || slash == normalized.length() - 1) {
            throw invalid(
                    "generic fullMethodName must be canonical Service/Method");
        }
        String methodService = normalized.substring(0, slash);
        String method = normalized.substring(slash + 1);
        if (!serviceName.equals(methodService)
                || !SAFE_SEGMENT.matcher(methodService).matches()
                || !METHOD_SEGMENT.matcher(method).matches()) {
            throw invalid("generic fullMethodName does not match service identity");
        }
        return normalized;
    }

    private static String requiredSegment(String value, String name) {
        String normalized = optionalSegment(value, name);
        if (normalized == null) {
            throw invalid(name + " is required");
        }
        return normalized;
    }

    private static String optionalSegment(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (!SAFE_SEGMENT.matcher(normalized).matches()) {
            throw invalid(name + " is invalid");
        }
        return normalized;
    }

    private static EgonRpcException invalid(String message) {
        return new EgonRpcException(EgonRpcErrorCode.RPC_INVALID_REQUEST, message);
    }
}
