package top.egon.cola.component.outbox.persistence;

import java.util.function.Supplier;

/** Supplies the exact shard key to each synchronous mapper invocation. */
public final class OutboxShardContext {

    private static final ThreadLocal<Integer> SLOT = new ThreadLocal<>();

    private OutboxShardContext() {
    }

    public static int currentSlot() {
        Integer slot = SLOT.get();
        return slot == null ? 0 : slot;
    }

    public static boolean isActive() {
        return SLOT.get() != null;
    }

    public static <T> T withSlot(int slot, Supplier<T> action) {
        if (slot < 0 || action == null) {
            throw new IllegalArgumentException("OUTBOX_SHARD_SLOT_INVALID");
        }
        Integer previous = SLOT.get();
        SLOT.set(slot);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                SLOT.remove();
            } else {
                SLOT.set(previous);
            }
        }
    }
}
