package top.egon.cola.component.agentflow.api.session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import top.egon.cola.component.agentflow.api.capability.CallPermitVO;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;

public interface SessionStore {

    String CAPABILITY_REF = "session";
    String READ_PURPOSE = "SESSION_READ";
    String WRITE_PURPOSE = "SESSION_WRITE";

    @Valid @NotNull SessionStateResult read(@Valid @NotNull ContextScopeVO scope,
                                           @NotBlank @Size(max = 128) String sessionId);

    @Valid @NotNull SessionStateResult compareAndSet(@Valid @NotNull SessionWriteCommand command,
                                                     @Valid @NotNull ContextScopeVO scope,
                                                     @Valid @NotNull CallPermitVO permit);
}
