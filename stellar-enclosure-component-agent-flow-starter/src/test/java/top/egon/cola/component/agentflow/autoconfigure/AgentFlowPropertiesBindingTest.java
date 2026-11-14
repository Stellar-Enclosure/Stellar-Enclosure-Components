package top.egon.cola.component.agentflow.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentFlowPropertiesBindingTest {

    @Test
    void applies_safe_defaults_when_property_prefix_is_absent() {
        AgentFlowProperties properties = binder(Map.of())
                .bindOrCreate("egon.cola.component.agent-flow", AgentFlowProperties.class);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.executionTimeout()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.shutdownTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.flows()).isEmpty();
        assertThat(properties.session().storeRef()).isEqualTo("inMemory");
        assertThat(properties.memory().mode()).isEqualTo("IN_MEMORY");
        assertThat(properties.rag().enabled()).isFalse();
        assertThat(new AgentFlowProperties(false, null, null, Map.of()).session().storeRef())
                .isEqualTo("inMemory");
        assertThat(new AgentFlowProperties(false, null, null, Map.of(), new AgentFlowProperties.SessionSettings("legacy")).memory()
                .mode()).isEqualTo("IN_MEMORY");
    }

    @Test
    void binds_nested_flow_configuration_without_mutating_order() {
        AgentFlowProperties properties = binder(Map.of(
                        "egon.cola.component.agent-flow.enabled", "true",
                        "egon.cola.component.agent-flow.execution-timeout", "30s",
                        "egon.cola.component.agent-flow.flows.research-flow.chat-model-bean-name", "researchChatModel",
                        "egon.cola.component.agent-flow.flows.research-flow.model-name", "research-model",
                        "egon.cola.component.agent-flow.flows.research-flow.root-agent-name", "planner",
                        "egon.cola.component.agent-flow.flows.research-flow.agents[0].name", "planner",
                        "egon.cola.component.agent-flow.flows.research-flow.agents[0].instruction", "Plan the work.",
                        "egon.cola.component.agent-flow.flows.research-flow.workflows[0].type", "sequential",
                        "egon.cola.component.agent-flow.flows.research-flow.workflows[0].name", "sequence",
                        "egon.cola.component.agent-flow.flows.research-flow.workflows[0].sub-agent-names[0]", "planner"))
                .bind("egon.cola.component.agent-flow", Bindable.of(AgentFlowProperties.class))
                .orElseThrow(() -> new IllegalStateException("properties were not bound"));

        assertThat(properties.executionTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.flows()).containsKey("research-flow");
        assertThat(properties.flows().get("research-flow").agents()).extracting("name")
                .containsExactly("planner");
    }

    @Test
    void rejects_unknown_property_with_strict_bind_handler() {
        assertThatThrownBy(() -> binder(Map.of(
                        "egon.cola.component.agent-flow.enabled", "true",
                        "egon.cola.component.agent-flow.unknown-setting", "value"))
                .bind("egon.cola.component.agent-flow", Bindable.of(AgentFlowProperties.class),
                        new NoUnboundElementsBindHandler(BindHandler.DEFAULT)))
                .isInstanceOf(Exception.class)
                .hasStackTraceContaining("unknown-setting");
    }

    @Test
    void bindsTrustedSessionStoreReferenceAlongsideLegacySettings() {
        AgentFlowProperties properties = binder(Map.of(
                        "egon.cola.component.agent-flow.enabled", "true",
                        "egon.cola.component.agent-flow.session.store-ref", "durableSessions"))
                .bind("egon.cola.component.agent-flow", Bindable.of(AgentFlowProperties.class),
                        new NoUnboundElementsBindHandler(BindHandler.DEFAULT))
                .orElseThrow(() -> new IllegalStateException("properties were not bound"));

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.executionTimeout()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.session().storeRef()).isEqualTo("durableSessions");
    }

    @Test
    void rejectsBlankSessionStoreReference() {
        assertThatThrownBy(() -> binder(Map.of(
                        "egon.cola.component.agent-flow.session.store-ref", " "))
                .bind("egon.cola.component.agent-flow", Bindable.of(AgentFlowProperties.class),
                        new NoUnboundElementsBindHandler(BindHandler.DEFAULT)))
                .hasStackTraceContaining("session store-ref must be a valid bean name");
    }

    @Test
    void binds_new_optional_capabilities_and_existing_session_in_one_strict_prefix() {
        var mode = strict(Map.of(
                "egon.cola.component.agent-flow.enabled", "true",
                "egon.cola.component.agent-flow.session.store-ref", "durableSessions",
                "egon.cola.component.agent-flow.memory.mode", "JDBC",
                "egon.cola.component.agent-flow.memory.data-source-ref", "isolatedMemory",
                "egon.cola.component.agent-flow.memory.schema", "egon_agent_memory",
                "egon.cola.component.agent-flow.rag.enabled", "true"));
        assertThat(mode.session().storeRef()).isEqualTo("durableSessions");
        assertThat(mode.memory().mode()).isEqualTo("JDBC");
        assertThat(mode.memory().dataSourceRef()).isEqualTo("isolatedMemory");
        assertThat(mode.memory().schema()).isEqualTo("egon_agent_memory");
        assertThat(mode.rag().enabled()).isTrue();
    }

    @Test
    void rejects_unknown_nested_keys_modes_and_missing_or_invalid_jdbc_settings() {
        assertThatThrownBy(() -> strict(Map.of("egon.cola.component.agent-flow.memory.unknown-key", "ignored")))
                .hasStackTraceContaining("unknown-key");
        assertThatThrownBy(() -> strict(Map.of("egon.cola.component.agent-flow.rag.unknown-key", "ignored")))
                .hasStackTraceContaining("unknown-key");
        assertThatThrownBy(() -> strict(Map.of("egon.cola.component.agent-flow.memory.mode", "VECTOR")))
                .hasStackTraceContaining("memory mode must be NONE, IN_MEMORY, or JDBC");
        assertThatThrownBy(() -> strict(Map.of("egon.cola.component.agent-flow.memory.mode", "JDBC")))
                .hasStackTraceContaining("data-source-ref");
        assertThatThrownBy(() -> strict(Map.of("egon.cola.component.agent-flow.memory.mode", "JDBC",
                        "egon.cola.component.agent-flow.memory.data-source-ref", "isolatedMemory",
                        "egon.cola.component.agent-flow.memory.schema", "foo.bar")))
                .hasStackTraceContaining("schema");
    }

    private static AgentFlowProperties strict(Map<String, String> values) {
        return binder(values).bind("egon.cola.component.agent-flow", Bindable.of(AgentFlowProperties.class),
                new NoUnboundElementsBindHandler(BindHandler.DEFAULT)).orElseThrow(
                () -> new IllegalStateException("properties were not bound"));
    }

    private static Binder binder(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values));
    }

}
