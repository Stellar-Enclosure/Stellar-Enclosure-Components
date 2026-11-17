package top.egon.cola.component.agentflow.memory;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.agentflow.api.memory.DocMemoryQuery;
import top.egon.cola.component.agentflow.api.memory.DocMemoryVO;
import top.egon.cola.component.common.core.exception.CommonException;
import top.egon.cola.component.common.core.validation.ValidationUtils;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocMemoryReaderTest {

    private static final Instant NOW = Instant.parse("2026-11-17T08:45:00Z");

    private static InMemoryDocMemoryReader newReader() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        return new InMemoryDocMemoryReader(new ValidationUtils(validator), DocMemoryQuery.MAX_BATCH_IDS);
    }

    @Test
    void findByIdsPreservesCallerOrderAndFiltersTenantAndDeleted() {
        InMemoryDocMemoryReader reader = newReader();
        reader.put("tenant-a", new DocMemoryVO("d1", "Doc 1", "# First", null, NOW), false);
        reader.put("tenant-a", new DocMemoryVO("d3", "Doc 3", "# Third", null, NOW), false);
        reader.put("tenant-b", new DocMemoryVO("d_other", "Other Tenant", "# Secret", null, NOW), false);
        reader.put("tenant-a", new DocMemoryVO("d_deleted", "Deleted", "# Deleted", null, NOW), true);

        List<DocMemoryVO> results = reader.findByIds(DocMemoryQuery.builder()
                .tenantKey("tenant-a")
                .ids(List.of("d3", "d1", "d_other", "d_deleted", "d3"))
                .build());

        assertThat(results).extracting(DocMemoryVO::id).containsExactly("d3", "d1");
        assertThat(results.get(0).contentMd()).isEqualTo("# Third");
        assertThat(results.get(1).contentMd()).isEqualTo("# First");
    }

    @Test
    void findByIdsShortCircuitsEmptyAndRejectsOverThirtyTwo() {
        InMemoryDocMemoryReader reader = newReader();
        assertThat(reader.findByIds(DocMemoryQuery.builder().tenantKey("tenant-a").ids(List.of()).build()))
                .isEmpty();

        List<String> thirtyThreeIds = IntStream.rangeClosed(1, 33).mapToObj(i -> "doc-" + i).toList();
        assertThatThrownBy(() -> reader.findByIds(DocMemoryQuery.builder()
                .tenantKey("tenant-a")
                .ids(thirtyThreeIds)
                .build()))
                .satisfies(ex -> assertThat(
                        (ex instanceof CommonException && "DOC_MEMORY_BATCH_LIMIT".equals(ex.getMessage()))
                                || ex instanceof ConstraintViolationException).isTrue());
    }
}
