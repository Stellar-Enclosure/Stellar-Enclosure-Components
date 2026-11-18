package top.egon.cola.component.rpc.context.invocation;

import io.grpc.Metadata;

import java.util.List;
import java.util.Objects;

public final class RpcMetadataKeys {

    public static final Metadata.Key<String> SERVICE =
            ascii("x-egon-rpc-service");

    public static final Metadata.Key<String> GROUP =
            ascii("x-egon-rpc-group");

    public static final Metadata.Key<String> VERSION =
            ascii("x-egon-rpc-version");

    public static final Metadata.Key<String> INVOCATION_ID =
            ascii("x-egon-rpc-invocation-id");

    /** Alias to the original wire key; callers must write only one value. */
    public static final Metadata.Key<String> LOGICAL_CALL_ID = INVOCATION_ID;

    public static final Metadata.Key<String> ATTEMPT =
            ascii("x-egon-rpc-attempt");

    public static final Metadata.Key<String> REQUEST_DIGEST =
            ascii("x-egon-rpc-request-digest");

    /** Envoy may add this upstream hint after processing the route. */
    public static final Metadata.Key<String> PROXY_EXPECTED_REQUEST_TIMEOUT =
            ascii("x-envoy-expected-rq-timeout-ms");

    public static final Metadata.Key<String> SOURCE_APP =
            ascii("x-egon-rpc-source-app");

    public static final Metadata.Key<String> SOURCE_INSTANCE =
            ascii("x-egon-rpc-source-instance");

    public static final Metadata.Key<String> REQUEST_ID =
            ascii("x-egon-request-id");

    public static final Metadata.Key<String> AUTHORIZATION =
            ascii("authorization");

    public static final Metadata.Key<String> TRACEPARENT =
            ascii("traceparent");

    public static final Metadata.Key<String> TRACESTATE =
            ascii("tracestate");

    public static final Metadata.Key<String> FAILURE_STAGE =
            ascii("x-egon-rpc-failure-stage");

    public static final Metadata.Key<String> ERROR_TYPE =
            ascii("x-egon-rpc-error-type");

    private static final List<Metadata.Key<String>> RESERVED_REQUEST_HEADERS = List.of(
            SERVICE, GROUP, VERSION, INVOCATION_ID, ATTEMPT, REQUEST_DIGEST,
            SOURCE_APP, SOURCE_INSTANCE, REQUEST_ID, AUTHORIZATION, TRACEPARENT,
            TRACESTATE, FAILURE_STAGE, ERROR_TYPE);

    /** Reject ambiguous reserved values and application attempts to control proxy retries or time budgets. */
    public static void validateRequestHeaders(Metadata headers) {
        Objects.requireNonNull(headers, "headers");
        for (Metadata.Key<String> key : RESERVED_REQUEST_HEADERS) {
            Iterable<String> values = headers.getAll(key);
            if (values != null) {
                var iterator = values.iterator();
                if (iterator.hasNext()) {
                    iterator.next();
                    if (iterator.hasNext()) {
                        throw new IllegalArgumentException("Duplicate reserved RPC request metadata: " + key.name());
                    }
                }
            }
        }
        for (String name : headers.keys()) {
            if (name.startsWith("x-envoy-")
                    && (name.contains("retry") || name.contains("retri")
                    || name.contains("hedge") || name.contains("timeout")
                    || name.equals("x-envoy-upstream-stream-duration-ms"))) {
                throw new IllegalArgumentException("Application proxy control metadata is not permitted");
            }
        }
    }

    private RpcMetadataKeys() {
    }

    private static Metadata.Key<String> ascii(String name) {
        return Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER);
    }
}
