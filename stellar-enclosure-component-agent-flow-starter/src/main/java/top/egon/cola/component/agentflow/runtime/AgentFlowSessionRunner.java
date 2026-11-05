package top.egon.cola.component.agentflow.runtime;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.apps.ResumabilityConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.plugins.Plugin;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Native ADK Runner with a host-provided session service and the existing Agent Flow cancellation guard. */
public final class AgentFlowSessionRunner extends Runner {
    private final AgentFlowInMemoryRunner.RunnerLifecycle lifecycle =
            new AgentFlowInMemoryRunner.RunnerLifecycle(() -> super.close());

    private final boolean explicitResume;

    public AgentFlowSessionRunner(BaseAgent agent, String appName, BaseSessionService sessions, List<Plugin> plugins) {
        this(agent, appName, sessions, plugins, false);
    }

    /** Opts a trusted host into explicit native invocation continuation. */
    public static AgentFlowSessionRunner withExplicitResume(BaseAgent agent, String appName,
                                                            BaseSessionService sessions, List<Plugin> plugins) {
        return new AgentFlowSessionRunner(agent, appName, sessions, plugins, true);
    }

    private AgentFlowSessionRunner(BaseAgent agent, String appName, BaseSessionService sessions, List<Plugin> plugins,
                                   boolean explicitResume) {
        super(agent, appName, new InMemoryArtifactService(), Objects.requireNonNull(sessions, "sessions"),
                new InMemoryMemoryService(), AgentFlowInMemoryRunner.withCancellationPlugin(plugins), null, null,
                explicitResume ? ResumabilityConfig.builder().resumable(true).plainTextContinuationAutoResume(false).build() : null);
        this.explicitResume = explicitResume;
    }

    @Override
    public Flowable<Event> runAsync(String userId, String sessionId, Content message, RunConfig config,
                                    Map<String, Object> stateDelta) {
        return lifecycle.runAsync(() -> super.runAsync(userId, sessionId, message, config, stateDelta));
    }

    @Override
    public Flowable<Event> runAsync(String userId, String sessionId, String invocationId, Content message,
                                    RunConfig config, Map<String, Object> stateDelta) {
        if (!explicitResume) return super.runAsync(userId, sessionId, invocationId, message, config, stateDelta);
        if (userId == null || userId.isBlank() || sessionId == null || sessionId.isBlank()
                || invocationId == null || invocationId.isBlank() || invocationId.length() > 128
                || message != null || config == null || config.autoCreateSession()
                || stateDelta != null && !stateDelta.isEmpty()) {
            return Flowable.error(new IllegalArgumentException("Explicit Agent Flow resume requires a stored invocation without new input"));
        }
        // ADK's explicit overload bypasses the protected fresh-invocation path.
        return lifecycle.runAsync(() -> lifecycle.runAsyncImpl(
                () -> super.runAsync(userId, sessionId, invocationId, null, config, null)));
    }

    @Override
    protected Flowable<Event> runAsyncImpl(Session session, Content message, RunConfig config,
                                           Map<String, Object> stateDelta) {
        return lifecycle.runAsyncImpl(() -> super.runAsyncImpl(session, message, config, stateDelta));
    }

    @Override
    public Completable close() { return lifecycle.close(); }
}
