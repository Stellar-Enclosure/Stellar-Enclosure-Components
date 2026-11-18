package top.egon.cola.component.rpc.consumer.interceptor;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.common.trace.TraceContext;
import top.egon.cola.component.rpc.context.identity.RpcProcessIdentity;
import top.egon.cola.component.rpc.context.invocation.RpcInvocationMetadata;
import top.egon.cola.component.rpc.context.invocation.RpcMetadataKeys;
import top.egon.cola.component.rpc.contract.descriptor.RpcContractDescriptor;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Writes one framework-owned logical identity and one bounded physical attempt per call. */
public class RpcConsumerClientInterceptor implements ClientInterceptor {

    private static final Runnable NO_RESPONSE_CALLBACK = () -> { };
    private static final List<Metadata.Key<String>> FRAMEWORK_REQUEST_HEADERS = List.of(
            RpcMetadataKeys.SERVICE, RpcMetadataKeys.GROUP, RpcMetadataKeys.VERSION,
            RpcMetadataKeys.LOGICAL_CALL_ID, RpcMetadataKeys.ATTEMPT, RpcMetadataKeys.REQUEST_DIGEST,
            RpcMetadataKeys.SOURCE_APP, RpcMetadataKeys.SOURCE_INSTANCE);

    private final String serviceName;
    private final String group;
    private final String version;
    private final RpcProcessIdentity processIdentity;
    private final String invocationId;
    private final Integer attempt;
    private final String requestDigest;
    private final Runnable responseCommitted;

    /** Compatibility for direct fixtures; managed invocations supply the shared logical ID and digest explicitly. */
    public RpcConsumerClientInterceptor(RpcContractDescriptor contract, RpcProcessIdentity processIdentity) {
        this(contract == null ? null : contract.serviceName(), contract == null ? null : contract.group(),
                contract == null ? null : contract.version(), processIdentity,
                SnowflakeIdGenerator.nextId(), null, null, NO_RESPONSE_CALLBACK);
    }

    public RpcConsumerClientInterceptor(RpcContractDescriptor contract, RpcProcessIdentity processIdentity,
                                        String logicalCallId, int attempt, String requestDigest) {
        this(contract, processIdentity, logicalCallId, attempt, requestDigest, NO_RESPONSE_CALLBACK);
    }

    public RpcConsumerClientInterceptor(RpcContractDescriptor contract, RpcProcessIdentity processIdentity,
                                        String logicalCallId, int attempt, String requestDigest,
                                        Runnable responseCommitted) {
        this(contract == null ? null : contract.serviceName(), contract == null ? null : contract.group(),
                contract == null ? null : contract.version(), processIdentity,
                logicalCallId, attempt, requestDigest, responseCommitted);
    }

    public RpcConsumerClientInterceptor(String serviceName, String group, String version,
                                        RpcProcessIdentity processIdentity, String invocationId) {
        this(serviceName, group, version, processIdentity, invocationId, null, null, NO_RESPONSE_CALLBACK);
    }

    private RpcConsumerClientInterceptor(String serviceName, String group, String version,
                                         RpcProcessIdentity processIdentity, String invocationId,
                                         Integer attempt, String requestDigest, Runnable responseCommitted) {
        this.serviceName = required(serviceName, "serviceName");
        this.group = required(group, "group");
        this.version = required(version, "version");
        this.processIdentity = Objects.requireNonNull(processIdentity, "processIdentity");
        this.invocationId = required(invocationId, "invocationId");
        RpcInvocationMetadata.validateLogicalCallId(this.invocationId);
        if ((attempt == null) != (requestDigest == null)) {
            throw new IllegalArgumentException("RPC attempt and request digest must be supplied together");
        }
        if (attempt != null) {
            RpcInvocationMetadata.validateAttempt(attempt);
            RpcInvocationMetadata.validateRequestDigest(requestDigest);
        }
        this.attempt = attempt;
        this.requestDigest = requestDigest;
        this.responseCommitted = Objects.requireNonNull(responseCommitted, "responseCommitted");
    }

    public static RpcConsumerClientInterceptor forTarget(String serviceName, String group, String version,
                                                         RpcProcessIdentity processIdentity, String invocationId) {
        return new RpcConsumerClientInterceptor(serviceName, group, version, processIdentity, invocationId);
    }

    public static RpcConsumerClientInterceptor forTarget(String serviceName, String group, String version,
                                                         RpcProcessIdentity processIdentity, String logicalCallId,
                                                         int attempt, String requestDigest) {
        return forTarget(serviceName, group, version, processIdentity, logicalCallId,
                attempt, requestDigest, NO_RESPONSE_CALLBACK);
    }

    public static RpcConsumerClientInterceptor forTarget(String serviceName, String group, String version,
                                                         RpcProcessIdentity processIdentity, String logicalCallId,
                                                         int attempt, String requestDigest, Runnable responseCommitted) {
        return new RpcConsumerClientInterceptor(serviceName, group, version, processIdentity,
                logicalCallId, attempt, requestDigest, responseCommitted);
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                           CallOptions callOptions, Channel next) {
        ClientCall<ReqT, RespT> delegate = next.newCall(method, callOptions);
        return new ForwardingClientCall.SimpleForwardingClientCall<>(delegate) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                RpcMetadataKeys.validateRequestHeaders(headers);
                for (Metadata.Key<String> key : FRAMEWORK_REQUEST_HEADERS) {
                    if (headers.containsKey(key)) {
                        throw new IllegalArgumentException("Framework-owned RPC request metadata cannot be supplied by the caller");
                    }
                }
                headers.merge(metadataAtStart(headers));
                AtomicBoolean committed = new AtomicBoolean();
                super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(responseListener) {
                    @Override
                    public void onHeaders(Metadata responseHeaders) {
                        markCommitted();
                        super.onHeaders(responseHeaders);
                    }

                    @Override
                    public void onMessage(RespT message) {
                        markCommitted();
                        super.onMessage(message);
                    }

                    private void markCommitted() {
                        if (committed.compareAndSet(false, true)) {
                            responseCommitted.run();
                        }
                    }
                }, headers);
            }
        };
    }

    private Metadata metadataAtStart(Metadata existingHeaders) {
        Metadata metadata = new Metadata();
        metadata.put(RpcMetadataKeys.SERVICE, serviceName);
        metadata.put(RpcMetadataKeys.GROUP, group);
        metadata.put(RpcMetadataKeys.VERSION, version);
        metadata.put(RpcMetadataKeys.LOGICAL_CALL_ID, invocationId);
        if (attempt != null) {
            metadata.put(RpcMetadataKeys.ATTEMPT, Integer.toString(attempt));
            metadata.put(RpcMetadataKeys.REQUEST_DIGEST, requestDigest);
        }
        metadata.put(RpcMetadataKeys.SOURCE_APP, processIdentity.applicationName());
        metadata.put(RpcMetadataKeys.SOURCE_INSTANCE, processIdentity.instanceId());
        if (!TraceContext.isValidTraceparent(existingHeaders.get(RpcMetadataKeys.TRACEPARENT))) {
            existingHeaders.discardAll(RpcMetadataKeys.TRACEPARENT);
            existingHeaders.discardAll(RpcMetadataKeys.TRACESTATE);
            TraceContext child = TraceContext.currentOrCreate()
                    .withSource(processIdentity.applicationName(), processIdentity.instanceId()).child();
            metadata.put(RpcMetadataKeys.TRACEPARENT, child.traceparent());
            if (child.tracestate() != null) {
                metadata.put(RpcMetadataKeys.TRACESTATE, child.tracestate());
            }
            if (child.requestId() != null && !existingHeaders.containsKey(RpcMetadataKeys.REQUEST_ID)) {
                metadata.put(RpcMetadataKeys.REQUEST_ID, child.requestId());
            }
        }
        return metadata;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
