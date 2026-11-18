package top.egon.cola.component.rpc.consumer.reference;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import top.egon.cola.component.rpc.config.RpcTransportSecurity;

import java.util.Set;

/** Immutable deployment-approved Service target; it does not establish business authorization. */
public record RpcServiceTargetVO(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.-]{0,127}") String bindingId,
        @Positive long version,
        @NotBlank @Pattern(regexp = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?") String namespace,
        @NotBlank @Pattern(regexp = "[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?") String service,
        @Min(1) @Max(65535) int port,
        @NotEmpty Set<@NotBlank @Pattern(regexp = "(?:[A-Za-z_][A-Za-z0-9_]*[.])*[A-Za-z_][A-Za-z0-9_]*/[A-Za-z_][A-Za-z0-9_]*") String> methods
) {
    private static final java.util.regex.Pattern BINDING = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    private static final java.util.regex.Pattern NAMESPACE = java.util.regex.Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final java.util.regex.Pattern SERVICE = java.util.regex.Pattern.compile("[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final java.util.regex.Pattern NATIVE_METHOD = java.util.regex.Pattern.compile(
            "(?:[A-Za-z_][A-Za-z0-9_]*[.])*[A-Za-z_][A-Za-z0-9_]*/[A-Za-z_][A-Za-z0-9_]*");

    public RpcServiceTargetVO {
        if (!isBindingId(bindingId)) {
            throw new IllegalArgumentException("RPC binding ID is invalid");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("RPC binding version must be positive");
        }
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("RPC namespace label is invalid");
        }
        if (service == null || !SERVICE.matcher(service).matches() || service.equals("stable") || service.equals("canary")
                || service.endsWith("-stable") || service.endsWith("-canary")) {
            throw new IllegalArgumentException("RPC target must name a stable Service front door");
        }
        if (port < 1 || port > 65535 || port == RpcTransportSecurity.GAMMA_FALLBACK_PORT) {
            throw new IllegalArgumentException("RPC Service port is invalid");
        }
        if (methods == null || methods.isEmpty() || methods.stream().anyMatch(method -> !isNativeMethodName(method))) {
            throw new IllegalArgumentException("RPC binding requires exact native method names");
        }
        if (methods.stream().map(method -> method.substring(0, method.indexOf('/'))).distinct().count() != 1) {
            throw new IllegalArgumentException("RPC binding methods must belong to one native service");
        }
        methods = Set.copyOf(methods);
    }

    public String authority() {
        return service + "." + namespace + ".svc.cluster.local:" + port;
    }

    public String dnsTarget() {
        return "dns:///" + authority();
    }

    public static boolean isBindingId(String value) {
        return value != null && BINDING.matcher(value).matches();
    }

    public static boolean isNativeMethodName(String value) {
        return value != null && NATIVE_METHOD.matcher(value).matches();
    }
}
