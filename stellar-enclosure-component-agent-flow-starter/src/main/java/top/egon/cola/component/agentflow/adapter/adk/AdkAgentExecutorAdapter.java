package top.egon.cola.component.agentflow.adapter.adk;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.springai.MessageConverter;
import com.google.adk.plugins.Plugin;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.agentflow.adapter.mcp.McpToolAdapter;
import top.egon.cola.component.agentflow.adapter.springai.SpringAiExecutionModelAdapter;
import top.egon.cola.component.agentflow.adapter.springai.SpringAiExecutionModelAdapter.ModelRegistrationVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanCompiler;
import top.egon.cola.component.agentflow.agentic.AgenticPlanCompiler.LeafOutcomeVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanCompiler.TransferVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO;
import top.egon.cola.component.agentflow.agentic.AgenticPlanVO.DependencyRefVO;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand.TemplateVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionEvent.TypeEnum;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionResult;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionResult.SideEffectOutcomeEnum;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionResult.StatusEnum;
import top.egon.cola.component.agentflow.execution.DefaultAgentExecutor.ExecutionAdapter;
import top.egon.cola.component.agentflow.execution.DefaultAgentExecutor.ExecutionInvocation;
import top.egon.cola.component.agentflow.runtime.AgentFlowSessionRunner;
import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Runs each compiled leaf through a fresh ADK root. The compiler owns all inter-leaf control flow. */
@Slf4j
@Validated
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class AdkAgentExecutorAdapter implements ExecutionAdapter {

    private static final String APP = "egon_agent_execution";
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(8192)
                    .maxNameLength(128).maxNumberLength(32).maxTokenCount(8192).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };

    @Qualifier("agenticPlanCompiler")
    private final AgenticPlanCompiler compiler;

    @Qualifier("springAiExecutionModelAdapter")
    private final SpringAiExecutionModelAdapter models;

    @Qualifier("mcpToolAdapter")
    private final McpToolAdapter mcp;

    @Qualifier("agentExecutionTemplateResolver")
    private final TemplateResolver templates;

    @FunctionalInterface
    public interface TemplateResolver {
        /** Resolve only the complete published identity from trusted, current host registration. */
        RegisteredTemplateVO resolve(TemplateVO expected, ContextScopeVO scope);
    }

    @FunctionalInterface
    public interface HostTool {
        Map<String, Object> call(Map<String, Object> arguments);
    }

    @FunctionalInterface
    public interface LeafDecision {
        /** Trusted host evidence of root completion or an exact handoff. */
        LeafDecisionVO afterRoot(ExecutionInvocation invocation, String agentKey, int round);
    }

    public record LeafDecisionVO(boolean rootTerminal, TransferVO transfer) { }

    public record RegisteredToolVO(DependencyRefVO reference, FunctionDeclaration declaration, boolean write,
                                   Consumer<Map<String, Object>> verifyHostInput,
                                   Consumer<Map<String, Object>> verifyHostOutput,
                                   HostTool hostTool, McpToolAdapter.RegistrationVO mcpTool) {

        public RegisteredToolVO {
            Objects.requireNonNull(reference, "tool reference");
            if (declaration == null || declaration.name().isEmpty()
                    || !declaration.name().orElseThrow().matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}")
                    || (hostTool == null) == (mcpTool == null)
                    || hostTool != null && (verifyHostInput == null || verifyHostOutput == null)
                    || mcpTool != null && (verifyHostInput != null || verifyHostOutput != null || write != mcpTool.write()
                        || !reference.key().equals(mcpTool.capabilityRef()) || !declaration.name().orElseThrow().equals(mcpTool.tool()))) {
                throw new IllegalArgumentException("Invalid registered ADK tool");
            }
        }

        @Override
        public String toString() {
            return "RegisteredToolVO[redacted]";
        }
    }

    public record RegisteredLeafVO(DependencyRefVO instructionRef, String instruction, String modelRef,
                                   ModelRegistrationVO model, List<RegisteredToolVO> tools, LeafDecision decision) {

        public RegisteredLeafVO {
            Objects.requireNonNull(instructionRef, "instruction reference");
            ContextScopeVO.requireIdentifier(modelRef);
            Objects.requireNonNull(model, "registered model");
            Objects.requireNonNull(decision, "trusted leaf decision");
            if (!modelRef.equals(model.capabilityRef()) || instruction == null || instruction.isBlank()
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(instruction)
                    || instruction.getBytes(StandardCharsets.UTF_8).length > AgentExecutionCommand.MAX_INPUT_BYTES) {
                throw new IllegalArgumentException("Invalid registered ADK instruction");
            }
            tools = List.copyOf(tools);
            if (tools.size() > 32 || tools.stream().map(value -> value.declaration().name()).distinct().count() != tools.size()
                    || tools.stream().map(RegisteredToolVO::reference).distinct().count() != tools.size()) {
                throw new IllegalArgumentException("Invalid registered ADK tools");
            }
        }

        @Override
        public String toString() {
            return "RegisteredLeafVO[redacted]";
        }
    }

    public record RegisteredTemplateVO(String key, long version, String contentSha256, AgenticPlanVO plan,
                                       Map<String, RegisteredLeafVO> leaves, Map<String, String> sharedCapabilities) {

        public RegisteredTemplateVO {
            ContextScopeVO.requireIdentifier(key);
            if (version < 1 || version > 999_999_999_999_999L || contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid registered ADK template");
            }
            Objects.requireNonNull(plan, "registered plan");
            leaves = Map.copyOf(leaves);
            sharedCapabilities = Map.copyOf(sharedCapabilities);
            if (leaves.size() != plan.agents().size() || !sharedCapabilities.keySet().equals(plan.valuePaths())) {
                throw new IllegalArgumentException("Incomplete registered ADK template");
            }
            sharedCapabilities.values().forEach(ContextScopeVO::requireIdentifier);
            for (var leaf : plan.agents()) {
                var registration = leaves.get(leaf.agentKey());
                if (registration == null || !leaf.instructionRef().equals(registration.instructionRef())
                        || !leaf.modelRef().equals(registration.modelRef())
                        || !leaf.toolRefs().equals(registration.tools().stream().map(RegisteredToolVO::reference).toList())) {
                    throw new IllegalArgumentException("ADK template leaf registration mismatch");
                }
            }
        }

        @Override
        public String toString() {
            return "RegisteredTemplateVO[redacted]";
        }
    }

    @Override
    public AgentExecutionResult execute(ExecutionInvocation invocation) {
        var expected = invocation.request().template();
        var registration = templates.resolve(expected, invocation.scope());
        if (registration == null || !expected.key().equals(registration.key()) || expected.version() != registration.version()
                || !expected.contentSha256().equals(registration.contentSha256())) {
            throw failure("TEMPLATE_REGISTRATION_UNAVAILABLE");
        }
        return compiler.execute(registration.plan(), invocation,
                (leaf, round, shared) -> executeLeaf(invocation, leaf.agentKey(), registration.leaves().get(leaf.agentKey()), round, shared),
                (run, path) -> run.requireCurrentCapability(registration.sharedCapabilities().get(path), "AGENTIC_SHARE"));
    }

    private LeafOutcomeVO executeLeaf(ExecutionInvocation invocation, String key, RegisteredLeafVO registration, int round,
                                      Map<String, Object> shared) {
        invocation.requireRunning();
        invocation.emit(TypeEnum.PROGRESS, Map.of("phase", "LEAF_STARTED", "agentKey", key, "round", round));
        var tools = registration.tools().stream().map(tool -> new GuardedTool(invocation, tool)).toList();
        var root = LlmAgent.builder().name("root_" + key).instruction(registration.instruction())
                .disallowTransferToParent(true).disallowTransferToPeers(true)
                .tools(tools).model(new GovernedModel(invocation, registration.model(), tools)).build();
        var sessions = new InMemorySessionService();
        var identity = new InvocationCapture();
        var runner = new AgentFlowSessionRunner(root, APP, sessions, List.of(identity));
        String sessionId = UUID.randomUUID().toString();
        String userId = invocation.scope().scopeHash();
        var config = RunConfig.builder().autoCreateSession(false).streamingMode(RunConfig.StreamingMode.SSE)
                .toolExecutionMode(RunConfig.ToolExecutionMode.SEQUENTIAL)
                .maxLlmCalls(invocation.request().limits().maxModelCalls()).saveInputBlobsAsArtifacts(false).build();
        var input = AgentExecutionCommand.JsonValues.copyObject(Map.of("input", invocation.request().input(),
                "parameters", invocation.request().parameters(), "shared", shared),
                AgentExecutionCommand.MAX_INPUT_BYTES * 3);
        String json = new String(AgentExecutionCommand.JsonValues.canonicalBytes(input), StandardCharsets.UTF_8);
        try {
            sessions.createSession(APP, userId, Map.of(), sessionId).blockingGet();
            var message = Content.builder().role("user").parts(List.of(Part.fromText(json))).build();
            var output = awaitRoot(runner.runAsync(userId, sessionId, message, config, Map.of()), root.name(), identity.current::get);
            invocation.requireRunning();
            invocation.emit(TypeEnum.PROGRESS, Map.of("phase", "LEAF_FINISHED", "agentKey", key, "round", round));
            var decision = Objects.requireNonNull(registration.decision().afterRoot(invocation, key, round));
            return new LeafOutcomeVO(StatusEnum.COMPLETED, output, null, decision.rootTerminal(), decision.transfer(), SideEffectOutcomeEnum.NONE);
        } finally {
            runner.close().timeout(1, TimeUnit.SECONDS)
                    .andThen(sessions.deleteSession(APP, userId, sessionId))
                    .subscribe(() -> { }, failure -> log.debug("Neutral ADK session cleanup was not confirmed"));
        }
    }

    static Map<String, Object> awaitRoot(Flowable<Event> events, String root, String invocation) {
        return awaitRoot(events, root, () -> invocation);
    }

    private static Map<String, Object> awaitRoot(Flowable<Event> events, String root, Supplier<String> invocation) {
        return events.filter(event -> root.equals(event.author()) && invocation.get() != null
                        && invocation.get().equals(event.invocationId()) && !event.partial().orElse(false)
                        && event.finalResponse() && event.functionCalls().isEmpty() && event.functionResponses().isEmpty())
                .map(AdkAgentExecutorAdapter::structuredOutput).firstElement()
                .switchIfEmpty(Maybe.error(failure("ADK_ROOT_RESULT_MISSING"))).blockingGet();
    }

    private static Map<String, Object> structuredOutput(Event event) {
        if (event.interrupted().orElse(false) || event.errorCode().isPresent() || event.errorMessage().isPresent()) {
            throw failure("ADK_ROOT_RESULT_INVALID");
        }
        var parts = event.content().flatMap(Content::parts).orElse(List.of());
        if (parts.size() != 1 || parts.getFirst().thought().orElse(false) || parts.getFirst().text().isEmpty()) {
            throw failure("ADK_ROOT_RESULT_INVALID");
        }
        String text = parts.getFirst().text().orElseThrow();
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(text)
                || text.getBytes(StandardCharsets.UTF_8).length > AgentExecutionCommand.MAX_OUTPUT_BYTES) {
            throw failure("ADK_ROOT_RESULT_INVALID");
        }
        try {
            return AgentExecutionCommand.JsonValues.copyObject(JSON.readValue(text, OBJECT), AgentExecutionCommand.MAX_OUTPUT_BYTES);
        } catch (java.io.IOException | RuntimeException malformed) {
            throw failure("ADK_ROOT_RESULT_INVALID");
        }
    }

    private static final class InvocationCapture implements Plugin {
        private final AtomicReference<String> current = new AtomicReference<>();

        @Override public String getName() { return "neutral_execution_scope"; }

        @Override
        public Maybe<Content> beforeRunCallback(InvocationContext context) {
            return Maybe.defer(() -> {
                if (!current.compareAndSet(null, Objects.requireNonNull(context.invocationId()))) {
                    return Maybe.error(failure("ADK_INVOCATION_MISMATCH"));
                }
                return Maybe.empty();
            });
        }
    }

    private final class GuardedTool extends BaseTool {
        private final ExecutionInvocation invocation;
        private final RegisteredToolVO tool;

        private GuardedTool(ExecutionInvocation invocation, RegisteredToolVO tool) {
            super(tool.declaration().name().orElseThrow(), tool.declaration().description().orElse(""));
            this.invocation = invocation;
            this.tool = tool;
        }

        @Override
        public Optional<FunctionDeclaration> declaration() {
            return Optional.of(tool.declaration());
        }

        @Override
        public Single<Map<String, Object>> runAsync(Map<String, Object> arguments, ToolContext context) {
            return Single.fromCallable(() -> {
                invocation.requireRunning();
                var input = AgentExecutionCommand.JsonValues.copyObject(arguments, AgentExecutionCommand.MAX_INPUT_BYTES);
                if (tool.mcpTool() != null) {
                    return mcp.call(invocation, tool.mcpTool(), input,
                            () -> invocation.emit(TypeEnum.TOOL_STARTED, Map.of("capabilityRef", tool.reference().key())),
                            () -> invocation.emit(TypeEnum.TOOL_FINISHED, Map.of("capabilityRef", tool.reference().key())));
                }
                tool.verifyHostInput().accept(input);
                return invocation.toolCallWithDispatch(tool.reference().key(), tool.write(), dispatch -> {
                    invocation.emit(TypeEnum.TOOL_STARTED, Map.of("capabilityRef", tool.reference().key()));
                    dispatch.beforeSend();
                    var output = AgentExecutionCommand.JsonValues.copyObject(
                            tool.hostTool().call(input), AgentExecutionCommand.MAX_OUTPUT_BYTES);
                    tool.verifyHostOutput().accept(output);
                    invocation.emit(TypeEnum.TOOL_FINISHED, Map.of("capabilityRef", tool.reference().key()));
                    return output;
                });
            });
        }
    }

    private final class GovernedModel extends BaseLlm {
        private final ExecutionInvocation invocation;
        private final ModelRegistrationVO registration;
        private final Map<String, GuardedTool> allowed;
        private final MessageConverter converter = new MessageConverter(JSON);

        private GovernedModel(ExecutionInvocation invocation, ModelRegistrationVO registration, List<GuardedTool> tools) {
            super(registration.modelName());
            this.invocation = invocation;
            this.registration = registration;
            this.allowed = tools.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(BaseTool::name, tool -> tool));
        }

        @Override
        public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
            return Flowable.fromCallable(() -> {
                if (request.tools().entrySet().stream().anyMatch(tool -> allowed.get(tool.getKey()) != tool.getValue())) {
                    throw failure("ADK_TOOL_UNDECLARED");
                }
                var prompt = converter.toLlmPrompt(request);
                List<ToolDefinition> declarations = prompt.getOptions() instanceof ToolCallingChatOptions options
                        ? options.getToolCallbacks().stream().map(ToolCallback::getToolDefinition).toList() : List.of();
                if (!declarations.stream().map(ToolDefinition::name).collect(java.util.stream.Collectors.toSet()).equals(request.tools().keySet())) {
                    throw failure("ADK_TOOL_UNDECLARED");
                }
                var output = request.config().flatMap(config -> config.maxOutputTokens()).orElse(null);
                var result = models.generate(invocation, registration, prompt.getInstructions(), declarations, output, AgentExecutionCommand.MAX_OUTPUT_BYTES);
                var usage = new java.util.LinkedHashMap<String, Object>();
                usage.put("phase", "MODEL_FINISHED");
                usage.put("inputTokens", result.inputTokens());
                usage.put("outputTokens", result.outputTokens());
                invocation.emit(TypeEnum.PROGRESS, usage);
                var response = converter.toLlmResponse(result.response()).toBuilder().partial(false).turnComplete(true);
                if (result.inputTokens() != null || result.outputTokens() != null) {
                    var use = GenerateContentResponseUsageMetadata.builder();
                    if (result.inputTokens() != null) use.promptTokenCount(result.inputTokens());
                    if (result.outputTokens() != null) use.candidatesTokenCount(result.outputTokens());
                    response.usageMetadata(use.build());
                }
                return response.build();
            });
        }

        @Override
        public BaseLlmConnection connect(LlmRequest request) {
            throw new UnsupportedOperationException("Live model connections are unavailable");
        }
    }

    private static CommonException failure(String code) {
        return new CommonException(ResultCode.SYSTEM_ERROR.getCode(), code, code);
    }
}
