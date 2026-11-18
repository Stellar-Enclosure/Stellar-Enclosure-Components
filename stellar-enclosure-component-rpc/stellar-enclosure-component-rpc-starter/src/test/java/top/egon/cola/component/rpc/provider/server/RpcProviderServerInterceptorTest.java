package top.egon.cola.component.rpc.provider.server;

import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import top.egon.cola.component.common.trace.TraceContext;
import top.egon.cola.component.rpc.context.invocation.RpcInvocationMetadata;
import top.egon.cola.component.rpc.context.invocation.RpcMetadataKeys;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RpcProviderServerInterceptorTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void shouldExposeValidatedMetadataAndCreateMissingTrace() {
        Metadata headers = new Metadata();
        headers.put(RpcMetadataKeys.SERVICE, "egon.rpc.test.Echo");
        headers.put(RpcMetadataKeys.GROUP, "default");
        RpcInvocationMetadata[] observed = new RpcInvocationMetadata[1];
        ServerCallHandler<String, String> next = (call, metadata) -> {
            observed[0] = RpcInvocationMetadata.current();
            return new ServerCall.Listener<>() {
            };
        };

        new RpcProviderServerInterceptor().interceptCall(
                new NoOpServerCall(),
                headers,
                next
        );

        assertThat(observed[0].service())
                .isEqualTo("egon.rpc.test.Echo");
        assertThat(observed[0].group()).isEqualTo("default");
        assertThat(observed[0].traceId()).matches("[0-9a-f]{32}");
        assertThat(observed[0].spanId()).matches("[0-9a-f]{16}");
        assertThat(observed[0].hasAttemptMetadata()).isFalse();
        assertThat(observed[0].attempt()).isNull();
        assertThat(observed[0].requestDigest()).isNull();
        assertThat(RpcInvocationMetadata.current()).isNull();
    }

    @Test
    void parsesTheBoundedPairAndKeepsTheClaimedDigestSeparateFromRequestVerification() {
        RpcInvocationMetadata[] observed = new RpcInvocationMetadata[1];
        Metadata headers = attemptHeaders("2", "a".repeat(64));
        headers.put(RpcMetadataKeys.AUTHORIZATION, "Bearer test-only-token");
        NoOpServerCall call = new NoOpServerCall();
        new RpcProviderServerInterceptor().interceptCall(call, headers, (serverCall, metadata) -> {
            observed[0] = RpcInvocationMetadata.current();
            assertThat(metadata.getAll(RpcMetadataKeys.AUTHORIZATION)).containsExactly("Bearer test-only-token");
            return new ServerCall.Listener<>() { };
        });
        assertThat(call.closedStatus).isNull();
        assertThat(observed[0].logicalCallId()).isEqualTo("invoke-1");
        assertThat(observed[0].invocationId()).isEqualTo("invoke-1");
        assertThat(observed[0].hasAttemptMetadata()).isTrue();
        assertThat(observed[0].attempt()).isEqualTo(2);
        assertThat(observed[0].requestDigest()).isEqualTo("a".repeat(64));
        assertThat(RpcInvocationMetadata.current()).isNull();
    }

    @Test
    void rejectsMalformedAndIncompleteAttemptPairsBeforeDispatch() {
        for (String attempt : List.of("0", "3", "-1", "01", "1,2", "1 ", "", "2147483648")) {
            assertRejected(attemptHeaders(attempt, "a".repeat(64)));
        }
        for (String digest : List.of("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64))) {
            assertRejected(attemptHeaders("1", digest));
        }
        Metadata withoutDigest = attemptHeaders("1", "a".repeat(64));
        withoutDigest.discardAll(RpcMetadataKeys.REQUEST_DIGEST);
        assertRejected(withoutDigest);
        Metadata withoutAttempt = attemptHeaders("1", "a".repeat(64));
        withoutAttempt.discardAll(RpcMetadataKeys.ATTEMPT);
        assertRejected(withoutAttempt);
        Metadata withoutLogicalId = attemptHeaders("1", "a".repeat(64));
        withoutLogicalId.discardAll(RpcMetadataKeys.LOGICAL_CALL_ID);
        assertRejected(withoutLogicalId);
        for (String id : List.of(" ", "invalid id", "a".repeat(257))) {
            Metadata invalidId = attemptHeaders("1", "a".repeat(64));
            invalidId.discardAll(RpcMetadataKeys.LOGICAL_CALL_ID);
            invalidId.put(RpcMetadataKeys.LOGICAL_CALL_ID, id);
            assertRejected(invalidId);
        }
    }

    @Test
    void rejectsDuplicateReservedAndAuthorizationHeadersBeforeDispatch() {
        for (Metadata.Key<String> key : List.of(RpcMetadataKeys.AUTHORIZATION, RpcMetadataKeys.TRACEPARENT,
                RpcMetadataKeys.TRACESTATE, RpcMetadataKeys.REQUEST_ID, RpcMetadataKeys.SERVICE,
                RpcMetadataKeys.GROUP, RpcMetadataKeys.VERSION, RpcMetadataKeys.LOGICAL_CALL_ID,
                RpcMetadataKeys.ATTEMPT, RpcMetadataKeys.REQUEST_DIGEST,
                RpcMetadataKeys.SOURCE_APP, RpcMetadataKeys.SOURCE_INSTANCE,
                RpcMetadataKeys.FAILURE_STAGE, RpcMetadataKeys.ERROR_TYPE)) {
            Metadata headers = new Metadata();
            headers.put(key, "first");
            headers.put(key, "second");
            assertRejected(headers);
        }
    }

    @Test
    void rejectsApplicationProxyControlsAndMalformedIdentityFields() {
        for (String name : List.of("x-envoy-retry-on", "x-envoy-retry-grpc-on", "x-envoy-max-retries",
                "x-envoy-retriable-status-codes", "x-envoy-hedge-on-per-try-timeout",
                "x-envoy-upstream-rq-timeout-ms", "x-envoy-upstream-rq-per-try-timeout-ms",
                "x-envoy-upstream-stream-duration-ms")) {
            Metadata headers = attemptHeaders("1", "a".repeat(64));
            headers.put(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER), "1");
            assertRejected(headers);
        }
        Metadata malformedSource = attemptHeaders("1", "a".repeat(64));
        malformedSource.put(RpcMetadataKeys.SOURCE_APP, "a".repeat(257));
        assertRejected(malformedSource);
    }

    @Test
    void restoresMdcForEveryServerListenerCallback() {
        Metadata headers = new Metadata();
        headers.put(
                RpcMetadataKeys.TRACEPARENT,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        );
        headers.put(RpcMetadataKeys.REQUEST_ID, "request-1");
        headers.put(RpcMetadataKeys.INVOCATION_ID, "invoke-1");
        StringBuilder callbacks = new StringBuilder();
        ServerCallHandler<String, String> next = (call, metadata) ->
                new ServerCall.Listener<>() {
                    @Override
                    public void onMessage(String message) {
                        assertTrace(callbacks, "message");
                    }

                    @Override
                    public void onHalfClose() {
                        assertTrace(callbacks, "halfClose");
                    }

                    @Override
                    public void onReady() {
                        assertTrace(callbacks, "ready");
                    }

                    @Override
                    public void onComplete() {
                        assertTrace(callbacks, "complete");
                    }
                };

        ServerCall.Listener<String> listener =
                new RpcProviderServerInterceptor().interceptCall(
                        new NoOpServerCall(),
                        headers,
                        next
                );

        MDC.put(TraceContext.TRACE_ID, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        listener.onMessage("request");
        listener.onHalfClose();
        listener.onReady();
        listener.onComplete();

        assertThat(callbacks.toString())
                .isEqualTo("message;halfClose;ready;complete;");
        assertThat(MDC.get(TraceContext.TRACE_ID))
                .isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }

    @Test
    void discardsTheExpectedTimeoutHintThatEnvoyMayGenerate() {
        Metadata headers = attemptHeaders("1", "a".repeat(64));
        headers.put(RpcMetadataKeys.PROXY_EXPECTED_REQUEST_TIMEOUT, "600");
        AtomicInteger dispatches = new AtomicInteger();
        NoOpServerCall call = new NoOpServerCall();

        new RpcProviderServerInterceptor().interceptCall(call, headers, (serverCall, metadata) -> {
            assertThat(metadata.containsKey(RpcMetadataKeys.PROXY_EXPECTED_REQUEST_TIMEOUT)).isFalse();
            dispatches.incrementAndGet();
            return new ServerCall.Listener<>() { };
        });

        assertThat(call.closedStatus).isNull();
        assertThat(dispatches).hasValue(1);
    }

    private void assertTrace(StringBuilder callbacks, String callback) {
        callbacks.append(callback).append(';');
        assertThat(MDC.get(TraceContext.TRACE_ID))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(MDC.get(TraceContext.PARENT_SPAN_ID))
                .isEqualTo("00f067aa0ba902b7");
        assertThat(MDC.get(TraceContext.REQUEST_ID)).isEqualTo("request-1");
        assertThat(RpcInvocationMetadata.current().invocationId())
                .isEqualTo("invoke-1");
    }

    private Metadata attemptHeaders(String attempt, String digest) {
        Metadata headers = new Metadata();
        headers.put(RpcMetadataKeys.SERVICE, "egon.rpc.test.Echo");
        headers.put(RpcMetadataKeys.GROUP, "default");
        headers.put(RpcMetadataKeys.VERSION, "1.0.0");
        headers.put(RpcMetadataKeys.LOGICAL_CALL_ID, "invoke-1");
        headers.put(RpcMetadataKeys.ATTEMPT, attempt);
        headers.put(RpcMetadataKeys.REQUEST_DIGEST, digest);
        return headers;
    }

    private void assertRejected(Metadata headers) {
        AtomicInteger dispatches = new AtomicInteger();
        NoOpServerCall call = new NoOpServerCall();
        new RpcProviderServerInterceptor().interceptCall(call, headers, (serverCall, metadata) -> {
            dispatches.incrementAndGet();
            return new ServerCall.Listener<>() { };
        });
        assertThat(call.closedStatus).isNotNull();
        assertThat(call.closedStatus.getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(dispatches).hasValue(0);
        assertThat(RpcInvocationMetadata.current()).isNull();
    }

    private static final class NoOpServerCall
            extends ServerCall<String, String> {

        private Status closedStatus;

        @Override
        public void request(int numMessages) {
        }

        @Override
        public void sendHeaders(Metadata headers) {
        }

        @Override
        public void sendMessage(String message) {
        }

        @Override
        public void close(Status status, Metadata trailers) {
            closedStatus = status;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public MethodDescriptor<String, String> getMethodDescriptor() {
            return null;
        }

        @Override
        public Attributes getAttributes() {
            return Attributes.EMPTY;
        }
    }
}
