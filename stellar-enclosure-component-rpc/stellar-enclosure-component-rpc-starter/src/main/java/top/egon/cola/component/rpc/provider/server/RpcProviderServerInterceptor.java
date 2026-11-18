package top.egon.cola.component.rpc.provider.server;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import top.egon.cola.component.common.trace.TraceContext;
import top.egon.cola.component.rpc.context.invocation.RpcInvocationMetadata;
import top.egon.cola.component.rpc.context.invocation.RpcMetadataKeys;

import java.util.regex.Pattern;

public class RpcProviderServerInterceptor implements ServerInterceptor {

    private static final int MAX_VALUE_LENGTH = 256;

    private static final Pattern SAFE_VALUE =
            Pattern.compile("[\\x20-\\x7e]{1," + MAX_VALUE_LENGTH + "}");

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        RpcInvocationMetadata invocation;
        try {
            // Never let the proxy's expected-timeout hint replace the gRPC deadline.
            headers.discardAll(RpcMetadataKeys.PROXY_EXPECTED_REQUEST_TIMEOUT);
            RpcMetadataKeys.validateRequestHeaders(headers);
            String sourceApp = optionalValue(headers, RpcMetadataKeys.SOURCE_APP);
            String sourceInstance = optionalValue(headers, RpcMetadataKeys.SOURCE_INSTANCE);
            String attempt = headers.get(RpcMetadataKeys.ATTEMPT);
            String requestDigest = headers.get(RpcMetadataKeys.REQUEST_DIGEST);
            if ((attempt == null) != (requestDigest == null)) {
                throw new IllegalArgumentException("RPC attempt and request digest must be supplied together");
            }
            Integer parsedAttempt = null;
            if (attempt != null) {
                if (!attempt.equals("1") && !attempt.equals("2")) {
                    throw new IllegalArgumentException("RPC attempt must be 1 or 2");
                }
                parsedAttempt = Integer.valueOf(attempt);
            }
            TraceContext traceContext = TraceContext.fromHeaders(
                    name -> traceHeader(headers, name), false).withSource(sourceApp, sourceInstance);
            invocation = new RpcInvocationMetadata(
                    optionalValue(headers, RpcMetadataKeys.SERVICE),
                    optionalValue(headers, RpcMetadataKeys.GROUP),
                    optionalValue(headers, RpcMetadataKeys.VERSION),
                    optionalValue(headers, RpcMetadataKeys.LOGICAL_CALL_ID),
                    parsedAttempt,
                    requestDigest,
                    traceContext);
        } catch (IllegalArgumentException invalidMetadata) {
            call.close(Status.INVALID_ARGUMENT.withDescription("RPC request metadata is invalid"), new Metadata());
            return new ServerCall.Listener<>() { };
        }
        TraceContext traceContext = invocation.traceContext();
        Context context = Context.current().withValue(
                RpcInvocationMetadata.CONTEXT_KEY,
                invocation
        );
        ServerCall.Listener<ReqT> listener;
        try (TraceContext.Scope ignored = traceContext.open()) {
            listener = Contexts.interceptCall(context, call, headers, next);
        }
        return new TraceServerCallListener<>(listener, traceContext);
    }

    private String optionalValue(Metadata headers, Metadata.Key<String> key) {
        String value = headers.get(key);
        if (value != null && (value.isBlank() || !SAFE_VALUE.matcher(value).matches())) {
            throw new IllegalArgumentException("RPC request metadata value is invalid");
        }
        return value;
    }

    private String traceHeader(Metadata headers, String name) {
        if (TraceContext.TRACEPARENT_HEADER.equals(name)) {
            return headers.get(RpcMetadataKeys.TRACEPARENT);
        }
        if (TraceContext.TRACESTATE_HEADER.equals(name)) {
            return headers.get(RpcMetadataKeys.TRACESTATE);
        }
        if (TraceContext.REQUEST_ID_HEADER.equals(name)) {
            return headers.get(RpcMetadataKeys.REQUEST_ID);
        }
        return null;
    }

    private static final class TraceServerCallListener<ReqT>
            extends ForwardingServerCallListener
            .SimpleForwardingServerCallListener<ReqT> {

        private final TraceContext traceContext;

        private TraceServerCallListener(ServerCall.Listener<ReqT> delegate,
                                        TraceContext traceContext) {
            super(delegate);
            this.traceContext = traceContext;
        }

        @Override
        public void onMessage(ReqT message) {
            withScope(() -> super.onMessage(message));
        }

        @Override
        public void onHalfClose() {
            withScope(super::onHalfClose);
        }

        @Override
        public void onCancel() {
            withScope(super::onCancel);
        }

        @Override
        public void onComplete() {
            withScope(super::onComplete);
        }

        @Override
        public void onReady() {
            withScope(super::onReady);
        }

        private void withScope(Runnable callback) {
            try (TraceContext.Scope ignored = traceContext.open()) {
                callback.run();
            }
        }
    }
}
