package top.egon.cola.component.agentflow.api.context;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Exact, case-sensitive scope for one host-owned execution and session. */
public record ContextScopeVO(
        @NotBlank @Size(max = 128) String namespace,
        @NotBlank @Size(max = 128) String tenantKey,
        @NotBlank @Size(max = 128) String principalKey,
        @NotBlank @Size(max = 128) String instanceKey,
        @NotBlank @Size(max = 128) String sessionKey,
        @NotBlank @Size(max = 128) String purpose) {

    public ContextScopeVO {
        for (String part : new String[]{namespace, tenantKey, principalKey, instanceKey, sessionKey, purpose}) {
            requireIdentifier(part);
        }
    }

    public String scopeHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{"EGON_AGENT_CONTEXT_SCOPE_V1", namespace, tenantKey, principalKey,
                    instanceKey, sessionKey, purpose}) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    public static void requireIdentifier(String part) {
        if (part == null || part.isBlank() || part.length() > 128
                || part.codePoints().anyMatch(Character::isISOControl)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(part)) {
            throw new IllegalArgumentException("Invalid Agent Flow identifier");
        }
    }
}
