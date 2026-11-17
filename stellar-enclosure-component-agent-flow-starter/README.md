# Stellar-Enclosure Agent Flow Starter

[English](README.md) | [中文](README.zh-CN.md)

## Overview

`stellar-enclosure-component-agent-flow-starter` is a flat Spring Boot component for compiling and running configuration-driven Agent Flow trees. It combines Spring AI's host-provided `ChatModel` with Google ADK `LlmAgent`, `SequentialAgent`, `ParallelAgent`, `LoopAgent`, and `InMemoryRunner` without introducing the reference project's DDD packages.

The component owns configuration validation, ADK graph compilation, an immutable runtime registry, process-local sessions, tuple-level execution exclusion, synchronous event collection, streaming cancellation, and bounded close. The host application owns the provider implementation, credentials, tool authorization, MCP, HTTP, authentication, and persistence decisions.

## Version matrix

| Requirement | Version or boundary |
|---|---|
| Java | 25 |
| Spring Boot | 4.1.2 |
| Spring AI | 2.0.2 |
| Google ADK | 1.13.0 |
| Session storage | `AgentFlowService` uses the ADK `InMemoryRunner`; a host may supply an ADK `BaseSessionService` to `AgentFlowSessionRunner` |

Spring AI provider starters and provider credentials are deliberately not included. A host supplies one or more named `ChatModel` beans.

## Dependency setup

Import the Components BOM and add the single Agent Flow starter:

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

The starter is default-off. Adding the dependency alone creates no Agent Flow runtime beans.

## Host-owned ChatModel

The provider-specific application configuration stays in the host. Only the bean name is referenced by Agent Flow configuration:

```java
@Bean("researchChatModel")
ChatModel researchChatModel(ChatModel hostProviderChatModel) {
    return hostProviderChatModel;
}
```

The example intentionally contains no provider URL, API key, or vendor starter. The configured `chat-model-bean-name` must resolve to this named bean.

## Configuration

The static flow configuration prefix is `egon.cola.component.agent-flow`. Binding is strict: unknown keys, invalid types, invalid durations, missing model beans, duplicate nodes, cycles, multiple parents, unreachable nodes, and invalid roots fail application startup before a partial registry is published.

Hosts that own their definitions and execution lifecycle may enable `egon.cola.component.agent-flow.enabled` and set `egon.cola.component.agent-flow-registry.enabled=false` to obtain the compiler, validator and named model map without creating the static registry or `AgentFlowService`. Static `flows` must be empty in this mode; the host validates each definition and owns Runner, session, authorization and resource lifecycles. The registry remains enabled by default and still requires at least one valid static flow.

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
                description: Plans the research work
                instruction: Produce a concise research plan.
                output-key: plan
              - name: writer
                description: Writes the final answer
                instruction: Turn the collected findings into a cited answer.
                output-key: answer
            workflows:
              - type: SEQUENTIAL
                name: research-sequence
                description: Plan then write
                sub-agent-names: [planner, writer]
```

Supported root shapes are represented by `root-agent-name`:

| Shape | Configuration |
|---|---|
| Single leaf | Root names one `agents` entry; `workflows` is empty. |
| Sequential | Root names a `SEQUENTIAL` workflow with ordered `sub-agent-names`. |
| Parallel | Root names a `PARALLEL` workflow with independent children. |
| Loop | Root names a `LOOP` workflow; `max-iterations` is optional there, defaults to `3`, and must be within 1-100. |

Each node has exactly one parent except the root. A workflow cannot refer to itself, an unknown node, or the same child twice.

## Java API

`AgentFlowService` is an internal Java API; it is not an HTTP controller or an MCP endpoint. The five operations are `listFlows`, `createSession`, `deleteSession`, `execute`, and `executeStream`:

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

`execute` makes a non-streaming model call and returns an immutable `List<Event>` in ADK emission order. `executeStream` requests ADK SSE mode and requires a host model that supports `ChatModel.stream`; it does not silently retry an unsupported stream as another model call. It is lazy: no session lookup, guard acquisition, or model call happens until subscription, and every subscription represents one execution.

Hosts that own a different ADK `BaseSessionService` may construct `AgentFlowSessionRunner(rootAgent, appName, sessionService, plugins)` directly. It uses the same cancellation and trace propagation as `AgentFlowInMemoryRunner`; the host owns session authorization, concurrency, persistence and Runner lifecycle. Its artifact and memory services remain in-memory defaults. This constructor does not change the default `AgentFlowService` registry or install a database adapter.

Streamed text events are partial deltas followed by a complete final response. Consumers can append the partial deltas, then treat the final response as a replacement snapshot rather than appending it again. A child Agent's final response does not by itself mean the whole workflow has completed. The adapter preserves reported usage, including a usage-only tail, and leaves counters absent when the provider's `Usage` reports null; it cannot distinguish an unknown count that the provider has already converted to zero.

When a named `ChatModel` supplies `ToolCallback` entries in its default `ToolCallingChatOptions`, the starter snapshots and registers them as native ADK tools while the flow is built. The adapter requires a native ADK tool context internally, but removes the reserved `adk_tool_context` entry before invoking the host callback; its string result is returned as `{"result": "..."}`. Spring AI MCP callbacks can forward other configured tool-context values as remote metadata, so hosts must control those values. The native ADK loop owns tool execution and honors `returnDirect`. The host must authorize and govern each tool; changing the model's callback list requires rebuilding the flow.

## Neutral execution API

Hosts that own their workflow lifecycle can call `AgentExecutor.execute(command, context)`. Build the command from a trusted, fixed template revision and its content digest, schema validator, bounded JSON input and parameters, and technical limits. Build `AgentExecutionContextVO` in process from a verified scope, deadline, cancellation signal, context and session sources, and call-permit provider.

The handle exposes `events()`, `completion()` and `cancel(reason)`. The Flow publisher honors subscriber demand and buffers up to 64 undelivered events per subscriber. Exceeding the bound ends execution with `BACKPRESSURE_LIMIT`. A live subscriber receives one terminal event followed by `onComplete`. The `completion()` stage resolves once with the final result. Pass a nonblank reason to cancel local execution. External effects already dispatched follow the provider's cancellation semantics.

The effective deadline is the earlier of the command's total timeout and the host context deadline. The command also carries positive node, model-idle and tool timeouts, call counts and resource limits. The executor rechecks host permits for protected calls. The default memory mode is `IN_MEMORY`. Set `NONE` to disable memory. Optional JDBC uses `egon.cola.component.agent-flow.memory.mode=JDBC` with explicit `egon.cola.component.agent-flow.memory.data-source-ref` and `egon.cola.component.agent-flow.memory.schema`. Supply the named `DataSource` and a provisioned schema for startup verification.

To use the optional RAG adapter, set `egon.cola.component.agent-flow.rag.enabled=true` and supply the RAG API, the named `ragRetrievalService` and an explicit `agentMemoryRagAuthority` bean. Startup requires the corresponding dependencies and host configuration for each selected adapter. See [AgentExecutorConformanceTest](src/test/java/top/egon/cola/component/agentflow/contract/AgentExecutorConformanceTest.java) and [OptionalClasspathTest](src/test/java/top/egon/cola/component/agentflow/autoconfigure/OptionalClasspathTest.java).

## Execution and lifecycle semantics

- The execution guard key is `(flowId, userId, sessionId)`. The same tuple is exclusive; different tuples may run in parallel.
- `AgentFlowSessionBusyException` is fail-fast. Delete and execute use the same guard, so deletion cannot pass a check-then-act gap.
- `execution-timeout` is a total deadline for execution after session validation. A timeout raises `AgentFlowExecutionTimeoutException`, disposes the upstream subscription, and releases the tuple lease.
- Caller cancellation disposes the stream and releases the lease without fabricating an error. The starter's `AgentFlowInMemoryRunner` also signals the active model subscription across ADK's cached execution step; directly constructing a plain ADK `InMemoryRunner` bypasses that propagation. An upstream failure becomes `AgentFlowExecutionException` with its cause retained.
- Completion and error release the session lease before notifying the terminal subscriber, so that callback can delete the session. Cancellation still disposes upstream before the lease is finally released.
- Cancellation and timeouts cannot undo a tool or remote request that has already started. The tuple lease is released when the stream terminates, even if external work ignores interruption; hosts must protect re-entry and side effects accordingly.
- The adapter requests model frames with bounded demand and fails explicitly after 100,000 response frames or 4,000,000 accumulated UTF-16 text/tool-argument code units. ADK's internal step cache and provider buffers may still retain data while a downstream consumer is slow; this is not end-to-end backpressure or a total memory bound. Missing or duplicate streamed tool-call IDs fail before the tool is executed.
- A custom `InMemoryRunner` subclass must implement the overload accepting `RunConfig` to customize streaming execution; the three-argument override remains in use for synchronous `execute`.
- There is no retry in the component (`no retry`); the host owns retry policy and idempotency decisions.
- Default `AgentFlowService` sessions are process-local and in-memory. They are not durable, replicated, or recoverable after process restart; a host-supplied session service defines its own storage guarantees. A timeout or cancel does not roll back events already accepted by ADK.
- `close` changes the guard to closing, rejects new operations, waits up to `shutdown-timeout`, then attempts every Runner close and cancels remaining starter-managed model subscriptions. It is idempotent and does not claim graceful completion after the bound expires.

## Boundaries and logging

The flat starter owns only the Agent Flow component capability:

```text
configuration -> validation -> ADK graph compilation -> registry -> session execution
```

It does not provide a provider integration, HTTP, MCP, database, UI, durable session store, or remote recovery protocol. The host owns those boundaries and supplies trusted `userId` values. Component logs contain only safe stage/outcome/error-type and event-count fields; they never record message content, instructions, credentials, `userId`, or `sessionId`. Provider/ADK debug logging must be reviewed or disabled in production because third-party observability may have broader content visibility.

Component failures are raised from `top.egon.cola.component.agentflow.common.exception` and are rooted on the common `CommonException`, so `getCode()`, `getStatus()` and `isRetryable()` stay uniform across starters. The starter publishes the canonical `egonColaValidationUtils` bean only when the application does not already have it, and injects it into `AgentFlowConfigValidator`, which extends `BaseValidator` and keeps its own graph rules after the native field constraints.

## Upgrade and validation gate

The compatibility line is Java 25 + Spring Boot 4.1.2 + Spring AI 2.0.2 + Google ADK 1.13.0. Any Spring AI or ADK upgrade requires re-running the focused tests, the runtime dependency tree check, and the Components reactor test. Do not add `google-adk-dev`, provider starters, Web, MCP, JDBC, or Flyway dependencies to this starter without a new specification.

The updated starter is versioned `0.0.2-SNAPSHOT` for source-reactor development; the existing Components BOM still selects `0.0.1`. Consumers must explicitly select the newer artifact after it is published, or build it in the same source reactor. `AgentFlowFactory.compileRoot` can compile a validated configuration without allocating a runner and takes an explicit ADK tool list; the caller owns tool authorization and runner lifecycle.

Configuration-built parallel agents use a context-aware scheduler so invocation trace and cancellation reach the branch subscriptions. Trusted code-first parallel agents must use `AgentFlowSchedulers.contextAwareIo()` or wrap their scheduler with `contextAware`; an independently configured native scheduler does not inherit that guarantee. Provider work scheduled on its own threads still needs its own context propagation.

## Three-Tier Memory & Unified Spring AI Context (Path A)

Enable the opt-in Path A three-tier memory and five-zone context pipeline with `egon.cola.component.agent-flow.memory.three-tier.enabled=true` (default `false` so existing `V1` hosts upgrade safely).

- **Level 1 (`session_message` / `TurnAlignedChatMemory`)**: Implements Spring AI `ChatMemory` & `ChatMemoryRepository` with turn-aligned sliding window (`egon.cola.component.agent-flow.memory.session-window-turns`, default `20`, range `1..200`), preserving complete `AssistantMessage.ToolCall` + `ToolResponseMessage` sequences without mid-chain splits; streams append only after full completion.
- **Level 2 (`text_memory` / `TextMemoryService`)**: Mid-term plain text store returning a stable downstream `id` via `bindOrCreate`, optimistic locking (`version`), and strict rejection (`TEXT_MEMORY_TOO_LARGE`) above `egon.cola.component.agent-flow.memory.text-max-chars` (default `10000`, never silently truncated).
- **Level 3 (`doc_memory` / `DocMemoryReader`)**: Read-only SPI managed by downstream systems (no Spring AI default table dependency); `JdbcDocMemoryReader` executes tenant-isolated `unnest(...) WITH ORDINALITY` lookups preserving caller `ids` order (configurable `egon.cola.component.agent-flow.memory.doc.*`, batch ceiling `32`). Runtime PostgreSQL role MUST hold `SELECT` only on `doc_memory` (`SELECT, INSERT, DELETE` on `session_message`; `SELECT, INSERT, UPDATE` on `text_memory`).
- **Unified Context (`SpringAiContextChatClient` & 6 `BaseAdvisor`s)**: Assembles `System Prompt`, `Session History`, `Memory [Text+Doc]`, `Others`, and `User Prompt` through ordered advisors (`100`..`500`, `900`) and `ContextAssemblyEngine` (`egon.cola.component.agent-flow.context.*`: `safety-margin-tokens=512`, `min-keep-history-turns=2`, `combine-context-into-final-user-message=true`), isolating untrusted memory inside `<context_data trust="reference_only">` and trimming `Others -> Tail DocMemory -> TextMemory -> Oldest Full Turn`.
- **Incremental DDL**: `db/egon-agent-memory/V2__create_three_tier_memory.sql` (checksum tracked in `three-tier-manifest.json`; `V1__create_memory_store.sql` and `manifest.json` remain unchanged).

```bash
./mvnw -B -ntp -pl stellar-enclosure-component-agent-flow-starter -am test
```
