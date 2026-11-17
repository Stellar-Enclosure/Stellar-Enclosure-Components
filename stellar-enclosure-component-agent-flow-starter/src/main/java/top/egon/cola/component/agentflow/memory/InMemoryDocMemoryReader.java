package top.egon.cola.component.agentflow.memory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.memory.DocMemoryQuery;
import top.egon.cola.component.agentflow.api.memory.DocMemoryReader;
import top.egon.cola.component.agentflow.api.memory.DocMemoryVO;
import top.egon.cola.component.common.core.validation.ValidationUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory read-only SPI implementation for Level 3 Markdown document memory.
 */
@Slf4j
@Validated
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class InMemoryDocMemoryReader implements DocMemoryReader {

    @Qualifier("egonColaValidationUtils")
    private final ValidationUtils validationUtils;

    private final int maxBatchSize;

    private final ConcurrentHashMap<DocKey, StoredDoc> documents = new ConcurrentHashMap<>();

    /** Package/test fixture helper to seed documents for in-memory execution and tests. */
    public void put(String tenantKey, DocMemoryVO document, boolean deleted) {
        ContextScopeVO.requireIdentifier(tenantKey);
        Objects.requireNonNull(document, "document");
        documents.put(new DocKey(tenantKey, document.id()), new StoredDoc(document, deleted));
    }

    @Override
    public List<DocMemoryVO> findByIds(@Valid @NotNull DocMemoryQuery query) {
        Objects.requireNonNull(query, "query");
        DocMemoryQuery safe = query.snapshot(maxBatchSize);
        validationUtils.validate(safe);
        if (safe.getIds().isEmpty()) {
            return List.of();
        }
        List<DocMemoryVO> ordered = new ArrayList<>(safe.getIds().size());
        for (String id : safe.getIds()) {
            StoredDoc entry = documents.get(new DocKey(safe.getTenantKey(), id));
            if (entry != null && !entry.deleted()) {
                ordered.add(entry.document());
            }
        }
        return List.copyOf(ordered);
    }

    private record DocKey(String tenantKey, String id) {
    }

    private record StoredDoc(DocMemoryVO document, boolean deleted) {
    }
}
