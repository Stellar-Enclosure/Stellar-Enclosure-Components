package top.egon.cola.component.agentflow.api.memory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Read-only SPI for Level 3 Markdown document memory managed by downstream host applications.
 */
@FunctionalInterface
public interface DocMemoryReader {

    /**
     * Fetches non-deleted Markdown documents matching {@code query.tenantKey()} and {@code query.ids()}
     * in the exact priority order requested by the caller.
     */
    List<DocMemoryVO> findByIds(@Valid @NotNull DocMemoryQuery query);
}
