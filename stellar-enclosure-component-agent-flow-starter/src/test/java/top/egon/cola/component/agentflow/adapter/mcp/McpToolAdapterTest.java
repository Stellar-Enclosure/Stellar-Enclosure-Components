package top.egon.cola.component.agentflow.adapter.mcp;

import org.junit.jupiter.api.Test;
import top.egon.cola.component.agentflow.adapter.mcp.McpToolAdapter.RegistrationVO;
import top.egon.cola.component.agentflow.adapter.mcp.McpToolAdapter.ResponseVO;
import top.egon.cola.component.agentflow.execution.DefaultAgentExecutor.ExecutionInvocation;
import top.egon.cola.component.agentflow.execution.DefaultAgentExecutor.ToolDispatch;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpToolAdapterTest {

    @Test
    void fixed_method_protocol_and_session_are_closed_and_the_session_is_released() {
        var requests = new ArrayList<McpToolAdapter.RequestVO>();
        var checks = new AtomicInteger();
        var adapter = new McpToolAdapter();
        var result = adapter.call(invocation(), registration(checks, request -> {
            requests.add(request);
            return switch (request.method()) {
                case "initialize" -> response(200, "owned-session", """
                        {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"fixed","version":"1"}}}
                        """);
                case "notifications/initialized" -> response(202, "owned-session", "");
                case "tools/call" -> response(200, "owned-session", """
                        {"jsonrpc":"2.0","id":2,"result":{"structuredContent":{"accepted":true},"isError":false}}
                        """);
                case "DELETE" -> response(204, null, "");
                default -> throw new AssertionError(request.method());
            };
        }), Map.of("number", 42));
        assertEquals(Map.of("accepted", true), result);
        assertEquals(List.of("initialize", "notifications/initialized", "tools/call", "DELETE"),
                requests.stream().map(McpToolAdapter.RequestVO::method).toList());
        assertNull(requests.getFirst().sessionId());
        assertTrue(requests.subList(1, 4).stream().allMatch(request -> "owned-session".equals(request.sessionId())));
        assertEquals(3, checks.get());
        assertTrue(new String(requests.get(2).body(), java.nio.charset.StandardCharsets.UTF_8).contains("\"name\":\"fixed_tool\""));
    }

    @Test
    void a_denied_or_invalid_request_has_no_transport_and_an_unknown_call_is_never_retried() {
        var adapter = new McpToolAdapter();
        assertThrows(IllegalArgumentException.class, () -> new RegistrationVO("fixed-tool", URI.create("http://example.invalid/mcp"),
                "fixed_tool", true, (scope, deadline) -> {}, arguments -> {}, result -> {}, request -> null));
        var count = new AtomicInteger();
        var denied = new RegistrationVO("fixed-tool", URI.create("https://example.invalid/mcp"), "fixed_tool", true,
                (scope, deadline) -> { throw new IllegalStateException("denied"); }, arguments -> {}, result -> {},
                request -> { count.incrementAndGet(); return null; });
        assertThrows(RuntimeException.class, () -> adapter.call(invocation(), denied, Map.of()));
        assertEquals(0, count.get());
        var methods = new ArrayList<String>();
        assertThrows(RuntimeException.class, () -> adapter.call(invocation(), registration(count, request -> {
            methods.add(request.method());
            return switch (request.method()) {
                case "initialize" -> response(200, "owned-session", """
                        {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"fixed","version":"1"}}}
                        """);
                case "notifications/initialized" -> response(202, null, "");
                case "tools/call" -> throw new IllegalStateException("disconnected");
                case "DELETE" -> response(204, null, "");
                default -> throw new AssertionError(request.method());
            };
        }), Map.of("number", 1)));
        assertEquals(1, methods.stream().filter("tools/call"::equals).count());
        assertEquals("DELETE", methods.getLast());
    }

    private static RegistrationVO registration(AtomicInteger checks, McpToolAdapter.Exchange exchange) {
        return new RegistrationVO("fixed-tool", URI.create("https://example.invalid/mcp"), "fixed_tool", true,
                (scope, deadline) -> checks.incrementAndGet(),
                arguments -> { if (!arguments.keySet().equals(java.util.Set.of("number"))) throw new IllegalArgumentException(); },
                result -> { if (!result.keySet().equals(java.util.Set.of("accepted"))) throw new IllegalArgumentException(); }, exchange);
    }

    private static ExecutionInvocation invocation() {
        var result = mock(ExecutionInvocation.class);
        Instant deadline = Instant.now().plusSeconds(60);
        when(result.deadline()).thenReturn(deadline);
        when(result.scope()).thenReturn(new top.egon.cola.component.agentflow.api.context.ContextScopeVO(
                "test", "tenant", "principal", "instance", "session", "mcp"));
        when(result.toolCallWithDispatch(eq("fixed-tool"), eq(true), any())).thenAnswer(call -> {
            var dispatch = mock(ToolDispatch.class);
            when(dispatch.deadline()).thenReturn(deadline);
            return ((Function<ToolDispatch, ?>) call.getArgument(2)).apply(dispatch);
        });
        return result;
    }

    private static ResponseVO response(int status, String session, String body) {
        return new ResponseVO(status, session, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
