package top.egon.cola.component.agentflow.api.execution;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public interface AgentExecutor {

    @NotNull AgentExecutionHandle execute(@Valid @NotNull AgentExecutionCommand command,
                                          @Valid @NotNull AgentExecutionContextVO context);
}
