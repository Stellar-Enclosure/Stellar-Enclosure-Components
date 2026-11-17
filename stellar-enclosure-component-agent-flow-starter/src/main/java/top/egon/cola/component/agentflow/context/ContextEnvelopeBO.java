package top.egon.cola.component.agentflow.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.definition.ToolDefinition;
import top.egon.cola.component.agentflow.api.capability.CallPermitVO;
import top.egon.cola.component.agentflow.api.context.ContextQuery;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.context.ContextSource;
import top.egon.cola.component.agentflow.api.memory.DocMemoryVO;
import top.egon.cola.component.agentflow.api.memory.TextMemoryVO;
import top.egon.cola.component.agentflow.memory.TurnAlignedChatMemory;

import java.util.ArrayList;
import java.util.List;

/** Mutable five-zone intermediate envelope carried inside {@code ChatClientRequest.context()} across Advisors. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
public class ContextEnvelopeBO {

    public static final String CONTEXT_KEY = "egon.agentflow.context.envelope";
    public static final String REPORT_KEY = "egon.agentflow.context.report";

    private ContextScopeVO scope;
    private String systemPrompt;
    private UserMessage rawUserMessage;
    private String turnId;
    private Integer windowTurns;
    private String textMemoryId;
    private boolean strictTextMemory;

    @Builder.Default
    private List<String> docMemoryIds = new ArrayList<>();

    private ContextSource contextSource;
    private ContextQuery contextQuery;
    private CallPermitVO callPermit;

    @Builder.Default
    private List<TurnAlignedChatMemory.SessionTurnVO> sessionTurns = new ArrayList<>();

    private TextMemoryVO textMemory;

    @Builder.Default
    private List<DocMemoryVO> docMemories = new ArrayList<>();

    @Builder.Default
    private List<String> otherFragments = new ArrayList<>();

    @Builder.Default
    private List<ToolDefinition> toolDefinitions = new ArrayList<>();

    @Builder.Default
    private List<Message> toolLoopMessages = new ArrayList<>();

    private ContextBudgetPolicyVO budgetPolicy;
}
