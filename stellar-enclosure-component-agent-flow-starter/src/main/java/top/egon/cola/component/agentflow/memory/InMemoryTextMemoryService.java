package top.egon.cola.component.agentflow.memory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.memory.MemoryOwnerTypeEnum;
import top.egon.cola.component.agentflow.api.memory.TextMemoryBindCommand;
import top.egon.cola.component.agentflow.api.memory.TextMemorySaveCommand;
import top.egon.cola.component.agentflow.api.memory.TextMemoryService;
import top.egon.cola.component.agentflow.api.memory.TextMemoryVO;
import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;
import top.egon.cola.component.common.core.validation.ValidationUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory Level 2 text memory service enforcing strict length rejection and optimistic locking.
 */
@Slf4j
@Validated
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class InMemoryTextMemoryService implements TextMemoryService {

    @Qualifier("agentExecutionClock")
    private final Clock clock;

    @Qualifier("egonColaValidationUtils")
    private final ValidationUtils validationUtils;

    private final int maxChars;

    private final ConcurrentHashMap<OwnerKey, String> idByOwner = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StoredSlot> byId = new ConcurrentHashMap<>();

    @Override
    public TextMemoryVO bindOrCreate(@Valid @NotNull TextMemoryBindCommand command) {
        Objects.requireNonNull(command, "command");
        validationUtils.validate(command);
        TextMemoryBindCommand safe = command.snapshot();
        OwnerKey key = new OwnerKey(safe.getTenantKey(), safe.getNamespace(), safe.getOwnerType(), safe.getOwnerKey());

        String id = idByOwner.computeIfAbsent(key, ignored -> {
            String generatedId = "tm_" + UUID.randomUUID().toString().replace("-", "");
            Instant now = nowMicros();
            byId.put(generatedId, new StoredSlot(generatedId, safe.getTenantKey(), "", 1L, now));
            return generatedId;
        });

        StoredSlot slot = byId.get(id);
        synchronized (slot) {
            return slot.toVO();
        }
    }

    @Override
    public Optional<TextMemoryVO> getById(@NotBlank @Size(max = 128) String tenantKey,
                                          @NotBlank @Size(max = 64) String id) {
        ContextScopeVO.requireIdentifier(tenantKey);
        if (id == null || id.isBlank() || id.length() > 64) {
            throw new IllegalArgumentException("Invalid TextMemory id");
        }
        StoredSlot slot = byId.get(id);
        if (slot == null) {
            return Optional.empty();
        }
        synchronized (slot) {
            if (!tenantKey.equals(slot.tenantKey)) {
                return Optional.empty();
            }
            return Optional.of(slot.toVO());
        }
    }

    @Override
    public TextMemoryVO save(@Valid @NotNull TextMemorySaveCommand command) {
        Objects.requireNonNull(command, "command");
        TextMemorySaveCommand safe = command.snapshot(maxChars);
        validationUtils.validate(safe);

        StoredSlot slot = byId.get(safe.getId());
        if (slot == null) {
            throw new CommonException(ResultCode.SYSTEM_ERROR.getCode(),
                    "TEXT_MEMORY_NOT_FOUND", "TEXT_MEMORY_NOT_FOUND");
        }
        synchronized (slot) {
            if (!safe.getTenantKey().equals(slot.tenantKey)) {
                throw new CommonException(ResultCode.SYSTEM_ERROR.getCode(),
                        "TEXT_MEMORY_NOT_FOUND", "TEXT_MEMORY_NOT_FOUND");
            }
            if (slot.version != safe.getExpectedVersion()) {
                throw new CommonException(ResultCode.SYSTEM_ERROR.getCode(),
                        "VERSION_CONFLICT", "VERSION_CONFLICT");
            }
            slot.contentText = safe.getContentText();
            slot.version = slot.version + 1L;
            slot.updatedAt = nowMicros();
            return slot.toVO();
        }
    }

    private Instant nowMicros() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private record OwnerKey(String tenantKey, String namespace, MemoryOwnerTypeEnum ownerType, String ownerKey) {
    }

    private static final class StoredSlot {
        private final String id;
        private final String tenantKey;
        private String contentText;
        private long version;
        private Instant updatedAt;

        private StoredSlot(String id, String tenantKey, String contentText, long version, Instant updatedAt) {
            this.id = id;
            this.tenantKey = tenantKey;
            this.contentText = contentText;
            this.version = version;
            this.updatedAt = updatedAt;
        }

        private TextMemoryVO toVO() {
            return new TextMemoryVO(id, version, contentText, contentText.length(), updatedAt);
        }
    }
}
