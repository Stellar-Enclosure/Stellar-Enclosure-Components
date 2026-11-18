package top.egon.cola.component.accessguard.store.redisson;

import top.egon.cola.component.accessguard.core.plan.AdmissionConfig;
import top.egon.cola.component.accessguard.store.LogicalQuotaAcquireCommand;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

public final class AccessGuardRedisKeyFactory {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private final String prefix;
    private final String application;

    public AccessGuardRedisKeyFactory(String prefix, String application) {
        this.prefix = requirePrefix(prefix);
        this.application = requireSegment(application, "application");
    }

    public String allowList(String ruleId, String dataVersion) {
        return list(ruleId, "allow-list", dataVersion);
    }

    public String denyList(String ruleId, String dataVersion) {
        return list(ruleId, "deny-list", dataVersion);
    }

    public String penalty(String ruleId, String stateVersion, String keyHash) {
        return state(ruleId, "penalty-box", stateVersion, keyHash);
    }

    public String rateLimit(String ruleId, String stateVersion, String keyHash) {
        return state(ruleId, "rate-limit", stateVersion, keyHash);
    }

    public String rateLimit(
            String ruleId,
            String stateVersion,
            String keyHash,
            AdmissionConfig.RateLimitAlgorithm algorithm) {
        AdmissionConfig.RateLimitAlgorithm selected = Objects.requireNonNull(
                algorithm, "algorithm");
        String base = rateLimit(ruleId, stateVersion, keyHash);
        return switch (selected) {
            case TOKEN_BUCKET -> base;
            case LEAKY_BUCKET -> base + ":leaky-bucket";
            case SLIDING_WINDOW -> base + ":sliding-window";
        };
    }

    /** The quota state and all logical decisions share one Redis Cluster slot, including across application versions. */
    public String logicalQuotaState(LogicalQuotaAcquireCommand command) {
        return logicalQuotaScope(command) + ":state";
    }

    /** A logical ID selects a decision inside the scope; physical attempt and claimed digest never reset the quota key. */
    public String logicalQuotaDecision(LogicalQuotaAcquireCommand command) {
        return logicalQuotaScope(command) + ":decision:" + digest(
                Objects.requireNonNull(command.logicalCallId(), "logicalCallId"));
    }

    private String logicalQuotaScope(LogicalQuotaAcquireCommand command) {
        Objects.requireNonNull(command, "command");
        return prefix + ':' + application + ":logical-quota:{" + digest(
                Long.toString(command.tenantId()),
                Objects.requireNonNull(command.clientId(), "clientId"),
                Objects.requireNonNull(command.resource(), "resource"),
                Objects.requireNonNull(command.quota(), "quota"),
                Objects.requireNonNull(command.fullMethodName(), "fullMethodName")) + '}';
    }

    /** Length-prefixed UTF-8 parts avoid ambiguous delimiters and keep authorization inputs out of Redis key text. */
    private static String digest(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private String list(String ruleId, String policy, String dataVersion) {
        return prefix + ':' + application + ':'
                + requireSegment(ruleId, "ruleId") + ':' + policy + ':'
                + requireSegment(dataVersion, "dataVersion");
    }

    private String state(String ruleId, String policy, String stateVersion, String keyHash) {
        String hash = Objects.requireNonNull(keyHash, "keyHash");
        if (!HASH.matcher(hash).matches()) {
            throw new IllegalArgumentException("keyHash must be a lowercase SHA-256 value");
        }
        return prefix + ':' + application + ':'
                + requireSegment(ruleId, "ruleId") + ':' + policy + ':'
                + requireSegment(stateVersion, "stateVersion") + ':' + hash;
    }

    private static String requirePrefix(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("keyPrefix must not be blank");
        }
        String normalized = value.trim();
        if (normalized.startsWith(":") || normalized.endsWith(":") || normalized.contains("::")) {
            throw new IllegalArgumentException("keyPrefix must contain non-empty segments");
        }
        for (String segment : normalized.split(":")) {
            requireSegment(segment, "keyPrefix");
        }
        return normalized;
    }

    private static String requireSegment(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String normalized = value.trim();
        if (!SEGMENT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " contains unsafe characters");
        }
        return normalized;
    }
}
