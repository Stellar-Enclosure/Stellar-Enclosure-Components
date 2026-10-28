package top.egon.cola.component.outbox.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantScopedOutboxKeyTest {
    @Test
    void keys_are_stable_scoped_and_unambiguous() {
        String key = TenantScopedOutboxKey.of(42, "start", "order-123");
        assertEquals(key, TenantScopedOutboxKey.of(42, "start", "order-123"));
        assertNotEquals(key, TenantScopedOutboxKey.of(43, "start", "order-123"));
        assertNotEquals(key, TenantScopedOutboxKey.of(42, "cancel", "order-123"));
        assertNotEquals(TenantScopedOutboxKey.of(42, "ab", "c"), TenantScopedOutboxKey.of(42, "a", "bc"));
        assertTrue(key.length() <= 256);
    }

    @Test
    void invalid_or_ambiguous_inputs_are_rejected() {
        assertThrows(IllegalArgumentException.class, () -> TenantScopedOutboxKey.of(0, "start", "id"));
        assertThrows(IllegalArgumentException.class, () -> TenantScopedOutboxKey.of(1, "", "id"));
        assertThrows(IllegalArgumentException.class, () -> TenantScopedOutboxKey.of(1, "start", " id"));
        assertThrows(IllegalArgumentException.class, () -> TenantScopedOutboxKey.of(1, "start", "x".repeat(257)));
    }
}
