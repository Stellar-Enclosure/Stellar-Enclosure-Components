package top.egon.cola.component.agentflow.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import top.egon.cola.component.agentflow.config.AgentWorkflowConfigDTO;
import top.egon.cola.component.agentflow.config.AgentWorkflowTypeEnum;
import top.egon.cola.component.agentflow.common.exception.AgentFlowConfigurationException;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 静态工作流策略工厂。
 *
 * <p>Selects the single strategy registered for a validated workflow type.</p>
 *
 * @deprecated 自 0.0.1 起标记软弃用，请迁移至 {@code tianji-service} 的 Flowable 流程编排。
 */
@Deprecated(since = "0.0.1", forRemoval = false)
@RequiredArgsConstructor(onConstructor_ = @Autowired)
@Slf4j
public class AgentWorkflowStrategyFactory {

    @Qualifier("agentWorkflowBuilderStrategies")
    private final List<AgentWorkflowBuilderStrategy> strategies;

    private final AtomicBoolean deprecationWarned = new AtomicBoolean(false);

    public AgentWorkflowBuilderStrategy getStrategy(AgentWorkflowTypeEnum type) {
        Objects.requireNonNull(type, "workflow type must not be null");
        if (deprecationWarned.compareAndSet(false, true)) {
            log.warn("AgentWorkflowStrategyFactory is deprecated; migrate static multi-agent workflows to tianji-service Flowable orchestration");
        }
        log.debug("Selecting Agent Flow workflow strategy for type={}", type);
        AgentWorkflowBuilderStrategy match = null;
        for (AgentWorkflowBuilderStrategy strategy : strategies) {
            if (strategy == null || !strategy.supports(new AgentWorkflowConfigDTO(type, "strategy", null, List.of("child"),
                    type == AgentWorkflowTypeEnum.LOOP ? 1 : null))) {
                continue;
            }
            if (match != null) {
                throw new AgentFlowConfigurationException("properties", type.name(), "duplicate workflow strategy");
            }
            match = strategy;
        }
        if (match == null) {
            throw new AgentFlowConfigurationException("properties", type.name(), "unsupported workflow type");
        }
        return match;
    }
}
