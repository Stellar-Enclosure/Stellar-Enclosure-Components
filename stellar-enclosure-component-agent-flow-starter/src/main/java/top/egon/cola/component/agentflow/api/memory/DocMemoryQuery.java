package top.egon.cola.component.agentflow.api.memory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Batch lookup query for Level 3 host-managed Markdown document memory, preserving caller id priority. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
public class DocMemoryQuery {

    public static final int MAX_BATCH_IDS = 32;

    @NotBlank
    @Size(max = 128)
    private String tenantKey;

    @NotNull
    @Size(max = MAX_BATCH_IDS)
    private List<@NotBlank @Size(max = 128) String> ids;

    /** Validates and deduplicates ids while preserving caller priority order. */
    public DocMemoryQuery snapshot() {
        return snapshot(MAX_BATCH_IDS);
    }

    /** Validates and deduplicates ids against configured batch limit while preserving caller order. */
    public DocMemoryQuery snapshot(int configuredMaxBatch) {
        ContextScopeVO.requireIdentifier(tenantKey);
        if (ids == null) {
            throw new IllegalArgumentException("DocMemoryQuery ids must not be null");
        }
        int effectiveMax = Math.min(Math.max(1, configuredMaxBatch), MAX_BATCH_IDS);
        if (ids.size() > effectiveMax) {
            throw new CommonException(ResultCode.SYSTEM_ERROR.getCode(),
                    "DOC_MEMORY_BATCH_LIMIT", "DOC_MEMORY_BATCH_LIMIT");
        }
        LinkedHashSet<String> deduplicated = new LinkedHashSet<>();
        for (String id : ids) {
            ContextScopeVO.requireIdentifier(id);
            deduplicated.add(id);
        }
        return new DocMemoryQuery(tenantKey, List.copyOf(new ArrayList<>(deduplicated)));
    }
}
