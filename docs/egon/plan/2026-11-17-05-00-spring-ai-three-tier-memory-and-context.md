# Agent Flow Starter 基于 Spring AI 的三级 Memory 与一体化 Context 实施计划

| Field | Value |
| --- | --- |
| Document | `docs/egon/plan/2026-11-17-05-00-spring-ai-three-tier-memory-and-context.md` |
| Template Version | `6` |
| Status | `Review` |
| Created | `2026-11-17 05:00 AST` |
| Updated | `2026-11-17 05:00 AST` |
| Owner | `mario` |
| Repository | `Stellar-Enclosure-Components` |
| Scope | `stellar-enclosure-component-agent-flow-starter`（BOM 托管 `spring-ai-client-chat` 依赖、配置属性、中立 API/SPI、V2 PgSQL 增量 DDL 与存储校验、Level 1 按 Turn 20 轮滑窗与增强 `session_message` 仓储、Level 2 ≤10,000 字符中期文本服务、Level 3 下游 Markdown 只读 SPI、一体化 `ContextEnvelope` 与 Spring AI `BaseAdvisor` 链、自动装配与双语文档/契约治理） |
| Source Requirement | 用户确认接受 Spec 并触发 `/stellar-enclosure-coding-writing-plan`：基于 Spring AI 路径 A 实施三级 Memory（1b 增强 `session_message`、默认 20 轮可自定义滑动窗口、Level 2 10,000 字符严格拒绝、Level 3 不用 Spring 默认表只读按 `ids` 保序查库）与一体化分区域 Context 管理 |
| Baseline Revision | `Stellar-Enclosure-Components` `main` @ `3a3dc86db0b0ade067c8221447f52ea46ea7b24f`（含已接受目标 Spec 与未提交 `2026-11-16-08-*` outbox 文档） |
| Implements Spec | [Agent Flow Starter 基于 Spring AI 的三级 Memory 与一体化 Context 架构设计规格](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) |
| Spec Status | `Accepted` |
| Spec Revision | `2026-11-17 05:00 AST SHA-256 4630479484dea6197c0956d2433f38261035d88a4f461ee60c870d7c4275b4d1` |
| Effective Specs | [Agent Flow Starter 基于 Spring AI 的三级 Memory 与一体化 Context 架构设计规格](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) |
| Depends On Plans | `None` |
| Supersedes | `None` |
| Superseded By | `None` |
| Related Plans | `None` |

## 1. Summary

本实施计划落地已获用户批准的 [Agent Flow Starter 基于 Spring AI 的三级 Memory 与一体化 Context 架构设计规格](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md)，在不改动既有 `V1__create_memory_store.sql`、`JdbcMemoryStore` 四表事实账本、`AgentFlowService` 及 `DefaultAgentExecutor` 原有边界的前提下，按严格依赖顺序拆分 **6 个独立可验证的原子 Step**：（1）Step 1 引入父 POM BOM 托管的 `spring-ai-client-chat`、扩展 `AgentFlowProperties` 并发布纯中立 `api/memory`、`api/context` 契约与治理断言；（2）Step 2 新增 `V2__create_three_tier_memory.sql`、`three-tier-manifest.json` 校验和与 `ThreeTierMemoryStorageVerifier` 只读权限/结构校验；（3）Step 3 落地 Level 1 `TurnAlignedChatMemory`（默认 20 轮可自定义）与增强 `session_message` PgSQL/内存仓储；（4）Step 4 落地 Level 2 `TextMemoryService`（≤10,000 字符严格拒绝与乐观锁）与 Level 3 `DocMemoryReader` 只读保序 SPI；（5）Step 5 落地一体化 `ContextEnvelopeBO`、`ContextAssemblyEngine`、6 个 Spring AI `BaseAdvisor` 与 `SpringAiContextChatClient`；（6）Step 6 完成 `AgentExecutionAutoConfiguration` / `AgentMemoryJdbcAutoConfiguration` 接线、PostgreSQL 集成测试与中英文 README 文档同步。最终通过 `./mvnw -B -ntp -pl stellar-enclosure-component-agent-flow-starter -am test` 与可选真实 PostgreSQL 集成校验。

## 2. Target Spec and Effective Design

### 2.1 Primary target

- Path: [Agent Flow Starter 基于 Spring AI 的三级 Memory 与一体化 Context 架构设计规格](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md)
- Status: `Accepted`
- Revision: `2026-11-17 05:00 AST`（`SHA-256 4630479484dea6197c0956d2433f38261035d88a4f461ee60c870d7c4275b4d1`；基线提交 `3a3dc86db0b0ade067c8221447f52ea46ea7b24f`）
- Approval evidence: 用户于 `2026-11-17` 明确回复 `确认 可以。 /stellar-enclosure-coding-writing-plan` 批准规格并授权生成实施计划。

### 2.2 Effective Spec set

| Role | Spec/link | Status/revision | Effective sections | Why included |
| --- | --- | --- | --- | --- |
| Primary | [2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) | `Accepted` / `2026-11-17 05:00 AST` | 全文（§1–§20） | 唯一规范源，定义全部 `REQ-001`–`REQ-006`、数据表、SPI 契约、Advisor 链与测试设计 |
| Contextual sibling | [2026-11-06-02-33-agent-flow-explicit-resume.md](../spec/2026-11-06-02-33-agent-flow-explicit-resume.md) | `Review` / `2026-11-06 02:33 Asia/Shanghai` | `AgentFlowSessionRunner` 保留边界（仅作背景参照） | 确认本计划不修改 `AgentFlowSessionRunner` 原有显式续跑逻辑 |

### 2.3 Superseded or excluded content

`None`（新增独立三级记忆与 Spring AI 一体化上下文管线，完全兼容保留 `V1` 四表 `JdbcMemoryStore` 与 `AgentFlowService`）。

## 3. Effective Requirements and Acceptance

| Requirement | Source Spec section | Effective statement | Observable acceptance | Implementation impact |
| --- | --- | --- | --- | --- |
| `REQ-001` | [Spec](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) §4 | Level 1 实现 Spring AI `ChatMemoryRepository` 与 `ChatMemory`，基于 PgSQL `session_message` 完整保存并还原文本与 `AssistantMessage.ToolCall`/`ToolResponseMessage` 载荷 | 含多工具交互的对话落库后重读完全一致，跨租户同 `conversation_id` 严格隔离 | `V2` DDL、`JdbcSessionChatMemoryRepository`、`InMemorySessionChatMemoryRepository`、`TurnAlignedChatMemoryTest`、`ThreeTierMemoryPostgresTest` |
| `REQ-002` | [Spec](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) §4 | Level 1 滑动窗口按完整 `turn_id` 裁剪，默认 `20` 轮，支持全局配置与调用参数覆盖（`1..200`），绝不从工具交互中间截断 | 25 轮输入默认返第 6–25 轮完整消息，自定义 `5` 返第 21–25 轮，头部无孤立 `ToolResponseMessage` | `AgentFlowProperties`、`TurnAlignedChatMemory`、`JdbcSessionChatMemoryRepository`、`TurnAlignedChatMemoryTest` |
| `REQ-003` | [Spec](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) §4 | Level 2 基于 `text_memory` 实现 `TextMemoryService`，幂等绑定下发 `id`，带 `version` 乐观锁更新；服务层与 DB 层双重强制上限 `10,000` 字符，超限严格拒绝 | 重复绑定返同 `id`；10,000 字符成功且 `version+1`；10,001 字符严格拒写且原值不变；旧版本抛 `VERSION_CONFLICT` | `api/memory/*`、`InMemoryTextMemoryService`、`JdbcTextMemoryService`、`V2` DDL、`TextMemoryServiceTest`、`ThreeTierMemoryPostgresTest` |
| `REQ-004` | [Spec](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) §4 | Level 3 不依赖 Spring 默认建表，提供只读 SPI `DocMemoryReader` 与默认 `JdbcDocMemoryReader`（表/列名可配置），按传入 `ids` 结合 `tenantKey` 保序查询未软删 MD 文档 | 传入 `[id3, id1, id2]` 严格按 `[id3, id1, id2]` 返回；跨租户/已软删不返回；超 32 条拒查；运行 Role 对 `doc_memory` 仅 `SELECT` | `api/memory/Doc*`、`InMemoryDocMemoryReader`、`JdbcDocMemoryReader`、`V2` DDL、`ThreeTierMemoryStorageVerifier`、`DocMemoryReaderTest`、`ThreeTierMemoryPostgresTest` |
| `REQ-005` | [Spec](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) §4 | 基于 Spring AI `ChatClient` + `BaseAdvisor` 链实现五区 `ContextEnvelopeBO` 与 `ContextAssemblyEngine` 统一预算裁剪及隔离渲染 | `SystemMessage` 无记忆杂质；`<context_data>` 隔离参考区；超预算严格按 `Others -> 尾部 DocMemory -> TextMemory -> 旧整轮 History` 裁剪；流式失败不写 Level 1 | `context/*`、`adapter/springai/*`、`pom.xml` (`spring-ai-client-chat`)、`ContextAssemblyEngineTest`、`SpringAiContextChatClientTest` |
| `REQ-006` | [Spec](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) §4 | 维持扁平包与 `api.*` 纯中立无框架类型泄露；`V1` SQL 与 `manifest.json` 保持 SHA-256 不变，`V2` 入 `three-tier-manifest.json`；同步双语 README 与治理测试 | `AgentFlowComponentContractTest`、`ComponentContractGovernanceTest` 及 `ThreeTierMemoryStorageVerifierTest` 全绿 | `AgentFlowComponentContractTest`、`ComponentContractGovernanceTest`、`README*`、`three-tier-manifest.json` |

## 4. Implementation Strategy and Dependency Order

### 4.1 Ordered strategy

按编译依赖、Schema 前置、存储实现、Spring AI 管线、容器接线与文档治理划分 **6 个严格有序、写范围互不重叠的 Step**：
1. **Step 1（依赖与中立契约/配置地基）**：先更新契约测试与属性绑定测试确立 RED，再修改 `pom.xml` 引入 `spring-ai-client-chat`、创建全部纯中立 `api/memory/*` 与 `api/context/*` 类型、并扩展 `AgentFlowProperties`。本 Step 产出的接口与配置项是后续全部 Step 的编译前置。
2. **Step 2（`V2` 数据库增量 DDL 与结构校验器）**：在写任何 JDBC 仓储之前，先锁定 `V2__create_three_tier_memory.sql`、创建独立 `three-tier-manifest.json` 并实现 `ThreeTierMemoryStorageVerifier`，确保 `V1` 与原 `manifest.json` 零变更且最小权限约束有单测及启动校验守卫。
3. **Step 3（Level 1 Session History 滑窗与增强仓储）**：依赖 Step 1 配置与 Step 2 `session_message` DDL，先编写 `TurnAlignedChatMemoryTest` 确立按轮滑窗与不断链 RED，再实现 `InMemorySessionChatMemoryRepository`、`TurnAlignedChatMemory` 与 `JdbcSessionChatMemoryRepository`。
4. **Step 4（Level 2 Text Memory 与 Level 3 Doc Memory 实现）**：依赖 Step 1 `api/memory/*` 契约与 Step 2 `text_memory`/`doc_memory` DDL，先编写 `TextMemoryServiceTest` 与 `DocMemoryReaderTest` 确立 RED，再分别实现内存版与 PgSQL JDBC 版。
5. **Step 5（一体化 Context 预算引擎与 Spring AI Advisor 链）**：依赖 Step 1 `spring-ai-client-chat` 与 Step 3/Step 4 的 Memory 接口，先编写 `ContextAssemblyEngineTest` 与 `SpringAiContextChatClientTest` 确立 RED，再落地 `ContextBudgetPolicyVO`、`ContextEnvelopeBO`、`ContextAssemblyEngine`、6 个 `BaseAdvisor` 及 `SpringAiContextChatClient`。
6. **Step 6（AutoConfiguration 接线、PostgreSQL 集成测试与双语文档）**：依赖 Step 1–Step 5 全部组件就位，先编写 `ThreeTierMemoryAutoConfigurationTest` 与 `ThreeTierMemoryPostgresTest` 确立接线与 PG 行为门禁，再更新 `AgentExecutionAutoConfiguration`、`AgentMemoryJdbcAutoConfiguration`、`README.md` 与 `README.zh-CN.md`，跑通全模块回归。

### 4.2 Test-first strategy

每个 Step 均坚持“先写聚焦 RED 测试、验证因缺失目标行为失败、再写最小实现达到 GREEN”：
- **Step 1 RED**：`AgentFlowPropertiesBindingTest`、`AgentFlowComponentContractTest`、`ComponentContractGovernanceTest` 在加入新类型/枚举/配置前因缺少目标类型或属性绑定字段而编译失败或断言失败。
- **Step 2 RED**：`ThreeTierMemoryStorageVerifierTest` 断言类路径存在 `V2__create_three_tier_memory.sql`、`three-tier-manifest.json` 且篡改脚本会抛出存储不可用异常，`V1` `manifest.json` 保持单条目不变，在文件创建前失败。
- **Step 3 RED**：`TurnAlignedChatMemoryTest` 断言写入 25 轮含 `toolCalls` 对话后默认取回第 6–25 轮且首条非 `ToolResponseMessage`，实现前无法编译/断言失败。
- **Step 4 RED**：`TextMemoryServiceTest` 断言 10,001 字符抛 `TEXT_MEMORY_TOO_LARGE` 与旧版本抛 `VERSION_CONFLICT`、`DocMemoryReaderTest` 断言按 `[d3, d1]` 保序与超 32 条抛 `DOC_MEMORY_BATCH_LIMIT`，实现前失败。
- **Step 5 RED**：`ContextAssemblyEngineTest` 与 `SpringAiContextChatClientTest` 断言五区渲染隔离、按优先级淘汰、超限抛 `CONTEXT_BUDGET_EXCEEDED` 与流式错误不写入 Level 1，实现前失败。
- **Step 6 RED**：`ThreeTierMemoryAutoConfigurationTest` 断言容器自动发布 `SpringAiContextChatClient`、`TurnAlignedChatMemory`、`TextMemoryService`、`DocMemoryReader` Bean（`IN_MEMORY` vs `JDBC`），未接线前因缺 Bean 失败。

### 4.3 Sequential and parallel boundaries

| Step | Depends on | May run in parallel with | Must not overlap with | Reason |
| --- | --- | --- | --- | --- |
| Step 1 | None | None | Step 2–6 | 变更 `pom.xml`、`AgentFlowProperties` 与全部基础 `api/*` 接口 |
| Step 2 | Step 1 | None | Step 3, Step 4, Step 6 | `V2` DDL 定义表名/列名/约束，是 Step 3/Step 4 JDBC 实现与 Step 6 PG 测试的基础 |
| Step 3 | Step 1, Step 2 | Step 4（源码路径互不重叠） | Step 5, Step 6 | Step 3 仅写 `session_message` 相关类，与 Step 4 Level 2/3 文件零交集；Step 5 需要 `TurnAlignedChatMemory` |
| Step 4 | Step 1, Step 2 | Step 3（源码路径互不重叠） | Step 5, Step 6 | Step 4 仅写 Level 2/3 内存与 JDBC 实现；Step 5 需要 `TextMemoryService` 与 `DocMemoryReader` |
| Step 5 | Step 1, Step 3, Step 4 | None | Step 6 | `Context` 引擎与 6 个 Advisor 依赖 Level 1/2/3 实现接口 |
| Step 6 | Step 2, Step 3, Step 4, Step 5 | None | Step 1–5 | 自动装配引用全量 Bean，契约测试全模块扫描，README 记录最终全量配置键 |

### 4.4 Commit boundaries

每个 Step 产生 1 个语义明确、路径严格隔离的提交：
- Commit 1（Step 1）：`feat(agent-flow): add spring-ai client dependency, three-tier memory api and properties`
- Commit 2（Step 2）：`feat(agent-flow): add V2 three-tier memory schema and storage verifier`
- Commit 3（Step 3）：`feat(agent-flow): implement turn-aligned session chat memory with pgsql store`
- Commit 4（Step 4）：`feat(agent-flow): implement level-2 text memory service and level-3 readonly doc reader`
- Commit 5（Step 5）：`feat(agent-flow): add unified context assembly engine and spring ai advisor pipeline`
- Commit 6（Step 6）：`feat(agent-flow): wire three-tier memory autoconfiguration, pg tests and docs`

### 4.5 Spec Simplicity and Implementation-necessity Audit

| Spec element | Spec necessity verdict/section | Current repository evidence | Direct/reuse alternative | Interaction/implementation cost | Plan decision |
| --- | --- | --- | --- | --- | --- |
| `org.springframework.ai:spring-ai-client-chat` | Add（Spec §7.0, §5.3 `DEC-005`） | `Stellar-Enclosure-Components/pom.xml` 已由 `spring-ai-bom:2.0.2` 托管 | 自建拦截循环替代 `BaseAdvisor` 会与 Spring AI 工具循环割裂 | 增加 1 个 BOM 托管 jar 依赖 | Implement |
| `egon_agent_memory.session_message` + `TurnAlignedChatMemory` | Add（Spec §7.0, §11.2.1） | `spring-ai-model` 含 `ChatMemory` 但无 PgSQL `tool_payload` 与 `turn_id` | 官方 4 列 `SPRING_AI_CHAT_MEMORY` 表丢失工具调用结构并切断工具对 | 1 张表 + 装饰器 + 1 个 JDBC 仓储 | Implement |
| `egon_agent_memory.text_memory` + `TextMemoryService` | Add（Spec §7.0, §11.2.2） | 既有 `JdbcMemoryStore` 为 4 表 JSONB 审计账本 | 强塞进 `JdbcMemoryStore` 需强制 `CallPermitVO` 与 `MemoryAuthority` | 1 张单表 + 轻量乐观锁服务 | Implement |
| `DocMemoryReader` + `JdbcDocMemoryReader` | Add（Spec §7.0, §11.2.3） | 现无按传入 `ids` 保序只读 MD 的 SPI | 宿主自行拼字符串无法纳管统一 Token 预算与隔离标签 | 1 个只读 SPI + 1 个默认 JDBC 实现 | Implement |
| `ContextEnvelopeBO` + `ContextAssemblyEngine` + 6 个 `BaseAdvisor` | Add（Spec §7.0, §7.3） | 既有 `DefaultContextAssembler` 仅管 64KB `ContextFragmentResult` | 改既有类无法同时编排 `SystemMessage`/`ChatMemory`/`ToolDefinitions` | 内存中转对象 + 标准 Spring AI 责任链 | Implement |

结论：经逐项核对，Spec 无 fetch-then-forward、无冗余多层转换、无脱离需求的投机抽象，全部元素按 Spec 实施。

### 4.6 Change-unit Dependency Matrix

| Change unit | Requirements | Proof/RED point | Compile/runtime prerequisites | Produces | Consumers/unblocks | Owning Step |
| --- | --- | --- | --- | --- | --- | --- |
| 依赖、配置属性与中立 API/VO | `REQ-002`–`REQ-006` | `AgentFlowPropertiesBindingTest`, `AgentFlowComponentContractTest`, `ComponentContractGovernanceTest` | 父 POM `spring-ai-bom:2.0.2` | `TextMemoryService`, `DocMemoryReader`, 新 `AgentFlowProperties` 字段 | Step 2–6 | Step 1 |
| `V2` DDL 与存储校验 | `REQ-001`–`REQ-004`, `REQ-006` | `ThreeTierMemoryStorageVerifierTest` | 既有 `db/egon-agent-memory/` 与 `V1` | `V2__create_three_tier_memory.sql`, `three-tier-manifest.json`, `ThreeTierMemoryStorageVerifier` | Step 3, Step 4, Step 6 | Step 2 |
| Level 1 按轮滑窗实现 | `REQ-001`, `REQ-002` | `TurnAlignedChatMemoryTest` | Step 1 (`AgentFlowProperties`), Step 2 (`session_message`) | `TurnAlignedChatMemory`, `InMemory/JdbcSessionChatMemoryRepository` | Step 5, Step 6 | Step 3 |
| Level 2/3 存储实现 | `REQ-003`, `REQ-004` | `TextMemoryServiceTest`, `DocMemoryReaderTest` | Step 1 (`api/memory/*`), Step 2 (`text_memory`/`doc_memory`) | `InMemory/JdbcTextMemoryService`, `InMemory/JdbcDocMemoryReader` | Step 5, Step 6 | Step 4 |
| 一体化 Context 与 Advisor 链 | `REQ-005` | `ContextAssemblyEngineTest`, `SpringAiContextChatClientTest` | Step 1 (`spring-ai-client-chat`), Step 3, Step 4 | `ContextAssemblyEngine`, 6 个 `*Advisor`, `SpringAiContextChatClient` | Step 6 | Step 5 |
| 自动装配、PG 集成与文档 | `REQ-001`–`REQ-006` | `ThreeTierMemoryAutoConfigurationTest`, `ThreeTierMemoryPostgresTest`, 契约测试 | Step 1–Step 5 全部完成 | 生产装配 Bean、PG 端到端证明、同步 `README` | 下游宿主（如 `tianji-service`） | Step 6 |

### 4.7 Java, Spring, and Egon-COLA Implementation Standards

| Concern | Current repository evidence | Effective Spec decision | Planned implementation consequence | Owning Steps/checks |
| --- | --- | --- | --- | --- |
| Architecture profile | `AgentFlowComponentContractTest#uses_flat_functional_packages`（禁止 `domain`/`application`/`infrastructure`/`repository`/`biz`；`adapter/*` 仅 `adk`/`springai`/`mcp`；`api.*` 纯中立） | Spec §6.1 保留扁平功能组件结构 | 所有新文件放入 `api/memory`, `api/context`, `memory`, `memory/jdbc`, `context`, `adapter/springai`, `autoconfigure` | Every Step; `MC-ARCH-001` |
| Reuse/capability | `spring-ai-model:2.0.2`, `spring-ai-commons:2.0.2`, `spring-jdbc`, `RagContextSource`, `MemoryStoreStorageVerifier` | Spec §6.1 & `DEC-005` 批准新增 BOM 托管 `spring-ai-client-chat`，不引入 `spring-ai-model-chat-memory-repository-jdbc` | `pom.xml` 加 `spring-ai-client-chat`；复用 `ChatMemory`, `TokenCountEstimator`, `BaseAdvisor` | Step 1, Step 5; `MC-REUSE-001`, `MC-DEP-001` |
| Naming/model/validation/conversion | `AgentFlowConfigValidator`, `ValidationUtils`, `ComponentContractGovernanceTest` | Spec §6.2, §10（Command/Query/BO 用 Lombok `class`，不可变 VO 用 `record`，`@JsonValue` 枚举，无无意义 `BaseConverter`） | 严格区分 `class` 与 `record`；边界加 `@Validated`/`@Valid`/`@Size`/`@Min`/`@Max`；新枚举注册至 `NEUTRAL_ENUM_CODES` | Step 1, Step 4, Step 5; `MC-NAME-001`, `MC-VALID-001`, `MC-MODEL-001`, `MC-CONVERT-001` |
| Bean/logging/util/JSON/time/config | `lombok.config`（拷贝 `@Qualifier`）、`JdbcMemoryStore`（`OffsetDateTime`/`Instant`）、`NoUnboundElementsBindHandler` | Spec §6.2, §6.3（`@Slf4j`、显式 Bean 名、`@RequiredArgsConstructor(onConstructor_ = @Autowired)`、`@Qualifier`、Jackson `JsonMapper`、`java.time.Instant`、`README` 键同步） | 所有 Bean 带显式名称与 `@Qualifier`；日志脱敏不打正文与密钥；时间统一 UTC `Instant` 经 `OffsetDateTime` 读写；双语 README 同步 | Step 1–6; `MC-LOG-001`, `MC-BEAN-001`, `MC-UTIL-001`, `MC-JSON-001`, `MC-TIME-001`, `MC-CONFIG-001` |
| Business variation/pattern | `AgentWorkflowStrategyFactory`、Spring AI `BaseAdvisor` | Spec §13.1（Complex 逻辑采用责任链 + 装饰器 + 预算策略 + SPI/适配器） | 6 个 `BaseAdvisor` 构成责任链，`TurnAlignedChatMemory` 装饰 `ChatMemory`，`ContextAssemblyEngine` 执行策略裁剪，`DocMemoryReader` 为只读 SPI | Step 3, Step 4, Step 5; `MC-PATTERN-001` |

#### Capability reuse ledger

| Need | Candidates inspected | Exact evidence | Fit/gap | Decision | Added dependency/custom code | Owning Step/check |
| --- | --- | --- | --- | --- | --- | --- |
| Level 1 滑窗抽象 | Spring AI `ChatMemory`, `ChatMemoryRepository`, `MessageWindowChatMemory`; 既有 `InMemorySessionStore` | `spring-ai-model-2.0.2.jar`; `InMemorySessionStore.java` | `MessageWindowChatMemory` 按条数裁切易断工具链，官方 JDBC 表缺 `toolCalls` 与 `tenant_key` | 复用 `ChatMemory` 与 `ChatMemoryRepository` 接口 | 自定义 `TurnAlignedChatMemory` + 增强 `session_message` 仓储（无额外 JDBC starter） | Step 3; `MC-REUSE-001` |
| Context 责任链与请求拦截 | Spring AI `ChatClient`, `BaseAdvisor`, `ToolCallAdvisor`, `LastMaxTokenSizeContentPurger` | `Stellar-Enclosure-Components/pom.xml` 管理 `spring-ai-bom:2.0.2` | `agent-flow-starter/pom.xml` 当前仅引 `spring-ai-model`，缺失 `spring-ai-client-chat` | 经用户路径 A 批准引入 `spring-ai-client-chat` | `org.springframework.ai:spring-ai-client-chat`（BOM 托管版本 `2.0.2`） | Step 1, Step 5; `MC-DEP-001` |
| Token 估算与文档抽象 | Spring AI `TokenCountEstimator`, `JTokkitTokenCountEstimator`, `Document` | `spring-ai-commons-2.0.2.jar` | 已有能力完全匹配 | 直接复用，不手写 tokenizer | None（`spring-ai-model` 已传递引入） | Step 5, Step 6; `MC-REUSE-001` |
| RAG 上下文接入 | 既有 `ContextSource`, `RagContextSource` | `integration/rag/RagContextSource.java` | 已实现带 `CallPermitVO` 的语义检索 | 在 `OthersContextAdvisor` 直接复用 | None | Step 5; `MC-REUSE-001` |
| JDBC 访问与结构校验 | `spring-jdbc`, `MemoryStoreStorageVerifier`, `JdbcMemoryStore` | `memory/jdbc/MemoryStoreStorageVerifier.java` | `V1` 仅校验 4 张事实表 | 复用相同风格新增 `V2` 校验器 `ThreeTierMemoryStorageVerifier` | None（`spring-jdbc` 与 `postgresql` 已在 `pom.xml`） | Step 2, Step 3, Step 4; `MC-REUSE-001` |

### 4.8 User-mandated Java Rule Implementation Matrix

| Literal rule | Spec source | Repository evidence | Exact files and order | Implementation obligations | Validation gate | Steps | Status/blocker |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Rule 1 | [Spec](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) §6.2, §8.2, §10.1 | `AgentFlowComponentContractTest` | Step 1 `api/*` -> Step 2 `*Verifier` -> Step 3/4 `*Memory`/`*Service`/`*Reader`/`*Repository` -> Step 5 `*VO`/`*BO`/`*Engine`/`*Advisor`/`*ChatClient` | 全量采用语义后缀（`Command`/`Query`/`VO`/`BO`/`Service`/`Reader`/`Repository`/`Advisor`/`Engine`/`Verifier`/`Enum`），禁用 `Data`/`Info`/`Param`/`Bean`/`Request`/`Response` | `AgentFlowComponentContractTest` + 类名静态检查 | Step 1–6 | PASS |
| Rule 2 | [Spec](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) §6.2, §9.2, §10.3 | `ValidationUtils`, `spring-boot-starter-validation` | Step 1 测试与 Command/Query -> Step 4 Service/Reader 实现 -> Step 5 `SpringAiContextChatClient` -> Step 6 配置 | 全边界加 `@Validated`、`@Valid`、Jakarta 注解（`contentText` `@Size(max=10000)`、`ids` `@Size(max=32)`、`windowTurns` `1..200`）；必要手工触发调用 `egonColaValidationUtils` | 边界正反单测（`TextMemoryServiceTest`, `DocMemoryReaderTest`, `ContextAssemblyEngineTest`） | Step 1, Step 4, Step 5, Step 6 | PASS |
| Rule 3 | [Spec](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) §6.2, §10.3 | `ComponentContractGovernanceTest#conversionsStayOnTheCommonContractWithoutFakeConverters` | Step 1 `api/*` 载体 -> Step 5 `context/*` 载体 | `TextMemoryBindCommand`, `TextMemorySaveCommand`, `DocMemoryQuery`, `ContextEnvelopeBO`, `AgentContextCallBO` 使用 `class` + `@Data @NoArgsConstructor @AllArgsConstructor @Accessors(chain=true) @Builder`；不可变 `TextMemoryVO`, `DocMemoryVO`, `ContextBudgetPolicyVO`, `ContextAssemblyReportVO`, `AgentContextCallResultVO` 使用 `record` + 防御拷贝；不引入伪 Converter | 构造器/不可变单测 + `ComponentContractGovernanceTest` | Step 1, Step 5 | PASS |
| Rule 4 | [Spec](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) §6.2, §7.3.5 | `lombok.config`, `DefaultAgentExecutor` | Step 3–6 所有业务/服务/仓储/Advisor Bean | 强制 `@Slf4j`、显式 Bean 名、`final` 依赖字段 + `@Qualifier("exactBeanName")` + `@RequiredArgsConstructor(onConstructor_ = @Autowired)`；禁止打印正文/密钥/`tenantKey`/`conversationId` 明文 | 编译检查 + `ThreeTierMemoryAutoConfigurationTest` + 日志内容断言 | Step 3, Step 4, Step 5, Step 6 | PASS |
| Rule 5 | [Spec](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) §6.2 | `pom.xml`, `AgentFlowComponentContractTest` | Step 1–6 全量新文件 | 只用 JDK 原生与现有允许依赖，不引入额外工具库或新 `*Utils` 类 | `pom.xml` 与 import 审查 | Step 1–6 | PASS |
| Rule 6 | [Spec](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) §6.2, §10.3 | `ComponentContractGovernanceTest#neutralEnumsKeepExplicitStableStringCodes` | Step 1 `MemoryOwnerTypeEnum`, `ContextTrimReasonEnum`, `ComponentContractGovernanceTest` -> Step 3 `JdbcSessionChatMemoryRepository` | 仅用 Spring Boot 托管 Jackson `JsonMapper`；枚举使用 `@JsonValue` 并注册至 `NEUTRAL_ENUM_CODES`；禁止 `ordinal()`、Gson、Fastjson | `ComponentContractGovernanceTest` + `JdbcSessionChatMemoryRepository` JSONB 往返测试 | Step 1, Step 3 | PASS |
| Rule 7 | [Spec](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) §6.2, §15 | `AgentFlowProperties`, `README.md`, `README.zh-CN.md` | Step 1 `AgentFlowPropertiesBindingTest` & `AgentFlowProperties` -> Step 6 `README.md` & `README.zh-CN.md` | 所有新配置集中于严格绑定 record `AgentFlowProperties`；组件双语文档同步完全一致的配置键与默认值 | `AgentFlowPropertiesBindingTest` + `AgentFlowComponentContractTest` | Step 1, Step 6 | PASS |
| Rule 9 | [Spec](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) §6.2, §13.1 | `AgentWorkflowStrategyFactory`, `BaseAdvisor` | Step 3 `TurnAlignedChatMemory` -> Step 4 `DocMemoryReader` -> Step 5 `ContextAssemblyEngine` & 6 `*Advisor` | 采用责任链（6 个 `BaseAdvisor`）、装饰器（`TurnAlignedChatMemory`）、策略（裁剪优先级策略）与只读 SPI（`DocMemoryReader`），拒绝硬编码分支混杂 | `TurnAlignedChatMemoryTest`, `ContextAssemblyEngineTest`, `SpringAiContextChatClientTest` | Step 3, Step 4, Step 5 | PASS |
| Rule 10 | [Spec](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) §6.2, §6.3 | `AgentFlowComponentContractTest`, `JdbcMemoryStore` | Step 1 `TextMemoryVO`/`DocMemoryVO` -> Step 2 `V2` SQL -> Step 3/4 仓储与服务 | §4.9 `TIME-001` 全量时间字段使用 `java.time.Instant`、`timestamptz(6)`、pgJDBC `OffsetDateTime` 边界转换与 `agentExecutionClock`（微秒截断）；禁用 `java.util.Date` | `AgentFlowComponentContractTest#uses_semantic_java_names_and_safe_logging` + PG 往返测试 | Step 1, Step 2, Step 3, Step 4, Step 6 | PASS |
| Rule 11 | [Spec](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) §6.1, §6.2 | `AgentFlowComponentContractTest#uses_flat_functional_packages` | Every Step | 严格保持组件扁平功能包结构与 `db/egon-agent-memory/` 受管校验机制，禁止混入三层 `biz.*` 或 COLA `domain/infrastructure` | `AgentFlowComponentContractTest` | Every Step | PASS |

### 4.9 Time semantics and conversion contract

Time contract: `Required`

| Time ID | Fields/symbols | Semantics | Java/runtime type | Storage policy | Wire policy | Business zone source | Display zone source | Boundary / DST rules | Precision / Clock | Compatibility | Verification |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `TIME-001` | `session_message.created_at`、`text_memory.{created_at, updated_at}`、`doc_memory.updated_at`、`TextMemoryVO.updatedAt`、`DocMemoryVO.updatedAt` | `Occurred instant` | `java.time.Instant`（pgJDBC 边界经 `OffsetDateTime` 转换） | `UTC instant` | `Internal` | `Not applicable: 仅记录发生瞬时，不涉及业务日历运算` | `Not applicable: 组件库无前端展示` | 写入使用 `instant.atOffset(ZoneOffset.UTC)`，读取使用 `rs.getObject(..., OffsetDateTime.class).toInstant()` | 微秒级 `ChronoUnit.MICROS`，来源于注入 `agentExecutionClock`（UTC） | 与既有 `JdbcMemoryStore` 时间处理方式完全一致 | `Step 1, Step 2, Step 3, Step 4, Step 6; TEST-001, TEST-004, TEST-006` (`./mvnw -B -ntp -pl stellar-enclosure-component-agent-flow-starter -am test`) |

## 5. Change File Tree

| Operation | Path | Current evidence/symbol | Final symbols/state | Responsibility | Step | Requirements | Validation owner |
| --- | --- | --- | --- | --- | --- | --- | --- |
| MODIFY | `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/autoconfigure/AgentFlowPropertiesBindingTest.java` | 既有属性绑定测试 | 新增三级 Memory 与 Context 属性绑定与越界断言 | Step 1 配置绑定 RED/GREEN | Step 1 | `REQ-002`–`REQ-005` | `AgentFlowPropertiesBindingTest` |
| MODIFY | `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/contract/AgentFlowComponentContractTest.java` | 既有契约治理测试 | 扩充新 `api.memory`/`api.context` 中立边界及 `spring-ai-client-chat` 依赖断言 | 保护扁平包与中立 API | Step 1 | `REQ-006` | `AgentFlowComponentContractTest` |
| MODIFY | `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/contract/ComponentContractGovernanceTest.java` | `NEUTRAL_ENUM_CODES` | 注册 `MemoryOwnerTypeEnum` 与 `ContextTrimReasonEnum` 线值断言 | 枚举治理门禁 | Step 1 | `REQ-006` | `ComponentContractGovernanceTest` |
| MODIFY | `stellar-enclosure-component-agent-flow-starter/pom.xml` | 仅声明 `spring-ai-model` | 增加 BOM 托管的 `org.springframework.ai:spring-ai-client-chat` | 提供 `ChatClient`/`BaseAdvisor` | Step 1 | `REQ-005`, `REQ-006` | `AgentFlowComponentContractTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/MemoryOwnerTypeEnum.java` | Absent | `MemoryOwnerTypeEnum` (`AGENT`, `SESSION`, `CUSTOM`) | Level 2 归属枚举（`@JsonValue`） | Step 1 | `REQ-003`, `REQ-006` | `ComponentContractGovernanceTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/context/ContextTrimReasonEnum.java` | Absent | `ContextTrimReasonEnum` | 裁剪原因枚举（`@JsonValue`） | Step 1 | `REQ-005`, `REQ-006` | `ComponentContractGovernanceTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/context/ContextAssemblyReportVO.java` | Absent | `ContextAssemblyReportVO`, `ContextTrimDetailVO` | 汇编摘要与裁剪审计不可变 VO | Step 1 | `REQ-005`, `REQ-006` | `ContextAssemblyEngineTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/TextMemoryBindCommand.java` | Absent | `TextMemoryBindCommand` | Level 2 绑定/创建命令 | Step 1 | `REQ-003`, `REQ-006` | `TextMemoryServiceTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/TextMemorySaveCommand.java` | Absent | `TextMemorySaveCommand` | Level 2 更新命令（≤10,000 字符） | Step 1 | `REQ-003`, `REQ-006` | `TextMemoryServiceTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/TextMemoryVO.java` | Absent | `TextMemoryVO` | Level 2 不可变快照 | Step 1 | `REQ-003`, `REQ-006` | `TextMemoryServiceTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/TextMemoryService.java` | Absent | `TextMemoryService` | Level 2 中立服务接口 | Step 1 | `REQ-003`, `REQ-006` | `TextMemoryServiceTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/DocMemoryQuery.java` | Absent | `DocMemoryQuery` | Level 3 按 ids 批量查询入参 | Step 1 | `REQ-004`, `REQ-006` | `DocMemoryReaderTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/DocMemoryVO.java` | Absent | `DocMemoryVO` | Level 3 不可变 MD 快照 | Step 1 | `REQ-004`, `REQ-006` | `DocMemoryReaderTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/DocMemoryReader.java` | Absent | `DocMemoryReader` | Level 3 只读 SPI | Step 1 | `REQ-004`, `REQ-006` | `DocMemoryReaderTest` |
| MODIFY | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/autoconfigure/AgentFlowProperties.java` | `MemorySettings(mode, dataSourceRef, schema)` | 扩展 `MemorySettings` 与 `DocSettings`、`ContextSettings`，兼容旧构造器 | 严格配置属性绑定 | Step 1 | `REQ-002`–`REQ-005` | `AgentFlowPropertiesBindingTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/memory/jdbc/ThreeTierMemoryStorageVerifierTest.java` | Absent | `ThreeTierMemoryStorageVerifierTest` | `V2` DDL/manifest 与最小权限 RED/GREEN 测试 | Step 2 | `REQ-006` | `ThreeTierMemoryStorageVerifierTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/resources/db/egon-agent-memory/V2__create_three_tier_memory.sql` | 仅存 `V1` 脚本 | 新建 `session_message`, `text_memory`, `doc_memory` DDL | 三级记忆 PgSQL 表结构 | Step 2 | `REQ-001`–`REQ-004`, `REQ-006` | `ThreeTierMemoryStorageVerifierTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/resources/db/egon-agent-memory/three-tier-manifest.json` | Absent（既有 `manifest.json` 保留 `V1` 单条目不变） | 新建 `schemaVersion: 2` 清单记录 `V2` 路径与 SHA-256 | 增量 DDL 清单治理（不破坏 `V1` `scripts.size()==1` 不变量） | Step 2 | `REQ-006` | `ThreeTierMemoryStorageVerifierTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/jdbc/ThreeTierMemoryStorageVerifier.java` | Absent | `ThreeTierMemoryStorageVerifier` | 校验 `V2` 表/约束/索引及 `doc_memory` 只读 Role | Step 2 | `REQ-004`, `REQ-006` | `ThreeTierMemoryStorageVerifierTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/memory/TurnAlignedChatMemoryTest.java` | Absent | `TurnAlignedChatMemoryTest` | Level 1 滑窗与不断链单测 | Step 3 | `REQ-001`, `REQ-002` | `TurnAlignedChatMemoryTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/InMemorySessionChatMemoryRepository.java` | Absent | `InMemorySessionChatMemoryRepository` | Level 1 内存实现（`IN_MEMORY` 模式） | Step 3 | `REQ-001`, `REQ-002` | `TurnAlignedChatMemoryTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/TurnAlignedChatMemory.java` | Absent | `TurnAlignedChatMemory` | Spring AI `ChatMemory` 按 Turn 滑窗装饰器 | Step 3 | `REQ-001`, `REQ-002` | `TurnAlignedChatMemoryTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/jdbc/JdbcSessionChatMemoryRepository.java` | Absent | `JdbcSessionChatMemoryRepository` | 方案 1B PgSQL `session_message` 仓储 | Step 3 | `REQ-001`, `REQ-002` | `ThreeTierMemoryPostgresTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/memory/TextMemoryServiceTest.java` | Absent | `TextMemoryServiceTest` | Level 2 绑定/乐观锁/10,001 字符拒写单测 | Step 4 | `REQ-003` | `TextMemoryServiceTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/memory/DocMemoryReaderTest.java` | Absent | `DocMemoryReaderTest` | Level 3 保序/租户隔离/超限单测 | Step 4 | `REQ-004` | `DocMemoryReaderTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/InMemoryTextMemoryService.java` | Absent | `InMemoryTextMemoryService` | Level 2 内存服务 | Step 4 | `REQ-003` | `TextMemoryServiceTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/InMemoryDocMemoryReader.java` | Absent | `InMemoryDocMemoryReader` | Level 3 内存只读实现 | Step 4 | `REQ-004` | `DocMemoryReaderTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/jdbc/JdbcTextMemoryService.java` | Absent | `JdbcTextMemoryService` | Level 2 PgSQL 绑定与乐观锁更新 | Step 4 | `REQ-003` | `ThreeTierMemoryPostgresTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/jdbc/JdbcDocMemoryReader.java` | Absent | `JdbcDocMemoryReader` | Level 3 PgSQL 只读 `WITH ORDINALITY` 查询 | Step 4 | `REQ-004` | `ThreeTierMemoryPostgresTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/context/ContextAssemblyEngineTest.java` | Absent | `ContextAssemblyEngineTest` | Token 预算裁剪顺序与防注入隔离单测 | Step 5 | `REQ-005` | `ContextAssemblyEngineTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/adapter/springai/SpringAiContextChatClientTest.java` | Absent | `SpringAiContextChatClientTest` | Advisor 链、工具循环与流式取消不写回单测 | Step 5 | `REQ-005` | `SpringAiContextChatClientTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/context/ContextBudgetPolicyVO.java` | Absent | `ContextBudgetPolicyVO` | 预算参数不可变 VO | Step 5 | `REQ-005` | `ContextAssemblyEngineTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/context/ContextEnvelopeBO.java` | Absent | `ContextEnvelopeBO` | 五区内存上下文载体 | Step 5 | `REQ-005` | `ContextAssemblyEngineTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/context/ContextAssemblyEngine.java` | Absent | `ContextAssemblyEngine` | 预算仲裁、淘汰与渲染核心引擎 | Step 5 | `REQ-005` | `ContextAssemblyEngineTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/adapter/springai/ContextInitAdvisor.java` | Absent | `ContextInitAdvisor` | Order=100 初始化五区容器 | Step 5 | `REQ-005` | `SpringAiContextChatClientTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/adapter/springai/SessionHistoryAdvisor.java` | Absent | `SessionHistoryAdvisor` | Order=200 加载并仅成功后回写 Level 1 | Step 5 | `REQ-001`, `REQ-002`, `REQ-005` | `SpringAiContextChatClientTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/adapter/springai/TextMemoryAdvisor.java` | Absent | `TextMemoryAdvisor` | Order=300 加载 Level 2 中期文本 | Step 5 | `REQ-003`, `REQ-005` | `SpringAiContextChatClientTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/adapter/springai/DocMemoryAdvisor.java` | Absent | `DocMemoryAdvisor` | Order=400 加载 Level 3 MD 文档 | Step 5 | `REQ-004`, `REQ-005` | `SpringAiContextChatClientTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/adapter/springai/OthersContextAdvisor.java` | Absent | `OthersContextAdvisor` | Order=500 采集 RAG/Runtime 碎片 | Step 5 | `REQ-005` | `SpringAiContextChatClientTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/adapter/springai/ContextAssemblyAdvisor.java` | Absent | `ContextAssemblyAdvisor` | Order=900 统一裁剪并重塑 `Prompt` | Step 5 | `REQ-005` | `SpringAiContextChatClientTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/adapter/springai/SpringAiContextChatClient.java` | Absent | `SpringAiContextChatClient`, `AgentContextCallBO`, `AgentContextCallResultVO` | 一体化 Spring AI 执行门面 | Step 5 | `REQ-005` | `SpringAiContextChatClientTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/autoconfigure/ThreeTierMemoryAutoConfigurationTest.java` | Absent | `ThreeTierMemoryAutoConfigurationTest` | 条件装配、`NONE`/`IN_MEMORY`/`JDBC` 与默认 Bean 校验 | Step 6 | `REQ-002`–`REQ-006` | `ThreeTierMemoryAutoConfigurationTest` |
| CREATE | `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/memory/jdbc/ThreeTierMemoryPostgresTest.java` | Absent | `ThreeTierMemoryPostgresTest` | 真实 PgSQL 端到端集成测试（遵循 `JdbcMemoryStorePostgresTest` 模式） | Step 6 | `REQ-001`–`REQ-004`, `REQ-006` | `ThreeTierMemoryPostgresTest` |
| MODIFY | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/autoconfigure/AgentExecutionAutoConfiguration.java` | 既有 `AgentExecutor` 装配 | 新增 `IN_MEMORY` 默认 Bean、`JTokkitTokenCountEstimator`、`ContextAssemblyEngine`、6 Advisors 与 `SpringAiContextChatClient` | Spring Boot 默认自动装配 | Step 6 | `REQ-001`–`REQ-005` | `ThreeTierMemoryAutoConfigurationTest` |
| MODIFY | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/autoconfigure/AgentMemoryJdbcAutoConfiguration.java` | 仅发布 `jdbcMemoryStore` | 按配置装配 `ThreeTierMemoryStorageVerifier` 与三级 JDBC Bean | PgSQL JDBC 模式自动装配 | Step 6 | `REQ-001`–`REQ-004`, `REQ-006` | `ThreeTierMemoryAutoConfigurationTest`, `ThreeTierMemoryPostgresTest` |
| MODIFY | `stellar-enclosure-component-agent-flow-starter/README.md` | 既有英文说明 | 补充三级 Memory、配置键与一体化 Context 说明 | 英文文档契约对齐 | Step 6 | `REQ-006` | `AgentFlowComponentContractTest` |
| MODIFY | `stellar-enclosure-component-agent-flow-starter/README.zh-CN.md` | 既有中文说明 | 同步补充三级 Memory、配置键与一体化 Context 说明 | 中文文档契约对齐 | Step 6 | `REQ-006` | `AgentFlowComponentContractTest` |

## 6. Prerequisites, Constraints, and Plan Clarifications

### 6.1 Repository and worktree baseline

- 适用约束：`AGENTS.md`（若无则以仓库 `pom.xml`、`lombok.config` 与组件契约测试为准）、`Stellar-Enclosure-Components` 主仓库规范。
- 基线分支与状态：`Stellar-Enclosure-Components` `main` @ `3a3dc86db0b0ade067c8221447f52ea46ea7b24f`；工作区中既有的未跟踪文件 `docs/egon/spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md` 与 `docs/egon/plan/2026-11-16-08-19-outbox-in-service-distributed-resilience.md` 属于其他组件文档，执行任何 Step 提交时**严禁将其 `git add` 或更改**。
- 每个 Step 必须按 `Commit paths` 精确暂存并提交，保护未相关路径不被误污染。

### 6.2 Build, test, and environment prerequisites

| Concern | Exact command/source | Required state | Validation boundary |
| --- | --- | --- | --- |
| Maven 构建与单测 | `./mvnw -B -ntp -pl stellar-enclosure-component-agent-flow-starter -am test`（工作目录：`Stellar-Enclosure-Components`） | JDK 25、Maven Wrapper 可执行、离线或仓库可解析 `spring-ai-client-chat:2.0.2` | 模块级编译、单测、Spring Context 测试、契约治理测试 |
| PostgreSQL 真实集成校验 | `./mvnw -B -ntp -pl stellar-enclosure-component-agent-flow-starter -Dtest=ThreeTierMemoryPostgresTest,JdbcMemoryStorePostgresTest -Degon.agent.memory.postgres.test.url=<jdbcUrl> -Degon.agent.memory.postgres.test.runtime-user=<role> test` | 已准备好的本地回环 PostgreSQL 14+ 实例与测试账号 | 真实 PG DDL、约束、`pg_advisory_xact_lock`、最小权限 Role 验证 |

### 6.3 Immutable constraints and approved decisions

- `V1__create_memory_store.sql` 字节与 SHA-256（`7aa621490c953b38a6231724e85d8fad4433e493d99d1bda37c8b749a6bc8654`）绝对不可更改；`JdbcMemoryStore` 保持原行为不变。
- `api.*` 公开包下的全部接口与载体方法签名绝不出现 `org.springframework.ai.*`、`com.google.adk.*`、`io.reactivex.*`；技术异常统一复用 `CommonException`。
- `pom.xml` 中 `spring-ai-client-chat` 已在 Spec §5.3 `DEC-005` 与用户审批中确认引入（由父 POM `spring-ai-bom:2.0.2` 管理版本）；排除 `spring-ai-model-chat-memory-repository-jdbc`。

### 6.4 Plan Clarifications

| ID | Small implementation inference | Repository evidence | Why semantics are unchanged | Impact if wrong |
| --- | --- | --- | --- | --- |
| `PLAN-CLAR-001` | Spec §14 曾提及 Testcontainers PG 测试，但仓库既有 `JdbcMemoryStorePostgresTest.java` 实际使用系统属性 `egon.agent.memory.postgres.test.url` 与 `egon.agent.memory.postgres.test.runtime-user` 对接回环 PostgreSQL（未传属性时安全跳过），Plan 的 `ThreeTierMemoryPostgresTest` 沿用这一既定仓库夹具模式，确保离线无 Docker 环境构建可稳定通过。 | `JdbcMemoryStorePostgresTest.java` L70–78 | 仍是在真实 PostgreSQL 引擎上跑全量 DDL/并发/权限验证，仅连接参数注入方式与现有测试保持一致 | 若需切换成 Testcontainers 容器化自动拉起，仅调整测试类连接工厂即可，零生产代码影响 |
| `PLAN-CLAR-002` | Spec 头部 `Change Surface` 曾概括提及 `memory/session`、`memory/text`、`memory/doc`，而 Spec §8.2 目标文件树已精确落于扁平的 `memory/` 与 `memory/jdbc/` 包下；本 Plan 严格以 Spec §8.2 精确路径为准，避免增加无必要子目录层级。 | Spec §8.2、`AgentFlowComponentContractTest#uses_flat_functional_packages` | 包结构与类职责完全按 Spec §8.2，未引入任何被禁止包段 | 仅包路径简化为扁平 `memory/`，可随时局部移动 |

## 7. Ordered File-by-file Implementation Steps

### Step 1 — 引入依赖、扩展严格配置属性并发布中立 API/SPI 与载体契约

- Requirements: `REQ-002`, `REQ-003`, `REQ-004`, `REQ-005`, `REQ-006`
- Dependencies: `None`
- Baseline state: `agent-flow-starter/pom.xml` 仅有 `spring-ai-model:2.0.2`；`AgentFlowProperties` 仅有旧 `MemorySettings(mode, dataSourceRef, schema)`；`api/memory` 与 `api/context` 尚无三级记忆与裁剪报告契约；既有测试全绿。
- Observable outcome: `spring-ai-client-chat` 就位；所有中立 API/SPI 接口、Command/Query/VO/Enum 载体与扩展 `AgentFlowProperties` 编译通过；`AgentFlowPropertiesBindingTest`、`AgentFlowComponentContractTest` 与 `ComponentContractGovernanceTest` 全部通过。
- End state: 三级 Memory 与 Context 汇编所需的全部接口、值对象与配置绑定契约建立完毕；具体内存/JDBC/Advisor 实现留待后续 Step。
- Test-first gate: `Required` — 先扩充 `AgentFlowPropertiesBindingTest`、`AgentFlowComponentContractTest` 与 `ComponentContractGovernanceTest` 断言新增配置键绑定默认值（`sessionWindowTurns=20`, `textMaxChars=10000`）、新增中立类型包纯洁性及新枚举字符串线值，未新增生产类型前编译或断言必失败（RED）。
- Manual Checks: `MC-ARCH-001`, `MC-REUSE-001`, `MC-DEP-001`, `MC-NAME-001`, `MC-VALID-001`, `MC-MODEL-001`, `MC-JSON-001`, `MC-TIME-001`, `MC-CONFIG-001`, `MC-SCOPE-001`, `MC-TEST-001`
- Literal Rules: `Rule 1`, `Rule 2`, `Rule 3`, `Rule 5`, `Rule 6`, `Rule 7`, `Rule 10`, `Rule 11`
- Ordered files:

#### File 1 — `MODIFY stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/autoconfigure/AgentFlowPropertiesBindingTest.java`

- Purpose: 建立新三级记忆与上下文配置属性的严格绑定、默认值与非法越界拦截测试（RED 门禁）。
- Symbols: 新增测试方法验证 `MemorySettings` 默认 `sessionWindowTurns=20`、`textMaxChars=10000`、`docMaxBatchSize=32` 及 `ContextSettings` 默认 `safetyMarginTokens=512`、`minKeepHistoryTurns=2`、合法自定义值绑定、以及越界值或未知配置键绑定失败。
- Repository evidence: 既有 `AgentFlowPropertiesBindingTest` 使用 `Binder` + `NoUnboundElementsBindHandler` 验证属性绑定规则。
- Dependencies and consumers: 依赖 `AgentFlowProperties`；被 Maven Surefire 消费。
- Why now: Test-first 起点，锁定配置结构契约后再修改生产类 `AgentFlowProperties`。
- Contract/signature changes: 测试构造新 `MemorySettings` / `ContextSettings` 访问器与非法输入断言。
- Input/output and state mapping: 模拟配置映射 -> `Binder` 绑定 -> 验证默认值与合法区间；非法范围预期抛绑定异常。
- Error and edge behavior: 覆盖 `sessionWindowTurns <= 0` 或 `> 200`、`textMaxChars <= 0` 或 `> 10000`、`docMaxBatchSize <= 0`、未知白名单外 `docTable` 名称被拒绝。
- Standards impact: `MC-CONFIG-001`, `MC-VALID-001`, `MC-SCOPE-001`, `MC-TEST-001`。
- Literal rule enforcement: `Rule 7`, `Rule 11` — 验证严格配置绑定与新增属性默认值一致，测试位于原 `autoconfigure` 测试包。
- Required change: 在既有配置绑定测试中新增针对三级 Memory（含 `DocSettings` 表/列名白名单与 `threeTierEnabled` 开关）与 `ContextSettings` 的默认值和非法范围断言。
- Verification contribution: Step 1 RED/GREEN 测试用例之一。
- After this file: 因 `AgentFlowProperties` 尚未扩展新字段，本文件处于预期的编译 RED 状态。

#### File 2 — `MODIFY stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/contract/AgentFlowComponentContractTest.java`

- Purpose: 扩充依赖边界与 `api.*` 纯中立边界契约测试，保护 `spring-ai-client-chat` 引入后不引入禁用依赖且不泄露框架类型至 `api.*`。
- Symbols: `uses_exact_dependency_boundary`, `retainsLegacySignaturesAndExportsSeparateNeutralTypes`
- Repository evidence: 既有 `AgentFlowComponentContractTest.java` L57–73 与 L236–254。
- Dependencies and consumers: 校验 `pom.xml` 及 `api.memory.*`、`api.context.*` 反射签名。
- Why now: 在引入新依赖和新建 `api/*` 前先锁定契约红线。
- Contract/signature changes: 在中立扫描清单中加入 `memory.TextMemoryService`、`memory.TextMemoryBindCommand`、`memory.TextMemorySaveCommand`、`memory.TextMemoryVO`、`memory.DocMemoryReader`、`memory.DocMemoryQuery`、`memory.DocMemoryVO`、`memory.MemoryOwnerTypeEnum`、`context.ContextAssemblyReportVO`、`context.ContextTrimReasonEnum`，并在 `uses_exact_dependency_boundary` 断言 `spring-ai-client-chat`（由父 BOM 管理，POM 中无硬编码 `<version>`）。
- Input/output and state mapping: 反射读取新增 `api.*` 声明方法泛型签名，断言不含 `org.springframework.ai`、`com.google.adk`、`io.reactivex`、`javax.sql`。
- Error and edge behavior: 若未来误将 Spring AI 类型写入 `api.*` 方法入参/出参，测试立即失败。
- Standards impact: `MC-ARCH-001`, `MC-DEP-001`, `MC-SCOPE-001`, `MC-TEST-001`。
- Literal rule enforcement: `Rule 1`, `Rule 11` — 以自动化反射门禁锁死组件扁平架构及 API 中立边界。
- Required change: 扩充中立反射断言列表并添加 `spring-ai-client-chat` 无版本号 BOM 托管声明断言。
- Verification contribution: 证明 `pom.xml` 与新 `api.*` 接口满足组件架构合同。
- After this file: 因新 `pom.xml` 依赖与新 `api.*` 类尚未创建，反射 `Class.forName` 与 POM 断言失败（RED）。

#### File 3 — `MODIFY stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/contract/ComponentContractGovernanceTest.java`

- Purpose: 将两个新字符串枚举注册到治理测试白名单并补充 JSON 反序列化边界验证。
- Symbols: `NEUTRAL_ENUM_CODES`
- Repository evidence: `ComponentContractGovernanceTest.java` L96–113 与 `neutralEnumsKeepExplicitStableStringCodes`。
- Dependencies and consumers: 扫描所有 `src/main/java` 下 enum 声明。
- Why now: 新增 `@JsonValue` 枚举必须登记在此白名单，否则会触发 `handwrittenEnumsImplementTheCommonEnumContract` 检查失败。
- Contract/signature changes: 在 `NEUTRAL_ENUM_CODES` 增加 `api.memory.MemoryOwnerTypeEnum` (`AGENT`, `SESSION`, `CUSTOM`) 与 `api.context.ContextTrimReasonEnum` (`BUDGET_OTHERS`, `BUDGET_DOC_MEMORY`, `BUDGET_TEXT_MEMORY`, `BUDGET_HISTORY_TURN`)。
- Input/output and state mapping: 枚举常量经 Jackson 2/3 序列化为稳定字符串编码并验证无重复。
- Error and edge behavior: 若枚举漏加 `@JsonValue` 或顺序错位，治理测试直接阻断。
- Standards impact: `MC-JSON-001`, `MC-TEST-001`。
- Literal rule enforcement: `Rule 6`, `Rule 11` — 强制枚举编码稳定且基于 Jackson。
- Required change: 在 `NEUTRAL_ENUM_CODES` 映射中登记两个新枚举的预期字符串编码列表。
- Verification contribution: Step 1 契约治理门禁组成部分。
- After this file: 新枚举类未创建，测试期待 `Class.forName` 失败（RED）。

#### File 4 — `MODIFY stellar-enclosure-component-agent-flow-starter/pom.xml`

- Purpose: 根据 Spec `DEC-005` 引入 BOM 托管的 `spring-ai-client-chat` 依赖。
- Symbols: `<dependency>` `org.springframework.ai:spring-ai-client-chat`（由父 POM `spring-ai-bom:2.0.2` 托管版本，无需 `<version>` 标签）。
- Repository evidence: `Stellar-Enclosure-Components/pom.xml` L144–149 已引入 `spring-ai-bom:2.0.2`；`agent-flow-starter/pom.xml` L55–59 引入 `spring-ai-model`。
- Dependencies and consumers: 为 Step 5 `ChatClient`、`BaseAdvisor`、`ToolCallAdvisor` 提供编译类路径。
- Why now: 依赖调整需在编译和新建实现类之前完成。
- Contract/signature changes: 仅新增 `spring-ai-client-chat`（不引入 `spring-ai-model-chat-memory-repository-jdbc`，不引入厂商 starter）。
- Input/output and state mapping: Maven 依赖解析由父 BOM 固定为 `2.0.2`。
- Error and edge behavior: 不新增 `flyway`/`liquibase`/`webmvc`/`google-adk-dev`。
- Standards impact: `MC-DEP-001`, `MC-REUSE-001`, `MC-SCOPE-001`。
- Literal rule enforcement: `Rule 5`, `Rule 11` — 复用 BOM 托管 Spring AI 能力，不违规引入替代工具库。
- Required change: 在 `dependencies` 中追加 `org.springframework.ai:spring-ai-client-chat` 声明（不写版本号，复用父 POM `spring-ai-bom`）。
- Verification contribution: 使 `AgentFlowComponentContractTest#uses_exact_dependency_boundary` 转 GREEN 并解锁 Step 5 编译。
- After this file: `pom.xml` 依赖解析通过，等待新增 Java 类型文件。

#### File 5 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/MemoryOwnerTypeEnum.java`

- Purpose: 定义 Level 2 Text Memory 归属维度枚举。
- Symbols: `MemoryOwnerTypeEnum` (`AGENT`, `SESSION`, `CUSTOM`)、`code()`、`fromCode(Object)`
- Repository evidence: 参考既有 `api/memory/MemoryFactTypeEnum.java` 枚举写法。
- Dependencies and consumers: 被 `TextMemoryBindCommand`、`JdbcTextMemoryService` 与治理测试消费。
- Why now: `TextMemoryBindCommand` 编译前置类型。
- Contract/signature changes: `@JsonValue private final String code;` 与 `@JsonCreator(mode = JsonCreator.Mode.DELEGATING)` 严格解码。
- Input/output and state mapping: `AGENT`/`SESSION`/`CUSTOM` 字符串线值与枚举常量双向精确对应。
- Error and edge behavior: 未知/空字符串反序列化抛 `IllegalArgumentException`。
- Standards impact: `MC-NAME-001`, `MC-JSON-001`, `MC-MODEL-001`。
- Literal rule enforcement: `Rule 1`, `Rule 6`, `Rule 11` — `*Enum` 语义命名、Jackson `@JsonValue`、扁平 `api.memory` 包。
- Required change: 新建 `MemoryOwnerTypeEnum`，遵循 `MemoryFactTypeEnum` 模式声明 `AGENT`、`SESSION`、`CUSTOM` 三个带 `@JsonValue` 的编码值。
- Verification contribution: `ComponentContractGovernanceTest#neutralEnumsKeepExplicitStableStringCodes`。
- After this file: `MemoryOwnerTypeEnum` 可编译通过。

#### File 6 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/context/ContextTrimReasonEnum.java`

- Purpose: 定义一体化 Context 预算裁剪原因代码枚举。
- Symbols: `ContextTrimReasonEnum` (`BUDGET_OTHERS`, `BUDGET_DOC_MEMORY`, `BUDGET_TEXT_MEMORY`, `BUDGET_HISTORY_TURN`)
- Repository evidence: 参考 `MemoryFactTypeEnum.java` 与 `AgentExecutionResult.java` 内部枚举格式。
- Dependencies and consumers: 被 `ContextAssemblyReportVO`、`ContextAssemblyEngine` 与治理测试消费。
- Why now: `ContextAssemblyReportVO` 编译前置类型。
- Contract/signature changes: `@JsonValue private final String code;` 与 `fromCode(Object)`。
- Input/output and state mapping: 严格四类淘汰原因代码。
- Error and edge behavior: 非法编码抛 `IllegalArgumentException`。
- Standards impact: `MC-NAME-001`, `MC-JSON-001`。
- Literal rule enforcement: `Rule 1`, `Rule 6`, `Rule 11`。
- Required change: 新建 `ContextTrimReasonEnum` 暴露四个标准预算淘汰枚举常量及其 `@JsonValue` 字符串编码。
- Verification contribution: `ComponentContractGovernanceTest#neutralEnumsKeepExplicitStableStringCodes`。
- After this file: `ContextTrimReasonEnum` 可编译通过。

#### File 7 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/context/ContextAssemblyReportVO.java`

- Purpose: 定义不可变的 Context 汇编结果与裁剪明细快照值对象。
- Symbols: `ContextAssemblyReportVO`, `ContextTrimDetailVO`
- Repository evidence: 参考 `api/execution/AgentExecutionResult.java` 等不可变 `record` VO。
- Dependencies and consumers: 由 `ContextAssemblyEngine` 与 `SpringAiContextChatClient` 产出，供宿主可观测记录。
- Why now: 完成 `api/context` 中立输出契约。
- Contract/signature changes: 包含 `availableInputTokens`、`estimatedInputTokens`、`systemTokens`、`historyTurnsIncluded`、`historyTurnsTrimmed`、`textMemoryIncluded`、`textMemoryChars`、`docCountRequested`、`docCountIncluded`、`othersTrimmed`、`List<ContextTrimDetailVO> trimDetails`；内部 `ContextTrimDetailVO(ContextTrimReasonEnum reason, String sectionId, int estimatedTokens)`。
- Input/output and state mapping: 紧凑构造器执行 `List.copyOf(trimDetails)` 与非负计数校验；`toString()` 屏蔽潜在敏感字段，仅保留数值统计。
- Error and edge behavior: 负数值或空 `trimDetails` 抛 `IllegalArgumentException`。
- Standards impact: `MC-NAME-001`, `MC-MODEL-001`, `MC-LOG-001`。
- Literal rule enforcement: `Rule 1`, `Rule 3`, `Rule 11` — 不可变值对象合法采用 `record` 并做防御拷贝。
- Required change: 创建 `ContextAssemblyReportVO` 记录五区 Token 分布、入围/淘汰计数与脱敏裁剪明细。
- Verification contribution: `AgentFlowComponentContractTest` 中立签名检查与 Step 5 `ContextAssemblyEngineTest`。
- After this file: `api/context` 新类型全部可编译。

#### File 8 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/TextMemoryBindCommand.java`

- Purpose: 封装 Level 2 绑定/创建中期文本记忆的入参命令。
- Symbols: `TextMemoryBindCommand`
- Repository evidence: 参考既有 `api/memory/MemoryWriteCommand.java` 的 Lombok `class` 风格（`@Data @NoArgsConstructor @AllArgsConstructor @Accessors(chain = true) @Builder`）。
- Dependencies and consumers: `TextMemoryService#bindOrCreate` 入参。
- Why now: 先于 `TextMemoryService` 创建。
- Contract/signature changes: 字段 `tenantKey` (`@NotBlank @Size(max=128)`), `namespace` (`@NotBlank @Size(max=128)`), `ownerType` (`@NotNull`), `ownerKey` (`@NotBlank @Size(max=256)`)，加 `snapshot()` 方法检验无控制字符。
- Input/output and state mapping: 校验字段合法性并做不可变快照投影防外部篡改。
- Error and edge behavior: 非法控制字符或空值抛 `ConstraintViolationException` / `IllegalArgumentException`。
- Standards impact: `MC-NAME-001`, `MC-MODEL-001`, `MC-VALID-001`。
- Literal rule enforcement: `Rule 1`, `Rule 2`, `Rule 3`, `Rule 11` — 普通可变 Command 使用 `class` + 完整 Lombok 基线，加 Jakarta 约束。
- Required change: 创建 `TextMemoryBindCommand` 类及 `snapshot()` 校验辅助方法。
- Verification contribution: Step 1 契约扫描与 Step 4 `TextMemoryServiceTest`。
- After this file: `TextMemoryBindCommand` 编译通过。

#### File 9 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/TextMemorySaveCommand.java`

- Purpose: 封装 Level 2 更新中期文本记忆的命令，显式强制 `10,000` 字符上限。
- Symbols: `TextMemorySaveCommand`（常量 `MAX_CONTENT_CHARS = 10_000`）
- Repository evidence: 参考 `MemoryWriteCommand.java`。
- Dependencies and consumers: `TextMemoryService#save` 入参。
- Why now: 先于 `TextMemoryService` 创建。
- Contract/signature changes: 字段 `tenantKey` (`@NotBlank @Size(max=128)`), `id` (`@NotBlank @Size(max=64)`), `contentText` (`@NotNull @Size(max=10000)`), `expectedVersion` (`@Min(1)`), 附带 `snapshot()` 检测 `\u0000` 与有效 UTF-8 编码。
- Input/output and state mapping: 空字符串允许（表示清空摘要），超过 10,000 字符在入库前严格拒绝。
- Error and edge behavior: 超 10,000 字符抛 `CommonException("TEXT_MEMORY_TOO_LARGE")` 或触发校验失败。
- Standards impact: `MC-NAME-001`, `MC-MODEL-001`, `MC-VALID-001`。
- Literal rule enforcement: `Rule 1`, `Rule 2`, `Rule 3`, `Rule 11`。
- Required change: 创建 `TextMemorySaveCommand` 类并锁定 `contentText` 最大 10,000 字符及正整数乐观锁版本约束。
- Verification contribution: Step 4 `TextMemoryServiceTest` 验证 10,000 与 10,001 字符分界。
- After this file: `TextMemorySaveCommand` 编译通过。

#### File 10 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/TextMemoryVO.java`

- Purpose: 表达 Level 2 中期文本记忆的不可变只读视图快照。
- Symbols: `TextMemoryVO(String id, long version, String contentText, int charCount, Instant updatedAt)`
- Repository evidence: 参考 `api/memory/MemoryFactVO.java` 不可变 `record` 设计。
- Dependencies and consumers: `TextMemoryService` 返回类型与 `TextMemoryAdvisor` 消费。
- Why now: 先于 `TextMemoryService` 创建。
- Contract/signature changes: 紧凑构造器验证非空 `id`、`version >= 1`、`contentText != null && contentText.length() <= 10_000`、`charCount == contentText.length()`、`updatedAt != null`，并重写 `toString()` 屏蔽文本正文避免日志泄漏。
- Input/output and state mapping: PostgreSQL `timestamptz(6)` 转 `Instant` 注入。
- Error and edge behavior: 长度不一致或空值抛 `IllegalArgumentException`。
- Standards impact: `MC-NAME-001`, `MC-MODEL-001`, `MC-TIME-001`, `MC-LOG-001`。
- Literal rule enforcement: `Rule 1`, `Rule 3`, `Rule 10`, `Rule 11`。
- Required change: 创建不可变 `TextMemoryVO` 记录，并在 `toString()` 脱敏正文。
- Verification contribution: Step 1 契约与 Step 4/Step 5 测试。
- After this file: `TextMemoryVO` 编译通过。

#### File 11 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/TextMemoryService.java`

- Purpose: 发布 Level 2 中期文本记忆的中立业务接口。
- Symbols: `TextMemoryService`（`bindOrCreate`, `getById`, `save`）
- Repository evidence: 参考 `api/memory/MemoryStore.java` 声明式 `@Valid`/`@NotNull` 接口注解。
- Dependencies and consumers: 被宿主应用、`TextMemoryAdvisor`、以及 `InMemoryTextMemoryService` / `JdbcTextMemoryService` 实现与调用。
- Why now: 完成 Level 2 公开接口契约。
- Contract/signature changes: 三个方法均标注 Jakarta `@Valid`/`@NotNull`/`@NotBlank`，签名只含 JDK 与 `api.memory.*` 类型。
- Input/output and state mapping: `bindOrCreate` 幂等返回 `TextMemoryVO`（含下发给下游的 `id`）；`getById` 返 `Optional<TextMemoryVO>`；`save` 返回递增版本后的 `TextMemoryVO`。
- Error and edge behavior: 明确契约规定超 10,000 字符与版本冲突均严格抛出异常而非截断或覆盖。
- Standards impact: `MC-ARCH-001`, `MC-NAME-001`, `MC-VALID-001`。
- Literal rule enforcement: `Rule 1`, `Rule 2`, `Rule 11`。
- Required change: 创建 `TextMemoryService` 接口声明三个操作及其边界校验注解。
- Verification contribution: `AgentFlowComponentContractTest` 验证零框架类型泄漏。
- After this file: Level 2 契约建立完毕。

#### File 12 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/DocMemoryQuery.java`

- Purpose: 封装 Level 3 按 `ids` 批量读取 Markdown 长期记忆的查询参数。
- Symbols: `DocMemoryQuery`（常量 `MAX_BATCH_IDS = 32`）
- Repository evidence: 参考 `api/memory/MemoryQuery.java` 的 Lombok `class` 风格与 `snapshot()` 模式。
- Dependencies and consumers: `DocMemoryReader#findByIds` 入参。
- Why now: 先于 `DocMemoryReader` 创建。
- Contract/signature changes: 字段 `tenantKey` (`@NotBlank @Size(max=128)`), `ids` (`@NotNull @Size(max=32)` of `@NotBlank @Size(max=128) String`)，附 `snapshot()` 去重同时保持传入优先级顺序并剔除非法控制字符。
- Input/output and state mapping: `[d3, d1, d3]` 归一化为不可变 `[d3, d1]`。
- Error and edge behavior: `ids.size() > 32` 或含空白/控制字符抛 `CommonException("DOC_MEMORY_BATCH_LIMIT")` / `ConstraintViolationException`。
- Standards impact: `MC-NAME-001`, `MC-MODEL-001`, `MC-VALID-001`。
- Literal rule enforcement: `Rule 1`, `Rule 2`, `Rule 3`, `Rule 11`。
- Required change: 创建 `DocMemoryQuery` 类，支持有序去重与批量大小/标识符校验。
- Verification contribution: Step 4 `DocMemoryReaderTest`。
- After this file: `DocMemoryQuery` 编译通过。

#### File 13 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/DocMemoryVO.java`

- Purpose: 表示 Level 3 Markdown 长期记忆文档的不可变值对象。
- Symbols: `DocMemoryVO(String id, String title, String contentMd, String contentSha256, Instant updatedAt)`
- Repository evidence: 参考 `ContextFragmentResult.FragmentVO`。
- Dependencies and consumers: `DocMemoryReader` 输出、`ContextEnvelopeBO`、`DocMemoryAdvisor`。
- Why now: 先于 `DocMemoryReader` 创建。
- Contract/signature changes: 紧凑构造器校验 `id` 非空、`title` 非空（允许空串）、`contentMd` 非空且不含 `\u0000`、`contentSha256` 可为空或 64 位小写 hex、`updatedAt` 非空；`toString()` 脱敏 `contentMd`。
- Input/output and state mapping: 映射 PostgreSQL `doc_memory` 行。
- Error and edge behavior: 非法字段抛 `IllegalArgumentException`。
- Standards impact: `MC-NAME-001`, `MC-MODEL-001`, `MC-TIME-001`, `MC-LOG-001`。
- Literal rule enforcement: `Rule 1`, `Rule 3`, `Rule 10`, `Rule 11`。
- Required change: 创建 `DocMemoryVO` 记录，保留完整 Markdown 正文且不暴露内容到 `toString()`。
- Verification contribution: Step 1 契约与 Step 4/Step 5 测试。
- After this file: `DocMemoryVO` 编译通过。

#### File 14 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/DocMemoryReader.java`

- Purpose: 声明 Level 3 长期 Markdown 记忆的只读 SPI，支持宿主覆盖自定义表实现。
- Symbols: `DocMemoryReader`（唯一方法 `findByIds(@Valid @NotNull DocMemoryQuery query)`）
- Repository evidence: 参考 `api/context/ContextSource.java` 只读函数式 SPI。
- Dependencies and consumers: `DocMemoryAdvisor` 消费；`InMemoryDocMemoryReader`、`JdbcDocMemoryReader` 或宿主自定义 Bean 实现。
- Why now: 完成 Level 3 中立 SPI 契约。
- Contract/signature changes: 仅声明 `List<DocMemoryVO> findByIds(@Valid @NotNull DocMemoryQuery query)`，明确只读语义。
- Input/output and state mapping: 传入租户与有序 ID 列表 -> 返回当前租户未软删的保序 MD 文档列表。
- Error and edge behavior: 空列表返空；越权或已软删 ID 自动省略不泄露；超出 32 条报错。
- Standards impact: `MC-ARCH-001`, `MC-NAME-001`, `MC-VALID-001`。
- Literal rule enforcement: `Rule 1`, `Rule 2`, `Rule 11`。
- Required change: 创建 `DocMemoryReader` `@FunctionalInterface` 只读 SPI。
- Verification contribution: `AgentFlowComponentContractTest` 中立边界校验。
- After this file: 全部新增 `api/*` 契约齐备。

#### File 15 — `MODIFY stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/autoconfigure/AgentFlowProperties.java`

- Purpose: 为三级记忆与一体化 Context 增添严格绑定的不可变配置项，同时保持老版本 YAML 与所有既有构造器签名 100% 兼容。
- Symbols: `MemorySettings` 新增字段（`boolean threeTierEnabled`, `int sessionWindowTurns`, `int textMaxChars`, `DocSettings doc`）、`DocSettings`（`String tableName`, `String idColumn`, `String tenantColumn`, `String titleColumn`, `String contentColumn`, `String sha256Column`, `String deletedColumn`, `String updatedAtColumn`, `int maxBatchSize`）、`ContextSettings`（`int safetyMarginTokens`, `int minKeepHistoryTurns`, `boolean combineContextIntoFinalUserMessage`）
- Repository evidence: `AgentFlowProperties.java` L22–94 既有重载构造器与正则白名单校验风格。
- Dependencies and consumers: `AgentExecutionAutoConfiguration`、`AgentMemoryJdbcAutoConfiguration`、`TurnAlignedChatMemory`、`JdbcDocMemoryReader`、`ContextAssemblyEngine` 与 `AgentFlowPropertiesBindingTest`。
- Why now: 依赖及中立接口齐备后落地配置绑定，解除 File 1 RED。
- Contract/signature changes: 保留既有 `AgentFlowProperties` 全部 4/5/7/8 参构造器以及 3 参 `MemorySettings(mode, dataSourceRef, schema)` 重载委派给全参主构造器；默认值分别为 `threeTierEnabled=false`（或当 `mode=JDBC`/`IN_MEMORY` 且使用新组件 Bean 时启用，注意默认按 `true` 在三级 Bean 存在时生效或显式开关注明，这里建议 `threeTierEnabled` 默认 `true` 以便开箱即可创建内存三级 Bean，而旧 `JdbcMemoryStore` 仍由 `mode=JDBC` 兼容控制，但注意若 `mode=JDBC` 老项目尚未建 `V2` 表怎么办？**重要兼容性保护**：设 `threeTierEnabled` 默认为 `false` 或通过 `egon.cola.component.agent-flow.memory.three-tier.enabled=false` 为默认开关！这样既**零破坏已有使用 `mode=JDBC` 但只建了 `V1` 表的老宿主（如 `tianji-service`）**，又让需要开启路径 A 三级记忆的宿主显式开启！）、`sessionWindowTurns=20`（`1..200`）、`textMaxChars=10000`（`1..10000`）、`doc.maxBatchSize=32`（`1..32`）、`doc` 表列名默认对齐 `doc_memory` 且全量做 `[a-z_][a-z0-9_]{0,62}` SQL 标识符正则防注入校验。
- Input/output and state mapping: 严格由 `NoUnboundElementsBindHandler` 绑定，缺省时自动填充合理默认值。
- Error and edge behavior: 任何越界或非法表/列名立即在启动期抛 `IllegalArgumentException`。
- Standards impact: `MC-CONFIG-001`, `MC-VALID-001`, `MC-MODEL-001`, `MC-SCOPE-001`。
- Literal rule enforcement: `Rule 3`, `Rule 7`, `Rule 11` — 不可变配置 record 与严格校验。
- Required change: 扩展 `AgentFlowProperties` 增加 `ContextSettings` 与扩展 `MemorySettings`（含 `ThreeTierSettings` / `DocSettings` 及 `threeTierEnabled` 安全开关默认 `false` 保兼容性），保留所有既有构造器重载。
- Verification contribution: 使 File 1 `AgentFlowPropertiesBindingTest` 与 File 2/3 契约测试全部转 GREEN。
- After this file: Step 1 全量配置、依赖与纯中立契约可编译并通过治理测试。

- Validation working directory: `Stellar-Enclosure-Components`
- Verification command: `./mvnw -B -ntp -pl stellar-enclosure-component-agent-flow-starter -Dtest=AgentFlowPropertiesBindingTest,AgentFlowComponentContractTest,ComponentContractGovernanceTest test`
- Expected result: Exit 0；全部属性绑定与契约治理测试通过（含 `spring-ai-client-chat` 解析正常及 `api.*` 零框架类型泄漏）。
- Failure returns to: 若 BOM 解析 `spring-ai-client-chat` 报错回查 `pom.xml`；若签名/枚举测试失败回查 File 5–15。
- Completion criteria: 3 个测试套件全部通过且未改动任何 `V1` 或业务执行代码。
- Rollback: 仅回退 Step 1 修改/新建的 15 个文件。
- Commit paths: `stellar-enclosure-component-agent-flow-starter/pom.xml`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/MemoryOwnerTypeEnum.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/context/ContextTrimReasonEnum.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/context/ContextAssemblyReportVO.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/TextMemoryBindCommand.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/TextMemorySaveCommand.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/TextMemoryVO.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/TextMemoryService.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/DocMemoryQuery.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/DocMemoryVO.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/api/memory/DocMemoryReader.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/autoconfigure/AgentFlowProperties.java`, `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/autoconfigure/AgentFlowPropertiesBindingTest.java`, `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/contract/AgentFlowComponentContractTest.java`, `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/contract/ComponentContractGovernanceTest.java`
- Commit: `feat(agent-flow): add spring-ai client dependency, three-tier memory api and properties`

---

### Step 2 — 新增 `V2` PgSQL 增量 DDL、`three-tier-manifest.json` 校验和与 `ThreeTierMemoryStorageVerifier`

- Requirements: `REQ-001`, `REQ-002`, `REQ-003`, `REQ-004`, `REQ-006`
- Dependencies: `Step 1`
- Baseline state: `db/egon-agent-memory/` 仅包含 `V1__create_memory_store.sql` 及 `schemaVersion: 1` 的 `manifest.json`；`MemoryStoreStorageVerifier` L110 硬性要求 `manifest.json` 的 `scripts.size() == 1` 并只校验 `V1` 四表。
- Observable outcome: `V2__create_three_tier_memory.sql` 就位且 SHA-256 记入独立清单 `three-tier-manifest.json`（`V1` 与原 `manifest.json` 字节不变）；`ThreeTierMemoryStorageVerifier` 能在启动期只读校验 `V2` 三张表及运行角色对 `doc_memory` 的只读权限边界。
- End state: PgSQL 三级表 Schema 与只读启动守卫建立完成，可供 Step 3 与 Step 4 JDBC 仓储依赖。
- Test-first gate: `Required` — 先创建 `ThreeTierMemoryStorageVerifierTest` 断言 `V2` 脚本与 `three-tier-manifest.json` SHA-256 解析、`V1` SHA-256 保持 `7aa62149...` 与 `manifest.json` 原样不变、以及 Mock `JdbcTemplate`/元信息查询拦截 `doc_memory` 写权限和缺表行为，在实现前必处于 RED 失败。
- Manual Checks: `MC-ARCH-001`, `MC-REUSE-001`, `MC-NAME-001`, `MC-BEAN-001`, `MC-JSON-001`, `MC-TIME-001`, `MC-SCOPE-001`, `MC-TEST-001`
- Literal Rules: `Rule 1`, `Rule 5`, `Rule 6`, `Rule 10`, `Rule 11`
- Ordered files:

#### File 1 — `CREATE stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/memory/jdbc/ThreeTierMemoryStorageVerifierTest.java`

- Purpose: 建立 `V2` DDL/manifest 与只读存储校验器的 RED 单测。
- Symbols: `verifiesManifestChecksumsAndPreservesV1`, `rejectsMissingV2TablesOrWritableDocMemoryRole`, `rejectsInvalidDocTableIdentifier`
- Repository evidence: 参考 `MemoryStoreStorageVerifier.java` 的 manifest 校验、角色权限查询与 schema 名校验模式。
- Dependencies and consumers: 测试 `ThreeTierMemoryStorageVerifier` 与 classpath 资源 `db/egon-agent-memory/*`。
- Why now: 先锁定 `V1` 不变性、`V2` SHA-256 一致性与 `doc_memory` 只读防线（RED）。
- Contract/signature changes: 测试构造 `ThreeTierMemoryStorageVerifier(dataSource, schema, docTable)` 并调用 `verify()`。
- Input/output and state mapping: 使用 H2/Mock `DataSource` 返回模拟 `pg_catalog` 结果集检验权限判断分支（真实 PG 验证留待 Step 6）。
- Error and edge behavior: 篡改 SQL 校验和或角色拥有 `doc_memory` `INSERT` 权限时抛 `IllegalStateException("THREE_TIER_MEMORY_STORAGE_UNAVAILABLE")`。
- Standards impact: `MC-TEST-001`, `MC-SCOPE-001`, `MC-TIME-001`。
- Literal rule enforcement: `Rule 1`, `Rule 10`, `Rule 11`。
- Required change: 创建 `ThreeTierMemoryStorageVerifierTest`，编写校验 `V1` SHA-256 不变、`V2` manifest 解析、非法的 schema/docTable 拦截及对 `doc_memory` 写权限拒绝的测试。
- Verification contribution: Step 2 核心 RED/GREEN 门禁。
- After this file: 因 `V2` SQL 与 `ThreeTierMemoryStorageVerifier` 尚不存在，测试编译失败（RED）。

#### File 2 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/resources/db/egon-agent-memory/V2__create_three_tier_memory.sql`

- Purpose: 创建 Level 1 `session_message`、Level 2 `text_memory`、Level 3 参考表 `doc_memory` 的 PostgreSQL DDL。
- Symbols: 表 `egon_agent_memory.session_message`, `egon_agent_memory.text_memory`, `egon_agent_memory.doc_memory` 及索引 `idx_session_message_turn_lookup`、`uk_text_memory_owner`
- Repository evidence: Spec §11.2.1–11.2.3 完整列与约束设计；参照 `V1__create_memory_store.sql` 的 `COLLATE "C"`、`timestamptz(6)`、无默认/生成列约定。
- Dependencies and consumers: 被 `three-tier-manifest.json` 哈希索引，被 `ThreeTierMemoryStorageVerifier` 与 Step 6 PG 测试消费。
- Why now: Schema 定义必须在 `three-tier-manifest.json` 计算 SHA-256 之前写入磁盘。
- Contract/signature changes: 新增三张表与所有 `CHECK` 约束（含 `char_length(content_text) <= 10000`、`message_type IN (...)`、`owner_type IN (...)`、`position(chr(0) in ...) = 0`）。
- Input/output and state mapping: 时间字段全部采用 `timestamptz(6)` UTC 存储；标识符列使用 `COLLATE "C"`。
- Error and edge behavior: 不触碰 `V1` 任何对象。
- Standards impact: `MC-TIME-001`, `MC-SCOPE-001`。
- Literal rule enforcement: `Rule 10`, `Rule 11` — 精确使用 `timestamptz(6)` 与增量版本脚本。
- Required change: 按照 Spec §11.2 写入 `session_message`、`text_memory`、`doc_memory` 建表、约束与索引 DDL 语句（注意不加 `DEFAULT` 与 `GENERATED ALWAYS`）。
- Verification contribution: 为 Step 2 校验和与 Step 6 PG 真实执行提供 DDL 源。
- After this file: SQL 文件就位，可计算确定性 SHA-256。

#### File 3 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/resources/db/egon-agent-memory/three-tier-manifest.json`

- Purpose: 保持既有 `manifest.json`（`version: 1`）零改动，新建 `three-tier-manifest.json` 记录 `V2` 脚本路径及其真实 SHA-256。
- Symbols: `schemaVersion: 2`, `scripts[0]` (`version: 2`, `file: "V2__create_three_tier_memory.sql"`, `sha256: "<computed>"`)
- Repository evidence: `MemoryStoreStorageVerifier.java` L110 硬性校验 `manifest.json` 的 `scripts.size() == 1`；新建同目录 `three-tier-manifest.json` 可在不触碰既有 `V1` 校验器的前提下实现增量 SHA-256 清单治理。
- Dependencies and consumers: 被 `ThreeTierMemoryStorageVerifier` 与 `ThreeTierMemoryStorageVerifierTest` 读取校验；既有 `MemoryStoreStorageVerifier` 继续只读原 `manifest.json`。
- Why now: `V2` SQL 已定稿，立即可精确计算 SHA-256 并写入独立清单。
- Contract/signature changes: 不修改现有 `manifest.json` 结构和 `MemoryStoreStorageVerifier` 契约；新增独立清单资源供三级记忆校验器消费。
- Input/output and state mapping: `schemaVersion` 为 `2`，`scripts` 包含 `V2__create_three_tier_memory.sql` 的文件名与 64 位小写 hex SHA-256 摘要。
- Error and edge behavior: 绝不破坏现有 `V1` 校验逻辑与既有 `JdbcMemoryStorePostgresTest`。
- Standards impact: `MC-JSON-001`, `MC-SCOPE-001`。
- Literal rule enforcement: `Rule 6`, `Rule 11` — Jackson `JsonMapper` 解析 `three-tier-manifest.json`，零破坏现有 `V1` 合同。
- Required change: 在 `src/main/resources/db/egon-agent-memory/` 下创建 `three-tier-manifest.json`，记录 `V2__create_three_tier_memory.sql` 的 `version: 2` 与实际 SHA-256，保持原有 `manifest.json` 与 `MemoryStoreStorageVerifier` 100% 零改动。
- Verification contribution: `ThreeTierMemoryStorageVerifierTest` 与既有 `JdbcMemoryStorePostgresTest` 均通过。
- After this file: `V1` 与 `V2` 清单各自独立且 SHA-256 精确对齐。

#### File 4 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/jdbc/ThreeTierMemoryStorageVerifier.java`

- Purpose: 实现启动期只读校验 `V2` 三级表存在性、列约束、索引、`three-tier-manifest.json` 校验和以及运行时 Role 最小权限（对 `doc_memory` 强制无写/删权限）。
- Symbols: `ThreeTierMemoryStorageVerifier` (`verify()`, `requireSchema(String)`, `requireIdentifier(String)`)
- Repository evidence: 参考 `MemoryStoreStorageVerifier.java` 只读 `TransactionTemplate` + `pg_catalog` 系统表检查写法。
- Dependencies and consumers: `AgentMemoryJdbcAutoConfiguration` 在启动时调用；被 `ThreeTierMemoryStorageVerifierTest` 校验。
- Why now: 解锁 File 1 RED 并为 Step 3/Step 4 提供公共 schema/表名校验辅助。
- Contract/signature changes: 构造器 `(DataSource dataSource, String schema, String docTable, boolean verifyDocTable)`；若 `docTable` 为下游自定义表名，允许跳过对默认 `doc_memory` 结构的强匹配但仍强制检查运行 Role 仅拥有 `SELECT` 权限。
- Input/output and state mapping: 只读检查 `pg_roles`、`pg_class`、`pg_constraint`、`pg_index`、`pg_attribute` 及 `has_table_privilege(..., 'INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER') = false`。
- Error and edge behavior: 任何校验失败抛出 `IllegalStateException("THREE_TIER_MEMORY_STORAGE_UNAVAILABLE")`，在容器启动期 fail-closed。
- Standards impact: `MC-NAME-001`, `MC-JSON-001`, `MC-SCOPE-001`。
- Literal rule enforcement: `Rule 1`, `Rule 5`, `Rule 6`, `Rule 11` — 使用 Jackson `JsonMapper` 校验清单，零自定义工具类。
- Required change: 创建 `ThreeTierMemoryStorageVerifier`，基于 `three-tier-manifest.json` 验证 `V2` DDL 完整性并核验运行时 DB Role 对 `doc_memory`（或自定义文档表）仅具只读权限。
- Verification contribution: 使 `ThreeTierMemoryStorageVerifierTest` 转 GREEN。
- After this file: Step 2 全部数据库 DDL 与启动守卫验证就绪。

- Validation working directory: `Stellar-Enclosure-Components`
- Verification command: `./mvnw -B -ntp -pl stellar-enclosure-component-agent-flow-starter -Dtest=ThreeTierMemoryStorageVerifierTest,AgentFlowComponentContractTest test`
- Expected result: Exit 0；`ThreeTierMemoryStorageVerifierTest` 与契约测试全部通过，`V1` 校验和未被破坏。
- Failure returns to: 检查 `three-tier-manifest.json` SHA-256 或 `ThreeTierMemoryStorageVerifier` SQL 断言解析规则。
- Completion criteria: `V2` DDL、`three-tier-manifest.json` 与 `ThreeTierMemoryStorageVerifier` 单测通过且 `V1` 零改动。
- Rollback: 删除 `V2` 脚本、`three-tier-manifest.json`、`ThreeTierMemoryStorageVerifier` 与测试类。
- Commit paths: `stellar-enclosure-component-agent-flow-starter/src/main/resources/db/egon-agent-memory/V2__create_three_tier_memory.sql`, `stellar-enclosure-component-agent-flow-starter/src/main/resources/db/egon-agent-memory/three-tier-manifest.json`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/jdbc/ThreeTierMemoryStorageVerifier.java`, `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/memory/jdbc/ThreeTierMemoryStorageVerifierTest.java`
- Commit: `feat(agent-flow): add V2 three-tier memory schema and storage verifier`

---

### Step 3 — 实现 Level 1 会话滑窗存储与 `TurnAlignedChatMemory`（InMemory + PgSQL 方案 1B）

- Requirements: `REQ-001`, `REQ-002`
- Dependencies: `Step 1`, `Step 2`
- Baseline state: `V2` `session_message` DDL 已就绪，尚无 Level 1 `ChatMemory` 与增强 PgSQL 仓储实现。
- Observable outcome: `TurnAlignedChatMemory` 实现 Spring AI `ChatMemory` 与按完整 Turn 滑窗（默认 `20` 轮、支持 `1..200` 自定义轮数），内存仓储与 PgSQL 方案 1B 仓储均可完整存储及还原 `AssistantMessage.ToolCall` 与 `ToolResponseMessage`。
- End state: Level 1 内存版与 PgSQL 版完全就绪且单测全绿，供 Step 5 `SessionHistoryAdvisor` 消费。
- Test-first gate: `Required` — 先创建 `TurnAlignedChatMemoryTest` 验证 25 轮对话（含工具调用链）默认滑窗返最近 20 轮、自定义 5 轮返最近 5 轮、头部永无孤立 `ToolResponseMessage`、同 `turnId` 幂等重放与租户隔离（RED）。
- Manual Checks: `MC-ARCH-001`, `MC-REUSE-001`, `MC-NAME-001`, `MC-VALID-001`, `MC-LOG-001`, `MC-BEAN-001`, `MC-JSON-001`, `MC-TIME-001`, `MC-PATTERN-001`, `MC-SCOPE-001`, `MC-TEST-001`
- Literal Rules: `Rule 1`, `Rule 2`, `Rule 4`, `Rule 5`, `Rule 6`, `Rule 9`, `Rule 10`, `Rule 11`
- Ordered files:

#### File 1 — `CREATE stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/memory/TurnAlignedChatMemoryTest.java`

- Purpose: 建立 Level 1 滑动窗口、工具调用防断链、多租户隔离与 `turnId` 幂等的 RED 单测。
- Symbols: `keepsRecentTwentyTurnsByDefault`, `supportsCustomWindowTurnsAndNeverSplitsToolTurn`, `restoresToolCallsAndToolResponsesWithTenantIsolation`, `rejectsInvalidWindowTurns`
- Repository evidence: 参考 `MemoryStoreConformanceTest.java` 构造多轮测试夹具。
- Dependencies and consumers: 测试 `TurnAlignedChatMemory` 与 `InMemorySessionChatMemoryRepository`。
- Why now: Test-first 定义 Level 1 核心行为与边界不变量。
- Contract/signature changes: 覆盖 `get(tenantKey, conversationId, windowTurns)`、`get(conversationId)` 默认 20 轮、`appendTurn(tenantKey, conversationId, turnId, messages)`、`clear(...)`。
- Input/output and state mapping: 构造 25 轮对话（含某轮 `UserMessage -> AssistantMessage(toolCalls) -> ToolResponseMessage -> AssistantMessage`），校验裁剪后首条消息与工具链完整性。
- Error and edge behavior: `windowTurns < 1` 或 `> 200` 抛 `CommonException("INVALID_SESSION_WINDOW")`；头部人为注入孤立 `ToolResponseMessage` 时自动对齐丢弃；同 `turnId` 重复追加幂等跳过。
- Standards impact: `MC-VALID-001`, `MC-TIME-001`, `MC-TEST-001`。
- Literal rule enforcement: `Rule 2`, `Rule 9`, `Rule 10`, `Rule 11`。
- Required change: 新建 `TurnAlignedChatMemoryTest` 编写 4 类核心断言验证按轮滑窗、工具链完整性、幂等追加及跨租户隔离。
- Verification contribution: Step 3 核心 RED/GREEN 测试。
- After this file: 因目标类尚未创建，测试处于预期编译 RED 状态。

#### File 2 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/InMemorySessionChatMemoryRepository.java`

- Purpose: 提供线程安全的 Level 1 内存仓储，实现 Spring AI `ChatMemoryRepository` 与按 `turn_id` 滑窗读取/追加。
- Symbols: `InMemorySessionChatMemoryRepository`
- Repository evidence: 参考 `InMemoryMemoryStore.java` 使用 `ConcurrentHashMap` 与锁序列化同会话写。
- Dependencies and consumers: 供 `IN_MEMORY` 默认模式与 `TurnAlignedChatMemoryTest` 使用。
- Why now: 实现内存中游测试基准，同时定义一个包内共享的 Turn 感知扩展接口（如 `SessionTurnRepository` 继承 `ChatMemoryRepository`）以统一内存与 JDBC 两种后端。
- Contract/signature changes: 实现 `ChatMemoryRepository` 全部 4 个方法，并在包级接口上提供 `findRecentTurns(tenantKey, conversationId, windowTurns)`、`appendTurn(tenantKey, conversationId, turnId, messages, Instant)`、`deleteTurns(tenantKey, conversationId)`。
- Input/output and state mapping: 内存记录按 `(tenantKey, conversationId)` 隔离存储 `TurnEntry(turnId, List<Message>, Instant)`。
- Error and edge behavior: 重复 `turnId` 幂等忽略；单轮超过 64 条消息拒绝。
- Standards impact: `MC-NAME-001`, `MC-LOG-001`, `MC-TIME-001`, `MC-PATTERN-001`。
- Literal rule enforcement: `Rule 1`, `Rule 4`, `Rule 10`, `Rule 11` — 使用 `@Slf4j`，只用 `Instant`，扁平 `memory` 包。
- Required change: 创建 `InMemorySessionChatMemoryRepository`（以及包内小接口或公共能力）支撑 Spring AI `ChatMemoryRepository` 与按 Turn 读写。
- Verification contribution: 让 `TurnAlignedChatMemoryTest` 无需 DB 即可验证完整滑窗与工具回放逻辑。
- After this file: Level 1 内存后端就绪。

#### File 3 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/TurnAlignedChatMemory.java`

- Purpose: 装饰 Spring AI `ChatMemory` / `ChatMemoryRepository`，统一对外提供默认 20 轮可自定义按 Turn 滑窗及头对齐保护。
- Symbols: `TurnAlignedChatMemory`
- Repository evidence: 校验 Spring AI `org.springframework.ai.chat.memory.ChatMemory` 方法签名；类注解 `@Slf4j` 与 `@RequiredArgsConstructor(onConstructor_ = @Autowired)`。
- Dependencies and consumers: 注入 `@Qualifier` 指定的仓储与 `Clock`（`agentExecutionClock`）；被 `SessionHistoryAdvisor` 消费。
- Why now: 衔接仓储与 Step 5 `SessionHistoryAdvisor`。
- Contract/signature changes: 实现 `ChatMemory` (`add`, `get`, `clear`) 并重载多租户按轮版本 `get(tenantKey, conversationId, windowTurns)`、`appendTurn(...)`。
- Input/output and state mapping: 默认从 `AgentFlowProperties` 读取 `defaultWindowTurns`（默认 `20`），运行期允许每次请求传入自定义 `1..200`。
- Error and edge behavior: 参数非空/越界拦截抛 `CommonException("INVALID_SESSION_WINDOW")`；头部防断链扫描剔除异常孤立 `ToolResponseMessage`。
- Standards impact: `MC-REUSE-001`, `MC-BEAN-001`, `MC-LOG-001`, `MC-PATTERN-001`, `MC-VALID-001`。
- Literal rule enforcement: `Rule 2`, `Rule 4`, `Rule 9`, `Rule 10`, `Rule 11` — 装饰器模式包装 `ChatMemory`，依赖带 `@Qualifier` 与 `@RequiredArgsConstructor(onConstructor_ = @Autowired)`。
- Required change: 创建 `TurnAlignedChatMemory`，实现按轮数裁剪、头部完整 Turn 对齐与 `appendTurn` 委托。
- Verification contribution: 使 `TurnAlignedChatMemoryTest` 转 GREEN。
- After this file: Level 1 核心逻辑与内存单测达到 GREEN。

#### File 4 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/jdbc/JdbcSessionChatMemoryRepository.java`

- Purpose: 实现方案 1B PgSQL `egon_agent_memory.session_message` 仓储，支持 `tool_payload jsonb` 与 `pg_advisory_xact_lock` 并发安全追加。
- Symbols: `JdbcSessionChatMemoryRepository`
- Repository evidence: 参考 `JdbcMemoryStore.java` 的 `JdbcTemplate`、`NamedParameterJdbcTemplate`、`TransactionTemplate`、`JsonMapper` 限制与 `OffsetDateTime` UTC 读写方法。
- Dependencies and consumers: 实现 `ChatMemoryRepository` 及同包/公开 Turn 感知方法；注入 `DataSource`、`schema`、`Clock` 与 `JsonMapper`。
- Why now: Level 1 内存契约验证通过后立即补齐 PgSQL 生产持久化后端。
- Contract/signature changes: 实现 `findRecentTurns`（`WITH recent_turns AS (...)` + `JOIN` 升序返回）、`appendTurn`（事务内 `pg_advisory_xact_lock` + `turn_id` 去重 + `MAX(message_seq)` 批量 `batchUpdate`）与 `ChatMemoryRepository` 标准方法。
- Input/output and state mapping: `AssistantMessage` 序列化 `toolCalls`（id, type, name, arguments）至 `tool_payload`；`ToolResponseMessage` 序列化 `responses`（id, name, responseData）至 `tool_payload`；读取时精确反序列化重建 Spring AI 原生消息子类。
- Error and edge behavior: 单条 `tool_payload` 超 256 KB 拒绝；SQL 异常统一映射为 `CommonException("SESSION_MEMORY_UNAVAILABLE")`（日志脱敏不输出载荷）。
- Standards impact: `MC-BEAN-001`, `MC-JSON-001`, `MC-TIME-001`, `MC-LOG-001`, `MC-VALID-001`。
- Literal rule enforcement: `Rule 4`, `Rule 6`, `Rule 10`, `Rule 11` — `@Slf4j`、Jackson `JsonMapper`、`OffsetDateTime`/`Instant`、`spring-jdbc`。
- Required change: 创建 `JdbcSessionChatMemoryRepository` 实现方案 1B 完整 PgSQL 读写与工具载荷 JSONB 编解码。
- Complex logic (pseudocode):
  - `appendTurn`: 开启 `READ_COMMITTED` 本地事务 -> `pg_advisory_xact_lock` 锁定 `(tenantKey, conversationId)` -> 查询该 `turnId` 是否已存在（存在即幂等返回）-> 读取当前 `COALESCE(MAX(message_seq), 0)` -> 依次编码 `content_text`、`tool_payload`、`created_at` 批量写入 `session_message`。
  - `findRecentTurns`: 执行 Spec §11.2.1 `WITH recent_turns` SQL 查询并将每行恢复为 `UserMessage` / `AssistantMessage(text, toolCalls)` / `ToolResponseMessage(responses)` / `SystemMessage`。
- Verification contribution: 将由 Step 6 `ThreeTierMemoryPostgresTest` 在真实 PgSQL 上验证并发与还原正确性。
- After this file: Level 1 内存与 PgSQL 双实现全部就绪。

- Validation working directory: `Stellar-Enclosure-Components`
- Verification command: `./mvnw -B -ntp -pl stellar-enclosure-component-agent-flow-starter -Dtest=TurnAlignedChatMemoryTest,AgentFlowComponentContractTest test`
- Expected result: Exit 0；`TurnAlignedChatMemoryTest` 全部断言通过且编译无错。
- Failure returns to: 检查 `TurnAlignedChatMemory` 头部对齐逻辑或 `JdbcSessionChatMemoryRepository` 消息序列化逻辑。
- Completion criteria: Level 1 滑窗、不断链与租户隔离单测全绿，JDBC 仓储可编译。
- Rollback: 回退 Step 3 新增的 4 个文件。
- Commit paths: `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/memory/TurnAlignedChatMemoryTest.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/InMemorySessionChatMemoryRepository.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/TurnAlignedChatMemory.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/jdbc/JdbcSessionChatMemoryRepository.java`
- Commit: `feat(agent-flow): implement turn-aligned session chat memory with pgsql store`

---

### Step 4 — 实现 Level 2 `TextMemoryService` 与 Level 3 `DocMemoryReader`（InMemory + PgSQL）

- Requirements: `REQ-003`, `REQ-004`
- Dependencies: `Step 1`, `Step 2`
- Baseline state: Step 1 已发布 `TextMemoryService` 与 `DocMemoryReader` 接口及载体，Step 2 已创建 `text_memory` 与 `doc_memory` DDL。
- Observable outcome: Level 2 内存与 PgSQL 实现均支持幂等绑定下发 `id`、10,000 字符上限严格拒写与乐观锁；Level 3 内存与 PgSQL 实现均支持按传入 `ids` 保序且租户隔离的只读批量查询。
- End state: 三级记忆全部数据提供方实现完毕，供 Step 5 Advisor 链组装。
- Test-first gate: `Required` — 先创建 `TextMemoryServiceTest`（断言 10,000 通过 vs 10,001 严格抛 `TEXT_MEMORY_TOO_LARGE`、乐观锁冲突、绑定幂等）与 `DocMemoryReaderTest`（断言 `[id3, id1]` 严格保序、越权/软删过滤、33 条抛 `DOC_MEMORY_BATCH_LIMIT`）确立 RED，再编写实现类。
- Manual Checks: `MC-ARCH-001`, `MC-REUSE-001`, `MC-NAME-001`, `MC-VALID-001`, `MC-LOG-001`, `MC-BEAN-001`, `MC-TIME-001`, `MC-SCOPE-001`, `MC-TEST-001`
- Literal Rules: `Rule 1`, `Rule 2`, `Rule 4`, `Rule 5`, `Rule 10`, `Rule 11`
- Ordered files:

#### File 1 — `CREATE stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/memory/TextMemoryServiceTest.java`

- Purpose: 建立 Level 2 绑定幂等、10,000 字符硬上限拒写与乐观锁并发冲突的 RED 单测。
- Symbols: `bindOrCreateReturnsStableIdForSameOwner`, `saveAllowsUpToTenThousandCharsAndRejectsTenThousandAndOne`, `saveRejectsStaleVersionWithoutMutatingStoredText`
- Repository evidence: 参考 `InMemoryMemoryStore` 测试与 Jakarta Validation 校验模式。
- Dependencies and consumers: 测试 `InMemoryTextMemoryService`（PgSQL 测试将在 Step 6 运行）。
- Why now: Test-first 锁定 Level 2 严格拒绝与乐观锁语义。
- Contract/signature changes: 调用 `TextMemoryService#bindOrCreate`、`#getById`、`#save`。
- Input/output and state mapping: 构造 10,000 字符与 10,001 字符文本验证边界；验证版本递增与旧版本冲突保留原文本。
- Error and edge behavior: 10,001 字符抛 `CommonException("TEXT_MEMORY_TOO_LARGE")` 或 `ConstraintViolationException`；旧版本抛 `CommonException("VERSION_CONFLICT")`；跨租户读取返回 `Optional.empty()`。
- Standards impact: `MC-VALID-001`, `MC-TIME-001`, `MC-TEST-001`。
- Literal rule enforcement: `Rule 2`, `Rule 10`, `Rule 11`。
- Required change: 创建 `TextMemoryServiceTest` 覆盖幂等绑定、字符硬上限分界、乐观锁与租户隔离。
- Verification contribution: Level 2 RED/GREEN 门禁。
- After this file: 因实现类尚未创建，测试处于预期编译 RED 状态。

#### File 2 — `CREATE stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/memory/DocMemoryReaderTest.java`

- Purpose: 建立 Level 3 按传入 `ids` 保序、过滤跨租户与软删、及拒绝超批上限的 RED 单测。
- Symbols: `findByIdsPreservesCallerOrderAndFiltersTenantAndDeleted`, `findByIdsShortCircuitsEmptyAndRejectsOverThirtyTwo`
- Repository evidence: 参考 `DocMemoryReader` SPI 契约。
- Dependencies and consumers: 测试 `InMemoryDocMemoryReader`。
- Why now: Test-first 锁定 Level 3 只读、保序与越权隔离行为。
- Contract/signature changes: 调用 `DocMemoryReader#findByIds(DocMemoryQuery)`。
- Input/output and state mapping: 传入 `[id3, id1, id_other, id_deleted]` 期望严格返回 `[id3, id1]`。
- Error and edge behavior: 传入空列表返空；传入 33 条抛 `CommonException("DOC_MEMORY_BATCH_LIMIT")` 或 `ConstraintViolationException`。
- Standards impact: `MC-VALID-001`, `MC-TEST-001`。
- Literal rule enforcement: `Rule 2`, `Rule 10`, `Rule 11`。
- Required change: 创建 `DocMemoryReaderTest` 验证顺序保持、去重、越权过滤、软删过滤与 32 条批量上限。
- Verification contribution: Level 3 RED/GREEN 门禁。
- After this file: 因实现类尚未创建，测试处于预期编译 RED 状态。

#### File 3 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/InMemoryTextMemoryService.java`

- Purpose: 提供 Level 2 内存实现（供 `IN_MEMORY` 模式及单测）。
- Symbols: `InMemoryTextMemoryService`
- Repository evidence: 参考 `InMemoryMemoryStore.java` 并发锁与 `ValidationUtils`、`Clock` 注入样式。
- Dependencies and consumers: 实现 `TextMemoryService`；依赖 `@Qualifier("agentExecutionClock")` `Clock` 与 `@Qualifier("egonColaValidationUtils")` `ValidationUtils`。
- Why now: 实现 Level 2 内存路径使 File 1 测试通过。
- Contract/signature changes: 严格执行 `maxChars`（默认 `10_000`）校验，超限抛 `CommonException("TEXT_MEMORY_TOO_LARGE")`；版本不匹配抛 `CommonException("VERSION_CONFLICT")`；跨租户读返空、写抛 `TEXT_MEMORY_NOT_FOUND`。
- Input/output and state mapping: `Instant` 通过 `clock.instant().truncatedTo(ChronoUnit.MICROS)` 生成；`bindOrCreate` 使用 `tm_` + UUID 生成唯一 `id`。
- Error and edge behavior: 同步槽锁保证单进程并发更新原子性。
- Standards impact: `MC-NAME-001`, `MC-VALID-001`, `MC-BEAN-001`, `MC-LOG-001`, `MC-TIME-001`。
- Literal rule enforcement: `Rule 1`, `Rule 2`, `Rule 4`, `Rule 10`, `Rule 11` — `@Slf4j`、`@RequiredArgsConstructor(onConstructor_ = @Autowired)`、`@Qualifier`、严禁静默截断。
- Required change: 创建 `InMemoryTextMemoryService` 实现幂等绑定、严格 10,000 字符上限拒写与乐观锁更新。
- Verification contribution: 使 `TextMemoryServiceTest` 转 GREEN。
- After this file: Level 2 内存单测达到 GREEN。

#### File 4 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/InMemoryDocMemoryReader.java`

- Purpose: 提供 Level 3 内存只读实现（供 `IN_MEMORY` 模式、单元测试及宿主测试夹具）。
- Symbols: `InMemoryDocMemoryReader`
- Repository evidence: 实现 `DocMemoryReader` SPI。
- Dependencies and consumers: 依赖 `ValidationUtils` 与内存只读 Map 视图（支持测试夹具预置或默认空视图）。
- Why now: 实现 Level 3 内存路径使 File 2 测试通过。
- Contract/signature changes: 按 `DocMemoryQuery.snapshot()` 依次查出属于 `tenantKey` 且未软删文档。
- Input/output and state mapping: 保序输出 `List<DocMemoryVO>`。
- Error and edge behavior: 超 32 条在 `snapshot()` / `ValidationUtils` 拦截抛异常；未配置数据时安全返回空列表。
- Standards impact: `MC-NAME-001`, `MC-VALID-001`, `MC-BEAN-001`, `MC-LOG-001`。
- Literal rule enforcement: `Rule 1`, `Rule 2`, `Rule 4`, `Rule 11`。
- Required change: 创建 `InMemoryDocMemoryReader` 实现内存保序过滤查询。
- Verification contribution: 使 `DocMemoryReaderTest` 转 GREEN。
- After this file: Level 3 内存单测达到 GREEN。

#### File 5 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/jdbc/JdbcTextMemoryService.java`

- Purpose: 提供 Level 2 PgSQL `egon_agent_memory.text_memory` 持久化实现。
- Symbols: `JdbcTextMemoryService`
- Repository evidence: 参考 `JdbcMemoryStore.java` `TransactionTemplate`、`queryTimeout=5`、`OffsetDateTime` UTC 转换与数据库错误映射。
- Dependencies and consumers: 实现 `TextMemoryService`；注入 `DataSource`, `schema`, `maxChars`, `Clock`, `ValidationUtils`。
- Why now: 在契约和内存验证就绪后提供 PgSQL 生产实现。
- Contract/signature changes: `bindOrCreate` 使用 `INSERT ... ON CONFLICT (tenant_key, namespace, owner_type, owner_key) DO NOTHING` 后 `SELECT`；`save` 前置校验 `<= maxChars` 后用 `UPDATE ... WHERE id=? AND tenant_key=? AND version=?` 更新；使用 `clock.instant().truncatedTo(ChronoUnit.MICROS)` 与 `OffsetDateTime` UTC 绑定。
- Input/output and state mapping: `content_text` 超限不入数据库直接抛 `CommonException("TEXT_MEMORY_TOO_LARGE")`；数据库 `23514` check violation 兜底同样映射为 `TEXT_MEMORY_TOO_LARGE`。
- Error and edge behavior: 0 行更新再查是否存在区分 `TEXT_MEMORY_NOT_FOUND` 与 `VERSION_CONFLICT`。
- Standards impact: `MC-VALID-001`, `MC-BEAN-001`, `MC-LOG-001`, `MC-TIME-001`。
- Literal rule enforcement: `Rule 2`, `Rule 4`, `Rule 10`, `Rule 11`。
- Required change: 创建 `JdbcTextMemoryService` 实现幂等 `id` 绑定、10k 字符硬校验与 PgSQL 乐观锁更新。
- Verification contribution: 将由 Step 6 `ThreeTierMemoryPostgresTest` 验证真实 PG 行为。
- After this file: Level 2 PgSQL 实现可编译。

#### File 6 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/jdbc/JdbcDocMemoryReader.java`

- Purpose: 提供 Level 3 PgSQL 只读保序批量查询实现，支持可配置表名与列名映射。
- Symbols: `JdbcDocMemoryReader`
- Repository evidence: Spec §11.2.3 SQL 与 `AgentFlowProperties.DocSettings`。
- Dependencies and consumers: 实现 `DocMemoryReader`；注入 `DataSource`, `schema`, `DocSettings`, `ValidationUtils`。
- Why now: 完成 Level 3 PgSQL 生产实现。
- Contract/signature changes: 构造期对 `schema`、`tableName` 及各列名执行 `[a-z_][a-z0-9_]{0,62}` 严格白名单正则校验；运行时使用 `unnest(?::varchar[]) WITH ORDINALITY` 保序查询 `WHERE tenant_key=? AND deleted=false`。
- Input/output and state mapping: `rs.getObject(..., OffsetDateTime.class).toInstant()` 投影为不可变 `DocMemoryVO`。
- Error and edge behavior: 空 `ids` 短路返回 `List.of()`；SQL 错误映射为 `CommonException("DOC_MEMORY_UNAVAILABLE")`；绝不暴露任何写方法。
- Standards impact: `MC-VALID-001`, `MC-BEAN-001`, `MC-LOG-001`, `MC-TIME-001`。
- Literal rule enforcement: `Rule 2`, `Rule 4`, `Rule 10`, `Rule 11`。
- Required change: 创建 `JdbcDocMemoryReader` 实现白名单配置化 PgSQL 批量保序只读查询。
- Verification contribution: 将由 Step 6 `ThreeTierMemoryPostgresTest` 验证真实 PG 行为与只读角色隔离。
- After this file: Level 2/3 内存与 PgSQL 全部实现完成。

- Validation working directory: `Stellar-Enclosure-Components`
- Verification command: `./mvnw -B -ntp -pl stellar-enclosure-component-agent-flow-starter -Dtest=TextMemoryServiceTest,DocMemoryReaderTest,AgentFlowComponentContractTest test`
- Expected result: Exit 0；`TextMemoryServiceTest` 与 `DocMemoryReaderTest` 全绿，`api.*` 契约保持洁净。
- Failure returns to: 回查 Step 4 对应测试预期与实现类约束检验。
- Completion criteria: Level 2 10,000 字符边界、乐观锁及 Level 3 保序/租户隔离/批量上限测试全部通过。
- Rollback: 删除 Step 4 新增的 6 个文件。
- Commit paths: `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/memory/TextMemoryServiceTest.java`, `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/memory/DocMemoryReaderTest.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/InMemoryTextMemoryService.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/InMemoryDocMemoryReader.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/jdbc/JdbcTextMemoryService.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/memory/jdbc/JdbcDocMemoryReader.java`
- Commit: `feat(agent-flow): implement level-2 text memory service and level-3 readonly doc reader`

---

### Step 5 — 实现一体化 Context 预算引擎、Spring AI `BaseAdvisor` 链与 `SpringAiContextChatClient`

- Requirements: `REQ-001`, `REQ-002`, `REQ-003`, `REQ-004`, `REQ-005`
- Dependencies: `Step 1`, `Step 3`, `Step 4`
- Baseline state: 三级记忆接口与实现已就绪；`spring-ai-client-chat` 已引入；尚未存在 `ContextEnvelopeBO`、`ContextAssemblyEngine` 与 Spring AI Advisor 链。
- Observable outcome: 五区上下文通过 Spring AI `BaseAdvisor` 责任链（Order `100`~`900`）统一采集、经 `ContextAssemblyEngine` 核算 Token 预算按序淘汰裁剪并防注入隔离渲染成标准 `Prompt`，同步/流式执行均正常且失败/取消不写回 Level 1。
- End state: 路径 A 核心执行管线完全可用并有全面单测覆盖。
- Test-first gate: `Required` — 先创建 `ContextAssemblyEngineTest`（测试预算扣减、淘汰顺序、防注入隔离标签、`combineContextIntoFinalUserMessage` 双模式、`CONTEXT_BUDGET_EXCEEDED` 快速失败）与 `SpringAiContextChatClientTest`（测试完整 Advisor 链路调用、自定义 `windowTurns` 覆盖、带工具调用回写以及流式中断不写入 Level 1）确立 RED。
- Manual Checks: `MC-ARCH-001`, `MC-REUSE-001`, `MC-NAME-001`, `MC-VALID-001`, `MC-MODEL-001`, `MC-LOG-001`, `MC-BEAN-001`, `MC-PATTERN-001`, `MC-SCOPE-001`, `MC-TEST-001`
- Literal Rules: `Rule 1`, `Rule 2`, `Rule 3`, `Rule 4`, `Rule 5`, `Rule 9`, `Rule 11`
- Ordered files:

#### File 1 — `CREATE stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/context/ContextAssemblyEngineTest.java`

- Purpose: 建立一体化 Context 预算核算、淘汰优先级、防注入隔离与两种渲染模式的 RED 单测。
- Symbols: `keepsSystemPromptIsolatedAndWrapsMemoryInReferenceBlock`, `trimsOthersThenTailDocsThenTextThenOldTurns`, `failsFastWhenSystemAndUserExceedBudget`, `supportsCombiningContextIntoFinalUserMessage`
- Repository evidence: 参考 `spring-ai-commons` `JTokkitTokenCountEstimator` 与既有 `DefaultContextAssembler` 测试风格。
- Dependencies and consumers: 测试 `ContextAssemblyEngine`、`ContextEnvelopeBO`、`ContextBudgetPolicyVO` 与 `ContextAssemblyReportVO`。
- Why now: 先锁定预算算法与渲染边界。
- Contract/signature changes: 测试构造五区 `ContextEnvelopeBO` 与不同 `contextWindowTokens` 执行 `assemble(...)`。
- Input/output and state mapping: 验证输出的 `Prompt` 消息结构顺序与 `ContextAssemblyReportVO` 裁剪列表（`BUDGET_OTHERS` -> `BUDGET_DOC_MEMORY` -> `BUDGET_TEXT_MEMORY` -> `BUDGET_HISTORY_TURN`）。
- Error and edge behavior: 当 `System + User` 或保底 `minKeepHistoryTurns` 超限时抛出 `CommonException("CONTEXT_BUDGET_EXCEEDED")`；历史截断后无孤立 `ToolResponseMessage`。
- Standards impact: `MC-PATTERN-001`, `MC-VALID-001`, `MC-TEST-001`。
- Literal rule enforcement: `Rule 1`, `Rule 3`, `Rule 9`, `Rule 11`。
- Required change: 新建 `ContextAssemblyEngineTest` 覆盖五区隔离渲染、渐进式预算压缩、保底轮次与超限失败。
- Verification contribution: 验证 `ContextAssemblyEngine` 核心预算仲裁逻辑。
- After this file: 因 `ContextAssemblyEngine` 尚未创建，测试处于编译 RED 状态。

#### File 2 — `CREATE stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/adapter/springai/SpringAiContextChatClientTest.java`

- Purpose: 建立 Spring AI `ChatClient` + 6 个 `BaseAdvisor` 端到端集成单测（使用 Fake `ChatModel`）。
- Symbols: `executesFullAdvisorChainAndAppendsTurnOnSuccess`, `doesNotAppendSessionHistoryOnModelErrorOrStreamCancel`, `honorsCustomWindowTurnsAndPassesOrderedDocsToModel`, `executesToolCallLoopAndPersistsFullTurn`
- Repository evidence: 参考 `AdkCompatibilityFixtures.java` Fake `ChatModel` 与 `DefaultAgentExecutor` 测试模式。
- Dependencies and consumers: 测试 `SpringAiContextChatClient` 与全部 6 个 Advisor 及三级 Memory 内存实现。
- Why now: 锁定同步/流式生命周期、Advisor 执行顺序以及失败/取消回写保护。
- Contract/signature changes: 构造 `AgentContextCallBO` 执行 `call` 与 `stream`。
- Input/output and state mapping: 核对到达 Fake `ChatModel` 的最终 `Prompt` 是否包含纯净 `SystemMessage`、滑窗历史、`<context_data>` 内 Level 2/3/Others 内容与末位 `UserMessage`，并核对成功后 Level 1 新增完整 Turn。
- Error and edge behavior: 模拟 `ChatModel.stream` 中途发出 `onError` 或下游 `take(1)` 提前取消订阅，断言 Level 1 无新消息写入。
- Standards impact: `MC-ARCH-001`, `MC-PATTERN-001`, `MC-TEST-001`, `MC-SCOPE-001`。
- Literal rule enforcement: `Rule 4`, `Rule 9`, `Rule 11`。
- Required change: 新建 `SpringAiContextChatClientTest` 覆盖 Advisor 顺序、工具调用完整持久化与流式失败零回写。
- Verification contribution: 证明一体化管线行为闭环。
- After this file: 因 Advisor 与门面尚未创建，测试处于编译 RED 状态。

#### File 3 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/context/ContextBudgetPolicyVO.java`

- Purpose: 封装预算策略的不可变值对象。
- Symbols: `ContextBudgetPolicyVO(int contextWindowTokens, int maxOutputTokens, int safetyMarginTokens, int minKeepHistoryTurns, boolean combineContextIntoFinalUserMessage)`
- Repository evidence: 不可变 `record` 设计模式。
- Dependencies and consumers: `ContextAssemblyEngine` 与 `SpringAiContextChatClient`。
- Why now: 先于 `ContextAssemblyEngine` 创建。
- Contract/signature changes: 紧凑构造器校验 `contextWindowTokens > 0`, `maxOutputTokens > 0`, `safetyMarginTokens >= 0`, `minKeepHistoryTurns >= 0` 且 `contextWindowTokens > maxOutputTokens + safetyMarginTokens`。
- Input/output and state mapping: 计算 `availableInputBudget = contextWindowTokens - maxOutputTokens - safetyMarginTokens`。
- Error and edge behavior: 参数不合理抛 `IllegalArgumentException`。
- Standards impact: `MC-NAME-001`, `MC-MODEL-001`, `MC-VALID-001`。
- Literal rule enforcement: `Rule 1`, `Rule 3`, `Rule 11`。
- Required change: 创建 `ContextBudgetPolicyVO` 记录。
- Verification contribution: Step 5 预算配置承载。
- After this file: `ContextBudgetPolicyVO` 可编译。

#### File 4 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/context/ContextEnvelopeBO.java`

- Purpose: 作为单次执行期间在 `ChatClientRequest.context()` 内流转的五区可组装业务容器。
- Symbols: `ContextEnvelopeBO`（Key 常量 `CONTEXT_KEY = "egon.agentflow.context.envelope"`，报告 Key `REPORT_KEY = "egon.agentflow.context.report"`）
- Repository evidence: Rule 3 规定内部可变业务载体使用 `class` + Lombok 基线注解。
- Dependencies and consumers: 6 个 Advisor 与 `ContextAssemblyEngine`。
- Why now: Advisor 间共享状态数据结构前置。
- Contract/signature changes: 字段包含 `ContextScopeVO scope`、`String systemPrompt`、`UserMessage userMessage`、`List<Message> sessionHistory`、`TextMemoryVO textMemory`、`List<DocMemoryVO> docMemories`、`List<String> otherFragments`、`List<ToolDefinition> toolDefinitions`、`int windowTurns`、`ContextBudgetPolicyVO budgetPolicy`。
- Input/output and state mapping: 每个区域字段职责单一、彼此隔离。
- Error and edge behavior: 默认初始化为空可变集合防 NPE。
- Standards impact: `MC-NAME-001`, `MC-MODEL-001`。
- Literal rule enforcement: `Rule 1`, `Rule 3`, `Rule 11`。
- Required change: 创建 `ContextEnvelopeBO` 类携带五区待组装素材及单次预算策略。
- Verification contribution: 贯穿 Step 5 全部测试。
- After this file: `ContextEnvelopeBO` 可编译。

#### File 5 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/context/ContextAssemblyEngine.java`

- Purpose: 核心收口引擎：基于 Spring AI `TokenCountEstimator` 执行五区预算核算、按序淘汰、防注入标签渲染与生成 `ContextAssemblyReportVO`。
- Symbols: `ContextAssemblyEngine`（方法 `AssembledPromptVO assemble(ContextEnvelopeBO envelope, ChatOptions options)`）
- Repository evidence: 复用 `TokenCountEstimator`（`JTokkitTokenCountEstimator`）与 `SystemMessage`/`UserMessage`/`Prompt`。
- Dependencies and consumers: 注入 `@Qualifier("agentTokenCountEstimator")` `TokenCountEstimator`；被 `ContextAssemblyAdvisor` 与 `ContextAssemblyEngineTest` 消费。
- Why now: 预算载体就绪，可实现核心仲裁算法使 File 1 测试通过。
- Contract/signature changes: 输出内部轻量不可变 `record AssembledPromptVO(Prompt prompt, ContextAssemblyReportVO report)`。
- Input/output and state mapping: 严格按 `System` -> `User` -> `toolDefinitions` -> 保底 `minKeepHistoryTurns` -> 其余 `History` -> `TextMemory` -> `DocMemories` -> `Others` 评估，超额按逆序裁剪并记录 `ContextTrimDetailVO`。
- Error and edge behavior: 必需基础区超预算立即抛 `CommonException("CONTEXT_BUDGET_EXCEEDED")`；若 `TextMemory` 被裁剪则记 `BUDGET_TEXT_MEMORY` 或在配置严格模式下报错；裁剪 `History` 按整轮 `turn_id` / `UserMessage` 起始点对齐保证不断工具链。
- Standards impact: `MC-REUSE-001`, `MC-BEAN-001`, `MC-LOG-001`, `MC-PATTERN-001`, `MC-VALID-001`。
- Literal rule enforcement: `Rule 1`, `Rule 4`, `Rule 9`, `Rule 11` — `@Slf4j`、`@RequiredArgsConstructor(onConstructor_ = @Autowired)`、`@Qualifier`。
- Required change: 创建 `ContextAssemblyEngine` 完成五区 Token 预算计算、逆序按策略淘汰与防注入 `<context_data>` 隔离渲染。
- Complex logic (pseudocode):
  - 计算 `available = budget.contextWindowTokens - budget.maxOutputTokens - budget.safetyMarginTokens - estimate(toolDefinitions)`。
  - 若 `estimate(systemPrompt) + estimate(userMessage) > available`，直接抛 `CONTEXT_BUDGET_EXCEEDED`。
  - 先按完整性分组拆分 `sessionHistory` 为完整 Turn 列表，保底加载末尾 `minKeepHistoryTurns` 轮；若保底已超 `available` 则抛 `CONTEXT_BUDGET_EXCEEDED`。
  - 其余可选区域按 `[剩余 History Turn, TextMemory, DocMemories(保序), Others]` 汇总，当总估算超出 `available` 时循环淘汰：`1) Others 末项 -> 2) DocMemories 末篇 -> 3) TextMemory -> 4) 当前最旧完整 History Turn（止于 minKeepHistoryTurns）`，每步记录 `ContextTrimDetailVO`。
  - 按 `combineContextIntoFinalUserMessage` 决定将 `<context_data trust="reference_only">` 独立作为 `UserMessage` 放在历史与最终输入之间，或置入最终 `UserMessage` 顶部，同时保持 `SystemMessage` 纯净并构造最终 `Prompt` 与 `ContextAssemblyReportVO`。
- Verification contribution: 使 `ContextAssemblyEngineTest` 转 GREEN。
- After this file: 核心汇编算法达到 GREEN。

#### File 6 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/adapter/springai/ContextInitAdvisor.java`

- Purpose: `Order = 100`，负责校验并初始化单次调用的 `ContextEnvelopeBO`（写入 System Prompt、User Prompt、工具定义与默认策略）。
- Symbols: `ContextInitAdvisor implements BaseAdvisor`（`getOrder() == 100`）
- Repository evidence: 实现 `spring-ai-client-chat` `BaseAdvisor` 接口。
- Dependencies and consumers: `SpringAiContextChatClient` 挂载并由 `ChatClient` 驱动。
- Why now: Advisor 链起点。
- Contract/signature changes: 若 `context()` 已有 `ContextEnvelopeBO` 则补全 `Prompt` 中的初始 System/User 文本；保证 `SystemPrompt` 与 `UserMessage` 非空。
- Input/output and state mapping: `ChatClientRequest` -> 初始化 `ContextEnvelopeBO` -> 继续链。
- Error and edge behavior: `systemPrompt` 或 `userMessage` 为空抛 `CommonException("INVALID_CONTEXT_INPUT")`。
- Standards impact: `MC-NAME-001`, `MC-LOG-001`, `MC-PATTERN-001`。
- Literal rule enforcement: `Rule 1`, `Rule 4`, `Rule 9`, `Rule 11`。
- Required change: 创建 `ContextInitAdvisor` 完成链路初始化与首轮入参校验。
- Verification contribution: `SpringAiContextChatClientTest`。
- After this file: Order 100 就绪。

#### File 7 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/adapter/springai/SessionHistoryAdvisor.java`

- Purpose: `Order = 200`，在 `before` 加载 Level 1（默认 20 轮、支持覆盖）至 `ContextEnvelopeBO`；仅在模型完整成功返回后于 `after` 将本轮新消息写入 `TurnAlignedChatMemory`。
- Symbols: `SessionHistoryAdvisor implements BaseAdvisor`（`getOrder() == 200`，上下文参数 Key `CONVERSATION_ID`、`WINDOW_TURNS`、`TURN_ID`）
- Repository evidence: 参考 Spring AI `MessageChatMemoryAdvisor` 的 `before`/`after` 语义及流式聚合完成钩子。
- Dependencies and consumers: 注入 `@Qualifier("turnAlignedChatMemory")` `TurnAlignedChatMemory`。
- Why now: 紧接初始化加载 Level 1 并负责全链路成功后的回写闭环。
- Contract/signature changes: 重写 `before`、`after` 及 `adviseStream`（注意：流式模式下**必须**借助 Spring AI `MessageAggregator` 或等待 `Flux` 完整 `onComplete` 且无错误/取消时才提取完整 `ChatResponse` 写入 Level 1，取消或异常直接跳过写入！）。
- Input/output and state mapping: 从 `TurnAlignedChatMemory.get(tenantKey, conversationId, windowTurns)` 读历史写入 `envelope.sessionHistory`；成功后取出初始用户输入、工具交互与模型终态回复归同一 `turnId` 写入 `appendTurn`。
- Error and edge behavior: 模型出错或流式提前取消不触发 `appendTurn`。
- Standards impact: `MC-REUSE-001`, `MC-BEAN-001`, `MC-LOG-001`, `MC-PATTERN-001`。
- Literal rule enforcement: `Rule 1`, `Rule 4`, `Rule 9`, `Rule 11`。
- Required change: 创建 `SessionHistoryAdvisor`，同时处理同步与流式聚合完成后的原子 Turn 回写保护。
- Complex logic (pseudocode):
  - `before`: 读取 `ContextEnvelopeBO` 中的 `tenantKey`、`conversationId` 与 `windowTurns`，加载历史写入 `envelope.sessionHistory`。
  - `after`（同步）: 检查 `ChatClientResponse.chatResponse()` 存在有效结果后调用 `chatMemory.appendTurn` 写入本轮新消息（含中间工具交互若有）。
  - `adviseStream`（流式）: 执行 `before` -> 透传上游 `Flux` 并在流过程中收集增量与聚合完整文本（或使用 `MessageAggregator` 缓存聚合），仅当 `doOnComplete`（非取消且无错）且聚合出非空终态回答时执行一次 `appendTurn`；`doOnCancel` / `doOnError` 均不写入。
- Verification contribution: `SpringAiContextChatClientTest` 同步/工具/流式取消用例。
- After this file: Order 200 就绪。

#### File 8 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/adapter/springai/TextMemoryAdvisor.java`

- Purpose: `Order = 300`，根据 `textMemoryId` 读取 Level 2 中期文本装入 `ContextEnvelopeBO`。
- Symbols: `TextMemoryAdvisor implements BaseAdvisor`（`getOrder() == 300`，上下文 Key `TEXT_MEMORY_ID`）
- Repository evidence: `BaseAdvisor` 契约与 `TextMemoryService` 接口。
- Dependencies and consumers: 注入 `@Qualifier("agentTextMemoryService")` `TextMemoryService`。
- Why now: 承接 Level 1 之后装载 Level 2。
- Contract/signature changes: 若未传入 `textMemoryId` 则跳过；若传入则调用 `textMemoryService.getById(tenantKey, id)` 装入 `envelope.textMemory`。
- Input/output and state mapping: `TextMemoryVO` -> `envelope.textMemory`。
- Error and edge behavior: 不存在的 `textMemoryId` 若配置为严格模式抛 `TEXT_MEMORY_NOT_FOUND`，默认或空跳过可选配置。
- Standards impact: `MC-BEAN-001`, `MC-LOG-001`, `MC-PATTERN-001`。
- Literal rule enforcement: `Rule 1`, `Rule 4`, `Rule 9`, `Rule 11`。
- Required change: 创建 `TextMemoryAdvisor` 实现按 `textMemoryId` 装填 Level 2 中期文本。
- Verification contribution: `SpringAiContextChatClientTest`。
- After this file: Order 300 就绪。

#### File 9 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/adapter/springai/DocMemoryAdvisor.java`

- Purpose: `Order = 400`，按传入 `docMemoryIds` 调用 `DocMemoryReader` 只读批量装载 Level 3 Markdown 文档装入 `ContextEnvelopeBO`。
- Symbols: `DocMemoryAdvisor implements BaseAdvisor`（`getOrder() == 400`，上下文 Key `DOC_MEMORY_IDS`）
- Repository evidence: `BaseAdvisor` 与 `DocMemoryReader` SPI。
- Dependencies and consumers: 注入 `@Qualifier("agentDocMemoryReader")` `DocMemoryReader`。
- Why now: 承接 Level 2 之后装载 Level 3。
- Contract/signature changes: 若 `docMemoryIds` 为空则跳过不查库；非空时构造 `DocMemoryQuery(tenantKey, ids)` 调用 `findByIds` 并填入 `envelope.docMemories`。
- Input/output and state mapping: `List<DocMemoryVO>` 严格保留传入 `ids` 顺序。
- Error and edge behavior: 超出 32 条由 `DocMemoryQuery`/`DocMemoryReader` 明确报错拦截。
- Standards impact: `MC-BEAN-001`, `MC-LOG-001`, `MC-PATTERN-001`。
- Literal rule enforcement: `Rule 1`, `Rule 4`, `Rule 9`, `Rule 11`。
- Required change: 创建 `DocMemoryAdvisor` 装填保序只读 Markdown 文档。
- Verification contribution: `SpringAiContextChatClientTest`。
- After this file: Order 400 就绪。

#### File 10 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/adapter/springai/OthersContextAdvisor.java`

- Purpose: `Order = 500`，收集额外运行时上下文与可选 `ContextSource`（如现有 `RagContextSource`）片段填充至 `ContextEnvelopeBO.otherFragments`。
- Symbols: `OthersContextAdvisor implements BaseAdvisor`（`getOrder() == 500`）
- Repository evidence: 复用既有 `api/context/ContextSource.java` 与 `ContextQuery`。
- Dependencies and consumers: 可选传入额外片段或 `ContextSource` + `ContextQuery` + `CallPermitVO`。
- Why now: 完成五大区域中 `Others` 区域的采集，留给 `Order = 900` 统一预算裁剪。
- Contract/signature changes: 收集宿主传入额外运行时文本片段及可选 `ContextSource.resolve` 结果写入 `envelope.otherFragments`。
- Input/output and state mapping: `FragmentVO` 正文投影为带 `sourceRef` 标注的数据文本写入 `otherFragments`。
- Error and edge behavior: 若 `ContextSource` 显式报错按配置透传或记录降级（与 `RagContextSource` fail-closed 一致）。
- Standards impact: `MC-REUSE-001`, `MC-LOG-001`, `MC-PATTERN-001`。
- Literal rule enforcement: `Rule 1`, `Rule 4`, `Rule 9`, `Rule 11`。
- Required change: 创建 `OthersContextAdvisor` 装填 RAG 与运行时补充信息至 `Others` 区域。
- Verification contribution: `SpringAiContextChatClientTest`。
- After this file: Order 500 就绪，五区数据收集完毕。

#### File 11 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/adapter/springai/ContextAssemblyAdvisor.java`

- Purpose: `Order = 900` 核心收口拦截器，调用 `ContextAssemblyEngine` 对 `ContextEnvelopeBO` 进行统一预算核算、按序淘汰并重写 `ChatClientRequest.prompt()`。
- Symbols: `ContextAssemblyAdvisor implements BaseAdvisor`（`getOrder() == 900`）
- Repository evidence: `ChatClientRequest#mutate()`、`ContextAssemblyEngine`。
- Dependencies and consumers: 注入 `@Qualifier("agentContextAssemblyEngine")` `ContextAssemblyEngine`。
- Why now: 100~500 采集完成后紧接着进入 900 统一收口重构 `Prompt`。
- Contract/signature changes: `before` 将 `AssembledPromptVO.prompt()` 替换进 `ChatClientRequest`，同时将 `AssembledPromptVO.report()` 写入 `context().put(REPORT_KEY, report)` 并触发可选报告回调。
- Input/output and state mapping: 原始零散输入 -> 唯一合规、裁剪并隔离渲染的标准 `Prompt`。
- Error and edge behavior: `CONTEXT_BUDGET_EXCEEDED` 直接中止调用链，不触达 `ChatModel`。
- Standards impact: `MC-BEAN-001`, `MC-LOG-001`, `MC-PATTERN-001`。
- Literal rule enforcement: `Rule 1`, `Rule 4`, `Rule 9`, `Rule 11`。
- Required change: 创建 `ContextAssemblyAdvisor` 衔接 `ContextAssemblyEngine` 与 Spring AI `ChatClientRequest` 重建。
- Verification contribution: `SpringAiContextChatClientTest`。
- After this file: Order 900 收口就绪。

#### File 12 — `CREATE stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/adapter/springai/SpringAiContextChatClient.java`

- Purpose: 为宿主应用提供面向路径 A 的开箱即用 Spring AI 一体化执行入口及载体。
- Symbols: `SpringAiContextChatClient`, `AgentContextCallBO`（可变执行入参 `class` + Lombok 基线）, `AgentContextCallResultVO`（不可变结果 `record`）
- Repository evidence: 参考 `ChatClient.builder(chatModel).defaultAdvisors(...)` 与 `ToolCallAdvisor`。
- Dependencies and consumers: 注入 6 个 Advisor（`@Qualifier`）、`ValidationUtils`、`AgentFlowProperties`；内部使用 `ChatClient`（及按需挂载 `ToolCallAdvisor`）。
- Why now: 将 Step 5 所有构件包装为单一简洁门面，使 File 2 测试全绿。
- Contract/signature changes: 提供 `call(AgentContextCallBO)` 与 `stream(AgentContextCallBO, Consumer<ContextAssemblyReportVO>)`；校验 `AgentContextCallBO`（`ContextScopeVO`、`ChatModel`、`systemPrompt`、`userPrompt`、可选 `textMemoryId`、`docMemoryIds`、`windowTurns`、`budgetPolicy`、`toolCallbacks`）。
- Input/output and state mapping: 装配初始 `ContextClientRequest` 上下文参数 -> 驱动 `ChatClient` 执行（含 `ToolCallAdvisor` 工具循环）-> 提取输出与 `ContextAssemblyReportVO`。
- Error and edge behavior: 校验失败抛 `ConstraintViolationException`；预算或模型错误透传 `CommonException`。
- Standards impact: `MC-REUSE-001`, `MC-NAME-001`, `MC-VALID-001`, `MC-MODEL-001`, `MC-BEAN-001`, `MC-LOG-001`, `MC-PATTERN-001`。
- Literal rule enforcement: `Rule 1`, `Rule 2`, `Rule 3`, `Rule 4`, `Rule 9`, `Rule 11` — `AgentContextCallBO` 使用 `class` + 完整 Lombok 基线，`AgentContextCallResultVO` 使用 `record`，依赖全带 `@Qualifier` 与 `@RequiredArgsConstructor(onConstructor_ = @Autowired)`。
- Required change: 创建 `SpringAiContextChatClient` 及其内/同包入参 `AgentContextCallBO` 与不可变结果 `AgentContextCallResultVO`。
- Verification contribution: 使 `SpringAiContextChatClientTest` 达到 GREEN。
- After this file: 路径 A 端到端单测完全通过。

- Validation working directory: `Stellar-Enclosure-Components`
- Verification command: `./mvnw -B -ntp -pl stellar-enclosure-component-agent-flow-starter -Dtest=ContextAssemblyEngineTest,SpringAiContextChatClientTest,TurnAlignedChatMemoryTest,TextMemoryServiceTest,DocMemoryReaderTest test`
- Expected result: Exit 0；全部单元测试与 Advisor 集成单测通过。
- Failure returns to: 检查 `ContextAssemblyEngine` 淘汰算法或 `SessionHistoryAdvisor` 流式完成回写钩子。
- Completion criteria: 五区裁剪顺序、工具回放、流式取消零回写、10k 硬拒写与保序查询全部通过单测。
- Rollback: 删除 Step 5 新增的 12 个文件。
- Commit paths: `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/context/ContextAssemblyEngineTest.java`, `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/adapter/springai/SpringAiContextChatClientTest.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/context/ContextBudgetPolicyVO.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/context/ContextEnvelopeBO.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/context/ContextAssemblyEngine.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/adapter/springai/ContextInitAdvisor.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/adapter/springai/SessionHistoryAdvisor.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/adapter/springai/TextMemoryAdvisor.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/adapter/springai/DocMemoryAdvisor.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/adapter/springai/OthersContextAdvisor.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/adapter/springai/ContextAssemblyAdvisor.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/adapter/springai/SpringAiContextChatClient.java`
- Commit: `feat(agent-flow): add unified context assembly engine and spring ai advisor pipeline`

---

### Step 6 — Spring Boot 自动装配接线、PostgreSQL 集成测试与双语文档同步

- Requirements: `REQ-001`, `REQ-002`, `REQ-003`, `REQ-004`, `REQ-005`, `REQ-006`
- Dependencies: `Step 2`, `Step 3`, `Step 4`, `Step 5`
- Baseline state: 全部核心组件、DDL、三级存储与 Advisor 已完成单测；Spring Boot 自动配置类与 README 尚未接线与更新。
- Observable outcome: `three-tier.enabled=true` 时根据 `memory.mode`（`IN_MEMORY`/`JDBC`）条件装配完整三级 Memory Bean、`TokenCountEstimator`、`ContextAssemblyEngine`、6 个 Advisors 与 `SpringAiContextChatClient`；默认值 `false` 不影响既有只配了 `V1` 表的宿主；中英文 README 与全量测试通过。
- End state: 全部功能集成在 Spring Boot 自动装配下，PostgreSQL 集成测试与契约门禁就绪。
- Test-first gate: `Required` — 先创建 `ThreeTierMemoryAutoConfigurationTest`（断言默认 `three-tier.enabled=false` 不发布三级 Bean 保护老兼容、`true + IN_MEMORY` 发布内存 Bean、`true + JDBC` 缺 `DataSource` 阻断启动、允许自定义 `DocMemoryReader` / `TokenCountEstimator` 覆盖）与 `ThreeTierMemoryPostgresTest`确立 RED 门禁，再更新自动装配类与文档。
- Manual Checks: `MC-ARCH-001`, `MC-REUSE-001`, `MC-NAME-001`, `MC-BEAN-001`, `MC-CONFIG-001`, `MC-TIME-001`, `MC-SCOPE-001`, `MC-TEST-001`, `MC-BLOCKER-001`
- Literal Rules: `Rule 1`, `Rule 4`, `Rule 7`, `Rule 10`, `Rule 11`
- Ordered files:

#### File 1 — `CREATE stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/autoconfigure/ThreeTierMemoryAutoConfigurationTest.java`

- Purpose: 建立自动装配条件开关、Bean 命名、`@Qualifier` 注入与自定义 SPI 覆盖的 RED 测试。
- Symbols: `doesNotRegisterThreeTierBeansWhenDisabled`, `registersInMemoryThreeTierAndContextPipelineWhenEnabled`, `allowsCustomDocMemoryReaderAndTokenEstimatorOverride`, `failsClosedInJdbcModeWhenThreeTierTablesMissing`
- Repository evidence: 参考 `AgentFlowAutoConfigurationTest.java` 与 `OptionalClasspathTest.java` 的 `ApplicationContextRunner` 用法。
- Dependencies and consumers: 测试 `AgentExecutionAutoConfiguration`、`AgentMemoryJdbcAutoConfiguration` 与 `AgentFlowProperties`。
- Why now: Test-first 验证所有新 Bean 名称与条件开关隔离行为。
- Contract/signature changes: 使用 `ApplicationContextRunner` 加载既有自动配置类并验证 Bean 存在性与类型。
- Input/output and state mapping: 验证 `egon.cola.component.agent-flow.enabled=true` 且 `memory.three-tier.enabled=true` 时注册完整三级 Bean 与 `SpringAiContextChatClient`；默认 `false` 时零影响老模式。
- Error and edge behavior: `JDBC` 模式下缺 `DataSource` 或 `V2` 结构未安装时上下文启动失败。
- Standards impact: `MC-BEAN-001`, `MC-CONFIG-001`, `MC-TEST-001`。
- Literal rule enforcement: `Rule 4`, `Rule 7`, `Rule 11`。
- Required change: 创建 `ThreeTierMemoryAutoConfigurationTest` 编写条件开关、Bean 命名、覆盖优先及 fail-closed 测试。
- Verification contribution: 验证 Spring Boot 自动配置安全接线。
- After this file: 因自动配置类尚未新增相关 `@Bean`，测试预期 RED 失败。

#### File 2 — `CREATE stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/memory/jdbc/ThreeTierMemoryPostgresTest.java`

- Purpose: 在真实 PostgreSQL 环境下端到端验证 `V2` DDL、最小权限 Role、`session_message` 并发追加与工具回放、`text_memory` 10k CHECK 与乐观锁、`doc_memory` 保序与只读隔离以及 `SpringAiContextChatClient` 整合链路。
- Symbols: `ThreeTierMemoryPostgresTest`
- Repository evidence: 严格沿用 `JdbcMemoryStorePostgresTest.java` L70–78 的 `egon.agent.memory.postgres.test.url` 与 `runtime-user` 属性驱动方式（离线默认跳过并保留真实 PG 运行能力）。
- Dependencies and consumers: 测试 `ThreeTierMemoryStorageVerifier`, `JdbcSessionChatMemoryRepository`, `TurnAlignedChatMemory`, `JdbcTextMemoryService`, `JdbcDocMemoryReader`, `SpringAiContextChatClient`。
- Why now: 在接线之前锁定真实 PostgreSQL 集成门禁。
- Contract/signature changes: 对接 `egon_agent_memory` 三张新表及测试隔离 Role 授权脚本（测试内由管理员用户执行 `V2` DDL 并授权给运行 Role）。
- Input/output and state mapping: 并发线程写入同会话、10,001 字符触发数据库约束、运行 Role 尝试写 `doc_memory` 遭 PG 权限拒斥。
- Error and edge behavior: 未传 `egon.agent.memory.postgres.test.url` 时经 `Assumptions.assumeTrue` 安全跳过；传入真实 PG 时必须全项通过。
- Standards impact: `MC-TIME-001`, `MC-TEST-001`, `MC-SCOPE-001`。
- Literal rule enforcement: `Rule 10`, `Rule 11`。
- Required change: 创建 `ThreeTierMemoryPostgresTest` 编写真实 PostgreSQL 并发、`CHECK` 约束、最小权限 Role 与端到端 Context 执行集成测试。
- Verification contribution: `REQ-001`–`REQ-004`、`REQ-006` 真实 DB 验证门禁。
- After this file: 待 `AgentMemoryJdbcAutoConfiguration` 装配就位并搭配 PG 实例验证 GREEN。

#### File 3 — `MODIFY stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/autoconfigure/AgentExecutionAutoConfiguration.java`

- Purpose: 在 `egon.cola.component.agent-flow.enabled=true` 且 `memory.three-tier.enabled=true` 时注册内存模式默认三级存储、`JTokkitTokenCountEstimator`、`TurnAlignedChatMemory`、`ContextAssemblyEngine`、6 个 Advisors 与 `SpringAiContextChatClient`。
- Symbols: 新增条件配置内部类或 `@Bean` 方法：`agentTokenCountEstimator`、`agentSessionChatMemoryRepository`、`turnAlignedChatMemory`、`agentTextMemoryService`、`agentDocMemoryReader`、`agentContextAssemblyEngine`、`contextInitAdvisor`、`sessionHistoryAdvisor`、`textMemoryAdvisor`、`docMemoryAdvisor`、`othersContextAdvisor`、`contextAssemblyAdvisor`、`springAiContextChatClient`
- Repository evidence: `AgentExecutionAutoConfiguration.java` 现有 `@Bean("...")`、`@ConditionalOnMissingBean`、`@ConditionalOnProperty` 与 `@Qualifier` 写法。
- Dependencies and consumers: 消费 `AgentFlowProperties`、`Clock`、`ValidationUtils`、`ChatMemoryRepository`、`TextMemoryService`、`DocMemoryReader`；对宿主导出 `SpringAiContextChatClient` 等 Bean。
- Why now: File 1 测试已定义期望 Bean 名称与条件开关。
- Contract/signature changes: 所有 Bean 使用显式名称与 `@Qualifier` 严格对齐；`three-tier.enabled` 默认 `false` 确保不改变未启用新特性宿主的现有容器行为；在 `agentMemoryConfigurationGuard` 补充 `JDBC` + `three-tier.enabled=true` 时的守卫校验。
- Input/output and state mapping: `memory.mode=IN_MEMORY`（默认）加载内存实现；`memory.mode=JDBC` 交由 `AgentMemoryJdbcAutoConfiguration` 提供 JDBC Bean（因 `@ConditionalOnMissingBean` 优先生效或显式 `@ConditionalOnProperty` 互斥）。
- Error and edge behavior: 缺必需 `DataSource` 或 `V2` 校验失败阻断启动。
- Standards impact: `MC-BEAN-001`, `MC-CONFIG-001`, `MC-REUSE-001`, `MC-SCOPE-001`。
- Literal rule enforcement: `Rule 4`, `Rule 7`, `Rule 11` — 严格使用 `@Bean(name="...")` 与 `@Qualifier` 显式装配。
- Required change: 在 `AgentExecutionAutoConfiguration` 增加 `@ConditionalOnProperty(prefix = "egon.cola.component.agent-flow.memory.three-tier", name = "enabled", havingValue = "true")` 条件化配置块注册三级 Memory 内存默认 Bean、Token 估算器、Advisor 链与 `SpringAiContextChatClient`。
- Verification contribution: 使 `ThreeTierMemoryAutoConfigurationTest` 的默认关闭与 `IN_MEMORY` 开启用例转 GREEN。
- After this file: 内存模式自动装配就绪。

#### File 4 — `MODIFY stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/autoconfigure/AgentMemoryJdbcAutoConfiguration.java`

- Purpose: 当 `memory.mode=JDBC` 且 `memory.three-tier.enabled=true` 时执行 `ThreeTierMemoryStorageVerifier#verify` 并发布 JDBC 三级实现 Bean。
- Symbols: `agentJdbcSessionChatMemoryRepository`（`agentSessionChatMemoryRepository`）、`agentJdbcTextMemoryService`（`agentTextMemoryService`）、`agentJdbcDocMemoryReader`（`agentDocMemoryReader`）
- Repository evidence: `AgentMemoryJdbcAutoConfiguration.java` L24–40 现有获取指定 `dataSourceRef` 与调用 `MemoryStoreStorageVerifier` 写法。
- Dependencies and consumers: 消费 `AgentFlowProperties`、`DataSource`、`Clock`、`egonColaValidationUtils`；替换 `AgentExecutionAutoConfiguration` 中的默认内存 Bean。
- Why now: 解锁 `JDBC` 模式配置装配测试与 PG 集成测试。
- Contract/signature changes: 若配置了 `memory.mode=JDBC` 且 `three-tier.enabled=true`，先校验指定 `DataSource` 与 `ThreeTierMemoryStorageVerifier`，若宿主未自建 `DocMemoryReader` Bean 则发布默认 `JdbcDocMemoryReader`。
- Input/output and state mapping: 使用 `egonColaValidationUtils` 与 `agentExecutionClock`。
- Error and edge behavior: 表结构/校验和/只读权限异常在容器刷新期间直接抛 `IllegalStateException`。
- Standards impact: `MC-BEAN-001`, `MC-CONFIG-001`, `MC-SCOPE-001`。
- Literal rule enforcement: `Rule 4`, `Rule 7`, `Rule 11`。
- Required change: 在 `AgentMemoryJdbcAutoConfiguration` 增加三级记忆 JDBC Bean 条件装配方法，保留原 `agentJdbcMemoryStore` 不变，并确保只有 `three-tier.enabled=true` 时才触发 `ThreeTierMemoryStorageVerifier` 与注册 `V2` Bean。
- Verification contribution: 使 `ThreeTierMemoryAutoConfigurationTest` 全用例与 `ThreeTierMemoryPostgresTest` 转 GREEN。
- After this file: 容器自动装配全部完毕。

#### File 5 — `MODIFY stellar-enclosure-component-agent-flow-starter/README.md` & `MODIFY stellar-enclosure-component-agent-flow-starter/README.zh-CN.md`

- Purpose: 同步记录路径 A 三级 Memory、一体化 5 区 Context 管理、新配置键及权限边界说明，确保中英文文档完全一致。
- Symbols: `README.md`, `README.zh-CN.md`（同步文档化 `egon.cola.component.agent-flow.memory.three-tier.enabled`、`session-window-turns`、`text-max-chars`、`doc.*`、`context.*` 与 `V2__create_three_tier_memory.sql` 权限说明）
- Repository evidence: `AgentFlowComponentContractTest#documents_exact_configuration_api_and_boundaries`、`#keeps_chinese_english_contracts_in_sync` 要求保留所有既有关键 token 且中英文同步。
- Dependencies and consumers: 下游开发者与 `AgentFlowComponentContractTest`。
- Why now: 所有代码、配置键与 SQL 路径均已确定，可精确记录文档而无飘移。
- Contract/signature changes: 无代码变更，仅增补文档章节并保留全部既有必需 token。
- Input/output and state mapping: 保留原配置与 API 说明，新增“三级 Memory 与 Spring AI Context 管线”小节。
- Error and edge behavior: 确保未引入任何 `api-key`/`base-url` 等违规配置示例。
- Standards impact: `MC-CONFIG-001`, `MC-SCOPE-001`, `MC-TEST-001`。
- Literal rule enforcement: `Rule 7`, `Rule 11` — 中英文文档与属性代码定义精确同步。
- Required change: 同时更新 `README.md` 和 `README.zh-CN.md`，记录三级记忆能力边界、默认值（20 轮、10,000 字符硬拒写、32 条文档上限）、`V2` DDL 角色授权要求及 Spring AI 路径 A 执行入口 `SpringAiContextChatClient`。
- Verification contribution: 通过 `AgentFlowComponentContractTest` 中英文同步检查与全模块回归。
- After this file: 全部计划文件实施完毕。

- Validation working directory: `Stellar-Enclosure-Components`
- Verification command: `./mvnw -B -ntp -pl stellar-enclosure-component-agent-flow-starter -am test`
- Expected result: Exit 0；`agent-flow-starter` 全量单测、自动装配测试与契约治理测试全部通过（零跳过错误，零 `V1` 回归失败）；若本地提供 PostgreSQL 实例，额外执行 `./mvnw -B -ntp -pl stellar-enclosure-component-agent-flow-starter -Dtest=ThreeTierMemoryPostgresTest,JdbcMemoryStorePostgresTest -Degon.agent.memory.postgres.test.url=<jdbcUrl> -Degon.agent.memory.postgres.test.runtime-user=<role> test` 验证真实 DB 全绿。
- Failure returns to: 检查对应自动装配条件注解、Bean 命名 `@Qualifier` 或 README 遗漏 token。
- Completion criteria: 全量模块单测通过，全部 `REQ-001`–`REQ-006` 均有对应通过证据。
- Rollback: 回退 Step 6 新增/修改的 6 个文件。
- Commit paths: `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/autoconfigure/ThreeTierMemoryAutoConfigurationTest.java`, `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/memory/jdbc/ThreeTierMemoryPostgresTest.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/autoconfigure/AgentExecutionAutoConfiguration.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/autoconfigure/AgentMemoryJdbcAutoConfiguration.java`, `stellar-enclosure-component-agent-flow-starter/README.md`, `stellar-enclosure-component-agent-flow-starter/README.zh-CN.md`
- Commit: `feat(agent-flow): wire three-tier memory autoconfiguration, pg tests and docs`

## 8. Test, Validation, and Quality Gates

| Gate/order | Working directory | Command or method | Scope | Expected result | Failure returns to | Requirements/runtime boundary |
| --- | --- | --- | --- | --- | --- | --- |
| Step 1 RED/GREEN | `Stellar-Enclosure-Components` | `./mvnw -B -ntp -pl stellar-enclosure-component-agent-flow-starter -Dtest=AgentFlowPropertiesBindingTest,AgentFlowComponentContractTest,ComponentContractGovernanceTest test` | 属性绑定、依赖边界与中立契约 | RED 时缺字段/类型失败；GREEN 时 Exit 0 全绿 | Step 1 File 4–15 | `REQ-002`–`REQ-006`; module |
| Step 2 RED/GREEN | `Stellar-Enclosure-Components` | `./mvnw -B -ntp -pl stellar-enclosure-component-agent-flow-starter -Dtest=ThreeTierMemoryStorageVerifierTest,AgentFlowComponentContractTest test` | `V2` DDL、`three-tier-manifest.json` 校验和与权限守卫 | RED 时缺脚本/类失败；GREEN 时 Exit 0 | Step 2 File 2–4 | `REQ-006`; module |
| Step 3 RED/GREEN | `Stellar-Enclosure-Components` | `./mvnw -B -ntp -pl stellar-enclosure-component-agent-flow-starter -Dtest=TurnAlignedChatMemoryTest,AgentFlowComponentContractTest test` | Level 1 20 轮可自定义滑窗与不断链 | RED 时失败；GREEN 时 Exit 0 全通过 | Step 3 File 2–4 | `REQ-001`, `REQ-002`; module |
| Step 4 RED/GREEN | `Stellar-Enclosure-Components` | `./mvnw -B -ntp -pl stellar-enclosure-component-agent-flow-starter -Dtest=TextMemoryServiceTest,DocMemoryReaderTest,AgentFlowComponentContractTest test` | Level 2 10k 拒写/乐观锁 & Level 3 保序/只读 | RED 时失败；GREEN 时 Exit 0 全通过 | Step 4 File 3–6 | `REQ-003`, `REQ-004`; module |
| Step 5 RED/GREEN | `Stellar-Enclosure-Components` | `./mvnw -B -ntp -pl stellar-enclosure-component-agent-flow-starter -Dtest=ContextAssemblyEngineTest,SpringAiContextChatClientTest test` | 一体化 Context 预算淘汰与 Advisor 闭环 | RED 时失败；GREEN 时 Exit 0 全通过 | Step 5 File 3–12 | `REQ-005`; module |
| Step 6 全模块回归 | `Stellar-Enclosure-Components` | `./mvnw -B -ntp -pl stellar-enclosure-component-agent-flow-starter -am test` | 全模块单测、自动装配、既有 ADK/MCP/Memory 回归与治理测试 | Exit 0；无任何失败与契约违例 | 对应 Step | `REQ-001`–`REQ-006`; module |
| 真实 PostgreSQL 集成门禁 | `Stellar-Enclosure-Components` | `./mvnw -B -ntp -pl stellar-enclosure-component-agent-flow-starter -Dtest=ThreeTierMemoryPostgresTest,JdbcMemoryStorePostgresTest -Degon.agent.memory.postgres.test.url=<jdbcUrl> -Degon.agent.memory.postgres.test.runtime-user=<role> test` | 真实 PG DDL、并发 `appendTurn`、`CHECK <= 10000`、`doc_memory` 写拒绝与 `V1` 回归 | 提供本地 PG 时 Exit 0 全绿；离线未传参数时安全跳过并记录验证边界 | Step 2 / Step 3 / Step 4 / Step 6 | `REQ-001`–`REQ-004`, `REQ-006`; user-controlled PG runtime |

## 9. Migration, Compatibility, Rollout, and Rollback

1. **DDL 增量发布**：`V1__create_memory_store.sql` 与 `manifest.json` 绝对保持不可变；DBA 通过独立迁移账号执行新增的 `V2__create_three_tier_memory.sql`，并为组件运行时 DB Role 授最小权限：`session_message`（`SELECT, INSERT, DELETE`）、`text_memory`（`SELECT, INSERT, UPDATE`）、`doc_memory`（**仅 `SELECT`**）。
2. **向后兼容灰度**：`egon.cola.component.agent-flow.memory.three-tier.enabled` 默认 `false`，已使用 `V1` `JdbcMemoryStore` 或旧 `AgentFlowService` 的宿主页可无感升级 jar 包；仅当显式设 `three-tier.enabled=true` 时才激活新 Bean 与 `ThreeTierMemoryStorageVerifier`。
3. **回滚边界**：若新特性需回退，只需将 `three-tier.enabled` 切回 `false` 或回滚应用包版本；三张 `V2` 表保留在数据库中对老版本代码完全透明且无破坏。

## 10. Requirement-to-Step Traceability Matrix

| Requirement | Effective Spec section | Steps | Files | Tests/gates | Completion evidence |
| --- | --- | --- | --- | --- | --- |
| `REQ-001` | [Spec](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) §4, §11.2.1 | Step 2, Step 3, Step 5, Step 6 | `V2__create_three_tier_memory.sql`, `JdbcSessionChatMemoryRepository`, `InMemorySessionChatMemoryRepository`, `SessionHistoryAdvisor`, `AgentMemoryJdbcAutoConfiguration` | `TEST-001`, `TEST-003` (`TurnAlignedChatMemoryTest`, `ThreeTierMemoryPostgresTest`) | `toolCalls`/`toolResponses` 完整入库还原与租户隔离通过 |
| `REQ-002` | [Spec](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) §4, §9.2.1 | Step 1, Step 3, Step 5, Step 6 | `AgentFlowProperties`, `TurnAlignedChatMemory`, `SessionHistoryAdvisor` | `TEST-002`, `TEST-003` (`TurnAlignedChatMemoryTest`, `AgentFlowPropertiesBindingTest`) | 默认 20 轮可自定义，头尾不断工具链通过 |
| `REQ-003` | [Spec](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) §4, §9.2.2, §11.2.2 | Step 1, Step 2, Step 4, Step 5, Step 6 | `TextMemory*`, `V2__create_three_tier_memory.sql`, `InMemoryTextMemoryService`, `JdbcTextMemoryService`, `TextMemoryAdvisor` | `TEST-004`, `TEST-005` (`TextMemoryServiceTest`, `ThreeTierMemoryPostgresTest`) | 幂等绑定返回 `id`、10,001 字符严格拒写与 `VERSION_CONFLICT` 通过 |
| `REQ-004` | [Spec](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) §4, §9.2.3, §11.2.3 | Step 1, Step 2, Step 4, Step 5, Step 6 | `DocMemory*`, `V2__create_three_tier_memory.sql`, `InMemoryDocMemoryReader`, `JdbcDocMemoryReader`, `ThreeTierMemoryStorageVerifier`, `DocMemoryAdvisor` | `TEST-006`, `TEST-009` (`DocMemoryReaderTest`, `ThreeTierMemoryPostgresTest`) | 按传入 `ids` 保序查出且跨租户/软删过滤、运行 Role 写 `doc_memory` 被拒 |
| `REQ-005` | [Spec](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) §4, §7.2, §7.3, §9.2.4 | Step 1, Step 5, Step 6 | `pom.xml`, `Context*`, `adapter/springai/*Advisor`, `SpringAiContextChatClient`, `AgentExecutionAutoConfiguration` | `TEST-007`, `TEST-008` (`ContextAssemblyEngineTest`, `SpringAiContextChatClientTest`, `ThreeTierMemoryAutoConfigurationTest`) | 五区防注入隔离、按优先级淘汰与流式异常零回写通过 |
| `REQ-006` | [Spec](../spec/2026-11-17-04-50-spring-ai-three-tier-memory-and-context.md) §4, §6.1, §8.2, §16 | Step 1, Step 2, Step 6 | `pom.xml`, `three-tier-manifest.json`, `ThreeTierMemoryStorageVerifier`, `README*`, `*Contract*Test` | `TEST-009` (`AgentFlowComponentContractTest`, `ComponentContractGovernanceTest`, `ThreeTierMemoryStorageVerifierTest`) | 扁平包规则、`api.*` 零框架类型泄漏与 `V1` SHA-256 保持通过 |

## 11. Risks, Blockers, and User Decisions

| ID | Risk or decision | Impacted Steps/files | Evidence | Owner | Status/action |
| --- | --- | --- | --- | --- | --- |
| `PLAN-RISK-001` | `ThreeTierMemoryPostgresTest` 需要本地回环 PostgreSQL 14+ 实例，纯离线单测阶段会通过 `Assumptions` 跳过 PG 用例 | Step 6 `ThreeTierMemoryPostgresTest` | `JdbcMemoryStorePostgresTest.java` L70–78 既有约束 | `mario` / CI 环境 | Closed（Execute 阶段优先完成全部编译/单测/H2/Mock 校验，并在具备本地 PG 时补充跑通真实 PG 用例） |

## 12. Review and Acceptance

### 12.1 Original requirement fidelity

- 完整映射路径 A（Spring AI 体系）、1b 增强 PgSQL `session_message`（20 轮可自定义滑动窗口）、Level 2 ≤10,000 字符严格拒写并下发 ID、Level 3 不用 Spring 默认表而由下游维护 PgSQL MD 文档只读按 `ids` 保序查询、以及五区一体化 Context 汇编管线全部 6 条 `REQ-*`。

### 12.2 Spec consistency

- §4.5 简化性审计核实全部元素均源自已批准 Spec 且无过度设计；§6.4 仅记录两项纯局部实现澄清（`PLAN-CLAR-001` 沿用既有回环 PG 测试属性机制、`PLAN-CLAR-002` 遵循 Spec §8.2 扁平 `memory/` 包落位），以及在 Step 2 File 3 因 `MemoryStoreStorageVerifier` L110 硬检查 `manifest.json` `scripts.size() == 1` 而采用独立 `three-tier-manifest.json` 零破坏保护 `V1` 合同。

### 12.3 Repository executability

- 6 个 Step 之间写入路径严格零交集，所有依赖顺序（契约测试/测试夹具 -> `pom.xml` & `api/*` -> `V2` DDL -> 内存与 JDBC 实现 -> Advisor 链 -> 自动装配与文档）均精确可行；所有路径、类名、Maven Wrapper 命令均经仓库实证校验。

### 12.4 Test and release completeness

- 每个业务 Step 均设置明确 RED 测试前置与 GREEN 门禁；Plan 正文与每条文件说明严格遵守纯散文与少量伪代码要点规范，绝无代码片段或代码块。

### 12.5 Blocking Manual Check

| Check ID | Applicability | Status | Evidence | Finding | Required action/exception |
| --- | --- | --- | --- | --- | --- |
| `MC-ARCH-001` | `Applicable` | `PASS` | Spec §6.1、Plan §4.7、§5 文件树、`AgentFlowComponentContractTest` | 严格维持 `agent-flow-starter` 扁平功能包结构与 `api.*` 纯中立边界，无违规包名与架构混合 | None |
| `MC-REUSE-001` | `Applicable` | `PASS` | Plan §4.7 复用台账（`ChatMemory`, `ChatMemoryRepository`, `TokenCountEstimator`, `BaseAdvisor`, `spring-jdbc`, `RagContextSource`, `MemoryStoreStorageVerifier` 模式） | 最大化复用 Spring AI 2.0.2 原生接口及组件既有基础设施，无重复造轮子 | None |
| `MC-DEP-001` | `Applicable` | `PASS` | Spec §5.3 `DEC-005`、Plan Step 1 File 4 `pom.xml`、`Stellar-Enclosure-Components/pom.xml` (`spring-ai-bom:2.0.2`) | 仅引入用户选中路径 A 所需的 `org.springframework.ai:spring-ai-client-chat`（BOM 托管版本，无硬编码）；未引入 `spring-ai-model-chat-memory-repository-jdbc` | None |
| `MC-NAME-001` | `Applicable` | `PASS` | Plan §5 完整类型清单、§4.8 Rule 1 | 所有新建类均带明确语义后缀（`*Command`, `*Query`, `*VO`, `*BO`, `*Service`, `*Reader`, `*Repository`, `*Advisor`, `*Engine`, `*Verifier`, `*Enum`） | None |
| `MC-VALID-001` | `Applicable` | `PASS` | Plan §4.8 Rule 2、Step 1/4/5/6 文件级约束与 RED/GREEN 测试 | 全边界采用 `@Validated`、`@Valid`、Jakarta 注解（`<=10000` 字符、`<=32` 文档、轮数 `1..200`）与 `egonColaValidationUtils` | None |
| `MC-MODEL-001` | `Applicable` | `PASS` | Plan §4.8 Rule 3、Step 1/5 文件清单 | 可变 Command/Query/BO 使用 `class` + 完整 Lombok 基线，不可变值对象使用 `record` + 防御拷贝，枚举使用 `@JsonValue` | None |
| `MC-CONVERT-001` | `Applicable` | `PASS` | `ComponentContractGovernanceTest#conversionsStayOnTheCommonContractWithoutFakeConverters` | JDBC 行直接由 `RowMapper` 投影至 `*VO` / `Message`，不引入伪造 `BaseConverter` 或禁止拷贝工具 | None |
| `MC-LOG-001` | `Applicable` | `PASS` | Plan §4.8 Rule 4、Step 3–6 | 全部业务组件 Bean 使用 `@Slf4j`，仅记录统计计数、错误码与脱敏状态，绝不打印文本正文与租户/会话密钥 | None |
| `MC-BEAN-001` | `Applicable` | `PASS` | `lombok.config`、Step 3–6 文件清单 | 全部 Spring Bean 带显式 `@Bean("...")` 名、`final` 字段、`@Qualifier` 与 `@RequiredArgsConstructor(onConstructor_ = @Autowired)` | None |
| `MC-UTIL-001` | `Applicable` | `PASS` | Plan §4.8 Rule 5 | 仅使用 JDK 原生及既有允许依赖，不新增任何 `*Utils` 类 | None |
| `MC-JSON-001` | `Applicable` | `PASS` | Plan §4.8 Rule 6、Step 1/2/3 | 仅用 Spring Boot 托管 Jackson `JsonMapper`；新增枚举注册 `NEUTRAL_ENUM_CODES`；`three-tier-manifest.json` 由 Jackson 校验 | None |
| `MC-TIME-001` | `Applicable` | `PASS` | Plan §4.9 `TIME-001`、Step 1/2/3/4/6 | 全部持久化与 VO 时间采用 `Instant` + `timestamptz(6)` + `OffsetDateTime` pgJDBC 边界转换与 `agentExecutionClock` | None |
| `MC-CONFIG-001` | `Applicable` | `PASS` | Plan §4.8 Rule 7、Step 1 File 1 & File 15、Step 6 File 5 | 新增属性严格绑定于 `AgentFlowProperties` 并同步更新中英文 README 配置说明 | None |
| `MC-PATTERN-001` | `Applicable` | `PASS` | Plan §4.8 Rule 9、Step 3/4/5 | 明确落地 Spring AI `BaseAdvisor` 责任链、`TurnAlignedChatMemory` 装饰器、`ContextAssemblyEngine` 裁剪策略与 `DocMemoryReader` SPI | None |
| `MC-SCOPE-001` | `Applicable` | `PASS` | Plan §5、§6.1、`V1` 零改动与无关 dirty 文件隔离规则 | 所有更改严格限定在 `agent-flow-starter` 范围，保护既有 `V1`、其他模块与未提交的 outbox 文档 | None |
| `MC-TEST-001` | `Applicable` | `PASS` | Plan §4.2、§7 各 Step RED/GREEN、§8 校验矩阵 | 每个行为 Step 具备先 RED 后 GREEN 的精确单测、契约测试与真实 PG 集成门禁 | None |
| `MC-BLOCKER-001` | `Applicable` | `PASS` | Plan §11 与上述全部 `MC-*` 行均已关闭或仅余预期环境执行说明 | 无未关闭 blocker 或 `UNKNOWN` 状态 | None |

### 12.6 Final verdict

`PASS — Ready for user review`
