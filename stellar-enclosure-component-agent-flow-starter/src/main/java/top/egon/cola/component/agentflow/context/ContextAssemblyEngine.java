package top.egon.cola.component.agentflow.context;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import top.egon.cola.component.agentflow.api.context.ContextAssemblyReportVO;
import top.egon.cola.component.agentflow.api.context.ContextAssemblyReportVO.ContextTrimDetailVO;
import top.egon.cola.component.agentflow.api.context.ContextTrimReasonEnum;
import top.egon.cola.component.agentflow.api.memory.DocMemoryVO;
import top.egon.cola.component.agentflow.api.memory.TextMemoryVO;
import top.egon.cola.component.agentflow.memory.TurnAlignedChatMemory;
import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Core five-zone context budget engine performing progressive priority trimming
 * and prompt-injection-isolated rendering into a Spring AI {@link Prompt}.
 */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class ContextAssemblyEngine {

    private static final int MESSAGE_ROLE_OVERHEAD_TOKENS = 4;

    @Qualifier("agentTokenCountEstimator")
    private final TokenCountEstimator tokenCountEstimator;

    /** Immutable output pairing the rendered Spring AI prompt with its budget observability report. */
    public record AssembledPromptVO(Prompt prompt, ContextAssemblyReportVO report) {
    }

    public AssembledPromptVO assemble(ContextEnvelopeBO envelope, ChatOptions options) {
        Objects.requireNonNull(envelope, "envelope");
        ContextBudgetPolicyVO policy = Objects.requireNonNull(envelope.getBudgetPolicy(), "budgetPolicy");
        String systemPrompt = envelope.getSystemPrompt();
        UserMessage rawUser = Objects.requireNonNull(envelope.getRawUserMessage(), "rawUserMessage");

        int toolTokens = estimateToolTokens(envelope.getToolDefinitions());
        int availableInput = policy.availableInputBudget() - toolTokens;
        int systemTokens = estimateTextTokens(systemPrompt) + (systemPrompt == null || systemPrompt.isEmpty() ? 0 : MESSAGE_ROLE_OVERHEAD_TOKENS);
        int userTokens = estimateSingleMessageTokens(rawUser);

        if (availableInput <= 0 || systemTokens + userTokens > availableInput) {
            throw budgetExceeded();
        }

        List<TurnAlignedChatMemory.SessionTurnVO> turns = new ArrayList<>(
                envelope.getSessionTurns() == null ? List.of() : envelope.getSessionTurns());
        int initialTurns = turns.size();
        int minKeepTurns = Math.min(policy.minKeepHistoryTurns(), initialTurns);

        TextMemoryVO textMemory = hasTextMemory(envelope.getTextMemory()) ? envelope.getTextMemory() : null;
        List<DocMemoryVO> docs = new ArrayList<>(
                envelope.getDocMemories() == null ? List.of() : envelope.getDocMemories());
        int docsRequested = docs.size();
        List<String> others = new ArrayList<>(
                envelope.getOtherFragments() == null ? List.of() : envelope.getOtherFragments());
        List<ContextTrimDetailVO> trimDetails = new ArrayList<>();

        List<Message> renderedMessages = renderMessages(systemPrompt, rawUser, turns, textMemory, docs, others, policy);
        int estimatedTotal = estimateMessagesTokens(renderedMessages);

        while (estimatedTotal > availableInput) {
            if (!others.isEmpty()) {
                int idx = others.size() - 1;
                String dropped = others.remove(idx);
                trimDetails.add(new ContextTrimDetailVO(
                        ContextTrimReasonEnum.BUDGET_OTHERS,
                        "other-" + idx,
                        estimateTextTokens(dropped)));
            } else if (!docs.isEmpty()) {
                DocMemoryVO dropped = docs.remove(docs.size() - 1);
                trimDetails.add(new ContextTrimDetailVO(
                        ContextTrimReasonEnum.BUDGET_DOC_MEMORY,
                        dropped.id(),
                        estimateTextTokens(dropped.title()) + estimateTextTokens(dropped.contentMd())));
            } else if (textMemory != null) {
                TextMemoryVO dropped = textMemory;
                textMemory = null;
                trimDetails.add(new ContextTrimDetailVO(
                        ContextTrimReasonEnum.BUDGET_TEXT_MEMORY,
                        dropped.id(),
                        estimateTextTokens(dropped.contentText())));
            } else if (turns.size() > minKeepTurns) {
                TurnAlignedChatMemory.SessionTurnVO dropped = turns.remove(0);
                trimDetails.add(new ContextTrimDetailVO(
                        ContextTrimReasonEnum.BUDGET_HISTORY_TURN,
                        dropped.turnId(),
                        estimateMessagesTokens(dropped.messages())));
            } else {
                break;
            }
            renderedMessages = renderMessages(systemPrompt, rawUser, turns, textMemory, docs, others, policy);
            estimatedTotal = estimateMessagesTokens(renderedMessages);
        }

        if (estimatedTotal > availableInput) {
            throw budgetExceeded();
        }

        Prompt prompt = options == null ? new Prompt(renderedMessages) : new Prompt(renderedMessages, options);
        int othersTrimmed = (int) trimDetails.stream()
                .filter(d -> d.reason() == ContextTrimReasonEnum.BUDGET_OTHERS)
                .count();
        ContextAssemblyReportVO report = new ContextAssemblyReportVO(
                availableInput,
                estimatedTotal,
                systemTokens,
                turns.size(),
                initialTurns - turns.size(),
                textMemory != null,
                textMemory == null ? 0 : textMemory.charCount(),
                docsRequested,
                docs.size(),
                othersTrimmed,
                trimDetails);

        log.debug("Assembled context prompt: report={}", report);
        return new AssembledPromptVO(prompt, report);
    }

    private static boolean hasTextMemory(TextMemoryVO vo) {
        return vo != null && vo.contentText() != null && !vo.contentText().isBlank();
    }

    private List<Message> renderMessages(String systemPrompt,
                                         UserMessage rawUser,
                                         List<TurnAlignedChatMemory.SessionTurnVO> turns,
                                         TextMemoryVO textMemory,
                                         List<DocMemoryVO> docs,
                                         List<String> others,
                                         ContextBudgetPolicyVO policy) {
        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }
        for (TurnAlignedChatMemory.SessionTurnVO turn : turns) {
            messages.addAll(turn.messages());
        }

        String contextDataBlock = renderContextDataBlock(textMemory, docs, others);
        String rawUserText = rawUser.getText() == null ? "" : rawUser.getText();
        if (contextDataBlock.isEmpty()) {
            messages.add(new UserMessage(rawUserText));
        } else if (policy.combineContextIntoFinalUserMessage()) {
            messages.add(new UserMessage(contextDataBlock + "\n\n" + rawUserText));
        } else {
            messages.add(new UserMessage(contextDataBlock));
            messages.add(new UserMessage(rawUserText));
        }
        return messages;
    }

    private static String renderContextDataBlock(TextMemoryVO textMemory,
                                                 List<DocMemoryVO> docs,
                                                 List<String> others) {
        boolean hasText = hasTextMemory(textMemory);
        boolean hasDocs = docs != null && !docs.isEmpty();
        boolean hasOthers = others != null && !others.isEmpty();
        if (!hasText && !hasDocs && !hasOthers) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<context_data trust=\"reference_only\">\n");
        if (hasText) {
            sb.append("<mid_term_memory>\n")
                    .append(textMemory.contentText())
                    .append("\n</mid_term_memory>\n");
        }
        if (hasDocs) {
            for (DocMemoryVO doc : docs) {
                sb.append("<doc_memory id=\"")
                        .append(escapeXmlAttr(doc.id()))
                        .append("\" title=\"")
                        .append(escapeXmlAttr(doc.title()))
                        .append("\">\n")
                        .append(doc.contentMd())
                        .append("\n</doc_memory>\n");
            }
        }
        if (hasOthers) {
            sb.append("<others_context>\n");
            for (String fragment : others) {
                if (fragment != null && !fragment.isBlank()) {
                    sb.append(fragment).append('\n');
                }
            }
            sb.append("</others_context>\n");
        }
        sb.append("</context_data>");
        return sb.toString();
    }

    private static String escapeXmlAttr(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private int estimateToolTokens(List<ToolDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ToolDefinition def : definitions) {
            total += estimateTextTokens(def.name())
                    + estimateTextTokens(def.description())
                    + estimateTextTokens(def.inputSchema());
        }
        return total;
    }

    private int estimateMessagesTokens(List<Message> messages) {
        int total = 0;
        for (Message msg : messages) {
            total += estimateSingleMessageTokens(msg);
        }
        return total;
    }

    private int estimateSingleMessageTokens(Message msg) {
        int tokens = MESSAGE_ROLE_OVERHEAD_TOKENS + estimateTextTokens(msg.getText());
        if (msg instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
            for (AssistantMessage.ToolCall tc : assistant.getToolCalls()) {
                tokens += estimateTextTokens(tc.name()) + estimateTextTokens(tc.arguments());
            }
        } else if (msg instanceof ToolResponseMessage trm && trm.getResponses() != null) {
            for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                tokens += estimateTextTokens(resp.name()) + estimateTextTokens(resp.responseData());
            }
        }
        return tokens;
    }

    private int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return tokenCountEstimator.estimate(text);
    }

    private static CommonException budgetExceeded() {
        return new CommonException(ResultCode.SYSTEM_ERROR.getCode(),
                "CONTEXT_BUDGET_EXCEEDED", "CONTEXT_BUDGET_EXCEEDED");
    }
}
