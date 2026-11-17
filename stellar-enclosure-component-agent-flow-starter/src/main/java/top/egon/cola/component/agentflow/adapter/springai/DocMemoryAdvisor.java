package top.egon.cola.component.agentflow.adapter.springai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import top.egon.cola.component.agentflow.api.memory.DocMemoryQuery;
import top.egon.cola.component.agentflow.api.memory.DocMemoryReader;
import top.egon.cola.component.agentflow.api.memory.DocMemoryVO;
import top.egon.cola.component.agentflow.context.ContextEnvelopeBO;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Level 3 document memory advisor ({@code Order = 400}) reading host-owned Markdown documents
 * strictly in caller-specified id priority order.
 */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class DocMemoryAdvisor implements BaseAdvisor {

    public static final int ORDER = 400;

    @Qualifier("agentDocMemoryReader")
    private final DocMemoryReader docMemoryReader;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        Objects.requireNonNull(chatClientRequest, "chatClientRequest");
        Object raw = chatClientRequest.context().get(ContextEnvelopeBO.CONTEXT_KEY);
        if (!(raw instanceof ContextEnvelopeBO envelope)
                || envelope.getScope() == null
                || envelope.getDocMemoryIds() == null
                || envelope.getDocMemoryIds().isEmpty()) {
            return chatClientRequest;
        }
        List<DocMemoryVO> docs = docMemoryReader.findByIds(DocMemoryQuery.builder()
                .tenantKey(envelope.getScope().tenantKey())
                .ids(envelope.getDocMemoryIds())
                .build());
        envelope.setDocMemories(new ArrayList<>(docs));
        return chatClientRequest;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }
}
