package top.egon.cola.component.outbox.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxShardContextTest {

    @Test
    void nestedScopesAndFailuresRestoreThePriorShardWithoutLeakingIt() {
        assertThat(OutboxShardContext.isActive()).isFalse();
        OutboxShardContext.withSlot(7, () -> {
            assertThat(OutboxShardContext.currentSlot()).isEqualTo(7);
            assertThatThrownBy(() -> OutboxShardContext.withSlot(9, () -> {
                assertThat(OutboxShardContext.currentSlot()).isEqualTo(9);
                throw new IllegalStateException("injected");
            })).hasMessage("injected");
            assertThat(OutboxShardContext.currentSlot()).isEqualTo(7);
            return null;
        });
        assertThat(OutboxShardContext.isActive()).isFalse();
        assertThat(OutboxShardContext.currentSlot()).isZero();
    }
}
