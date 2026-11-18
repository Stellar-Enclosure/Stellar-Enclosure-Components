package top.egon.cola.component.rpc.consumer.interceptor;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.common.trace.TraceContext;
import top.egon.cola.component.rpc.context.identity.RpcProcessIdentity;
import top.egon.cola.component.rpc.context.invocation.RpcInvocationMetadata;
import top.egon.cola.component.rpc.context.invocation.RpcMetadataKeys;
import top.egon.cola.component.rpc.consumer.generic.RpcGenericInvocation;
import top.egon.cola.component.rpc.contract.descriptor.RpcContractDescriptor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RpcConsumerClientInterceptorTest {

    @BeforeAll
    static void bindTheProcessWideEngine() {
        SnowflakeIdGenerator.initialize(0L, Duration.ofMillis(5));
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void createsTraceMetadataWhenClientCallStarts() {
        TraceContext state = TraceContext.root("request-1");
        AtomicReference<Metadata> observed = new AtomicReference<>();
        RpcConsumerClientInterceptor interceptor =
                new RpcConsumerClientInterceptor(
                        contract(),
                        identity());
        ClientCall<String, String> call = interceptor.interceptCall(
                method(),
                CallOptions.DEFAULT,
                new CapturingChannel(observed)
        );

        try (TraceContext.Scope ignored = state.open()) {
            call.start(new ClientCall.Listener<>() {
            }, new Metadata());
        }

        Metadata headers = observed.get();
        assertThat(headers.get(RpcMetadataKeys.TRACEPARENT))
                .startsWith("00-" + state.traceId() + "-");
        assertThat(headers.get(RpcMetadataKeys.REQUEST_ID))
                .isEqualTo("request-1");
        assertThat(headers.keys()).doesNotContain("x-egon-trace-id");
        assertThat(headers.get(RpcMetadataKeys.INVOCATION_ID)).isNotBlank();
        assertThat(headers.get(RpcMetadataKeys.SOURCE_APP)).isEqualTo("rpc-app");
        assertThat(headers.get(RpcMetadataKeys.SOURCE_INSTANCE)).isEqualTo("rpc-1");
    }

    @Test
    void genericTargetUsesOneLogicalInvocationIdAcrossAttempts() {
        String digest = RpcInvocationMetadata.requestDigest(new byte[]{1, 2, 3});
        AtomicReference<Metadata> firstHeaders = new AtomicReference<>();
        AtomicReference<Metadata> secondHeaders = new AtomicReference<>();
        ClientCall<String, String> first = attempt(1, digest).interceptCall(
                method(),
                CallOptions.DEFAULT,
                new CapturingChannel(firstHeaders)
        );
        ClientCall<String, String> second = attempt(2, digest).interceptCall(
                method(),
                CallOptions.DEFAULT,
                new CapturingChannel(secondHeaders)
        );

        first.start(new ClientCall.Listener<>() {
        }, new Metadata());
        second.start(new ClientCall.Listener<>() {
        }, new Metadata());

        assertThat(firstHeaders.get().get(RpcMetadataKeys.INVOCATION_ID))
                .isEqualTo("invocation-1");
        assertThat(secondHeaders.get().get(RpcMetadataKeys.INVOCATION_ID))
                .isEqualTo("invocation-1");
        assertThat(firstHeaders.get().get(RpcMetadataKeys.SERVICE))
                .isEqualTo("egon.rpc.test.Echo");
        assertThat(RpcMetadataKeys.LOGICAL_CALL_ID).isSameAs(RpcMetadataKeys.INVOCATION_ID);
        assertThat(firstHeaders.get().getAll(RpcMetadataKeys.LOGICAL_CALL_ID)).containsExactly("invocation-1");
        assertThat(secondHeaders.get().getAll(RpcMetadataKeys.LOGICAL_CALL_ID)).containsExactly("invocation-1");
        assertThat(firstHeaders.get().get(RpcMetadataKeys.ATTEMPT)).isEqualTo("1");
        assertThat(secondHeaders.get().get(RpcMetadataKeys.ATTEMPT)).isEqualTo("2");
        assertThat(firstHeaders.get().get(RpcMetadataKeys.REQUEST_DIGEST)).isEqualTo(digest);
        assertThat(secondHeaders.get().get(RpcMetadataKeys.REQUEST_DIGEST)).isEqualTo(digest);
    }

    @Test
    void rejectsInvalidAttemptsAndNonCanonicalDigestsBeforeCreatingTheCall() {
        for (int attempt : List.of(0, -1, 3, Integer.MAX_VALUE)) {
            assertThatThrownBy(() -> attempt(attempt, "a".repeat(64)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String digest : List.of("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64))) {
            assertThatThrownBy(() -> attempt(1, digest)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> attempt(1, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateReservedHeadersAndCallerSuppliedFrameworkIdentity() {
        for (Metadata.Key<String> key : List.of(RpcMetadataKeys.AUTHORIZATION, RpcMetadataKeys.TRACEPARENT,
                RpcMetadataKeys.TRACESTATE, RpcMetadataKeys.REQUEST_ID, RpcMetadataKeys.SERVICE,
                RpcMetadataKeys.GROUP, RpcMetadataKeys.VERSION, RpcMetadataKeys.LOGICAL_CALL_ID,
                RpcMetadataKeys.ATTEMPT, RpcMetadataKeys.REQUEST_DIGEST,
                RpcMetadataKeys.SOURCE_APP, RpcMetadataKeys.SOURCE_INSTANCE,
                RpcMetadataKeys.FAILURE_STAGE, RpcMetadataKeys.ERROR_TYPE)) {
            Metadata headers = new Metadata();
            headers.put(key, "first");
            headers.put(key, "second");
            assertRejectedBeforeStart(headers);
        }
        for (Metadata.Key<String> key : List.of(RpcMetadataKeys.SERVICE, RpcMetadataKeys.GROUP,
                RpcMetadataKeys.VERSION, RpcMetadataKeys.LOGICAL_CALL_ID, RpcMetadataKeys.ATTEMPT,
                RpcMetadataKeys.REQUEST_DIGEST, RpcMetadataKeys.SOURCE_APP, RpcMetadataKeys.SOURCE_INSTANCE)) {
            Metadata headers = new Metadata();
            headers.put(key, "caller-value");
            assertRejectedBeforeStart(headers);
        }
    }

    @Test
    void rejectsApplicationEnvoyRetryHedgingAndTimeoutControls() {
        for (String name : List.of("x-envoy-retry-on", "x-envoy-retry-grpc-on", "x-envoy-max-retries",
                "x-envoy-retriable-status-codes", "x-envoy-retriable-header-names",
                "x-envoy-hedge-on-per-try-timeout", "x-envoy-upstream-rq-timeout-ms",
                "x-envoy-upstream-rq-per-try-timeout-ms", "x-envoy-expected-rq-timeout-ms",
                "x-envoy-upstream-stream-duration-ms")) {
            Metadata headers = new Metadata();
            headers.put(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER), "1");
            assertRejectedBeforeStart(headers);
        }
    }

    @Test
    void preservesSingleAuthorizationAndValidTraceMetadata() {
        AtomicReference<Metadata> observed = new AtomicReference<>();
        Metadata headers = new Metadata();
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        headers.put(RpcMetadataKeys.AUTHORIZATION, "Bearer test-only-token");
        headers.put(RpcMetadataKeys.TRACEPARENT, traceparent);
        headers.put(RpcMetadataKeys.TRACESTATE, "vendor=member");
        headers.put(RpcMetadataKeys.REQUEST_ID, "caller-request");
        Metadata.Key<String> custom = Metadata.Key.of("x-client-label", Metadata.ASCII_STRING_MARSHALLER);
        headers.put(custom, "approved-label");
        attempt(1, "a".repeat(64)).interceptCall(method(), CallOptions.DEFAULT, new CapturingChannel(observed))
                .start(new ClientCall.Listener<>() { }, headers);
        assertThat(observed.get().getAll(RpcMetadataKeys.AUTHORIZATION)).containsExactly("Bearer test-only-token");
        assertThat(observed.get().getAll(RpcMetadataKeys.TRACEPARENT)).containsExactly(traceparent);
        assertThat(observed.get().getAll(RpcMetadataKeys.TRACESTATE)).containsExactly("vendor=member");
        assertThat(observed.get().getAll(RpcMetadataKeys.REQUEST_ID)).containsExactly("caller-request");
        assertThat(observed.get().get(custom)).isEqualTo("approved-label");
    }

    @Test
    void replacingAnInvalidTraceDoesNotCreateDuplicateTraceOrRequestHeaders() {
        AtomicReference<Metadata> observed = new AtomicReference<>();
        Metadata headers = new Metadata();
        headers.put(RpcMetadataKeys.TRACEPARENT, "invalid");
        headers.put(RpcMetadataKeys.TRACESTATE, "old=trace");
        headers.put(RpcMetadataKeys.REQUEST_ID, "caller-request");
        attempt(1, "a".repeat(64)).interceptCall(method(), CallOptions.DEFAULT, new CapturingChannel(observed))
                .start(new ClientCall.Listener<>() { }, headers);
        assertThat(observed.get().getAll(RpcMetadataKeys.TRACEPARENT)).hasSize(1);
        assertThat(TraceContext.isValidTraceparent(observed.get().get(RpcMetadataKeys.TRACEPARENT))).isTrue();
        assertThat(observed.get().getAll(RpcMetadataKeys.REQUEST_ID)).containsExactly("caller-request");
    }

    @Test
    void commitsEachAttemptOnItsFirstActualHeadersOrMessageBeforeCallingTheListener() {
        AtomicInteger commits = new AtomicInteger();
        RpcConsumerClientInterceptor interceptor = RpcConsumerClientInterceptor.forTarget(
                "egon.rpc.test.Echo", "default", "1.0.0", identity(), "invocation-1", 1,
                "a".repeat(64), commits::incrementAndGet);
        CapturingChannel first = new CapturingChannel(new AtomicReference<>());
        CapturingChannel second = new CapturingChannel(new AtomicReference<>());
        interceptor.interceptCall(method(), CallOptions.DEFAULT, first).start(new ClientCall.Listener<>() {
            @Override public void onHeaders(Metadata headers) { assertThat(commits).hasValue(1); }
            @Override public void onMessage(String message) { assertThat(commits).hasValue(1); }
        }, new Metadata());
        interceptor.interceptCall(method(), CallOptions.DEFAULT, second).start(new ClientCall.Listener<>() {
            @Override public void onHeaders(Metadata headers) { assertThat(commits).hasValue(2); }
            @Override public void onMessage(String message) { assertThat(commits).hasValue(2); }
        }, new Metadata());
        assertThat(commits).hasValue(0);
        first.responseListener.onHeaders(new Metadata());
        first.responseListener.onMessage("first response");
        second.responseListener.onMessage("second response");
        second.responseListener.onHeaders(new Metadata());
        assertThat(commits).hasValue(2);
    }

    @Test
    void requestDigestsAreLowercaseSha256AndProtobufMapsAreCanonical() {
        assertThat(RpcInvocationMetadata.requestDigest(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        Value one = Value.newBuilder().setStringValue("one").build();
        Value two = Value.newBuilder().setStringValue("two").build();
        Struct first = Struct.newBuilder().putFields("a", one).putFields("b", two).build();
        Struct reordered = Struct.newBuilder().putFields("b", two).putFields("a", one).build();
        String digest = RpcInvocationMetadata.canonicalRequestDigest(first);
        assertThat(digest).matches("[0-9a-f]{64}")
                .isEqualTo(RpcInvocationMetadata.canonicalRequestDigest(reordered));
        assertThat(RpcInvocationMetadata.canonicalRequestDigest(
                first.toBuilder().putFields("a", two).build())).isNotEqualTo(digest);
    }

    @Test
    void genericInvocationContextIsRawAndDefensive() {
        byte[] payload = new byte[]{1, 2, 3};
        RpcGenericInvocation command = new RpcGenericInvocation(
                "echo-binding",
                "egon.rpc.test.Echo",
                "default",
                "1.0.0",
                "egon.rpc.test.Echo/Echo",
                payload,
                1000
        );
        RpcClientInvocation invocation = RpcClientInvocation.generic(
                command,
                identity(),
                "invocation-2",
                new top.egon.cola.component.rpc.consumer.reference.RpcServiceTargetVO(
                        "echo-binding", 1, "apps-prod", "echo-rpc", 18122,
                        java.util.Set.of("egon.rpc.test.Echo/Echo"))
        );
        payload[0] = 9;

        assertThat(invocation.generic()).isTrue();
        assertThat(invocation.contract()).isNull();
        assertThat(invocation.method()).isNull();
        assertThat(invocation.request()).isNull();
        assertThat(invocation.rawRequestPayload())
                .containsExactly(1, 2, 3);
        assertThat(invocation.invocationId()).isEqualTo("invocation-2");
    }

    private RpcContractDescriptor contract() {
        return new RpcContractDescriptor(
                Object.class,
                "egon.rpc.test.Echo",
                "default",
                "1.0.0",
                List.of()
        );
    }

    private RpcConsumerClientInterceptor attempt(int attempt, String digest) {
        return RpcConsumerClientInterceptor.forTarget(
                "egon.rpc.test.Echo", "default", "1.0.0", identity(), "invocation-1", attempt, digest);
    }

    private void assertRejectedBeforeStart(Metadata headers) {
        AtomicReference<Metadata> observed = new AtomicReference<>();
        ClientCall<String, String> call = attempt(1, "a".repeat(64))
                .interceptCall(method(), CallOptions.DEFAULT, new CapturingChannel(observed));
        assertThatThrownBy(() -> call.start(new ClientCall.Listener<>() { }, headers))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(observed).hasNullValue();
    }

    private RpcProcessIdentity identity() {
        return new RpcProcessIdentity(
                "rpc-app",
                "test",
                "default",
                "127.0.0.1",
                1L,
                "rpc-1"
        );
    }

    private MethodDescriptor<String, String> method() {
        return MethodDescriptor.<String, String>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("egon.rpc.test.Echo/Echo")
                .setRequestMarshaller(new StringMarshaller())
                .setResponseMarshaller(new StringMarshaller())
                .build();
    }

    private static final class CapturingChannel extends Channel {

        private final AtomicReference<Metadata> observed;

        private ClientCall.Listener<String> responseListener;

        private CapturingChannel(AtomicReference<Metadata> observed) {
            this.observed = observed;
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
                MethodDescriptor<ReqT, RespT> methodDescriptor,
                CallOptions callOptions) {
            return new ClientCall<>() {
                @Override
                @SuppressWarnings("unchecked")
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    observed.set(headers);
                    CapturingChannel.this.responseListener = (Listener<String>) responseListener;
                }

                @Override
                public void request(int numMessages) {
                }

                @Override
                public void cancel(String message, Throwable cause) {
                }

                @Override
                public void halfClose() {
                }

                @Override
                public void sendMessage(ReqT message) {
                }
            };
        }

        @Override
        public String authority() {
            return "test";
        }
    }

    private static final class StringMarshaller
            implements MethodDescriptor.Marshaller<String> {

        @Override
        public java.io.InputStream stream(String value) {
            return new java.io.ByteArrayInputStream(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public String parse(java.io.InputStream stream) {
            try {
                return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
