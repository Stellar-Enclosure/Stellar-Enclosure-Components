package top.egon.cola.component.agentflow.adapter.springai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import top.egon.cola.component.agentflow.api.context.ContextFragmentResult;
import top.egon.cola.component.agentflow.context.ContextEnvelopeBO;

import java.util.Objects;

/**
 * Runtime/RAG context advisor ({@code Order = 500}) collecting {@code Others} zone fragments
 * into {@link ContextEnvelopeBO#getOtherFragments()}.
 */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class OthersContextAdvisor implements BaseAdvisor {

    public static final int ORDER = 500;

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
        if (envelope.getContextSource() != null
                && envelope.getContextQuery() != null
                && envelope.getScope() != null
                && envelope.getCallPermit() != null) {
            ContextFragmentResult resolved = envelope.getContextSource()
                    .resolve(envelope.getContextQuery(), envelope.getScope(), envelope.getCallPermit());
            if (resolved != null && resolved.getFragments() != null) {
                for (ContextFragmentResult.FragmentVO fragment : resolved.getFragments()) {
                    envelope.getOtherFragments().add("[" + fragment.sourceRef() + "] " + fragment.content());
                }
            }
        }
        return chatClientRequest;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }
}
