package top.egon.cola.component.agentflow.workflow;

import com.google.adk.agents.BaseAgent;
import top.egon.cola.component.agentflow.config.AgentWorkflowConfigDTO;

import java.util.List;

/**
 * 静态工作流组合策略契约。
 *
 * <p>Builds one supported Google ADK composite workflow type.</p>
 *
 * @deprecated 自 0.0.1 起标记软弃用，请迁移至 {@code tianji-service} 的 Flowable 流程编排；
 *             本接口仅保留用于存量静态配置兼容。
 */
@Deprecated(since = "0.0.1", forRemoval = false)
public interface AgentWorkflowBuilderStrategy {

    boolean supports(AgentWorkflowConfigDTO workflow);

    BaseAgent build(AgentWorkflowConfigDTO workflow, List<? extends BaseAgent> children);
}
