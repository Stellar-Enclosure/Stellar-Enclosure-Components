package top.egon.cola.component.rpc.provider.server;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Immutable identity supplied only after authentication and resource/method authorization. */
public record RpcAdmissionIdentityVO(
        @Positive long tenantId,
        @NotBlank @Size(max = 256) @Pattern(regexp = "[\\x20-\\x7e]+") String clientId,
        @NotBlank @Size(max = 256) @Pattern(regexp = "[\\x20-\\x7e]+") String resource
) {

    public RpcAdmissionIdentityVO {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        requireIdentity(clientId, "clientId");
        requireIdentity(resource, "resource");
    }

    private static void requireIdentity(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 256
                || !value.chars().allMatch(character -> character >= 0x20 && character <= 0x7e)) {
            throw new IllegalArgumentException(field + " must be bounded nonblank printable ASCII");
        }
    }

    @Override
    public String toString() {
        return "RpcAdmissionIdentityVO[identity=<redacted>]";
    }
}
