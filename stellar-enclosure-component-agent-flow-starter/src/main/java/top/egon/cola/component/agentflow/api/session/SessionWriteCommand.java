package top.egon.cola.component.agentflow.api.session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
public class SessionWriteCommand {

    @NotBlank @Size(max = 128)
    private String sessionId;

    @PositiveOrZero
    private long expectedRevision;

    @NotBlank @Size(max = 128)
    private String commandId;

    @NotNull @Valid
    private Map<String, Object> state;

    static Map<String, Object> checkedState(Map<String, Object> state) {
        Map<String, Object> snapshot = AgentExecutionCommand.JsonValues.copyObject(state, 256 * 1024);
        if (!snapshot.keySet().equals(Set.of("schemaVersion", "messages", "localState"))
                || !(snapshot.get("messages") instanceof List<?> messages) || messages.size() > 512
                || !(snapshot.get("localState") instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Invalid session state");
        }
        if (!Long.valueOf(1).equals(snapshot.get("schemaVersion"))) {
            throw new IllegalArgumentException("INCOMPATIBLE_STATE");
        }
        return snapshot;
    }

    public Map<String, Object> checkedState() {
        return checkedState(state);
    }
}
