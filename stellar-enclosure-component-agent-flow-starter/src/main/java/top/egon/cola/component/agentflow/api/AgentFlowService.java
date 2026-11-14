package top.egon.cola.component.agentflow.api;

import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.agentflow.config.AgentFlowSessionValidationGroup;

import java.util.List;

/**
 * Compatibility facade for compiled ADK flow discovery and in-memory session execution.
 * @deprecated Use the neutral AgentExecutor contract for new integrations.
 */
@Deprecated(forRemoval = false)
public interface AgentFlowService {

    List<AgentFlowDescriptorDTO> listFlows();

    @Validated(AgentFlowSessionValidationGroup.Create.class)
    AgentFlowSessionResult createSession(@Valid AgentFlowSessionCommand command);

    @Validated(AgentFlowSessionValidationGroup.Delete.class)
    void deleteSession(@Valid AgentFlowSessionCommand command);

    List<Event> execute(@Valid AgentFlowExecutionCommand command);

    Flowable<Event> executeStream(@Valid AgentFlowExecutionCommand command);
}
