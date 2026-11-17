# Transactional Outbox 服务内分布式事务事件韧性与防误杀实施计划

| Field | Value |
| --- | --- |
| Document | `docs/egon/plan/2026-11-16-08-19-outbox-in-service-distributed-resilience.md` |
| Template Version | `6` |
| Status | `Review` |
| Created | `2026-11-16 08:19 AST` |
| Updated | `2026-11-16 08:19 AST` |
| Owner | `mario` |
| Repository | `Stellar-Enclosure-Components` |
| Scope | `stellar-enclosure-component-transactional-outbox-starter`（轮询抖动与 MP 抢占冲突退让、Claim 通道能力过滤、技术生命周期 `RECLAIM` 守卫与 Store 原子熔断、业务状态机缺失定义退避、文档与契约治理测试） |
| Source Requirement | 用户确认审计通过“服务内分布式事务事件 3 点轻量优化方案”，并批准按对应 Spec 编写实施计划 |
| Baseline Revision | `Stellar-Enclosure-Components` `main` @ `c88fd8912c9f1a80caec3bf6baa2bae0df97870b`（仅含已审查 Spec 未跟踪文件，其余工作区干净） |
| Implements Spec | [Transactional Outbox 服务内分布式事务事件韧性与防误杀设计规格](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) |
| Spec Status | `Review` |
| Spec Revision | `2026-11-16 08:11 AST SHA-256 f19b08bfc05684fd03f1ae1aa6b3b63e253b639e9f6bc42a8f3c648bd817056d` |
| Effective Specs | [Transactional Outbox 服务内分布式事务事件韧性与防误杀设计规格](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md)（增量修订并依赖已 `Implemented` 的 `2026-09-24-12-00-outbox-statemachine-cqe-integration.md` §7/§9/§11/§15，见 §2.2） |
| Depends On Plans | `None` |
| Supersedes | `None` |
| Superseded By | `None` |
| Related Plans | `None` |

## 1. Summary

本计划实施 [Transactional Outbox 服务内分布式事务事件韧性与防误杀设计规格](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md)，在不引入外部中间件与零数据库 DDL 变更的前提下，分四个互不重叠文件的原子 Step 落地服务内多实例部署的韧性增强：（1）Step 1 增加 `polling.jitter` 与 `polling.maxClaimContentionRetries` 配置校验并实现 `OutboxPoller` 抖动自调度；（2）Step 2 在 `OutboxLifecycleStateMachineFactory` 为 `RECLAIM` 边接入重试预算 Guard；（3）Step 3 打通 `DeliveryHandlerRegistry#channels()`、`OutboxStore`/`DAO`/`Repository`/`Mapper XML`/`JdbcOutboxStore`/`MybatisPlusOutboxStore` 与 `OutboxDispatcher` 的按通道过滤、冲突上限退让及 `RECLAIM -> DEAD` 原子熔断，并补齐 PostgreSQL 恢复与执行计划集成测试；（4）Step 4 落地 `state-machine.business.retryOnMissingDefinition` 滚动升级退避、同步双语 README 与 `ComponentContractGovernanceTest`。完成证据为 Surefire 全量单测及开启 `EGON_OUTBOX_TEST_POSTGRES_ENABLED=true` 的 Failsafe PostgreSQL 集成测试通过。

## 2. Target Spec and Effective Design

### 2.1 Primary target

- Path: [Transactional Outbox 服务内分布式事务事件韧性与防误杀设计规格](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md)
- Status: `Review`
- Revision: `2026-11-16 08:11 AST`（`SHA-256 f19b08bfc05684fd03f1ae1aa6b3b63e253b639e9f6bc42a8f3c648bd817056d`；基线提交 `c88fd8912c9f1a80caec3bf6baa2bae0df97870b`）
- Approval evidence: 用户于 `2026-11-16` 明确确认 Spec 内容并授权按 `/stellar-enclosure-coding-writing-plan` 产出实施计划。

### 2.2 Effective Spec set

| Role | Spec/link | Status/revision | Effective sections | Why included |
| --- | --- | --- | --- | --- |
| Primary | [2026-11-16-08-11-outbox-in-service-distributed-resilience.md](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) | `Review` / `2026-11-16 08:11 AST` | 全文（§1–§20） | 规定本次 `REQ-001`–`REQ-006` 目标架构、SPI 契约、SQL 访问路径与测试门槛 |
| Dependency & Amended Base | [2026-09-24-12-00-outbox-statemachine-cqe-integration.md](../../../../Stellar-Enclosure-Parent/docs/history/egon/spec/2026-09-24-12-00-outbox-statemachine-cqe-integration.md) | `Implemented` / `2026-09-27 08:52 CST` | §7、§9、§11、§15（其中 §7.3.1 `RECLAIM` 与 §7.3.2/§15 由 Primary Spec 修订） | 约束技术五态图、MP/Advisory Lock 抢占 SQL 守卫、业务消费事务与 Receipt 幂等不变量 |

### 2.3 Superseded or excluded content

前序规格 [2026-09-24-12-00-outbox-statemachine-cqe-integration.md](../../../../Stellar-Enclosure-Parent/docs/history/egon/spec/2026-09-24-12-00-outbox-statemachine-cqe-integration.md) §7.3.1 中“不给 `RECLAIM` 增加 `maxAttempts` 次数上限”及 §15 中“缺失业务状态机定义无条件永久失败”的旧规则已被 Primary Spec §1、§4、§7、§9、§11 明确修订，本计划不执行旧规则；跨服务 RabbitMQ/HTTP 投递、Redis 协调与 DDL 变更为显式 Non-goals，排除在本计划范围之外。

## 3. Effective Requirements and Acceptance

| Requirement | Source Spec section | Effective statement | Observable acceptance | Implementation impact |
| --- | --- | --- | --- | --- |
| `REQ-001` | [Spec](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) §4 | `OutboxPoller` 支持 `polling.jitter`（默认 `0.2`，`[0.0, 1.0]`）随机抖动自调度；`jitter=0.0` 保持 `scheduleWithFixedDelay` | `OutboxPollerTest` 证明抖动区间与异常续约，`OutboxConfigurationValidatorTest` 拦截越界/NaN | `TransactionalOutboxProperties`, `OutboxConfigurationValidator`, `OutboxPoller`, 单测 |
| `REQ-002` | [Spec](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) §4 | `MybatisPlusOutboxStore` 冲突重开事务次数受 `polling.maxClaimContentionRetries`（默认 `8`，`>=1`）约束 | `OutboxMultiShardStoreTest` 证明连续锁冲突达上限后安全退出并保留已领批次 | `TransactionalOutboxProperties`, `OutboxConfigurationValidator`, `MybatisPlusOutboxStore`, `OutboxMybatisPlusAutoConfiguration`, 单测 |
| `REQ-003` | [Spec](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) §4 | `OutboxDispatcher` 传入 `DeliveryHandlerRegistry.channels()`；两套 `OutboxStore` 均仅领取 `channel IN (...)` 消息且空集合短路 | `DeliveryHandlerRegistryTest`, `OutboxDispatcherTest`, `PostgresqlOutboxRecoveryIntegrationTest`, `PostgresqlOutboxQueryPlanIntegrationTest` 通过 | `DeliveryHandlerRegistry`, `OutboxStore`, `OutboxMessageDAO`, `OutboxMessageMapper.xml`, `OutboxMessageRepository`, `JdbcOutboxStore`, `MybatisPlusOutboxStore`, `OutboxDispatcher` |
| `REQ-004` | [Spec](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) §4 | `state-machine.business.retry-on-missing-definition`（默认 `true`）控制 `statemachine` 缺失定义时返 `retryableFailure` 或 `permanentFailure`，生产者 `validateDestination` 保持强拒绝 | `BusinessStateMachineDeliveryHandlerTest`, `BusinessStateMachineServiceTest`, `BusinessStateMachineIntegrationTest` 验证退避并由新版接管 | `OutboxStateMachineProperties`, `BusinessStateMachineDeliveryHandler`, `BusinessStateMachineService`, `OutboxBusinessStateMachineAutoConfiguration`, 单测/PG 集成测试 |
| `REQ-005` | [Spec](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) §4 | `OutboxLifecycleStateMachineFactory` 为 `RECLAIM` 挂接 `RETRY_WITH_BUDGET`/`RETRY_EXHAUSTED`；Store 评估返回 `DEAD` 时原子置 `DEAD` 且不返给 `Dispatcher` | `OutboxLifecycleServiceTest` 验证预算切换，`PostgresqlOutboxRecoveryIntegrationTest` 验证 MP 与 JDBC 原子置 `DEAD` | `OutboxLifecycleStateMachineFactory`, `OutboxMessageDAO`, `OutboxMessageMapper.xml`, `OutboxMessageRepository`, `JdbcOutboxStore`, `MybatisPlusOutboxStore`, 单测/PG 集成测试 |
| `REQ-006` | [Spec](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) §4 | 保持 `OutboxStore` 旧签名、`OutboxPoller` 4 参构造器及表结构向后兼容，同步双语 README 与 `ComponentContractGovernanceTest` | `ComponentContractGovernanceTest` 及全量回归通过 | `OutboxStore`, `OutboxPoller`, `README.md`, `README.zh-CN.md`, `ComponentContractGovernanceTest` |

## 4. Implementation Strategy and Dependency Order

### 4.1 Ordered strategy

按编译依赖与可独立验证边界划分 4 个顺序 Step，且确保任意生产文件或测试文件仅归属单个 Step，不出现跨 Step 写入冲突：
1. **Step 1（轮询配置与调度层）**：独立于存储与状态机，先完成 `TransactionalOutboxProperties.Polling`、`OutboxConfigurationValidator` 与 `OutboxPoller`，建立基础配置校验与错峰轮询。
2. **Step 2（技术状态机图规则）**：在修改任何 Store 的 `RECLAIM` 分支前，先在 `OutboxLifecycleStateMachineFactory` 为 `RECLAIM` 挂接 `RETRY_WITH_BUDGET` 与 `RETRY_EXHAUSTED` Guard 并通过 `OutboxLifecycleServiceTest`。
3. **Step 3（Store / DAO / Registry / Dispatcher 能力过滤与熔断落库）**：以 Step 1 新增属性和 Step 2 新状态机语义为编译与行为前置，自底向上完成 `DeliveryHandlerRegistry`、`OutboxStore` 接口、`OutboxMessageDAO`/`Mapper XML`/`OutboxMessageRepository`、`JdbcOutboxStore`、`MybatisPlusOutboxStore`、`OutboxMybatisPlusAutoConfiguration` 与 `OutboxDispatcher`，再运行 PostgreSQL 恢复与执行计划集成测试。
4. **Step 4（业务状态机滚动发版退避、文档与治理门禁）**：在 Step 3 验证稳定的基础上，更新 `OutboxStateMachineProperties`、`BusinessStateMachineDeliveryHandler`、`BusinessStateMachineService` 与 `OutboxBusinessStateMachineAutoConfiguration`，跑通滚动发版跨版本集成测试，最后同步 `README.md`、`README.zh-CN.md` 与 `ComponentContractGovernanceTest` 完成全量回归。

### 4.2 Test-first strategy

每个行为 Step 均先修改/新增对应单元测试确定预期 RED 失败原因，再写最小实现至 GREEN：
- **Step 1 RED**：`OutboxConfigurationValidatorTest` 调用新 getter/setter 在实现前无法编译；在配置字段就位、校验与 `OutboxPoller` 实现前，`OutboxConfigurationValidatorTest` 因未拒绝非法 `polling.jitter`/`maxClaimContentionRetries` 而失败，`OutboxPollerTest` 因仍走 `scheduleWithFixedDelay` 未调用 `schedule(Runnable, Instant)` 而失败。
- **Step 2 RED**：`OutboxLifecycleServiceTest` 断言 `evaluate("PROCESSING", RECLAIM, 3, 3)` 期望返回 `DEAD`，当前无 Guard 实现会实际返回 `PROCESSING` 触发断言失败。
- **Step 3 RED**：`DeliveryHandlerRegistryTest` 与 `OutboxDispatcherTest` 针对 `channels()` 与带 `channels` 重载；`OutboxMultiShardStoreTest` 模拟连续 `tryAcquireTransactionLock=false` 期望事务调用上限为 `1 + maxClaimContentionRetries`，在加保护前会退化成全部候选重开循环触发失败；PG 集成测试在实现前因异构 `channel` 被抢或毒丸被重领而失败。
- **Step 4 RED**：`BusinessStateMachineDeliveryHandlerTest` 与 `BusinessStateMachineServiceTest` 断言缺失定义默认返回 `RETRYABLE_FAILURE`，在改造前仍返回 `PERMANENT_FAILURE` 触发断言失败。

### 4.3 Sequential and parallel boundaries

| Step | Depends on | May run in parallel with | Must not overlap with | Reason |
| --- | --- | --- | --- | --- |
| Step 1 | None | Step 2（`OutboxLifecycleStateMachineFactory` 写入完全正交） | Step 3（Step 3 依赖 Step 1 的 `TransactionalOutboxProperties.Polling#getMaxClaimContentionRetries`） | Step 1 与 Step 2 无共享文件；Step 3 读取 Step 1 属性并消费 Step 2 状态机输出 |
| Step 2 | None | Step 1 | Step 3 | Step 3 的 `claim` `RECLAIM -> DEAD` 依赖 Step 2 返回 `OutboxStatus.DEAD` |
| Step 3 | Step 1, Step 2 | None | Step 1, Step 2, Step 4（共享 PG 集成测试进程与 Maven 模块编译） | 修改核心 Store、DAO、Mapper XML 与 Dispatcher |
| Step 4 | Step 3 | None | Step 3（`BusinessStateMachineIntegrationTest` 依赖稳定的 `OutboxDispatcher`、`OutboxStore` 及 `ComponentContractGovernanceTest` 全模块文本扫描） | 最终契约测试 `ComponentContractGovernanceTest` 扫描全模块源码与双语 README |

### 4.4 Commit boundaries

每个 Step 对应一个语义清晰、路径严格受限的提交（共 4 个提交）：
- Commit 1（Step 1）：`feat(outbox): add polling jitter and contention retry limit properties`
- Commit 2（Step 2）：`feat(outbox): guard reclaim lifecycle transition with attempt budget`
- Commit 3（Step 3）：`feat(outbox): filter claim by registered channels and mark exhausted reclaim dead`
- Commit 4（Step 4）：`feat(outbox): allow retryable missing business statemachine definition during rollout`

### 4.5 Spec Simplicity and Implementation-necessity Audit

| Spec element | Spec necessity verdict/section | Current repository evidence | Direct/reuse alternative | Interaction/implementation cost | Plan decision |
| --- | --- | --- | --- | --- | --- |
| `Polling.jitter` & `maxClaimContentionRetries` | Keep（Spec §7.0） | `TransactionalOutboxProperties.java#Polling`、`OutboxPoller.java` | 硬编码常量无法在测试或运维时关闭抖动 | 仅 2 个属性字段与校验规则，零新类 | Implement |
| `DeliveryHandlerRegistry#channels()` | Keep（Spec §7.0, §9.2.2） | `DeliveryHandlerRegistry.java` 已聚合全部 `DeliveryHandler` | 再次注入 `List<DeliveryHandler>` 导致两份校验与状态 | 1 个 O(1) 只读方法 | Implement |
| `OutboxStore` 带 `channels` 重载 | Keep（Spec §7.0, §9.2.1） | `OutboxStore.java`、`JdbcOutboxStore.java`、`MybatisPlusOutboxStore.java` | `Dispatcher` 领后再丢弃会破坏行状态与租约 | 保留 `default` 旧签名兼容，SQL 仅增可选 `channel IN` | Implement |
| `OutboxLifecycleStateMachineFactory` `RECLAIM` Guard | Keep（Spec §7.0, §9.2.3） | `OutboxLifecycleStateMachineFactory.java` 已有 `RETRY_WITH_BUDGET`/`RETRY_EXHAUSTED` | 在两套 Store 中手写 `if/else` 违背状态机统一决策 | 复用既有 Guard 常量与新加 `updateReclaimDead` | Implement |
| `BusinessProperties.retryOnMissingDefinition` | Keep（Spec §7.0, §9.2.4） | `OutboxStateMachineProperties.java#BusinessProperties` | `claimDue` SQL 中过滤 `destination` 耦合业务路由且拉长 SQL | 1 个布尔属性及 2 处返回值分支 | Implement |

结论：经核对仓库源码，Spec 无任何 fetch-then-forward、冗余缓存、无依据抽象层或多余接口，全部条目按原设计实施。

### 4.6 Change-unit Dependency Matrix

| Change unit | Requirements | Proof/RED point | Compile/runtime prerequisites | Produces | Consumers/unblocks | Owning Step |
| --- | --- | --- | --- | --- | --- | --- |
| 轮询抖动配置与调度 | `REQ-001`, `REQ-002`（配置） | `OutboxConfigurationValidatorTest`, `OutboxPollerTest` | 既有 `TaskScheduler`, `TransactionalOutboxProperties` | `jitter`, `maxClaimContentionRetries` 与抖动调度逻辑 | `MybatisPlusOutboxStore` 注入上限 | Step 1 |
| `RECLAIM` 预算 Guard | `REQ-005`（FSM） | `OutboxLifecycleServiceTest` | 既有 `RETRY_WITH_BUDGET`, `RETRY_EXHAUSTED` | `RECLAIM` 满额返回 `OutboxStatus.DEAD` | `JdbcOutboxStore`, `MybatisPlusOutboxStore` | Step 2 |
| Store 能力过滤与 `RECLAIM -> DEAD` 落库 | `REQ-002`, `REQ-003`, `REQ-005`, `REQ-006` | `DeliveryHandlerRegistryTest`, `OutboxDispatcherTest`, `OutboxMultiShardStoreTest`, PG 集成测试 | Step 1 属性、Step 2 状态机 | `channels()`, `claimDue(..., channels)`, `updateReclaimDead`, MP 冲突上限 | `OutboxDispatcher`、`OutboxMybatisPlusAutoConfiguration` | Step 3 |
| 业务状态机缺失定义退避与文档治理 | `REQ-004`, `REQ-006` | `BusinessStateMachineDeliveryHandlerTest`, `BusinessStateMachineServiceTest`, `BusinessStateMachineIntegrationTest`, `ComponentContractGovernanceTest` | Step 3 通道过滤与调度稳定性 | `retryOnMissingDefinition` 开关及双语 README/治理校验 | 业务服务滚动发布接入方 | Step 4 |

### 4.7 Java, Spring, and Egon-COLA Implementation Standards

| Concern | Current repository evidence | Effective Spec decision | Planned implementation consequence | Owning Steps/checks |
| --- | --- | --- | --- | --- |
| Architecture profile | `Stellar-Enclosure-Components/egon-cola-components-architecture.md`、`pom.xml` | 维持基础设施 Flat Starter 结构，不引业务三层 | 仅修改 `autoconfigure/delivery/dispatch/persistence/statemachine/store` 既有文件 | Every Step; `MC-ARCH-001` |
| Reuse/capability | `ExponentialJitterRetryPolicy`, `OutboxLifecycleStateMachineFactory`, `DeliveryHandlerRegistry` | 零新依赖，复用 JDK `ThreadLocalRandom`、`java.time` 与既有 Guard | 不新增任何 Maven 依赖或外部工具类 | Step 1–4; `MC-REUSE-001`, `MC-DEP-001` |
| Naming/model/validation/conversion | `TransactionalOutboxProperties`, `OutboxStateMachineProperties`, `OutboxConfigurationValidator`, `OutboxMessageDAO` | 不改 PO/Record/Converter；新增配置与方法入参使用 Jakarta/显式校验 | 保留 `OutboxStore` 旧签名 `default` 兼容，DAO/Store 严格校验 `channels` | Step 1, Step 3, Step 4; `MC-NAME-001`, `MC-VALID-001`, `MC-MODEL-001`, `MC-CONVERT-001` |
| Bean/logging/util/JSON/time/config | `lombok.config`（开启 `Qualifier` 复制）、`TransactionalOutboxAutoConfiguration`, `OutboxMybatisPlusAutoConfiguration`, `OutboxBusinessStateMachineAutoConfiguration` | 修改业务类使用 `@Slf4j` 并安全记录非敏感 ID；保留原 `@Bean` 名与构造器兼容并按 `@Qualifier` 装配；全部用 `java.time` | `OutboxPoller`, `JdbcOutboxStore`, `MybatisPlusOutboxStore`, `BusinessStateMachine*` 补全 `@Slf4j` 与合规装配；README 与治理测试同步新配置键 | Step 1, Step 3, Step 4; `MC-LOG-001`, `MC-BEAN-001`, `MC-UTIL-001`, `MC-JSON-001`, `MC-TIME-001`, `MC-CONFIG-001` |
| Business variation/pattern | `OutboxLifecycleStateMachineFactory`, `DeliveryHandlerRegistry`, `OutboxStateMachineProperties` | `RECLAIM` 使用 State+Guard；通道发现使用 Registry；缺失定义退避使用轻量布尔配置 | 无长 `if/else` 分派与无谓抽象 | Step 2, Step 3, Step 4; `MC-PATTERN-001` |

#### Capability reuse ledger

| Need | Candidates inspected | Exact evidence | Fit/gap | Decision | Added dependency/custom code | Owning Step/check |
| --- | --- | --- | --- | --- | --- | --- |
| 轮询抖动延时 | Spring `TaskScheduler#schedule`, `ThreadLocalRandom`, `java.time.Duration/Instant` | `OutboxPoller.java` 已注入 `TaskScheduler`；`ExponentialJitterRetryPolicy.java` 已使用随机抖动模型 | `OutboxPoller` 当前只调用 `scheduleWithFixedDelay` | 复用既有 `TaskScheduler` 与 JDK 原生随机源 | `None`（零新依赖/零新工具类） | Step 1; `MC-REUSE-001`, `MC-DEP-001` |
| `RECLAIM` 重试上限 | `OutboxLifecycleStateMachineFactory.RETRY_WITH_BUDGET` / `RETRY_EXHAUSTED` | `OutboxLifecycleStateMachineFactory.java#L33-L47` | 已存在完全匹配的 Guard，仅 `RECLAIM` 边未绑定 | 直接复用既有两项 Guard | `None` | Step 2; `MC-REUSE-001` |
| 通道注册集合查询 | `DeliveryHandlerRegistry.handlers` | `DeliveryHandlerRegistry.java#L16-L37` | 缺公开只读访问方法 | 新增 `channels()` | `None` | Step 3; `MC-REUSE-001` |
| 配置参数校验 | `OutboxConfigurationValidator`, `ValidationUtils`, Jakarta `@Min` | `OutboxConfigurationValidator.java` 已有 `retry.jitter` 校验逻辑 | 缺 `polling.jitter` 与 `polling.maxClaimContentionRetries` 校验 | 复用 `OutboxConfigurationValidator` | `None` | Step 1; `MC-REUSE-001`, `MC-VALID-001` |

### 4.8 User-mandated Java Rule Implementation Matrix

| Literal rule | Spec source | Repository evidence | Exact files and order | Implementation obligations | Validation gate | Steps | Status/blocker |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Rule 1 | [Spec](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) §6.2, §8 | `OutboxPoller`, `OutboxStore`, `OutboxMessageDAO`, `OutboxMessageRepository`, `TransactionalOutboxProperties`, `OutboxStateMachineProperties` | 见 §5 全量修改文件列表 | 不新增任何类，全部既有类保持标准职责后缀，不出现 `Data`/`Info`/`Param`/`Bean` | 编译与命名检查 | Step 1–4 | PASS |
| Rule 2 | [Spec](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) §6.2, §9 | `OutboxConfigurationValidator.java`, `OutboxStore.java`, `OutboxMessageDAO.java`, `OutboxMessageRepository.java` | `OutboxConfigurationValidatorTest` -> `TransactionalOutboxProperties` -> `OutboxConfigurationValidator`；`OutboxStore` -> `OutboxMessageDAO` -> `OutboxMessageRepository` | 校验 `polling.jitter` `[0.0,1.0]` 有限值与 `maxClaimContentionRetries >= 1`；`OutboxStore`/`DAO`/`Repository` 约束 `channels` 元素非空白且最大长度 64 | `OutboxConfigurationValidatorTest`, `OutboxMultiShardStoreTest` | Step 1, Step 3 | PASS |
| Rule 3 | [Spec](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) §6.2, §10 | `OutboxMessagePO`, `OutboxRecord`, `BusinessStateMachineEvent` 均 `Unchanged` | 无新建或变更 POJO/VO/Record/Converter | 保持已有 PO/Record/Converter 不变；配置 POJO 保持 getter/setter 绑定结构 | `ComponentContractGovernanceTest` | Step 3–4 | N/A |
| Rule 4 | [Spec](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) §6.2, §7.3.5 | `lombok.config`, `OutboxPoller`, `JdbcOutboxStore`, `MybatisPlusOutboxStore`, `OutboxDispatcher`, `BusinessStateMachineDeliveryHandler`, `BusinessStateMachineService` | 各修改业务类与对应 `@AutoConfiguration` | 所有发出新日志的修改类使用 `@Slf4j` 且仅记录安全标识符；`AutoConfiguration` 维持显式 Bean 名与 `@Qualifier` 参数注入；既有公开构造器保留向后兼容，新增带参注入通过既有 `@Bean` 工厂方法或 `@RequiredArgsConstructor(onConstructor_ = @Autowired)` 完成 | 上下文加载与单测 | Step 1, Step 3, Step 4 | PASS |
| Rule 5 | [Spec](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) §6.2 | `pom.xml`, `ExponentialJitterRetryPolicy.java` | `OutboxPoller`, `JdbcOutboxStore`, `MybatisPlusOutboxStore` | 仅使用 JDK 原生 `ThreadLocalRandom`, `java.time.*`, `java.util.*`，禁止新依赖与 `*Utils` | 依赖树与 import 检查 | Step 1, Step 3 | PASS |
| Rule 6 | [Spec](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) §6.2 | 外部 JSON 契约与 `OutboxStatus`/`OutboxLifecycleSignalEnum` 编码均未修改 | 无变更 | 保留 Spring Boot Jackson 与 `@EnumValue` 现有契约不变 | `ComponentContractGovernanceTest` | Step 4 | N/A |
| Rule 7 | [Spec](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) §6.2, §16 | `TransactionalOutboxProperties`, `OutboxStateMachineProperties`, `README.md`, `README.zh-CN.md`, `ComponentContractGovernanceTest` | `TransactionalOutboxProperties` -> `OutboxStateMachineProperties` -> `README.md` -> `README.zh-CN.md` -> `ComponentContractGovernanceTest` | 新配置项具备安全默认值；中英文 README 文档键与 `ComponentContractGovernanceTest` `requiredKeys` 完全同步一致 | `ComponentContractGovernanceTest` | Step 1, Step 4 | PASS |
| Rule 9 | [Spec](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) §6.2, §13 | `OutboxLifecycleStateMachineFactory`, `DeliveryHandlerRegistry`, `BusinessStateMachineService` | `OutboxLifecycleServiceTest` -> `OutboxLifecycleStateMachineFactory` -> `DeliveryHandlerRegistry` -> `BusinessStateMachine*` | 生命周期流转复用 State + Guard 模式；通道发现复用 Registry 模式 | `OutboxLifecycleServiceTest`, `DeliveryHandlerRegistryTest`, `BusinessStateMachineServiceTest` | Step 2, Step 3, Step 4 | PASS |
| Rule 10 | [Spec](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) §6.2, §6.3 | `OutboxPoller`, `JdbcOutboxStore`, `MybatisPlusOutboxStore` | `OutboxPoller`, `JdbcOutboxStore`, `MybatisPlusOutboxStore`, `OutboxMessageMapper.xml` | 全程使用 `java.time.Duration`, `java.time.Instant`, `java.time.Clock` 及 PG `clock_timestamp()`，禁止 `java.util.Date` | 静态搜索与 `OutboxPollerTest` | Step 1, Step 3 | PASS |
| Rule 11 | [Spec](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) §6.1, §6.2 | `egon-cola-components-architecture.md`, `ComponentContractGovernanceTest` | 全部 Step 所属文件 | 保留基础设施 Flat Starter 包布局，不创造第三种架构 | `ComponentContractGovernanceTest` | Step 1, Step 2, Step 3, Step 4 | PASS |

### 4.9 Time semantics and conversion contract

Time contract: `Required`

| Time ID | Fields/symbols | Semantics | Java/runtime type | Storage policy | Wire policy | Business zone source | Display zone source | Boundary / DST rules | Precision / Clock | Compatibility | Verification |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `TIME-001` | `TransactionalOutboxProperties.Polling#fixedDelay`、`OutboxPoller` 抖动调度间隔 | Duration | `java.time.Duration` | Not persisted | Internal | Not applicable: 纯相对延时计算，不涉日历与业务时区 | Not applicable: 无前端展示 | 基于 `fixedDelay` 与 `jitter` 计算 `[fixedDelay, fixedDelay * (1 + jitter)]`（首轮 `[0, fixedDelay * jitter]`），下限钳制 `>= 1ms` | 毫秒级相对时长；调度时通过 `Instant.now(clock).plus(delay)` 计算触发瞬间 | `jitter = 0.0` 时严格等价于原固定延时调度 | Step 1; `TEST-001`, `TEST-002`, `OutboxPollerTest` |
| `TIME-002` | `egon_cola_outbox_message.completed_at`、`updated_at`、`locked_until`（`RECLAIM -> DEAD` 分支） | Occurred instant | `java.time.Instant` | UTC instant | Internal | Not applicable: 技术基础设施表统一 UTC 绝对瞬间 | Not applicable: 无前端展示 | PostgreSQL `clock_timestamp()` 比较与写入均基于服务器事务时钟瞬间，不受会话时区或 DST 影响 | 微秒级 `timestamptz(6)`；`MybatisPlusOutboxStore` 使用注入的 `egonColaMybatisPlusClock`，`JdbcOutboxStore` 使用 PG `clock_timestamp()` | 与既有 `markDead`/`markSucceeded` 写入语义完全一致 | Step 3; `TEST-008`, `TEST-009`, `PostgresqlOutboxRecoveryIntegrationTest` |

## 5. Change File Tree

| Operation | Path | Current evidence/symbol | Final symbols/state | Responsibility | Step | Requirements | Validation owner |
| --- | --- | --- | --- | --- | --- | --- | --- |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/OutboxConfigurationValidatorTest.java` | 既有 `shouldRejectInvalidRetryAndCapacityRanges` | 新增 `polling.jitter` 与 `polling.maxClaimContentionRetries` 边界校验测试 | Step 1 配置校验 RED/GREEN 守卫 | Step 1 | `REQ-001`, `REQ-002` | `OutboxConfigurationValidatorTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/dispatch/OutboxPollerTest.java` | 既有固定周期测试 | 新增 `jitter=0.2` 随机区间、异常续约与 `jitter=0.0` 兼容性测试 | Step 1 轮询抖动 RED/GREEN 守卫 | Step 1 | `REQ-001` | `OutboxPollerTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/TransactionalOutboxProperties.java` | `Polling` 含 `enabled`, `fixedDelay`, `batchSize`, `concurrency` | `Polling` 新增 `jitter=0.2`, `maxClaimContentionRetries=8` | 声明轮询抖动与 MP 冲突重开上限属性 | Step 1 | `REQ-001`, `REQ-002` | `OutboxConfigurationValidatorTest`, `TransactionalOutboxPropertiesTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/OutboxConfigurationValidator.java` | `validateCore` 校验 `retry.jitter` 等 | 增加 `polling.jitter` 与 `polling.max-claim-contention-retries` 合法性校验 | 启动期拦截非法轮询配置 | Step 1 | `REQ-001`, `REQ-002` | `OutboxConfigurationValidatorTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/dispatch/OutboxPoller.java` | `start()` 调用 `scheduleWithFixedDelay` | `@Slf4j`，支持抖动自调度与异常保护续约，保留 4 参公有构造器并增设包内可测构造器 | 实现错峰抖动轮询主逻辑 | Step 1 | `REQ-001` | `OutboxPollerTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/statemachine/OutboxLifecycleServiceTest.java` | `everyConfiguredLifecycleSignalUsesTheExpectedStateMachineTarget` | 增加 `RECLAIM` 在达标与超限时的边界断言 | Step 2 `RECLAIM` 守卫 RED/GREEN 守卫 | Step 2 | `REQ-005` | `OutboxLifecycleServiceTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/statemachine/OutboxLifecycleStateMachineFactory.java` | `RECLAIM` 边无 Guard | `RECLAIM` 边按 `RETRY_WITH_BUDGET` -> `PROCESSING` 与 `RETRY_EXHAUSTED` -> `DEAD` 分流 | 统一由 Spring Statemachine 决策毒丸接管上限 | Step 2 | `REQ-005` | `OutboxLifecycleServiceTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/delivery/DeliveryHandlerRegistryTest.java` | `shouldResolveExactlyOneHandlerAndValidateDestination` | 新增 `channels()` 不可变性与空集合测试 | Step 3 通道查询 RED/GREEN 守卫 | Step 3 | `REQ-003` | `DeliveryHandlerRegistryTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/dispatch/OutboxDispatcherTest.java` | `RecordingOutboxStore` 与调度测试 | 断言 `dispatchDue`/`dispatchMessageIds` 向 Store 精确传入 `channels()` | Step 3 分发传参 RED/GREEN 守卫 | Step 3 | `REQ-003` | `OutboxDispatcherTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/store/OutboxMultiShardStoreTest.java` | 分片抢锁单测 | 新增 MP Store 连续锁冲突达到 `maxClaimContentionRetries` 后停止重试的测试 | Step 3 MP 冲突上限 RED/GREEN 守卫 | Step 3 | `REQ-002` | `OutboxMultiShardStoreTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/delivery/DeliveryHandlerRegistry.java` | `required(channel)` | 新增 `channels()` 返回不可变 `Set<String>` | 对外暴露本机已注册投递通道 | Step 3 | `REQ-003` | `DeliveryHandlerRegistryTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxStore.java` | 3/4 参 `claimDue` / `claimByMessageIds` | 新增带 `Collection<String> channels` 参数的 `default` 重载方法 | 扩展接口支持通道过滤并向后兼容 | Step 3 | `REQ-003`, `REQ-006` | `OutboxDispatcherTest`, `OutboxMultiShardStoreTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/dao/OutboxMessageDAO.java` | `selectDueCandidates`, `selectByMessageIdsCandidates` | 候选查询方法支持可选 `channels` 参数，并新增 `updateReclaimDead` | 声明 MP 通道过滤查询与毒丸终态更新 DAO 接口 | Step 3 | `REQ-003`, `REQ-005` | `OutboxMultiShardStoreTest`, PG 集成测试 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/resources/mapper/outbox/OutboxMessageMapper.xml` | 对应 `selectDueCandidates`, `selectByMessageIdsCandidates` | 候选查询追加动态 `AND channel IN (...)`，新增 `updateReclaimDead` SQL | 实现兼容 SQL 守卫的动态过滤与 `RECLAIM -> DEAD` CAS 更新 | Step 3 | `REQ-003`, `REQ-005` | PG 集成测试 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/repository/OutboxMessageRepository.java` | 代理 `OutboxMessageDAO` 查询与更新 | 新增带 `channels` 参数的候选查询方法（保留旧签名兼容）与 `updateReclaimDead` | Repository 门面校验参数并转发 DAO（见 `PLAN-CLAR-001`） | Step 3 | `REQ-003`, `REQ-005`, `REQ-006` | `OutboxMultiShardStoreTest`, PG 集成测试 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/JdbcOutboxStore.java` | `claim(...)` 无通道过滤与无 `RECLAIM -> DEAD` 处理 | `@Slf4j`，支持 `channels` 过滤/空集合短路与 `RECLAIM -> DEAD` 原子更新 | JDBC 存储完整实现 `REQ-003` 与 `REQ-005` | Step 3 | `REQ-003`, `REQ-005`, `REQ-006` | `PostgresqlOutboxRecoveryIntegrationTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/MybatisPlusOutboxStore.java` | `claimDue`, `claimByMessageIds`, `inWorkerTransactionSkippingLocked` | 支持 `channels` 过滤/空集合短路、冲突重试上限控制（默认 8，新重载构造器可注）与 `RECLAIM -> DEAD` 原子更新 | MP 存储完整实现 `REQ-002`、`REQ-003` 与 `REQ-005`（见 `PLAN-CLAR-002`） | Step 3 | `REQ-002`, `REQ-003`, `REQ-005`, `REQ-006` | `OutboxMultiShardStoreTest`, PG 集成测试 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/OutboxMybatisPlusAutoConfiguration.java` | `outboxStore(...)` 注入 9 项参数 | 向 `MybatisPlusOutboxStore` 传入 `TransactionalOutboxProperties` 的 `maxClaimContentionRetries`（保留可选默认） | 将 `polling.max-claim-contention-retries` 真正装配到运行期 Bean | Step 3 | `REQ-002` | `OutboxMybatisPlusAutoConfigurationTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/dispatch/OutboxDispatcher.java` | `dispatchDue` / `dispatchMessageIds` 调旧签名 | 调用带 `handlerRegistry.channels()` 的 `OutboxStore` 重载 | 调度层落地本机能力过滤 | Step 3 | `REQ-003` | `OutboxDispatcherTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/PostgresqlOutboxRecoveryIntegrationTest.java` | `shouldRespectDueTimeAndRecoverOnlyExpiredLeases` | 覆写增加 MP 与 JDBC 下异构 `channel` 过滤、`channels=[]` 短路与 `RECLAIM` 满额直达 `DEAD` 集成用例 | 真实 PG 容器验证 Step 3 核心行为 | Step 3 | `REQ-003`, `REQ-005` | Failsafe `PostgresqlOutboxRecoveryIntegrationTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/PostgresqlOutboxQueryPlanIntegrationTest.java` | `shouldExposeRequiredSchemaAndUseClaimIndexes` | 追加带 `channel IN (...)` 过滤条件的 `EXPLAIN` 索引命中验证 | 实测确认追加 `channel` 过滤不退化索引计划 | Step 3 | `REQ-003`, `REQ-006` | Failsafe `PostgresqlOutboxQueryPlanIntegrationTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/delivery/statemachine/BusinessStateMachineDeliveryHandlerTest.java` | 缺失定义断言 `PERMANENT_FAILURE` | 覆写验证默认 `retryOnMissingDefinition=true` 返 `RETRYABLE_FAILURE`，`false` 返 `PERMANENT_FAILURE`，`validateDestination` 仍抛异常 | Step 4 Handler 行为 RED/GREEN 守卫 | Step 4 | `REQ-004` | `BusinessStateMachineDeliveryHandlerTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/statemachine/BusinessStateMachineServiceTest.java` | `process` 缺失定义断言 | 验证 `retryOnMissingDefinition=true/false` 两种配置分支 | Step 4 Service 行为 RED/GREEN 守卫 | Step 4 | `REQ-004` | `BusinessStateMachineServiceTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/OutboxStateMachineProperties.java` | `BusinessProperties` 含 `enabled`, `timeout` | 新增 `retryOnMissingDefinition = true` | 声明滚动发布缺失业务定义退避开关 | Step 4 | `REQ-004` | `OutboxBusinessStateMachineAutoConfigurationTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/delivery/statemachine/BusinessStateMachineDeliveryHandler.java` | 4 参构造器，`definitions.get == null` 直接永久失败 | 保留 4 参构造器兼容并增设含 `OutboxStateMachineProperties` 构造器，按开关返回可重试/永久失败；`validateDestination` 不变 | Handler 层安全处理老版本缺失定义（见 `PLAN-CLAR-003`） | Step 4 | `REQ-004`, `REQ-006` | `BusinessStateMachineDeliveryHandlerTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/statemachine/BusinessStateMachineService.java` | 已注入 `OutboxStateMachineProperties`，`definitions.get == null` 返永久失败 | 读取 `properties.getBusiness().isRetryOnMissingDefinition()` 按开关返回 `retryableFailure` 或 `permanentFailure` | Service 层与 Handler 层行为保持绝对一致 | Step 4 | `REQ-004` | `BusinessStateMachineServiceTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/OutboxBusinessStateMachineAutoConfiguration.java` | `outboxBusinessStateMachineDeliveryHandler(...)` 调 4 参构造 | 向 Handler 传入 `@Qualifier("outboxStateMachineProperties") OutboxStateMachineProperties` | 完成 `retryOnMissingDefinition` 自动装配 | Step 4 | `REQ-004` | `OutboxBusinessStateMachineAutoConfigurationTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/BusinessStateMachineIntegrationTest.java` | 已有 `BusinessStateMachineIntegrationTest` | 新增 Pod A（缺 `:2`）先领走退避进入 `RETRY_WAIT`，后由 Pod B（含 `:2`）消费成功集成测试 | 真实 PG 容器证明滚动发版跨版本收敛 | Step 4 | `REQ-004` | Failsafe `BusinessStateMachineIntegrationTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/README.md` | 文档列出现有 `state-machine.*` 与 `storage.mp.*` 键 | 补充 `egon.cola.component.transactional-outbox.state-machine.business.retry-on-missing-definition` 及轮询抖动/`RECLAIM` 说明 | 保持英文官方文档准确 | Step 4 | `REQ-006` | `ComponentContractGovernanceTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/README.zh-CN.md` | 中文文档与英文对应 | 同步补充相同配置键与行为说明 | 保持中文官方文档与英文一致 | Step 4 | `REQ-006` | `ComponentContractGovernanceTest` |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/contract/ComponentContractGovernanceTest.java` | `requiredKeys` 硬编码 10 个反引号键 | 将 `egon.cola.component.transactional-outbox.state-machine.business.retry-on-missing-definition` 纳入 `requiredKeys`（若 `polling.jitter` 等也以反引号列于正文同样纳入） | 保障配置键文档与测试治理门禁不破裂 | Step 4 | `REQ-006` | `ComponentContractGovernanceTest` |

## 6. Prerequisites, Constraints, and Plan Clarifications

### 6.1 Repository and worktree baseline

- **仓库根与模块**：`Stellar-Enclosure-Components`（git 仓库根目录），目标模块 `stellar-enclosure-component-transactional-outbox-starter`。
- **当前工作区状态**：`main @ c88fd8912c9f1a80caec3bf6baa2bae0df97870b`，仅存在新建的 Spec 文件 `docs/egon/spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md`（计划本身落于 `docs/egon/plan/`）；除目标模块外，`Stellar-Enclosure-Components` 其他模块与 sibling 目录（`tianji-service`、`tianquan-shoubing-service` 等）全部保持未触碰状态。
- **提交范围规则**：所有提交严格限定在当前 Step 声明的 `Commit paths`，禁止 `git add -A` 或带入无关改动。

### 6.2 Build, test, and environment prerequisites

| Concern | Exact command/source | Required state | Validation boundary |
| --- | --- | --- | --- |
| Build & Unit (Surefire) | `mvn -pl stellar-enclosure-component-transactional-outbox-starter -Dtest=... test` | 工作目录为 `Stellar-Enclosure-Components`；JDK 25 与本地 Maven 依赖缓存可用 | 模块级单测，不依赖 Docker 或 PG |
| Integration (Failsafe PG) | `EGON_OUTBOX_TEST_POSTGRES_ENABLED=true mvn -pl stellar-enclosure-component-transactional-outbox-starter -Dtest=__skip_surefire__ -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=... -Dfailsafe.failIfNoSpecifiedTests=false verify` | 本机 Docker daemon 运行中，允许 Testcontainers 拉起隔离 PostgreSQL 实例 | 本地隔离 PG 容器，不等同生产高并发环境 |
| Contract Governance | `mvn -pl stellar-enclosure-component-transactional-outbox-starter -Dtest=ComponentContractGovernanceTest test` | 在模块目录下或由 Maven Surefire 以模块根目录解析 `README.md` / `src/main/java` | 静态源码与文档键一致性校验 |

### 6.3 Immutable constraints and approved decisions

- 严格禁止修改 `V20260924_001__initialize_outbox_mp_schema.sql`、`V20261027_001__add_outbox_shard_slot.sql`、`V1__create_transactional_outbox_schema.sql` 与 `manifest.json`（由 `ComponentContractGovernanceTest#managedMpMigrationChecksumsBothVersionsAndKeepsLegacySqlImmutable` 强校验）。
- 严格禁止在 `OutboxMessageMapper.xml` 中使用 `FOR UPDATE SKIP LOCKED`、CTE 或 `UPDATE FROM`（遵守 `EgonColaOriginalSqlGuardInterceptor` 与 ShardingSphere 5.5.3 约束）。
- 严格禁止引入 Redis、Quartz、XXL-Job 或任何新依赖。

### 6.4 Plan Clarifications

| ID | Small implementation inference | Repository evidence | Why semantics are unchanged | Impact if wrong |
| --- | --- | --- | --- | --- |
| `PLAN-CLAR-001` | Spec 提及 `OutboxMessageDAO` 与 `MybatisPlusOutboxStore`，实际源码中 `MybatisPlusOutboxStore` 通过 `OutboxMessageRepository` 门面调用 `OutboxMessageDAO`；因此在 Step 3 一并修改 `OutboxMessageRepository.java` 转发 `channels` 重载（保留原方法兼容）与 `updateReclaimDead`。 | `MybatisPlusOutboxStore.java#L50,L150,L172`、`OutboxMessageRepository.java#L60-L82` | `OutboxMessageRepository` 是纯粹的 DAO 转发与批量边界检验门面，无业务逻辑变更。 | 仅需确保 `OutboxMessageRepositoryTest` / `OutboxMultiShardStoreTest` 参数约束与 DAO 一致。 |
| `PLAN-CLAR-002` | `MybatisPlusOutboxStore` 当前构造函数未接收 `TransactionalOutboxProperties`，新增一个包含 `int maxClaimContentionRetries` 的重载构造器并保留既有 8/9 参构造器（默认 `8`），由 `OutboxMybatisPlusAutoConfiguration#outboxStore` 传入配置值。 | `MybatisPlusOutboxStore.java#L76-L97`、`OutboxMybatisPlusAutoConfiguration.java#L176-L198` | 既保留测试桩与既有构造调用 100% 向后兼容，又让运行期 Bean 真实消费 `polling.max-claim-contention-retries`。 | 若未来再增加 `OutboxMpStorageProperties` 属性需同步检查构造器列表。 |
| `PLAN-CLAR-003` | `BusinessStateMachineDeliveryHandler` 当前 4 参构造器未接收 `OutboxStateMachineProperties`，新增 5 参构造器接收 `@Qualifier("outboxStateMachineProperties") OutboxStateMachineProperties` 并保留原 4 参构造器（默认 `new OutboxStateMachineProperties()`，即 `retryOnMissingDefinition = true`），由 `OutboxBusinessStateMachineAutoConfiguration` 注入真实配置 Bean。 | `BusinessStateMachineDeliveryHandler.java#L29-L44`、`OutboxBusinessStateMachineAutoConfiguration.java#L140-L150`、`BusinessStateMachineDeliveryHandlerTest.java#L116-L125` | 保持既有 4 参单测与默认行为语义完全一致，同时支持配置文件动态切换为 `false`。 | 若外部手动实例化 4 参 Handler 且未传配置，将使用默认值 `true`。 |

## 7. Ordered File-by-file Implementation Steps

### Step 1 — 增加轮询抖动与 MP 冲突重开上限配置校验及 `OutboxPoller` 抖动调度

- Requirements: `REQ-001`, `REQ-002`（配置声明与启动校验）
- Dependencies: `None`
- Baseline state: `TransactionalOutboxProperties.Polling` 无 `jitter` 与 `maxClaimContentionRetries`；`OutboxPoller#start` 使用固定间隔 `scheduleWithFixedDelay`；现有 `OutboxConfigurationValidatorTest` 与 `OutboxPollerTest` 全通过。
- Observable outcome: 合法配置的 `OutboxPoller` 在默认 `jitter=0.2` 下按 `[fixedDelay, fixedDelay * 1.2]`（首轮 `[0, fixedDelay * 0.2]`）自调度并在异常后继续续约，`jitter=0.0` 时保留 `scheduleWithFixedDelay`；非法 `polling.jitter` 与 `maxClaimContentionRetries` 在校验期抛 `OutboxConfigurationException`。
- End state: 轮询配置属性、校验器与 `OutboxPoller` 完成就位并有单测护航；`MybatisPlusOutboxStore` 对 `maxClaimContentionRetries` 的实际消费留待 Step 3。
- Test-first gate: `Required` — 新增测试用例在配置属性与 `OutboxPoller` 抖动调度实现前将因未拒绝非法参数或仍调用 `scheduleWithFixedDelay` 而失败。
- Manual Checks: `MC-ARCH-001, MC-REUSE-001, MC-DEP-001, MC-NAME-001, MC-VALID-001, MC-LOG-001, MC-BEAN-001, MC-UTIL-001, MC-TIME-001, MC-CONFIG-001, MC-SCOPE-001, MC-TEST-001`
- Literal Rules: `Rule 1`, `Rule 2`, `Rule 4`, `Rule 5`, `Rule 7`, `Rule 10`, `Rule 11`
- Ordered files:

#### File 1 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/OutboxConfigurationValidatorTest.java`

- Purpose: 在改动生产配置与校验逻辑前固定 `polling.jitter` 与 `polling.maxClaimContentionRetries` 的失败边界。
- Symbols: `shouldRejectInvalidPollingJitterAndContentionRetries`
- Repository evidence: 现有 `OutboxConfigurationValidatorTest#shouldRejectInvalidRetryAndCapacityRanges` 直接实例化 `TransactionalOutboxProperties` 并断言抛出 `OutboxConfigurationException`。
- Dependencies and consumers: 依赖 `TransactionalOutboxProperties.Polling` 的 `setJitter`、`setMaxClaimContentionRetries` 与 `OutboxConfigurationValidator#validate`。
- Why now: 作为 Step 1 的第一个 RED 测试文件。
- Contract/signature changes: 测试侧消费新增 getter/setter。
- Input/output and state mapping: 传入 `polling.jitter` 为 `-0.1`、`1.1`、`Double.NaN` 以及 `maxClaimContentionRetries` 为 `0`、`-1` -> 期望抛 `OutboxConfigurationException` 且消息包含对应字段键；边界值 `0.0`、`1.0`、`1` 校验通过。
- Error and edge behavior: 验证 NaN、负值与超上界输入不静默放行。
- Standards impact: `MC-VALID-001`, `MC-CONFIG-001`, `MC-TEST-001` — 覆盖边界正反测试。
- Literal rule enforcement: `Rule 2`, `Rule 7`, `Rule 11` — 验证 `@ConfigurationProperties` 校验边界且保持原测试目录结构。
- Required change: 在 `OutboxConfigurationValidatorTest` 中增加针对 `polling.jitter` 非法值与 `polling.maxClaimContentionRetries` 非正数的异常断言及合法极值断言。
- Verification contribution: 运行 `-Dtest=OutboxConfigurationValidatorTest` 作为 RED/GREEN 锚点之一。
- After this file: 在 File 3 新增属性前测试暂不可编译；File 3 完成而 File 4 未完成时测试呈期待中的校验缺失 RED。

#### File 2 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/dispatch/OutboxPollerTest.java`

- Purpose: 在改动 `OutboxPoller` 前固定抖动调度区间、异常后持续调度以及 `jitter=0.0` 向后兼容的测试契约。
- Symbols: 更新既有固定周期用例以显式覆盖 `jitter=0.0` 与默认 `jitter=0.2`，新增 `shouldSchedulePollingWithJitterAndContinueAfterFailure`
- Repository evidence: 现有 `OutboxPollerTest` mock `TaskScheduler` 并用 `ArgumentCaptor<Runnable>` 捕获任务验证 `stop()` 防护。
- Dependencies and consumers: 调用 `OutboxPoller` 4 参或新增带 `DoubleSupplier`/`Clock` 重载构造器及 `TaskScheduler#schedule(Runnable, Instant)`。
- Why now: 锁定 `OutboxPoller` 抖动行为作为 RED 基准。
- Contract/signature changes: 测试中使用 `OutboxPoller` 包内可见测试构造器传入固定随机数与 `Clock`。
- Input/output and state mapping: `fixedDelay = 1000ms`、`jitter = 0.2`、随机源返回 `0.5` -> 首轮触发时间在 `clock.instant().plusMillis(100)`，任务执行后下轮调度时间为 `clock.instant().plusMillis(1100)`；首次执行抛出 `RuntimeException` 后仍再次注册下一次 `schedule`；`jitter = 0.0` 时走 `scheduleWithFixedDelay`。
- Error and edge behavior: 轮询任务异常不终止调度链；`stop()` 后在执行中的回调不再发起下一轮 `schedule`。
- Standards impact: `MC-TIME-001`, `MC-TEST-001`, `MC-SCOPE-001` — 校验 `java.time.Duration`/`Instant` 相对计算。
- Literal rule enforcement: `Rule 10`, `Rule 11` — 使用 `java.time.Clock`/`Instant`/`Duration` 构造确定性时间测试，保持现有包位置。
- Required change: 调整既有 `OutboxPollerTest` 两个用例明确指定 `jitter=0.0`（或使用默认测试新调度）并新增测试验证默认抖动延时区间、异常续约与极小 `fixedDelay` 钳制 `>= 1ms`。
- Verification contribution: 运行 `-Dtest=OutboxPollerTest` 证明 `REQ-001` 行为与兼容性。
- After this file: File 5 实现前，`OutboxPollerTest` 因构造器/调度接口未就位处于编译或行为 RED。

#### File 3 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/TransactionalOutboxProperties.java`

- Purpose: 在 `Polling` 嵌套配置类中添加 `jitter` 与 `maxClaimContentionRetries` 属性。
- Symbols: `TransactionalOutboxProperties.Polling#jitter`、`#getJitter`、`#setJitter`、`#maxClaimContentionRetries`、`#getMaxClaimContentionRetries`、`#setMaxClaimContentionRetries`
- Repository evidence: `TransactionalOutboxProperties.java#L180-L218` 已有 `Polling`，`#L251-L305` `Retry` 已按相同风格定义 `double jitter = 0.2`。
- Dependencies and consumers: 被 `OutboxConfigurationValidator`、`OutboxPoller` 与 Step 3 的 `OutboxMybatisPlusAutoConfiguration` 读取。
- Why now: 为 File 1/File 2/File 4/File 5 提供编译所需的配置属性载体。
- Contract/signature changes: `Polling` 新增 `private double jitter = 0.2;` 与 `private int maxClaimContentionRetries = 8;` 及标准 getter/setter。
- Input/output and state mapping: 未在 YAML 显式配置时分别默认 `0.2` 与 `8`；支持 Spring Boot 属性绑定 `egon.cola.component.transactional-outbox.polling.jitter` 与 `...polling.max-claim-contention-retries`。
- Error and edge behavior: 数值范围合法性统一交由 File 4 `OutboxConfigurationValidator` 校验，不在此处静默修正非法值。
- Standards impact: `MC-NAME-001`, `MC-MODEL-001`, `MC-CONFIG-001`, `MC-SCOPE-001` — 复用既有 `@ConfigurationProperties` JavaBean 模式，不加 `@Autowired`。
- Literal rule enforcement: `Rule 1`, `Rule 7`, `Rule 11` — 沿用 `*Properties` 命名与标准配置属性绑定。
- Required change: 在 `TransactionalOutboxProperties.Polling` 内部增加 `jitter`（默认 `0.2`）和 `maxClaimContentionRetries`（默认 `8`）字段与访问方法。
- Verification contribution: 使 File 1 编译通过并进入待校验 RED 状态。
- After this file: File 1 可编译，运行 `OutboxConfigurationValidatorTest` 稳定暴露缺失校验的 RED 失败。

#### File 4 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/OutboxConfigurationValidator.java`

- Purpose: 对新增的 `polling.jitter` 与 `polling.maxClaimContentionRetries` 执行严格启动校验。
- Symbols: `OutboxConfigurationValidator#validateCore`
- Repository evidence: `OutboxConfigurationValidator.java#L52-L73` 已使用 `positive(...)` 与 `Double.isFinite` 校验 `retry.jitter`。
- Dependencies and consumers: `OutboxInfrastructure` 在启动创建期调用 `validator.validate(properties)`。
- Why now: File 1 的 RED 测试等待此校验逻辑使 `OutboxConfigurationValidatorTest` 转 GREEN。
- Contract/signature changes: 无签名变更，在 `validateCore` 中追加两个字段判断。
- Input/output and state mapping: `polling.maxClaimContentionRetries` 调用 `positive(..., "polling.max-claim-contention-retries")`；`polling.jitter` 检查 `!Double.isFinite || < 0.0 || > 1.0` 时抛出 `invalid("polling.jitter")`。
- Error and edge behavior: 非法值抛 `OutboxConfigurationException`，消息键明确为 `polling.jitter` / `polling.max-claim-contention-retries`。
- Standards impact: `MC-VALID-001`, `MC-CONFIG-001`, `MC-SCOPE-001` — 沿用既有一致错误前缀。
- Literal rule enforcement: `Rule 2`, `Rule 7`, `Rule 11` — 沿用 `BaseValidator` 既有校验体系。
- Required change: 在 `OutboxConfigurationValidator#validateCore` 中参照 `retry.jitter` 与 `polling.concurrency` 的写法校验 `polling.jitter` 与 `polling.maxClaimContentionRetries`。
- Verification contribution: 使 `OutboxConfigurationValidatorTest` 转 GREEN。
- After this file: 配置及校验完整可用，`OutboxConfigurationValidatorTest` 全绿。

#### File 5 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/dispatch/OutboxPoller.java`

- Purpose: 实现带随机抖动的可恢复单次链式调度，同时保留 `jitter=0.0` 的 `scheduleWithFixedDelay` 模式与原有 4 参公有构造器。
- Symbols: `OutboxPoller`、`#start()`、`#stop()`、`#scheduleNextPoll(boolean initial)`、`#computePollDelay(boolean initial)`
- Repository evidence: `OutboxPoller.java` 与 `TransactionalOutboxAutoConfiguration.java#L325-L334`（使用 4 参构造器创建 `OutboxPoller` Bean）；`ExponentialJitterRetryPolicy.java` 采用注入 `DoubleSupplier` 以便测试确定性。
- Dependencies and consumers: 依赖 `OutboxDispatcher`, `OutboxCleanupJob`, `TaskScheduler`, `TransactionalOutboxProperties`；仅由 `TransactionalOutboxAutoConfiguration` 装配。
- Why now: 完成 File 2 的最小 GREEN 实现。
- Contract/signature changes: 保留原有 `public OutboxPoller(OutboxDispatcher, OutboxCleanupJob, TaskScheduler, TransactionalOutboxProperties)` 4 参构造器（内部委派 `Clock.systemUTC()` 与 `() -> ThreadLocalRandom.current().nextDouble()`），新增包内可见测试构造器接收 `DoubleSupplier` 与 `Clock`。
- Input/output and state mapping: `jitter == 0.0` 时沿用 `taskScheduler.scheduleWithFixedDelay`；`jitter > 0.0` 时在 `start()` 调度首轮（延时 `[0, baseMillis * jitter]`），每次执行 `submitDueWhenRunning()` 并在 `finally` 判断 `if (running)` 后按 `[baseMillis, baseMillis * (1 + jitter)]`（钳制 `>= 1ms`）调用 `taskScheduler.schedule(this::runPollCycleAndReschedule, clock.instant().plus(nextDelay))`；`cleanupFuture` 仍用 `scheduleWithFixedDelay`。
- Error and edge behavior: 捕获 `RuntimeException` 记 `log.warn` 保证 `finally` 正常安排下一轮；`stop()` 原子加锁置 `running = false` 并取消 `pollingFuture`/`cleanupFuture`，正在执行的周期末尾见 `!running` 不再续约。
- Standards impact: `MC-LOG-001`, `MC-BEAN-001`, `MC-UTIL-001`, `MC-TIME-001`, `MC-SCOPE-001`, `MC-TEST-001` — 类上新增 `@Slf4j`，只用 JDK `ThreadLocalRandom` 与 `java.time.Duration/Instant/Clock`。
- Literal rule enforcement: `Rule 4`, `Rule 5`, `Rule 10`, `Rule 11` — 使用 `@Slf4j` 记非敏感警告日志，保留原有包路径与 4 参构造器兼容 `TransactionalOutboxAutoConfiguration`，全 `java.time` 计算。
- Required change: 为 `OutboxPoller` 添加 `@Slf4j` 与抖动自调度逻辑。
- Complex logic (pseudocode):
  - 启动 `start()` 时检查 `polling.enabled`：若 `jitter == 0.0` 仍调 `scheduleWithFixedDelay`；否则计算首轮抖动延时 `0 .. fixedDelay * jitter`（若为 0 则立刻或 `clock.instant()` 调度）调用 `taskScheduler.schedule(...)`；
  - 执行任务 `runPollCycleAndReschedule()`：`synchronized` 或 `volatile` 检查 `running` 后 `try` 调用 `dispatcher.submitDue()`；捕获 `RuntimeException` 打 `log.warn`（仅含异常类型，不吞 `Error`）；在 `finally` 中若仍 `running` 且 `jitter > 0.0` 则计算下一周期延时 `max(1ms, fixedDelay + round(fixedDelay * jitter * random))` 并更新 `pollingFuture`。
- Verification contribution: 使 `OutboxPollerTest` 全部转 GREEN。
- After this file: Step 1 所有目标文件实现完毕，核心轮询与配置测试全绿。

- Validation working directory: `/Users/mario/SelfProject/Stellar-Enclosure/Stellar-Enclosure-Components`
- Verification command: `mvn -pl stellar-enclosure-component-transactional-outbox-starter -Dtest=OutboxConfigurationValidatorTest,OutboxPollerTest,TransactionalOutboxPropertiesTest test`
- Expected result: Exit 0；上述 3 个测试类全部通过，无跳过或错误。
- Failure returns to: 校验报错回 File 4；调度/并发标志异常回 File 5。
- Completion criteria: `REQ-001` 与 `REQ-002`（配置校验边界）获得单测通过证明，`jitter=0.0` 向后兼容验证通过。
- Rollback: `git checkout --` 仅回退 Step 1 的 5 个路径。
- Commit paths: `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/OutboxConfigurationValidatorTest.java`, `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/dispatch/OutboxPollerTest.java`, `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/TransactionalOutboxProperties.java`, `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/OutboxConfigurationValidator.java`, `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/dispatch/OutboxPoller.java`
- Commit: `feat(outbox): add polling jitter and contention retry limit properties`

---

### Step 2 — 为技术生命周期状态机 `RECLAIM` 边增加重试预算 Guard

- Requirements: `REQ-005`（状态机决策规则修订，amends 历史 Spec §7.3.1）
- Dependencies: `None`（与 Step 1 文件完全独立，可并行或顺序执行）
- Baseline state: `OutboxLifecycleStateMachineFactory#create` 的 `RECLAIM` 边无 Guard，始终返回 `PROCESSING`；`OutboxLifecycleServiceTest` 通过。
- Observable outcome: `OutboxLifecycleService#evaluate("PROCESSING", RECLAIM, attemptCount, maxAttempts)` 在 `attemptCount < maxAttempts` 时返回 `PROCESSING`，在 `attemptCount >= maxAttempts` 时返回 `DEAD`。
- End state: 状态机规则修订完成且单测全绿；Store 层消费该 `DEAD` 目标的原子写回留待 Step 3。
- Test-first gate: `Required` — 新增 `attemptCount >= maxAttempts` 下的 `RECLAIM` 断言在 Guard 挂载前必然返回 `PROCESSING` 而 RED。
- Manual Checks: `MC-ARCH-001, MC-REUSE-001, MC-PATTERN-001, MC-SCOPE-001, MC-TEST-001`
- Literal Rules: `Rule 9`, `Rule 11`
- Ordered files:

#### File 1 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/statemachine/OutboxLifecycleServiceTest.java`

- Purpose: 固化 `RECLAIM` 在预算内返 `PROCESSING`、超限时返 `DEAD` 的状态机测试契约。
- Symbols: 新增 `reclaimTransitionsToDeadWhenAttemptBudgetIsExhausted`
- Repository evidence: `OutboxLifecycleServiceTest.java#L59-L102` 已有 `retriesAreSeparatedByTheConfiguredAttemptBudget` 测试相同风格的预算切换。
- Dependencies and consumers: 调用 `OutboxLifecycleService#evaluate`（底层驱动 `OutboxLifecycleStateMachineFactory`）。
- Why now: 建立 Step 2 RED 基线。
- Contract/signature changes: 无签名修改。
- Input/output and state mapping: `evaluate("PROCESSING", RECLAIM, 2, 3)` -> `OutboxStatus.PROCESSING`；`evaluate("PROCESSING", RECLAIM, 3, 3)` 与 `evaluate("PROCESSING", RECLAIM, 4, 3)` -> `OutboxStatus.DEAD`。
- Error and edge behavior: 保证 `attemptCount=1, maxAttempts=3` 的既有测试保持通过。
- Standards impact: `MC-PATTERN-001`, `MC-TEST-001`, `MC-SCOPE-001` — 直接验证真实 `StateMachine` Guard 行为，不使用 mock。
- Literal rule enforcement: `Rule 9`, `Rule 11` — 验证 Spring Statemachine State+Guard 规则并保持原测试路径。
- Required change: 在 `OutboxLifecycleServiceTest` 中新增对 `RECLAIM` 信号在不同 `attemptCount` 与 `maxAttempts` 组合下的断言用例。
- Verification contribution: 运行 `-Dtest=OutboxLifecycleServiceTest` 在修改工厂前稳定暴露预期 RED。
- After this file: 测试因 `RECLAIM` 返回 `PROCESSING` 而非 `DEAD` 失败（RED）。

#### File 2 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/statemachine/OutboxLifecycleStateMachineFactory.java`

- Purpose: 为 `RECLAIM` 边挂接复用既有 `RETRY_WITH_BUDGET` 与 `RETRY_EXHAUSTED` Guard。
- Symbols: `OutboxLifecycleStateMachineFactory#create`
- Repository evidence: `OutboxLifecycleStateMachineFactory.java#L62-L69` 已有 `DELIVERY_RETRYABLE` 挂接 `RETRY_WITH_BUDGET` / `RETRY_EXHAUSTED` 的完全对称写法。
- Dependencies and consumers: 被 `OutboxLifecycleService` 调用，Step 3 的 `JdbcOutboxStore` 与 `MybatisPlusOutboxStore` 消费其返回的 `OutboxStatus`。
- Why now: File 1 的 RED 测试等待此唯一工厂配置更改。
- Contract/signature changes: 无公开方法签名变化；扩展内部转移图，允许 `RECLAIM` 边在预算耗尽时转至 `DEAD`。
- Input/output and state mapping: 原单条 `PROCESSING --RECLAIM (guard=null)--> PROCESSING` 改为两条互斥 Guard 边：`PROCESSING --RECLAIM (RETRY_WITH_BUDGET)--> PROCESSING` 与 `PROCESSING --RECLAIM (RETRY_EXHAUSTED)--> DEAD`。
- Error and edge behavior: 两个 Guard 对有效整数 `attemptCount >= 0, maxAttempts >= 1` 互斥且完备，不会出现无边匹配或双边匹配。
- Standards impact: `MC-REUSE-001`, `MC-PATTERN-001`, `MC-SCOPE-001` — 直接复用既有 Guard 常量实现 State 模式，零新增类与零硬编码。
- Literal rule enforcement: `Rule 9`, `Rule 11` — 复杂生命周期流转通过状态机 Guard 显式表达，保持 `statemachine` 包结构不变。
- Required change: 在 `OutboxLifecycleStateMachineFactory#create()` 中将原 `RECLAIM` 无守卫转移替换为分别使用 `RETRY_WITH_BUDGET` 指向 `PROCESSING`、使用 `RETRY_EXHAUSTED` 指向 `DEAD` 的两条转移。
- Verification contribution: 使 `OutboxLifecycleServiceTest` 全量转 GREEN。
- After this file: 状态机模型完整支持 `RECLAIM` 超限熔断，Step 2 可安全提交。

- Validation working directory: `/Users/mario/SelfProject/Stellar-Enclosure/Stellar-Enclosure-Components`
- Verification command: `mvn -pl stellar-enclosure-component-transactional-outbox-starter -Dtest=OutboxLifecycleServiceTest,StateMachineExecutionServiceTest test`
- Expected result: Exit 0；`OutboxLifecycleServiceTest` 与 `StateMachineExecutionServiceTest` 全量通过。
- Failure returns to: File 2（`OutboxLifecycleStateMachineFactory#create`）或 File 1 断言参数。
- Completion criteria: 真实 Spring Statemachine 实例精确验证 `RECLAIM` 在预算内返 `PROCESSING`、满额返 `DEAD`。
- Rollback: `git checkout --` 仅回退 Step 2 的 2 个路径。
- Commit paths: `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/statemachine/OutboxLifecycleServiceTest.java`, `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/statemachine/OutboxLifecycleStateMachineFactory.java`
- Commit: `feat(outbox): guard reclaim lifecycle transition with attempt budget`

---

### Step 3 — 实现 `DeliveryHandlerRegistry` 通道暴露、Store 通道过滤/冲突上限/`RECLAIM -> DEAD` 落库与 `OutboxDispatcher` 联动

- Requirements: `REQ-002`（MP 冲突上限执行）, `REQ-003`, `REQ-005`（Store 原子落库）, `REQ-006`（兼容与索引）
- Dependencies: `Step 1`, `Step 2`
- Baseline state: Step 1（`maxClaimContentionRetries` 配置）与 Step 2（`RECLAIM` 预算 Guard）已提交就位；Store 尚不支持 `channels` 过滤、`RECLAIM -> DEAD` 写回与 MP 重试上限。
- Observable outcome: `OutboxDispatcher` 仅按本机 `DeliveryHandlerRegistry.channels()` 领取消息（空注册表零 SQL 短路）；MP 冲突重开受限；超时且已达 `maxAttempts` 的 `PROCESSING` 行在 `claim` 中原子转为 `DEAD`（不加入投递批次）；真实 PostgreSQL（MP 与 JDBC 双存储）集成测试与查询计划索引校验全通过。
- End state: 服务内事务事件核心分发、存储锁控与毒丸保护闭环全部就位；仅余业务状态机缺失定义退避及 README 文档待 Step 4 收口。
- Test-first gate: `Required` — 先更新/增加 `DeliveryHandlerRegistryTest`、`OutboxDispatcherTest`、`OutboxMultiShardStoreTest` 获得预期 RED，再按依赖编译顺序实施生产代码，最后扩展运行 PostgreSQL 集成测试验证真实 DB 行为。
- Manual Checks: `MC-ARCH-001, MC-REUSE-001, MC-DEP-001, MC-NAME-001, MC-VALID-001, MC-LOG-001, MC-BEAN-001, MC-UTIL-001, MC-TIME-001, MC-PATTERN-001, MC-SCOPE-001, MC-TEST-001`
- Literal Rules: `Rule 1`, `Rule 2`, `Rule 4`, `Rule 5`, `Rule 9`, `Rule 10`, `Rule 11`
- Ordered files:

#### File 1 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/delivery/DeliveryHandlerRegistryTest.java`

- Purpose: 固定 `DeliveryHandlerRegistry#channels()` 返回不可变通道集合及空列表返回空集的测试契约。
- Symbols: `shouldExposeImmutableRegisteredChannels`
- Repository evidence: `DeliveryHandlerRegistryTest.java` 已有 `handler(channel)` 辅助方法。
- Dependencies and consumers: 调用 `DeliveryHandlerRegistry#channels()`。
- Why now: 驱动 `DeliveryHandlerRegistry` 增量 API 的 RED 断言。
- Contract/signature changes: 测试断言 `Set<String> channels()` 的内容与不可变性（`UnsupportedOperationException`）。
- Input/output and state mapping: 注册 `["http", "statemachine"]` -> 返回不可变 `Set.of("http", "statemachine")`；空构造返回空集合。
- Error and edge behavior: 试图 `add`/`remove` 返回集合时抛出 `UnsupportedOperationException`。
- Standards impact: `MC-TEST-001`, `MC-SCOPE-001` — 隔离验证注册表契约。
- Literal rule enforcement: `Rule 11` — 维持既有测试位置。
- Required change: 在 `DeliveryHandlerRegistryTest` 增加对 `channels()` 返回结果与不可变性的测试。
- Verification contribution: 与 File 11 配合完成 `DeliveryHandlerRegistryTest` GREEN。
- After this file: 在 File 11 添加 `channels()` 前处于编译/预期 RED。

#### File 2 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/dispatch/OutboxDispatcherTest.java`

- Purpose: 固定 `OutboxDispatcher` 调用 `claimDue` 与 `claimByMessageIds` 时传入本机 `handlerRegistry.channels()` 的测试契约。
- Symbols: `RecordingOutboxStore` 覆写记录传入的 `channels` 集合，新增 `claimPassesRegisteredChannelsToStore`
- Repository evidence: `OutboxDispatcherTest.java` 内部已有 `RecordingOutboxStore` 测试替身记录 `claimDueCount` 与 `claimByMessageIdsCount`。
- Dependencies and consumers: 测试 `OutboxDispatcher#dispatchDue` 与 `#dispatchMessageIds`。
- Why now: 先锁定 Dispatcher 传参契约 RED，后续 File 12 与 File 19 实现后转 GREEN。
- Contract/signature changes: `RecordingOutboxStore` 实现带 `Collection<String> channels` 参数的 `OutboxStore` 新重载方法。
- Input/output and state mapping: 注册单个 `"http"` Handler -> 断言 `store.lastClaimedChannels` 精确等于 `Set.of("http")`；空注册表下传入空集合且不执行无效投递。
- Error and edge behavior: 保证旧 3/4 参 `RecordingOutboxStore` 若只覆写旧签名时的行为兼容（单测分别验证新重载传参）。
- Standards impact: `MC-TEST-001`, `MC-SCOPE-001` — 使用现有内存 Recording 测试替身。
- Literal rule enforcement: `Rule 11` — 保持 `dispatch` 测试包结构。
- Required change: 在 `OutboxDispatcherTest` 的 `RecordingOutboxStore` 中记录新重载接收到的 `channels`，新增用例分别触发 `dispatchDue` 和 `dispatchMessageIds` 并断言传入的通道集合。
- Verification contribution: 运行 `-Dtest=OutboxDispatcherTest` 验证 `REQ-003` 调度侧行为。
- After this file: 在 File 12 与 File 19 就位前处于 RED。

#### File 3 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/store/OutboxMultiShardStoreTest.java`

- Purpose: 固定 MP Store 在连续抢锁冲突下受限于 `maxClaimContentionRetries` 的单测契约。
- Symbols: 新增 `stopsRetryingClaimWhenContentionLimitIsReached`
- Repository evidence: `OutboxMultiShardStoreTest.java` 已使用 Mockito `OutboxMessageRepository` 与 `TransactionTemplate` 构造 `MybatisPlusOutboxStore`。
- Dependencies and consumers: 测试 `MybatisPlusOutboxStore` 带 `maxClaimContentionRetries` 的重载构造器。
- Why now: 在更改 `MybatisPlusOutboxStore` 之前固化循环退让上限 RED。
- Contract/signature changes: 测试构造 `MybatisPlusOutboxStore` 传入 `maxClaimContentionRetries = 2`。
- Input/output and state mapping: 模拟 `selectDueCandidates` 返回 5 条候选，每次 `tryAcquireTransactionLock` 均返回 `false` -> `claimDue` 安全返回空列表，且 `tryAcquireTransactionLock` 调用次数或事务重开次数受限于 `1 + 2 = 3` 轮（不超过 3 轮）。
- Error and edge behavior: 不抛 `OutboxStorageException` 也不无限遍历完 5 条（否则会执行 5 轮）。
- Standards impact: `MC-TEST-001`, `MC-SCOPE-001` — 复用既有 H2/Mock 存储测试类。
- Literal rule enforcement: `Rule 11` — 不新增测试类文件膨胀结构。
- Required change: 在 `OutboxMultiShardStoreTest` 增加模拟持续抢锁冲突的用例，断言事务重开轮数在 `maxClaimContentionRetries=2` 时截断。
- Verification contribution: 运行 `-Dtest=OutboxMultiShardStoreTest` 验证 `REQ-002` 与 `REQ-006`。
- After this file: 在 File 17 实现退让上限前处于编译或行为 RED。

#### File 4 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/delivery/DeliveryHandlerRegistry.java`

- Purpose: 提供只读 `channels()` 方法返回当前实例已注册的投递通道集合。
- Symbols: `DeliveryHandlerRegistry#channels()`
- Repository evidence: `DeliveryHandlerRegistry.java#L36` 已生成 `Collections.unmodifiableMap(registered)`。
- Dependencies and consumers: `OutboxDispatcher` 调用 `handlerRegistry.channels()`。
- Why now: 解除 File 1 编译阻断并供 File 19 使用。
- Contract/signature changes: 新增 `public Set<String> channels()`，返回 `Collections.unmodifiableSet(handlers.keySet())`（或构造期 `Set.copyOf(registered.keySet())`）。
- Input/output and state mapping: 空参 -> 不可变 `Set<String>`。
- Error and edge behavior: 不返回 `null`，并发读绝对线程安全。
- Standards impact: `MC-REUSE-001`, `MC-NAME-001`, `MC-SCOPE-001` — 零新依赖。
- Literal rule enforcement: `Rule 5`, `Rule 11` — 仅用 JDK 原生 `Set` / `Collections`。
- Required change: 在 `DeliveryHandlerRegistry` 中添加 `public Set<String> channels()` 方法，返回不可变通道集合视图。
- Verification contribution: 使 File 1 `DeliveryHandlerRegistryTest` 转 GREEN。
- After this file: `DeliveryHandlerRegistryTest` 可独立运行通过。

#### File 5 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxStore.java`

- Purpose: 扩展 `OutboxStore` SPI 支持传入 `Collection<String> channels` 过滤，同时保持原有 3/4 参签名向后兼容。
- Symbols: `OutboxStore#claimDue(int, String, Duration, Collection<String>)`, `#claimByMessageIds(Collection<String>, int, String, Duration, Collection<String>)`
- Repository evidence: `OutboxStore.java#L27-L63` 已使用 `default` 重载模式扩展 `shardSlot` 参数。
- Dependencies and consumers: `OutboxDispatcher` 调用新签名；`JdbcOutboxStore` 与 `MybatisPlusOutboxStore` 覆写实现；外部 test mock 继承 `default` 无需修改。
- Why now: 统一存储抽象接口必须在具体 DAO/Store 实现前定义。
- Contract/signature changes: 新增上述两个 `default` 重载方法；若外部自定义 Store 未覆写，默认校验 `channels` 非 null 且空集合直接返回 `List.of()`，非空时委托原 3/4 参 `claimDue` / `claimByMessageIds`。
- Input/output and state mapping: `channels` 为空集 -> 返回 `List.of()`；非空 -> `List<OutboxRecord>`（全为 `PROCESSING`）。
- Error and edge behavior: `channels == null` 或元素空白/超 64 字符时抛出 `IllegalArgumentException("OUTBOX_CHANNELS_INVALID")`（在接口契约与 Store 实现中保持一致）。
- Standards impact: `MC-VALID-001`, `MC-SCOPE-001` — 延续 `@Positive`/`@Max(10_000)`/`@NotNull` 约束风格。
- Literal rule enforcement: `Rule 2`, `Rule 11` — 接口入参明确 Jakarta 校验与合法边界。
- Required change: 在 `OutboxStore` 中声明并实现带 `Collection<String> channels` 参数的 `claimDue` 与 `claimByMessageIds` `default` 重载方法。
- Verification contribution: 解除 File 2/File 19 与两套 Store 实现之间的接口编译依赖。
- After this file: 既有调用 `OutboxStore` 的测试双仍可编译。

#### File 6 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/dao/OutboxMessageDAO.java`

- Purpose: 为 MP Mapper 增加带可选 `channels` 过滤的候选查询重载及 `updateReclaimDead` 终态更新方法。
- Symbols: `OutboxMessageDAO#selectDueCandidates(int, Collection<String>)`, `#selectByMessageIdsCandidates(Collection<String>, int, Collection<String>)`, `#updateReclaimDead(...)`
- Repository evidence: `OutboxMessageDAO.java#L43-L48,L89-L98` 已有单参查询及 `updateDead` 方法签名风格。
- Dependencies and consumers: 绑定 `OutboxMessageMapper.xml`，由 `OutboxMessageRepository` 调用。
- Why now: 处于 MP 持久层最底层接口，须先于 XML/Repository/Store 更新。
- Contract/signature changes: 保留原有 `selectDueCandidates(int)` 与 `selectByMessageIdsCandidates(Collection, int)`（可作为 `default` 委托新重载传 `null`），新增带 `@Param("channels") Collection<@NotBlank @Size(max = 64) String> channels` 参数的查询方法；新增 `updateReclaimDead(id, version, updateTime, updateUserId)`。
- Input/output and state mapping: `channels == null` 或空则不拼 `IN`（由上层拦截空集合短路）；`updateReclaimDead` 返受影响行数 `0` 或 `1`。
- Error and edge behavior: 方法级 `@Validated` 校验非法参数（如 `channels` 含空白或超长字符串）。
- Standards impact: `MC-VALID-001`, `MC-TIME-001`, `MC-SCOPE-001` — 沿用 `@EgonColaExplicitTenantScopeMapper` 与 `java.time.Instant`。
- Literal rule enforcement: `Rule 1`, `Rule 2`, `Rule 10`, `Rule 11` — 保持 `*DAO` 命名、Jakarta 方法校验及 `Instant` 类型。
- Required change: 在 `OutboxMessageDAO` 中添加支持 `@Param("channels")` 的候选查询签名与 `updateReclaimDead` 方法。
- Verification contribution: 供 Mapper XML 绑定与 Repository 调用。
- After this file: DAO 接口定义就位。

#### File 7 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/resources/mapper/outbox/OutboxMessageMapper.xml`

- Purpose: 在 `selectDueCandidates` / `selectByMessageIdsCandidates` 支持可选 `AND channel IN (...)`，并实现 `updateReclaimDead` SQL。
- Symbols: `<select id="selectDueCandidates">`, `<select id="selectByMessageIdsCandidates">`, `<update id="updateReclaimDead">`
- Repository evidence: `OutboxMessageMapper.xml#L114-L142,L237-L258` 已有 `<foreach>` 与 `updateDead` SQL 模板及 `<include refid="outboxShardBinding"/>`。
- Dependencies and consumers: 映射 `OutboxMessageDAO`，运行期受 `EgonColaOriginalSqlGuardInterceptor` 与 ShardingSphere SQL 解析器检验。
- Why now: 实现 File 6 声明的 SQL 映射。
- Contract/signature changes: 查询块内增加 MyBatis `<if test="channels != null and !channels.isEmpty()"> AND channel IN <foreach collection="channels" item="channel" open="(" separator="," close=")">#{channel}</foreach> </if>`；新增 `updateReclaimDead` 显式写入 `status = 'DEAD'`、`locked_by = NULL`、`locked_until = NULL`、`last_error_code = 'OUTBOX_RETRY_EXHAUSTED'`、`last_error_message = 'OUTBOX_RECLAIM_EXHAUSTED'`、`completed_at = clock_timestamp()`、`updated_at = clock_timestamp()`、`version = version + 1`，条件严格包含 `id = #{id} AND shard_slot = #{_outboxSlot} AND version = #{version} AND status = 'PROCESSING' AND locked_until < clock_timestamp() AND attempt_count >= max_attempts AND tenant_id = 0 AND deleted_at IS NULL`。
- Input/output and state mapping: 输入绑定 `shard_slot`、参数值；输出受影响行数或 `outboxMessageResultMap`。
- Error and edge behavior: 绝不使用 CTE、`UPDATE FROM` 或 `SKIP LOCKED`；条件不满足影响 `0` 行。
- Standards impact: `MC-TIME-001`, `MC-SCOPE-001` — 全程 UTC `clock_timestamp()` 与零 DDL 变动。
- Literal rule enforcement: `Rule 10`, `Rule 11` — 保持平坦单表路由与技术租户 `0` 谓词。
- Required change: 更新两个候选查询语句的 `WHERE` 条件并追加 `updateReclaimDead` 语句。
- Verification contribution: 由真实 PG 集成测试与 `PostgresqlOutboxQueryPlanIntegrationTest` 验证 SQL 解析、更新与索引。
- After this file: MP Mapper 层完整映射完毕。

#### File 8 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/repository/OutboxMessageRepository.java`

- Purpose: 在 Repository 门面层转发 `channels` 过滤查询与 `updateReclaimDead` 并保持单行影响校验。
- Symbols: `OutboxMessageRepository#selectDueCandidates(int, Collection<String>)`, `#selectByMessageIdsCandidates(Collection<String>, int, Collection<String>)`, `#updateReclaimDead(...)`
- Repository evidence: `OutboxMessageRepository.java#L60-L82,L133-L145,L196-L203` 已有 `requireBatchLimit`、`requireCollectionBound` 与 `requireAtMostOne`。
- Dependencies and consumers: 包装 `OutboxMessageDAO`，供 `MybatisPlusOutboxStore` 调用。
- Why now: 处于 DAO 与 Store 之间（见 `PLAN-CLAR-001`）。
- Contract/signature changes: 保留原有 `selectDueCandidates(int)` 与 `selectByMessageIdsCandidates(Collection, int)` 委托 `null` 过滤，新增带 `channels` 集合的重载（校验集合元素非空白、长度 `<= 64` 且集合非超大）及 `updateReclaimDead` 调用 `requireAtMostOne("updateReclaimDead", ...)`。
- Input/output and state mapping: 参数校验后透传 DAO；返回候选列表或 `0/1` 受影响行数。
- Error and edge behavior: 非法 `channels` 元素或超过批次集合上限抛 `IllegalArgumentException`；影响超过 `1` 行抛 `IllegalStateException`。
- Standards impact: `MC-VALID-001`, `MC-REUSE-001`, `MC-SCOPE-001` — 复用既有 `requireBatchLimit`/`requireAtMostOne` 保护。
- Literal rule enforcement: `Rule 1`, `Rule 2`, `Rule 10`, `Rule 11` — 延续 `*Repository` 命名与 Jakarta 参数校验。
- Required change: 在 `OutboxMessageRepository` 中新增带 `channels` 过滤参数的候选查询重载及 `updateReclaimDead` 包装方法。
- Verification contribution: 供 File 10 `MybatisPlusOutboxStore` 调用并通过 `OutboxMultiShardStoreTest`。
- After this file: MP 持久门面完整可用。

#### File 9 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/JdbcOutboxStore.java`

- Purpose: 在 `JdbcOutboxStore` 中实现按 `channels` 过滤的 `claimDue`/`claimByMessageIds`、空集合短路与 `RECLAIM -> DEAD` 原子终态写回。
- Symbols: `JdbcOutboxStore`、`#claimDue(..., Collection<String>)`、`#claimByMessageIds(..., Collection<String>)`、`#claim(...)`
- Repository evidence: `JdbcOutboxStore.java#L110-L161` 已有动态拼接 `idFilter` 占位符与 `rowMapper` 查询逻辑。
- Dependencies and consumers: 实现 `OutboxStore`，被 `OutboxDispatcher` 及 JDBC 集成测试调用。
- Why now: 与 `MybatisPlusOutboxStore` 并列实现新接口契约。
- Contract/signature changes: 覆写 4 参 `claimDue` 与 5 参 `claimByMessageIds`；原 3/4 参方法保留并内部传 `null`（表示不限制通道，向后兼容既有直接调用方）。
- Input/output and state mapping: 若传入 `channels` 非 null 且空集合，直接返回 `List.of()`；若非空则校验去重并拼接 `AND channel IN (?, ...)`；循环处理候选时评估 `CLAIM`/`RECLAIM`，若 `target == OutboxStatus.DEAD` 则执行单行条件更新将其置为 `DEAD`（`locked_by=NULL, locked_until=NULL, last_error_code='OUTBOX_RETRY_EXHAUSTED', last_error_message='OUTBOX_RECLAIM_EXHAUSTED', completed_at=clock_timestamp()`），影响 1 行时记 `log.warn`，不加入返回列表；若 `target == OutboxStatus.PROCESSING` 则按既有逻辑更新租约与 `attempt_count+1` 并加入返回列表。
- Error and edge behavior: `channels` 中含空白或超 64 字符元素抛 `IllegalArgumentException("OUTBOX_CHANNELS_INVALID")`；在 `RECLAIM -> DEAD` 时并发已被其他实例修改（`changed == 0`）则安全跳过。
- Standards impact: `MC-LOG-001`, `MC-VALID-001`, `MC-UTIL-001`, `MC-TIME-001`, `MC-SCOPE-001` — 类增加 `@Slf4j`，只输出 `id`/`messageId`/`channel`/`attemptCount` 安全字段，全用 `java.time` 与 `clock_timestamp()`。
- Literal rule enforcement: `Rule 2`, `Rule 4`, `Rule 5`, `Rule 10`, `Rule 11` — 使用 `@Slf4j` 与合法通道参数校验，保留现有构造器兼容 `OutboxJdbcAutoConfiguration`。
- Required change: 为 `JdbcOutboxStore` 添加 `@Slf4j`，扩展 `claim` 支持可选 `channels` SQL 过滤与 `target == DEAD` 分支的原子终态更新。
- Complex logic (pseudocode):
  - 若 `channels != null`：校验并去重；若为空集合直接返回 `List.of()`，否则生成 `AND channel IN (...)` 占位符及参数；
  - 对每个 `FOR UPDATE SKIP LOCKED` 锁定的候选执行 `lifecycle.evaluate(status, signal, attemptCount, maxAttempts)`；
  - 若 `signal == RECLAIM` 且 `target == DEAD`：执行 `UPDATE ... SET status='DEAD', locked_by=NULL, locked_until=NULL, last_error_code='OUTBOX_RETRY_EXHAUSTED', last_error_message='OUTBOX_RECLAIM_EXHAUSTED', completed_at=clock_timestamp(), updated_at=clock_timestamp() WHERE id=? AND status='PROCESSING' AND locked_until < clock_timestamp() AND attempt_count >= max_attempts`；更新成功记录 `log.warn` 并 `continue` 不返回；
  - 否则照旧执行 `PROCESSING` 租约抢占 `UPDATE` 并重载返回。
- Verification contribution: 使 JDBC 存储下 PG 恢复集成测试（`TEST-005`, `TEST-009`）通过。
- After this file: JDBC 存储完整满足 `REQ-003`、`REQ-005` 与 `REQ-006`。

#### File 10 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/MybatisPlusOutboxStore.java`

- Purpose: 在 `MybatisPlusOutboxStore` 中实现按 `channels` 过滤的 `claimDue`/`claimByMessageIds`、单批次冲突重开事务上限控制及 `RECLAIM -> DEAD` 原子落库。
- Symbols: `MybatisPlusOutboxStore` 新重载构造器（接收 `int maxClaimContentionRetries`）、`#claimDue(..., Collection<String>)`、`#claimByMessageIds(..., Collection<String>)`、`#inWorkerTransactionSkippingLocked(...)`、`#claim(...)`
- Repository evidence: `MybatisPlusOutboxStore.java#L76-L97,L142-L176,L314-L409` 已有 `@Slf4j` 与 `inWorkerTransactionSkippingLocked` 循环。
- Dependencies and consumers: 依赖 `OutboxMessageRepository`, `OutboxLifecycleService`, `TransactionTemplate`, `OutboxShardRouting`；被 `OutboxMybatisPlusAutoConfiguration` 与 `OutboxDispatcher` 调用。
- Why now: 依赖 File 8 Repository、Step 1 配置上限与 Step 2 状态机语义，必须在此位置落地（见 `PLAN-CLAR-002`）。
- Contract/signature changes: 保留现有 8/9 参构造器（默认 `maxClaimContentionRetries = 8`），新增 10 参构造器接受 `int maxClaimContentionRetries`（`>= 1`）；覆写 4 参 `claimDue` 与 5 参 `claimByMessageIds`，旧 3/4 参方法内部传 `null` 兼容既有调用方。
- Input/output and state mapping: 传入 `channels` 若空集则不开启事务直接返回 `List.of()`；非空先去重校验；在 `inWorkerTransactionSkippingLocked` 中累计锁冲突次数 `retries`，当 `retries >= maxClaimContentionRetries` 时跳出循环直接返回 `empty`（不再无限重开事务）；处理候选时若 `signal == RECLAIM` 且 `target == OutboxStatus.DEAD`，调用 `repository.updateReclaimDead(candidate.getId(), candidate.getVersion(), clock.instant(), TECHNICAL_USER_ID)` 置终态，成功记 `log.warn` 后跳过，不重载也不加入 `claimed` 列表。
- Error and edge behavior: 冲突计数只针对 `contention.matches(failure)`（即 `55P03` 或 advisory lock 冲突）；其他数据库异常照旧包装抛出 `OutboxStorageException`；在第一个候选撞锁回滚前本批没有任何记录被提交，跳出后安全返回空结果交由他节点或下一抖动周期处理。
- Standards impact: `MC-VALID-001`, `MC-LOG-001`, `MC-TIME-001`, `MC-SCOPE-001` — 沿用 `@Slf4j` 与 `clock.instant()` UTC 时钟，校验参数范围。
- Literal rule enforcement: `Rule 2`, `Rule 4`, `Rule 5`, `Rule 10`, `Rule 11` — 保持既有注入字段与 `@Slf4j`，仅用 JDK 集合与 `java.time`。
- Required change: 在 `MybatisPlusOutboxStore` 中支持 `channels` 过滤、限制冲突重开事务上限并在 `claim` 中落实 `RECLAIM -> DEAD` 原子写回。
- Complex logic (pseudocode):
  - 若 `channels != null`：去重并校验每项非空且 `<= 64`；若为空集直接返回 `List.of()`；
  - 遍历 `rotatedSlots()` 查询候选时透传 `channels`；
  - `inWorkerTransactionSkippingLocked` 维护 `int retries = 0`；捕获匹配的 `LockContention` 后剔除冲突行，若 `++retries > maxClaimContentionRetries` 直接返回 `empty` 结束本轮；
  - `claim` 循环对候选求值 `target`：若 `signal == RECLAIM && target == DEAD`，先获取 `acquireCandidate`，再执行 `repository.updateReclaimDead(...)`，影响 1 行则记 `log.warn` 并 `continue` 不加入 `claimed` 列表；否则走现有 `updateClaim` 抢占流程。
- Verification contribution: 使 File 3 `OutboxMultiShardStoreTest` 转 GREEN，并支撑 PG 恢复集成测试。
- After this file: 双存储引擎核心抢占与熔断语义完全统一。

#### File 11 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/OutboxMybatisPlusAutoConfiguration.java`

- Purpose: 将 `TransactionalOutboxProperties#getPolling().getMaxClaimContentionRetries()` 注入到 `MybatisPlusOutboxStore` Bean。
- Symbols: `OutboxMybatisPlusAutoConfiguration#outboxStore(...)`
- Repository evidence: `OutboxMybatisPlusAutoConfiguration.java#L172-L198` 已装配 `outboxStore` Bean 并通过 `@Qualifier` 注入所有依赖。
- Dependencies and consumers: 使用 `@Qualifier("outboxStateMachineOutboxProperties") TransactionalOutboxProperties`（或直接注入唯一 `TransactionalOutboxProperties` Bean，保留参数 `@Qualifier` 契约，注意查验当 `OutboxStateMachineAutoConfiguration` 在后/前加载时别名可用性：因 `TransactionalOutboxAutoConfiguration` 创建 `TransactionalOutboxProperties` 且在 `OutboxMybatisPlusAutoConfiguration` 使用 `ObjectProvider<TransactionalOutboxProperties>` 或直接注入类型唯一 Bean 并加安全默认回退，可避免测试上下文未引入 `OutboxStateMachineAutoConfiguration` 时装配失败！）。
- Why now: File 10 已提供新构造器，现在完成配置向 Bean 的端到端接线。
- Contract/signature changes: `@Bean("outboxStore") outboxStore(...)` 新增 `ObjectProvider<TransactionalOutboxProperties>` 参数（或 `@Qualifier` 注入），调用 `MybatisPlusOutboxStore` 10 参构造器传入 `getPolling().getMaxClaimContentionRetries()`（不可得时回退 `8`）。
- Input/output and state mapping: 配置值 -> `MybatisPlusOutboxStore` 运行期阈值。
- Error and edge behavior: 使用 `ObjectProvider<TransactionalOutboxProperties>`（并在存在时校验正数）可同时兼容独立测试只导入 `OutboxMybatisPlusAutoConfiguration` 的轻量化上下文。
- Standards impact: `MC-BEAN-001`, `MC-CONFIG-001`, `MC-SCOPE-001` — 维持显式 Bean 名 `"outboxStore"` 与安全装配。
- Literal rule enforcement: `Rule 4`, `Rule 7`, `Rule 11` — 不改变原有 `@Bean("outboxStore")` 名称及既有 `@Qualifier` 注解。
- Required change: 在 `OutboxMybatisPlusAutoConfiguration#outboxStore` 中注入 `TransactionalOutboxProperties`（通过 `ObjectProvider` 兼容精简测试上下文）并将其 `polling.maxClaimContentionRetries` 传入 `MybatisPlusOutboxStore` 构造器。
- Verification contribution: 保证 `OutboxMybatisPlusAutoConfigurationTest` 及集成测试上下文装配通过。
- After this file: MP 配置项直达运行期 Store。

#### File 12 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/dispatch/OutboxDispatcher.java`

- Purpose: 在调度抢占时向 `OutboxStore` 传递 `handlerRegistry.channels()`。
- Symbols: `OutboxDispatcher#dispatchDue()`, `#dispatchMessageIds(Collection<String>)`
- Repository evidence: `OutboxDispatcher.java#L164-L181` 当前调用 3 参 `store.claimDue` 与 4 参 `store.claimByMessageIds`。
- Dependencies and consumers: 依赖 File 4 `DeliveryHandlerRegistry#channels()` 与 File 5 `OutboxStore` 新签名。
- Why now: 完成从配置、Dispatcher 到 Store 的能力过滤闭环。
- Contract/signature changes: 无公开方法签名变更。
- Input/output and state mapping: `dispatchDue` 调用 `store.claimDue(limit, owner, lease, handlerRegistry.channels())`；`dispatchMessageIds` 调用 `store.claimByMessageIds(selectedIds, limit, owner, lease, handlerRegistry.channels())`。
- Error and edge behavior: 本机未注册任何 `DeliveryHandler` 时 `channels()` 为空集合，Store 立即短路不发 SQL，避免消费与误判 `HANDLER_MISSING`。
- Standards impact: `MC-BEAN-001`, `MC-SCOPE-001`, `MC-TEST-001` — 沿用 `@Slf4j` 与 `@RequiredArgsConstructor` 字段。
- Literal rule enforcement: `Rule 4`, `Rule 11` — 不增删依赖字段，复用既有 `@Qualifier("deliveryHandlerRegistry")`。
- Required change: 更新 `OutboxDispatcher#dispatchDue` 与 `#dispatchMessageIds` 中的 `store.claimDue` / `store.claimByMessageIds` 调用以传入 `handlerRegistry.channels()`。
- Verification contribution: 使 File 2 `OutboxDispatcherTest` 转 GREEN。
- After this file: Step 3 全部单元测试（File 1–3）达到 GREEN。

#### File 13 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/PostgresqlOutboxRecoveryIntegrationTest.java`

- Purpose: 在真实 PostgreSQL（同时验证 MP 与 JDBC 存储）中证实按 `channel` 过滤防误领及 `RECLAIM` 满额熔断直达 `DEAD`。
- Symbols: 新增 `shouldClaimOnlyRegisteredChannelsWithoutTouchingUnsupportedMessages`、`shouldMarkExpiredProcessingDeadWhenReclaimBudgetIsExhausted`
- Repository evidence: `PostgresqlOutboxRecoveryIntegrationTest.java` 已扩展 `PostgresqlOutboxTestSupport`（基于真实 Testcontainers PostgreSQL + ShardingSphere-JDBC）。
- Dependencies and consumers: 覆盖 `MybatisPlusOutboxStore` 与 `JdbcOutboxStore` 两个真实存储实例。
- Why now: 单元测试绿色后，需要真实 PostgreSQL 事务、行锁与 `clock_timestamp()` 验证 Step 3 闭环。
- Contract/signature changes: 无生产签名变更。
- Input/output and state mapping: (1) 入库 `channel="http"` 与 `channel="statemachine"` 两条记录，仅传入 `Set.of("statemachine")` 调 `claimDue` 断言只领走后者、`http` 保持 `PENDING`，且传入 `Set.of()` 返回空列表；(2) 插入已过租约且 `attempt_count = max_attempts` 的 `PROCESSING` 行，调 `claimDue` 断言返回空列表且数据库中行已置为 `status='DEAD'`、`locked_by IS NULL`、`last_error_code='OUTBOX_RETRY_EXHAUSTED'`、`completed_at IS NOT NULL`（MP 与 JDBC 均验证）。
- Error and edge behavior: 验证并发已被先一步修改时安全不报错。
- Standards impact: `MC-TIME-001`, `MC-TEST-001`, `MC-SCOPE-001` — 真 PG `timestamptz(6)` 与锁实测。
- Literal rule enforcement: `Rule 10`, `Rule 11` — 验证数据库 `clock_timestamp()` UTC 时间写入与 `integration` 测试规范。
- Required change: 在 `PostgresqlOutboxRecoveryIntegrationTest` 中增加上述针对 MP Store 与 JDBC Store 的通道过滤和 `RECLAIM -> DEAD` 集成测试用例。
- Verification contribution: 为 `REQ-003` 与 `REQ-005` 提供真 PG 运行时证明。
- After this file: Step 3 恢复与防误杀语义获真库实证。

#### File 14 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/PostgresqlOutboxQueryPlanIntegrationTest.java`

- Purpose: 证实追加 `AND channel IN (...)` 过滤条件的候选查询与接管查询仍命中 PostgreSQL 局部索引 `idx_outbox_claim` 与 `idx_outbox_reclaim`。
- Symbols: 扩展 `shouldExposeRequiredSchemaAndUseClaimIndexes`（或新增 `shouldUseClaimIndexesWhenFilteringByChannels`）
- Repository evidence: `PostgresqlOutboxQueryPlanIntegrationTest.java#L94-L112` 使用 `EXPLAIN (FORMAT JSON)` 检查执行计划包含索引名。
- Dependencies and consumers: 验证真实 PG 表索引与 Mapper 查询形兼容。
- Why now: 证明 `REQ-003` 不引入全表扫描退化（`REQ-006`）。
- Contract/signature changes: 无。
- Input/output and state mapping: 在 1,000 行测试样本下分别对带 `AND channel IN ('statemachine')` 的 `claim` 与 `reclaim` 查询执行 `EXPLAIN (FORMAT JSON)`，断言输出包含 `idx_outbox_claim` 与 `idx_outbox_reclaim`。
- Error and edge behavior: 保证原有索引断言继续通过。
- Standards impact: `MC-TEST-001`, `MC-SCOPE-001` — 真 PG 计划实证，不以静态推演替代。
- Literal rule enforcement: `Rule 11` — 沿用既有 Failsafe 查询计划测试文件。
- Required change: 在 `PostgresqlOutboxQueryPlanIntegrationTest` 中增加带 `channel IN (...)` 过滤条件的 `EXPLAIN (FORMAT JSON)` 索引使用断言。
- Verification contribution: 关闭 `RISK-003` 查询计划退化风险。
- After this file: Step 3 单测、契约与真实 PG 集成测试全部 GREEN。

- Validation working directory: `/Users/mario/SelfProject/Stellar-Enclosure/Stellar-Enclosure-Components`
- Verification command: 先执行单测 `mvn -pl stellar-enclosure-component-transactional-outbox-starter -Dtest=DeliveryHandlerRegistryTest,OutboxDispatcherTest,OutboxMultiShardStoreTest,OutboxMybatisPlusAutoConfigurationTest,OutboxJdbcAutoConfigurationTest test`，再执行 PG 集成测试 `EGON_OUTBOX_TEST_POSTGRES_ENABLED=true mvn -pl stellar-enclosure-component-transactional-outbox-starter -Dtest=__skip_surefire__ -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=PostgresqlOutboxRecoveryIntegrationTest,PostgresqlOutboxQueryPlanIntegrationTest,PostgresqlOutboxConcurrencyIntegrationTest,JdbcOutboxPostgresqlIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false verify`
- Expected result: 两次命令均 Exit 0，所有指定单测与 Failsafe PG 集成测试全量通过，0 skipped/0 failures。
- Failure returns to: 单测失败回对应 File 4–12；SQL 解析/索引失败回 File 6–10（检查 `EgonColaOriginalSqlGuardInterceptor` 与 `EXPLAIN` 计划）。
- Completion criteria: `REQ-002`, `REQ-003`, `REQ-005`, `REQ-006`（Store 兼容与查询计划）获单元与真实 PG 集成证据。
- Rollback: `git checkout --` 仅回退 Step 3 的 14 个路径。
- Commit paths: `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/delivery/DeliveryHandlerRegistryTest.java`, `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/dispatch/OutboxDispatcherTest.java`, `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/store/OutboxMultiShardStoreTest.java`, `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/delivery/DeliveryHandlerRegistry.java`, `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxStore.java`, `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/dao/OutboxMessageDAO.java`, `stellar-enclosure-component-transactional-outbox-starter/src/main/resources/mapper/outbox/OutboxMessageMapper.xml`, `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/repository/OutboxMessageRepository.java`, `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/JdbcOutboxStore.java`, `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/MybatisPlusOutboxStore.java`, `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/OutboxMybatisPlusAutoConfiguration.java`, `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/dispatch/OutboxDispatcher.java`, `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/PostgresqlOutboxRecoveryIntegrationTest.java`, `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/PostgresqlOutboxQueryPlanIntegrationTest.java`
- Commit: `feat(outbox): filter claim by registered channels and mark exhausted reclaim dead`

---

### Step 4 — 实现业务状态机滚动升级缺失定义退避、更新双语文档与治理测试

- Requirements: `REQ-004`, `REQ-006`（README 与治理门禁收口）
- Dependencies: `Step 3`
- Baseline state: Step 1–3 已提交并验证通过；`BusinessStateMachineDeliveryHandler` 与 `BusinessStateMachineService` 对缺失定义仍一律返 `PERMANENT_FAILURE`。
- Observable outcome: 默认 `state-machine.business.retry-on-missing-definition=true` 时，老版本实例领到新版本 `destination` 事件后返回 `RETRYABLE_FAILURE` 退避待新节点消费成功；设 `false` 时恢复立即永久失败；中英文 README 与 `ComponentContractGovernanceTest` 及全模块 Surefire/Failsafe 回归全绿。
- End state: 本规格全部需求 `REQ-001`–`REQ-006` 实施闭环完毕。
- Test-first gate: `Required` — 先更新 `BusinessStateMachineDeliveryHandlerTest` 与 `BusinessStateMachineServiceTest` 断言缺失定义默认返 `RETRYABLE_FAILURE`（此时必然返 `PERMANENT_FAILURE` 呈现 RED）。
- Manual Checks: `MC-ARCH-001, MC-REUSE-001, MC-DEP-001, MC-NAME-001, MC-VALID-001, MC-LOG-001, MC-BEAN-001, MC-CONFIG-001, MC-PATTERN-001, MC-SCOPE-001, MC-TEST-001, MC-BLOCKER-001`
- Literal Rules: `Rule 1`, `Rule 2`, `Rule 4`, `Rule 7`, `Rule 9`, `Rule 11`
- Ordered files:

#### File 1 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/delivery/statemachine/BusinessStateMachineDeliveryHandlerTest.java`

- Purpose: 固定 `BusinessStateMachineDeliveryHandler` 在缺失定义时的可重试/永久失败双模式及 `validateDestination` 强拒绝契约。
- Symbols: 更新缺失定义用例以验证 `retryOnMissingDefinition=true` 返 `RETRYABLE_FAILURE`、`false` 返 `PERMANENT_FAILURE`、`validateDestination` 仍抛 `IllegalArgumentException`
- Repository evidence: `BusinessStateMachineDeliveryHandlerTest.java` 已有 `handler(...)` 辅助构造函数。
- Dependencies and consumers: 调用 `BusinessStateMachineDeliveryHandler` 4 参或新 5 参构造器。
- Why now: 作为 Step 4 的 RED 测试起点。
- Contract/signature changes: 测试传入 `OutboxStateMachineProperties` 控制开关。
- Input/output and state mapping: 默认（`true`）遇未知 `destination` -> `DeliveryResult.Kind.RETRYABLE_FAILURE` + code `OUTBOX_SM_DEFINITION_MISSING`；显式 `false` -> `DeliveryResult.Kind.PERMANENT_FAILURE`；调用 `validateDestination("unknown:1")` -> 两种开关下均抛 `IllegalArgumentException`。
- Error and edge behavior: 其他信封格式/租户不匹配校验继续保持 `PERMANENT_FAILURE`。
- Standards impact: `MC-CONFIG-001`, `MC-TEST-001`, `MC-SCOPE-001` — 验证开关双向语义与不变边界。
- Literal rule enforcement: `Rule 7`, `Rule 11` — 校验配置开关效果并保持包位置不变。
- Required change: 在 `BusinessStateMachineDeliveryHandlerTest` 中增加/更新测试覆盖 `retryOnMissingDefinition` 为 `true` 与 `false` 两种情形及 `validateDestination` 不变性。
- Verification contribution: 运行 `-Dtest=BusinessStateMachineDeliveryHandlerTest` 验证 Handler 契约。
- After this file: 在 File 3–4 实现前处于 RED。

#### File 2 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/statemachine/BusinessStateMachineServiceTest.java`

- Purpose: 固定 `BusinessStateMachineService#process` 在缺失 `definitions` 项时依据 `retryOnMissingDefinition` 返回对应结果，并验证存在 `definition` 但数据库聚合 `definitionVersion` 不匹配时仍保持永久失败。
- Symbols: 更新缺失定义用例与版本不匹配用例
- Repository evidence: `BusinessStateMachineServiceTest.java` 已直接向 `BusinessStateMachineService` 传入 `OutboxStateMachineProperties`。
- Dependencies and consumers: 测试 `BusinessStateMachineService#process`。
- Why now: 同步锁定 Service 层 RED 行为防止 Handler 与 Service 语义割裂。
- Contract/signature changes: 测试配置 `properties.getBusiness().setRetryOnMissingDefinition(...)`。
- Input/output and state mapping: `definitions.get(dest) == null` 时依据开关返 `RETRYABLE_FAILURE` / `PERMANENT_FAILURE`（且不调用 `contextExecutor` / 开启事务）；`definitions.get(dest) != null` 但 `snapshot.getDefinitionVersion()` 不等于 `event.getDefinitionVersion()` 时始终返 `PERMANENT_FAILURE`。
- Error and edge behavior: 确保老版本不会错误进入 `TransactionTemplate`。
- Standards impact: `MC-CONFIG-001`, `MC-TEST-001`, `MC-SCOPE-001` — 精确隔离两种不同的 `OUTBOX_SM_DEFINITION_MISSING` 来源。
- Literal rule enforcement: `Rule 7`, `Rule 11` — 保护既有聚合版本冲突语义。
- Required change: 在 `BusinessStateMachineServiceTest` 中补充 `retryOnMissingDefinition=true/false` 断言及聚合版本不匹配保持 `PERMANENT_FAILURE` 的回归断言。
- Verification contribution: 运行 `-Dtest=BusinessStateMachineServiceTest` 证明 Service 层行为。
- After this file: 在 File 3 & File 5 实现前处于 RED。

#### File 3 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/OutboxStateMachineProperties.java`

- Purpose: 在 `BusinessProperties` 新增 `retryOnMissingDefinition` 布尔配置（默认 `true`）。
- Symbols: `OutboxStateMachineProperties.BusinessProperties#retryOnMissingDefinition`
- Repository evidence: `OutboxStateMachineProperties.java#L52-L70` 使用 Lombok `@Data`, `@Builder`, `@Accessors(chain = true)` 与 `@Builder.Default`。
- Dependencies and consumers: 被 `BusinessStateMachineDeliveryHandler`、`BusinessStateMachineService` 与 `OutboxBusinessStateMachineAutoConfiguration` 读取。
- Why now: 为 File 4、File 5 与测试提供配置属性字段。
- Contract/signature changes: `BusinessProperties` 新增 `@Builder.Default private boolean retryOnMissingDefinition = true;`。
- Input/output and state mapping: 绑定配置键 `egon.cola.component.transactional-outbox.state-machine.business.retry-on-missing-definition`，默认 `true`。
- Error and edge behavior: 布尔类型自动由 Spring Boot Binder 解析，非法字符串在配置绑定期被拒绝。
- Standards impact: `MC-MODEL-001`, `MC-CONFIG-001`, `MC-SCOPE-001` — 延续现有 Lombok `@Data`/`@Builder.Default` 注解基线。
- Literal rule enforcement: `Rule 1`, `Rule 7`, `Rule 11` — 沿用 `*Properties` 命名并保持 `ignoreUnknownFields = false` 校验一致。
- Required change: 在 `OutboxStateMachineProperties.BusinessProperties` 中新增 `@Builder.Default private boolean retryOnMissingDefinition = true;`。
- Verification contribution: 解锁 File 4、File 5 与测试编译。
- After this file: 配置类携带默认值 `true` 就位。

#### File 4 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/delivery/statemachine/BusinessStateMachineDeliveryHandler.java`

- Purpose: 在 `deliver()` 中按 `OutboxStateMachineProperties.getBusiness().isRetryOnMissingDefinition()` 决定缺失定义时返回可重试或永久失败，保持 `validateDestination` 严格拒绝与 4 参构造器向后兼容。
- Symbols: `BusinessStateMachineDeliveryHandler` 新 5 参构造器、`#deliver(DeliveryContext)`
- Repository evidence: `BusinessStateMachineDeliveryHandler.java#L28-L85` 当前使用 `@RequiredArgsConstructor`（4 个 `final` 字段）。
- Dependencies and consumers: `OutboxBusinessStateMachineAutoConfiguration`、单元测试及 `OutboxDispatcher`。
- Why now: 解决 File 1 RED。
- Contract/signature changes: 新增 `@Qualifier("outboxStateMachineProperties") OutboxStateMachineProperties properties`（或保留原 4 参构造器调用 5 参构造器传入 `new OutboxStateMachineProperties()` 见 `PLAN-CLAR-003`；若采用显式 4 参 + 5 参构造器，仅在 5 参构造器或 AutoConfiguration 注入，需注意勿加无意义 `@RequiredArgsConstructor` 冲突）。
- Input/output and state mapping: `definitions.get(destination) == null` 且 `properties.getBusiness().isRetryOnMissingDefinition()` 为 `true` -> `log.warn` 并返 `DeliveryResult.retryableFailure("OUTBOX_SM_DEFINITION_MISSING", "Business state-machine definition is not registered on this node")`；为 `false` -> `DeliveryResult.permanentFailure(...)`。
- Error and edge behavior: `validateDestination` 与信封/Header/租户异常路径完全保持不变。
- Standards impact: `MC-LOG-001`, `MC-BEAN-001`, `MC-CONFIG-001`, `MC-SCOPE-001` — 保留 `@Slf4j` 与 `@Validated`，仅记录 `destination` 安全字符串（见 `PLAN-CLAR-003`）。
- Literal rule enforcement: `Rule 2`, `Rule 4`, `Rule 7`, `Rule 11` — 保持 `@Validated` 与安全日志；4 参兼容构造器委托 5 参构造器，由 `OutboxBusinessStateMachineAutoConfiguration` 显式按 `@Qualifier` 注入 Bean（见 `PLAN-CLAR-003`）。
- Required change: 为 `BusinessStateMachineDeliveryHandler` 增加接受 `OutboxStateMachineProperties` 的 5 参构造器（保留 4 参默认兼容构造器），在 `deliver()` 缺失定义分支按开关返回 `retryableFailure` 或 `permanentFailure`。
- Verification contribution: 使 File 1 `BusinessStateMachineDeliveryHandlerTest` 转 GREEN。
- After this file: Handler 行为达到预期，待 Service 与自动配置联动。

#### File 5 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/statemachine/BusinessStateMachineService.java`

- Purpose: 使 `BusinessStateMachineService#process` 与 Handler 的缺失定义退避策略保持完全一致。
- Symbols: `BusinessStateMachineService#process`
- Repository evidence: `BusinessStateMachineService.java#L56-L57,L89-L92` 已注入 `@Qualifier("outboxStateMachineProperties") private final OutboxStateMachineProperties properties;`。
- Dependencies and consumers: `BusinessStateMachineDeliveryHandler` 及 `BusinessStateMachineServiceTest`。
- Why now: 防止绕过 Handler 直接调 `Service#process` 时行为不一致，并使 File 2 转 GREEN。
- Contract/signature changes: 无签名与构造器变化。
- Input/output and state mapping: `definitions.get(destination) == null` 时按 `properties.getBusiness().isRetryOnMissingDefinition()` 记 `log.warn` 返回 `retryableFailure("OUTBOX_SM_DEFINITION_MISSING", ...)` 或 `permanentFailure(...)`；`processInBusinessTransaction` 内的 `snapshot.getDefinitionVersion()` 版本校验仍保持 `permanentFailure`。
- Error and edge behavior: 缺失定义分支在 `contextExecutor` 与 `transactionTemplate` 之前直接返回，零事务与上下文开销。
- Standards impact: `MC-LOG-001`, `MC-CONFIG-001`, `MC-SCOPE-001` — 复用已注入的 `properties` 与 `@Slf4j`。
- Literal rule enforcement: `Rule 4`, `Rule 7`, `Rule 11` — 保持既有 `@Slf4j`、`@Validated` 与 `@RequiredArgsConstructor` 结构不变。
- Required change: 在 `BusinessStateMachineService#process` 查找 `definitions.get(destination)` 为空时按 `properties.getBusiness().isRetryOnMissingDefinition()` 返回 `retryableFailure` 或 `permanentFailure`。
- Verification contribution: 使 File 2 `BusinessStateMachineServiceTest` 转 GREEN。
- After this file: Handler 与 Service 行为完全统一。

#### File 6 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/OutboxBusinessStateMachineAutoConfiguration.java`

- Purpose: 将容器内的 `outboxStateMachineProperties` 装配到 `outboxBusinessStateMachineDeliveryHandler` Bean。
- Symbols: `OutboxBusinessStateMachineAutoConfiguration#outboxBusinessStateMachineDeliveryHandler(...)`
- Repository evidence: `OutboxBusinessStateMachineAutoConfiguration.java#L140-L150` 已装配该 Bean。
- Dependencies and consumers: 注入 `@Qualifier("outboxStateMachineProperties") OutboxStateMachineProperties`。
- Why now: 使 Spring Boot 配置文件中的开关实际注入运行期 Handler Bean。
- Contract/signature changes: `@Bean` 方法增加 `OutboxStateMachineProperties` 参数并调用 File 4 的 5 参构造器。
- Input/output and state mapping: YAML 属性 -> Handler 实例行为。
- Error and edge behavior: 沿用 `@ConditionalOnMissingBean(name = "outboxBusinessStateMachineDeliveryHandler")`。
- Standards impact: `MC-BEAN-001`, `MC-CONFIG-001`, `MC-SCOPE-001` — 显式 `@Bean` 名与 `@Qualifier` 注解。
- Literal rule enforcement: `Rule 4`, `Rule 7`, `Rule 11` — 参数显式标注 `@Qualifier("outboxStateMachineProperties")`。
- Required change: 更新 `outboxBusinessStateMachineDeliveryHandler` `@Bean` 工厂方法，注入 `outboxStateMachineProperties` 并传入 `BusinessStateMachineDeliveryHandler` 新 5 参构造器。
- Verification contribution: 保障 `OutboxBusinessStateMachineAutoConfigurationTest` 及真实上下文配置绑定生效。
- After this file: 生产代码端到端装配完成。

#### File 7 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/BusinessStateMachineIntegrationTest.java`

- Purpose: 在真实 PostgreSQL 中端到端模拟滚动升级期“老 Pod 误领新版本状态机事件安全退避、新版 Pod 上线接管消费成功”的场景。
- Symbols: 新增 `shouldDeferMissingDefinitionToRetryWaitUntilUpdatedNodeConsumesIt`
- Repository evidence: `BusinessStateMachineIntegrationTest.java` 已具备真实 `StateMachineFactory`、`OutboxDispatcher`、`JdbcBusinessStateMachineRepository` 与 PG 容器环境。
- Dependencies and consumers: 测试真实 `OutboxDispatcher` + `BusinessStateMachineDeliveryHandler` + `BusinessStateMachineService` + PostgreSQL 存储协作。
- Why now: 在真实 PG 与真实状态机执行链路下证明 `REQ-004` 闭环。
- Contract/signature changes: 无。
- Input/output and state mapping: 构造新版本（`order-payment:2`）已落库消息，先由仅含 `order-payment:1` 的老 Dispatcher 轮询投递 -> 断言消息进入 `RETRY_WAIT` 且业务状态未受损；随后清除退避时间戳（模拟冷却到期）并由包含 `order-payment:2` 的新 Dispatcher 轮询 -> 断言消息转为 `SUCCEEDED`、业务聚合与 Receipt 正确落库。
- Error and edge behavior: 确认老节点不会误将消息标为 `DEAD`。
- Standards impact: `MC-TEST-001`, `MC-SCOPE-001` — 覆盖完整 PG 事务与退避重放闭环。
- Literal rule enforcement: `Rule 11` — 保留 `integration` 包约定。
- Required change: 在 `BusinessStateMachineIntegrationTest` 中增加跨版本滚动升级双 Dispatcher 交接消费集成测试用例。
- Verification contribution: 提供 `REQ-004` 的真实 PG 端到端证据。
- After this file: 全部运行时与业务逻辑集成测试完备。

#### File 8 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/README.md`

- Purpose: 同步英文文档记录新增配置键与服务内分布式行为说明（受 `ComponentContractGovernanceTest` 强制校验）。
- Symbols: `egon.cola.component.transactional-outbox.state-machine.business.retry-on-missing-definition` 及新增 `polling` / `RECLAIM` 语义描述
- Repository evidence: `README.md#L135-L180`（配置表）与 `ComponentContractGovernanceTest.java#L421-L447`（抓取全文匹配 `` `(egon\.cola\.component\.transactional-outbox\.[a-z0-9.-]+)` ``）。
- Dependencies and consumers: 与 File 9（`README.zh-CN.md`）及 File 10（`ComponentContractGovernanceTest`）保持精确键集对齐。
- Why now: 文档必须与最终生效配置键一致。
- Contract/signature changes: 配置表增补 `egon.cola.component.transactional-outbox.state-machine.business.retry-on-missing-definition`（默认 `true`）；注意正文提及 `polling.jitter` 与 `polling.max-claim-contention-retries` 若使用反引号完整键名则须同步计入 `ComponentContractGovernanceTest` `requiredKeys`（建议三项新配置键统一完整键名入表并同步加入 `requiredKeys`）。
- Input/output and state mapping: N/A（文档）。
- Error and edge behavior: 确保不意外残留反引号拼写偏差或过时 `SKIP LOCKED` 越界描述。
- Standards impact: `MC-CONFIG-001`, `MC-SCOPE-001` — 遵循配置文档完整对齐规则。
- Literal rule enforcement: `Rule 7`, `Rule 11` — 保持中英文配置键结构完全一致。
- Required change: 在 `README.md` 中补充 `state-machine.business.retry-on-missing-definition`、`polling.jitter`、`polling.max-claim-contention-retries` 配置说明以及 Claim 通道过滤和 `RECLAIM` 上限说明。
- Verification contribution: 使 `ComponentContractGovernanceTest` 中英文校验逻辑通过。
- After this file: 英文文档与代码实现同步。

#### File 9 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/README.zh-CN.md`

- Purpose: 同步中文文档，确保与 `README.md` 包含完全相同的 `egon.cola.component.transactional-outbox.*` 配置键集合与行为说明。
- Symbols: 同 File 8 三个新增配置键及说明
- Repository evidence: `README.zh-CN.md#L115-L152` 已有对应配置表。
- Dependencies and consumers: 与 File 8 成对被 `ComponentContractGovernanceTest` 校验。
- Why now: 维持双语文档对等。
- Contract/signature changes: 与 `README.md` 精确对齐三个配置键与默认值。
- Input/output and state mapping: N/A（文档）。
- Error and edge behavior: 中英文抽取出的键集合必须完全一致。
- Standards impact: `MC-CONFIG-001`, `MC-SCOPE-001` — 双语配置键同步。
- Literal rule enforcement: `Rule 7`, `Rule 11` — 遵循配置一致规范。
- Required change: 在 `README.zh-CN.md` 同步添加 `state-machine.business.retry-on-missing-definition`、`polling.jitter`、`polling.max-claim-contention-retries` 配置说明以及分布式行为变更说明。
- Verification contribution: 使 `ComponentContractGovernanceTest` 中英文比对一致。
- After this file: 双语文档同步就绪。

#### File 10 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/contract/ComponentContractGovernanceTest.java`

- Purpose: 更新治理测试的 `requiredKeys` 以容纳本次新增并已记录于双语 README 的配置键，并验证既有治理断言全绿。
- Symbols: `ComponentContractGovernanceTest#englishAndChineseReadmesDocumentTheSameCqeMpAndMigrationKeys`
- Repository evidence: `ComponentContractGovernanceTest.java#L426-L440` 定义了 `Set<String> requiredKeys`。
- Dependencies and consumers: 扫描本模块全部 `main`/`test` 源码与双语 README。
- Why now: 在 Step 4 最后一步收口契约治理门禁与全量回归。
- Contract/signature changes: 在 `requiredKeys` 中加入 `egon.cola.component.transactional-outbox.state-machine.business.retry-on-missing-definition`、`egon.cola.component.transactional-outbox.polling.jitter`、`egon.cola.component.transactional-outbox.polling.max-claim-contention-retries`（与 File 8/9 完全一一对应）。
- Input/output and state mapping: 治理测试断言 `englishKeys`、`chineseKeys` 与 `requiredKeys` 完全相等。
- Error and edge behavior: 任一文档漏写或拼错键名时立刻报错阻断。
- Standards impact: `MC-CONFIG-001`, `MC-TEST-001`, `MC-SCOPE-001`, `MC-BLOCKER-001` — 全模块静态契约闭环。
- Literal rule enforcement: `Rule 7`, `Rule 11` — 通过自动化治理测试守住配置一致与包边界规则。
- Required change: 将三个新增配置键加入 `ComponentContractGovernanceTest` 的 `requiredKeys` 集合。
- Verification contribution: 配合全模块 Surefire 与 Failsafe 套件最终门禁通过。
- After this file: 整个组件完成代码、测试、文档与治理门禁闭环。

- Validation working directory: `/Users/mario/SelfProject/Stellar-Enclosure/Stellar-Enclosure-Components`
- Verification command: 先执行全模块单测 `mvn -pl stellar-enclosure-component-transactional-outbox-starter test`，再执行核心 PG Failsafe 集成测试 `EGON_OUTBOX_TEST_POSTGRES_ENABLED=true mvn -pl stellar-enclosure-component-transactional-outbox-starter -Dtest=__skip_surefire__ -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=BusinessStateMachineIntegrationTest,PostgresqlOutboxRecoveryIntegrationTest,PostgresqlOutboxQueryPlanIntegrationTest,PostgresqlOutboxConcurrencyIntegrationTest,TransactionalOutboxTransactionIntegrationTest,OutboxMybatisPlusIntegrationTest,JdbcOutboxPostgresqlIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false verify`
- Expected result: 两个命令均 Exit 0；Surefire 全部单测与 `ComponentContractGovernanceTest` 全通过；指定 Failsafe PG 集成测试全部非跳过且 0 failure/error。
- Failure returns to: 业务状态机用例失败回 File 3–6；治理测试失败核对 File 8–10 配置键拼写一致性。
- Completion criteria: `REQ-001`–`REQ-006` 全部落地并通过模块单测与真实 PostgreSQL 集成验证。
- Rollback: `git checkout --` 仅回退 Step 4 的 10 个路径。
- Commit paths: `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/delivery/statemachine/BusinessStateMachineDeliveryHandlerTest.java`, `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/statemachine/BusinessStateMachineServiceTest.java`, `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/OutboxStateMachineProperties.java`, `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/delivery/statemachine/BusinessStateMachineDeliveryHandler.java`, `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/statemachine/BusinessStateMachineService.java`, `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/OutboxBusinessStateMachineAutoConfiguration.java`, `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/BusinessStateMachineIntegrationTest.java`, `stellar-enclosure-component-transactional-outbox-starter/README.md`, `stellar-enclosure-component-transactional-outbox-starter/README.zh-CN.md`, `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/contract/ComponentContractGovernanceTest.java`
- Commit: `feat(outbox): allow retryable missing business statemachine definition during rollout`

## 8. Test, Validation, and Quality Gates

| Gate/order | Working directory | Command or method | Scope | Expected result | Failure returns to | Requirements/runtime boundary |
| --- | --- | --- | --- | --- | --- | --- |
| 1 RED (Step 1–4 各步前置) | `/Users/mario/SelfProject/Stellar-Enclosure/Stellar-Enclosure-Components` | `mvn -pl stellar-enclosure-component-transactional-outbox-starter -Dtest=<StepTest> test` | 各 Step 对应单测文件 | 因目标缺省行为（未校字段、未抖动、未返 `DEAD`、未按通道过滤、未返回可重试）失败，而非 fixture 编译/环境报错 | 对应 Step File 1–2 | `REQ-001`–`REQ-005`; 模块单测 |
| 2 GREEN (Step 1) | `/Users/mario/SelfProject/Stellar-Enclosure/Stellar-Enclosure-Components` | `mvn -pl stellar-enclosure-component-transactional-outbox-starter -Dtest=OutboxConfigurationValidatorTest,OutboxPollerTest,TransactionalOutboxPropertiesTest test` | Step 1 配置、校验与轮询抖动 | Exit 0；全部通过 | Step 1 File 3–5 | `REQ-001`, `REQ-002`; 模块单测 |
| 3 GREEN (Step 2) | `/Users/mario/SelfProject/Stellar-Enclosure/Stellar-Enclosure-Components` | `mvn -pl stellar-enclosure-component-transactional-outbox-starter -Dtest=OutboxLifecycleServiceTest,StateMachineExecutionServiceTest test` | Step 2 `RECLAIM` Guard | Exit 0；全部通过 | Step 2 File 2 | `REQ-005`; 模块单测 |
| 4 GREEN (Step 3 单元 + PG 集成) | `/Users/mario/SelfProject/Stellar-Enclosure/Stellar-Enclosure-Components` | `mvn -pl stellar-enclosure-component-transactional-outbox-starter -Dtest=DeliveryHandlerRegistryTest,OutboxDispatcherTest,OutboxMultiShardStoreTest,OutboxMybatisPlusAutoConfigurationTest,OutboxJdbcAutoConfigurationTest test` 及 `EGON_OUTBOX_TEST_POSTGRES_ENABLED=true mvn -pl stellar-enclosure-component-transactional-outbox-starter -Dtest=__skip_surefire__ -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=PostgresqlOutboxRecoveryIntegrationTest,PostgresqlOutboxQueryPlanIntegrationTest,PostgresqlOutboxConcurrencyIntegrationTest,JdbcOutboxPostgresqlIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false verify` | Step 3 通道过滤、冲突上限、`RECLAIM -> DEAD` 及 PG 索引计划 | Exit 0；0 failures/errors/skips | Step 3 File 4–14 | `REQ-002`, `REQ-003`, `REQ-005`, `REQ-006`; 本地隔离 PG Testcontainers |
| 5 Regression & Final (Step 4 全模块 + PG 核心回归) | `/Users/mario/SelfProject/Stellar-Enclosure/Stellar-Enclosure-Components` | `mvn -pl stellar-enclosure-component-transactional-outbox-starter test` 及 `EGON_OUTBOX_TEST_POSTGRES_ENABLED=true mvn -pl stellar-enclosure-component-transactional-outbox-starter -Dtest=__skip_surefire__ -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=BusinessStateMachineIntegrationTest,PostgresqlOutboxRecoveryIntegrationTest,PostgresqlOutboxQueryPlanIntegrationTest,PostgresqlOutboxConcurrencyIntegrationTest,TransactionalOutboxTransactionIntegrationTest,OutboxMybatisPlusIntegrationTest,JdbcOutboxPostgresqlIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false verify` | 全模块单测、`ComponentContractGovernanceTest` 与 7 个 PG 核心集成测试 | Exit 0；全量通过且无 skipped 集成项 | Step 4 或对应归属 Step | `REQ-001`–`REQ-006`; 需本机 Docker，不等同多节点生产压测 |

注：上述全部验证命令均复用仓库既有 Maven Surefire 与 Failsafe 配置，无需在 `scripts/work/` 创建任何临时脚本或额外验证工具。

## 9. Migration, Compatibility, Rollout, and Rollback

- **数据库迁移**：`N/A` — 依据 [Spec §11 & §16](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md#11-database-design)，本次零表结构与零 DDL 脚本变动，`V20260924_001`、`V20261027_001`、`V1` 及 `manifest.json` 保持完全不可变。
- **向后兼容性**：
  - `OutboxStore` 保留原有 3 参 `claimDue` 与 4 参 `claimByMessageIds` `default` 方法；
  - `OutboxPoller` 保留原有 4 参公有构造器；
  - `MybatisPlusOutboxStore` 与 `BusinessStateMachineDeliveryHandler` 均保留既有构造器重载并赋予安全默认值（`maxClaimContentionRetries = 8`、`retryOnMissingDefinition = true`）。
- **上线顺序与运行期回退**：
  - 随业务服务新版本发布同步带上升级后的 `stellar-enclosure-component-transactional-outbox-starter`；对于新增跨版本 `statemachine` `destination` 的发布，仍建议优先滚动升级消费集群，完全替换后再放量产出新版事件。
  - 若运行期需临时退回旧行为，无需回滚代码包即可通过 YAML 参数降级关闭：`egon.cola.component.transactional-outbox.polling.jitter=0.0`（恢复固定周期）与 `egon.cola.component.transactional-outbox.state-machine.business.retry-on-missing-definition=false`（缺失定义立即进 `DEAD`）；或按 Step 提交逐项 `git revert`，零数据库回滚风险。

## 10. Requirement-to-Step Traceability Matrix

| Requirement | Effective Spec section | Steps | Files | Tests/gates | Completion evidence |
| --- | --- | --- | --- | --- | --- |
| `REQ-001` | [Spec §4, §7, §8](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) | Step 1 | `OutboxConfigurationValidatorTest`, `OutboxPollerTest`, `TransactionalOutboxProperties`, `OutboxConfigurationValidator`, `OutboxPoller` | `TEST-001`, `TEST-002`（Step 1 验证命令） | 合法范围与抖动区间单测通过 |
| `REQ-002` | [Spec §4, §7, §8, §9](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) | Step 1, Step 3 | `OutboxConfigurationValidatorTest`, `TransactionalOutboxProperties`, `OutboxConfigurationValidator`, `OutboxMultiShardStoreTest`, `MybatisPlusOutboxStore`, `OutboxMybatisPlusAutoConfiguration` | `TEST-001`, `TEST-003`（Step 1 & Step 3 命令） | 配置校验与冲突重开上限单测通过 |
| `REQ-003` | [Spec §4, §7, §8, §9, §11](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) | Step 3 | `DeliveryHandlerRegistryTest`, `OutboxDispatcherTest`, `DeliveryHandlerRegistry`, `OutboxStore`, `OutboxMessageDAO`, `OutboxMessageMapper.xml`, `OutboxMessageRepository`, `JdbcOutboxStore`, `MybatisPlusOutboxStore`, `OutboxDispatcher`, `PostgresqlOutboxRecoveryIntegrationTest`, `PostgresqlOutboxQueryPlanIntegrationTest` | `TEST-004`, `TEST-005`（Step 3 单元 + PG 集成命令） | 异构 `channel` 不被误领且 `EXPLAIN` 走既有索引 |
| `REQ-004` | [Spec §4, §7, §8, §9](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) | Step 4 | `BusinessStateMachineDeliveryHandlerTest`, `BusinessStateMachineServiceTest`, `OutboxStateMachineProperties`, `BusinessStateMachineDeliveryHandler`, `BusinessStateMachineService`, `OutboxBusinessStateMachineAutoConfiguration`, `BusinessStateMachineIntegrationTest` | `TEST-006`, `TEST-007`（Step 4 单元 + PG 集成命令） | 老节点安全退避待新节点消费成功，入队仍强校验 |
| `REQ-005` | [Spec §4, §7, §8, §9, §11, §13](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) | Step 2, Step 3 | `OutboxLifecycleServiceTest`, `OutboxLifecycleStateMachineFactory`, `OutboxMessageDAO`, `OutboxMessageMapper.xml`, `OutboxMessageRepository`, `JdbcOutboxStore`, `MybatisPlusOutboxStore`, `PostgresqlOutboxRecoveryIntegrationTest` | `TEST-008`, `TEST-009`（Step 2 & Step 3 命令） | 真实 SSM `RECLAIM` 满额返 `DEAD` 且双存储原子落库 |
| `REQ-006` | [Spec §4, §8, §9, §11, §14, §16](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) | Step 3, Step 4 | `OutboxStore`, `OutboxMessageRepository`, `JdbcOutboxStore`, `MybatisPlusOutboxStore`, `PostgresqlOutboxQueryPlanIntegrationTest`, `README.md`, `README.zh-CN.md`, `ComponentContractGovernanceTest` | `TEST-005`, `TEST-009`, `TEST-010`（全模块单测与 PG 集成） | 既有签名兼容、索引不变、双语 README 与治理测试通过 |

## 11. Risks, Blockers, and User Decisions

| ID | Risk or decision | Impacted Steps/files | Evidence | Owner | Status/action |
| --- | --- | --- | --- | --- | --- |
| `RISK-001` | 若本地未启动 Docker daemon，`EGON_OUTBOX_TEST_POSTGRES_ENABLED=true` 的 PG Failsafe 集成测试将无法执行 | Step 3, Step 4 集成验证 | `PostgresqlOutboxTestSupport.java` 使用 Testcontainers | 实施执行方 | 闭环前置检查：执行 Step 3/4 集成验证前确认本机 Docker 可用 |
| `RISK-002` | `README.md` / `README.zh-CN.md` 若新增任何反引号 `` `egon.cola.component.transactional-outbox.*` `` 键而未同步更新 `ComponentContractGovernanceTest#requiredKeys`，治理测试将立即失败 | Step 4 File 8–10 | `ComponentContractGovernanceTest.java#L421-L447` | 实施执行方 | 已在 Step 4 File 8–10 显式绑定三者同一步变更与核对 |
| `BLOCK-001` | 主 Spec 当前 `Status` 为 `Review`（待用户最终批准实施）；依 Plan 规则在用户对 Plan 确认执行前不启动代码改动 | 全计划 | [Spec](../spec/2026-11-16-08-11-outbox-in-service-distributed-resilience.md) Header | User | Closed for planning（计划自身内部闭环）；等待用户授权进入 `/stellar-enclosure-coding-execute-plan` |

## 12. Review and Acceptance

### 12.1 Original requirement fidelity
本计划完整覆盖 Spec 中基于用户确认方案拆解的 `REQ-001`–`REQ-006`，严格保留“提交后实时唤醒 + 内置抖动轮询兜底”架构，不引入外部定时任务或 Redis，并清晰隔离服务内事务事件改进范围。

### 12.2 Spec consistency
逐文件核对确认所有签名、构造兼容、SQL 谓词、配置键、Guard 复用与测试目标均忠实实现 Spec 设计；仅存在 3 处局部可逆的构造重载/Repository 转发细节并已在 §6.4 记录为 `Plan Clarification`（`PLAN-CLAR-001`–`PLAN-CLAR-003`）；§4.5 必要性审计确认无 fetch-then-forward 或多余抽象。

### 12.3 Repository executability
全计划 31 个修改文件（生产代码、Mapper XML、双语文档与单测/集成测试）在 4 个 Step 中严格保持无写范围重叠，路径、类名、方法名与 Maven 测试命令均基于当前 `Stellar-Enclosure-Components` 仓库基线核实，无任何代码块或占位符。

### 12.4 Test and release completeness
每个行为 Step 均安排先写/改失败单测确立 RED 再落地最小 GREEN 实现，并区分 Surefire 模块单测与隔离 PostgreSQL Failsafe 集成测试的验证边界；所有变更均无 DDL 迁移风险且支持配置开关降级与路径级提交回退。

### 12.5 Blocking Manual Check

| Check ID | Applicability | Status | Evidence | Finding | Required action/exception |
| --- | --- | --- | --- | --- | --- |
| `MC-ARCH-001` | `Applicable` | `PASS` | `Stellar-Enclosure-Components/egon-cola-components-architecture.md`, `.../pom.xml`, §5 文件树 | 严格保持基础设施 Flat Starter 包边界，无新层与业务三层混合 | `None` |
| `MC-REUSE-001` | `Applicable` | `PASS` | §4.7 Capability reuse ledger（`TaskScheduler`, `DeliveryHandlerRegistry`, `RETRY_WITH_BUDGET`/`RETRY_EXHAUSTED`, `OutboxConfigurationValidator`） | 全部能力优先复用既有组件与 JDK/Spring 原生接口 | `None` |
| `MC-DEP-001` | `Applicable` | `PASS` | `.../pom.xml`, `stellar-enclosure-components-bom/pom.xml`, §6.3 | 零新增、零升级任何 Maven 依赖 | `None` |
| `MC-NAME-001` | `Applicable` | `PASS` | §5 文件树与 §4.8 Rule 1 | 不新增任何类，全部既有类保持标准语义后缀，无 `Data`/`Info`/`Param`/`Bean` | `None` |
| `MC-VALID-001` | `Applicable` | `PASS` | Step 1 `OutboxConfigurationValidator`、Step 3 `OutboxStore`/`OutboxMessageDAO`/`OutboxMessageRepository`、`OutboxConfigurationValidatorTest` | 配置与 `channels` 参数均具备明确校验边界及负向测试 | `None` |
| `MC-MODEL-001` | `Applicable` | `PASS` | Spec §10 与 §4.8 Rule 3（`OutboxMessagePO`, `OutboxRecord`, `BusinessStateMachineEvent` 均不变；`OutboxStateMachineProperties` 沿用 Lombok `@Data/@Builder`） | 既有 PO/Record/Event 建模不变，配置属性合规扩展 | `None` |
| `MC-CONVERT-001` | `Not applicable` | `N/A` | Spec §10 与 §4.8 Rule 3（无任何新增或变更跨层对象转换与 `Converter`） | 无新增/变更对象映射边界，既有 `OutboxMessageConverter` 保持不变 | `None` |
| `MC-LOG-001` | `Applicable` | `PASS` | Step 1 `OutboxPoller`、Step 3 `JdbcOutboxStore`/`MybatisPlusOutboxStore`、Step 4 `BusinessStateMachine*` | 所有新增/修改业务类使用 `@Slf4j`，仅记录非敏感 ID/channel/destination | `None` |
| `MC-BEAN-001` | `Applicable` | `PASS` | `lombok.config`, `OutboxMybatisPlusAutoConfiguration`, `OutboxBusinessStateMachineAutoConfiguration`, `PLAN-CLAR-002`, `PLAN-CLAR-003` | 显式 Bean 名与 `@Qualifier` 参数注入保持不变，既有测试构造器兼容 | `None` |
| `MC-UTIL-001` | `Applicable` | `PASS` | §4.8 Rule 5（仅用 JDK 原生 `ThreadLocalRandom`/`java.time`/`Collections`） | 无新 `*Utils` 类与禁止清单外工具引入 | `None` |
| `MC-JSON-001` | `Not applicable` | `N/A` | Spec §6.2 Rule 6 & §10（不涉及外部 JSON 契约与序列化格式改动） | 既有 Jackson 契约保持不变 | `None` |
| `MC-TIME-001` | `Applicable` | `PASS` | §4.9 `TIME-001`, `TIME-002`、`OutboxPoller`、`JdbcOutboxStore`、`MybatisPlusOutboxStore`、`OutboxPollerTest`、`PostgresqlOutboxRecoveryIntegrationTest` | 相对延时用 `Duration`/`Instant.now(clock)`，落库用 `clock_timestamp()` UTC `timestamptz(6)` | `None` |
| `MC-CONFIG-001` | `Applicable` | `PASS` | Step 1 `TransactionalOutboxProperties`、Step 4 `OutboxStateMachineProperties`、`README.md`、`README.zh-CN.md`、`ComponentContractGovernanceTest` | 新配置均有安全默认值且双语 README 与治理测试键集合完全同步 | `None` |
| `MC-PATTERN-001` | `Applicable` | `PASS` | Step 2 `OutboxLifecycleStateMachineFactory`（State+Guard）、Step 3 `DeliveryHandlerRegistry`（Registry）、Step 4 简单开关 | 状态逻辑落实到状态机 Guard，无硬编码状态分支或过度模式膨胀 | `None` |
| `MC-SCOPE-001` | `Applicable` | `PASS` | §5 树与 §6.1（31 个文件严格限定在 `transactional-outbox-starter` 内，4 Step 写范围完全正交） | 无跨模块越界改动与无相关重构 | `None` |
| `MC-TEST-001` | `Applicable` | `PASS` | §7 各 Step RED/GREEN 安排与 §8 测试矩阵（单测 + PG Failsafe + 治理门禁） | 每项需求均配有独立可复现的验证命令与成功判定标准 | `None` |
| `MC-BLOCKER-001` | `Applicable` | `PASS` | §11（`RISK-001`/`002` 与 `BLOCK-001` 已明确边界，Plan 内部无未闭合阻塞） | 所有适用 Manual Check 均为 `PASS` 或有据 `N/A` | `None` |

### 12.6 Final verdict

`PASS — Ready for user review`
