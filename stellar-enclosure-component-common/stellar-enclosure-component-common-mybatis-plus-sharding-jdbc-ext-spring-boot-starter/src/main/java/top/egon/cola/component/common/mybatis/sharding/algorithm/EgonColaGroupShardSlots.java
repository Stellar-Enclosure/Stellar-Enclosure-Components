package top.egon.cola.component.common.mybatis.sharding.algorithm;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/** Stable per-group slots; zero remains reserved for the original single-source group. */
public final class EgonColaGroupShardSlots {

    private EgonColaGroupShardSlots() {
    }

    public static Map<Integer, String> resolve(Collection<String> groups, String legacyGroup) {
        if (groups == null || groups.isEmpty() || legacyGroup == null || !groups.contains(legacyGroup)) {
            throw new IllegalArgumentException("TECHNICAL_SHARD_GROUP_INVALID");
        }
        Map<Integer, String> result = new TreeMap<>();
        result.put(0, legacyGroup);
        for (String group : groups) {
            if (group == null || group.isBlank()) {
                throw new IllegalArgumentException("TECHNICAL_SHARD_GROUP_INVALID");
            }
            if (group.equals(legacyGroup)) {
                continue;
            }
            int slot = slot(group);
            if (slot == 0 || result.putIfAbsent(slot, group) != null) {
                throw new IllegalArgumentException("TECHNICAL_SHARD_SLOT_COLLISION");
            }
        }
        if (result.size() != groups.size()) {
            throw new IllegalArgumentException("TECHNICAL_SHARD_GROUP_INVALID");
        }
        return Map.copyOf(result);
    }

    public static String encode(Map<Integer, String> slots) {
        return new TreeMap<>(slots).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
    }

    public static Map<Integer, String> decode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("TECHNICAL_SHARD_GROUP_INVALID");
        }
        Map<Integer, String> result = new TreeMap<>();
        for (String item : value.split(",", -1)) {
            String[] pair = item.split("=", -1);
            if (pair.length != 2 || !pair[0].matches("0|[1-9][0-9]*")
                    || !pair[1].matches("[a-zA-Z_][a-zA-Z0-9_-]*")) {
                throw new IllegalArgumentException("TECHNICAL_SHARD_GROUP_INVALID");
            }
            int number = Integer.parseInt(pair[0]);
            if (result.putIfAbsent(number, pair[1]) != null || java.util.Collections.frequency(result.values(), pair[1]) > 1) {
                throw new IllegalArgumentException("TECHNICAL_SHARD_GROUP_INVALID");
            }
        }
        if (!result.containsKey(0)) {
            throw new IllegalArgumentException("TECHNICAL_SHARD_GROUP_INVALID");
        }
        return Map.copyOf(result);
    }

    private static int slot(String group) {
        try {
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                    .digest(group.getBytes(StandardCharsets.UTF_8))).getInt() & Integer.MAX_VALUE;
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA256_REQUIRED", failure);
        }
    }
}
