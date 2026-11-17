package top.egon.cola.component.agentflow.adapter.springai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import top.egon.cola.component.agentflow.api.memory.TextMemoryService;
import top.egon.cola.component.agentflow.api.memory.TextMemoryVO;
import top.egon.cola.component.agentflow.context.ContextEnvelopeBO;
import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;

import java.util.Objects;
import java.util.Optional;

/**
 * Level 2 mid-term text memory advisor ({@code Order = 300}) loading tenant-isolated summary slot by id.
 */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class TextMemoryAdvisor implements BaseAdvisor {

    public static final int ORDER = 300;

    @Qualifier("agentTextMemoryService")
    private final TextMemoryService textMemoryService;

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
                || envelope.getTextMemoryId() == null
                || envelope.getTextMemoryId().isBlank()) {
            return chatClientRequest;
        }
        Optional<TextMemoryVO> found = textMemoryService.getById(
                envelope.getScope().tenantKey(), envelope.getTextMemoryId());
        if (found.isPresent()) {
            envelope.setTextMemory(found.get());
        } else if (envelope.isStrictTextMemory()) {
            throw new CommonException(ResultCode.SYSTEM_ERROR.getCode(),
                    "TEXT_MEMORY_NOT_FOUND", "TEXT_MEMORY_NOT_FOUND");
        }
        return chatClientRequest;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }
}
