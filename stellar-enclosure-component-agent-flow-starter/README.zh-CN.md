# Stellar-Enclosure Agent Flow Starter

[English](README.md) | [中文](README.zh-CN.md)

## 简介

`stellar-enclosure-component-agent-flow-starter` 是一个扁平化的 Spring Boot 组件，用于按配置编译和运行 Agent Flow 树。它把宿主提供的 Spring AI `ChatModel` 与 Google ADK 的 `LlmAgent`、`SequentialAgent`、`ParallelAgent`、`LoopAgent` 和 `InMemoryRunner` 组合起来，不引入参考项目的 DDD 包结构。

组件负责配置校验、ADK 图编译、不可变运行时 Registry、进程内 Session、按 tuple 的执行互斥、同步事件收集、流式 cancel 和有界 close。宿主应用负责 provider 实现、凭据、工具授权、MCP、HTTP、认证和持久化决策。

## 版本矩阵

| 要求 | 版本或边界 |
|---|---|
| Java | 25 |
| Spring Boot | 4.1.2 |
| Spring AI | 2.0.2 |
| Google ADK | 1.13.0 |
| Session 存储 | `AgentFlowService` 使用 ADK `InMemoryRunner`；宿主也可向 `AgentFlowSessionRunner` 提供 ADK `BaseSessionService` |

组件不会带入 Spring AI provider starter 或 provider 凭据。宿主需要提供一个或多个具名 `ChatModel` Bean。

## 依赖方式

导入 Components BOM，再加入唯一的 Agent Flow starter：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>top.egon</groupId>
            <artifactId>stellar-enclosure-components-bom</artifactId>
            <version>${stellar-enclosure.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>top.egon</groupId>
        <artifactId>stellar-enclosure-component-agent-flow-starter</artifactId>
    </dependency>
</dependencies>
```

Starter 默认为关闭状态。仅添加依赖不会创建 Agent Flow 运行时 Bean。

## 宿主提供 ChatModel

provider 相关的应用配置必须留在宿主中，Agent Flow 配置只引用 Bean 名称：

```java
@Bean("researchChatModel")
ChatModel researchChatModel(ChatModel hostProviderChatModel) {
    return hostProviderChatModel;
}
```

示例故意不包含 provider URL、API key 或厂商 starter。配置中的 `chat-model-bean-name` 必须能解析到这个具名 Bean。

## 配置

静态流程配置前缀是 `egon.cola.component.agent-flow`。Binder 使用严格模式：未知键、类型错误、非法 duration、缺失模型 Bean、重复节点、环、多个父节点、不可达节点和非法 root 都会在应用启动时失败，不发布部分 Registry。

自行管理定义和执行生命周期的宿主可以启用 `egon.cola.component.agent-flow.enabled`，并设置 `egon.cola.component.agent-flow-registry.enabled=false`，只获取编译器、校验器和具名模型映射，不创建静态 Registry 或 `AgentFlowService`。该模式下静态 `flows` 必须为空；宿主负责逐个校验定义以及 Runner、Session、授权和资源生命周期。Registry 默认仍然启用，并要求至少一个有效的静态流程。

```yaml
egon:
  cola:
    component:
      agent-flow:
        enabled: true
        execution-timeout: 2m
        shutdown-timeout: 10s
        flows:
          research-flow:
            chat-model-bean-name: researchChatModel
            model-name: research-model
            root-agent-name: planner
            agents:
              - name: planner
                description: 规划研究工作
                instruction: Produce a concise research plan.
                output-key: plan
              - name: writer
                description: 撰写最终答案
                instruction: Turn the collected findings into a cited answer.
                output-key: answer
            workflows:
              - type: SEQUENTIAL
                name: research-sequence
                description: 先规划再写作
                sub-agent-names: [planner, writer]
```

通过 `root-agent-name` 可以选择以下四种形状：

| 形状 | 配置方式 |
|---|---|
| 单叶子 | Root 指向一个 `agents` 项，`workflows` 为空。 |
| Sequential | Root 指向 `SEQUENTIAL` workflow，并按顺序填写 `sub-agent-names`。 |
| Parallel | Root 指向 `PARALLEL` workflow，子 Agent 可并行执行。 |
| Loop | Root 指向 `LOOP` workflow；`max-iterations` 在 LOOP 下可省略，默认为 `3`，取值范围 1-100。 |

除 root 外，每个节点必须恰好有一个父节点。Workflow 不能引用自身、未知节点或重复的子节点。

## Java API

`AgentFlowService` 是内部 Java API，不是 HTTP Controller，也不是 MCP endpoint。它提供五个操作：`listFlows`、`createSession`、`deleteSession`、`execute` 和 `executeStream`。

```java
AgentFlowSessionResult session = agentFlowService.createSession(
        new AgentFlowSessionCommand("research-flow", "tenant-a:user-42", null));

List<Event> events = agentFlowService.execute(new AgentFlowExecutionCommand(
        "research-flow",
        "tenant-a:user-42",
        session.sessionId(),
        Content.fromParts(Part.fromText("Research the selected topic."))));

Flowable<Event> stream = agentFlowService.executeStream(new AgentFlowExecutionCommand(
        "research-flow",
        "tenant-a:user-42",
        session.sessionId(),
        Content.fromParts(Part.fromText("Continue with the source comparison."))));

agentFlowService.deleteSession(new AgentFlowSessionCommand(
        "research-flow", "tenant-a:user-42", session.sessionId()));
```

`execute` 使用非流式模型调用，并按 ADK 发出顺序返回不可变的 `List<Event>`。`executeStream` 请求 ADK SSE 模式，要求宿主模型支持 `ChatModel.stream`；不支持时不会静默重试成另一次模型调用。它是惰性的：订阅之前不会查询 Session、获取 guard 或调用模型，每次订阅都代表一次执行。

拥有其他 ADK `BaseSessionService` 的宿主可以直接构造 `AgentFlowSessionRunner(rootAgent, appName, sessionService, plugins)`。它复用 `AgentFlowInMemoryRunner` 的取消与追踪传播，Session 授权、并发、持久化和 Runner 生命周期由宿主负责；Artifact 与 Memory 仍使用内存默认实现。该构造入口不会改变默认 `AgentFlowService` Registry，也不会自动安装数据库适配器。

流式文本先发出 partial 增量，再发出完整 final 响应。消费者可以追加 partial 增量，随后将 final 视为替换快照，而不是再次追加。子 Agent 的 final 不代表整个工作流已结束。适配器保留已报告的 usage（包括只有 usage 的尾帧）；provider 的 `Usage` 返回 null 时会保留缺失，但若 provider 已把未知计数转换为零，组件无法区分。

当具名 `ChatModel` 的默认 `ToolCallingChatOptions` 包含 `ToolCallback` 时，starter 在构建 Flow 时将这些回调快照并注册为 ADK 原生工具。适配器在内部要求原生 ADK 工具上下文，但调用宿主回调前会移除保留的 `adk_tool_context` 条目；字符串结果以 `{"result": "..."}` 返回。Spring AI MCP 回调可能将其他配置的工具上下文值作为远程元数据，宿主必须控制这些值。工具循环由 ADK 执行，并保留 `returnDirect` 语义。宿主仍需为每个工具执行授权与治理；修改回调列表后需要重新构建 Flow。

## 执行与生命周期语义

- 执行 guard 的 key 是 `(flowId, userId, sessionId)`。相同 tuple 互斥，不同 tuple 可以并行。
- `AgentFlowSessionBusyException` 立即失败。delete 和 execute 使用同一个 guard，避免检查后再操作的竞态。
- `execution-timeout` 是 Session 校验后的执行总 deadline。超时抛出 `AgentFlowExecutionTimeoutException`，dispose 上游订阅并释放 tuple lease。
- 调用方 cancel 会 dispose 流并释放 lease，不伪造 error。starter 的 `AgentFlowInMemoryRunner` 会跨 ADK 缓存步骤通知正在运行的模型订阅；直接创建原生 ADK `InMemoryRunner` 不包含这层传播。上游失败会包装为保留 cause 的 `AgentFlowExecutionException`。
- 正常完成或失败时，组件在通知终态订阅者之前释放 Session lease，终态回调可以删除该 Session。cancel 仍先 dispose 上游，再最终释放 lease。
- 取消和超时不能撤销已经开始的工具或远程请求。流终止时 tuple lease 就会释放；若外部工作忽略中断而继续，宿主必须自行保护重入和业务副作用。
- 适配器有界地请求模型帧；累计超过 100,000 帧或 4,000,000 个文本/工具参数 UTF-16 代码单元时明确失败。ADK 内部步骤缓存和 provider 缓冲仍可能在下游消费缓慢时保留数据，这不是端到端背压或总内存上限。流式工具调用 ID 缺失或重复时，在执行工具前失败。
- 自定义 `InMemoryRunner` 子类需要实现接受 `RunConfig` 的重载才能自定义流式执行；同步 `execute` 仍使用原来的三个参数重载。
- 组件没有自动重试（`no retry`），重试策略和幂等决策由宿主负责。
- 默认 `AgentFlowService` 的 Session 是进程内 in-memory 状态，不持久化、不复制，进程重启后不能恢复；宿主提供的 session service 自行定义存储保证。timeout 或 cancel 不会回滚 ADK 已接受的事件。
- `close` 把 guard 切换到 closing，拒绝新操作，在 `shutdown-timeout` 内等待，然后尝试关闭全部 Runner，并取消仍在运行的 starter 模型订阅。它是幂等的，超时后不宣称优雅完成。

## 边界与日志

扁平 starter 只拥有 Agent Flow 能力：

```text
configuration -> validation -> ADK graph compilation -> registry -> session execution
```

它不提供 provider 集成、HTTP、MCP、database、UI、持久化 Session 或远程恢复协议。宿主拥有这些边界，并负责提供可信的 `userId`。组件日志只记录安全的 stage/outcome/error-type 和 event-count，不记录消息内容、instruction、凭据、`userId` 或 `sessionId`。生产环境应审查或关闭 provider/ADK debug 日志，因为第三方 observability 可能看到更宽的内容范围。

组件异常位于 `top.egon.cola.component.agentflow.common.exception`，统一继承 common-core 的 `CommonException`，因此各 starter 的 `getCode()`、`getStatus()` 与 `isRetryable()` 保持一致。Starter 仅在应用尚未定义时发布规范的 `egonColaValidationUtils` Bean，并注入 `AgentFlowConfigValidator`；该 validator 继承 `BaseValidator`，在原生字段约束之后继续执行自己的图规则校验。

## 升级与验证门禁

当前兼容线是 Java 25 + Spring Boot 4.1.2 + Spring AI 2.0.2 + Google ADK 1.13.0。升级 Spring AI 或 ADK 后必须重新执行 focused tests、runtime dependency tree 检查和 Components reactor 测试。没有新的 Spec，不要向 starter 加入 `google-adk-dev`、provider starter、Web、MCP、JDBC 或 Flyway 依赖。

更新后的 starter 使用 `0.0.2-SNAPSHOT` 进行源码 reactor 开发；现有 Components BOM 仍选择 `0.0.1`。消费者需要在新版发布后显式选择该版本，或在同一源码 reactor 中构建。`AgentFlowFactory.compileRoot` 可以只编译已校验配置而不创建 Runner，并要求调用方明确提供 ADK 工具列表；工具授权和 Runner 生命周期由调用方负责。

配置构建的并行 Agent 使用上下文感知调度器，使运行 Trace 与取消信号传到分支订阅。受信 Code-First 并行 Agent 必须使用 `AgentFlowSchedulers.contextAwareIo()` 或通过 `contextAware` 包装其调度器；独立配置的原生调度器不具有此保证。Provider 自己调度到其他线程的工作仍需自行传播上下文。

```bash
./mvnw -B -ntp -pl stellar-enclosure-component-agent-flow-starter -am test
```
