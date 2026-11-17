package top.egon.cola.component.agentflow.memory;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.agentflow.api.memory.MemoryOwnerTypeEnum;
import top.egon.cola.component.agentflow.api.memory.TextMemoryBindCommand;
import top.egon.cola.component.agentflow.api.memory.TextMemorySaveCommand;
import top.egon.cola.component.agentflow.api.memory.TextMemoryVO;
import top.egon.cola.component.common.core.exception.CommonException;
import top.egon.cola.component.common.core.validation.ValidationUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextMemoryServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-17T08:30:00Z"), ZoneOffset.UTC);

    private static InMemoryTextMemoryService newService() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        return new InMemoryTextMemoryService(FIXED_CLOCK, new ValidationUtils(validator), TextMemorySaveCommand.MAX_CONTENT_CHARS);
    }

    @Test
    void bindOrCreateReturnsStableIdForSameOwner() {
        InMemoryTextMemoryService service = newService();

        TextMemoryVO first = service.bindOrCreate(TextMemoryBindCommand.builder()
                .tenantKey("tenant-a")
                .namespace("crm")
                .ownerType(MemoryOwnerTypeEnum.SESSION)
                .ownerKey("sess-42")
                .build());
        TextMemoryVO second = service.bindOrCreate(TextMemoryBindCommand.builder()
                .tenantKey("tenant-a")
                .namespace("crm")
                .ownerType(MemoryOwnerTypeEnum.SESSION)
                .ownerKey("sess-42")
                .build());
        TextMemoryVO otherTenant = service.bindOrCreate(TextMemoryBindCommand.builder()
                .tenantKey("tenant-b")
                .namespace("crm")
                .ownerType(MemoryOwnerTypeEnum.SESSION)
                .ownerKey("sess-42")
                .build());

        assertThat(first.id()).startsWith("tm_");
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(first.version()).isEqualTo(1L);
        assertThat(first.contentText()).isEmpty();
        assertThat(otherTenant.id()).isNotEqualTo(first.id());
        assertThat(service.getById("tenant-b", first.id())).isEmpty();
    }

    @Test
    void saveAllowsUpToTenThousandCharsAndRejectsTenThousandAndOne() {
        InMemoryTextMemoryService service = newService();
        TextMemoryVO created = service.bindOrCreate(TextMemoryBindCommand.builder()
                .tenantKey("tenant-a")
                .namespace("crm")
                .ownerType(MemoryOwnerTypeEnum.AGENT)
                .ownerKey("agent-1")
                .build());

        String exactTenK = "a".repeat(10_000);
        TextMemoryVO saved = service.save(TextMemorySaveCommand.builder()
                .tenantKey("tenant-a")
                .id(created.id())
                .contentText(exactTenK)
                .expectedVersion(1L)
                .build());
        assertThat(saved.version()).isEqualTo(2L);
        assertThat(saved.charCount()).isEqualTo(10_000);

        String overLimit = "b".repeat(10_001);
        assertThatThrownBy(() -> service.save(TextMemorySaveCommand.builder()
                .tenantKey("tenant-a")
                .id(created.id())
                .contentText(overLimit)
                .expectedVersion(2L)
                .build()))
                .satisfies(ex -> assertThat(
                        (ex instanceof CommonException && "TEXT_MEMORY_TOO_LARGE".equals(ex.getMessage()))
                                || ex instanceof ConstraintViolationException).isTrue());

        TextMemoryVO unchanged = service.getById("tenant-a", created.id()).orElseThrow();
        assertThat(unchanged.version()).isEqualTo(2L);
        assertThat(unchanged.contentText()).isEqualTo(exactTenK);
    }

    @Test
    void saveRejectsStaleVersionWithoutMutatingStoredText() {
        InMemoryTextMemoryService service = newService();
        TextMemoryVO created = service.bindOrCreate(TextMemoryBindCommand.builder()
                .tenantKey("tenant-a")
                .namespace("crm")
                .ownerType(MemoryOwnerTypeEnum.CUSTOM)
                .ownerKey("user-memory-1")
                .build());

        TextMemoryVO updated = service.save(TextMemorySaveCommand.builder()
                .tenantKey("tenant-a")
                .id(created.id())
                .contentText("v2 summary")
                .expectedVersion(1L)
                .build());
        assertThat(updated.version()).isEqualTo(2L);

        assertThatThrownBy(() -> service.save(TextMemorySaveCommand.builder()
                .tenantKey("tenant-a")
                .id(created.id())
                .contentText("stale overwrite")
                .expectedVersion(1L)
                .build()))
                .isInstanceOf(CommonException.class)
                .hasMessage("VERSION_CONFLICT");

        assertThatThrownBy(() -> service.save(TextMemorySaveCommand.builder()
                .tenantKey("tenant-b")
                .id(created.id())
                .contentText("cross-tenant overwrite")
                .expectedVersion(2L)
                .build()))
                .isInstanceOf(CommonException.class)
                .hasMessage("TEXT_MEMORY_NOT_FOUND");

        assertThat(service.getById("tenant-a", created.id()).orElseThrow().contentText())
                .isEqualTo("v2 summary");
    }
}
