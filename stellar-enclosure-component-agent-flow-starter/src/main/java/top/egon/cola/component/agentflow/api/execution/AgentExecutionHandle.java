package top.egon.cola.component.agentflow.api.execution;

import jakarta.validation.constraints.NotBlank;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public interface AgentExecutionHandle {

    Flow.Publisher<AgentExecutionEvent> events();

    CompletionStage<AgentExecutionResult> completion();

    void cancel(@NotBlank String reason);
}
