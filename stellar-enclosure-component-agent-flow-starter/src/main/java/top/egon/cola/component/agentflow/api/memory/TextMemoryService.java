package top.egon.cola.component.agentflow.api.memory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Optional;

/** Neutral business contract for Level 2 mid-term text memory (10,000-char strict ceiling). */
public interface TextMemoryService {

    /**
     * Idempotently binds or creates a Level 2 text memory slot and returns its snapshot containing the downstream {@code id}.
     */
    TextMemoryVO bindOrCreate(@Valid @NotNull TextMemoryBindCommand command);

    /**
     * Reads a Level 2 text memory entry isolated by {@code tenantKey}.
     */
    Optional<TextMemoryVO> getById(@NotBlank @Size(max = 128) String tenantKey,
                                   @NotBlank @Size(max = 64) String id);

    /**
     * Updates a Level 2 text memory entry with optimistic locking; strictly rejects content over 10,000 characters.
     */
    TextMemoryVO save(@Valid @NotNull TextMemorySaveCommand command);
}
