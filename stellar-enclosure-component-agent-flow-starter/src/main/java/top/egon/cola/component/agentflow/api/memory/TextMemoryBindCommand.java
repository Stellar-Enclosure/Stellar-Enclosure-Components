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

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Command to idempotently bind or create a Level 2 text memory entry and return its stable id. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
public class TextMemoryBindCommand {

    @NotBlank
    @Size(max = 128)
    private String tenantKey;

    @NotBlank
    @Size(max = 128)
    private String namespace;

    @NotNull
    private MemoryOwnerTypeEnum ownerType;

    @NotBlank
    @Size(max = 256)
    private String ownerKey;

    /** Validates and returns an isolated copy safe for repository execution. */
    public TextMemoryBindCommand snapshot() {
        ContextScopeVO.requireIdentifier(tenantKey);
        ContextScopeVO.requireIdentifier(namespace);
        Objects.requireNonNull(ownerType, "ownerType");
        requireOwnerKey(ownerKey);
        return new TextMemoryBindCommand(tenantKey, namespace, ownerType, ownerKey);
    }

    private static void requireOwnerKey(String value) {
        if (value == null || value.isBlank() || value.length() > 256
                || value.codePoints().anyMatch(Character::isISOControl)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException("Invalid TextMemory ownerKey");
        }
    }
}
