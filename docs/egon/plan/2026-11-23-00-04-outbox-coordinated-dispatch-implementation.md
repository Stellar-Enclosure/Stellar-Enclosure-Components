# Transactional Outbox 多实例队列协调与可靠分发实施计划

| Field | Value |
| --- | --- |
| Document | `docs/egon/plan/2026-11-23-00-04-outbox-coordinated-dispatch-implementation.md` |
| Template Version | `6` |
| Status | `Review` |
| Created | `2026-11-23 00:04 AST` |
| Updated | `2026-11-23 01:39 AST` |
| Owner | `mario` |
| Repository | `Stellar-Enclosure-Components` |
| Scope | `stellar-enclosure-component-transactional-outbox-starter` Java 实现、JDBC/MP 协调持久化、运行时、组件验证及双语文档 |
| Source Requirement | 用户确认按当前 Outbox Spec 写实施 Plan，明确写清改造顺序、MP 多分片验证、回退步骤，先审核。Rust/JNI 不纳入 |
| Baseline Revision | `main@d3f5f9cc989a01e054aed6b3982df9366f1b842a`，开始时唯一未跟踪文件为本次目标 Spec |
| Implements Spec | [Outbox 多实例队列协调与可靠分发 Spec](../spec/2026-11-22-23-41-outbox-coordinated-dispatch.md) |
| Spec Status | `Review` |
| Spec Revision | `2026-11-22 23:57 AST SHA-256 2c43e4ea9bfdc36100a1c17ab5084f21f06a700fa152090d72b60db4057a3092`，包含本次 Related Plans 回链 |
| Effective Specs | [Outbox 多实例队列协调与可靠分发 Spec](../spec/2026-11-22-23-41-outbox-coordinated-dispatch.md) ＋ [双层 Spring Statemachine 与 CQE 已实现规格](../../../../Stellar-Enclosure-Parent/docs/history/egon/spec/2026-09-24-12-00-outbox-statemachine-cqe-integration.md)（规范依赖与被修订部分见 §2.2） |
| Depends On Plans | `None` |
| Supersedes | `None` |
| Superseded By | `None` |
| Related Plans | [11-16 韧性实施计划](./2026-11-16-08-19-outbox-in-service-distributed-resilience.md)，仅历史背景 |

## 1. Summary

实施分七个 Step。先锁定契约、状态机和配置，再让 schema 和路由能独立核验；分别实现 JDBC 和 MP Store，然后实现本地分发与队列协调；最后装配并控制启用，完成观测、压测和回退演练。先交付单条安全基线，在 JDBC 压测通过后开放小批。MP 多分片验收必须走生产 Store 和 Dispatcher，记录各主库实际执行及行差异。回退演练保留新制品与数据库历史，以暂停、排空、同版本 LOCAL 重新启用作为默认流程。

## 2. Target Spec and Effective Design

### 2.1 Primary target

- Path: [Outbox 多实例队列协调与可靠分发 Spec](../spec/2026-11-22-23-41-outbox-coordinated-dispatch.md)
- Status: `Review`。当前对话明确授权据此写 Plan，用户审核 Plan 后再决定实施。
- Revision: `2026-11-22 23:57 AST`。文档 SHA-256 见顶部，唯一内容增量是本 Plan 的关系回链。目标基线为 `d3f5f9cc989a01e054aed6b3982df9366f1b842a`。
- Approval evidence: 用户「可以，按这个 Outbox spec 写一份实施 plan，把改造顺序、MP 多分片验证和回退步骤写清楚。我先审核，先不要改代码，Rust JNI 还是不纳入。」

### 2.2 Effective Spec set

| Role | Spec/link | Status/revision | Effective sections | Why included |
| --- | --- | --- | --- | --- |
| Primary | [Outbox 多实例队列协调与可靠分发 Spec](../spec/2026-11-22-23-41-outbox-coordinated-dispatch.md) | Review，2026-11-22 23:57 AST | REQ-001 至 REQ-008，§7–§11、§14–§18 | 本轮实现唯一目标，明确修订旧技术领取与 ACK 语义 |
| Normative dependency and amended baseline | [双层 Spring Statemachine 与 CQE 已实现规格](../../../../Stellar-Enclosure-Parent/docs/history/egon/spec/2026-09-24-12-00-outbox-statemachine-cqe-integration.md) | Implemented，2026-09-27 08:52 CST | §7.3.1、§9 INTERNAL-001、§11 由主 Spec 修订；§7/9/11 其余双层职责、原消息身份、事务和 receipt 保留 | 技术 SSM 强制，业务 SSM 是已有可选适配，CQE 保持同事务入队 |

11-16 韧性 Spec 是主 Spec 的 Related Specs，候选过滤、冲突退避和重领预算以当前源码为证。它及其历史 Plan 不变更。历史已实现 Spec 的 REQ-009/010 仅按适用边界做保留回归。当前主 Spec 明确支持单库 JDBC 作为独立配置，MP 不允许自动回退为 JDBC。原停写复制和手动运维按已实现版本回归，无本次存量复制。

### 2.3 Superseded or excluded content

本 Plan 沿用 Spec 对技术领取、ACK 截止时间及权威结果决策的增量修订。任务类型到 destination、task_type、哈希桶的细分、MP 快速语法、Rust/JNI/FFM 和独立投递进程均不属于本次开发。保留旧显式运维边界，LOCAL/COORDINATED 关闭 DEAD 自动重驱。默认 LEGACY 兼容已有自定义实现；切换显式执行。应用、网关、审计平台及外部 API 无代码范围。

## 3. Effective Requirements and Acceptance

| Requirement | Source Spec section | Effective statement | Observable acceptance | Implementation impact |
| --- | --- | --- | --- | --- |
| REQ-001 | §4、§7.3.1、§9 INTERNAL-001 | 按物理 shard/channel 自动分配领取权 | 同时最多一个有效 owner，资格缺失及旧 epoch 无权查 due；退出后自动接管 | 两个技术表、Coordinator、Store |
| REQ-002 | §4、§7.3.2、§9 JOB-001 | 先预约就绪 worker；轮询兜底 | 四忙第五条保持待分发；信号丢失仍扫描；槽归还重扫 | Dispatcher、Poller、条件单分片 claim |
| REQ-003 | §4、§7.3.4、§11 | 领取、协调和确认严格单主分片 | A 已提交直接交付，B 断开不影响；实际执行无跨组和广播 | 管理 DDL、MP 路由/DAO、安全事务 |
| REQ-004 | §4、§7.3.1/3、§9 INTERNAL-003 | 队列 epoch 与消息 token 独立 | 旧队列不能新领，旧 owner 合法消息可 finish；消息到期等号拒绝 ACK | 主库时钟、行锁与 token CAS |
| REQ-005 | §4、§7.3.3/5 | Store 权威状态机决策、预算、稳定 ID、未知提交恢复 | 失败后可恢复；无过期回写或仅因 ACK 失败重发；下游 receipt 保护业务副作用；耗尽归 DEAD | 技术状态机、JDBC/MP finish、故障注入 |
| REQ-006 | §4、§7.3.5、§16 | 兼容老消息/入队/SPI/业务定义，并验证 schema 和部署准入 | 旧 ID 可消费、旧 Store LEGACY 启动、缺定义分类及 header/tenant 拒绝、坏 route 拒启 | 装配、迁移治理、内置 handler |
| REQ-007 | §4、§15–§16 | 分阶段指标、对照压力验证与同版可回退 | 可区分 claim/start/ACK；实际 MP 物理证据、真实报告非跳过；同版 LOCAL 回滚后恢复进度 | 日志/指标、测试夹具、双语运行手册 |
| REQ-008 | §4、§3.2 | Java Starter 延续原依赖与运维职责 | 改动仅组件和验证；无新依赖或自动重驱 DEAD | 所有 Step 的范围/依赖检查 |
| REQ-009（历史，保留适用部分） | 依赖 Spec §4/7/9 | MP 模式经既有 Repo/Mapper、EgonModel 与逻辑 DS，无运行时物理旁路或 JDBC fallback | 实际路由同主且新版保留；独立 JDBC 按主 Spec | Step 4/6，MP 模式回归 |
| REQ-010（历史，保留适用部分） | 依赖 Spec §4/11 | 受管 DDL、无损启用与受控回退 | 原已安装 SQL/身份保持，后续版本可续跑；旧迁移合同仍通过 | Step 2/6/7，版史与切换回归 |

## 4. Implementation Strategy and Dependency Order

### 4.1 Ordered strategy

1. Step 1 发布编译期契约、配置与技术状态机规则，默认行为仍走 LEGACY。新 Store 默认方法明确不支持，防止无实现时启动新消费者。
2. Step 2 在一次提交交付两个 DDL、MP manifest、逻辑拓扑适配和校验测试。新 manifest 在 LEGACY 同样参与启动检查，Step 2 的制品因此不能独立用于生产滚动发布。
3. Step 3 完成单库 JDBC Store；Step 4 完成同分片 MP Store、PO、DAO、mapper 和物理路由门槛。使用直接调用的 Store 合同验证，运行时新分派暂未开放。
4. Step 5 在现有 Dispatcher 中完成两种安全模式的执行和协调，组件测试直接构造。Step 6 接上 Spring Bean 配置、暂停入口、消费迁移及真正端到端 MP 夹具。两种安全模式只能在全部条件满足时启动，无 quiet fallback。
5. Step 7 交付阶段诊断、双语运行手册及性能门槛。基线用完整试点矩阵证明后启用小批。代码分步提交与部署迁移是两条边界，部署只使用已验证的最终制品。

### 4.2 Test-first strategy

| Step | RED | Expected missing behavior | Minimum GREEN |
| --- | --- | --- | --- |
| 1 | OutboxLifecycleServiceTest、OutboxConfigurationValidatorTest | 耗尽待分发无终结、无跨字段安全启动规则 | 技术图、生效配置和兼容默认，接口先提供可编译签名 |
| 2 | OutboxManagedDdlReadwriteTest、OutboxShardSqlGuardTest、OutboxManagedDdlIntegrationTest | 缺两个协调表／错误拓扑未被拒绝／固定 SQL 尚未放行 | 加法 DDL、错误拓扑 fail closed、精确 canonical SQL 守卫 |
| 3 | JdbcOutboxPostgresqlIntegrationTest | Store 仅旧扫描入口，缺少 epoch/容量/finish | 单库 store，真实 PG 锁、时间和 commit |
| 4 | OutboxMpSharedContractIntegrationTest、PostgresqlOutboxRecoveryIntegrationTest | MP 缺新 Store 路径和主库写回；并发老 token 未测试 | 受管逻辑 SQL、shard 内 fence、技术状态 CAS |
| 5 | OutboxDispatcherTest、OutboxPollerTest、OutboxQueueCoordinatorTest | 旧线程池排队、脏代次丢唤醒、无 ownership 或狭义缺定义分类 | ready reservations、单队列调度、fence 与分片交接 |
| 6 | TransactionalOutboxAutoConfigurationTest、OutboxPostgresqlMultishardIntegrationTest 新场景 | 旧入口绕过 pause；没有真实两主运行器、未完成模式门控 | 完整实例装配、切换隔离、A/B 故障矩阵 |
| 7 | MicrometerOutboxMetricsTest、PostgresqlOutboxQueryPlanIntegrationTest | 指标未区分 handler 与 confirmed；小批未证明减少交互 | 阶段指标、查询计划、回退与配置文档，迟到的环境缺失只标未验收 |

必要类型或签名先让测试可编译。只有断言按列出的业务原因失败才算 RED。每步完成后再执行该步完整回归，验证前后不能通过跳过 opt-in 用例代替成功。

### 4.3 Sequential and parallel boundaries

| Step | Depends on | May run in parallel with | Must not overlap with | Reason |
| --- | --- | --- | --- | --- |
| Step 1 | Spec、Plan 实施批准 | 已有基线静态查验 | Step 5/6 的接口或配置写 | 类型和状态图是前置 |
| Step 2 | Step 1 | JDBC 性能基线采样可另开已授权隔离夹具 | Step 4/6 的 MP DDL/拓扑与真实集成夹具写 | 先定义可校验的路由及主库命令上限 |
| Step 3 | Step 2 | Step 4 可探索，双方共享测试契约保持只读 | Step 6 JDBC Bean 路径 | Store 独立；先用单库验证通用事务语义 |
| Step 4 | Step 2、Step 3 的合同回归 | Step 5 只做静态梳理 | Step 6 MP AutoConfig 与真实联调 | 先验证受管逻辑路径再启控制循环 |
| Step 5 | Step 1、Step 3、Step 4 | 性能基线数据准备 | Step 6 Dispatcher 装配测试 | 服务端调度先可直接构造验证 |
| Step 6 | Step 5 | 发布环境与 DBA 预演工作准备 | Step 7 装配观察点、运行手册最终参数确认 | 第一次可整体启用 |
| Step 7 | Step 6 | JDBC 和 MP 压测使用隔离数据库 | 同一测量数据库上的另一轮压测 | 结果需可比较；完成验证后才走部署门 |

### 4.4 Commit boundaries

未来授权实施时每步用一个仅包含 §7 所列路径的语义提交，检查 `git diff --cached --name-only`、`git diff --cached --check` 和模块相关测试摘要。重复路径仅允许 §5 标注的符号/测试段各自改动，前步保留可编译可测状态。不把本次未跟踪 Spec/Plan、临时性能 harness 或其他工作并入代码提交。DDL 未应用前可逐步回退代码；已应用 MP 迁移时用 §9.4 的同版模式回退。

### 4.5 Spec Simplicity and Implementation-necessity Audit

| Spec element | Spec necessity verdict/section | Current repository evidence | Direct/reuse alternative | Interaction/implementation cost | Plan decision |
| --- | --- | --- | --- | --- | --- |
| 成员、归属和 Coordinator | Add，§7.0/7.3.1 | DeliveryHandlerRegistry、OutboxWorkerIdentity 无实例共享视图 | 单活应用或仅本地哈希无法自动接管/挡旧 epoch | 单 shard 受控心跳与一行归属，消息不新增协调 round trip 至远程服务 | Implement |
| INTERNAL-002 单分片 claim | Merge，§7.0/§9 | MybatisPlusOutboxStore 当前跨 slot 汇总 | 将现有返回值按分片再分组无法恢复已提交、未返回的一批 | 同一短事务验 epoch 和锁定消息，确认后移交 | Implement |
| INTERNAL-003 唯一 finish | Merge，§7.0/§9 | OutboxDispatcher#applyResult 与 Store 历史各判一次 | 仅重试老 mark 不能区分权威预算和 commit 未知 | 现有 Store 事务、State+Guard；权威读取在事务内完成 | Implement |
| Worker 真就绪 | Merge，§7.0/§7.3.2 | delivery permits 含池队列、消费后无 ready 唤醒 | 直接领会延迟到执行器真正可用 | 已有 Dispatcher 内单槽交接，不增常驻外部进程 | Implement |
| 容量受限 JDBC 小批 | Add，§7.3.4/§15 | JDBC 原候选锁定和逐条更新 | 单条仍是有效安全路径 | 小批只减少领取交互，ACK 语义保留 | Implement with gate |
| 阶段指标与模式门禁 | Merge，§15/16 | OutboxMetrics 与独立的 afterCommit / poll 路径 | 日志无法统计租约持有与确认，poll 开关不兜住 afterCommit | 复用 SPI/Properties，无 API 或额外热写 | Implement |

无 fetch-then-forward 接口。实例能力由注册表得出，分片路由从可信上下文与领取回执得出，时间及尝试预算由目标主库确定。

### 4.6 Change-unit Dependency Matrix

| Change unit | Requirements | Proof/RED point | Compile/runtime prerequisites | Produces | Consumers/unblocks | Owning Step |
| --- | --- | --- | --- | --- | --- | --- |
| 技术契约与启动规则 | REQ-004/005/006/008 | L01 | 原 SSM、Properties | safe 入口及校验、legacy ABI | 两种 Store 和 Dispatcher | Step 1 |
| 扩 schema 与受管路由 | REQ-001/003/006/007 | L02 | 当前两个 SQL 与 route profile | PG 表、guard、schema ready | JDBC/MP Store | Step 2 |
| JDBC 事务、批量和 ACK | REQ-001/002/004/005 | L03 | 新 schema、JDK/JDBC、技术图 | JDBC coordinate/claim/finish | 本地执行和性能门槛 | Step 3 |
| MP 单 shard 真实持久化 | REQ-001/003/004/005/006 | L04 | MP route、schema、技术图 | MP coordinate/claim/finish | 实例调度，PG 物理验证 | Step 4 |
| 实例间协调与 ready 交接 | REQ-001/002/003/004/005/006 | L05 | 单 shard Store 合同 | Coordinator、Dispatcher、Poller | Spring 激活 | Step 5 |
| 装配、模式、真 MP 故障门 | REQ-001/002/003/004/006/007/008 | L06 | 完成 Step 5、dedicated PG | 两种模式、总门禁、真实 2×2 证据 | 演练与发布 | Step 6 |
| 阶段指标与性能／交付 | REQ-007/008 | L07 | 启用路径及证据 | 监控、双语运行手册、压力记录 | 发布评估与回退复核 | Step 7 |

### 4.7 Java, Spring, and Egon-COLA Implementation Standards

| Concern | Current repository evidence | Effective Spec decision | Planned implementation consequence | Owning Steps/checks |
| --- | --- | --- | --- | --- |
| Architecture profile | `egon-cola-components-architecture.md`、模块 POM 和现有 dispatch/store/persistence 包 | §6.1 既有基础设施 Starter | 保持组件包形态，依赖 dispatch → Store → persistence → 已有 Spring/MP，继承技术表约束；无应用架构迁移 | Step 1–7，MC-ARCH-001 |
| Reuse/capability | 下表与双模式 AutoConfiguration | §6.1、§7.0 | 复用依赖、已有辅助器和测试基座 | Step 1–7，MC-REUSE-001、MC-DEP-001 |
| Naming/model/validation/conversion | OutboxMessagePO、OutboxRecord、OutboxMessageConverter、BaseValidator | §6.2、§9–10 | 不可变 key/lease record；Command/BO/PO class 使用 Lombok 约定；Jakarta 分组校验；MapStruct + BaseForwardConverter | Step 1/4/6，MC-NAME-001、MC-VALID-001、MC-MODEL-001、MC-CONVERT-001 |
| Bean/logging/util/JSON/time/config | `lombok.config`、TransactionalOutboxAutoConfiguration、OutboxHeadersConverter、MicrometerOutboxMetrics | §10/15/16 | 显式 Bean/Qualifier、必要构造器 @Autowired，保留 @Lazy；Boot Jackson，java.time、DB UTC；双语配置同键 | Step 1–7，MC-BEAN-001、MC-LOG-001、MC-UTIL-001、MC-JSON-001、MC-TIME-001、MC-CONFIG-001 |
| Business variation/pattern | OutboxLifecycleStateMachineFactory、DeliveryHandlerRegistry | §7/13 | 快照仅作调度，状态机用锁内行判断；运输、业务回执及选择器沿用 | Step 1/3/4/5，MC-PATTERN-001 |

#### Capability reuse ledger

| Need | Candidates inspected | Exact evidence | Fit/gap | Decision | Added dependency/custom code | Owning Step/check |
| --- | --- | --- | --- | --- | --- | --- |
| 事务和调度 | Spring TransactionTemplate、TaskScheduler、JDK 标准并发 | OutboxJdbcAutoConfiguration#outboxStore、OutboxMybatisPlusAutoConfiguration#outboxWorkerTransactionTemplate、OutboxPoller | 可复用事务管理器和调度，缺真实预约 | REQUIRES_NEW 独立短事务，扩 Dispatcher | 现有依赖 | Step 3–6，MC-REUSE-001 |
| 数据库与守卫 | Common MP、Egon Postgre DDL、现有 Outbox 路由 | OutboxManagedDdlInitializer、OutboxLogicalDataSourceFactory、OutboxShardSqlGuard | 二表未纳入现有 guard | 追加窄白名单，技术 tenant 0 | 现有依赖 | Step 2/4，MC-REUSE-001 |
| 模型/验证/序列化 | Jakarta Validation、Spring Boot 管理 Jackson、MapStruct、Common Core | OutboxMessagePO、OutboxMessageConverter、ValidationUtils | 新表需技术 PO/投影 | 模型分组、显式映射、无新工具依赖 | 现有依赖 | Step 1/4，MC-DEP-001 |
| 决策、状态及指标 | Spring Statemachine、DeliveryHandler Registry、Micrometer | OutboxLifecycleService、OutboxMetricsAutoConfiguration | 缺权威 finish 和阶段指标 | 增量接好既有模式与默认 SPI | 现有依赖 | Step 1、5–7，MC-REUSE-001 |

### 4.8 User-mandated Java Rule Implementation Matrix

> 1 类名规范，必须以 java的pojo规范命名。以dao po bo vo dto query command event等结尾
> 2 每层之间必须被 springboot-validation 校验，复用对象使用分组校验；基于原生和自定义约束注解、@Valid、@Validated 及 ConstraintValidator 扩展实现。ValidationUtils 只承担通用手工校验，不承载全部业务校验；电话号码复用 libphonenumber。
> 3 Java POJO 默认使用 class，注解为 @Data @NoArgsConstructor @AllArgsConstructor @Accessors(chain = true)，按构造目标选择 @Builder 或 @SuperBuilder；父类状态参与相等性时使用 @EqualsAndHashCode(callSuper = true)。只有不可变 value object 可以使用 record，并豁免 class 的 Lombok 规范；普通实体不能因简单而使用 record。使用 MapStruct、MapStructPlus 和 egon-cola-component-common-core 的 BaseConverter（双向）或 BaseForwardConverter（不可逆投影）进行转换。
> 4业务类必须使用@Slf4j注解注入log对象。如果业务类被spring管理，必须指定名称，如果是单例的情况下，参考@Service("userService")。如果需要依赖注入，必须@RequiredArgsConstructor进行修饰，不要代码中写。且属性必须被@qualify修饰。
> 5 工具类只允许使用jdk原生、Apache Commons(commons-lang3、commons-collections4、commons-io、commons-text、commons-codec、commons-beanutils)、Guava。针对Tika按需引入。
> 6 JSON 使用 Spring Boot Jackson；持久化枚举值使用 @EnumValue，向前端输出的枚举值使用 @JsonValue，禁止 ordinal 作为业务编码。
> 7 springboot 多环境配置文件，必须保持配置一致，但值不一定一致。
> 9 复杂业务必须引入设计模式，不允许硬编码
> 10 日期相关的必须使用java.time下的实体类，不允许使用java.util下的
> 11 只允许现有三层结构或当前 egon-cola-archetypes 的 DDD 结构，三层结构保持现状。必须尽量复用 egon-cola-components；持久化模块必须使用 Egon COLA MP Starter，DDL 统一由 egon-mp-sdj-ext-starter 分布式管理。业务唯一键必须组合业务列与 deletedAt（数据库 deleted_at），并验证 NULL、租户及重复软删除语义。采用 CQE（Command Query Event）；Event 必须经 egon-cola-component-transactional-outbox-starter 或 MQ 中间件投递。

| Literal rule | Spec source | Repository evidence | Exact files and order | Implementation obligations | Validation gate | Steps | Status/blocker |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Rule 1 | §6.2/8/10 | Store command、PO、原 DAO/Converter | Step 1 类型先于 Store，Step 4 PO、Converter、DAO/XML | 命名与语义一致，Java/XML 不重复 DAO 类型 | 编译与文件清单 | Step 1/4/5 | PASS |
| Rule 2 | §6.2/9/10 | BaseValidator、ValidationUtils、MP 校验 | Step 1 命令及配置 → Step 4 DAO → Step 6 装配 | 各跨层 @Validated/@Valid，命令/技术模式分组，DAO 只验 shape，授权由 SQL 验 | L01/L04/L06 正负验证 | Step 1/4/6 | PASS |
| Rule 3 | §6.2/10 | OutboxRecord 与 OutboxMessagePO、OutboxMessageConverter | Step 1 值对象和载体 → Step 4 PO、转换器 | record 只作不可变键/租约；class 全套 Lombok；不可逆 PO 投影走 MapStruct + BaseForwardConverter | 编译与映射往返合同 | Step 1/4 | PASS |
| Rule 4 | §6.2/7/13 | lombok.config、现有命名 Bean | Step 5 Coordinator/Dispatcher → Step 6 AutoConfiguration | 行为类 @Slf4j；显式 Bean 名、@RequiredArgsConstructor(onConstructor_ = @Autowired)、依赖字段 @Qualifier；属性绑定排除 DI 规则 | 反射与启动测试、无敏感日志 | Step 5/6/7 | PASS |
| Rule 5 | §6.2/15 | Dispatcher、Clock/JDK 标准并发 | 配置 → Store → Coordinator → Micrometer | JDK SHA-256、集合和计时；不添加依赖 | POM 和 import 对比 | Step 1–7 | PASS |
| Rule 6 | §6.2/9/10 | OutboxStatus、OutboxHeadersConverter | Step 1 状态合同 → Step 3/4 能力序列化 | Boot Jackson 编码规范能力数组；@EnumValue 保留原状态字符串，无 ordinal/新 wire | L03/L04、TEST-006 | Step 1/3/4/5 | PASS |
| Rule 7 | §6.2/16 | TransactionalOutboxProperties、双语 README 治理 | Properties → 装配 → README.md、README.zh-CN.md | 同一属性类及环境键；默认安全模式需显式启用；无新的应用配置文件 | L06/L07，配置键集合 | Step 1/6/7 | PASS |
| Rule 9 | §6.2/13 | LifecycleService、DeliveryHandlerRegistry | 技术图 → 两种 Store → Coordinator/Dispatcher | State+Guard、能力 Registry、既有存储适配 | L01/L03/L04/L05 | Step 1/3/4/5 | PASS |
| Rule 10 | §6.2/6.3 | JDBC DB 时间、MP 审计字段 | Step 1 模型 → DDL/DAO → Dispatcher | UTC Instant、Duration、DB clock 与本地单调时钟分工 | §4.9、L03/L04/L05 | Step 1–6 | PASS |
| Rule 11 | §6.1/6.2/8 | Starter POM、OutboxManagedDdlInitializer、原事务 enqueue | §5 文件清单按 Step 1–7 | 原架构、MP 与受管 DDL，保留事务 CQE；技术行固定租户/无软删，原业务键语义保持 | L02/L06/L07 | Every Step | PASS |

### 4.9 Time semantics and conversion contract

Time contract: `Required`

| Time ID | Fields/symbols | Semantics | Java/runtime type | Storage policy | Wire policy | Business zone source | Display zone source | Boundary / DST rules | Precision / Clock | Compatibility | Verification |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| TIME-001 | 归属/成员 expiresAt、lastSeenAt、消息 lockedUntil/nextAttemptAt、技术审计 | Occurred instant | Instant，JDBC 边界 OffsetDateTime (UTC) | UTC instant | Internal | Not applicable: 技术时钟 | Not applicable: 无界面 | 截止等号过期；due 等号可领，无 DST 条件 | timestamptz(6)，目标主库 clock_timestamp()，持锁后比较 | 旧消息不转换或回填，MP deleted_at 固定 NULL | Step 2/3/4/6，TEST-003、TEST-004、TEST-005，L02–L06 |
| TIME-002 | claimTimeout、heartbeatInterval、ackReserve、轮询退避、Handler budget | Duration | Duration、System.nanoTime() 计耗时 | Not persisted | Internal | Not applicable: 相对持续量 | Not applicable: 无界面 | 扣除 claim/commit/移交耗时，余额不足不启动 handler | 配置毫秒，DB 授权用 TIME-001，本地用单调时间 | 保留原投递超时作为上界 | Step 1/5/6/7，TEST-001、TEST-004、TEST-008，L01/L05/L06/L07 |

## 5. Change File Tree

路径均相对于组件仓根。下表按首次修改顺序列出文件，每个路径仅登记一次。跨 Step 路径限 OutboxShardSqlGuardTest（Step 2 固定形态、Step 4 实际 Mapper XML）和两个读写分离集成测试（Step 2 拓扑夹具、Step 6 生产运行时场景）。其余路径只有一个归属。迁移版史和路由配套验证合并在 Step 2。

| Operation | Path | Current evidence/symbol | Final symbols/state | Responsibility | Step | Requirements | Validation owner |
| --- | --- | --- | --- | --- | --- | --- | --- |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/statemachine/OutboxLifecycleServiceTest.java` | 现有 evaluate、RECLAIM 和拒绝非法转换用例 | 新增 safeClaimBudget、legacyClaimCompatibility | 用同一技术图验证新安全领取与旧入口兼容 | Step 1 | REQ-004、REQ-005、REQ-006、REQ-008 | L01，TEST-005/006 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/TransactionalOutboxPropertiesTest.java` | 已有 Properties 配置测试 | 验证 Dispatch/Coordination 缺省值、旧键 | 证明缺省启动不变，覆盖绑定负例 | Step 1 | REQ-004、REQ-005、REQ-006、REQ-008 | L01，TEST-007 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/OutboxConfigurationValidatorTest.java` | 已有 delivery/polling/租约关系断言 | safeModeValidation | 覆盖边界等号与 LEGACY 兼容 | Step 1 | REQ-004、REQ-005、REQ-006、REQ-008 | L01，TEST-007 |
| CREATE | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxQueueKey.java` | OutboxShardRouting 与 DeliveryHandlerRegistry#channels | OutboxQueueKey | 新增可校验且稳定相等的分片通道键 | Step 1 | REQ-004、REQ-005、REQ-006、REQ-008 | L01，TEST-002/003 |
| CREATE | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxQueueLease.java` | OutboxWorkerIdentity 及 §11.2.2 | OutboxQueueLease | 建不变队列授权值，独立于 per-message token | Step 1 | REQ-004、REQ-005、REQ-006、REQ-008 | L01，TEST-005 |
| CREATE | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxQueueCoordinationCommand.java` | OutboxStore 现有 store 风格、§9.2.1 | OutboxQueueCoordinationCommand | 使用 Lombok Command，@Valid 嵌套 key/lease 并分离授权和参数校验 | Step 1 | REQ-004、REQ-005、REQ-006、REQ-008 | L01/L06，TEST-002/007 |
| CREATE | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxQueueCoordinationBO.java` | §9.2.1 与当前 Bo 模式 | OutboxQueueCoordinationBO | Lombok BO 携带提交证明和下一轮选举输入 | Step 1 | REQ-004、REQ-005、REQ-006、REQ-008 | L01/L06，TEST-002 |
| CREATE | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxClaimCommand.java` | OutboxStore#claimDue 旧约束、§9.2.2 | OutboxClaimCommand | 定义单 shard claim typed Command 和模式校验 | Step 1 | REQ-004、REQ-005、REQ-006、REQ-008 | L01/L03/L04，TEST-003/005 |
| CREATE | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxClaimBatchBO.java` | OutboxRecord 不增加字段，§9.2.2 | OutboxClaimBatchBO | BO 对 List 防御拷贝、附带数据库时间与分支 | Step 1 | REQ-004、REQ-005、REQ-006、REQ-008 | L01/L03/L04，TEST-004/005 |
| CREATE | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxFinishStatusEnum.java` | OutboxStatus 和 §9.2.3 | OutboxFinishStatusEnum | 内部 enum，无新增 wire 或 ordinal | Step 1 | REQ-004、REQ-005、REQ-006、REQ-008 | L01/L03/L04，TEST-004 |
| CREATE | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxFinishCommand.java` | DeliveryResult、OutboxRecord、§9.2.3 | OutboxFinishCommand | 新增 Lombok Command 和嵌套约束，传递原身份 | Step 1 | REQ-004、REQ-005、REQ-006、REQ-008 | L01/L03/L04，TEST-004 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxStore.java` | 现有 claim/mark 已通过 default overload 兼容 | coordinate、claim、finishDelivery、支持模式检查 | 提供二进制兼容 default 及所支持模式自描述 | Step 1 | REQ-004、REQ-005、REQ-006、REQ-008 | L01，TEST-007 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/statemachine/OutboxLifecycleStateMachineFactory.java` | 固定图、RETRY_WITH_BUDGET/RETRY_EXHAUSTED | CLAIM、RECLAIM、DELIVERY_RETRYABLE Guard | 实现 Spec 状态表，所有转换仍经 Spring Statemachine | Step 1 | REQ-004、REQ-005、REQ-006、REQ-008 | L01，TEST-005/006 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/statemachine/OutboxLifecycleService.java` | Map attemptCount/maxAttempts、StateMachineExecutionService | 现有 evaluate 与安全 claim 调用 | 技术判定一次，安全 claim 与原合同隔离 | Step 1 | REQ-004、REQ-005、REQ-006、REQ-008 | L01，TEST-005/006 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/TransactionalOutboxProperties.java` | 现有 Polling、Delivery、Retry 绑定前缀 | Dispatch、Coordination 属性与模式枚举 | 新增强类型节点和默认值，运行仍未切换 | Step 1 | REQ-004、REQ-005、REQ-006、REQ-008 | L01，TEST-007 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/OutboxConfigurationValidator.java` | 当前 BaseValidator 与 delivery lease 关系 | 安全配置 group 和 cross-field | 按 §16.1 精确拒绝无 poll、自动重驱及不安全预算 | Step 1 | REQ-004、REQ-005、REQ-006、REQ-008 | L01，TEST-007 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/store/OutboxMigrationContractTest.java` | 当前迁移合同与已有 SQL 路径 | managed DDL shape、旧资产校验 | 新增两种存储结构和旧 SQL 字节保护断言 | Step 2 | REQ-001、REQ-003、REQ-006、REQ-007 | L02，TEST-007 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/contract/ComponentContractGovernanceTest.java` | 固定两条 migration 的断言和双语 README 的提取器 | manifest、配置键同步及 Maven 合同 | 只扩历史及资源治理，键集合继续自动对比 | Step 2 | REQ-001、REQ-003、REQ-006、REQ-007 | L02/L07，TEST-007 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/migration/OutboxManagedDdlReadwriteTest.java` | 已有 schema readiness/primary 角色验证 | 合成两主拓扑、mock runner/replay | 分别断言 physical target role 和严格同 slot 映射 | Step 2 | REQ-001、REQ-003、REQ-006、REQ-007 | L02，TEST-003/007 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/migration/OutboxShardSqlGuardTest.java` | everyOutboxMapperStatementContainsAnExactShardKey | 新增等槽 SQL 形态与负例；真实三份 mapper 渲染测试 | 扩展 guard RED，Step 4 再接真实 XML 回归 | Step 2、Step 4 | REQ-001、REQ-003、REQ-006、REQ-007、REQ-002、REQ-004、REQ-005 | L02/L04，TEST-003；L04，TEST-003 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/migration/OutboxPrimaryReadPolicyTest.java` | 已有 canonical allowlist 正反例 | 精准主读列表 | 验证精确 allowlist 和 tenant/shard predicate | Step 2 | REQ-001、REQ-003、REQ-006、REQ-007 | L02，TEST-003 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/OutboxManagedDdlIntegrationTest.java` | appliesTheManifestOnceAndSkipsItOnRestart、现有失败回滚、fingerprint fixtures | 首次/重启、部分 DDL、老清单拒绝 | 加旧路由指纹、已安装顺序及故障注入，并查 PG 元数据 | Step 2 | REQ-001、REQ-003、REQ-006、REQ-007 | L02，TEST-007 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/PostgresqlOutboxTestSupport.java` | 类内固定 PRIMARY egon_outbox 表列表 | 共享 MP NATIVE PG 夹具初始化 | 使共用测试按新 schema 验证而非私建假表 | Step 2、Step 4 | REQ-001、REQ-003、REQ-006、REQ-007 | L02/L04 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/OutboxPostgresqlReadwriteIntegrationTest.java` | outboxTechnicalLocksShareTheBusinessTransactionWhileBusinessReadsUseTheReplica | 既有单主/单副本夹具拓扑；新增主库权威／副本角色测试 | 用同样 topology builder 加两表 | Step 2、Step 6 | REQ-001、REQ-003、REQ-006、REQ-007、REQ-002、REQ-004、REQ-005、REQ-008 | L02/L06，TEST-003；L06，TEST-003/007 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/OutboxPostgresqlMultishardIntegrationTest.java` | 现有 two-group TableProperties、claimScoped 辅助 SQL | 既有 atomicShardWritesAndConcurrentClaimsStayOnTheirSelectedPrimaries 夹具；真实 Store、Dispatcher、两个主组和降级回退 | 限本 Step 修改夹具和结构，不以旧辅助 SQL 宣称新行为验证 | Step 2、Step 6 | REQ-001、REQ-003、REQ-006、REQ-007、REQ-002、REQ-004、REQ-005、REQ-008 | L02/L06，TEST-003；L06，TEST-002/003/004/005/007 |
| CREATE | `stellar-enclosure-component-transactional-outbox-starter/src/main/resources/db/transactional-outbox/postgresql/V2__add_outbox_queue_coordination.sql` | V1__create_transactional_outbox_schema.sql 和 Spec §11.2 | JDBC 两张协调表 | 新增成员与归属表，消息表索引维持基线；成员 expiry 索引按 Spec 留待诊断 | Step 2 | REQ-001、REQ-003、REQ-006、REQ-007 | L02/L03，TEST-007 |
| CREATE | `stellar-enclosure-component-transactional-outbox-starter/src/main/resources/db/egon-outbox-mp/V20261122_001__add_outbox_queue_coordination.sql` | 旧 V20260924_001 继承模型与 V20261027_001 slot、Spec §11.2 | MP 两个技术表 | DDL 同一物理主目标追加两表 | Step 2 | REQ-001、REQ-003、REQ-006、REQ-007 | L02，TEST-003/007 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/resources/db/egon-outbox-mp/manifest.json` | 现有两个 manifest 项原样 | family component-outbox scripts 第 3 项 | 追加精确历史条目，并与对应 guard/DDL 一同提交 | Step 2 | REQ-001、REQ-003、REQ-006、REQ-007 | L02，TEST-007 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/migration/OutboxManagedDdlInitializer.java` | REQUIRED_TABLES 后缀当前全部要求 SINGLE | validateTransactionAndNodes、readiness、managed targets | 拆分消息协同表/历史表校验，沿用 DDL、主库和 replay 顺序 | Step 2 | REQ-001、REQ-003、REQ-006、REQ-007 | L02，TEST-003/007 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/OutboxSchemaMetadataValidator.java` | 当前精确 history 与物理元数据访问器 | validateHistoryPrefix 和协调结构校验 | 每个主目标验证二表、版本和路由，保留老历史保护 | Step 2 | REQ-001、REQ-003、REQ-006、REQ-007 | L02，TEST-007 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/JdbcOutboxSchemaValidator.java` | 当前 V1 与配置 schema 验证 | 协调 schema 只读验证 | 补字段、约束、锁用唯一索引查验入口 | Step 2 | REQ-001、REQ-003、REQ-006、REQ-007 | L02/L03，TEST-007 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/OutboxShardRouting.java` | 当前只校验 message，支持现有 fixture 构造 | 路由校验两个技术表 profile | 只扩当前路由证明，不引路由算法 | Step 2 | REQ-001、REQ-003、REQ-006、REQ-007 | L02/L04，TEST-003 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/migration/OutboxShardSqlGuard.java` | 当前单表 exact shard_slot/禁止 CTE等 | validate、固定协调读写 SQL | 扩固定形态，不打开 broad parser exception | Step 2 | REQ-001、REQ-003、REQ-006、REQ-007 | L02/L04，TEST-003 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/migration/OutboxPrimaryReadPolicy.java` | 只允许原锁/管理 SQL | permits 与 canonical approved reads | 对已知准确读形态登记最小条件 | Step 2、Step 4 | REQ-001、REQ-003、REQ-006、REQ-007 | L02/L04，TEST-003 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/migration/OutboxLogicalDataSourceFactory.java` | 用 initializer 就绪、现有两个策略 | validateLogicalSql、permitsPrimaryRead、enforceSingleWriteGroup | 挂接窄 guard 并验证现有 strict logical 模式 | Step 2 | REQ-001、REQ-003、REQ-006、REQ-007 | L02/L06，TEST-003 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/JdbcOutboxPostgresqlIntegrationTest.java` | 现有 JDBC PG fixture 和旧 Store contract | 新单库合同：claim/queue fence/finish、V1与V2初始化 | 原事务资产继续回归，新路径使用真实 PG 校验 lease 与 budget | Step 3 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006 | L03，TEST-003/004/005/006 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/JdbcOutboxStore.java` | 现有事务模板、SQL SKIP LOCKED、finish 与 DataSourceUtils | coordinate、claim、finishDelivery、legacy claim/mark guard | 实现成员续约/接管、共享行锁后选候选、逐行状态机后受限 UPDATE RETURNING、finish 权威锁与时间；不改旧 SPI | Step 3 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006 | L03，TEST-001/003/004/005/006/008 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/OutboxMpSharedContractIntegrationTest.java` | satisfies_the_shared_postgresql_store_contract 和 PG base | 安全 Store 合同 | 直接调用生产 Store，验证新合同与旧合同共存 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006 | L04，TEST-002/003/005 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/PostgresqlOutboxRecoveryIntegrationTest.java` | shouldRejectCompletionFromAnOldClaimToken 等现有断言 | 到期、耗尽、旧 token、ACK unknown | 新增安全模式恢复测试，保留原方法回归 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006 | L04，TEST-004/005/006 |
| CREATE | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/po/OutboxQueueMemberPO.java` | OutboxMessagePO 继承 EgonModel | OutboxQueueMemberPO | 新增协调表的技术 PO | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006 | L04，TEST-002/007 |
| CREATE | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/po/OutboxQueueLeasePO.java` | OutboxMessagePO 与 §11.2.2 | OutboxQueueLeasePO | 区分 MP version 与 queue epoch | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006 | L04，TEST-002/007 |
| CREATE | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/converter/OutboxQueueMemberConverter.java` | OutboxMessageConverter 与 OutboxHeadersConverter | OutboxQueueMemberConverter | 用 MapStruct + BaseForwardConverter 做规范化成员映射 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006 | L04，TEST-002/007 |
| CREATE | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/converter/OutboxQueueLeaseConverter.java` | 现有 OutboxMessageConverter | OutboxQueueLeaseConverter | 用 MapStruct + BaseForwardConverter 保留主库原值 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006 | L04，TEST-005 |
| CREATE | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/dao/OutboxQueueMemberDAO.java` | OutboxMessageDAO 的 Mapper + XML 命名空间 | upsert、完整快照、过期清理 | 按现有 package 扫描定义命令、shape 验证 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006 | L04，TEST-002/003 |
| CREATE | `stellar-enclosure-component-transactional-outbox-starter/src/main/resources/mapper/outbox/OutboxQueueMemberMapper.xml` | OutboxMessageMapper.xml、固定主读授权 | OutboxQueueMemberDAO statements | 绑定成员事务所需 SQL，不走物理绕路 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006 | L04，TEST-002/003 |
| CREATE | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/dao/OutboxQueueLeaseDAO.java` | 原 selectProcessingForUpdate 及 §11 技术锁 | guardLock、续约/夺权/释放 | 只暴露显式 slot 归属操作 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006 | L04，TEST-003/005 |
| CREATE | `stellar-enclosure-component-transactional-outbox-starter/src/main/resources/mapper/outbox/OutboxQueueLeaseMapper.xml` | 旧锁 SQL 和新 canonical allowlist | OutboxQueueLeaseDAO statements | 实现队列行守卫和条件写，保持精确主库读 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006 | L04，TEST-003/005 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/dao/OutboxMessageDAO.java` | 现有 selectDueCandidatesByChannels、tryAcquireTransactionLock、version 方法 | safe due/reclaim、更新与确认方法 | 声明固定语句的绑定和结果投影 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006 | L04，TEST-003/005/006 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/resources/mapper/outbox/OutboxMessageMapper.xml` | 现有固定 statement 与 idx_outbox_claim/reclaim | 安全候选、锁、条件更新和回读 | 扩安全语句并保留 LEGACY SQL | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006 | L04，TEST-003/005/008 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/repository/OutboxMessageRepository.java` | 现有 getBaseMapper 和 requireAtMostOne | safe 候选与 ACK 转发 | 沿用现有 Repository 接通新增固定消息操作 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006 | L04，TEST-003/005 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/MybatisPlusOutboxStore.java` | OutboxShardContext、selectProcessingForUpdate、原 workerTransaction | coordinate、claim、finishDelivery、旧入口兼容 | 实现三个安全契约和消息结果确认、MP 默认批 1 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006 | L04，TEST-003/004/005/006 |
| CREATE | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/dispatch/OutboxQueueCoordinatorTest.java` | DispatcherTest/PollerTest 受控时间模式 | ownerSelection、staleEpoch、gracefulHandover | 加入确定性选主和故障接管测试 | Step 5 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006、REQ-008 | L05，TEST-002/005 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/dispatch/OutboxDispatcherTest.java` | 现有 runCoordinator、mark 路径测试 | 4+1、A commit/B fail、窄 delivery 验证 | 用 barrier 验 wake、claim、handoff、handler、finish | Step 5 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006、REQ-008 | L05，TEST-001/003/004/006 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/dispatch/OutboxPollerTest.java` | 固定延时/jitter 测试 | 周期、ready 唤醒与 shard 退避 | 加补扫及停止验证，旧轮询保持 | Step 5 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006、REQ-008 | L05，TEST-001/002 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/observability/OutboxMetrics.java` | Noop、Micrometer、自定义接口 | 默认阶段与队列事件回调 | 提供增量默认方法，旧实现不用修改 | Step 5 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006、REQ-008 | L05/L07，TEST-008 |
| CREATE | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/dispatch/OutboxQueueCoordinator.java` | WorkerIdentity#canClaim、registry 与 §9.2.1 | 注册、续租、接管、释放与停止 | 协调缓存仅作提示，资格依据事务确认，不加入外部服务 | Step 5 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006、REQ-008 | L05，TEST-002/005 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/delivery/statemachine/BusinessStateMachineDeliveryHandler.java` | validateDestination 和 deliver 的现有 retry-on-missing-definition | deliver 的 envelope 与查定义先后 | 只改内置交付校验次序 | Step 5 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006、REQ-008 | L05，TEST-006 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/dispatch/OutboxDispatcher.java` | 原 deliveryPermits 与 applyResult | submitDue、submitMessageIds、协调循环、单分片 handoff、finish | 实现 drain、事件唤醒/fairness、A/B 交付隔离、经 Spring 代理识别内置 handler | Step 5 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006、REQ-008 | L05，TEST-001/002/003/004/005/006 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/dispatch/OutboxPoller.java` | 现有 TaskScheduler与 jitter | 周期唤醒、启动/停止 | 让失去通知的消息在下一可用轮被发现 | Step 5 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006、REQ-008 | L05，TEST-001/002 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/TransactionalOutboxAutoConfigurationTest.java` | preservesTransactionAdviceOnDeliveryHandlersWhenAnInfrastructureConsumerCreatesTheAspectEarly | 安全模式、同版 LOCAL、lazy advice 与只生产 | 扩配置矩阵并保留 d3f5f9c 的 handler 事务 advice 回归 | Step 6 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006、REQ-007、REQ-008 | L06，TEST-007 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/OutboxOptionalAutoConfigurationTest.java` | 已有 optional transports、poller 生命周期夹具 | 暂停、旧扩展及 polling.enabled 组合 | 证明老配置、可选 Beans 不破坏安全门 | Step 6 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006、REQ-007、REQ-008 | L06，TEST-001/007 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/OutboxJdbcAutoConfigurationTest.java` | 原 outboxStore 及 infra Test | JDBC V1/V2 验证和 worker 模式 | 增加模式/暂停/失败配置测试 | Step 6 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006、REQ-007、REQ-008 | L06，TEST-003/007 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/OutboxMybatisPlusAutoConfigurationTest.java` | validateStoreInfrastructure 及合成 profile 测试 | 新技术 ignore、MapperScan 和 readiness | 覆盖 legacy 兼容与严格门 | Step 6 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006、REQ-007、REQ-008 | L06，TEST-003/007 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/OutboxBusinessStateMachineAutoConfigurationTest.java` | 现有 adapter 和业务 state machine wiring | 已有 adapter / 严格 producer 兼容验证 | 证明 Dispatcher 的窄分流能在真实 Spring proxy 下生效 | Step 6 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006、REQ-007、REQ-008 | L06，TEST-006 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/OutboxJdbcAutoConfiguration.java` | 当前创建本地 REQUIRES_NEW 模板和 JDBC schema validator | outboxStore 模式参数、V2 readiness 和 DB 预算 | 接通两安全契约及消费闸门，保留旧自定义条件 | Step 6 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006、REQ-007、REQ-008 | L06，TEST-003/007 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/OutboxMybatisPlusAutoConfiguration.java` | 现有 @MapperScan、logical factory、named transaction | validateStoreInfrastructure、outboxStore 与 qualifier | 装配安全模式的真实逻辑链，LEGACY 无运行时协调 | Step 6 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006、REQ-007、REQ-008 | L06，TEST-003/007 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/TransactionalOutboxAutoConfiguration.java` | 现有 named executor、dispatcher、transactionalMessageAop | outboxDispatcher、scheduler、executor、监听连接与 pause | 只有验证完毕才连通安全路径，保留老 Bean 路径与生产 CQE | Step 6 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006、REQ-007、REQ-008 | L06，TEST-001/002/007 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/observability/MicrometerOutboxMetricsTest.java` | 现有 registry 指标测试 | 新增阶段指标／标签断言 | 区分 ACK unknown 和 handler 成功，验证 no-op fallback | Step 7 | REQ-002、REQ-003、REQ-005、REQ-006、REQ-007、REQ-008 | L07，TEST-008 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/PostgresqlOutboxQueryPlanIntegrationTest.java` | shouldExposeRequiredSchemaAndUseClaimIndexes | 候选计划与 SQL/行放大基线 | 扩现有真实性能调用，详细预发采样见 §8.3 | Step 7 | REQ-002、REQ-003、REQ-005、REQ-006、REQ-007、REQ-008 | L07，TEST-008 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/observability/MicrometerOutboxMetrics.java` | 原 Micrometer MeterRegistry 与命名 | 实现 Step 5 默认阶段回调 | 接好分阶段统计、重领与拒绝诊断，既有 metrics API 继续可用 | Step 7 | REQ-002、REQ-003、REQ-005、REQ-006、REQ-007、REQ-008 | L07，TEST-008 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/README.md` | 当前 Config 表、PostgreSQL readwrite/multishard 章节 | Dispatch Config、真实 MP 环境和发布回退 | 按 §8、§9 同步英文配置/验收与回退步骤 | Step 7 | REQ-002、REQ-003、REQ-005、REQ-006、REQ-007、REQ-008 | L07，TEST-007/008 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/README.zh-CN.md` | 现有中文 Options、集成验收段 | 同英文配置键、MP gate、回退 | 补齐相同配置键及中文演练步骤 | Step 7 | REQ-002、REQ-003、REQ-005、REQ-006、REQ-007、REQ-008 | L07，TEST-007/008 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/contract/ComponentContractGovernanceTest.java` | englishAndChineseReadmesDocumentTheSameCqeMpAndMigrationKeys | 更新十项安全模式配置键的精确集合 | 执行中维持双语键集合和原键治理 | Step 7 | REQ-007 | L07，TEST-007/008 |
| MODIFY | `stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/OutboxTechnicalContextExecutor.java` | 现有 executeWithUser 与技术租户精确 Mapper 授权 | 新成员、归属调用分别进入自身授权 | 复用 tenant 0 和 MDC 清理 | Step 4 | REQ-001、REQ-003、REQ-004 | L04/MPI-02 |




## 6. Prerequisites, Constraints, and Plan Clarifications

### 6.1 Repository and worktree baseline

组件仓 `main` 当前为 `d3f5f9cc989a01e054aed6b3982df9366f1b842a`、领先远端 2 个提交。已有未跟踪 Spec；仅 Plan 与该 Spec 回链属于本轮写入范围。遵循会话提供的 AGENTS.md，工作区根、拥有仓和模块路径已检查；复用当前已有技术组件边界。执行前以 `git status --short --branch` 和两份文档 hash 对照，已存在用户改动保持原样。

### 6.2 Build, test, and environment prerequisites

| Concern | Exact command/source | Required state | Validation boundary |
| --- | --- | --- | --- |
| Build tool | 仓根 `./mvnw -v`；仓根 POM 的 Java 25、组件 POM 的 Surefire/Failsafe | 能解析现有依赖，保持 JDK 25 与仓库当前版本 | 编译通过不代表 PG 路由 |
| Container PG | 组件 README 的 `EGON_OUTBOX_TEST_POSTGRES_ENABLED=true`，`PostgresqlOutboxTestSupport` | 隔离 PG 16.6 Testcontainer（或测试类明确支持的隔离 loopback PG） | L02–L04、L07；无真实 2×2 假设 |
| 单组读写分离、两主各一副本 | §8.2 显式 opt-in 和外部专用 endpoint | 拥有测试 schema 和业务表的创建/清理权限，现有身份校验按需 `pg_control_system()`；验证 primary/replica 和两组数据独立 | 目标必须为空/专用。现有测试会清理自己创建的对象；生产库不使用该命令 |
| 发布验证 | 最终制品 SHA、每组路由指纹、schema 版史、预发压力证据 | 与 §9 的变更窗口、暂停权限和配置审批同时成立 | 编写 Plan 已完成静态证据核对；运行和发布是将来的验收 |

### 6.3 Immutable constraints and approved decisions

- 保留发布过的 MP `V20260924_001__initialize_outbox_mp_schema.sql`、`V20261027_001__add_outbox_shard_slot.sql` 及 JDBC V1 原字节。新 MP DDL 与 history 按同一主目标管理，禁止为兼容旧 Jar 删除记录。
- 原 producer 与业务更新使用宿主选择的同一事务；原消息 ID、dedupe、envelope、公开 handler、数据库五态、技术信号码和业务 receipt 契约保留。只将技术状态判定移进权威 Store 行；状态机仍强制。生产者不参与队列归属。
- 准入参数以 Spec §16.1 为源。默认 LEGACY 兼容；LOCAL/COORDINATED 启用消费均需 `polling.enabled=true`、明确能力、有效路由和 `retry.recover-exhausted=false`。MP 首发上限 1。新模式暂停通过 `dispatch.consumption-enabled=false` 同时关闭入口，保留合法在途 finish。
- 技术布局为 `egon_outbox` 下 MP 三个逻辑业务技术表同 slot、history SINGLE；JDBC 沿用所选 schema 和 slot 0。运行态禁止使用物理 metadata datasource 或放开通用 CTE。MP host 新表的 STRATEGY/tenant-ignore 配置和 JDBC V2 由实际部署方按已审核配置切换，本计划的代码提交限组件仓。

### 6.4 Plan Clarifications

| ID | Small implementation inference | Repository evidence | Why semantics are unchanged | Impact if wrong |
| --- | --- | --- | --- | --- |
| PLAN-CLAR-001 | XML 的 Java namespace 沿用 DAO，更新现有 OutboxMessageRepository 桥接 | `OutboxMessageMapper.xml` namespace 指向 `OutboxMessageDAO`；MP Store 经 `OutboxMessageRepository` 使用精确 mapper | 仅将 Spec §8 的 mapper/DAO 位置落实为真实包结构，维持同事务 | 若放错包，映射或 SqlSessionFactory 启动检查失败 |
| PLAN-CLAR-002 | 沿用合成拓扑用例作 SQL shape 回归，真实 MP 另加生产路径用例 | `OutboxPostgresqlMultishardIntegrationTest#claimScoped` 只是辅助 SQL，README 真实组单独 opt-in | 明确缺口和验证对象，不改变 Spec 的路由或运行契约 | 未覆盖真实执行，MP 放量门无法闭合 |
| PLAN-CLAR-003 | 两张技术表的 MP 脚本、指纹、拓扑验证和 manifest 同 Step 落地 | `OutboxSchemaMetadataValidator#validateHistoryPrefix` 读取制品全量清单，无按 mode 延迟解释 | 源码提交顺序安排，部署仍走 §9 统一切换 | 拆开发版会导致缺结构或旧 Jar 拒绝启动 |
| PLAN-CLAR-004 | 带预算的安全 CLAIM 在现有生命周期 service→factory 独立入口 | 原历史 Store 调 evaluate | 同一技术图保留 LEGACY | 由 L01 证实 |
| PLAN-CLAR-005 | 新持久化映射各持专用的零租户 namespace 权限，并在同一主库事务中按具体 DAO 顺序进入/退出 | Common EgonColaTechnicalTenantScope 禁止嵌套切换 Mapper，现有 Executor 仅授权消息 Mapper | 技术 tenant 与数据库范围保持，复用原资格守卫并限定两种表 | 未接入将触发技术租户拦截；不可豁免 |

## 7. Ordered File-by-file Implementation Steps

以下命令和提交仅描述批准实施后的动作。工作目录统一为组件仓根，所有 Step 源码提交都在核对路径和手工检查后进行。Step 1–5 若新签名或新类是测试编译的前置，可先完成最小签名再执行本 Step RED，验证报告必须区分未实现的业务失败和编译错误。

### Step 1 — 固定 Store 契约、配置与技术状态机边界

- Requirements: REQ-004、REQ-005、REQ-006、REQ-008
- Dependencies: 用户批准实施；当前源码 d3f5f9c
- Baseline state: 既有公开 SPI 可用，新模式尚无合约。
- Observable outcome: 新契约可编译，历史默认分支保持，安全 claim 有预算边。
- End state: 新 Store 接口默认明确不支持安全模式；状态机、属性与验证就绪，运行时入口未启用。
- Test-first gate: Required — 到期 PENDING/RETRY_WAIT 的耗尽转换未建立；无新模式交叉校验。测试方法先写，缺类型才先补可编译签名，完成后执行同一选择器收集 GREEN。
- Manual Checks: MC-ARCH-001, MC-REUSE-001, MC-DEP-001, MC-NAME-001, MC-VALID-001, MC-MODEL-001, MC-LOG-001, MC-BEAN-001, MC-UTIL-001, MC-TIME-001, MC-CONFIG-001, MC-PATTERN-001, MC-SCOPE-001, MC-TEST-001。
- Literal Rules: Rule 1, Rule 2, Rule 3, Rule 4, Rule 6, Rule 7, Rule 9, Rule 10, Rule 11。
- Ordered files:

#### File 1 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/statemachine/OutboxLifecycleServiceTest.java`

- Purpose: 用同一技术图验证新安全领取与旧入口兼容。
- Symbols: 新增 safeClaimBudget、legacyClaimCompatibility。
- Repository evidence: 现有 evaluate、RECLAIM 和拒绝非法转换用例。
- Dependencies and consumers: Factory、Service、旧 Store。
- Why now: 旧契约先可运行。
- Contract/signature changes: PENDING/RETRY_WAIT 两种边界及旧入口。
- Input/output and state mapping: attempt=max−1 新模式 PROCESSING，=max DEAD。
- Error and edge behavior: 未知状态、负预算、错误信号仍拒绝。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 用同一技术图验证新安全领取与旧入口兼容。
- Verification contribution: L01，TEST-005/006。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 新增断言在安全语义缺失时 RED。

#### File 2 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/TransactionalOutboxPropertiesTest.java`

- Purpose: 证明缺省启动不变，覆盖绑定负例。
- Symbols: 验证 Dispatch/Coordination 缺省值、旧键。
- Repository evidence: 已有 Properties 配置测试。
- Dependencies and consumers: Binding、旧消费者。
- Why now: 在新 Binder 正式接入之前。
- Contract/signature changes: §16.1 所有新键与已有 polling、delivery、retry。
- Input/output and state mapping: LEGACY/true/1/2/1s 与 5s/20s/30s/2s/1h。
- Error and edge behavior: 自定义旧配置保持效果。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 证明缺省启动不变，覆盖绑定负例。
- Verification contribution: L01，TEST-007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 仅验证配置，不启用 runtime。

#### File 3 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/OutboxConfigurationValidatorTest.java`

- Purpose: 覆盖边界等号与 LEGACY 兼容。
- Symbols: safeModeValidation。
- Repository evidence: 已有 delivery/polling/租约关系断言。
- Dependencies and consumers: BaseValidator、配置 class。
- Why now: 固定配置启用限制。
- Contract/signature changes: LOCAL/COORDINATED active 分组。
- Input/output and state mapping: 类别、批上限、DB 时长关系与 §16.1。
- Error and edge behavior: polling=false、recoverExhausted=true、租约不容 ACK 早拒；pause 场景不误阻断生产。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 覆盖边界等号与 LEGACY 兼容。
- Verification contribution: L01，TEST-007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 安全模式参数失败为 RED，非 fixture 错误。

#### File 4 — `CREATE stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxQueueKey.java`

- Purpose: 新增可校验且稳定相等的分片通道键。
- Symbols: OutboxQueueKey。
- Repository evidence: OutboxShardRouting 与 DeliveryHandlerRegistry#channels。
- Dependencies and consumers: 所有新 Store 命令、Coordinator。
- Why now: 键先于 token。
- Contract/signature changes: 不可变 slot 和 channel。
- Input/output and state mapping: slot ≥0，channel 非空，至多 64 字符。
- Error and edge behavior: JDBC slot 0 由 Store 校验，空值拒绝。
- Standards impact: MC-NAME-001, MC-VALID-001, MC-MODEL-001。Jakarta 约束、@Valid 或不可变性；复用原包契约。既有调用/ABI 兼容，禁止 ordinal；不增加 Maven 依赖。
- Literal rule enforcement: Rule 1, Rule 2, Rule 3, Rule 6, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 新增可校验且稳定相等的分片通道键。
- Verification contribution: L01，TEST-002/003。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 具备统一索引键。

#### File 5 — `CREATE stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxQueueLease.java`

- Purpose: 建不变队列授权值，独立于 per-message token。
- Symbols: OutboxQueueLease。
- Repository evidence: OutboxWorkerIdentity 及 §11.2.2。
- Dependencies and consumers: OutboxClaimCommand 与协调返回。
- Why now: 先定义 epoch token。
- Contract/signature changes: key、instanceId、epoch、expiresAt。
- Input/output and state mapping: instanceId ≤128，epoch >0，Instant。
- Error and edge behavior: 无缺省归属；缺字段拒绝，权威有效期由 DB 比较。
- Standards impact: MC-NAME-001, MC-VALID-001, MC-MODEL-001。Jakarta 约束、@Valid 或不可变性；复用原包契约。既有调用/ABI 兼容，禁止 ordinal；不增加 Maven 依赖。
- Literal rule enforcement: Rule 1, Rule 2, Rule 3, Rule 6, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 建不变队列授权值，独立于 per-message token。
- Verification contribution: L01，TEST-005。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 队列 lease 类型可校验。

#### File 6 — `CREATE stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxQueueCoordinationCommand.java`

- Purpose: 使用 Lombok Command，@Valid 嵌套 key/lease 并分离授权和参数校验。
- Symbols: OutboxQueueCoordinationCommand。
- Repository evidence: OutboxStore 现有 store 风格、§9.2.1。
- Dependencies and consumers: Coordinator 与两种 Store。
- Why now: 提交前先约束入口。
- Contract/signature changes: slot、instanceId、channels、renew/acquire/release 及 drain。
- Input/output and state mapping: 完整能力去重、排序、上限 128，动作绑定同 shard 和本实例。
- Error and edge behavior: 拒绝冲突意图、非法 slot/token、截断 capability。
- Standards impact: MC-NAME-001, MC-VALID-001, MC-MODEL-001。Jakarta 约束、@Valid 或不可变性；复用原包契约。既有调用/ABI 兼容，禁止 ordinal；不增加 Maven 依赖。
- Literal rule enforcement: Rule 1, Rule 2, Rule 3, Rule 6, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 使用 Lombok Command，@Valid 嵌套 key/lease 并分离授权和参数校验。
- Verification contribution: L01/L06，TEST-002/007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: Store 有精确协调入参。

#### File 7 — `CREATE stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxQueueCoordinationBO.java`

- Purpose: Lombok BO 携带提交证明和下一轮选举输入。
- Symbols: OutboxQueueCoordinationBO。
- Repository evidence: §9.2.1 与当前 Bo 模式。
- Dependencies and consumers: Store 回执给 Coordinator。
- Why now: 输入后定义结果。
- Contract/signature changes: 同 shard 完整成员快照、请求队列状态、DB sample。
- Input/output and state mapping: 不可变快照副本，持有结果含本实例正 epoch。
- Error and edge behavior: 空/无权为正常；视图超限是一类失败。
- Standards impact: MC-NAME-001, MC-VALID-001, MC-MODEL-001。Jakarta 约束、@Valid 或不可变性；复用原包契约。既有调用/ABI 兼容，禁止 ordinal；不增加 Maven 依赖。
- Literal rule enforcement: Rule 1, Rule 2, Rule 3, Rule 6, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: Lombok BO 携带提交证明和下一轮选举输入。
- Verification contribution: L01/L06，TEST-002。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 协调方不依赖截断视图。

#### File 8 — `CREATE stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxClaimCommand.java`

- Purpose: 定义单 shard claim typed Command 和模式校验。
- Symbols: OutboxClaimCommand。
- Repository evidence: OutboxStore#claimDue 旧约束、§9.2.2。
- Dependencies and consumers: Dispatcher 和两个 Store。
- Why now: 固定容量授权。
- Contract/signature changes: queueKey、limit、queueLease、claimToken、leaseDuration、两类配额。
- Input/output and state mapping: limit 受预约、config；leaseDuration 正，token≤128；epoch 条件匹配。
- Error and edge behavior: LOCAL 可空 queueLease；协调必填；拒跨键、超限。
- Standards impact: MC-NAME-001, MC-VALID-001, MC-MODEL-001。Jakarta 约束、@Valid 或不可变性；复用原包契约。既有调用/ABI 兼容，禁止 ordinal；不增加 Maven 依赖。
- Literal rule enforcement: Rule 1, Rule 2, Rule 3, Rule 6, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 定义单 shard claim typed Command 和模式校验。
- Verification contribution: L01/L03/L04，TEST-003/005。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 下游不需要再推断容量或 slot。

#### File 9 — `CREATE stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxClaimBatchBO.java`

- Purpose: BO 对 List 防御拷贝、附带数据库时间与分支。
- Symbols: OutboxClaimBatchBO。
- Repository evidence: OutboxRecord 不增加字段，§9.2.2。
- Dependencies and consumers: Store 与 Dispatcher。
- Why now: 提交结果边界。
- Contract/signature changes: 确认批次、DB 时间、not-owner/empty、耗尽数。
- Input/output and state mapping: 记录 deadline、attempt、token 与原 shard/id，0..limit。
- Error and edge behavior: 过期耗尽不作为可执行；unknown 不伪装空。
- Standards impact: MC-NAME-001, MC-VALID-001, MC-MODEL-001。Jakarta 约束、@Valid 或不可变性；复用原包契约。既有调用/ABI 兼容，禁止 ordinal；不增加 Maven 依赖。
- Literal rule enforcement: Rule 1, Rule 2, Rule 3, Rule 6, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: BO 对 List 防御拷贝、附带数据库时间与分支。
- Verification contribution: L01/L03/L04，TEST-004/005。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 可验证完成批次，旧 record ABI 保留。

#### File 10 — `CREATE stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxFinishStatusEnum.java`

- Purpose: 内部 enum，无新增 wire 或 ordinal。
- Symbols: OutboxFinishStatusEnum。
- Repository evidence: OutboxStatus 和 §9.2.3。
- Dependencies and consumers: finish、dispatcher/metrics。
- Why now: 先固化输出态。
- Contract/signature changes: APPLIED、ALREADY_TERMINAL、LEASE_LOST、INDETERMINATE。
- Input/output and state mapping: 按提交确实、终态已有、越权与未知分类。
- Error and edge behavior: 未确认不能报告 APPLIED；不持久化。
- Standards impact: MC-NAME-001, MC-VALID-001, MC-MODEL-001。Jakarta 约束、@Valid 或不可变性；复用原包契约。既有调用/ABI 兼容，禁止 ordinal；不增加 Maven 依赖。
- Literal rule enforcement: Rule 1, Rule 2, Rule 3, Rule 6, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 内部 enum，无新增 wire 或 ordinal。
- Verification contribution: L01/L03/L04，TEST-004。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 确认结果可独立诊断。

#### File 11 — `CREATE stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxFinishCommand.java`

- Purpose: 新增 Lombok Command 和嵌套约束，传递原身份。
- Symbols: OutboxFinishCommand。
- Repository evidence: DeliveryResult、OutboxRecord、§9.2.3。
- Dependencies and consumers: Dispatcher 与两个 Store。
- Why now: 确认类型齐备后定义输入。
- Contract/signature changes: slot、id、messageId、attemptCount、claimToken、deliveryResult。
- Input/output and state mapping: 来源为原 claim 回执和 handler 的现有结果。
- Error and edge behavior: 正 id/attempt，非空 token 和结果；无可信调用方时 SQL 拒写。
- Standards impact: MC-NAME-001, MC-VALID-001, MC-MODEL-001。Jakarta 约束、@Valid 或不可变性；复用原包契约。既有调用/ABI 兼容，禁止 ordinal；不增加 Maven 依赖。
- Literal rule enforcement: Rule 1, Rule 2, Rule 3, Rule 6, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 新增 Lombok Command 和嵌套约束，传递原身份。
- Verification contribution: L01/L03/L04，TEST-004。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 返回与命令编译齐备。

#### File 12 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxStore.java`

- Purpose: 提供二进制兼容 default 及所支持模式自描述。
- Symbols: coordinate、claim、finishDelivery、支持模式检查。
- Repository evidence: 现有 claim/mark 已通过 default overload 兼容。
- Dependencies and consumers: 两个 Store、Dispatcher、旧自定义 Store。
- Why now: 公开 SPI 演进需先发布签名。
- Contract/signature changes: Spec §9 的三个 validated 入口，能力缺省仅旧路径。
- Input/output and state mapping: 新入口接收上述 Command，结果返回对应 BO/enum。
- Error and edge behavior: 默认明确 unsupported；选择安全模式不自动退旧；旧方法签名不变。
- Standards impact: MC-NAME-001, MC-VALID-001, MC-MODEL-001。Jakarta 约束、@Valid 或不可变性；复用原包契约。既有调用/ABI 兼容，禁止 ordinal；不增加 Maven 依赖。
- Literal rule enforcement: Rule 1, Rule 2, Rule 3, Rule 6, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 提供二进制兼容 default 及所支持模式自描述。
- Verification contribution: L01，TEST-007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 旧实现可编译，新实现可逐步接入。

#### File 13 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/statemachine/OutboxLifecycleStateMachineFactory.java`

- Purpose: 实现 Spec 状态表，所有转换仍经 Spring Statemachine。
- Symbols: CLAIM、RECLAIM、DELIVERY_RETRYABLE Guard。
- Repository evidence: 固定图、RETRY_WITH_BUDGET/RETRY_EXHAUSTED。
- Dependencies and consumers: OutboxLifecycleService。
- Why now: 先落图后服务分流。
- Contract/signature changes: 设立安全 claim 的预算头与 transition。
- Input/output and state mapping: 现有 code 不变；有预算且 due 到 PROCESSING，耗尽到 DEAD。
- Error and edge behavior: 复用 RECLAIM 预算；避免默认路径出现重叠 guard。
- Standards impact: MC-PATTERN-001, MC-VALID-001, MC-LOG-001。复用 Spring Statemachine State+Guard、原五态和信号码；@Slf4j、Qualified 构造注入；锁外不产生副作用。
- Literal rule enforcement: Rule 2, Rule 4, Rule 6, Rule 9, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 实现 Spec 状态表，所有转换仍经 Spring Statemachine。
- Verification contribution: L01，TEST-005/006。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 技术图可在新旧入口区分运行。

#### File 14 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/statemachine/OutboxLifecycleService.java`

- Purpose: 技术判定一次，安全 claim 与原合同隔离。
- Symbols: 现有 evaluate 与安全 claim 调用。
- Repository evidence: Map attemptCount/maxAttempts、StateMachineExecutionService。
- Dependencies and consumers: 两种 Store；生产 ENQUEUE 保留。
- Why now: 工厂后连入适当 header。
- Contract/signature changes: 保留旧签名；安全模式明确选择技术 guard。
- Input/output and state mapping: 锁定行的 status/attempt/max 输入，只返回目标 enum。
- Error and edge behavior: 拒绝未知态和非法边；无 SQL 或 I/O。
- Standards impact: MC-PATTERN-001, MC-VALID-001, MC-LOG-001。复用 Spring Statemachine State+Guard、原五态和信号码；@Slf4j、Qualified 构造注入；锁外不产生副作用。
- Literal rule enforcement: Rule 2, Rule 4, Rule 6, Rule 9, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 技术判定一次，安全 claim 与原合同隔离。
- Verification contribution: L01，TEST-005/006。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 状态 RED 转 GREEN。

#### File 15 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/TransactionalOutboxProperties.java`

- Purpose: 新增强类型节点和默认值，运行仍未切换。
- Symbols: Dispatch、Coordination 属性与模式枚举。
- Repository evidence: 现有 Polling、Delivery、Retry 绑定前缀。
- Dependencies and consumers: OutboxConfigurationValidator、Step 6 装配。
- Why now: 先声明类型和缺省。
- Contract/signature changes: §16.1 的所有键及 LEGACY/LOCAL/COORDINATED。
- Input/output and state mapping: Duration 秒级缺省，batch 与 max shards 正整数。
- Error and edge behavior: 保留旧 queue-capacity，active 模式由 Validator 交叉校验。
- Standards impact: MC-BEAN-001, MC-CONFIG-001, MC-VALID-001。配置 POJO 绑定，Jakarta @Validated/@Valid、分类与边界校验；Boot 配置键共用。新增 DI 保持 Bean 名、@Qualifier 和 @RequiredArgsConstructor(onConstructor_ = @Autowired)。
- Literal rule enforcement: Rule 2, Rule 3, Rule 4, Rule 7, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 新增强类型节点和默认值，运行仍未切换。
- Verification contribution: L01，TEST-007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 绑定可编译，兼容老配置。

#### File 16 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/OutboxConfigurationValidator.java`

- Purpose: 按 §16.1 精确拒绝无 poll、自动重驱及不安全预算。
- Symbols: 安全配置 group 和 cross-field。
- Repository evidence: 当前 BaseValidator 与 delivery lease 关系。
- Dependencies and consumers: Step 6 的 safe 模式装配。
- Why now: 配置模型已可绑定。
- Contract/signature changes: active safe group，@Valid 验子配置。
- Input/output and state mapping: 短事务、heartbeat、TTL、ACK reserve 和既有 lease 关系。
- Error and edge behavior: 忽略纯 producer 看不到 Ready 的限制但仍验键形态；不改变 LEGACY。
- Standards impact: MC-BEAN-001, MC-CONFIG-001, MC-VALID-001。配置 POJO 绑定，Jakarta @Validated/@Valid、分类与边界校验；Boot 配置键共用。新增 DI 保持 Bean 名、@Qualifier 和 @RequiredArgsConstructor(onConstructor_ = @Autowired)。
- Literal rule enforcement: Rule 2, Rule 3, Rule 4, Rule 7, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 按 §16.1 精确拒绝无 poll、自动重驱及不安全预算。
- Verification contribution: L01，TEST-007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 本 Step 全部 GREEN。

- Validation working directory: `/Users/mario/SelfProject/Stellar-Enclosure/Stellar-Enclosure-Components`。
- Verification command: `./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dtest=OutboxLifecycleServiceTest,OutboxConfigurationValidatorTest,TransactionalOutboxPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test`。见 §8 的 L01。
- Expected result: 有效环境下命名断言全部通过，新增结果/side effects 与本 Step 成功条件一致。PG opt-in 有效用例的 skipped 必须为 0。
- Failure returns to: 先核对所选 fixture、参数和真实目标；状态、SQL、路由或装配不符按 §8 L01 回相应文件。缺环境标记为未验收。
- Completion criteria: 新契约可编译，历史默认分支保持，安全 claim 有预算边。保留测试输出、必要的 schema/路由证据，检查本 Step 暂存路径、契约和已有场景。
- Rollback: 未部署时回退本 Step 一次语义提交。任何已应用新 MP 历史的环境按 §9.4 保留新制品/schema。
- Commit paths: `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/statemachine/OutboxLifecycleServiceTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/TransactionalOutboxPropertiesTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/OutboxConfigurationValidatorTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxQueueKey.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxQueueLease.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxQueueCoordinationCommand.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxQueueCoordinationBO.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxClaimCommand.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxClaimBatchBO.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxFinishStatusEnum.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxFinishCommand.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/OutboxStore.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/statemachine/OutboxLifecycleStateMachineFactory.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/statemachine/OutboxLifecycleService.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/TransactionalOutboxProperties.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/OutboxConfigurationValidator.java`。
- Commit: `feat(outbox): define safe dispatch contracts and lifecycle guards`。

### Step 2 — 交付加法 DDL、精准路由和启动校验

- Requirements: REQ-001、REQ-003、REQ-006、REQ-007、REQ-010（历史受管 DDL 保留回归）
- Dependencies: Step 1
- Baseline state: MP 两个已发布版史，JDBC V1，原固定路由。
- Observable outcome: 新技术表与消息同一主分片；所有物理目标结构/版史核对。
- End state: 合并后的 manifest、路由、guard、夹具一同可用；独立生产发版仍未获准。
- Test-first gate: Required — 旧 manifest 无新版本，当前校验只支持消息+history。测试方法先写，缺类型才先补可编译签名，完成后执行同一选择器收集 GREEN。
- Manual Checks: MC-ARCH-001, MC-REUSE-001, MC-DEP-001, MC-VALID-001, MC-CONVERT-001, MC-TIME-001, MC-CONFIG-001, MC-SCOPE-001, MC-TEST-001。
- Literal Rules: Rule 2, Rule 5, Rule 9, Rule 10, Rule 11。
- Ordered files:

#### File 1 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/store/OutboxMigrationContractTest.java`

- Purpose: 新增两种存储结构和旧 SQL 字节保护断言。
- Symbols: managed DDL shape、旧资产校验。
- Repository evidence: 当前迁移合同与已有 SQL 路径。
- Dependencies and consumers: 两个新 SQL、test fixtures。
- Why now: 先定义两个技术表的合同 RED。
- Contract/signature changes: UK、epoch、固定租户和时间精度；消息表不变。
- Input/output and state mapping: JDBC slot 0；MP tenant 0／deleted_at NULL；epoch 不复位。
- Error and edge behavior: DDL 缺项、变更旧 SQL、非法键、历史回放被拒绝。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 新增两种存储结构和旧 SQL 字节保护断言。
- Verification contribution: L02，TEST-007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 结构要求可验证。

#### File 2 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/contract/ComponentContractGovernanceTest.java`

- Purpose: 只扩历史及资源治理，键集合继续自动对比。
- Symbols: manifest、配置键同步及 Maven 合同。
- Repository evidence: 固定两条 migration 的断言和双语 README 的提取器。
- Dependencies and consumers: Step 2 manifest、Step 7 双语 README。
- Why now: 先写版本失败断言，保留键集合规则。
- Contract/signature changes: 前两项不动、追加一条、SHA-256 匹配。
- Input/output and state mapping: 前两项不动、追加一条、SHA-256 匹配。
- Error and edge behavior: 假 hash、额外或改序拒绝。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 只扩历史及资源治理，键集合继续自动对比。
- Verification contribution: L02/L07，TEST-007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 新 manifest 必须精确成立。

#### File 3 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/migration/OutboxManagedDdlReadwriteTest.java`

- Purpose: 分别断言 physical target role 和严格同 slot 映射。
- Symbols: 合成两主拓扑、mock runner/replay。
- Repository evidence: 已有 schema readiness/primary 角色验证。
- Dependencies and consumers: OutboxManagedDdlInitializer。
- Why now: 在改验证器前固定失败/通过场景。
- Contract/signature changes: 两个新队列表使用消息对应 profile；history 保持 SINGLE。
- Input/output and state mapping: 两个新队列表使用消息对应 profile；history 保持 SINGLE。
- Error and edge behavior: 缺项、错组、读侧失败均不能 ready。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 分别断言 physical target role 和严格同 slot 映射。
- Verification contribution: L02，TEST-003/007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 合成路由模型可验。

#### File 4 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/migration/OutboxShardSqlGuardTest.java`

- Purpose: 扩展 guard RED，Step 4 再接真实 XML 回归。
- Symbols: 新增等槽 SQL 形态与负例。
- Repository evidence: everyOutboxMapperStatementContainsAnExactShardKey。
- Dependencies and consumers: 固定 guard、Step 4 新 XML。
- Why now: 先证明协调 SQL 白名单要求。
- Contract/signature changes: 三表等 slot，表驱动覆盖读锁与 DML。
- Input/output and state mapping: 三表等 slot，表驱动覆盖读锁与 DML。
- Error and edge behavior: 无上下文、伪造槽、OR/IN/CTE/更新分片键拒绝。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 扩展 guard RED，Step 4 再接真实 XML 回归。
- Verification contribution: L02/L04，TEST-003。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 合成 SQL 基线可独立执行。

#### File 5 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/migration/OutboxPrimaryReadPolicyTest.java`

- Purpose: 验证精确 allowlist 和 tenant/shard predicate。
- Symbols: 精准主读列表。
- Repository evidence: 已有 canonical allowlist 正反例。
- Dependencies and consumers: OutboxPrimaryReadPolicy。
- Why now: 批准形态先有测试。
- Contract/signature changes: 授权读、筛候选、确认回读、必要的时钟采样。
- Input/output and state mapping: 授权读、筛候选、确认回读、必要的时钟采样。
- Error and edge behavior: 关联业务表、宽查询和形态变化被拒绝；业务非锁定读保留副本策略。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 验证精确 allowlist 和 tenant/shard predicate。
- Verification contribution: L02，TEST-003。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 路由准入需固定 SQL。

#### File 6 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/OutboxManagedDdlIntegrationTest.java`

- Purpose: 加旧路由指纹、已安装顺序及故障注入，并查 PG 元数据。
- Symbols: 首次/重启、部分 DDL、老清单拒绝。
- Repository evidence: appliesTheManifestOnceAndSkipsItOnRestart、现有失败回滚、fingerprint fixtures。
- Dependencies and consumers: 管理 DDL、Schema Validator。
- Why now: 集成 RED 先界定历史。
- Contract/signature changes: 更新硬编码 2，NATIVE fixture 对两张表建 SINGLE 映射。
- Input/output and state mapping: 更新硬编码 2，NATIVE fixture 对两张表建 SINGLE 映射。
- Error and edge behavior: 失败脚本结构/历史同目标回滚；老 manifest 见第 3 行报 DDL_HISTORY_PREFIX_MISMATCH。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 加旧路由指纹、已安装顺序及故障注入，并查 PG 元数据。
- Verification contribution: L02，TEST-007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 实际 PG 证据不依赖 Mockito。

#### File 7 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/PostgresqlOutboxTestSupport.java`

- Purpose: 使共用测试按新 schema 验证而非私建假表。
- Symbols: 共享 MP NATIVE PG 夹具初始化。
- Repository evidence: 类内固定 PRIMARY egon_outbox 表列表。
- Dependencies and consumers: 既有 MP PG 集成套件。
- Why now: 测试前先有新结构夹具。
- Contract/signature changes: 两张 SINGLE 技术表映射到同一 fixture 主库。
- Input/output and state mapping: 两张 SINGLE 技术表映射到同一 fixture 主库。
- Error and edge behavior: fixture 只消费本模块 classpath manifest，不接入生产。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 使共用测试按新 schema 验证而非私建假表。
- Verification contribution: L02/L04。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 既有 PG suite 可通过启动门。

#### File 8 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/OutboxPostgresqlReadwriteIntegrationTest.java`

- Purpose: 用同样 topology builder 加两表。
- Symbols: 既有单主/单副本夹具拓扑。
- Repository evidence: outboxTechnicalLocksShareTheBusinessTransactionWhileBusinessReadsUseTheReplica。
- Dependencies and consumers: MP SHARDING_READWRITE。
- Why now: 先同步 boot 结构，端到端在 Step 6。
- Contract/signature changes: 两个新技术表同 SINGLE group。
- Input/output and state mapping: 两个新技术表同 SINGLE group。
- Error and edge behavior: 保留读副本业务 oracle、空 schema guard。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 用同样 topology builder 加两表。
- Verification contribution: L02/L06，TEST-003。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 独立 opt-in 用例仍可 bootstrap。

#### File 9 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/OutboxPostgresqlMultishardIntegrationTest.java`

- Purpose: 限本 Step 修改夹具和结构，不以旧辅助 SQL 宣称新行为验证。
- Symbols: 既有 atomicShardWritesAndConcurrentClaimsStayOnTheirSelectedPrimaries 夹具。
- Repository evidence: 现有 two-group TableProperties、claimScoped 辅助 SQL。
- Dependencies and consumers: MP SHARDING_READWRITE。
- Why now: 先同步 managed topology，再由 Step 6 增真实路径。
- Contract/signature changes: 两张新表复制同一 TECHNICAL_GROUP 映射。
- Input/output and state mapping: 两张新表复制同一 TECHNICAL_GROUP 映射。
- Error and edge behavior: 其他 schema 不触、相同 slot 键不改。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 限本 Step 修改夹具和结构，不以旧辅助 SQL 宣称新行为验证。
- Verification contribution: L02/L06，TEST-003。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 双主夹具能跑新 manifest。

#### File 10 — `CREATE stellar-enclosure-component-transactional-outbox-starter/src/main/resources/db/transactional-outbox/postgresql/V2__add_outbox_queue_coordination.sql`

- Purpose: 新增成员与归属表，消息表索引维持基线；成员 expiry 索引按 Spec 留待诊断。
- Symbols: JDBC 两张协调表。
- Repository evidence: V1__create_transactional_outbox_schema.sql 和 Spec §11.2。
- Dependencies and consumers: 部署方、JdbcOutboxSchemaValidator、L03。
- Why now: 先建立无回填脚本。
- Contract/signature changes: identity id、slot 0、两类 UK、bounded capabilities、UTC expiry。
- Input/output and state mapping: identity id、slot 0、两类 UK、bounded capabilities、UTC expiry。
- Error and edge behavior: 既有 V1 原字节；禁止修改消息表；JDBC 启动不执行脚本。
- Standards impact: MC-SCOPE-001, MC-TIME-001, MC-VALID-001。保留 Egon MP 技术 tenant 0、deleted_at IS NULL、确定 route 和版本守卫；Instant/UTC；结构与版本迁移按当前管理器。
- Literal rule enforcement: Rule 2, Rule 5, Rule 10, Rule 11。按当前技术表/固定路由契约与 UTC 验收；具体输入、异常和门槛见本文件。
- Required change: 新增成员与归属表，消息表索引维持基线；成员 expiry 索引按 Spec 留待诊断。
- Verification contribution: L02/L03，TEST-007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 资源待部署侧应用。

#### File 11 — `CREATE stellar-enclosure-component-transactional-outbox-starter/src/main/resources/db/egon-outbox-mp/V20261122_001__add_outbox_queue_coordination.sql`

- Purpose: DDL 同一物理主目标追加两表。
- Symbols: MP 两个技术表。
- Repository evidence: 旧 V20260924_001 继承模型与 V20261027_001 slot、Spec §11.2。
- Dependencies and consumers: MP DDL runner。
- Why now: SQL 完成后计算字节 checksum。
- Contract/signature changes: BIGINT IDs、等 slot + UK、epoch 正、技术 tenant/deleted_at/version 与 UTC 时间。
- Input/output and state mapping: BIGINT IDs、等 slot + UK、epoch 正、技术 tenant/deleted_at/version 与 UTC 时间。
- Error and edge behavior: 无业务 FK；不得改变消息或旧历史。
- Standards impact: MC-SCOPE-001, MC-TIME-001, MC-VALID-001。保留 Egon MP 技术 tenant 0、deleted_at IS NULL、确定 route 和版本守卫；Instant/UTC；结构与版本迁移按当前管理器。
- Literal rule enforcement: Rule 2, Rule 5, Rule 10, Rule 11。按当前技术表/固定路由契约与 UTC 验收；具体输入、异常和门槛见本文件。
- Required change: DDL 同一物理主目标追加两表。
- Verification contribution: L02，TEST-003/007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 脚本-ready，尚未在本任务运行。

#### File 12 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/resources/db/egon-outbox-mp/manifest.json`

- Purpose: 追加精确历史条目，并与对应 guard/DDL 一同提交。
- Symbols: family component-outbox scripts 第 3 项。
- Repository evidence: 现有两个 manifest 项原样。
- Dependencies and consumers: initializer 与结构校验。
- Why now: 同一提交引用已定稿 SQL。
- Contract/signature changes: 附加 version=20261122_001、classpath path 与实际 SHA-256。
- Input/output and state mapping: 附加 version=20261122_001、classpath path 与实际 SHA-256。
- Error and edge behavior: 保持旧顺序、路径及 hashes，不能扩大 previous fingerprint。
- Standards impact: MC-SCOPE-001, MC-TIME-001, MC-VALID-001。保留 Egon MP 技术 tenant 0、deleted_at IS NULL、确定 route 和版本守卫；Instant/UTC；结构与版本迁移按当前管理器。
- Literal rule enforcement: Rule 2, Rule 5, Rule 10, Rule 11。按当前技术表/固定路由契约与 UTC 验收；具体输入、异常和门槛见本文件。
- Required change: 追加精确历史条目，并与对应 guard/DDL 一同提交。
- Verification contribution: L02，TEST-007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 脚本加入新制品 schema 合同。

#### File 13 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/migration/OutboxManagedDdlInitializer.java`

- Purpose: 拆分消息协同表/历史表校验，沿用 DDL、主库和 replay 顺序。
- Symbols: validateTransactionAndNodes、readiness、managed targets。
- Repository evidence: REQUIRED_TABLES 后缀当前全部要求 SINGLE。
- Dependencies and consumers: 拓扑校验、runner、逻辑工厂。
- Why now: 与 manifest 同步避免半套 schema。
- Contract/signature changes: 消息及两张队列表采用同组同 slot 策略；history 保留历史逻辑定位。
- Input/output and state mapping: 消息及两张队列表采用同组同 slot 策略；history 保留历史逻辑定位。
- Error and edge behavior: 主组 partial failure/replay 不满足 → 非 ready；实际新旧指纹精确匹配。
- Standards impact: MC-SCOPE-001, MC-TIME-001, MC-VALID-001。保留 Egon MP 技术 tenant 0、deleted_at IS NULL、确定 route 和版本守卫；Instant/UTC；结构与版本迁移按当前管理器。
- Literal rule enforcement: Rule 2, Rule 5, Rule 10, Rule 11。按当前技术表/固定路由契约与 UTC 验收；具体输入、异常和门槛见本文件。
- Required change: 拆分消息协同表/历史表校验，沿用 DDL、主库和 replay 顺序。
- Verification contribution: L02，TEST-003/007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 阻断错配的启动。

#### File 14 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/OutboxSchemaMetadataValidator.java`

- Purpose: 每个主目标验证二表、版本和路由，保留老历史保护。
- Symbols: validateHistoryPrefix 和协调结构校验。
- Repository evidence: 当前精确 history 与物理元数据访问器。
- Dependencies and consumers: MP Store/AutoConfig。
- Why now: DDL 布局确定后验证。
- Contract/signature changes: schema/table/列/PK/UK、约束及已追加历史。
- Input/output and state mapping: schema/table/列/PK/UK、约束及已追加历史。
- Error and edge behavior: 额外历史和旧路径报 PREFIX_MISMATCH，缺项 prefix incomplete，不放宽。
- Standards impact: MC-SCOPE-001, MC-TIME-001, MC-VALID-001。保留 Egon MP 技术 tenant 0、deleted_at IS NULL、确定 route 和版本守卫；Instant/UTC；结构与版本迁移按当前管理器。
- Literal rule enforcement: Rule 2, Rule 5, Rule 10, Rule 11。按当前技术表/固定路由契约与 UTC 验收；具体输入、异常和门槛见本文件。
- Required change: 每个主目标验证二表、版本和路由，保留老历史保护。
- Verification contribution: L02，TEST-007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 新 schema/readiness 可可靠判断。

#### File 15 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/JdbcOutboxSchemaValidator.java`

- Purpose: 补字段、约束、锁用唯一索引查验入口。
- Symbols: 协调 schema 只读验证。
- Repository evidence: 当前 V1 与配置 schema 验证。
- Dependencies and consumers: OutboxJdbcAutoConfiguration/Store。
- Why now: V2 确定后加校验。
- Contract/signature changes: active COORDINATED 针对两表，LEGACY/LOCAL 保持 V1 基线。
- Input/output and state mapping: active COORDINATED 针对两表，LEGACY/LOCAL 保持 V1 基线。
- Error and edge behavior: 缺少 V2 拒绝协调，但绝不自动运行 DDL。
- Standards impact: MC-SCOPE-001, MC-TIME-001, MC-VALID-001。保留 Egon MP 技术 tenant 0、deleted_at IS NULL、确定 route 和版本守卫；Instant/UTC；结构与版本迁移按当前管理器。
- Literal rule enforcement: Rule 2, Rule 5, Rule 10, Rule 11。按当前技术表/固定路由契约与 UTC 验收；具体输入、异常和门槛见本文件。
- Required change: 补字段、约束、锁用唯一索引查验入口。
- Verification contribution: L02/L03，TEST-007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: JDBC V2 具备显式检查。

#### File 16 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/OutboxShardRouting.java`

- Purpose: 只扩当前路由证明，不引路由算法。
- Symbols: 路由校验两个技术表 profile。
- Repository evidence: 当前只校验 message，支持现有 fixture 构造。
- Dependencies and consumers: MP Store 与装配。
- Why now: profile 编译后比较。
- Contract/signature changes: SINGLE 同唯一组，TECHNICAL_GROUP 同 slot map/节点。
- Input/output and state mapping: SINGLE 同唯一组，TECHNICAL_GROUP 同 slot map/节点。
- Error and edge behavior: 缺、错节点、复用错误逻辑 source fail closed。
- Standards impact: MC-SCOPE-001, MC-TIME-001, MC-VALID-001。保留 Egon MP 技术 tenant 0、deleted_at IS NULL、确定 route 和版本守卫；Instant/UTC；结构与版本迁移按当前管理器。
- Literal rule enforcement: Rule 2, Rule 5, Rule 10, Rule 11。按当前技术表/固定路由契约与 UTC 验收；具体输入、异常和门槛见本文件。
- Required change: 只扩当前路由证明，不引路由算法。
- Verification contribution: L02/L04，TEST-003。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 可按单分片访问二表。

#### File 17 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/migration/OutboxShardSqlGuard.java`

- Purpose: 扩固定形态，不打开 broad parser exception。
- Symbols: validate、固定协调读写 SQL。
- Repository evidence: 当前单表 exact shard_slot/禁止 CTE等。
- Dependencies and consumers: Factory 与 MP mapper。
- Why now: 准入在实际 SQL 接入前分离验证。
- Contract/signature changes: 三技术表精确 concrete slot、禁止 shard mutation。
- Input/output and state mapping: 三技术表精确 concrete slot、禁止 shard mutation。
- Error and edge behavior: 无 slot/common CTE、多表/业务误命中全部拒绝。
- Standards impact: MC-SCOPE-001, MC-TIME-001, MC-VALID-001。保留 Egon MP 技术 tenant 0、deleted_at IS NULL、确定 route 和版本守卫；Instant/UTC；结构与版本迁移按当前管理器。
- Literal rule enforcement: Rule 2, Rule 5, Rule 10, Rule 11。按当前技术表/固定路由契约与 UTC 验收；具体输入、异常和门槛见本文件。
- Required change: 扩固定形态，不打开 broad parser exception。
- Verification contribution: L02/L04，TEST-003。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: allowlist 具备拒绝基线。

#### File 18 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/migration/OutboxPrimaryReadPolicy.java`

- Purpose: 对已知准确读形态登记最小条件。
- Symbols: permits 与 canonical approved reads。
- Repository evidence: 只允许原锁/管理 SQL。
- Dependencies and consumers: Factory、MP mapper。
- Why now: guard 后补读侧规则。
- Contract/signature changes: 固定成员、锁、候选、主库复查；普通读走原角色。
- Input/output and state mapping: 固定成员、锁、候选、主库复查；普通读走原角色。
- Error and edge behavior: SQL 多表、任意读取、动态放开禁止。
- Standards impact: MC-SCOPE-001, MC-TIME-001, MC-VALID-001。保留 Egon MP 技术 tenant 0、deleted_at IS NULL、确定 route 和版本守卫；Instant/UTC；结构与版本迁移按当前管理器。
- Literal rule enforcement: Rule 2, Rule 5, Rule 10, Rule 11。按当前技术表/固定路由契约与 UTC 验收；具体输入、异常和门槛见本文件。
- Required change: 对已知准确读形态登记最小条件。
- Verification contribution: L02/L04，TEST-003。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 本 Step 能证明形态与角色治理。

#### File 19 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/migration/OutboxLogicalDataSourceFactory.java`

- Purpose: 挂接窄 guard 并验证现有 strict logical 模式。
- Symbols: validateLogicalSql、permitsPrimaryRead、enforceSingleWriteGroup。
- Repository evidence: 用 initializer 就绪、现有两个策略。
- Dependencies and consumers: Common Sharding 钩子。
- Why now: 用已更新守卫完成入口。
- Contract/signature changes: 协调表作用域与现有 single write group / metadata-only；SINGLE 支持逻辑 slot 0。
- Input/output and state mapping: 协调表作用域与现有 single write group / metadata-only；SINGLE 支持逻辑 slot 0。
- Error and edge behavior: 错误数据源/SQL 拒绝；不启物理运行时访问。
- Standards impact: MC-SCOPE-001, MC-TIME-001, MC-VALID-001。保留 Egon MP 技术 tenant 0、deleted_at IS NULL、确定 route 和版本守卫；Instant/UTC；结构与版本迁移按当前管理器。
- Literal rule enforcement: Rule 2, Rule 5, Rule 10, Rule 11。按当前技术表/固定路由契约与 UTC 验收；具体输入、异常和门槛见本文件。
- Required change: 挂接窄 guard 并验证现有 strict logical 模式。
- Verification contribution: L02/L06，TEST-003。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: DDL 和路由合为一个一致提交。

- Validation working directory: `/Users/mario/SelfProject/Stellar-Enclosure/Stellar-Enclosure-Components`。
- Verification command: `./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dtest=OutboxMigrationContractTest,ComponentContractGovernanceTest,OutboxShardSqlGuardTest,OutboxPrimaryReadPolicyTest,OutboxManagedDdlReadwriteTest -Dsurefire.failIfNoSpecifiedTests=false test` 以及 `EGON_OUTBOX_TEST_POSTGRES_ENABLED=true ./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dit.test=OutboxManagedDdlIntegrationTest,OutboxMpMigrationIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false verify`。见 §8 的 L02。
- Expected result: 有效环境下命名断言全部通过，新增结果/side effects 与本 Step 成功条件一致。PG opt-in 有效用例的 skipped 必须为 0。
- Failure returns to: 先核对所选 fixture、参数和真实目标；状态、SQL、路由或装配不符按 §8 L02 回相应文件。缺环境标记为未验收。
- Completion criteria: 新技术表与消息同一主分片；所有物理目标结构/版史核对。保留测试输出、必要的 schema/路由证据，检查本 Step 暂存路径、契约和已有场景。
- Rollback: 未应用 DDL 时可回退本 Step 的源码提交。只要任何 MP 目标已有新版本，按 §9.3 部分迁移处理，再回同版模式，保留历史。
- Commit paths: `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/store/OutboxMigrationContractTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/contract/ComponentContractGovernanceTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/migration/OutboxManagedDdlReadwriteTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/migration/OutboxShardSqlGuardTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/migration/OutboxPrimaryReadPolicyTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/OutboxManagedDdlIntegrationTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/PostgresqlOutboxTestSupport.java`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/OutboxPostgresqlReadwriteIntegrationTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/OutboxPostgresqlMultishardIntegrationTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/resources/db/transactional-outbox/postgresql/V2__add_outbox_queue_coordination.sql`、`stellar-enclosure-component-transactional-outbox-starter/src/main/resources/db/egon-outbox-mp/V20261122_001__add_outbox_queue_coordination.sql`、`stellar-enclosure-component-transactional-outbox-starter/src/main/resources/db/egon-outbox-mp/manifest.json`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/migration/OutboxManagedDdlInitializer.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/OutboxSchemaMetadataValidator.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/JdbcOutboxSchemaValidator.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/OutboxShardRouting.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/migration/OutboxShardSqlGuard.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/migration/OutboxPrimaryReadPolicy.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/migration/OutboxLogicalDataSourceFactory.java`。
- Commit: `feat(outbox): add managed queue schema and shard guards`。

### Step 3 — 完成 JDBC 单库协调、容量领取和权威确认

- Requirements: REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006
- Dependencies: Step 2
- Baseline state: JdbcOutboxStore 已有 SKIP LOCKED 候选与旧 mark。
- Observable outcome: 真实 PostgreSQL 通过单库队列 fencing、预约上限和权威 ACK。
- End state: 可直接验证 JDBC 三个新契约；Starter 新消费者仍未启用。
- Test-first gate: Required — 旧 store 未实现新契约，故障/过期行为不满足新合同。测试方法先写，缺类型才先补可编译签名，完成后执行同一选择器收集 GREEN。
- Manual Checks: MC-ARCH-001, MC-REUSE-001, MC-DEP-001, MC-VALID-001, MC-LOG-001, MC-BEAN-001, MC-JSON-001, MC-TIME-001, MC-PATTERN-001, MC-SCOPE-001, MC-TEST-001。
- Literal Rules: Rule 2, Rule 4, Rule 5, Rule 6, Rule 9, Rule 10, Rule 11。
- Ordered files:

#### File 1 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/JdbcOutboxPostgresqlIntegrationTest.java`

- Purpose: 原事务资产继续回归，新路径使用真实 PG 校验 lease 与 budget。
- Symbols: 新单库合同：claim/queue fence/finish、V1与V2初始化。
- Repository evidence: 现有 JDBC PG fixture 和旧 Store contract。
- Dependencies and consumers: JDBC Store、V2、PostgresqlOutboxTestSupport。
- Why now: 先验证新合同 RED。
- Contract/signature changes: 同一 channel 成员/lease，批量 1/2/4，主库事务 + 消息 token。
- Input/output and state mapping: 同一 channel 成员/lease，批量 1/2/4，主库事务 + 消息 token。
- Error and edge behavior: 未知提交与过期老 token、重复/并发、wrong slot、旧 mode兼容。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 原事务资产继续回归，新路径使用真实 PG 校验 lease 与 budget。
- Verification contribution: L03，TEST-003/004/005/006。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 旧 runtime 没替换，直接调用合同用例可跑。

#### File 2 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/JdbcOutboxStore.java`

- Purpose: 实现成员续约/接管、共享行锁后选候选、逐行状态机后受限 UPDATE RETURNING、finish 权威锁与时间；不改旧 SPI。
- Symbols: coordinate、claim、finishDelivery、legacy claim/mark guard。
- Repository evidence: 现有事务模板、SQL SKIP LOCKED、finish 与 DataSourceUtils。
- Dependencies and consumers: Dispatch/Step 6 JdbcAutoConfiguration。
- Why now: 有失败测试、时钟/索引合同后实现。
- Contract/signature changes: slot 0；READ_COMMITTED 短事务；主库采样；固定列、队列 token 与 per-batch claim token。
- Input/output and state mapping: slot 0；READ_COMMITTED 短事务；主库采样；固定列、队列 token 与 per-batch claim token。
- Error and edge behavior: 对过期/old epoch CAS 拒绝；事务失败不得提交；ACK unknown 保守，使用原结果。
- Standards impact: MC-REUSE-001, MC-TIME-001, MC-PATTERN-001。@Slf4j；保留命名依赖、受管 REQUIRES_NEW；Spring 状态机负责结果，DB 按 UTC 及权威行守卫；短事务外才交付。
- Literal rule enforcement: Rule 2, Rule 4, Rule 5, Rule 6, Rule 9, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 实现成员续约/接管、共享行锁后选候选、逐行状态机后受限 UPDATE RETURNING、finish 权威锁与时间；不改旧 SPI。
- Verification contribution: L03，TEST-001/003/004/005/006/008。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: JDBC 单条和 SQL 小批均可直接调用验收。

- Validation working directory: `/Users/mario/SelfProject/Stellar-Enclosure/Stellar-Enclosure-Components`。
- Verification command: `EGON_OUTBOX_TEST_POSTGRES_ENABLED=true ./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dit.test=JdbcOutboxPostgresqlIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false verify`。见 §8 的 L03。
- Expected result: 有效环境下命名断言全部通过，新增结果/side effects 与本 Step 成功条件一致。PG opt-in 有效用例的 skipped 必须为 0。
- Failure returns to: 先核对所选 fixture、参数和真实目标；状态、SQL、路由或装配不符按 §8 L03 回相应文件。缺环境标记为未验收。
- Completion criteria: 真实 PostgreSQL 通过单库队列 fencing、预约上限和权威 ACK。保留测试输出、必要的 schema/路由证据，检查本 Step 暂存路径、契约和已有场景。
- Rollback: 未部署时回退本 Step 一次语义提交。任何已应用新 MP 历史的环境按 §9.4 保留新制品/schema。
- Commit paths: `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/JdbcOutboxPostgresqlIntegrationTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/JdbcOutboxStore.java`。
- Commit: `feat(outbox): implement jdbc queue claims and outcomes`。

### Step 4 — 完成 MP 精确分片持久化与技术结果决策

- Requirements: REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006、REQ-009（历史 MP 边界回归）
- Dependencies: Step 2、Step 3 的合同验证
- Baseline state: 逻辑 guard 和 DDL 就绪，MP 仍走旧跨 shard 批次。
- Observable outcome: coordinate、claim、finish 均经受管逻辑 DS，真实主路由和版本 CAS。
- End state: MP 可直接运行单分片安全合同；无通用 CTE、无物理绕路。
- Test-first gate: Required — 新 MP 内部合同不受支持，未授权或过期不能按新合同拒绝。测试方法先写，缺类型才先补可编译签名，完成后执行同一选择器收集 GREEN。
- Manual Checks: MC-ARCH-001, MC-REUSE-001, MC-DEP-001, MC-NAME-001, MC-VALID-001, MC-MODEL-001, MC-CONVERT-001, MC-LOG-001, MC-BEAN-001, MC-JSON-001, MC-TIME-001, MC-PATTERN-001, MC-SCOPE-001, MC-TEST-001。
- Literal Rules: Rule 1, Rule 2, Rule 3, Rule 4, Rule 5, Rule 6, Rule 9, Rule 10, Rule 11。
- Ordered files:

#### File 1 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/PostgresqlOutboxTestSupport.java`

- Purpose: 三表显式技术租户 fixture。
- Symbols: 三表显式技术租户 fixture。
- Repository evidence: 现有固定测试 fixture 或主库白名单；Step 2 已建立结构。
- Dependencies and consumers: 受管主库验证及新增 Queue DAO。
- Why now: 使新 Mapper 的真实执行证据保留原安全边界。
- Contract/signature changes: 所有操作继续精确绑定 slot 和技术租户。
- Input/output and state mapping: 按已批准的相同 schema、主组和原语义读写。
- Error and edge behavior: 拒绝未审批的 SQL、未绑定的技术授权和目标角色。
- Standards impact: MC-SCOPE-001、MC-VALID-001、MC-TEST-001。保留精确映射与关口。
- Literal rule enforcement: Rule 2、Rule 11。实际映射继续走受管数据源及组件范围。
- Required change: 仅向共享隔离测试 context 加两表技术 ignore，继续以同一逻辑数据源、Mapper XML 和事务管理器验证；真实测试路径与 fixture 清理保持分离。
- Verification contribution: L04、MPI-02，实际 Store 不可绕过权限或路由。
- After this file: 新生产映射可走真实测试路径。

#### File 2 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/OutboxMpSharedContractIntegrationTest.java`

- Purpose: 直接调用生产 Store，验证新合同与旧合同共存。
- Symbols: 安全 Store 合同。
- Repository evidence: satisfies_the_shared_postgresql_store_contract 和 PG base。
- Dependencies and consumers: MP Store、DDL 夹具。
- Why now: 先定义 RED。
- Contract/signature changes: 成员、归属、单分片容量/确认。
- Input/output and state mapping: 有资格主库领取，无资格或错槽无写。
- Error and edge behavior: 冲突、旧 ABI 保留。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 直接调用生产 Store，验证新合同与旧合同共存。
- Verification contribution: L04，TEST-002/003/005。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 新不支持路径 RED。

#### File 3 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/PostgresqlOutboxRecoveryIntegrationTest.java`

- Purpose: 新增安全模式恢复测试，保留原方法回归。
- Symbols: 到期、耗尽、旧 token、ACK unknown。
- Repository evidence: shouldRejectCompletionFromAnOldClaimToken 等现有断言。
- Dependencies and consumers: MP Store、真实 PG。
- Why now: 先覆盖故障路径。
- Contract/signature changes: 旧权属有效消息可 ACK；消息过期不行。
- Input/output and state mapping: 原 ID、权威状态与尝试次数。
- Error and edge behavior: ACK unknown 不重调 handler；耗尽 DEAD。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 新增安全模式恢复测试，保留原方法回归。
- Verification contribution: L04，TEST-004/005/006。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 到期和故障可重复。

#### File 4 — `CREATE stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/po/OutboxQueueMemberPO.java`

- Purpose: 新增协调表的技术 PO。
- Symbols: OutboxQueueMemberPO。
- Repository evidence: OutboxMessagePO 继承 EgonModel。
- Dependencies and consumers: 成员 DAO 与转换器。
- Why now: 物理模型先定义。
- Contract/signature changes: slot/id/instance/capabilitiesJson/lastSeenAt/expiresAt、继承字段。
- Input/output and state mapping: 固定技术 tenant；expiresAt≥lastSeenAt。
- Error and edge behavior: 能力上限 32KiB，DB 负责时间。
- Standards impact: MC-NAME-001, MC-VALID-001, MC-MODEL-001。@Data、@NoArgsConstructor、@AllArgsConstructor、@Accessors(chain = true)、@SuperBuilder、@EqualsAndHashCode(callSuper = true)，继承 EgonModel；按 Insert/Update/Persisted 分组作 shape 校验。
- Literal rule enforcement: Rule 1, Rule 2, Rule 3, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 新增协调表的技术 PO。
- Verification contribution: L04，TEST-002/007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 映射字段就绪。

#### File 5 — `CREATE stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/po/OutboxQueueLeasePO.java`

- Purpose: 区分 MP version 与 queue epoch。
- Symbols: OutboxQueueLeasePO。
- Repository evidence: OutboxMessagePO 与 §11.2.2。
- Dependencies and consumers: 归属 DAO 与转换器。
- Why now: 先有锁行模型。
- Contract/signature changes: slot/channel/ownerInstanceId/epoch/expiresAt。
- Input/output and state mapping: audit/version 继承，epoch>0。
- Error and edge behavior: epoch 溢出拒绝，不软删。
- Standards impact: MC-NAME-001, MC-VALID-001, MC-MODEL-001。@Data、@NoArgsConstructor、@AllArgsConstructor、@Accessors(chain = true)、@SuperBuilder、@EqualsAndHashCode(callSuper = true)，继承 EgonModel；按 Insert/Update/Persisted 分组作 shape 校验。
- Literal rule enforcement: Rule 1, Rule 2, Rule 3, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 区分 MP version 与 queue epoch。
- Verification contribution: L04，TEST-002/007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 持有数据库权威快照。

#### File 6 — `CREATE stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/converter/OutboxQueueMemberConverter.java`

- Purpose: 用 MapStruct + BaseForwardConverter 做规范化成员映射。
- Symbols: OutboxQueueMemberConverter。
- Repository evidence: OutboxMessageConverter 与 OutboxHeadersConverter。
- Dependencies and consumers: MP Store、Boot Jackson。
- Why now: 模型后定义查询投影。
- Contract/signature changes: 技术 PO 到协调 BO 的成员快照。
- Input/output and state mapping: 确定 slot、能力数组及 Instant；审计字段不进业务。
- Error and edge behavior: 坏 JSON 或超限拒绝整视图。
- Standards impact: MC-CONVERT-001, MC-JSON-001, MC-MODEL-001。使用 MapStruct、BaseForwardConverter 和 Boot Jackson 环境，映射完整且未映射字段显式拒绝或说明；不做 BeanUtils/JSON 对象复制。
- Literal rule enforcement: Rule 1, Rule 3, Rule 6, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 用 MapStruct + BaseForwardConverter 做规范化成员映射。
- Verification contribution: L04，TEST-002/007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 转换器可编译可验证。

#### File 7 — `CREATE stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/converter/OutboxQueueLeaseConverter.java`

- Purpose: 用 MapStruct + BaseForwardConverter 保留主库原值。
- Symbols: OutboxQueueLeaseConverter。
- Repository evidence: 现有 OutboxMessageConverter。
- Dependencies and consumers: Store、Dispatcher。
- Why now: PO 已知。
- Contract/signature changes: PO 到 OutboxQueueLease。
- Input/output and state mapping: 组合 slot/channel、epoch/owner/expiresAt。
- Error and edge behavior: 无行不生成 token，坏行拒绝。
- Standards impact: MC-CONVERT-001, MC-JSON-001, MC-MODEL-001。使用 MapStruct、BaseForwardConverter 和 Boot Jackson 环境，映射完整且未映射字段显式拒绝或说明；不做 BeanUtils/JSON 对象复制。
- Literal rule enforcement: Rule 1, Rule 3, Rule 6, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 用 MapStruct + BaseForwardConverter 保留主库原值。
- Verification contribution: L04，TEST-005。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 权属投影稳定。

#### File 8 — `CREATE stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/dao/OutboxQueueMemberDAO.java`

- Purpose: 按现有 package 扫描定义命令、shape 验证。
- Symbols: upsert、完整快照、过期清理。
- Repository evidence: OutboxMessageDAO 的 Mapper + XML 命名空间。
- Dependencies and consumers: Store、member XML。
- Why now: 先固定 mapper API。
- Contract/signature changes: 显式 slot、instance/能力、DB TTL。
- Input/output and state mapping: 只返回始终同 shard 的有界结果。
- Error and edge behavior: 截断拒绝，清理仅过期保留窗外。
- Standards impact: MC-SCOPE-001, MC-TIME-001, MC-VALID-001。保留 Egon MP 技术 tenant 0、deleted_at IS NULL、确定 route 和版本守卫；Instant/UTC；结构与版本迁移按当前管理器。
- Literal rule enforcement: Rule 2, Rule 5, Rule 10, Rule 11。按当前技术表/固定路由契约与 UTC 验收；具体输入、异常和门槛见本文件。
- Required change: 按现有 package 扫描定义命令、shape 验证。
- Verification contribution: L04，TEST-002/003。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: XML 命名空间就绪。

#### File 9 — `CREATE stellar-enclosure-component-transactional-outbox-starter/src/main/resources/mapper/outbox/OutboxQueueMemberMapper.xml`

- Purpose: 绑定成员事务所需 SQL，不走物理绕路。
- Symbols: OutboxQueueMemberDAO statements。
- Repository evidence: OutboxMessageMapper.xml、固定主读授权。
- Dependencies and consumers: DAO、Store、逻辑 DS。
- Why now: DAO 后写固定 SQL。
- Contract/signature changes: upsert/读视图/清理。
- Input/output and state mapping: UK 与 slot、tenant、DB 时间；无跨表。
- Error and edge behavior: 冲突走固定同表守卫；回滚后可重读。
- Standards impact: MC-SCOPE-001, MC-TIME-001, MC-VALID-001。保留 Egon MP 技术 tenant 0、deleted_at IS NULL、确定 route 和版本守卫；Instant/UTC；结构与版本迁移按当前管理器。
- Literal rule enforcement: Rule 2, Rule 5, Rule 10, Rule 11。按当前技术表/固定路由契约与 UTC 验收；具体输入、异常和门槛见本文件。
- Required change: 绑定成员事务所需 SQL，不走物理绕路。
- Verification contribution: L04，TEST-002/003。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: SQL 可渲染可验证。

#### File 10 — `CREATE stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/dao/OutboxQueueLeaseDAO.java`

- Purpose: 只暴露显式 slot 归属操作。
- Symbols: guardLock、续约/夺权/释放。
- Repository evidence: 原 selectProcessingForUpdate 及 §11 技术锁。
- Dependencies and consumers: Store、lease XML。
- Why now: 先有 DAO 方言合同。
- Contract/signature changes: slot + channel + owner/epoch/time。
- Input/output and state mapping: 固定输出 lease PO 或写入行数。
- Error and edge behavior: 无资格、缺失、epoch 溢出拒绝。
- Standards impact: MC-SCOPE-001, MC-TIME-001, MC-VALID-001。保留 Egon MP 技术 tenant 0、deleted_at IS NULL、确定 route 和版本守卫；Instant/UTC；结构与版本迁移按当前管理器。
- Literal rule enforcement: Rule 2, Rule 5, Rule 10, Rule 11。按当前技术表/固定路由契约与 UTC 验收；具体输入、异常和门槛见本文件。
- Required change: 只暴露显式 slot 归属操作。
- Verification contribution: L04，TEST-003/005。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 可绑定 XML。

#### File 11 — `CREATE stellar-enclosure-component-transactional-outbox-starter/src/main/resources/mapper/outbox/OutboxQueueLeaseMapper.xml`

- Purpose: 实现队列行守卫和条件写，保持精确主库读。
- Symbols: OutboxQueueLeaseDAO statements。
- Repository evidence: 旧锁 SQL 和新 canonical allowlist。
- Dependencies and consumers: MP Store、PrimaryReadPolicy。
- Why now: DAO 后。
- Contract/signature changes: 锁模式 FOR SHARE 或经证明互斥等价。
- Input/output and state mapping: 夺权同一行写锁，续约原 epoch，释放只改 expiresAt。
- Error and edge behavior: 无权不读候选、不跨组、不删除历史 epoch。
- Standards impact: MC-SCOPE-001, MC-TIME-001, MC-VALID-001。保留 Egon MP 技术 tenant 0、deleted_at IS NULL、确定 route 和版本守卫；Instant/UTC；结构与版本迁移按当前管理器。
- Literal rule enforcement: Rule 2, Rule 5, Rule 10, Rule 11。按当前技术表/固定路由契约与 UTC 验收；具体输入、异常和门槛见本文件。
- Required change: 实现队列行守卫和条件写，保持精确主库读。
- Verification contribution: L04，TEST-003/005。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 队列 guard 可同一事务执行。

#### File 12 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/dao/OutboxMessageDAO.java`

- Purpose: 声明固定语句的绑定和结果投影。
- Symbols: safe due/reclaim、更新与确认方法。
- Repository evidence: 现有 selectDueCandidatesByChannels、tryAcquireTransactionLock、version 方法。
- Dependencies and consumers: Repository、Mapper XML。
- Why now: 先定义固定声明。
- Contract/signature changes: 参数 exact slot、channel/id/version、时间、状态、token。
- Input/output and state mapping: 候选分正常与过期；耗尽单分支。
- Error and edge behavior: 非法槽及超预算拒绝，旧签名保留。
- Standards impact: MC-SCOPE-001, MC-TIME-001, MC-VALID-001。保留 Egon MP 技术 tenant 0、deleted_at IS NULL、确定 route 和版本守卫；Instant/UTC；结构与版本迁移按当前管理器。
- Literal rule enforcement: Rule 2, Rule 5, Rule 10, Rule 11。按当前技术表/固定路由契约与 UTC 验收；具体输入、异常和门槛见本文件。
- Required change: 声明固定语句的绑定和结果投影。
- Verification contribution: L04，TEST-003/005/006。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 能渲染新增 mapper。

#### File 13 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/resources/mapper/outbox/OutboxMessageMapper.xml`

- Purpose: 扩安全语句并保留 LEGACY SQL。
- Symbols: 安全候选、锁、条件更新和回读。
- Repository evidence: 现有固定 statement 与 idx_outbox_claim/reclaim。
- Dependencies and consumers: DAO、主库策略。
- Why now: 声明后。
- Contract/signature changes: 按时间/id 选候选，锁/守卫后按权威状态更新。
- Input/output and state mapping: 每条显式 slot/channel；ACK 带 version CAS。
- Error and edge behavior: NOWAIT 失败回滚，不启广播、通用 CTE 或 DEAD 重驱。
- Standards impact: MC-SCOPE-001, MC-TIME-001, MC-VALID-001。保留 Egon MP 技术 tenant 0、deleted_at IS NULL、确定 route 和版本守卫；Instant/UTC；结构与版本迁移按当前管理器。
- Literal rule enforcement: Rule 2, Rule 5, Rule 10, Rule 11。按当前技术表/固定路由契约与 UTC 验收；具体输入、异常和门槛见本文件。
- Required change: 扩安全语句并保留 LEGACY SQL。
- Verification contribution: L04，TEST-003/005/008。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 固定 MP SQL 就绪。

#### File 14 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/repository/OutboxMessageRepository.java`

- Purpose: 沿用现有 Repository 接通新增固定消息操作。
- Symbols: safe 候选与 ACK 转发。
- Repository evidence: 现有 getBaseMapper 和 requireAtMostOne。
- Dependencies and consumers: MybatisPlusOutboxStore、DAO。
- Why now: 合上 persistence 边界。
- Contract/signature changes: shape 与 DAO 一致。
- Input/output and state mapping: 保留同事务代理、逐次写结果。
- Error and edge behavior: 超一行是错误，不改变公开消息格式。
- Standards impact: MC-REUSE-001, MC-TIME-001, MC-PATTERN-001。@Slf4j；保留命名依赖、受管 REQUIRES_NEW；Spring 状态机负责结果，DB 按 UTC 及权威行守卫；短事务外才交付。
- Literal rule enforcement: Rule 2, Rule 4, Rule 5, Rule 6, Rule 9, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 沿用现有 Repository 接通新增固定消息操作。
- Verification contribution: L04，TEST-003/005。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: DAO 访问边界未被跳过。

#### File 15 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/migration/OutboxShardSqlGuardTest.java`

- Purpose: 扩充 Step 2 断言的输入，接 Store 前锁定 SQL 契约。
- Symbols: 真实三份 mapper 渲染测试。
- Repository evidence: Step 2 固定形态用例。
- Dependencies and consumers: 两新 XML、原消息 XML 与 guard。
- Why now: 有实际 XML 后验证。
- Contract/signature changes: 渲染三份真实 statement，证明 compact canonical。
- Input/output and state mapping: 合法精确 slot、权威读允许。
- Error and edge behavior: 伪造路由/宽 SQL 实际被拒。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 扩充 Step 2 断言的输入，接 Store 前锁定 SQL 契约。
- Verification contribution: L04，TEST-003。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 不再只依赖手写 SQL 示例。

#### File 16 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/OutboxTechnicalContextExecutor.java`

- Purpose: 保持三张技术表的精确 Mapper 授权。
- Symbols: executeMember、executeLease、保留原 execute 和 executeMigration。
- Repository evidence: 当前 Executor 固定进入 OutboxMessageDAO，Common EgonColaTechnicalTenantScope 禁止嵌套切换。
- Dependencies and consumers: 新两个 Mapper、MybatisPlusOutboxStore。
- Why now: 新 Mapper 完成后连入其独立 scope。
- Contract/signature changes: 以同样的 Supplier 与技术用户绑定方式进入固定 Member 或 Lease Mapper。
- Input/output and state mapping: 复用 tenant 0、审计主体、同步退出恢复原 MDC。
- Error and edge behavior: 仍拒绝其他 Mapper 的跨权限调用，错误和返回均清理。
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-SCOPE-001。遵守现有 Qualifier、tenant 和同步保护。
- Literal rule enforcement: Rule 2、Rule 4、Rule 11。保持注解、限定上下文以及正反检查。
- Required change: 两个固定 scope 入口与现有方法共用保护，runtime 在事务中逐次进入各 DAO scope。
- Verification contribution: L04、MPI-02，三个 mapper 都通过自己的精确保护。
- After this file: 三条受管路径可接入同一事务。

#### File 17 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/migration/OutboxPrimaryReadPolicy.java`

- Purpose: 固定成员快照及维护读。
- Symbols: 固定成员快照及维护读。
- Repository evidence: 现有固定测试 fixture 或主库白名单；Step 2 已建立结构。
- Dependencies and consumers: 受管主库验证及新增 Queue DAO。
- Why now: 使新 Mapper 的真实执行证据保留原安全边界。
- Contract/signature changes: 所有操作继续精确绑定 slot 和技术租户。
- Input/output and state mapping: 按已批准的相同 schema、主组和原语义读写。
- Error and edge behavior: 拒绝未审批的 SQL、未绑定的技术授权和目标角色。
- Standards impact: MC-SCOPE-001、MC-VALID-001、MC-TEST-001。保留精确映射与关口。
- Literal rule enforcement: Rule 2、Rule 11。实际映射继续走受管数据源及组件范围。
- Required change: 为生产 Mapper 的每个固定主库快照添加精确授权。用 PostgreSQL KEY SHARE 读授权的有界完整集合，保留现有角色判断。禁止使用 HintManager 或普通主库 SELECT 越权路径。
- Verification contribution: L04、MPI-02，实际 Store 不可绕过权限或路由。
- After this file: 新生产映射可走真实测试路径。

#### File 18 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/MybatisPlusOutboxStore.java`

- Purpose: 实现三个安全契约和消息结果确认、MP 默认批 1。
- Symbols: coordinate、claim、finishDelivery、旧入口兼容。
- Repository evidence: OutboxShardContext、selectProcessingForUpdate、原 workerTransaction。
- Dependencies and consumers: Dispatcher、MP AutoConfiguration。
- Why now: 模型、DAO 和固定 SQL 就绪。
- Contract/signature changes: 单 shard logical REQUIRES_NEW，队列 guard 和消息 claim 同事务。
- Input/output and state mapping: 状态机一次、PG 时钟、token/time/version。
- Error and edge behavior: unknown 不交付；无物理元数据绕路，行冲突本 shard 回滚。
- Standards impact: MC-REUSE-001, MC-TIME-001, MC-PATTERN-001。@Slf4j；保留命名依赖、受管 REQUIRES_NEW；Spring 状态机负责结果，DB 按 UTC 及权威行守卫；短事务外才交付。
- Literal rule enforcement: Rule 2, Rule 4, Rule 5, Rule 6, Rule 9, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 实现三个安全契约和消息结果确认、MP 默认批 1。
- Verification contribution: L04，TEST-003/004/005/006。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: MP 可直接验收，未激活新运行时。

- Validation working directory: `/Users/mario/SelfProject/Stellar-Enclosure/Stellar-Enclosure-Components`。
- Verification command: `EGON_OUTBOX_TEST_POSTGRES_ENABLED=true ./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dit.test=OutboxMpSharedContractIntegrationTest,PostgresqlOutboxRecoveryIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false verify` 以及 `./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dtest=OutboxShardSqlGuardTest,OutboxPrimaryReadPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`。见 §8 的 L04。
- Expected result: 有效环境下命名断言全部通过，新增结果/side effects 与本 Step 成功条件一致。PG opt-in 有效用例的 skipped 必须为 0。
- Failure returns to: 先核对所选 fixture、参数和真实目标；状态、SQL、路由或装配不符按 §8 L04 回相应文件。缺环境标记为未验收。
- Completion criteria: coordinate、claim、finish 均经受管逻辑 DS，真实主路由和版本 CAS。保留测试输出、必要的 schema/路由证据，检查本 Step 暂存路径、契约和已有场景。
- Rollback: 未部署时回退本 Step 一次语义提交。任何已应用新 MP 历史的环境按 §9.4 保留新制品/schema。
- Commit paths: `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/OutboxMpSharedContractIntegrationTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/PostgresqlOutboxRecoveryIntegrationTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/po/OutboxQueueMemberPO.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/po/OutboxQueueLeasePO.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/converter/OutboxQueueMemberConverter.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/converter/OutboxQueueLeaseConverter.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/dao/OutboxQueueMemberDAO.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/resources/mapper/outbox/OutboxQueueMemberMapper.xml`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/dao/OutboxQueueLeaseDAO.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/resources/mapper/outbox/OutboxQueueLeaseMapper.xml`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/dao/OutboxMessageDAO.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/resources/mapper/outbox/OutboxMessageMapper.xml`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/repository/OutboxMessageRepository.java`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/migration/OutboxShardSqlGuardTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/store/MybatisPlusOutboxStore.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/persistence/OutboxTechnicalContextExecutor.java`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/PostgresqlOutboxTestSupport.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/migration/OutboxPrimaryReadPolicy.java`。
- Commit: `feat(outbox): implement primary-routed mp queue store`。

### Step 5 — 完成实例归属和预留 worker 的提交后移交

- Requirements: REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006、REQ-008
- Dependencies: Step 1、Step 3、Step 4
- Baseline state: Store 三个入口可直接运行，旧 Dispatcher 使用池队列和跨组汇总。
- Observable outcome: 只对已就绪预约领；旧 epoch 不新领；A 独立移交；内置缺定义按配置处理。
- End state: 直接构造的新运行时场景通过，Spring 仍未对外自动激活。
- Test-first gate: Required — 无槽时唤醒被耗用、没有 queue capability/epoch、内置消费校验短路。测试方法先写，缺类型才先补可编译签名，完成后执行同一选择器收集 GREEN。
- Manual Checks: MC-ARCH-001, MC-REUSE-001, MC-DEP-001, MC-NAME-001, MC-VALID-001, MC-LOG-001, MC-BEAN-001, MC-UTIL-001, MC-JSON-001, MC-TIME-001, MC-CONFIG-001, MC-PATTERN-001, MC-SCOPE-001, MC-TEST-001。
- Literal Rules: Rule 1, Rule 2, Rule 3, Rule 4, Rule 5, Rule 6, Rule 7, Rule 9, Rule 10, Rule 11。
- Ordered files:

#### File 1 — `CREATE stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/dispatch/OutboxQueueCoordinatorTest.java`

- Purpose: 加入确定性选主和故障接管测试。
- Symbols: ownerSelection、staleEpoch、gracefulHandover。
- Repository evidence: DispatcherTest/PollerTest 受控时间模式。
- Dependencies and consumers: Coordinator、Store fake。
- Why now: 先写故障与分配。
- Contract/signature changes: 两 shard、三副本异构能力。
- Input/output and state mapping: 两次稳定视图后有序交还。
- Error and edge behavior: 失权/超限/未知不新领，避免 sleep 等竞态。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 加入确定性选主和故障接管测试。
- Verification contribution: L05，TEST-002/005。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 未协调场景 RED。

#### File 2 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/dispatch/OutboxDispatcherTest.java`

- Purpose: 用 barrier 验 wake、claim、handoff、handler、finish。
- Symbols: 4+1、A commit/B fail、窄 delivery 验证。
- Repository evidence: 现有 runCoordinator、mark 路径测试。
- Dependencies and consumers: 安全 Store、handler、metrics。
- Why now: 先证明缺失。
- Contract/signature changes: ready 代次、A/B、unknown、代理内置适配器。
- Input/output and state mapping: 四条独占槽位，独立 handoff、ACK 结果分类。
- Error and edge behavior: 拒绝入池、过期不执行、旧 token 不写、伪造 header 拒绝。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 用 barrier 验 wake、claim、handoff、handler、finish。
- Verification contribution: L05，TEST-001/003/004/006。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 旧队列场景 RED。

#### File 3 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/dispatch/OutboxPollerTest.java`

- Purpose: 加补扫及停止验证，旧轮询保持。
- Symbols: 周期、ready 唤醒与 shard 退避。
- Repository evidence: 固定延时/jitter 测试。
- Dependencies and consumers: Poller/Dispatcher。
- Why now: 先写丢信号断言。
- Contract/signature changes: 无本地 afterCommit，周期扫描。
- Input/output and state mapping: 零槽保留 generation，ready 重新发现。
- Error and edge behavior: 无 owner/无连接不忙循环。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 加补扫及停止验证，旧轮询保持。
- Verification contribution: L05，TEST-001/002。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 调度用例就绪。

#### File 4 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/observability/OutboxMetrics.java`

- Purpose: 提供增量默认方法，旧实现不用修改。
- Symbols: 默认阶段与队列事件回调。
- Repository evidence: Noop、Micrometer、自定义接口。
- Dependencies and consumers: Dispatcher、Coordinator、Step 7。
- Why now: 先声明无副作用 SPI。
- Contract/signature changes: claim/handoff/start/result/confirmed、拒绝与 unknown。
- Input/output and state mapping: 类型化 reason/已有受控维度。
- Error and edge behavior: 默认 no-op，无 payload/token 或高基数标签。
- Standards impact: MC-NAME-001, MC-VALID-001, MC-MODEL-001。Jakarta 约束、@Valid 或不可变性；复用原包契约。既有调用/ABI 兼容，禁止 ordinal；不增加 Maven 依赖。
- Literal rule enforcement: Rule 1, Rule 2, Rule 3, Rule 6, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 提供增量默认方法，旧实现不用修改。
- Verification contribution: L05/L07，TEST-008。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 运行时可发阶段事件。

#### File 5 — `CREATE stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/dispatch/OutboxQueueCoordinator.java`

- Purpose: 协调缓存仅作提示，资格依据事务确认，不加入外部服务。
- Symbols: 注册、续租、接管、释放与停止。
- Repository evidence: WorkerIdentity#canClaim、registry 与 §9.2.1。
- Dependencies and consumers: Dispatcher、独立 control 调度。
- Why now: 先实现 DB 归属馈送。
- Contract/signature changes: 主 shard snapshot，SHA-256 rendezvous，epoch 与期限。
- Input/output and state mapping: 两次稳定观测、有序移交与每分片退避。
- Error and edge behavior: 视图过大/失信/unknown/停机禁新领。
- Standards impact: MC-LOG-001, MC-BEAN-001, MC-PATTERN-001。@Slf4j、命名 Bean 与 Qualified 构造注入；只用 JDK/Spring 调度组件，单调耗时，复用现有 handler registry 和技术状态机。
- Literal rule enforcement: Rule 2, Rule 4, Rule 5, Rule 7, Rule 9, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 协调缓存仅作提示，资格依据事务确认，不加入外部服务。
- Verification contribution: L05，TEST-002/005。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 运行时可直接构造。

#### File 6 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/delivery/statemachine/BusinessStateMachineDeliveryHandler.java`

- Purpose: 只改内置交付校验次序。
- Symbols: deliver 的 envelope 与查定义先后。
- Repository evidence: validateDestination 和 deliver 的现有 retry-on-missing-definition。
- Dependencies and consumers: Dispatcher、BusinessStateMachineService。
- Why now: 确保无非法消息进入缺定义重试。
- Contract/signature changes: header/tenant 校验前移，再按现有缺定义配置判。
- Input/output and state mapping: 合法使用既有 protocol/receipt。
- Error and edge behavior: 旧 producer 检查严格，伪造、缺 header 保持拒绝。
- Standards impact: MC-LOG-001, MC-BEAN-001, MC-PATTERN-001。@Slf4j、命名 Bean 与 Qualified 构造注入；只用 JDK/Spring 调度组件，单调耗时，复用现有 handler registry 和技术状态机。
- Literal rule enforcement: Rule 2, Rule 4, Rule 5, Rule 7, Rule 9, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 只改内置交付校验次序。
- Verification contribution: L05，TEST-006。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 接入 Dispatcher 后分类有效。

#### File 7 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/dispatch/OutboxDispatcher.java`

- Purpose: 实现 drain、dirty generation 与公平轮转、A/B 交付隔离。按既有 OutboxWorkerIdentity 生成 claim token，保留同一 release/finish 的路由和尝试身份。使用 Spring AopUtils 识别实际代理目标，仅允许已注册内置 handler 走新校验顺序。
- Symbols: submitDue、submitMessageIds、协调循环、单分片 handoff、finish。
- Repository evidence: 原 deliveryPermits 与 applyResult。
- Dependencies and consumers: Poller、Listener、worker、Store。
- Why now: 控制/执行契约完成后接入。
- Contract/signature changes: ready 代次、真实预约、独占 slot、每 shard 单 in-flight，正常/过期同时积压时每四个可用机会至少一给恢复。
- Input/output and state mapping: 事务确认后单独 handoff；旧 LEGACY 分支保留。
- Error and edge behavior: unknown 归还预约，消息等过期；ACK 重试不重调 handler。
- Standards impact: MC-LOG-001, MC-BEAN-001, MC-PATTERN-001。@Slf4j、命名 Bean 与 Qualified 构造注入；只用 JDK/Spring 调度组件，单调耗时，复用现有 handler registry 和技术状态机。
- Literal rule enforcement: Rule 2, Rule 4, Rule 5, Rule 7, Rule 9, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 实现 drain、dirty generation 与公平轮转、A/B 交付隔离。按既有 OutboxWorkerIdentity 生成 claim token，保留同一 release/finish 的路由和尝试身份。使用 Spring AopUtils 识别实际代理目标，仅允许已注册内置 handler 走新校验顺序。
- Verification contribution: L05，TEST-001/002/003/004/005/006。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 安全逻辑可构造，未正式启用。

#### File 8 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/dispatch/OutboxPoller.java`

- Purpose: 让失去通知的消息在下一可用轮被发现。
- Symbols: 周期唤醒、启动/停止。
- Repository evidence: 现有 TaskScheduler与 jitter。
- Dependencies and consumers: Dispatcher、Spring 装配。
- Why now: dispatcher 路径可以使用。
- Contract/signature changes: fixedDelay、jitter 与 per-shard backoff。
- Input/output and state mapping: 合并 ready/定时触发、保留循环兜底。
- Error and edge behavior: paused 或关停不开始新 claim。
- Standards impact: MC-LOG-001, MC-BEAN-001, MC-PATTERN-001。@Slf4j、命名 Bean 与 Qualified 构造注入；只用 JDK/Spring 调度组件，单调耗时，复用现有 handler registry 和技术状态机。
- Literal rule enforcement: Rule 2, Rule 4, Rule 5, Rule 7, Rule 9, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 让失去通知的消息在下一可用轮被发现。
- Verification contribution: L05，TEST-001/002。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 新逻辑测试 GREEN。

- Validation working directory: `/Users/mario/SelfProject/Stellar-Enclosure/Stellar-Enclosure-Components`。
- Verification command: `./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dtest=OutboxQueueCoordinatorTest,OutboxDispatcherTest,OutboxPollerTest -Dsurefire.failIfNoSpecifiedTests=false test`。见 §8 的 L05。
- Expected result: 有效环境下命名断言全部通过，新增结果/side effects 与本 Step 成功条件一致。PG opt-in 有效用例的 skipped 必须为 0。
- Failure returns to: 先核对所选 fixture、参数和真实目标；状态、SQL、路由或装配不符按 §8 L05 回相应文件。缺环境标记为未验收。
- Completion criteria: 只对已就绪预约领；旧 epoch 不新领；A 独立移交；内置缺定义按配置处理。保留测试输出、必要的 schema/路由证据，检查本 Step 暂存路径、契约和已有场景。
- Rollback: 未部署时回退本 Step 一次语义提交。任何已应用新 MP 历史的环境按 §9.4 保留新制品/schema。
- Commit paths: `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/dispatch/OutboxQueueCoordinatorTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/dispatch/OutboxDispatcherTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/dispatch/OutboxPollerTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/observability/OutboxMetrics.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/dispatch/OutboxQueueCoordinator.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/delivery/statemachine/BusinessStateMachineDeliveryHandler.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/dispatch/OutboxDispatcher.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/dispatch/OutboxPoller.java`。
- Commit: `feat(outbox): dispatch claims to reserved ready workers`。

### Step 6 — 装配安全模式并验证 MP 两主两副本和排空回退

- Requirements: REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-006、REQ-007、REQ-008
- Dependencies: Step 5
- Baseline state: 可测试运行时存在，自动配置未绑定新路径。
- Observable outcome: 总门禁覆盖 poll/afterCommit/内置 claim，真实 2×2 故障及回退。
- End state: 新制品可严格启动或只生产，已验证 LOCAL/COORDINATED 和真实隔离，不自动应用至生产。
- Test-first gate: Required — 旧开关能被 afterCommit 绕过，原 MP 用例只模拟 SQL。测试方法先写，缺类型才先补可编译签名，完成后执行同一选择器收集 GREEN。
- Manual Checks: MC-ARCH-001, MC-REUSE-001, MC-DEP-001, MC-VALID-001, MC-CONVERT-001, MC-LOG-001, MC-BEAN-001, MC-TIME-001, MC-CONFIG-001, MC-SCOPE-001, MC-TEST-001。
- Literal Rules: Rule 2, Rule 3, Rule 4, Rule 7, Rule 9, Rule 10, Rule 11。
- Ordered files:

#### File 1 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/TransactionalOutboxAutoConfigurationTest.java`

- Purpose: 扩配置矩阵并保留 d3f5f9c 的 handler 事务 advice 回归。
- Symbols: 安全模式、同版 LOCAL、lazy advice 与只生产。
- Repository evidence: preservesTransactionAdviceOnDeliveryHandlersWhenAnInfrastructureConsumerCreatesTheAspectEarly。
- Dependencies and consumers: 三个 AutoConfiguration、Dispatcher、测试 Store。
- Why now: 先写模式 RED。
- Contract/signature changes: 默认 LEGACY、自定义缺 capability 拒绝安全模式。
- Input/output and state mapping: pause 时生产仍入队，poll 和 afterCommit/Store 门关闭，合法 ACK 可完成。
- Error and edge behavior: COORDINATED 新路由/配置不成立则 fail closed。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 扩配置矩阵并保留 d3f5f9c 的 handler 事务 advice 回归。
- Verification contribution: L06，TEST-007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 覆盖完整启动入口。

#### File 2 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/OutboxOptionalAutoConfigurationTest.java`

- Purpose: 证明老配置、可选 Beans 不破坏安全门。
- Symbols: 暂停、旧扩展及 polling.enabled 组合。
- Repository evidence: 已有 optional transports、poller 生命周期夹具。
- Dependencies and consumers: Listener、TaskScheduler、配置。
- Why now: 覆盖旁路基线。
- Contract/signature changes: LEGACY polling=false 原语义；消费总门禁统一 veto。
- Input/output and state mapping: producer 及现有 handler Bean 仍可用。
- Error and edge behavior: 自定义 Store LEGACY 不被迫实现协调，unsupported 模式拒绝启动。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 证明老配置、可选 Beans 不破坏安全门。
- Verification contribution: L06，TEST-001/007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 装配测试可定位问题。

#### File 3 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/OutboxJdbcAutoConfigurationTest.java`

- Purpose: 增加模式/暂停/失败配置测试。
- Symbols: JDBC V1/V2 验证和 worker 模式。
- Repository evidence: 原 outboxStore 及 infra Test。
- Dependencies and consumers: JDBC validator、Store、事务模板。
- Why now: 配置路径先有 RED。
- Contract/signature changes: LOCAL 可 V1，COORDINATED 要 V2。
- Input/output and state mapping: 选中同 datasource 及事务管理器；库角色由实际 PGT 验证。
- Error and edge behavior: 安全缺表拒启，不自动迁移。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 增加模式/暂停/失败配置测试。
- Verification contribution: L06，TEST-003/007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 构建条件清晰。

#### File 4 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/OutboxMybatisPlusAutoConfigurationTest.java`

- Purpose: 覆盖 legacy 兼容与严格门。
- Symbols: 新技术 ignore、MapperScan 和 readiness。
- Repository evidence: validateStoreInfrastructure 及合成 profile 测试。
- Dependencies and consumers: MP config、原 MsgRepository。
- Why now: 先写拒绝缺 mapper 或跨组。
- Contract/signature changes: 技术三表、fixed route、selected SqlSessionFactory。
- Input/output and state mapping: 协调验所需 namespace、tenant ignore 与 DB 预算。
- Error and edge behavior: 不能猜测默认 SqlSessionFactory 或自行退旧。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 覆盖 legacy 兼容与严格门。
- Verification contribution: L06，TEST-003/007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: MP 装配 RED 具证。

#### File 5 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/OutboxBusinessStateMachineAutoConfigurationTest.java`

- Purpose: 证明 Dispatcher 的窄分流能在真实 Spring proxy 下生效。
- Symbols: 已有 adapter / 严格 producer 兼容验证。
- Repository evidence: 现有 adapter 和业务 state machine wiring。
- Dependencies and consumers: 由 Step 5 修正的内置 handler。
- Why now: 补代理和逐路径测试。
- Contract/signature changes: 相同 Spring 代理注册内置 adapter。
- Input/output and state mapping: 合法缺定义按配置分类；有定义同 receipt，非法租户拒绝。
- Error and edge behavior: 外部自定义实现与原生产契约不受影响。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 证明 Dispatcher 的窄分流能在真实 Spring proxy 下生效。
- Verification contribution: L06，TEST-006。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 区分单测和装配失败。

#### File 6 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/OutboxPostgresqlReadwriteIntegrationTest.java`

- Purpose: 加入真 Mapper 和 Store 路径验证，保持原用例。
- Symbols: 新增主库权威／副本角色测试。
- Repository evidence: Step 2 已更新的单组 topology。
- Dependencies and consumers: 真实 MP Store/Starter 和 replica fixture。
- Why now: 新增 active 路径 RED。
- Contract/signature changes: 三表业务 scoped/technical 读写。
- Input/output and state mapping: 停 replay 后 guard/claim/ACK 仍走 primary；普通读只用副本。
- Error and edge behavior: 启动 replay 未达则不 ready。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 加入真 Mapper 和 Store 路径验证，保持原用例。
- Verification contribution: L06，TEST-003/007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 真实读写分离有物理证据。

#### File 7 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/OutboxPostgresqlMultishardIntegrationTest.java`

- Purpose: 按 §8.2 MPI-01 至 MPI-06 增真端到端，不依赖测试自写 UPDATE。
- Symbols: 真实 Store、Dispatcher、两个主组和降级回退。
- Repository evidence: 旧 atomicShardWrites... 用 claimScoped 模拟。
- Dependencies and consumers: 生产 Starter、实际 SQL，四个独立 endpoint。
- Why now: 旧模型先 RED，随后验证完整装配。
- Contract/signature changes: A/B 同 id 不同 slot；锁、replica lag、连接失败、过期与 mode drainer。
- Input/output and state mapping: 收集总表差异、真实 SQL、事务目标、worker start、ACK。
- Error and edge behavior: 错误路由无写；B 失败不撤 A；旧 epoch 和过期 ACK 无影响；同 Jar LOCAL 恢复。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 按 §8.2 MPI-01 至 MPI-06 增真端到端，不依赖测试自写 UPDATE。
- Verification contribution: L06，TEST-002/003/004/005/007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 2×2 物理验收可执行。

#### File 8 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/OutboxJdbcAutoConfiguration.java`

- Purpose: 接通两安全契约及消费闸门，保留旧自定义条件。
- Symbols: outboxStore 模式参数、V2 readiness 和 DB 预算。
- Repository evidence: 当前创建本地 REQUIRES_NEW 模板和 JDBC schema validator。
- Dependencies and consumers: Dispatcher、选定 datasource。
- Why now: Store 完成后连接。
- Contract/signature changes: LEGACY 不要求 V2；active COORDINATED 验表、明确能力。
- Input/output and state mapping: 按已有 qualifier 和选择器使用 JDBC 事务管理器。
- Error and edge behavior: 不自动建表；拒 V2 无约束、错 route/设置。
- Standards impact: MC-BEAN-001, MC-CONFIG-001, MC-VALID-001。配置 POJO 绑定，Jakarta @Validated/@Valid、分类与边界校验；Boot 配置键共用。新增 DI 保持 Bean 名、@Qualifier 和 @RequiredArgsConstructor(onConstructor_ = @Autowired)。
- Literal rule enforcement: Rule 2, Rule 3, Rule 4, Rule 7, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 接通两安全契约及消费闸门，保留旧自定义条件。
- Verification contribution: L06，TEST-003/007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: JDBC 模式矩阵达到 GREEN。

#### File 9 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/OutboxMybatisPlusAutoConfiguration.java`

- Purpose: 装配安全模式的真实逻辑链，LEGACY 无运行时协调。
- Symbols: validateStoreInfrastructure、outboxStore 与 qualifier。
- Repository evidence: 现有 @MapperScan、logical factory、named transaction。
- Dependencies and consumers: MP Store、两个 DAO/Converter、profile。
- Why now: 路由/mapper 就绪后装配。
- Contract/signature changes: 验证 exact namespace XML 与技术三表；继承 Common Snowflake 与 MP tenant ctx。
- Input/output and state mapping: 选定 SqlSessionFactory、guard、DDL 就绪，MapStruct Bean 稳定。
- Error and edge behavior: 盘点已验证事务和驱动预算；拒绝 schema/fingerprint/replica 不合格。
- Standards impact: MC-BEAN-001, MC-CONFIG-001, MC-VALID-001。配置 POJO 绑定，Jakarta @Validated/@Valid、分类与边界校验；Boot 配置键共用。新增 DI 保持 Bean 名、@Qualifier 和 @RequiredArgsConstructor(onConstructor_ = @Autowired)。
- Literal rule enforcement: Rule 2, Rule 3, Rule 4, Rule 7, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 装配安全模式的真实逻辑链，LEGACY 无运行时协调。
- Verification contribution: L06，TEST-003/007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: MP 新 Store 可按模式启动。

#### File 10 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/TransactionalOutboxAutoConfiguration.java`

- Purpose: 只有验证完毕才连通安全路径，保留老 Bean 路径与生产 CQE。
- Symbols: outboxDispatcher、scheduler、executor、监听连接与 pause。
- Repository evidence: 现有 named executor、dispatcher、transactionalMessageAop。
- Dependencies and consumers: Store、Coordinator、Poller、Listener、legacy custom。
- Why now: 最后启用关口时做完整 Bean wiring。
- Contract/signature changes: 按 mode select，消费关闭仍可 enqueue；独立 control/claim/handler 资源。
- Input/output and state mapping: 启动确认能力及 schema，有界 drain，保留 @Lazy/late registry。
- Error and edge behavior: configuration mismatch fail closed，不绕回遗留领取；安全消费需有效 poll。
- Standards impact: MC-BEAN-001, MC-CONFIG-001, MC-VALID-001。配置 POJO 绑定，Jakarta @Validated/@Valid、分类与边界校验；Boot 配置键共用。新增 DI 保持 Bean 名、@Qualifier 和 @RequiredArgsConstructor(onConstructor_ = @Autowired)。
- Literal rule enforcement: Rule 2, Rule 3, Rule 4, Rule 7, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 只有验证完毕才连通安全路径，保留老 Bean 路径与生产 CQE。
- Verification contribution: L06，TEST-001/002/007。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 两种安全模式可整体启用。

- Validation working directory: `/Users/mario/SelfProject/Stellar-Enclosure/Stellar-Enclosure-Components`。
- Verification command: `./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dtest=TransactionalOutboxAutoConfigurationTest,OutboxOptionalAutoConfigurationTest,OutboxJdbcAutoConfigurationTest,OutboxMybatisPlusAutoConfigurationTest,OutboxBusinessStateMachineAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test` 以及 `EGON_OUTBOX_PG_MULTISHARD_IT=true ./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dit.test=OutboxPostgresqlMultishardIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false verify`。见 §8 的 L06。
- Expected result: 有效环境下命名断言全部通过，新增结果/side effects 与本 Step 成功条件一致。PG opt-in 有效用例的 skipped 必须为 0。
- Failure returns to: 先核对所选 fixture、参数和真实目标；状态、SQL、路由或装配不符按 §8 L06 回相应文件。缺环境标记为未验收。
- Completion criteria: 总门禁覆盖 poll/afterCommit/内置 claim，真实 2×2 故障及回退。保留测试输出、必要的 schema/路由证据，检查本 Step 暂存路径、契约和已有场景。
- Rollback: 未部署时只回退该 Step 的源码路径，已进入发布后执行 §9.4 的同版本暂停、排空与 LOCAL 回退。
- Commit paths: `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/TransactionalOutboxAutoConfigurationTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/OutboxOptionalAutoConfigurationTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/OutboxJdbcAutoConfigurationTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/OutboxMybatisPlusAutoConfigurationTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/autoconfigure/OutboxBusinessStateMachineAutoConfigurationTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/OutboxPostgresqlReadwriteIntegrationTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/OutboxPostgresqlMultishardIntegrationTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/OutboxJdbcAutoConfiguration.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/OutboxMybatisPlusAutoConfiguration.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/autoconfigure/TransactionalOutboxAutoConfiguration.java`。
- Commit: `feat(outbox): wire safe modes and verify multishard failover`。

### Step 7 — 完成阶段指标、PG 性能门槛和发布文档

- Requirements: REQ-002、REQ-003、REQ-005、REQ-006、REQ-007、REQ-008
- Dependencies: Step 6
- Baseline state: 安全模式已证实可运行；吞吐收益和模式切换指标未验。
- Observable outcome: 阶段指标可观察，JDBC 批量和 MP 真实压力可量化，有可执行 runbook。
- End state: 全量测试、真实 PG 证据和文档齐备；可选快速路径在门槛未过时保持单条。
- Test-first gate: Required — 现有成功指标不能识别 DB ACK，等容量的多实例批载数据不足。测试方法先写，缺类型才先补可编译签名，完成后执行同一选择器收集 GREEN。
- Manual Checks: MC-ARCH-001, MC-REUSE-001, MC-DEP-001, MC-LOG-001, MC-BEAN-001, MC-CONFIG-001, MC-TIME-001, MC-SCOPE-001, MC-TEST-001。
- Literal Rules: Rule 2, Rule 4, Rule 5, Rule 7, Rule 9, Rule 10, Rule 11。
- Ordered files:

#### File 1 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/observability/MicrometerOutboxMetricsTest.java`

- Purpose: 区分 ACK unknown 和 handler 成功，验证 no-op fallback。
- Symbols: 新增阶段指标／标签断言。
- Repository evidence: 现有 registry 指标测试。
- Dependencies and consumers: MicrometerOutboxMetrics。
- Why now: 指标计数先 RED。
- Contract/signature changes: claim、handoff、handler result、confirmed 按 distinct event。
- Input/output and state mapping: 以受控 shard/channel/reason 聚合；旧模式兼容。
- Error and edge behavior: token、message ID、payload 不出现在标签。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 区分 ACK unknown 和 handler 成功，验证 no-op fallback。
- Verification contribution: L07，TEST-008。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 有具体监控 RED。

#### File 2 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/PostgresqlOutboxQueryPlanIntegrationTest.java`

- Purpose: 扩现有真实性能调用，详细预发采样见 §8.3。
- Symbols: 候选计划与 SQL/行放大基线。
- Repository evidence: shouldExposeRequiredSchemaAndUseClaimIndexes。
- Dependencies and consumers: 真实 Store、PG 内核和索引。
- Why now: 先定义与 Step 6 路由独立的负载对照。
- Contract/signature changes: 基线与 JDBC 1/2/4；MP 单条及普通/过期扫描。
- Input/output and state mapping: 记录命中现有索引、过滤、RT 和应用事务延迟。
- Error and edge behavior: 无条件新增索引方案不进入本次；慢通道导致延迟则禁止放量。
- Standards impact: MC-TEST-001, MC-SCOPE-001。JUnit Jupiter/AssertJ 和现有夹具。精确断言副作用/无副作用，失败按范围回退，测试与生产路径保持同 Package 约定。
- Literal rule enforcement: Rule 2, Rule 9, Rule 11。固定组件测试入口和独立失败断言，使用当前 fixture；具体输入、异常和门槛见本文件。
- Required change: 扩现有真实性能调用，详细预发采样见 §8.3。
- Verification contribution: L07，TEST-008。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 有可复现的最小压力对照。

#### File 3 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/observability/MicrometerOutboxMetrics.java`

- Purpose: 接好分阶段统计、重领与拒绝诊断，既有 metrics API 继续可用。
- Symbols: 实现 Step 5 默认阶段回调。
- Repository evidence: 原 Micrometer MeterRegistry 与命名。
- Dependencies and consumers: Dispatcher、Coordinator、现有 metrics AutoConfig。
- Why now: 指标合同已失败后实现。
- Contract/signature changes: 阶段、租约与池使用；保留原 metrics。
- Input/output and state mapping: 已提交才 confirmed，按受控标签限基数。
- Error and edge behavior: 禁止全 token、payload；没有 registry 仍老 no-op。
- Standards impact: MC-LOG-001, MC-BEAN-001, MC-PATTERN-001。@Slf4j、命名 Bean 与 Qualified 构造注入；只用 JDK/Spring 调度组件，单调耗时，复用现有 handler registry 和技术状态机。
- Literal rule enforcement: Rule 2, Rule 4, Rule 5, Rule 7, Rule 9, Rule 10, Rule 11。按照上面的类型/注入/验证约束和现有组件结构交付；具体输入、异常和门槛见本文件。
- Required change: 接好分阶段统计、重领与拒绝诊断，既有 metrics API 继续可用。
- Verification contribution: L07，TEST-008。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: metrics RED 可绿色。

#### File 4 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/README.md`

- Purpose: 按 §8、§9 同步英文配置/验收与回退步骤。
- Symbols: Dispatch Config、真实 MP 环境和发布回退。
- Repository evidence: 当前 Config 表、PostgreSQL readwrite/multishard 章节。
- Dependencies and consumers: 宿主、DBA、治理测试。
- Why now: 最终核对实现默认值后写明。
- Contract/signature changes: §16.1 所有新键、真实两主 env、§9.4 同版 LOCAL。
- Input/output and state mapping: 按制品区分 manifest 迁移、模式和批量；温和排空。
- Error and edge behavior: 旧历史 mismatch 后不可降旧包，不改 DEAD 或消息 ID。
- Standards impact: MC-CONFIG-001, MC-SCOPE-001。配置键集合和原 ABI 保持双语一致，保留执行环境边界与 DDL 不可变性，公开文档不输出凭证或 token。
- Literal rule enforcement: Rule 7, Rule 11。记录一致的配置键、回退和范围；具体输入、异常和门槛见本文件。
- Required change: 按 §8、§9 同步英文配置/验收与回退步骤。
- Verification contribution: L07，TEST-007/008。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 英文文档可执行。

#### File 5 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/README.zh-CN.md`

- Purpose: 补齐相同配置键及中文演练步骤。
- Symbols: 同英文配置键、MP gate、回退。
- Repository evidence: 现有中文 Options、集成验收段。
- Dependencies and consumers: 中文接入方及治理测试。
- Why now: 同英文逐项校验。
- Contract/signature changes: 相同九类配置键与 README 的默认值及工作流。
- Input/output and state mapping: 模式和 schema 约束相同。
- Error and edge behavior: 拒绝旧 Jar 与双模式同库竞争。
- Standards impact: MC-CONFIG-001, MC-SCOPE-001。配置键集合和原 ABI 保持双语一致，保留执行环境边界与 DDL 不可变性，公开文档不输出凭证或 token。
- Literal rule enforcement: Rule 7, Rule 11。记录一致的配置键、回退和范围；具体输入、异常和门槛见本文件。
- Required change: 补齐相同配置键及中文演练步骤。
- Verification contribution: L07，TEST-007/008。由本 Step 的同名选择器或 §8 对应 PG 场景观测。
- After this file: 双语键集合一致。

#### File 6 — `MODIFY stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/contract/ComponentContractGovernanceTest.java`

- Purpose: 随双语 README 新增十个公开配置键，维持现有治理断言的精确匹配。
- Symbols: englishAndChineseReadmesDocumentTheSameCqeMpAndMigrationKeys。
- Repository evidence: 原 matcher 识别全部完整配置键，旧期望集合不含 dispatch 和 coordination。
- Dependencies and consumers: 两个 README 与 TransactionalOutboxProperties。
- Why now: L07 实际 RED 显示十项新键被识别，但旧集合拒绝它们。
- Contract/signature changes: 两种语言均须包含原有 11 键和新增 10 键。
- Input/output and state mapping: 可见配置键与当前启动/回退流程严格一致。
- Error and edge behavior: 缺失、多余或双语不一致继续失败。
- Standards impact: MC-CONFIG-001, MC-SCOPE-001, MC-TEST-001。
- Literal rule enforcement: Rule 7, Rule 11。沿用当前治理测试的精确检查。
- Required change: 将 §16.1 十项新键追加到固定期望集合。
- Verification contribution: L07，TEST-007/008。
- After this file: 重跑双语治理、阶段指标和现有行为。

- Validation working directory: `/Users/mario/SelfProject/Stellar-Enclosure/Stellar-Enclosure-Components`。
- Verification command: `./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dtest=MicrometerOutboxMetricsTest,ComponentContractGovernanceTest -Dsurefire.failIfNoSpecifiedTests=false test` 以及 `EGON_OUTBOX_TEST_POSTGRES_ENABLED=true ./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dit.test=PostgresqlOutboxQueryPlanIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false verify`。见 §8 的 L07。
- Expected result: 有效环境下命名断言全部通过，新增结果/side effects 与本 Step 成功条件一致。PG opt-in 有效用例的 skipped 必须为 0。
- Failure returns to: 先核对所选 fixture、参数和真实目标；状态、SQL、路由或装配不符按 §8 L07 回相应文件。缺环境标记为未验收。
- Completion criteria: 阶段指标可观察，JDBC 批量和 MP 真实压力可量化，有可执行 runbook。保留测试输出、必要的 schema/路由证据，检查本 Step 暂存路径、契约和已有场景。
- Rollback: 未部署时只回退该 Step 的源码路径，已进入发布后执行 §9.4 的同版本暂停、排空与 LOCAL 回退。
- Commit paths: `stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/observability/MicrometerOutboxMetricsTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/integration/PostgresqlOutboxQueryPlanIntegrationTest.java`、`stellar-enclosure-component-transactional-outbox-starter/src/main/java/top/egon/cola/component/outbox/observability/MicrometerOutboxMetrics.java`、`stellar-enclosure-component-transactional-outbox-starter/README.md`、`stellar-enclosure-component-transactional-outbox-starter/README.zh-CN.md`、`stellar-enclosure-component-transactional-outbox-starter/src/test/java/top/egon/cola/component/outbox/contract/ComponentContractGovernanceTest.java`。
- Commit: `feat(outbox): expose dispatch diagnostics and document release gates`。

## 8. Test, Validation, and Quality Gates

### 8.1 命令和证据层级

每条命令在组件仓根运行。单测和 PG 用例分别由 Surefire、Failsafe 收集。聚合模块可以没有指定用例，验收只认可目标模块报告中命名用例真正执行。PG 容器和真实主从命令使用各自的开关与隔离环境。每步先执行相同选择器记录 RED，再实现并记录 GREEN。新类型尚不能编译时先补契约，再触发业务 RED。[Maven Failsafe 选择与无匹配参数说明](https://maven.apache.org/surefire/maven-failsafe-plugin/integration-test-mojo.html)

| Gate/order | Working directory | Command or method | Scope | Expected result | Failure returns to | Requirements/runtime boundary |
| --- | --- | --- | --- | --- | --- | --- |
| L01 | 组件仓根 | `./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dtest=OutboxLifecycleServiceTest,OutboxConfigurationValidatorTest,TransactionalOutboxPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test` | Lifecycle + properties/validator | 安全状态和配置 RED 转 GREEN，旧 LEGACY 路径通过 | Step 1 类型、Factory 或 Validator | REQ-004/005/006/008，离线 |
| L02 | 组件仓根 | `./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dtest=OutboxMigrationContractTest,ComponentContractGovernanceTest,OutboxShardSqlGuardTest,OutboxPrimaryReadPolicyTest,OutboxManagedDdlReadwriteTest -Dsurefire.failIfNoSpecifiedTests=false test`；`EGON_OUTBOX_TEST_POSTGRES_ENABLED=true ./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dit.test=OutboxManagedDdlIntegrationTest,OutboxMpMigrationIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false verify` | DDL 合同、固定 SQL 与真实 PG 管理迁移 | 校验三条历史、原两条摘要、物理新表结构、按目标恢复及旧清单拒绝 | Step 2 错配返回拓扑/DDL/guard；摘要错返回 manifest | REQ-001/003/006/007，含隔离 PG |
| L03 | 组件仓根 | `EGON_OUTBOX_TEST_POSTGRES_ENABLED=true ./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dit.test=JdbcOutboxPostgresqlIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false verify` | 单库实际 claim/ACK/兼容 | V1/V2、容量 1/2/4、超预算、old token 与 unknown 均执行 | Step 3 Store 或 Step 2 JDBC schema | REQ-001–006，隔离 PG |
| L04 | 组件仓根 | `EGON_OUTBOX_TEST_POSTGRES_ENABLED=true ./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dit.test=OutboxMpSharedContractIntegrationTest,PostgresqlOutboxRecoveryIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false verify`；`./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dtest=OutboxShardSqlGuardTest,OutboxPrimaryReadPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test` | MP mapper 形态和单组 PG Store | 三份 XML 固定形态；主库动作和 MP version CAS；真实 PG 恢复断言，失败行无副作用 | Step 4 mapper/repo/Store；形态合法但路由失败返 Step 2 | REQ-001–006，单组 PG 与静态 |
| L05 | 组件仓根 | `./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dtest=OutboxQueueCoordinatorTest,OutboxDispatcherTest,OutboxPollerTest -Dsurefire.failIfNoSpecifiedTests=false test` | 确定性 worker、协调、poll 及代理前后分流 | 4+1、丢 wake、A/B、unknown、关停、新旧定义全部通过 | Step 5 Dispatcher/Coordinator/Poller/Handler | REQ-001–006/008，离线组件 |
| L06 | 组件仓根 | `./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dtest=TransactionalOutboxAutoConfigurationTest,OutboxOptionalAutoConfigurationTest,OutboxJdbcAutoConfigurationTest,OutboxMybatisPlusAutoConfigurationTest,OutboxBusinessStateMachineAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`；`EGON_OUTBOX_PG_READWRITE_IT=true ./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dit.test=OutboxPostgresqlReadwriteIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false verify`；`EGON_OUTBOX_PG_MULTISHARD_IT=true ./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dit.test=OutboxPostgresqlMultishardIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false verify` | Spring Boot 装配、独立单主读写、真实两主两副本 | 新旧安全门、物理路由、全部 MPI 验收；两个 opt-in 套件非跳过 | Bean 错返 Step 6，物理/epoch 错返 Step 2/4，交付错返 Step 5 | REQ-001–008，隔离的真实主从 |
| L07 | 组件仓根 | `./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dtest=MicrometerOutboxMetricsTest,ComponentContractGovernanceTest -Dsurefire.failIfNoSpecifiedTests=false test`；`EGON_OUTBOX_TEST_POSTGRES_ENABLED=true ./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am -Dit.test=PostgresqlOutboxQueryPlanIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false verify`；`EGON_OUTBOX_TEST_POSTGRES_ENABLED=true ./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am clean verify` | 指标、双语、查询计划、完整容器回归、§8.3 预发性能 | 指标区分三个阶段，双语配置键对齐，criteria 满足，PG 报告无失败/误跳过 | Step 7（指标/文档）、Step 3/4（SQL）、Step 5/6（调度或装配） | REQ-002/003/005/006/007/008；全量容器与预发 |
| 每步 staged/final | 组件仓根 | `git diff --cached --check`，`git diff --cached --name-only`，`git status --short`，读目标模块的 `target/surefire-reports`、`target/failsafe-reports` | 差异、稳定 ABI、报告与产物 | 变更等于精确 Commit paths；报告关联构建 SHA、模式和环境组 | 当前 Step 补证据或清理非目标暂存 | 所有 REQ；无生产验收宣称 |

### 8.2 真 PostgreSQL MP 多分片测试

**环境与安全边界。** 使用两个独立可写 primary，每组各有一套持续流复制的只读 replica。测试账号能运行现有身份校验，数据库为空且专供该测试。单组用例需要 `EGON_OUTBOX_PG_READWRITE_IT=true`、`EGON_OUTBOX_PG_PRIMARY_URL`、`EGON_OUTBOX_PG_REPLICA_URL`。多分片用例需要 `EGON_OUTBOX_PG_MULTISHARD_IT=true`、`EGON_OUTBOX_PG_SHARD_0_PRIMARY_URL`、`EGON_OUTBOX_PG_SHARD_0_REPLICA_URL`、`EGON_OUTBOX_PG_SHARD_1_PRIMARY_URL`、`EGON_OUTBOX_PG_SHARD_1_REPLICA_URL`。两者均从隔离环境注入 `EGON_OUTBOX_PG_USER`/`EGON_OUTBOX_PG_PASSWORD`，报告只保留别名 A/A′/B/B′，不记录实际凭证。未配置 opt-in 的跳过结果不能用于发布。A/B 指实际从 route profile 解析出的主组，测试不要将主库 B 的实际 slot 硬编码为 1。

**证明口径。** 在测试环境启用 ShardingSphere 的已有的路由/实际 SQL 观察手段，给每个测试动作关联唯一 fixture、阶段和槽位。必须读取每个物理 primary 的相关记录或变更计数作交叉证据。应用操作使用真实 `MybatisPlusOutboxStore`、生产 DAO、逻辑 datasource、Dispatcher 与正常工厂装配。测试导出的物理连接仅用于隔离环境的读侧检查、已授权故障注入及数据夹具，与运行时逻辑请求分开。保留现有 `atomicShardWritesAndConcurrentClaimsStayOnTheirSelectedPrimaries` 路由基线，但不得把原 `claimScoped` 测试辅助语句当成新的装配/执行证据。

| Case | Setup / action | Required evidence | Pass or fail |
| --- | --- | --- | --- |
| MPI-01 节点与版本 | 构造真实 A/A′、B/B′；在每组主库应用新 manifest。选取一组模拟中间失败、恢复后同制品重跑；验证升级前旧指纹与升级后尾项 | 两主的 route/role、可用事务管理器、三条 history 的 version/SHA 和尾部新指纹；旧记录经审核 old fingerprint；两个副本的 WAL 水位/结构；旧清单读取新历史触发拒绝 | 任一物理目标少表、少历史、历史漂移、replay 未达即不 ready |
| MPI-02 每个操作的主库归属 | A/B 准备可区分消息与相同 row id，两边各配不同能力实例；真实 coordinate、claim、finish。另提交坏 slot、错 epoch 和明确缺 slot 请求 | 每个事务只路由一个主目标；物理 A/B 的 member/lease/message-before/after diff；ACK 用 claim 原 slot。拒绝请求无物理副作用；原业务事务跨组 guard 仍生效 | 不得广播、跨组写、回读错误主库或用测试辅助 claimant 代替 |
| MPI-03 提交与故障隔离 | barrier 保证 A 真实 PostgreSQL commit 完成；此时切断 B 的 claim 连接或令其超时。再从单个 worker 等到 handler 回执 | A commit → A handoff → A handler start 可关联，顺序和时间独立于 B 错误；A 最终写回仍在 A；B 无 handler 及多余状态更改，按自己退避，恢复后推进 | A 已提交但只在汇总等待、未交付或丢批判失败 |
| MPI-04 双 lease / 旧 owner | 用独立连接持有归属行的 claim 锁并竞选转移。用两条已领消息分别保留有效租约、超过截止点；停旧 owner 或释放有效 epoch，备选接管；旧实例随后恢复 | 转移与同 shard claim 的 guard 锁冲突且事务短有界；epoch +1、旧 epoch 续约或 claim 失败；仍有效旧消息可 ACK，等号及过期 CAS 为零，原 ID 过期重领且新 token 安全 | 无权者扫 due、提前强行回收、旧 token 覆盖新状态任一失败 |
| MPI-05 延迟与未知提交 | 集群启动完整后暂缓 A′ 复制；真实主库技术操作照常完成。分开注入 claim 事务 commit 前/后客户端断连、handler 成功但 ACK 前终止、ACK 提交结果未知 | authoritative lock/claim/ACK 只在 primary，普通业务读继续 replica 路径；未确认 claim 不调 handler，旧 ID 可恢复；ACK 只确认重读/限时重试，receipt 去重；本地预约能释放或安全退场 | 副本陈旧导致错误授权、ACK unknown 重调 handler，或者去重测试缺失均失败 |
| MPI-06 模式回退 | 两个新 consumer 协调运行，在有在途合法租约和已超预算终态时启动 §9.4；重新启动同一制品为 LOCAL 单条 | 暂停后的老入口无新领，原有消息 token 的保护、queue epoch、history/hash 均可对账；合法在途完成或按租约恢复；LOCAL 从待分发继续且 DEAD 未自动领取 | 同时存在不同模式的共享消费者、旧 Jar 替换、删除 DDL/epoch 或重置消息判失败 |

对数据库预算额外验收 **三段超时**。启动期核对所选连接池的 connectionTimeout 以及 PostgreSQL 的 lock_timeout、statement_timeout 和 pgJDBC 的 connectTimeout、socketTimeout。Dispatcher 以单调钟为借用、取锁、执行及 commit 共用一个操作预算。socketTimeout 只作为单次网络读的上限，不能替代请求总 deadline。用隔离池满、持锁、以及 commit 响应断连三类故障证明 worker 不会提前收到消息，unknown 有界返回、在途事务未退出时保留该 shard 的执行保护、不让失败循环耗尽池。仅设置 Spring transaction timeout 不能作为此门通过的证据。具体驱动及目标生效值随预发报告记录。不能证明时保持消费暂停，或回到独立验证合格的 LOCAL 路径。[pgJDBC 参数说明](https://jdbc.postgresql.org/documentation/use/)

### 8.3 PostgreSQL 压力与放量

固定消息总量、生产速率、消费限额、handler 完成时间分布、数据集和 PostgreSQL 资源，每个样本至少重复三轮。覆盖空轮、均匀、单热队列、慢队列、积压、过期恢复。副本数 1/3/6，分片单、双及预发实际数。比较 LEGACY 旧实现、LOCAL/COORDINATED 单条、JDBC COORDINATED 2/4；MP 只以已经证明的安全 SQL 比较，快速 MP 路径属于后续独立评审。记录每主库的查询与锁、池等待、WAL/死元组/回放、业务与交付 p95/p99、每条成功消息的 claim 往返、稳定期非 owner due SQL、接管耗时。一条队列的容量受单 owner 的 worker 与资源限制。

查询计划和基础小批用 L07 既有类。跨多个预发副本的数据采集没有现成组件命令。未来 Step 7 允许在实施测试环境创建临时 `scripts/work/2026-11-23-00-04-outbox-coordinated-dispatch-implementation-measure.sh`，只对隔离环境采集已有运行/DB 证据、输出环境别名及指标，命令入口为从组件仓根执行 `bash scripts/work/2026-11-23-00-04-outbox-coordinated-dispatch-implementation-measure.sh`。harness 在试验结束时删除，测量报告按发布记录留存，脚本不暂存、不纳入任何 commit。预发采集需明确提供隔离环境和其已审核的数据库/业务延迟预算。

只有真实路由、安全与恢复通过、稳定期非 owner due scan 为 0、相同负载下 claim 往返下降且业务延迟/DB 容量在已审批预算内，才在相应发布环境启用 JDBC 2/4。否则保持 1 或按 §9.4 降级。目标性能与环境不具备时记未验收，本 Plan 不预填性能结果。

## 9. Migration, Compatibility, Rollout, and Rollback

### 9.1 迁移前盘点与部署互斥

1. 固定最终新制品与用于比较旧制品的 SHA、目标 DB、主副本角色、当前 route fingerprint、旧两条历史和旧 SQL hash。演练实际使用的部署配置，包括两个新技术表的合法拓扑、MP tenant-ignore、SELECT/锁和 DDL 角色。JDBC 盘点 V1。用只读盘点记录异常消息租约、超预算和分片、可用 worker 容量及非空队列。
2. 枚举所有共享消息表的 consumer。范围含旧 Jar、定时轮询、本地 afterCommit、自定义领取、不同应用副本/异构 handler 版本。确认同 channel 所需定义及路由能力。在共享域切换前停止现有所有领取方。旧版只有 `polling.enabled=false` 时，afterCommit 仍可能领取，需退出消费域或使用已验证的隔离方式。在各组准备停机/drain；旧实例不能被自动扩容或重启回旧消费者注册。
3. 预部署在达成「无新领取、合法在途 finish 仍允许」后继续。应用启动绑定处理配置，Plan 不假定热刷新。用读侧指标和测试确认审批的停机方式能够耗尽 claim 短事务。若必须中断 handler，其原消息租约自然到期，不能人为清空。继续生产仅限已验入队的部署和同事务路径。

### 9.2 DDL 及制品激活顺序

| Order | Target/action | Observable go/no-go |
| --- | --- | --- |
| M01 MP 配置 | 新制品先按暂停消费部署：`dispatch.consumption-enabled=false`。两张协调表随 message 进入同一 SLOT STRATEGY：多主用现有 TECHNICAL_GROUP；单主同 group、slot 0；history 逻辑 SINGLE 保留 | 新旧渲染 YAML 和物理组列表对照；仅加入核实过的 `storage.mp.previous-route-fingerprints` 值。能确认未做业务扩路由或新分片拓扑 |
| M02 MP 增量 | 使用现有受管 DDL 启动路径向每个 primary 的 `egon_outbox` 应用 `V20261122_001__add_outbox_queue_coordination.sql` 及新 manifest。按部署规范由指定的受管初始化器或已授权执行方执行 | 每主的 DDL 与历史同事务、已有行不变；新行摘要/尾部指纹和物理结构全部合格，再等各 replica WAL 回放；任一未完成则仍暂停 |
| M03 JDBC 增量 | 部署方按该服务既有迁移路径应用同 schema 的 `V2__add_outbox_queue_coordination.sql`，JDBC Starter 只校验 | 新 V2 结构通过。LOCAL 可按兼容路径 V1，COORDINATED 验证两个技术表 |
| M04 同版 LOCAL | 严格 schema ready、无旧消费者后，同版 LOCAL / batch 1 启动试点 | 暂停向启动的切换生效、历史任务可进展、真实 A/B 和消息 lease 守卫通过。此项是回退基线 |
| M05 协调启用 | 试点使用隔离共享存储，或者完整共享组按同一窗口先暂停并 drain LOCAL，再启同版 COORDINATED。参与组内支持能力匹配 | 多实例有唯一 owner，空查/恢复指标可观察，旧路径无扫描；各健康 shard 独立。确认 §8.2 |
| M06 SQL 放量 | JDBC 按 §8.3 1 → 2 → 4，任一退化缩回 1 | 每次变更都重新采集压力和恢复证据，MP 保持单条安全形态 |

### 9.3 部分失败及前向恢复

以实际 PG 物理主组核对 history 和结构，不把某一组成功当集群整体成功。某主库 DDL 或连接 commit 不确定时，在新连线上检查本版本记录和脚本结构，按现有管理器的校验判断是否提交。只在确认目标尚未完成且无漂移时由现有管理器续跑缺的目标；已经成功的版本经合法 hash 验证后跳过。若是 fingerprint、checksum 或约束漂移，保持暂停，先查制品和实际路由再走受审前向修正。禁止倒序删表、改 history、绕过副本 replay 或手工补写 checksum。进入任何真实环境前需执行审批流程。

### 9.4 回退步骤与验收

1. **标定回退动作。** JDBC 只有升批导致延迟/压力超预算时，按部署窗口缩回 `dispatch.max-claim-batch=1`。归属争用、路由守卫失败、A/B 故障隔离问题或影响业务写入时，执行以下同版 LOCAL 回退。实际 MP schema/route 无法校验时维持消费暂停并按 §9.3 恢复，不能跳过校验直接 LOCAL。
2. **停止新领取。** 对共享存储域全部协同实例先触发受控暂停或终止/drain，确保不会被回滚控制器重新拉起活跃 COORDINATED。可先把同制品替代实例以 `dispatch.consumption-enabled=false` 启动。检查 Poller、afterCommit 和内置 Store claim 的新进入为零；保留已进入的有限 DB 事务与合法消息 ACK。
3. **排空在途。** 比对执行器 reserved/assigned/running、各 slot 的 claim in-flight、queue 与 message 的 deadline。等待短事务终结，正常让 handler 在原消息租约内 finish。归属只用当前 epoch 的释放路径或等待自然到期。消息仍活跃、提交未知或进程被终止时保留原行及 token，后续仅按 DB 租约处理。禁止批量改 PENDING/PROCESSING、清 lease、重发号或自动重驱 DEAD。
4. **用同一制品恢复。** 保留已经部署的两张表、已升级 `ddl_history`、原 epoch 和新逻辑 topology。设置 `dispatch.mode=LOCAL`、`dispatch.max-claim-batch=1`、按 Spec 设置 `polling.enabled=true`、`retry.recover-exhausted=false`。实例先暂停启动/检查 ready；确认所有协调领取者均退出或暂停，再逐批启用 consumption，直到共享组健康。切换只改合格的新制品配置，并举证没有新旧模式重叠领取。
5. **证明回退成功。** 以同一消息 ID 回放 A/B 逾期与未处理 case；确认成功/重试增长、积压时间回落、无新 ownership 扫描、有效旧 token 不被误拒/过期不被重新接纳、DEAD 没有重驱。逐个主库核对 V20261122_001 的 history、hash 与保留 epoch，记录切前后同版制品 SHA。指标或 DBA 核验失败则再停消费并前向修正，无静默回到 LEGACY。

| Artifact/config | Before new MP DDL | After any MP target records new history |
| --- | --- | --- |
| 旧 Jar＋旧路由 | 只在旧全域和旧 schema 管理边界支持 | 新行超出旧 manifest，预期旧校验报 `DDL_HISTORY_PREFIX_MISMATCH`；旧 Jar 不能当常规回退 |
| 新 Jar 暂停消费 | 部署窗口可用于受管初始化，须以实际 DDL 状态和验证开关为准 | 每组完成后可做生产和验证；任一结构异常保持暂停 |
| 新 Jar LOCAL / COORDINATED | 不在混版共享域启用安全消费 | LOCAL 是同版已验证回退；COORDINATED 完整生效时旧领取入口禁止 |
| 前向治理 | 保留旧 SQL/校验，按新制品统一升级 | 历史只能追加合法版本；独立旧包桥接属于新设计，非本次动作 |

## 10. Requirement-to-Step Traceability Matrix

| Requirement | Effective Spec section | Steps | Files | Tests/gates | Completion evidence |
| --- | --- | --- | --- | --- | --- |
| REQ-001 | §7.3.1、§9 INTERNAL-001、§11.2.1/2 | Step 2–6 | QueueCoordinator、两种 Store、两表/DAO | TEST-002/005，L03/L04/L05/L06 MPI-04 | 同 shard 唯一 owner，非 owner 不扫 due，旧 epoch 零影响 |
| REQ-002 | §7.3.2、JOB-001 | Step 3–7 | Dispatcher、Poller、claim 合同 | TEST-001/004/008，L03/L05/L06 | 4+1、真就绪、补扫及分阶段限额 |
| REQ-003 | §7.3.4、§11、§16.2 | Step 2–7 | ManagedDdlInitializer、Routing、MP DAO、真实 MP fixture | TEST-003/007/008，L02/L04/L06 MPI-01/02/03/05 | 真 SQL、各组物理结果、A/B 故障、无跨组事务 |
| REQ-004 | §7.3.1/3、§9 INTERNAL-003 | Step 1、Step 3–6 | QueueLease、FinishCommand、Store 和状态机 | TEST-004/005，L01/L03/L04/L06 | 旧 epoch/有效原 ACK/过期拒绝各自有证据 |
| REQ-005 | §7.3.3/5 | Step 1、Step 3–7 | Lifecycle、Store、Dispatcher、Recovery 测试 | TEST-004/005/006，L03/L04/L05/L06 | 预算和回执一次权威决策、同 ID 重放、无 DEAD 重驱 |
| REQ-006 | §7.3.5、§16 | Step 1–7 | AutoConfiguration、内置 handler、schema tests 与双语 README | TEST-001/003/006/007，L01/L02/L05/L06/L07 | 旧 API/生产回归、代理/tenant、老 schema 文件与启动门 |
| REQ-007 | §15–16 | Step 2、Step 6–7 | Obs、治理测试、MP fixture 与两 README | TEST-007/008，L06 MPI-06、L07、§9.4 | 代码测试与真实环境分栏，性能矩阵、同版回退记录 |
| REQ-008 | §3.2、§6、§7.3.3 | Step 1、Step 5–7 | 无新增第三方依赖，组件路径内合同与门控 | TEST-006/007，L01/L05/L06/L07 | Rust/JNI 排除，技术死信仍由运维，变更列表局限组件 |
| REQ-009（历史适用） | 依赖 Spec §4/7/9 | Step 4/6 | MybatisPlusOutboxStore、Repository/Mapper、AutoConfiguration | L04/L06、MPI-02 | MP 保持自己的真实路由和技术约束，失效明确拒绝 |
| REQ-010（历史适用） | 依赖 Spec §4/11 | Step 2/6/7 | manifest、Initializer、SchemaValidator、原 OutboxMpMigrationIntegrationTest | L02/L06/L07 | 旧资产哈希、原维护合同及同版回退核验 |

## 11. Risks, Blockers, and User Decisions

| ID | Risk or decision | Impacted Steps/files | Evidence | Owner | Status/action |
| --- | --- | --- | --- | --- | --- |
| RISK-001 | 新 manifest 与旧 Jar 的不兼容 | Step 2/6，Initializer 和 Schema Validator | validateHistoryPrefix 见额外行会拒绝；L02/L06 | 组件维护者、DBA | 设计已闭合，环境按 §9.1–§9.4 验证 |
| RISK-002 | 真多分片拓扑/副本环境缺失，报告可能误跳过 | Step 6 外部 PG 夹具 | README 双独立开关，旧用例只证路由基础 | 预发布验收者 | 测试资源待提供，生产准入门仍关闭 |
| RISK-003 | 长锁或真实连接等待吃掉消息租约 | Step 3/4/5/6，受管连接及 commit | Spec TIME-001/002；单一事务超时不能证明总预算 | 组件、DBA | §8.2 三段实测不达则保持安全模式不开或经验证回退 |
| RISK-004 | 元数据心跳掩盖扫库收益，热点单 lane 不随副本线性增长 | Step 7 性能门 | Spec §15，L07 | 组件与发布者 | 1/3/6 次数、WAL、业务 p99 量化；不达保持 1 或同版 LOCAL |
| RISK-005 | 交付/ACK unknown 触发下游重复副作用 | Step 3–6 和实际接入方 | 稳定消息 ID、合法消息租约与业务 receipt | 组件与 handler owner | 测试未知提交和 receipt；外部 HTTP/MQ 按其原去重契约验 |
| RISK-006 | Plan 处于待审状态 | 全部实施、发布 | 用户只批准编写此 Plan；目标 Spec 文件仍是 Review | mario | 等审核后的实施决定。当前无其他阻塞性设计问题 |

## 12. Review and Acceptance

### 12.1 Original requirement fidelity

按用户确认的 Outbox Spec 交付七步实施顺序、真实 PostgreSQL MP 验收和可演练的同版回退。文件范围严格是目标组件、其测试和双语文档。Rust/JNI 不进入实施清单。本轮执行仅完成 Plan 和 Spec 关系元数据。

### 12.2 Spec consistency

以指定 Spec 的 REQ-001 至 REQ-008 和规范依赖核对模式、接口、表结构、锁、双层租约、恢复、运维、回退。§6.4 的四项是代码边界、基线取证和顺序澄清。MP 快速 SQL 和更多队列维度依主 Spec 排除，现有技术状态机及原消息语义继续有效。

### 12.3 Repository executability

核过仓根模块、现有符号、MyBatis XML namespace、Java 25、Surefire/Failsafe 分组和各自 opt-in 变量。新增路径、编译前置、文件先后、测试与提交边界在 §5/7 可定位。操作者必须为真实 PostgreSQL 与发布提供隔离环境、账号和批准，缺证据时 L06/L07 未验收。

### 12.4 Test and release completeness

先 RED 再 GREEN；支持层验证完成后才构造及激活。MP 用实际生产 Store 与 Dispatcher、物理节点对账和故障注入。管理历史完整性、滚动升级边界及模式回退分别写进 §8–9。此处通过是 Plan 静态审阅，不代表代码、迁移、运行时或性能达标。

### 12.5 Blocking Manual Check

| Check ID | Applicability | Status | Evidence | Finding | Required action/exception |
| --- | --- | --- | --- | --- | --- |
| MC-ARCH-001 | Applicable | PASS | §4.7、§5、组件架构与 POM | 现有 Starter 包，生产与消费边界清晰 | None |
| MC-REUSE-001 | Applicable | PASS | §4.5–4.7、Store 和 Spring/MP 路径 | 复用模块已有能力，最小引入两个持久化表和调度主体 | None |
| MC-DEP-001 | Applicable | PASS | §4.7、§5 无 POM/依赖变更 | 现有 Java/Spring/MP/SSM 组合 | None |
| MC-NAME-001 | Applicable | PASS | Step 1、4、5 类型清单 | Command、BO、PO、DAO、Converter、值对象明确命名 | None |
| MC-VALID-001 | Applicable | PASS | Step 1、4、6；§4.8 Rule 2 | 输入、配置、DAO shape 和实际主库权限分别验证 | None |
| MC-MODEL-001 | Applicable | PASS | Step 1/4、§4.8 Rule 3 | class/record 分工，技术枚举编码稳定 | None |
| MC-CONVERT-001 | Applicable | PASS | Step 4 两个 Converter、OutboxMessageConverter | MapStruct + BaseForwardConverter，明确类型及 UTC 边界 | None |
| MC-LOG-001 | Applicable | PASS | Step 5/7、L07 | 安全日志、阶段计数与可复核状态分离 | None |
| MC-BEAN-001 | Applicable | PASS | Step 6，lombok.config 与 lazy 创建原回归 | Bean/Qualifier/MapperScan 及启动兼容可验 | None |
| MC-UTIL-001 | Applicable | PASS | §4.7、Step 5，JDK SHA-256、调度原 Bean | 不创建等价库或 native bridge | None |
| MC-JSON-001 | Applicable | PASS | Step 3/4，现有 Jackson/技术能力上限 | 新 JSON 只作能力存储，业务序列保留 | None |
| MC-TIME-001 | Applicable | PASS | §4.9、L03/L04/L05/L06 | DB UTC/precision/expiry，JVM 单调耗时，实际故障门 | None |
| MC-CONFIG-001 | Applicable | PASS | Step 1/6/7，双语治理文件 | LEGACY 缺省、显式启用和暂停；无环境键漂移 | None |
| MC-PATTERN-001 | Applicable | PASS | §4.5/4.7、Step 1/5 | State+Guard、旧 registry、真正槽位交接/两级 fencing | None |
| MC-SCOPE-001 | Applicable | PASS | §2、§5、§7 文件范围 | 无应用、外部 API、原生改动，既有事务接口保留 | None |
| MC-TEST-001 | Applicable | PASS | L01–L07、MPI-01–06、每步 RED 和 commit path | 阶段测试、物理/负面路由、回退与环境门可执行 | None |
| MC-BLOCKER-001 | Applicable | PASS | §11、§12 | 静态计划可审核；运行门与用户实施授权列明 | None |

### 12.6 Final verdict

`PASS — Ready for user review`

## Java / CQE contract review

| Concern | Review finding | Verification |
| --- | --- | --- |
| POJO、值对象、枚举 | Step 1/4 使用持久化 PO 和元数据转换；既有状态字符串不变 | MC-MODEL-001、L01/L04 |
| Components / MP | 固定逻辑 DS、技术上下文、管理历史与主库 guard 一致 | MC-REUSE-001、L02/L04/L06 |
| Annotation validation | 逐边界启用 Jakarta、分组和 DAO 形状，授权由 DB 判定 | MC-VALID-001、L01/L06 |
| 唯一键/软删除 | 组件技术键与 deleted_at 固定 NULL 一致；业务键、幂等键和回执语义保留 | MC-MODEL-001、L02/L04 |
| CQE | 同事务 enqueue；现有事件以原 ID 经 Outbox，consumer 用原 receipt | MC-ARCH-001、TEST-001/006 |
| 架构 | 现有非业务 Starter profile，技术 SSM 和可选业务 SSM | MC-ARCH-001、§4.7 |

## Backend generation and dependency evidence

已有基础设施 Starter 的技术元数据由组件维护。业务 Archetype 生成器和 SQL journal 不适用，Step 2 的两个 SQL 与 MP manifest 依既有工具管理；Step 4 的新 Java/Mapper XML 是组件内明确维护的技术映射，MapStruct 编译生成实现延续原方式。既有依赖、版本和工厂均由当前 POM 与源码支撑。计划初稿审核时未操作生产或测试代码、数据库、生成器和 POM。实际执行见后附记录。

## 执行记录

本会话用户明确授权执行本 Plan、分 Step 验证及提交，重点为真实 MP 两主两副本和同版本 LOCAL 回退演练。执行目标为当前组件，提交不包含这两份原本未跟踪的设计文档。

| Step | 状态／提交 | 已观察到的验证 |
| --- | --- | --- |
| 1 | Committed · 20a9169，枚举治理补正 d7af9c8、4246087 | 先 RED（新生命周期入口和新配置缺失）；随后 24 个聚焦测试通过，无跳过。新增枚举显式实现 EgonEnum 的固定代码和文案，复核通过。 |
| 2 | Committed · 99bd2ff | 25 个单测、14 个实际 PostgreSQL DDL 用例、8 个旧 MP 迁移回归外加两主两副本/单组读写分离各 1 个，全部通过，无跳过。真实迁移用例证明 A 已提交/B 失败时可用同一制品续跑，并验证两组主库历史和双副本回放。 |
| 3 | Committed · 64acc6a | 先 RED（新 JDBC Store 构造及契约缺失）；39 个单测与 17 个实际 PostgreSQL JDBC 集成用例通过，无跳过。验证 1/2/4 单分片领取、消息与队列租约隔离、提交结果未知及暂停期间 ACK。 |
| 4 | Committed · 265bd29，校验补正 0dde19a | 10 个 guard／policy 测试和 14 个单主真实 PG MP Store 集成场景通过。使用原逻辑数据源和 Mapper 证明 slot、技术租户授权、取得队列新 epoch、过期消息重领、旧 token 拒绝及仍有效旧消息 ACK。 |
| 5 | Committed · de5f6c1，修复 1e49ccd、5f50797 | 调度功能 11 个测试先 RED，42 个聚焦测试 GREEN。终审又复现了唯一 worker 遇到 handler Error 后永久退出，修复后该消息由租约恢复，同一个 worker 可以继续投递下一条。修复后 31 个聚焦测试通过。最后一次心跳视图丢失时，停机仍撤销有能力成员登记，未知归属交到期。该场景先 RED，最后两主两副本回退通过。 |
| 6 | Committed · 17d322e | 48 个 Spring 装配测试与 3 个实际 PG 拓扑测试通过，无跳过。真实两主两副本、两个 COORDINATED 实例、B 故障、结果未知、暂停仍入队、同版 LOCAL 和双副本重放。 |
| 7 | Committed · 1ef5c5e，文档补正 141f0d7 | 阶段指标缺失先 RED，随后 11 个指标和双语治理测试、3 个真实 PG 查询计划测试通过，无跳过。用 1,200 行隔离样本对 JDBC 1/2/4 和 MP 单条的待分发／重领 SQL 形状做 EXPLAIN ANALYZE。实际返回行数与限制相同并命中对应索引。 |

环境调整由本机 Docker daemon 未运行触发。本轮使用任务专属 loopback PostgreSQL 18.3（两主两副本及各自专库），以现有测试类的独立 PG 参数替换容器入口。全 reactor 非过滤测试先被 Common MP 模块已有 LifecycleScopeEnum 治理失败挡住；保留该模块和历史源代码，只使用相同模块编译加精准 Surefire/Failsafe 选择器证明本 Plan 要求。未迁移共享/生产数据库。

Step 3 补正 `b37a883` 对齐 JDBC 与 MP 的活跃归属快照，两个 Store 都向调度器返回同分片的完整受限视图。17 个真实 JDBC 集成场景及 3 个 schema 合同测试重新通过。

### 真实 MP 两主两副本回退演练

环境为隔离的 PostgreSQL 18.3，两个 primary 各有独立 replica 进程。应用使用正式 Starter、真实 MP Store、实际 Mapper SQL 和受管 ShardingSphere 逻辑数据源，连接故障由测试专用代理注入。两条运行在不同 slot 的消息使用相同物理行 ID，验证了结果各回自己的主库。

1. 启动两个 `COORDINATED` 消费者。两分片均出现两个成员。暂停 A 副本回放后，普通业务读留在该副本，A 消息在实际主库领取并提交后启动 handler。
2. A handler 进入屏障后制造 B 主库连接故障。A ACK 提交，B 的待分发任务保持 `PENDING`、attempt 为 0，B handler 未启动。恢复后 B 正常投递，A/B 分片各只有本组任务。
3. 在 B 真实 commit 返回后分别注入 ACK 和 claim 回执丢失。ACK 已落库时只重新确认，handler 一次。claim 实际已落库时保留 `PROCESSING` 的原记录，未调用 handler。
4. 退出两个协调消费者并核对成员和队列。以暂停模式启动同一制品，实际生产的新消息保持待分发。退出暂停实例后以 `LOCAL` 启动同制品，验证暂停时写入、新写入和过期旧租约的任务。旧任务保留原消息 ID，attempt 从 1 到 2，旧 token 的 ACK 返回 `LEASE_LOST`。旧租约过期通过隔离夹具推进截止时间。
5. 核对两个主库的三条版史、归属 epoch 均保持一致，两个副本都回放了新 `LOCAL` 结果。四个 PG 进程的角色和流复制状态复核通过，两个副本正常回放。验证完成后关闭四个隔离进程。

[MP 阶段验证日志](/private/tmp/outbox-step6-mp-final.log) · [最终组件回归日志](/private/tmp/outbox-final-drain-verify.log) · [候选 SQL 查询计划日志](/private/tmp/outbox-step7-query-green.log)

### 最终验证与基线

`main` 上从 `d3f5f9c` 开始的交付增量由七个主提交和七个补正组成。所有提交均在 Outbox Starter 模块。两份设计文档保留在工作区。JDBC V1、既有 MP 两份 SQL、POM、其他组件均无变更。

以下命令在仓库根目录执行。真实主从测试需要先为任务专用 PostgreSQL 进程设置双语 README 的 opt-in 环境变量。运行时参数 `OUTBOX_TEST_URL` 指向独立本机测试库。

`./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am '-Dtest=**/component/outbox/**/*Test,!**/component/outbox/integration/**' -Dsurefire.failIfNoSpecifiedTests=false '-Dit.test=**/component/outbox/integration/**/*Test,!**/PostgresqlOutboxConcurrencyIntegrationTest,!**/PostgresqlOutboxCleanupIntegrationTest,!**/BusinessStateMachineIntegrationTest' -Dfailsafe.failIfNoSpecifiedTests=false "-Degon.outbox.test.postgres.url=$OUTBOX_TEST_URL" verify`

最终代码 259 个组件单测和 79 个集成用例通过，跳过 0。双语命令补正后再跑组件治理 9 个，全部通过。扩大未过滤回归发现三个历史测试类中四个旧行为用例失败。使用原提交 `d3f5f9c` 的干净 checkout、原夹具和新独立 PG 库复跑，仍出现业务缺定义、旧领取并发和旧清理锁四项失败。旧领取事务锁用例在基线抛执行异常，在当前库报持锁断言失败，两次均未通过。以现有 `LEGACY` 行为如实记录，其余范围按上方选择器验收。此前完整 reactor 的 Common MP 枚举治理错误也保留在基线记录中。

[扩大回归失败记录](/private/tmp/outbox-final-verify.log) · [原提交复跑记录](/private/tmp/outbox-baseline-repro.log) · [终审 worker 错误修复](/private/tmp/outbox-worker-failure-green.log)

### 最终 Spec 审计

| Requirement ID | 结论 | 代码与证据 | 未闭合项 |
| --- | --- | --- | --- |
| REQ-001 | Satisfied | `OutboxQueueCoordinator` 按 shard/channel 和注册能力选主。真实双成员和独占队列、异能力协调测试、旧 epoch 拒接均通过 | — |
| REQ-002 | Satisfied | `OutboxDispatcher` 仅在 ready worker 已预约时领取。4 忙第 5 条、丢唤醒、worker 异常后继续和未确认领取测试通过 | — |
| REQ-003 | Satisfied | MP 受管数据源的 A/B 图、固定 slot、两主各写自己、B 故障不影响 A、暂停副本业务读与主库读写分离均已有真实证据 | — |
| REQ-004 | Satisfied | MP/JDBC 集成证明 queue epoch 与消息 token 独立，过期重领后旧 ACK 被拒。双主回退链路复用了相同消息 ID | — |
| REQ-005 | Satisfied | 预算在持久化边界执行，真实提交回执丢失、过期消息与 ACK 重读覆盖，DEAD 按现有运维边界 | — |
| REQ-006 | Partial | 入队和两种安全模式中的序列化、租户 header、业务 handler proxy、缺定义分类与实际回退已验证。老 schema/路由和不支持的 Store 均拒绝启动安全消费 | 原 `LEGACY` 业务缺定义与并发／清理用例在基线仍失败 |
| REQ-007 | Partial | 分阶段指标、MP 单条与 JDBC 1/2/4 的真实领取测试、索引形状检查和同版本回退均通过 | 预发等容量压测、连接借用／SQL 锁／提交合计预算和全部生产画像待目标环境证明 |
| REQ-008 | Satisfied | 交付均为 Java Starter 模块和组件文档。依赖边界及实际 diff 验证 | — |

性能样本使用固定数据和局部索引可用性验证。该结果没有覆盖真实配置的连接池争用、长锁、网络和 commit 总预算，也没有覆盖生产流量下的业务 p95/p99、WAL 和副本滞后。按 Spec §15/§16，保留 MP 单条与已有放量门槛。两个主库之间的故障隔离和同版 LOCAL 是本次实测，生产发布验收仍待目标环境数据。

| Literal Rule | Applicability | Result | Final evidence |
| --- | --- | --- | --- |
| Rule 1 | Applicable | PASS | 新模型语义 PO/BO/Command/DAO 及枚举，编译和源码核对 |
| Rule 2 | Applicable | PASS | 安全领取和 ACK 校验、固定 SQL 上下文与 Spring/V1/V2 装配拒绝测试 |
| Rule 3 | Applicable | PASS | 队列字段由 EgonModel 和 Lombok 管理。MapStruct 继承 Egon BaseForwardConverter，实际 MP 安装使用生成转换器 |
| Rule 4 | Applicable | PASS | 受管 Bean 明确命名和 Qualifier。受管组故障日志只输出阶段、slot、channel、异常类型 |
| Rule 5 | Applicable | PASS | 复用 JDK 摘要、已有执行器/持久化与配置，无 POM 差异 |
| Rule 6 | Applicable | PASS | Spring Boot Jackson、现有 OutboxStatus 字符串表示与显式 Egon 枚举码 |
| Rule 7 | Applicable | PASS | 两份 README 的新旧配置键固定集合治理通过 |
| Rule 9 | Applicable | PASS | 状态机守卫、权威 Store、就绪交付和协调分工都有独立验证 |
| Rule 10 | Applicable | PASS | 实际租约由 PG 时间决定，Java Instant/Duration 及单调耗时，过期与 ACK 边界实测 |
| Rule 11 | Applicable | PASS | 既有 Starter 范围，MP 外部技术表通过受管 DDL。业务入队仍复用原 CQE 事务链 |

| Manual Check | Applicability | Result | Verified evidence / Remaining action |
| --- | --- | --- | --- |
| MC-ARCH-001 | Applicable | PASS | 单组件路径，真实逻辑数据源 |
| MC-REUSE-001 | Applicable | PASS | 既有 Store、Mapper、状态机、技术租户上下文与 DDL 管理器 |
| MC-DEP-001 | Applicable | PASS | POM 无变更，无原生或外部协调器 |
| MC-NAME-001 | Applicable | PASS | 新 Java 类型编译及命名抽查 |
| MC-VALID-001 | Applicable | PASS | 命令、路由、租约、缺 schema 和配置拒绝场景 |
| MC-MODEL-001 | Applicable | PASS | EgonModel 字段、物理主键与协调自然键 |
| MC-CONVERT-001 | Applicable | PASS | 编译生成 MapStruct，真实 MP 入队至 ACK |
| MC-LOG-001 | Applicable | PASS | 阶段与失败类型可区分，无 payload、headers 或 token 标签 |
| MC-BEAN-001 | Applicable | PASS | COORDINATED Bean、LEGACY 默认、配置暂停与真实 Spring proxy |
| MC-UTIL-001 | Applicable | PASS | JDK 并发、摘要与时间类型，现有 Jackson 和 Spring |
| MC-JSON-001 | Applicable | PASS | 受限能力 JSON、消息原序列化回归 |
| MC-TIME-001 | Applicable | PASS | 以数据库时间和单调耗时实现，在过期与结果未知实验中复核 |
| MC-CONFIG-001 | Applicable | PASS | 21 项公开键双语精确匹配，安全默认值和 schema 门复核 |
| MC-PATTERN-001 | Applicable | PASS | 状态机、独立队列协调、就绪 worker 分离及线程边界验证 |
| MC-SCOPE-001 | Applicable | PASS | 交付提交都限所列 Starter 路径，数据库实验限 PG fixture |
| MC-TEST-001 | Applicable | PARTIAL | 目标组件与 MP 回退已验。已定位的旧回归失败及预发性能与预算证据仍待闭合 |
| MC-BLOCKER-001 | Applicable | PARTIAL | 当前结果可用于本地代码和回退评审。协调激活的目标环境验证仍未闭合 |

保持原 Spec 技术决策与 SHA。执行中修改了 Plan 的 Step 4 文件清单及 Step 7 双语治理测试范围。Step 4 的新 Mapper 参数约束与 Step 5 的异常隔离、视图丢失时的停机收口均独立记录和提交。临时 RED 测试和 MP 夹具已进入对应组件测试。改造前基线用的临时 Git worktree 已移除，日志留在临时路径。仓库未生成未归属的执行脚本。未部署、推送或操作共享数据库。

**PARTIAL — Spec requirements are unmet or unverified**。隔离环境的主要功能与同版本 LOCAL 回退已实际通过，剩余项为上表的基线回归和预发激活验证。
