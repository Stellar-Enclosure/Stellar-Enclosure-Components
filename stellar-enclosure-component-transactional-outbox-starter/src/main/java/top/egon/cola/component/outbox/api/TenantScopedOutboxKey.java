package top.egon.cola.component.outbox.api;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Builds a stable technical idempotency key from a tenant identity established by the host. */
public final class TenantScopedOutboxKey {
    public static String of(long tenantId, String operation, String commandId) {
        if (tenantId <= 0 || invalid(operation, 128) || invalid(commandId, 256)) {
            throw new IllegalArgumentException("OUTBOX_TENANT_KEY_INVALID");
        }
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            add(hash, operation);
            add(hash, commandId);
            return "t:" + tenantId + ":" + HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static boolean invalid(String value, int maximum) {
        return value == null || value.isBlank() || value.length() > maximum || !value.equals(value.trim());
    }

    private static void add(MessageDigest hash, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        hash.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        hash.update(bytes);
    }

    private TenantScopedOutboxKey() { }
}
