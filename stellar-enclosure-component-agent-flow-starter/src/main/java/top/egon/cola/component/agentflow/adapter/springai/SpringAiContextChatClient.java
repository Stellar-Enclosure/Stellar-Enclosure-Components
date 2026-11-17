package top.egon.cola.component.agentflow.adapter.springai;

import io.micrometer.observation.ObservationRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import top.egon.cola.component.agentflow.api.capability.CallPermitVO;
import top.egon.cola.component.agentflow.api.context.ContextAssemblyReportVO;
import top.egon.cola.component.agentflow.api.context.ContextQuery;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.context.ContextSource;
import top.egon.cola.component.agentflow.autoconfigure.AgentFlowProperties;
import top.egon.cola.component.agentflow.context.ContextBudgetPolicyVO;
import top.egon.cola.component.agentflow.context.ContextEnvelopeBO;
import top.egon.cola.component.agentflow.memory.TurnAlignedChatMemory;
import top.egon.cola.component.common.core.validation.ValidationUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Unified Path A Spring AI {@link ChatClient} facade orchestrating the 6-advisor
 * five-zone context and three-tier memory pipeline (sync and streaming).
 */
@Slf4j
@Validated
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class SpringAiContextChatClient {

    private static final int TOOL_ADVISOR_ORDER = 1000;

    @Qualifier("contextInitAdvisor")
    private final ContextInitAdvisor contextInitAdvisor;

    @Qualifier("sessionHistoryAdvisor")
    private final SessionHistoryAdvisor sessionHistoryAdvisor;

    @Qualifier("textMemoryAdvisor")
    private final TextMemoryAdvisor textMemoryAdvisor;

    @Qualifier("docMemoryAdvisor")
    private final DocMemoryAdvisor docMemoryAdvisor;

    @Qualifier("othersContextAdvisor")
    private final OthersContextAdvisor othersContextAdvisor;

    @Qualifier("contextAssemblyAdvisor")
    private final ContextAssemblyAdvisor contextAssemblyAdvisor;

    @Qualifier("egonColaValidationUtils")
    private final ValidationUtils validationUtils;

    private final AgentFlowProperties properties;

    /** Mutable call command carrier passed to {@link SpringAiContextChatClient}. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    @Builder
    public static class AgentContextCallBO {
        @NotNull
        private ChatModel chatModel;

        @NotNull
        @Valid
        private ContextScopeVO scope;

        @NotBlank
        private String systemPrompt;

        @NotBlank
        private String userPrompt;

        private String turnId;

        @Min(1)
        @Max(TurnAlignedChatMemory.MAX_WINDOW_TURNS)
        private Integer windowTurns;

        private String textMemoryId;
        private boolean strictTextMemory;

        @Builder.Default
        private List<String> docMemoryIds = new ArrayList<>();

        @Builder.Default
        private List<String> otherFragments = new ArrayList<>();

        private ContextSource contextSource;
        private ContextQuery contextQuery;
        private CallPermitVO callPermit;

        @Builder.Default
        private List<ToolCallback> toolCallbacks = new ArrayList<>();

        private ContextBudgetPolicyVO budgetPolicy;
    }

    /** Immutable response combining text content and unified context assembly observability report. */
    public record AgentContextCallResultVO(String content, ChatResponse chatResponse, ContextAssemblyReportVO report) {
    }

    public AgentContextCallResultVO call(@Valid @NotNull AgentContextCallBO request) {
        Objects.requireNonNull(request, "request");
        validationUtils.validate(request);

        ContextEnvelopeBO envelope = prepareEnvelope(request);
        ChatClient client = buildClient(request.getChatModel(), envelope);
        ChatClient.ChatClientRequestSpec spec = configureSpec(client, request, envelope);

        ChatClientResponse clientResponse = spec.call().chatClientResponse();
        ContextAssemblyReportVO report = extractReport(clientResponse);
        ChatResponse chatResponse = clientResponse == null ? null : clientResponse.chatResponse();
        String content = extractText(chatResponse);
        return new AgentContextCallResultVO(content, chatResponse, report);
    }

    public Flux<String> stream(@Valid @NotNull AgentContextCallBO request,
                               Consumer<ContextAssemblyReportVO> reportConsumer) {
        Objects.requireNonNull(request, "request");
        validationUtils.validate(request);

        ContextEnvelopeBO envelope = prepareEnvelope(request);
        ChatClient client = buildClient(request.getChatModel(), envelope);
        ChatClient.ChatClientRequestSpec spec = configureSpec(client, request, envelope);

        return spec.stream().chatClientResponse()
                .doOnNext(resp -> {
                    if (reportConsumer != null) {
                        ContextAssemblyReportVO report = extractReport(resp);
                        if (report != null) {
                            reportConsumer.accept(report);
                        }
                    }
                })
                .map(resp -> extractText(resp.chatResponse()))
                .filter(text -> !text.isEmpty());
    }

    private ContextEnvelopeBO prepareEnvelope(AgentContextCallBO request) {
        ContextBudgetPolicyVO effectivePolicy = request.getBudgetPolicy() == null
                ? ContextBudgetPolicyVO.fromProperties(properties.context())
                : request.getBudgetPolicy();

        List<ToolDefinition> toolDefs = new ArrayList<>();
        if (request.getToolCallbacks() != null) {
            for (ToolCallback cb : request.getToolCallbacks()) {
                if (cb != null && cb.getToolDefinition() != null) {
                    toolDefs.add(cb.getToolDefinition());
                }
            }
        }

        return ContextEnvelopeBO.builder()
                .scope(request.getScope())
                .systemPrompt(request.getSystemPrompt())
                .rawUserMessage(new UserMessage(request.getUserPrompt()))
                .turnId(request.getTurnId())
                .windowTurns(request.getWindowTurns())
                .textMemoryId(request.getTextMemoryId())
                .strictTextMemory(request.isStrictTextMemory())
                .docMemoryIds(request.getDocMemoryIds() == null ? new ArrayList<>() : new ArrayList<>(request.getDocMemoryIds()))
                .otherFragments(request.getOtherFragments() == null ? new ArrayList<>() : new ArrayList<>(request.getOtherFragments()))
                .contextSource(request.getContextSource())
                .contextQuery(request.getContextQuery())
                .callPermit(request.getCallPermit())
                .toolDefinitions(toolDefs)
                .budgetPolicy(effectivePolicy)
                .build();
    }

    private ChatClient buildClient(ChatModel chatModel, ContextEnvelopeBO envelope) {
        ToolCallingManager recordingManager = new RecordingToolCallingManager(
                DefaultToolCallingManager.builder().build(), envelope);
        var toolAdvisorBuilder = ToolCallingAdvisor.builder()
                .advisorOrder(TOOL_ADVISOR_ORDER)
                .toolCallingManager(recordingManager);
        return ChatClient.builder(chatModel, ObservationRegistry.NOOP, null, null, toolAdvisorBuilder)
                .defaultAdvisors(
                        contextInitAdvisor,
                        sessionHistoryAdvisor,
                        textMemoryAdvisor,
                        docMemoryAdvisor,
                        othersContextAdvisor,
                        contextAssemblyAdvisor)
                .build();
    }

    private static ChatClient.ChatClientRequestSpec configureSpec(ChatClient client,
                                                                   AgentContextCallBO request,
                                                                   ContextEnvelopeBO envelope) {
        ChatClient.ChatClientRequestSpec spec = client.prompt()
                .system(request.getSystemPrompt())
                .user(request.getUserPrompt())
                .advisors(a -> a.param(ContextEnvelopeBO.CONTEXT_KEY, envelope));
        if (request.getToolCallbacks() != null && !request.getToolCallbacks().isEmpty()) {
            if (!(request.getChatModel().getOptions() instanceof ToolCallingChatOptions)) {
                spec.options(ToolCallingChatOptions.builder());
            }
            spec.toolCallbacks(request.getToolCallbacks().toArray(ToolCallback[]::new));
        }
        return spec;
    }

    private static ContextAssemblyReportVO extractReport(ChatClientResponse response) {
        if (response == null || response.context() == null) {
            return null;
        }
        Object raw = response.context().get(ContextEnvelopeBO.REPORT_KEY);
        return raw instanceof ContextAssemblyReportVO report ? report : null;
    }

    private static String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private static final class RecordingToolCallingManager implements ToolCallingManager {
        private final ToolCallingManager delegate;
        private final ContextEnvelopeBO envelope;

        private RecordingToolCallingManager(ToolCallingManager delegate, ContextEnvelopeBO envelope) {
            this.delegate = delegate;
            this.envelope = envelope;
        }

        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
            return delegate.resolveToolDefinitions(chatOptions);
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
            ToolExecutionResult result = delegate.executeToolCalls(prompt, chatResponse);
            List<Message> fullHistory = result.conversationHistory();
            int baseCount = prompt.getInstructions().size();
            if (fullHistory.size() > baseCount) {
                envelope.getToolLoopMessages().addAll(fullHistory.subList(baseCount, fullHistory.size()));
            }
            return result;
        }
    }
}
