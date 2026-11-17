package top.egon.cola.component.agentflow.adapter.springai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import top.egon.cola.component.agentflow.context.ContextAssemblyEngine;
import top.egon.cola.component.agentflow.context.ContextEnvelopeBO;

import java.util.Objects;

/**
 * Closing context assembly advisor ({@code Order = 900}) invoking {@link ContextAssemblyEngine}
 * to budget-trim and render five-zone context into the final Spring AI {@code Prompt}.
 */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class ContextAssemblyAdvisor implements BaseAdvisor {

    public static final int ORDER = 900;

    @Qualifier("agentContextAssemblyEngine")
    private final ContextAssemblyEngine assemblyEngine;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        Objects.requireNonNull(chatClientRequest, "chatClientRequest");
        Object raw = chatClientRequest.context().get(ContextEnvelopeBO.CONTEXT_KEY);
        if (!(raw instanceof ContextEnvelopeBO envelope)) {
            return chatClientRequest;
        }
        ContextAssemblyEngine.AssembledPromptVO assembled =
                assemblyEngine.assemble(envelope, chatClientRequest.prompt().getOptions());
        return chatClientRequest.mutate()
                .prompt(assembled.prompt())
                .context(ContextEnvelopeBO.REPORT_KEY, assembled.report())
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }
}
