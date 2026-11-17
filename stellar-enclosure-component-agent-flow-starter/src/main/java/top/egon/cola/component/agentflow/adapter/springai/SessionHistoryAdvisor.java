package top.egon.cola.component.agentflow.adapter.springai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import reactor.core.publisher.Flux;
import top.egon.cola.component.agentflow.context.ContextEnvelopeBO;
import top.egon.cola.component.agentflow.memory.InMemorySessionChatMemoryRepository;
import top.egon.cola.component.agentflow.memory.TurnAlignedChatMemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Level 1 session history advisor ({@code Order = 200}) loading turn-aligned history before
 * model invocation and atomically appending completed turns only on full success.
 */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class SessionHistoryAdvisor implements BaseAdvisor {

    public static final int ORDER = 200;

    @Qualifier("turnAlignedChatMemory")
    private final TurnAlignedChatMemory chatMemory;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        Objects.requireNonNull(chatClientRequest, "chatClientRequest");
        Object raw = chatClientRequest.context().get(ContextEnvelopeBO.CONTEXT_KEY);
        if (!(raw instanceof ContextEnvelopeBO envelope) || envelope.getScope() == null) {
            return chatClientRequest;
        }
        String tenantKey = envelope.getScope().tenantKey();
        String conversationId = envelope.getScope().sessionKey();
        if (conversationId == null || conversationId.isBlank()) {
            return chatClientRequest;
        }
        List<TurnAlignedChatMemory.SessionTurnVO> turns =
                chatMemory.getTurns(tenantKey, conversationId, envelope.getWindowTurns());
        envelope.setSessionTurns(new ArrayList<>(turns));
        return chatClientRequest;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        appendCompletedTurn(chatClientResponse);
        return chatClientResponse;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
                                                   StreamAdvisorChain streamAdvisorChain) {
        ChatClientRequest prepared = before(chatClientRequest, streamAdvisorChain);
        AtomicBoolean cancelledOrErrored = new AtomicBoolean(false);
        AtomicBoolean completedNormally = new AtomicBoolean(false);
        AtomicReference<ChatClientResponse> aggregated = new AtomicReference<>();

        Flux<ChatClientResponse> monitored = streamAdvisorChain.nextStream(prepared)
                .doOnCancel(() -> cancelledOrErrored.set(true))
                .doOnError(error -> cancelledOrErrored.set(true))
                .doOnComplete(() -> completedNormally.set(true));

        return new ChatClientMessageAggregator().aggregateChatClientResponse(monitored, response -> {
            aggregated.set(response);
            if (completedNormally.get() && !cancelledOrErrored.get()) {
                appendCompletedTurn(response);
            }
        });
    }

    private void appendCompletedTurn(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) {
            return;
        }
        Object raw = response.context().get(ContextEnvelopeBO.CONTEXT_KEY);
        if (!(raw instanceof ContextEnvelopeBO envelope) || envelope.getScope() == null) {
            return;
        }
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null) {
            return;
        }
        AssistantMessage assistantOutput = chatResponse.getResult().getOutput();
        List<Message> turnMessages = new ArrayList<>();
        if (envelope.getRawUserMessage() != null) {
            turnMessages.add(envelope.getRawUserMessage());
        }
        if (envelope.getToolLoopMessages() != null && !envelope.getToolLoopMessages().isEmpty()) {
            turnMessages.addAll(envelope.getToolLoopMessages());
        }
        turnMessages.add(assistantOutput);

        String tenantKey = envelope.getScope().tenantKey() == null
                ? InMemorySessionChatMemoryRepository.DEFAULT_TENANT
                : envelope.getScope().tenantKey();
        chatMemory.appendTurn(tenantKey, envelope.getScope().sessionKey(), envelope.getTurnId(), turnMessages);
    }
}
