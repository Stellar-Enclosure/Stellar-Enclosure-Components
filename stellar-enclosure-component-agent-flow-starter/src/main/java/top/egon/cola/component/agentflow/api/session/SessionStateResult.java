package top.egon.cola.component.agentflow.api.session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.Map;

public record SessionStateResult(boolean found, @PositiveOrZero long revision, @Valid Map<String, Object> state) {

    public SessionStateResult {
        if ((!found && (revision != 0 || state != null)) || (found && (revision <= 0 || state == null))) {
            throw new IllegalArgumentException("Invalid session state result");
        }
        if (found) {
            state = SessionWriteCommand.checkedState(state);
        }
    }

    public static SessionStateResult absent() {
        return new SessionStateResult(false, 0, null);
    }
}
