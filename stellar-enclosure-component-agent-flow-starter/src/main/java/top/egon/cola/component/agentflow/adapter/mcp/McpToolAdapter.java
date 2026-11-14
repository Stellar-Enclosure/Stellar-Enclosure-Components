package top.egon.cola.component.agentflow.adapter.mcp;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.execution.AgentExecutionCommand;
import top.egon.cola.component.agentflow.execution.DefaultAgentExecutor.ExecutionInvocation;
import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** A fixed MCP protocol exchange. Network policy, transport ownership and credentials belong to the registering host. */
@Slf4j
@Validated
public class McpToolAdapter {

    private static final String VERSION = "2025-11-25";
    private static final int MAX_BYTES = 64 * 1024;
    private static final byte[] EMPTY = new byte[0];
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(MAX_BYTES)
                    .maxNameLength(128).maxNumberLength(32).maxTokenCount(8192).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };

    @FunctionalInterface
    public interface ScopeAuthority {
        void recheck(ContextScopeVO scope, Instant deadline);
    }

    @FunctionalInterface
    public interface Exchange {
        /** Serve precisely the supplied URI and absolute deadline. Redirects must be disabled. */
        ResponseVO execute(RequestVO request);
    }

    public record RegistrationVO(String capabilityRef, URI endpoint, String tool, boolean write,
                                 ScopeAuthority authority, Consumer<Map<String, Object>> verifyInput,
                                 Consumer<Map<String, Object>> verifyOutput, Exchange exchange) {

        public RegistrationVO {
            ContextScopeVO.requireIdentifier(capabilityRef);
            if (endpoint == null || !"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                    || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                    || endpoint.getPort() == 0 || endpoint.getPort() > 65_535 || !endpoint.normalize().equals(endpoint)
                    || tool == null || !tool.matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}")) {
                throw new IllegalArgumentException("Invalid fixed MCP registration");
            }
            Objects.requireNonNull(authority, "MCP scope authority");
            Objects.requireNonNull(verifyInput, "MCP input schema");
            Objects.requireNonNull(verifyOutput, "MCP output schema");
            Objects.requireNonNull(exchange, "MCP transport");
        }

        @Override
        public String toString() {
            return "McpRegistrationVO[redacted]";
        }
    }

    public record RequestVO(URI endpoint, String method, String sessionId, Instant deadline, byte[] body) {

        public RequestVO {
            body = body.clone();
        }

        public String protocolVersion() {
            return VERSION;
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public String toString() {
            return "McpRequestVO[redacted]";
        }
    }

    public record ResponseVO(int status, String sessionId, byte[] body) {

        public ResponseVO {
            if (body == null || body.length > MAX_BYTES) throw failure("MCP_RESPONSE_INVALID");
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public String toString() {
            return "McpResponseVO[redacted]";
        }
    }

    public Map<String, Object> call(@NotNull ExecutionInvocation invocation, @NotNull @Valid RegistrationVO registration,
                                    @NotNull Map<String, Object> arguments) {
        return call(invocation, registration, arguments, () -> { }, () -> { });
    }

    public Map<String, Object> call(ExecutionInvocation invocation, RegistrationVO registration,
                                    Map<String, Object> arguments, Runnable beforeDispatch, Runnable afterResponse) {
        Objects.requireNonNull(beforeDispatch, "MCP dispatch observation");
        Objects.requireNonNull(afterResponse, "MCP response observation");
        var input = AgentExecutionCommand.JsonValues.copyObject(arguments, MAX_BYTES);
        registration.verifyInput().accept(input);
        var init = request("initialize", 1, Map.of("protocolVersion", VERSION, "capabilities", Map.of(),
                "clientInfo", Map.of("name", "egon-agent-flow", "version", "1.0")));
        byte[] notification = request("notifications/initialized", null, Map.of());
        byte[] call = request("tools/call", 2, Map.of("name", registration.tool(), "arguments", input));
        return invocation.toolCallWithDispatch(registration.capabilityRef(), registration.write(), dispatch -> {
            String session = null;
            Instant deadline = dispatch.deadline();
            try {
                authorize(invocation, registration, deadline);
                var initialized = exchange(registration, "initialize", null, deadline, init);
                if (initialized.status() != 200) throw failure("MCP_RESPONSE_INVALID");
                session = sessionId(initialized.sessionId());
                verifyInitialize(envelope(initialized, 1, session));
                authorize(invocation, registration, deadline);
                requireEmpty(exchange(registration, "notifications/initialized", session, deadline, notification), 202, session);
                authorize(invocation, registration, deadline);
                beforeDispatch.run();
                dispatch.beforeSend();
                try {
                    var output = toolResult(envelope(exchange(registration, "tools/call", session, deadline, call), 2, session));
                    registration.verifyOutput().accept(output);
                    afterResponse.run();
                    return output;
                } catch (RuntimeException uncertain) {
                    throw failure("MCP_TOOL_OUTCOME_UNKNOWN");
                }
            } finally {
                if (session != null) {
                    try {
                        requireEmpty(exchange(registration, "DELETE", session, deadline, EMPTY), 204, session);
                    } catch (RuntimeException unavailable) {
                        log.debug("MCP session cleanup could not be confirmed");
                    }
                }
            }
        });
    }

    private static void authorize(ExecutionInvocation invocation, RegistrationVO registration, Instant deadline) {
        invocation.requireCurrentCapability(registration.capabilityRef(), "TOOL_CALL");
        registration.authority().recheck(invocation.scope(), deadline);
        invocation.requireRunning();
    }

    private static ResponseVO exchange(RegistrationVO registration, String method, String session, Instant deadline, byte[] body) {
        return Objects.requireNonNull(registration.exchange().execute(new RequestVO(registration.endpoint(), method, session, deadline, body)));
    }

    private static byte[] request(String method, Integer id, Map<String, Object> params) {
        var root = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", method);
        if (id != null) root.put("id", id);
        if (!params.isEmpty()) root.set("params", JSON.valueToTree(params));
        try {
            byte[] bytes = JSON.writeValueAsBytes(root);
            if (bytes.length > MAX_BYTES) throw failure("MCP_REQUEST_INVALID");
            return bytes;
        } catch (java.io.IOException invalid) {
            throw failure("MCP_REQUEST_INVALID");
        }
    }

    private static JsonNode envelope(ResponseVO response, int id, String session) {
        if (response.status() != 200 || !session.equals(response.sessionId())) throw failure("MCP_RESPONSE_INVALID");
        try {
            var root = JSON.readTree(response.body());
            if (!shape(root, Set.of("jsonrpc", "id", "result")) || !"2.0".equals(root.path("jsonrpc").textValue())
                    || !root.path("id").isIntegralNumber() || !root.path("id").canConvertToInt() || root.path("id").intValue() != id
                    || !root.path("result").isObject()) throw failure("MCP_RESPONSE_INVALID");
            return root.path("result");
        } catch (java.io.IOException malformed) {
            throw failure("MCP_RESPONSE_INVALID");
        }
    }

    private static void verifyInitialize(JsonNode result) {
        var capabilities = result.path("capabilities");
        var server = result.path("serverInfo");
        if (!fields(result, Set.of("protocolVersion", "capabilities", "serverInfo"), Set.of("instructions", "_meta"))
                || !VERSION.equals(result.path("protocolVersion").textValue()) || !shape(capabilities, Set.of("tools"))
                || !fields(capabilities.path("tools"), Set.of(), Set.of("listChanged"))
                || capabilities.path("tools").has("listChanged") && !capabilities.path("tools").path("listChanged").isBoolean()
                || !fields(server, Set.of("name", "version"), Set.of("title", "description", "websiteUrl", "icons"))
                || !identifier(server.path("name")) || !identifier(server.path("version"))
                || result.has("instructions") && !result.path("instructions").isTextual()
                || !optionalMetadata(result)) {
            throw failure("MCP_PROTOCOL_UNAVAILABLE");
        }
    }

    private static Map<String, Object> toolResult(JsonNode result) {
        if (!fields(result, Set.of("structuredContent", "isError"), Set.of("content", "_meta"))
                || !optionalContent(result)
                || !optionalMetadata(result) || !result.path("structuredContent").isObject()
                || !result.path("isError").isBoolean() || result.path("isError").booleanValue()) throw failure("MCP_RESPONSE_INVALID");
        return AgentExecutionCommand.JsonValues.copyObject(JSON.convertValue(result.path("structuredContent"), OBJECT), MAX_BYTES);
    }

    private static boolean shape(JsonNode node, Set<String> fields) {
        return node != null && node.isObject() && node.size() == fields.size() && fields.stream().allMatch(node::has);
    }

    private static boolean fields(JsonNode node, Set<String> required, Set<String> optional) {
        if (node == null || !node.isObject() || !required.stream().allMatch(node::has)) return false;
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!required.contains(field) && !optional.contains(field)) return false;
        }
        return true;
    }

    private static boolean optionalContent(JsonNode node) {
        if (!node.has("content")) return true;
        var content = node.path("content");
        if (!content.isArray()) return false;
        for (JsonNode item : content) {
            if (!item.isObject()) return false;
        }
        return true;
    }

    private static boolean optionalMetadata(JsonNode node) {
        return !node.hasNonNull("_meta") || node.path("_meta").isObject();
    }

    private static boolean identifier(JsonNode value) {
        return value.isTextual() && !value.textValue().isBlank() && value.textValue().length() <= 128
                && value.textValue().chars().noneMatch(Character::isISOControl);
    }

    private static String sessionId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.~-]{1,256}")) throw failure("MCP_SESSION_INVALID");
        return value;
    }

    private static void requireEmpty(ResponseVO response, int status, String session) {
        if (response.status() != status || response.body().length != 0
                || response.sessionId() != null && !session.equals(response.sessionId())) throw failure("MCP_RESPONSE_INVALID");
    }

    private static CommonException failure(String code) {
        return new CommonException(ResultCode.SYSTEM_ERROR.getCode(), code, code);
    }
}
