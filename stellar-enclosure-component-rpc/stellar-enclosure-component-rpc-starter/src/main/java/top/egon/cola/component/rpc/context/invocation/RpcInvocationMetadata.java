package top.egon.cola.component.rpc.context.invocation;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.MessageLite;
import io.grpc.Context;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import top.egon.cola.component.common.trace.TraceContext;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Parsed request metadata; a claimed digest does not establish request integrity or authorization. */
public record RpcInvocationMetadata(
        String service,
        String group,
        String version,
        String invocationId,
        @Min(1) @Max(2) Integer attempt,
        @Pattern(regexp = "[0-9a-f]{64}") String requestDigest,
        @NotNull TraceContext traceContext
) {

    public static final Context.Key<RpcInvocationMetadata> CONTEXT_KEY =
            Context.key("egon-rpc-invocation-metadata");

    public RpcInvocationMetadata {
        Objects.requireNonNull(traceContext, "traceContext");
        if ((attempt == null) != (requestDigest == null)) {
            throw new IllegalArgumentException("RPC attempt and request digest must be supplied together");
        }
        if (attempt != null) {
            validateAttempt(attempt);
            validateRequestDigest(requestDigest);
            validateLogicalCallId(invocationId);
        }
    }

    /** Compatibility for direct fixtures without logical-attempt metadata; quota admission must reject that absence. */
    public RpcInvocationMetadata(String service, String group, String version,
                                 String invocationId, TraceContext traceContext) {
        this(service, group, version, invocationId, null, null, traceContext);
    }

    public static RpcInvocationMetadata current() {
        return CONTEXT_KEY.get();
    }

    /** Uses the established invocation-id wire field; there is no second logical-ID header. */
    public String logicalCallId() {
        return invocationId;
    }

    public boolean hasAttemptMetadata() {
        return attempt != null;
    }

    public static void validateAttempt(int attempt) {
        if (attempt < 1 || attempt > 2) {
            throw new IllegalArgumentException("RPC attempt must be 1 or 2");
        }
    }

    public static void validateRequestDigest(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("RPC request digest must be a lowercase SHA-256 value");
        }
    }

    public static void validateLogicalCallId(String logicalCallId) {
        if (logicalCallId == null || logicalCallId.isBlank()
                || !logicalCallId.matches("[\\x21-\\x7e]{1,256}")) {
            throw new IllegalArgumentException("RPC logical call ID must be a bounded printable identifier");
        }
    }

    /** Deterministic protobuf bytes let parsed requests reproduce the digest independently of map insertion order. */
    public static String canonicalRequestDigest(MessageLite message) {
        Objects.requireNonNull(message, "message");
        byte[] bytes = new byte[message.getSerializedSize()];
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        output.useDeterministicSerialization();
        try {
            message.writeTo(output);
            output.checkNoSpaceLeft();
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to serialize the RPC request", failure);
        }
        return requestDigest(bytes);
    }

    /** Raw client requests can be hashed before parsing; the provider recomputes its canonical digest before quota. */
    public static String requestDigest(byte[] request) {
        Objects.requireNonNull(request, "request");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    public String traceId() { return traceContext.traceId(); }
    public String spanId() { return traceContext.spanId(); }
    public String parentSpanId() { return traceContext.parentSpanId(); }
    public String requestId() { return traceContext.requestId(); }
    public String sourceApp() { return traceContext.sourceApp(); }
    public String sourceInstance() { return traceContext.sourceInstance(); }
    public String traceparent() { return traceContext.traceparent(); }
    public String tracestate() { return traceContext.tracestate(); }
}
