package top.egon.cola.component.agentflow.adapter.springai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import top.egon.cola.component.agentflow.autoconfigure.AgentFlowProperties;
import top.egon.cola.component.agentflow.context.ContextBudgetPolicyVO;
import top.egon.cola.component.agentflow.context.ContextEnvelopeBO;
import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;

import java.util.Objects;
import java.util.UUID;

/**
 * Pipeline entry advisor ({@code Order = 100}) initializing and validating {@link ContextEnvelopeBO}.
 */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class ContextInitAdvisor implements BaseAdvisor {

    public static final int ORDER = 100;

    private final AgentFlowProperties properties;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        Objects.requireNonNull(chatClientRequest, "chatClientRequest");
        Object existing = chatClientRequest.context().get(ContextEnvelopeBO.CONTEXT_KEY);
        ContextEnvelopeBO envelope = existing instanceof ContextEnvelopeBO bo ? bo : new ContextEnvelopeBO();

        if ((envelope.getSystemPrompt() == null || envelope.getSystemPrompt().isBlank())
                && chatClientRequest.prompt().getSystemMessage() != null) {
            envelope.setSystemPrompt(chatClientRequest.prompt().getSystemMessage().getText());
        }
        if (envelope.getRawUserMessage() == null && chatClientRequest.prompt().getUserMessage() != null) {
            envelope.setRawUserMessage(chatClientRequest.prompt().getUserMessage());
        }
        UserMessage userMsg = envelope.getRawUserMessage();
        if (envelope.getSystemPrompt() == null || envelope.getSystemPrompt().isBlank()
                || userMsg == null || userMsg.getText() == null || userMsg.getText().isBlank()) {
            throw new CommonException(ResultCode.SYSTEM_ERROR.getCode(),
                    "INVALID_CONTEXT_INPUT", "INVALID_CONTEXT_INPUT");
        }
        if (envelope.getBudgetPolicy() == null) {
            envelope.setBudgetPolicy(ContextBudgetPolicyVO.fromProperties(properties.context()));
        }
        if (envelope.getTurnId() == null || envelope.getTurnId().isBlank()) {
            envelope.setTurnId("turn_" + UUID.randomUUID().toString().replace("-", ""));
        }
        return chatClientRequest.mutate()
                .context(ContextEnvelopeBO.CONTEXT_KEY, envelope)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }
}
