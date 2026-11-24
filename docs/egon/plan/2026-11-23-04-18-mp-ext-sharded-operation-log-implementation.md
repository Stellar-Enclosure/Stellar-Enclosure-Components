# MP-Ext 分库分表操作日志与天枢切换实施计划

| Field | Value |
| --- | --- |
| Document | `docs/egon/plan/2026-11-23-04-18-mp-ext-sharded-operation-log-implementation.md` |
| Template Version | 6 |
| Status | Review |
| Created | 2026-11-23 04:18 AST |
| Updated | 2026-11-23 05:53 AST |
| Owner | mario |
| Repository | Stellar-Enclosure-Components、tianshu-service、tianshu-web |
| Scope | MP-Ext 统一审计与 PostgreSQL 目标库、天枢现有 Outbox 集成、PAC 回执、查询与管理台 |
| Source Requirement | 用户确认 Spec 并开始写 Plan。新版本直接启用新日志。旧普通历史丢弃，独立保留 PAC 回执 |
| Baseline Revision | Components main@5f50797383433b35daa9804785037d24b3410bce，Tianshu Service main@56bafdae6da26f3f59304ec8367fb1cb1f6e486d，Tianshu Web main@f7e0a079e34010eff97c9423fcdeea2ed9f1a269 |
| Implements Spec | [MP-Ext 分库分表操作日志与天枢切换 Spec](../spec/2026-11-23-03-57-mp-ext-sharded-operation-log.md) |
| Spec Status | Review |
| Spec Revision | 2026-11-23 04:11 AST。SHA-256 71dbb8193ce8a38e91418cc6fcb6fb576bb4795bb78e0edcb0abfb64d470edd5 |
| Effective Specs | [MP-Ext 分库分表操作日志与天枢切换 Spec](../spec/2026-11-23-03-57-mp-ext-sharded-operation-log.md) |
| Depends On Plans | None |
| Supersedes | None |
| Superseded By | None |
| Related Plans | [Outbox 多实例队列协调与可靠分发实施计划](./2026-11-23-00-04-outbox-coordinated-dispatch-implementation.md)，仅上下文 |

## 1. Summary

实施分 8 步。Steps 1–7 交付 MP-Ext 采集和分片存储、天枢直切、查询页及故障回退演练。Step 8 只在业务与 Outbox 两份实际版史都验证可接受后安排物理退役。发布验收以真实 PostgreSQL 两主两副本的角色、各桶/月实际行、回放位置、源 Outbox 与目标提交关联、PAC 原始回执字节和兼容回退制品为证据。

## 2. Target Spec and Effective Design

### 2.1 Primary target

- Path: [MP-Ext 分库分表操作日志与天枢切换 Spec](../spec/2026-11-23-03-57-mp-ext-sharded-operation-log.md)
- Status: 文件头为 Review。用户已明确审批按该设计写 Plan。
- Revision: 2026-11-23 04:11 AST。顶部 SHA-256 对应加入本 Plan 关系回链后的文件内容。
- Approval evidence: 用户回复「confirmed 开始写plan吧」。MapStruct 两个本模块编译期处理器随后收到「确认复用现有版本」。

### 2.2 Effective Spec set

| Role | Spec/link | Status/revision | Effective sections | Why included |
| --- | --- | --- | --- | --- |
| Primary | [MP-Ext 分库分表操作日志与天枢切换 Spec](../spec/2026-11-23-03-57-mp-ext-sharded-operation-log.md) | 文件状态 Review。用户确认 2026-11-23 | REQ-001 至 REQ-008 及完整契约、表、页面、迁移和测试 | 唯一实施规格 |
| Context | [Outbox 协调 Spec](../spec/2026-11-22-23-41-outbox-coordinated-dispatch.md) | 相关背景 | 只有现有技术交付 API 的源码边界进入测试 | 主 Spec 无 Depends On。实施使用实际部署版 Outbox 和既有工作模式 |

### 2.3 Superseded or excluded content

主 Spec 无修订、依赖或替代关系。普通历史回填、双读双写、审计中心、网关采集、Outbox 状态与调度改造、DEAD 自动重驱、Rust JNI 均超出当前实施面。正式环境执行属于之后获准的发布。

## 3. Effective Requirements and Acceptance

| Requirement | Source Spec section | Effective statement | Observable acceptance | Implementation impact |
| --- | --- | --- | --- | --- |
| REQ-001 | §4/7.3.1/9.2.3 | MP-Ext 提供宿主 opt-in 的统一显式 AOP | 真实代理与显式模板均产生一条命令事实。关闭后零 SQL/入队，嵌套不重复 | 组件、配置、宿主事务和业务入口 |
| REQ-002 | §4/7.3.1/9.2.4/10 | HTTP 脱敏 URL、路由、动词与完整方法签名 | HTTP 事件还能区分桥接和重载。后台使用实际执行方法且不虚构 HTTP。字段安全与时间精度保持 | 共用快照、天枢来源适配、API 与 UI |
| REQ-003 | §4/7.3.3/9.2.4 | 业务提交与审计事实或投递意图同源事务 | 源回滚无成功事实。目标不可用恢复后能补投。已提交未 ACK 的目标只落一次 | 源事务、Outbox 本地 channel、目标 receipt+fact |
| REQ-004 | §4/7/9/11 | 分库、分桶、月分区和目标读写 | 真实 2×2 主副、每主两桶、UTC 月裁剪、重复跨月冲突以及精确详情 | MP 托管、路由、读合并及真实物理验证 |
| REQ-005 | §4/8.3/11.2.4/16 | 天枢直接启用新的普通操作日志 | 普通旧数据 0 导入、无双读双写，新首屏空，且新写表唯一。旧表物理退役按独立门 | 源调用及 legacy 导入、上线和退役隔离 |
| REQ-006 | §4/11.2.3/16 | 迁出并保留 PAC 业务回执 | 复用原锁、原操作键和 fingerprint。旧表删时仍返回原结果字节，不重复初始化 | 受管 SQL、CLI、验收制品 |
| REQ-007 | §4/9/12 | 只在可信租户与正确权限范围读日志 | 真实两个 GET、字段权限及独立错误。UI 保留游标精度并丢弃旧租户迟到结果 | Web/PAC/springdoc/页面/只读 Mapper |
| REQ-008 | §4/11/14–16 | 受管迁移、验证与可回退启用 | 两主分别续跑。已应用版本/源指纹受保护。峰值预算和退役前回退可核实 | 应用与组件版史门、手册、容量和演练 |

## 4. Implementation Strategy and Dependency Order

### 4.1 Ordered strategy

先把注解、可信上下文和同事务 API 固定并通过真实 Spring 代理测试。再交付分片审计 DAO、受管脚本和目标 Factory。与此同时，在天枢独立迁出 PAC，保留一个支持 V001 版史的旧行为回退制品。业务写入口与旧导入路径一次切换。Admin 负责消费，配置 Publisher 只用源连接生产。后端受限读与权限编目稳定后交 Web。完成实链路后才能启用新日志，物理退役独立于首次切新。

### 4.2 Test-first strategy

组件先声明纯接口和模型作为编译前置，再以容器行为或 SQL 资源 RED 验证缺少的代码。同样对未存在的 Query/VO 和新 TS 客户端允许最小可编译声明，记录 §7 表顺序中的测试文件先设计。实际行为 RED 执行在生产实现前。缺少 fixture、版本、进程和编译错误不计 RED。切新、回退和退役分别提供反例。每步最小 GREEN 后才做装配和跨包回归。

### 4.3 Sequential and parallel boundaries

| Step | Depends on | May run in parallel with | Must not overlap with | Reason |
| --- | --- | --- | --- | --- |
| Step 1 | 无 | Step 3 | Step 2 | 采集合同先于 Mapper 与宿主 |
| Step 2 | Step 1 | Step 3 | Step 4 | 源/目标及产物先可验证 |
| Step 3 | 无 | Steps 1–2 | Step 4 | 冻结合法 PAC 版史和可用回退制品 |
| Step 4 | Steps 2–3 | Step 6 仅静态准备 | Steps 5、7 | 新 producer/handler 就绪先于读取和切换 |
| Step 5 | Steps 2、4 | Step 6 测试设计 | Step 6 正式产物 | 两条 GET、Server manifest 与权限先发布合约 |
| Step 6 | Step 5 | Step 7 夹具准备 | Step 7 验收 | 真实 Web consume 已生成契约 |
| Step 7 | Steps 1–6 | 无 | Step 8 | 先演练切新、隔离、回退及重新启用 |
| Step 8 | Step 7 及退役审批 | 无 | 所有旧制品 | 两份版史可证兼容才进入退役提交 |

### 4.4 Commit boundaries

仅暂存每 Step 声明的路径，一步一仓一个语义提交。Step 4 把全部普通旧写入口与 legacy 普通导入在同一发布提交切换，消除混合写者。Step 3 可先合并并生成独立回退制品。Step 8 不满足退役门时无提交。跨 Step 同文件只限 §5 登记的增量职责。Plan、Spec 或既有 Outbox 文档的提交与源码提交分开。

### 4.5 Spec Simplicity and Implementation-necessity Audit

| Spec element | Spec necessity verdict/section | Current repository evidence | Direct/reuse alternative | Interaction/implementation cost | Plan decision |
| --- | --- | --- | --- | --- | --- |
| 采集注解/显式 Recorder/可信投影 | §7.0/7.3.1 Add | DdcConfigService 的事务方法和 DdcPublishService 的 TransactionTemplate 并存 | 同一个内部入口复用，不拆不同平台 recorder | 一个 source 写，不造 preflight 请求与新身份 | 实施 |
| 隔离事务/Outbox adapter | §7.0/9.2.4 Add | DdcOutboxConfiguration、实际 Admin 受限 registry、Publisher 手工 import | 复用两个既有进程边界和本地 channel，不做 RPC | 新目标池和一次目标事务。源端仍就地 enqueue | 实施 |
| Fact+跨月 receipt | §7.0/11.2.1–2 Add | MP 两级路由，月表 PK 含 occurred_at | 同组时只写 Fact。独立接收键承担分区 PK 无法覆盖的跨月幂等 | 独立模式多一次目标写。权限/保留窗口独立 | 实施 |
| PAC 专用表 | §7.0/11.2.3 Add | Bootstrap Repository 实际把原 JSON 当幂等协议 | 复用原 SERIALIZABLE / advisory lock，不复用普通日志生命周期 | 单一业务表、保留既有 CLI，不建新的回执 API | 实施 |
| 两只读 GET + 页面 | §7.0/9.2/12 Add | selectByScope 当前无独立权限/窗口/游标 | 从已有 OAuth/租户上下文派生身份。用户打开详情才查一次 | 首屏一次，详情一次。无需预取令牌、tenant 或物化缓存 | 实施 |
| 分区保留与容量 | §11/15/16 Add | 当前受管 DDL 一主一个事务，Readwrite IT 可实跑角色 | 现有工具运行受管候选，按物理组记录证据 | 不新增 Job 或自动 DROP，旧普通表早退役受控 | 实施已有门 |
| 新发布依赖 | §6.1/10.4 复用 | MP-Ext 消费 当前 Common Core 的 MapStruct API 为 test scope，缺编译处理器 | 复用天枢和 RPC Tianshu adapter 同版 processors | 仅 MP-Ext 编译期改动，用户已明确确认 | 实施局部配置 |

### 4.6 Change-unit Dependency Matrix

| Change unit | Requirements | Proof/RED point | Compile/runtime prerequisites | Produces | Consumers/unblocks | Owning Step |
| --- | --- | --- | --- | --- | --- | --- |
| 建立默认关闭的 MP-Ext 应用审计采集合同 | REQ-001、REQ-002、REQ-003 | 接口/枚举先提供编译期合同。实际 Recorder/Advisor 之前写可编译容器用例，RED 为 Bean 缺失和实际事务未被采集 | 无。只使用现有 Spring AOP、Jakarta Validation、Egon ID 及 Clock | 已定义 Event、SPI、Recorder、Advisor 和开关，假 Destination 通过 Spring 真实事务代理，未接持久化 | 注解和显式入口在正确源事务只观察一次，禁用时两入口零副作用 | Step 1 |
| 交付分片审计表与目标读写装配 | REQ-003、REQ-004、REQ-008 | 先执行资源和装配合同 RED。真实 2×2 缺 manifest 与表是预期。编译期转换类型只在被调用前就绪 | Step 1 | 登记两份目标 migration，复用 MP/SDJ，独立具名 Bean 和 SOURCE_LOCAL 策略。下一步安装 Components 制品 | 两主每主两桶，目标键和事实同事务，回执重放与副本查询可证明 | Step 2 |
| 把 PAC 业务回执迁出并构建兼容回退制品 | REQ-005、REQ-006、REQ-008 | 先用现有 bootstrap/DDL 测试做 RED。原 CLI 选旧表或旧 manifest 不支持新增回执 | 与 Steps 1–2 可并行。本 Step 完整验证后才可进入 Step 4 启用 | 保留旧普通日志行为、旧 route 和旧表。产出带 PAC 新版史/新 CLI 的独立回退制品及 SHA | 混合旧记录仅迁 PAC，原操作重读字节一致。不会使用读取新表之前的未兼容原制品回退 | Step 3 |
| 切换业务操作采集和已有 Outbox 投递 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | 现有业务和 Worker fixtures 先转可观测的审计 contract，缺事务/仍写旧日志/缺 channel 为 RED | Steps 2–3。目标路径和 source 版本清单、Phase 3 兼容制品已锁定 | 唯一新写者，旧 YAML route 过渡保留。业务接口与部署默认保持预期，目标先准备后开新采集 | 符合动作范围的每个成功操作只经新采集入队一次。独立 Publisher 最终状态确实能产生事件 | Step 4 |
| 发布受权限保护的天枢审计查询合同 | REQ-002、REQ-004、REQ-007 | WebMvc 在最小已可编译 DTO 后对真实安全配置 RED。缺审计 route/结果排序/二级权限可观察 | Steps 2、4。受限查询 DAO、目标工厂及现有 Admin security 在位 | 后端生成 Springdoc 和 PAC Java manifest。保留 API 测试与可锁定的 RC 阶段证据给 Web | 实际两个 GET 受租户和审计权限约束，并输出文档合同 | Step 5 |
| 上线管理台操作日志浏览入口 | REQ-002、REQ-007 | Vitest 首写缺路由/页面/错误状态，使用布置好的权限和语言 fixture，真实断言失败 | Step 5 已产出同版本 OpenAPI 与 PAC manifest。UI 编码可在固定契约下并行准备 | 加入独立页面、最小导航和源翻译同步，交付测试、类型检查和锁定 Web 制品 | 有权从正常菜单查询与看详情。首屏可空，服务错误与跨租户状态安全 | Step 6 |
| 完成两主两副本端到端证据和切新回退演练 | REQ-003、REQ-004、REQ-005、REQ-006、REQ-007、REQ-008 | IT 先对基线、反例或故障注入有意义地 RED。对照兼容制品+真 DB，否则不作为回退证明 | Steps 1–6。干净的隔离数据库与兼容回退制品，按 §8 准备真实运行 | 交付可重放的 IT 与运行手册，临时脚本在本步结束前移除。生产启用留审批 | 可按清单切换唯一生产、回退并恢复，两个真实 replica 与 PAC bytes/普通空表均有证据 | Step 7 |
| 按独立验收门评估旧表物理退役 | REQ-005、REQ-006、REQ-008 | 先校验两份实际 route/history 就绪 proof 与旧引用测试，基线缺兼容时禁止进入下一个 File | Step 7。只在旧制品退出、回退窗关闭且兼容两个实际 fingerprint/history 的独立审批后执行 | 通过时追加受管 V002 并删除已断 consumer 的旧实现。未通过时无 SQL/代码提交，不阻塞 Step 7 切新 | 候选退役制品在两个现有历史保护下可启动，新 CLI 同 key 可读，且老表可安全移除。未证则保留旧表并记录独立后续 | Step 8 |

### 4.7 Java, Spring, and Egon-COLA Implementation Standards

| Concern | Current repository evidence | Effective Spec decision | Planned implementation consequence | Owning Steps/checks |
| --- | --- | --- | --- | --- |
| Architecture profile | AJ controller/service/repository/dao，Admin POM 依赖 starter。MP-Ext 组内基础设施。Web 单仓 | §6.1 Traditional Three-Layer | 保持原三层和基础设施依赖方向。既有工程不使用 CREATE archetype 模板。跑 DdcArchitectureContractTest | Steps 1–8，MC-ARCH-001 |
| Reuse/capability | 原 AOP/Validate、MP SSDS bootstrapper、Outbox SPI、ResultRecord、PAC manifest | §6.1/7/9 | 第二逻辑 DS 复用标准角色/DDL/Mapper 验证。无新运行时依赖 | Steps 1–5，MC-REUSE-001 / MC-DEP-001 |
| Naming/model/validation/conversion | EgonModel、EgonColaModelValidationGroups、BaseForwardConverter | §9/10/11 | Event/DTO/PO/Query/VO 角色区分，普通类 Lombok，尽量单合约。MapStruct 单向投影 | Steps 1–5，MC-NAME-001 / MC-VALID-001 / MC-MODEL-001 / MC-CONVERT-001 |
| Bean/logging/util/JSON/time/config | 两 Java 仓 lombok.config。DdcOutboxConfiguration、Clock、Jackson、DdcProfileParityTest | §6.2/6.3/15–16 | @Slf4j、稳定 Bean、final @Qualifier、@RequiredArgsConstructor(onConstructor_ = @Autowired)，构造参数反射验证。HTTP/事件同稳定格式 | Steps 1–7，MC-LOG-001 / MC-BEAN-001 / MC-UTIL-001 / MC-JSON-001 / MC-TIME-001 / MC-CONFIG-001 |
| Business variation/pattern | 单事务写和两事务投递已有不同边界，字段权限分层 | §13 Strategy/Adapter/Projection | 固定 writer Strategy、宿主 Adapter、按动作和读模型两个 Projection。PAC 单一路径直接处理 | Steps 1–5，MC-PATTERN-001 |

#### Capability reuse ledger

| Need | Candidates inspected | Exact evidence | Fit/gap | Decision | Added dependency/custom code | Owning Step/check |
| --- | --- | --- | --- | --- | --- | --- |
| 实际应用方法与时间 | Spring AOP、Validation | AopUtils、BridgeMethodResolver、ValidationAutoConfiguration | 框架已有代理工具及校验，新注解/投影才缺 | 复用 | 自定义已批准 Advisor/SPI | Step 1，MC-REUSE-001 |
| 可信模型与转换 | Components Common Core、MP-Ext、ID | BaseForwardConverter、EgonModel、SnowflakeIdGenerator | 缺 MP-Ext 编译 processor。已确认用相同版本的 provided API | 原版本配置 | org.mapstruct:mapstruct-processor:1.6.3、org.projectlombok:lombok-mapstruct-binding:0.2.0，本模块编译期 | Step 2，MC-DEP-001 |
| 数据库角色与版史 | MP-SDJ Starter、runtime role guard | EgonColaShardingDataSourceBootstrapper、EgonColaPostgreDdlRunner、EgonColaDdlManifestBO | 既有工厂 hook 支持窄主读。不同指纹不能共享全局 resolver | 复用并实例化 | 两个目标模板及允许的固定技术语句，无新依赖 | Steps 2、3、8，MC-REUSE-001 |
| 同事务出站和消费 | TransactionalOutbox、DeliveryHandler | OutboxMessage、DeliveryContext.deadline、DeliveryResult、现有 Admin/Publisher | 缺业务事件和适配，无需改领取或 ACK | 复用 | 新宿主制品 Bean | Step 4，MC-ARCH-001 |
| HTTP 授权和 Web | Spring MVC、现有 OAS、PAC、ddcApi | DdcAdminSecurityConfiguration、DdcTenantContextFilter、DdcPublishTaskController、RequirePermission | 两个清晰的读取能力缺失 | 复用 | 两个 API、专权和一页 | Steps 5–6，MC-VALID-001 |

### 4.8 User-mandated Java Rule Implementation Matrix

| Literal rule | Spec source | Repository evidence | Exact files and order | Implementation obligations | Validation gate | Steps | Status/blocker |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Rule 1 | §6.2/8/10 | EgonModel、已有 Query/Result/DAO | Step 1 ContextDTO/Event，Step 2 PO 后 DAO，Step 5 Query→VO→Result | 无模糊载体。保持语义后缀与生成类实际文件名 | G01/G02/G05 JDK25 编译 | 1、2、5 | PASS |
| Rule 2 | §6.2/7/9/10 | ValidationUtils、MP Validator、DdcTenantContextFilter | capture test→Recorder、PO→DAO、WebMvc→Query→Controller 及 adapter | 入口 @Validated、对象 @Valid、跨字段约束，Repository→DAO Insert/Query/Persisted。非代理仅复用既有 Jakarta 元数据显式验证。无电话字段 | G01、G02、G04、G05 错参数/边界测试 | 1–5、7 | PASS |
| Rule 3 | §6.2/10 | EgonModel @SuperBuilder、BaseForwardConverter | Context record 与 Event class，PO 后 OperationLogConverter，VO 后 ViewConverter | 普通类 @Data/@NoArgsConstructor/@AllArgsConstructor/@Accessors(chain = true) 与合适 Builder。PO callSuper。只不可变快照用 record，生成投影查敏感字段 | G01/G02/G05 生成、映射和注解 | 1、2、5 | PASS |
| Rule 4 | §6.2/7/13 | 两仓 lombok.config、已有具名 source Beans | Recorder/Advisor→AutoConfig。Outbox adapter→Worker。QueryService→Controller | @Slf4j 安全日志、具名 Bean、合格 final 字段、@RequiredArgsConstructor(onConstructor_ = @Autowired)、显式 @Qualifier 传播 | 容器/反射 G01/G02/G04/G05 | 1–5、7、8 | PASS |
| Rule 5 | §6.1/15 | JDK、Spring、Common Core/MP | Recorder→Repository→adapter→QueryService | JDK 时钟/摘要、Spring 路由、无新的工具 import 或通用 Helper | G08 diff 依赖和禁用导入 | 1–5、7 | PASS |
| Rule 6 | §6.2/9/10 | Jackson、Egon enum、ResultRecord | Event/Phase/Outcome→PO→双 VO。Web 接口仅消费同 shape | @JsonValue/@EnumValue、显式 schema 与未知字段拒绝。BigInt 字符串和允许空值。禁止 ordinal | G01/G02/G04/G05/G06 | 1–7、8 | PASS |
| Rule 7 | §6.2/15/16 | egon-tianshu-persistence.yml 和 DdcProfileParityTest | Properties→AutoConfig→共享 persistence YAML。退役独立一变 | 优先共用同一导入，实际四类 profile 合并结构完全等价，值可差。禁用默认 | G04/G08 key parity | 1–4、7、8 | PASS |
| Rule 9 | §7.3/13 | Outbox Adapter 与 MP Resolver | Destination→Recorder→装配→Tianshu adapter。ViewConverter 按权限实现投影 | 复杂写模式固定 Strategy。目标投递 Adapter。读写字段 Projection，PAC 保留简单单向 | G01/G02/G04/G05/G07 | 1、2、4、5、7 | PASS |
| Rule 10 | §6.3/9/11/12 | EgonModel、原 PAC UTC、现有浏览器 display | Event→PO、PAC SQL→CLI、Query→VO→Web | java.time、微秒 UTC、半开分区/查询、原游标精度、显示区一次格式化、单调耗时 | G02/G03/G05/G06/G07 与 §4.9 | 1–7 | PASS |
| Rule 11 | §6.1/8/11 | 现有三层、MP starter/受管 runner、CQE Outbox | §§5/7 每步。既有 Controller→Service→Repo→DAO，组件保持独立 | 保持 CQE、受管历史与永久技术 PK，保留其他业务软删除规则。普通日志无 edit/delete | G08 architecture/历史/真实 CQE | Every Step | PASS |

### 4.9 Time semantics and conversion contract

Time contract: Required

| Time ID | Fields/symbols | Semantics | Java/runtime type | Storage policy | Wire policy | Business zone source | Display zone source | Boundary / DST rules | Precision / Clock | Compatibility | Verification |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| TIME-001 | occurredAt/occurred_at、recordedAt/create_time | Occurred instant | Instant，现有 JDBC 路径或显式 OffsetDateTime | UTC instant | UTC Z | UTC 决定月分区 | 用户 IANA 时区，缺省浏览器 | UTC 左闭右开，展示不影响分片或定位 | 源 Clock、入库技术时钟、截断到微秒后指纹 | 普通旧数据不转换。PAC 单独 TIME-004 | Step 1、2、4、5、6、7，G01/G02/G05/G06/G07 TEST-010 |
| TIME-002 | from/to/beforeTime 与详情 occurredAt | Occurred instant | OffsetDateTime 入参后 Instant，浏览器原字符串 | Not persisted | UTC Z | UTC 瞬时。UI 选择区日期范围先转时刻 | 用户 IANA 时区或浏览器 | 筛选按次日当地起点处理 DST。传输保留 offset 与半开 UTC 截止 | 微秒。Clock 计算缺省窗口，前端不得重做光标 | 新接口采用原有封套 | Step 5、6，G05/G06 TEST-007/010 |
| TIME-003 | durationMs/duration_ms | Duration | Duration、单调 System.nanoTime | Duration | Duration | Not applicable: 纯耗时 | Not applicable: 按非负毫秒显示 | 无日历与 DST 运算 | 毫秒向下取整、时钟回拨不影响 | ResultRecord.timestamp 保留原 Unix 毫秒 | Step 1、2、4、5，G01/G02/G05 TEST-001/010 |
| TIME-004 | PAC recorded_at，来源旧 created_at | Occurred instant | Instant，旧数据固定 UTC 解释 | UTC instant | Internal | 显式 UTC。现有初始化写入使用 ZoneOffset.UTC | Not applicable: 原回执按原文本返回 | 一次 UTC 映射，跨日不可随 JVM 区改变 | 微秒。new Clock UTC | 旧 timestamp without time zone 依当前代码按 UTC，正文不重编码 | Step 3、7，G03/G07 TEST-006/010 |

## 5. Change File Tree

路径相对行内仓库根。C = Stellar-Enclosure-Components，T = tianshu-service，W = tianshu-web。全路径和文件顺序见 §7。跨步增量归属为 C 的 EgonOperationLogAutoConfiguration（Step 1 采集、Step 2 目标），T 的 repository-manifest/DdcDatabaseReadyValidator/DdcTianshuDdlContractTest（Step 3 PAC、Step 8 可选退役），共享 YAML（Step 4 新能力、Step 8 可选移除旧声明）。未满足 Step 8 门时，该步文件无变更。

| Operation | Path | Current evidence/symbol | Final symbols/state | Responsibility | Step | Requirements | Validation owner |
| --- | --- | --- | --- | --- | --- | --- | --- |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogContextDTO.java` | DdcTenantContextDTO 与 EgonModel 的可信租户语义 | OperationLogContextDTO | 可信调用快照 | Step 1 | REQ-001、REQ-002、REQ-003 | G01 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogWriteModeEnum.java` | EgonModel 合同与 §10.3.1、§10.6 | OperationLogWriteModeEnum。SOURCE_LOCAL、OUTBOX | 声明稳定边界枚举 | Step 1 | REQ-001、REQ-002、REQ-003 | G01 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogPhaseEnum.java` | EgonModel 合同与 §10.3.1、§10.6 | OperationLogPhaseEnum。COMMAND、EXECUTION | 声明稳定边界枚举 | Step 1 | REQ-001、REQ-002、REQ-003 | G01 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogOutcomeEnum.java` | EgonModel 合同与 §10.3.1、§10.6 | OperationLogOutcomeEnum。ACCEPTED、SUCCEEDED、FAILED、PARTIAL_SUCCESS、UNKNOWN、REJECTED、ROLLED_BACK | 声明稳定边界枚举 | Step 1 | REQ-001、REQ-002、REQ-003 | G01 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogEvent.java` | Outbox payload 为 JSON 对象，§9.2.4 定义命名与字段 | OperationLogEvent、schemaVersion、eventId、operationId | 唯一版本化事件契约 | Step 1 | REQ-001、REQ-002、REQ-003 | G01 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogProjection.java` | Spec §7.3.1、§13。天枢 DdcConfigService 已拥有实际修改后的版本结果 | OperationLogProjection | 定义按动作的投影契约 | Step 1 | REQ-001、REQ-002、REQ-003 | G01 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogDestination.java` | Spring named strategy 与 §13.1。业务侧现有 TransactionalOutbox.enqueue | OperationLogDestination 与 OperationLogWriteModeEnum | 定义固定写策略 SPI | Step 1 | REQ-001、REQ-002、REQ-003 | G01 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLog.java` | Spring AOP 现有 Bean 代理、Spec INTERNAL-001 | @OperationLog(actionCode, resourceType, projector) | 提供显式标记 | Step 1 | REQ-001、REQ-002、REQ-003 | G01 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/test/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogCaptureTest.java` | EgonColaMybatisPlusAutoConfigurationTest 的 Spring context 与事务探针 | disabledNeverWrites、proxyAndExplicitWriteOnce、outerRollbackNeverWritesSuccess、rejectsSpoofedAndNontxContext | 先写采集合同 RED | Step 1 | REQ-001、REQ-002、REQ-003 | G01 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/EgonOperationLogProperties.java` | EgonColaMybatisPlusProperties.PREFIX 与 DdcProfileParityTest | EgonOperationLogProperties.enabled、writeMode | 绑定启动策略配置 | Step 1 | REQ-001、REQ-002、REQ-003 | G01 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogRecorder.java` | EgonColaTransactionWriteTarget、EgonColaModelValidationGroups、SnowflakeIdGenerator | recordInTransaction(OperationLogEvent)、帧绑定和恢复 | 约束真实事务与单个根事实 | Step 1 | REQ-001、REQ-002、REQ-003 | G01 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogAdvisor.java` | Spring Named PointcutAdvisor、Spring AopUtils 与 BridgeMethodResolver。Spec §7.3.1 | OperationLogAdvisor、最具体 bridged method、投影调用 | 将注解调用接到 Recorder | Step 1 | REQ-001、REQ-002、REQ-003 | G01 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/EgonOperationLogAutoConfiguration.java` | EgonColaShardingAutoConfiguration 的具名 Bean 和旧 MP guards | operationLogDataSource、operationLogSqlSessionFactory、operationLogTransactionManager、SOURCE_LOCAL Destination | 装配采集扩展点 | Step 1、Step 2 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-008 | G01、G02 |
| MODIFY | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 现有 EgonColaMybatisPlusAutoConfiguration 已在同一 imports | EgonOperationLogAutoConfiguration | 注册公共自动配置 | Step 1 | REQ-001、REQ-002、REQ-003 | G01 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/test/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogPersistenceContractTest.java` | EgonColaDdlManifestFamilyTest、EgonColaShardingReadwriteIntegrationTest 与 EgonColaMybatisPlusContractValidator | manifestPerSlot、boundFactoriesAndWriteGuard、preciseReadAndRecvOnlyPrimary | 先断言实际装配拓扑和资源 | Step 2 | REQ-003、REQ-004、REQ-008 | G02 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/test/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogPostgresReadwriteIT.java` | EgonColaPostgreSqlReadwriteIT 的 EGON_MP_PG_SHARDING_IT 及四 URL | componentWriteAndReadAcrossBuckets、retryReceiptRace、rollbackWithoutHalfWrites | 验证组件的真实 PostgreSQL 路径 | Step 2 | REQ-003、REQ-004、REQ-008 | G02 |
| MODIFY | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/pom.xml` | Components parent 的 lombok/boot processors，Common Core 对 MapStruct API 使用 test scope，不能传递至 MP-Ext，RPC Tianshu adapter 已有同版 MapStruct/binding | maven-compiler-plugin、annotationProcessorPaths | 仅启用获准的编译处理器 | Step 2 | REQ-003、REQ-004、REQ-008 | G02 |
| MODIFY | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/ddl/EgonColaDdlManifestBO.java` | 现有 component-outbox/tianshu 白名单与脚本递增规则 | EgonColaDdlManifestBO family 校验 | 允许受管审计 DDL family | Step 2 | REQ-003、REQ-004、REQ-008 | G02 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/resources/db/egon-operation-log/V20261123_001__initialize_operation_log_slot_0.sql` | EgonColaComplexTenantThenBusinessShardingStrategy 实际物理名。Spec §11.2.1/2 | V20261123_001、egon_cola_operation_log_t0_b0/b1、egon_cola_operation_log_receipt_t0_b0/b1 | 在审计主组 0 安装两桶事实和接收键 | Step 2 | REQ-003、REQ-004、REQ-008 | G02 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/resources/db/egon-operation-log/manifest-slot-0.json` | EgonColaDdlManifestBO.ScriptBO 和 initializer.target.manifestResource | family component-operation-log、V20261123_001 与 SHA-256 | 绑定审计主组 0 版史资源 | Step 2 | REQ-003、REQ-004、REQ-008 | G02 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/resources/db/egon-operation-log/V20261123_001__initialize_operation_log_slot_1.sql` | EgonColaComplexTenantThenBusinessShardingStrategy 实际物理名。Spec §11.2.1/2 | V20261123_001、egon_cola_operation_log_t1_b0/b1、egon_cola_operation_log_receipt_t1_b0/b1 | 在审计主组 1 安装两桶事实和接收键 | Step 2 | REQ-003、REQ-004、REQ-008 | G02 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/resources/db/egon-operation-log/manifest-slot-1.json` | EgonColaDdlManifestBO.ScriptBO 和 initializer.target.manifestResource | family component-operation-log、V20261123_001 与 SHA-256 | 绑定审计主组 1 版史资源 | Step 2 | REQ-003、REQ-004、REQ-008 | G02 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogPO.java` | EgonModel、OutboxMessagePO、EgonColaModelValidationInterceptor | OperationLogPO extends EgonModel、§11.2.1 行字段 | 承载分区事实存储行 | Step 2 | REQ-003、REQ-004、REQ-008 | G02 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogReceiptPO.java` | Outbox 技术 PO 的持久化惯例。Spec §11.2.2 | OperationLogReceiptPO extends EgonModel、occurredAt、payloadSha256 | 承载独立幂等接收行 | Step 2 | REQ-003、REQ-004、REQ-008 | G02 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogConverter.java` | Common Core BaseForwardConverter。MP 构建 processor 按本 Step 已审批配置 | OperationLogConverter extends BaseForwardConverter<OperationLogEvent,OperationLogPO> | 生成事实写侧单向转换 | Step 2 | REQ-003、REQ-004、REQ-008 | G02 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogDAO.java` | EgonColaMapper、OutboxMessageDAO 对不适用生命周期方法默认拒绝 | OperationLogDAO、append、page、findDetail | 声明固定事实读写契约 | Step 2 | REQ-003、REQ-004、REQ-008 | G02 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogReceiptDAO.java` | EgonColaMapper、OutboxMessageDAO 技术主读先例 | OperationLogReceiptDAO、insertIfAbsent、confirm | 声明接收键写入与同组确认 | Step 2 | REQ-003、REQ-004、REQ-008 | G02 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/resources/mapper/operationlog/OperationLogMapper.xml` | Tianshu DdcOperationLogMapper.xml 现有 active 谓词。Spec §11.2.1 | OperationLogDAO 三条 named statements、摘要 resultMap | 接通事实写入和受限分页 | Step 2 | REQ-003、REQ-004、REQ-008 | G02 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/resources/mapper/operationlog/OperationLogReceiptMapper.xml` | MybatisPlusOutboxStore 的技术 Mapper/主读边界与 Spec §11.2.2 | OperationLogReceiptDAO.insertIfAbsent、confirm | 接通同一主组的竞争安全确认 | Step 2 | REQ-003、REQ-004、REQ-008 | G02 |
| CREATE | C `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogRepository.java` | EgonColaRepository 受管模式、EgonColaTransactionWriteTarget、ShardingWriteTargetResolver | append、accept、page、findDetail | 封装同组事务和有界读 | Step 2 | REQ-003、REQ-004、REQ-008 | G02 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/bootstrap/DdcPermissionMetadataBootstrapTest.java` | 现有 Bootstrap 现行幂等 fixture。§11.2.3 | initialize、receipt、expectedReceipt 的重复命令/不同 digest | 验证兼容回执重放 | Step 3 | REQ-005、REQ-006、REQ-008 | G03 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/migration/DdcTianshuDdlContractTest.java` | 现有 DdcTianshuDdlContractTest 对已应用 SQL 的位置校验 | prefixAndRouteAfterDrop、retirementDoesNotTouchOldHistory | 冻结 PAC 增量和旧版史 | Step 3、Step 8 | REQ-005、REQ-006、REQ-008 | G03、G09 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/integration/DdcTianshuDdlIT.java` | 现有同文件使用真实 PostgreSQL/Testcontainers 和原 validateTianshuSchema | 迁移精确回执、rollback-on-bad-candidate、history prefix 检查 | 验证新旧版本的实际 PostgreSQL 内容 | Step 3 | REQ-005、REQ-006、REQ-008 | G03 |
| CREATE | T `stellar-enclosure-tianshu-admin/src/main/resources/db/egon-mp/V20261123_001__tianshu_metadata_bootstrap_receipt.sql` | DdcPermissionMetadataBootstrapRepository 的 pac-b0 前缀、原 UTC created_at 和原 JSON 字符串。当前 PostgreSQL MP baseline | ddc_metadata_bootstrap_receipt_t0、PK tenant_id/operation_id | 创建并迁出 PAC 业务回执 | Step 3 | REQ-005、REQ-006、REQ-008 | G03 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/main/resources/db/egon-mp/repository-manifest.json` | 当前最后 20261119_001，freshBaselineVersion 20261018_001 | V20261123_002 | 追加第一个业务版史 | Step 3、Step 8 | REQ-005、REQ-006、REQ-008 | G03、G09 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/bootstrap/DdcPermissionMetadataBootstrapRepository.java` | 现有 initialize 以 -tenant advisory_xact_lock、SERIALIZABLE、read-for-update 核对 digest/expectedReceipt | initialize、receipt、table 物理选择 | 将 CLI 新写和重读切到专表 | Step 3 | REQ-005、REQ-006、REQ-008 | G03 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/config/DdcDatabaseReadyValidator.java` | 当前 validator 精确校验每条 history、旧表和旧约束 | TIANSHU_TABLES、EXPECTED_COLUMNS、TABLE_CONSTRAINTS 中 ddc_operation_log_t0 | 使回退制品兼容追加后的业务版史 | Step 3、Step 8 | REQ-005、REQ-006、REQ-008 | G03、G09 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/config/DdcConfigServiceTest.java` | 当前 DdcConfigServiceTest 测试和 Spec §7/§14 | create/upsert/update/delete/rollback 与回滚动作 | 先用现有测试锁定切换路径 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/publish/DdcPublishPreparationTest.java` | 当前 DdcPublishPreparationTest 测试和 Spec §7/§14 | publish/prepare 及重放与源 enqueue | 先用现有测试锁定切换路径 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/persistence/DdcConfigDeliveryPersistenceIT.java` | 当前 DdcConfigDeliveryPersistenceIT 测试和 Spec §7/§14 | publish 真实事务失败夹具 | 先用现有测试锁定切换路径 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/publish/DdcNativeConfigRolloutServiceTest.java` | 当前 DdcNativeConfigRolloutServiceTest 测试和 Spec §7/§14 | finish/版本 CAS/阶段结果 | 先用现有测试锁定切换路径 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/i18n/DdcI18nPageServiceTest.java` | 当前 DdcI18nPageServiceTest 测试和 Spec §7/§14 | create、replaceDraft、publish 的原 audit | 先用现有测试锁定切换路径 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/i18n/DdcI18nSourceImportServiceTest.java` | 当前 DdcI18nSourceImportServiceTest 测试和 Spec §7/§14 | importSource 的 trusted producer 和重复回执 | 先用现有测试锁定切换路径 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/list/DdcListTemplateServiceTest.java` | 当前 DdcListTemplateServiceTest 测试和 Spec §7/§14 | 模板保存/切换/删除的原 audit | 先用现有测试锁定切换路径 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/list/DdcNamespaceExportServiceTest.java` | 当前 DdcNamespaceExportServiceTest 测试和 Spec §7/§14 | export 生成回调及二次权限检查 | 先用现有测试锁定切换路径 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/integration/DdcConfigWorkerApplicationTest.java` | 当前 DdcConfigWorkerApplicationTest 测试和 Spec §7/§14 | 独立 Publisher/Observer 和手工导入集 | 先用现有测试锁定切换路径 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/config/DdcAdminOutboxDispatchConfigurationTest.java` | 当前 DdcAdminOutboxDispatchConfigurationTest 测试和 Spec §7/§14 | adminDeliveryRegistry 的 channel 集 | 先用现有测试锁定切换路径 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/contract/DdcProfileParityTest.java` | 当前 DdcProfileParityTest 测试和 Spec §7/§14 | 配置键对比 | 先用现有测试锁定切换路径 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/migration/DdcLegacyImportContractTest.java` | 当前 DdcLegacyImportContractTest 测试和 Spec §7/§14 | 旧普通日志导入检查 | 先用现有测试锁定切换路径 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| CREATE | T `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/outbox/DdcOperationLogOutboxAdapterTest.java` | DdcConfigDispatchDeliveryHandler、DdcAiPublicationOutboxTest 既有宿主测试 | acceptOnlyAfterCommit、retryCommitUnknown、permanentEnvelopeConflict、restoreTenantOnFailure | 锁定本地 Outbox 双事务边界 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| CREATE | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/outbox/DdcOperationLogOutboxAdapter.java` | DdcOutboxConfiguration 的具名 source 事务和 `DdcConfigDispatchDeliveryHandler` 的可信 tenant 绑定。current Outbox typed API | DdcOperationLogOutboxAdapter、固定 tianshu.operation-log/tianshu-audit、OutboxMessage、DeliveryResult | 复用现有 Outbox 生产与消费接口 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| CREATE | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/config/DdcOperationLogWebConfiguration.java` | DdcTenantContextExecutor、DdcAdminAuth* 已验证权限，Spring MVC HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE | DdcOperationLogWebConfiguration、operationLogContextProvider、审计投影注册 | 提供来自可信 Web/宿主的调用快照与声明 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/config/DdcConfigService.java` | 当前版本与日志同 transaction，存在包装重载和手写 DI 构造器 | create、upsert、update、delete、rollback、saveOperation | 替换现有配置写入调用 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/publish/DdcPublishService.java` | TransactionTemplate 内实际创建任务与 enqueueNative | publish、prepare、retry 与原 changeId 核对 | 在实际发布受理时入队 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/publish/DdcNativeConfigRolloutService.java` | 原 row lock、wakeup 匹配、CAS 和专用 PublisherApplication | finish、updateNativeEvidence、PUBLISHING、PLATFORM_APPLIED、SUPERSEDED 等 | 记录真正提交的执行阶段 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/config/DdcConfigWorkerApplicationConfiguration.java` | PublisherApplication 手工创建 DdcNativeConfigRolloutService，Observer 独立，Jackson alias、Clock 已固定 | PublisherApplication#rollouts、PersistenceApplication 白名单 | 装配已有非 Web 发布 Worker 的生产边界 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/i18n/DdcI18nPageService.java` | 已有 source scope、回执和发布命令的事务边界 | create、replaceDraft、publish 触达原 audit helper | 替换页面语言写操作 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/i18n/DdcI18nSourceImportService.java` | 已有 DdcSourceImportIdentityBO 与来源 grant 事务 | importSource、事务内日志追加、receipt | 替换可信来源导入审计 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/list/DdcListTemplateService.java` | 已验 SystemAuthorizationSnapshot 和模板 CAS | 原 audit helper、模板可写操作 | 替换模板命令审计 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/list/DdcNamespaceExportService.java` | 当前 export 不是长写事务，已有两次权限快照和生成成功 callback | export、audit、XlsxExport 回调 | 在文件生成回调记录事实 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/migration/DdcLegacyTableStrategy.java` | 现有 ordered 老表迁移包含 ddc_operation_log | ddc_operation_log 注册项与单表策略分派 | 停止 legacy 导入普通历史 | Step 4 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008 | G04 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/main/resources/egon-tianshu-persistence.yml` | 原共享持久化 YAML 提供业务 MP 与 Outbox，application/本地/测试/si-catalog 按实际 import 复用 | sharding.tables.ddc_operation_log | 在现有共享配置放新能力的默认设置 | Step 4、Step 8 | REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008、REQ-006 | G04、G09 |
| CREATE | T `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/web/DdcOperationLogWebContractTest.java` | DdcAdminSecurityIntegrationTest 和 DdcAdminWebResourceTest WebMvc/真实路由 patterns | supportsPageAndDetail、forbidsGeneralRead、deniesCrossTenant、openApiAndNoStore | 固定有真实安全链的 API RED | Step 5 | REQ-002、REQ-004、REQ-007 | G05 |
| CREATE | T `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/audit/DdcOperationLogQueryServiceTest.java` | 现有 DdcConfigServiceTest 快照检查与组件 Spec §11.2.1 | equalTimeCrossBuckets、sameAnchor、failedBucketNoResult、detailExactInstant | 固定只读有界分页行为 | Step 5 | REQ-002、REQ-004、REQ-007 | G05 |
| CREATE | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/query/DdcOperationLogPageQuery.java` | DdcConfigPreviewQuery 与现有 Jakarta 校验 | DdcOperationLogPageQuery、from/to、beforeTime/beforeId、limit 与 §9 筛选 | 承载查询输入与归一化 | Step 5 | REQ-002、REQ-004、REQ-007 | G05 |
| CREATE | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/vo/DdcOperationLogSummaryVO.java` | DdcPublishTaskController 的 ResultRecord/VO 与 MP 摘要投影 | DdcOperationLogSummaryVO、§9.2.1 records 项 | 固定列表最小可见字段 | Step 5 | REQ-002、REQ-004、REQ-007 | G05 |
| CREATE | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/vo/DdcOperationLogDetailVO.java` | DdcPublishTaskController 既有 DTO/VO wire。底层 §11 行 | DdcOperationLogDetailVO、§9.2.2 data | 固定获权详情字段 | Step 5 | REQ-002、REQ-004、REQ-007 | G05 |
| CREATE | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/vo/DdcOperationLogPageResult.java` | 既有 Egon ResultRecord。Spec 选择 keyset 代替 count 容器 | DdcOperationLogPageResult、from/to、records、nextCursor.beforeTime/beforeId | 固定页返回和下一页位置 | Step 5 | REQ-002、REQ-004、REQ-007 | G05 |
| CREATE | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/convertor/DdcOperationLogViewConverter.java` | Tianshu 根 POM 的 MapStruct/binding 与原 DdcPublishTaskConverter | Summary extends BaseForwardConverter<OperationLogPO,DdcOperationLogSummaryVO>。Detail extends BaseForwardConverter<OperationLogPO,DdcOperationLogDetailVO> | 用现有 MapStruct 生成读投影 | Step 5 | REQ-002、REQ-004、REQ-007 | G05 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/permission/DdcPermissionCodes.java` | 既有 READ/WRITE 与 i18n 资源编目，permission-producer profile | TIANSHU_OPERATION_LOG_READ、TIANSHU_OPERATION_LOG_DETAIL、@PermissionDefinition、@PermissionMessage | 声明独立审计权限 | Step 5 | REQ-002、REQ-004、REQ-007 | G05 |
| MODIFY | T `stellar-enclosure-tianshu-starter/src/main/java/top/egon/cola/component/tianshu/error/DdcErrorStatus.java` | 现有 INVALID_REQUEST 56000 和 INTERNAL_FAILURE 56999。Repo 不会创建新错误协议 | TIANSHU_OPERATION_LOG_UNAVAILABLE 56998 | 注册可区分读取不可用的业务错误 | Step 5 | REQ-002、REQ-004、REQ-007 | G05 |
| CREATE | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/audit/DdcOperationLogQueryService.java` | DdcTenantContextExecutor、既有 publish 查询型服务、Step 2 OperationLogRepository | page(DdcOperationLogPageQuery)、detail(eventId,occurredAt) | 执行授权上下文中的只读查询 | Step 5 | REQ-002、REQ-004、REQ-007 | G05 |
| CREATE | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/controller/audit/DdcOperationLogController.java` | DdcPublishTaskController 使用 @EgonGatewayPolicy、@PermissionResource、@RequiresPermissions。现有 ResultRecord | GET page、GET detail、operationId tianshu.ddcOperationLogController.page/.detail | 暴露两条只读合同和 springdoc | Step 5 | REQ-002、REQ-004、REQ-007 | G05 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/security/tenancy/DdcTenantContextFilter.java` | 目前对 /api/v1/tianshu/i18n/、/ai/ 显式校验 | requiresExpectedTenant | 将操作审计纳入预期租户验证 | Step 5 | REQ-002、REQ-004、REQ-007 | G05 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/security/management/DdcAdminSecurityConfiguration.java` | 既有显式 GET 列表、`anyRequest().denyAll()`，scopeAndPermission 校 OAuth 与 Rbac3UserDetails | requestMatchers、scopeAndPermission 底层检查 | 在现有 denyAll 前放行具体方法和审计权限 | Step 5 | REQ-002、REQ-004、REQ-007 | G05 |
| CREATE | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/permission/DdcTianshuOperationLogsMessages.java` | DdcTianshuPublishTasksMessages 为每页独立的源默认 | @PermissionMessage、pageCode tianshu.operationLogs | 声明操作日志页双语消息 | Step 5 | REQ-002、REQ-004、REQ-007 | G05 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/i18n/DdcAdminUiI18nCatalog.java` | 现有 tianshu.publishTasks 静态页面注册和 locale 动态覆盖 | PAGE_CODES、fromDeclarations | 在正统语言目录登记新页面 | Step 5 | REQ-002、REQ-004、REQ-007 | G05 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/i18n/DdcAdminUiI18nCatalogTest.java` | 已有页面注册和 Bundle set 断言 | PAGE_CODES、新 page bundle locale coverage | 核对翻译源与完整消息列表 | Step 5 | REQ-002、REQ-004、REQ-007 | G05 |
| CREATE | W `src/pages/OperationLogsPage.test.tsx` | PublishTasksPage.test.tsx、AdminI18nProvider.test.tsx、access.test.tsx | listsAndKeepsCursor、showsUnavailableAndEmpty、deniesDetail、tenantGenerationDropsOldResult | 按真实 UI 使用路径写 RED | Step 6 | REQ-002、REQ-007 | G06 |
| CREATE | W `src/api/operationLog.ts` | src/api/client.ts 的 ddcApi<T>、DdcRequestOptions、X-Egon-Tianshu-Tenant | pageOperationLogs、getOperationLogDetail、审计读取 shape | 承载两条已审批 GET 合同 | Step 6 | REQ-002、REQ-007 | G06 |
| MODIFY | W `src/permissions/permissions.ts` | 现有 READ、PUBLISH、AI_READ 等常量 | 审计 READ/DETAIL 常量 | 同步审计权限码 | Step 6 | REQ-002、REQ-007 | G06 |
| MODIFY | W `src/permissions/resources.ts` | publishTasks 页面与菜单的标准定义 | tianshu.page.operationLogs、对应菜单、父 tianshu.app.console | 声明菜单和页面资源并标注语言键 | Step 6 | REQ-002、REQ-007 | G06 |
| MODIFY | W `src/test/i18nCatalogs.json` | scripts/sync-i18n-fixtures.mjs 读取 tianshu-java manifest，产出已有 JSON 文件 | tianshu.operationLogs 与权限资源测试数据 | 同步 server 编译后的语言 fixture | Step 6 | REQ-002、REQ-007 | G06 |
| CREATE | W `src/pages/OperationLogsPage.tsx` | PublishTasksPage、现有 usePageMessages、access usePermission、Ant Design | OperationLogsPage、筛选与已访问页、Drawer | 展示租户内的可用审计列表和获权详情 | Step 6 | REQ-002、REQ-007 | G06 |
| MODIFY | W `src/App.tsx` | 与 publish-tasks 同一管理路由组及 PageLanguageBoundary | operation-logs、tianshu.page.operationLogs、tianshu.operationLogs | 接通实际路由和双语边界 | Step 6 | REQ-002、REQ-007 | G06 |
| CREATE | T `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/integration/DdcOperationLogCutoverIT.java` | DdcTianshuDdlIT 使用真实版史与 metadata，DdcConfigOutboxIntegrationTest 已使用原 Outbox，Components EgonColaPostgreSqlReadwriteIT 复用四物理 URL 约定 | physicalAuditAndFailureRecovery、pacOnlyAndRollbackArtifact、replicaLagAndCapacity | 先写端到端故障和回退验收 | Step 7 | REQ-003、REQ-004、REQ-005、REQ-006、REQ-007、REQ-008 | G07 |
| CREATE | T `scripts/work/2026-11-23-04-18-operation-log-verify.sh` | 现有 Maven test selector 可定向实跑。但跨制品、物理备库回放/暂停和旧 worker 水位没有单命令 | 验收后同 Step 删除，不做 durable 工件 | 仅在实施环境执行缺省无命令的 release 对照演练 | Step 7 | REQ-003、REQ-004、REQ-005、REQ-006、REQ-007、REQ-008 | G07 |
| CREATE | T `docs/tianshu-operation-log-rollout.md` | docs/tianshu-ai-migration-runbook.md 与 README 的 immutable GAV/部署边界 | 目标诊断、PAC 兼容制品、2×2 检查、暂停/恢复、保留与退役门 | 沉淀审核可用的发布和回退手册 | Step 7 | REQ-003、REQ-004、REQ-005、REQ-006、REQ-007、REQ-008 | G07 |
| CREATE | T `stellar-enclosure-tianshu-admin/src/main/resources/db/egon-mp/V20261123_002__retire_tianshu_operation_log.sql` | §11.2.4、业务 runner 和独立 cutoff | V20261123_002、ddc_operation_log_t0 | 登记独立物理清退版本 | Step 8 | REQ-005、REQ-006、REQ-008 | G09 |
| DELETE | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/repository/impl/DdcOperationLogRepositoryImpl.java` | 现有 DdcOperationLogRepositoryImpl 启动/编译引用和 Step 4 写切断证据 | 仅退役批准时删除 | 移除已切断的旧表实现 | Step 8 | REQ-005、REQ-006、REQ-008 | G09 |
| DELETE | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/repository/DdcOperationLogRepository.java` | 现有 DdcOperationLogRepository 启动/编译引用和 Step 4 写切断证据 | 仅退役批准时删除 | 移除已切断的旧表实现 | Step 8 | REQ-005、REQ-006、REQ-008 | G09 |
| DELETE | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/dao/DdcOperationLogMapper.java` | 现有 DdcOperationLogMapper 启动/编译引用和 Step 4 写切断证据 | 仅退役批准时删除 | 移除已切断的旧表实现 | Step 8 | REQ-005、REQ-006、REQ-008 | G09 |
| DELETE | T `stellar-enclosure-tianshu-admin/src/main/resources/mybatis/mapper/tianshu/DdcOperationLogMapper.xml` | 现有 DdcOperationLogMapper XML 启动/编译引用和 Step 4 写切断证据 | 仅退役批准时删除 | 移除已切断的旧表实现 | Step 8 | REQ-005、REQ-006、REQ-008 | G09 |
| DELETE | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/convertor/DdcOperationLogConverter.java` | 现有 DdcOperationLogConverter 启动/编译引用和 Step 4 写切断证据 | 仅退役批准时删除 | 移除已切断的旧表实现 | Step 8 | REQ-005、REQ-006、REQ-008 | G09 |
| DELETE | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/bo/DdcOperationLogBO.java` | 现有 DdcOperationLogBO 启动/编译引用和 Step 4 写切断证据 | 仅退役批准时删除 | 移除已切断的旧表实现 | Step 8 | REQ-005、REQ-006、REQ-008 | G09 |
| DELETE | T `stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/po/DdcOperationLogPO.java` | 现有 DdcOperationLogPO 启动/编译引用和 Step 4 写切断证据 | 仅退役批准时删除 | 移除已切断的旧表实现 | Step 8 | REQ-005、REQ-006、REQ-008 | G09 |
| MODIFY | T `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/repository/DdcPublishPersistenceContractTest.java` | 现有 case DdcOperationLog 对逻辑表映射和字段，其他领域仍应保留 | DdcOperationLog 条目 | 剔除仅验证旧普通 PO 的契约项 | Step 8 | REQ-005、REQ-006、REQ-008 | G09 |

## 6. Prerequisites, Constraints, and Plan Clarifications

### 6.1 Repository and worktree baseline

| Repo | Validation root | Verified baseline | Scope guard |
| --- | --- | --- | --- |
| C | /Users/mario/SelfProject/Stellar-Enclosure/Stellar-Enclosure-Components | main@5f50797，本地 origin/main 一致 | 既有未跟踪 Outbox Spec/Plan 不纳入此次提交。Plan 与目标 Spec 单独核对 |
| T | /Users/mario/SelfProject/Stellar-Enclosure/tianshu-service | main@56bafda，已 ahead 2、无工作树改动 | 保留两条先前提交 |
| W | /Users/mario/SelfProject/Stellar-Enclosure/tianshu-web | main@f7e0a07，本地 origin/main 一致 | 仅计划页面、资源配置与 test fixture |

三仓根及相关上层当前未发现磁盘 AGENTS.md。会话 AGENTS.md 生效。每步在对应根复核 git status 与暂存路径。Java 用各仓 Maven Wrapper 和 Java 25，Web 用 Node 24 和现有 npm lock。

### 6.2 Build, test, and environment prerequisites

- EGON_AUDIT_PLAN_M2 是隔离 Maven 本地仓绝对路径。需要源码依赖构建时，从 C 或 T 执行现有 `bash ../Stellar-Enclosure-Parent/scripts/bootstrap-workspace.sh --workspace /Users/mario/SelfProject/Stellar-Enclosure --local-repository "$EGON_AUDIT_PLAN_M2"`。核对 JDK25、wrapper 和现有 parent/BOM。组件先安装，同仓解析和校验 JAR SHA-256 后才能编译 Admin。
- 实测使用独立源主库和两组真实流复制审计库。复用 EGON_MP_PG_SHARDING_IT=true、EGON_MP_PG_USER、EGON_MP_PG_PASSWORD 与 EGON_MP_PG_SHARD_0_PRIMARY_URL、EGON_MP_PG_SHARD_0_REPLICA_URL、EGON_MP_PG_SHARD_1_PRIMARY_URL、EGON_MP_PG_SHARD_1_REPLICA_URL。必须核实两主、两副本及各自 streaming 对应，DDL 专用角色与 append/read 角色分开。T Step 7 使用 TIANSHU_OPERATION_LOG_IT_SOURCE_URL、TIANSHU_OPERATION_LOG_IT_SOURCE_USER、TIANSHU_OPERATION_LOG_IT_SOURCE_PASSWORD 的独立源库。
- Web 正式 build 用现有 README 的 PAC_SDK_DIR、PAC_TOOLCHAIN_LOCK、PAC_CONTRACT_ARTIFACT、PAC_BUILD_ID。AUDIT_TIANSHU_MANIFEST 是 Step 5 的 tianshu-java manifest 完整绝对路径。仓内 fixture 可用于 Vitest，正式构件要有锁定工具链和同版实际 Java 制品 SHA。
- 版本号、制品仓、部署身份、审计保留/允许迟到/重投窗口、连接池和 p99/滞后预算是发布输入。0.0.1 lineage 在本地隔离 Maven 源码验证不等于发布同 GAV。真实发布按 README 的不可变坐标规则和已获准新版本准备制品及现有 BOM 选择，禁止覆盖已发布产物。

### 6.3 Immutable constraints and approved decisions

C/T 已应用 SQL、manifest 历史与 checksum 不重写。两审计目标可以分别使用相同的迁移版本号，各自只登记对应脚本。默认关闭无新业务副作用。用户已准许 MP-Ext 编译期使用现有 MapStruct 和 Lombok binding processors。按部署版固定 MP 与 Outbox 制品，不改引擎、协调和 DEAD 运维。测试凭证和数据限隔离环境，生产部署、授权和 DDL 按之后的发布审批。

### 6.4 Plan Clarifications

执行补充审批。第 2 步首次编译确认 Common Core 的 MapStruct API 是 test scope，MP-Ext 无可用的 @Mapper。用户回复「确认添加编译期 API」，批准 org.mapstruct:mapstruct:1.6.3（provided）。生成实现直接实例化，无运行时 MapStruct 工厂依赖。首次编译失败及缺少 org.mapstruct 包的诊断见 step2-mapstruct-api-missing.log。

| ID | Local/reversible implementation detail | Exact evidence | Why it preserves the Spec | Impact if wrong | Step |
| --- | --- | --- | --- | --- | --- |
| PC-001 | MP-Ext 配置两项已有编译处理器，Common Core 对 MapStruct API 使用 test scope，不能传递至 MP-Ext。用户已批准 | C 根 POM 默认无 MapStruct processor，MP-Ext 无覆盖。RPC Tianshu adapter 和 T 根 POM 使用相同版本 | 仅落实 §10.4 生成转换，无新运行依赖 | 生成不全回 POM/映射验证 | Step 2 |
| PC-002 | 显式装配 Publisher 生产端、Admin 消费端，目标 readiness 与是否产生新事件区分 | DdcConfigWorkerApplicationConfiguration.PublisherApplication#rollouts 手工构造，Admin registry 刻意排除 rollout channel | 职责不变，直接覆盖 §7.3.1 的独立来源和 §16 先消费就绪 | 强制验证 Publisher 无目标连接及停产可排空 | Step 4 |
| PC-003 | 回退制品使用 Step 3 提交，旧普通行为配合 V001 PAC 回执和新版史 | DdcDatabaseReadyValidator.validateHistory 拒绝超出旧 manifest 的任何版本 | 落实 §16.3 的兼容版史原应用制品。直接切新保留历史为零 | 无同库实际回退证据禁止启用 | Steps 3、7 |
| PC-004 | 补接 denyAll、安全租户过滤、Java 双语编目和 Web 资源声明 | DdcAdminSecurityConfiguration、DdcAdminUiI18nCatalog、resources.ts、sync-i18n-fixtures.mjs | 只接 Spec 已定义的两条 API、权限和页面，保留原 APP 准入 | 未接好表现为 403、导航或语言缺失 | Steps 5、6 |
| PC-005 | 使用实际 ShardingSphere 有界逻辑查询归并，读取每桶 limit+1 的证据。§6.3 的 first_seen_at 用 §11.2.2 已列的 create_time 表示 | EgonColaComplexTenantThenBusinessShardingStrategy、receipt 现有设计列 | 字段及物理边界保持 Spec，零新增商用接口 | 多扫组、桶或时间不保真回 G02/G05 | Steps 2、5 |
| PC-006 | 未证两份实际版史兼容删除时保留无正常读写的物理表及 route | DdcDatabaseReadyValidator、EgonColaManagedDdlInitializer.previous-route-exclusions，§16.2 明确允许保留 | 首发按 Spec 零普通历史，物理退役另行受管 | 未证明不得提前提交/加载 V002 | Step 8 |

## 7. Ordered File-by-file Implementation Steps

每个 File 路径相对该 Step 的 Validation working directory。全新类型只允许先建立可编译合同以进入测试，生产实现开始前仍需真实行为 RED。各 Java 文件遵从 §4.7–4.9。

### Step 1 — 建立默认关闭的 MP-Ext 应用审计采集合同

- Requirements: REQ-001、REQ-002、REQ-003
- Dependencies: 无。只使用现有 Spring AOP、Jakarta Validation、Egon ID 及 Clock
- Baseline state: Components `5f50797` 无 audit 模块或注解。已有事务 Guard 与 Common Core 正常
- Observable outcome: 注解和显式入口在正确源事务只观察一次，禁用时两入口零副作用
- End state: 已定义 Event、SPI、Recorder、Advisor 和开关，假 Destination 通过 Spring 真实事务代理，未接持久化
- Test-first gate: Required。接口/枚举先提供编译期合同。实际 Recorder/Advisor 之前写可编译容器用例，RED 为 Bean 缺失和实际事务未被采集
- Manual Checks: MC-ARCH-001、MC-REUSE-001、MC-NAME-001、MC-VALID-001、MC-MODEL-001、MC-LOG-001、MC-BEAN-001、MC-UTIL-001、MC-JSON-001、MC-TIME-001、MC-CONFIG-001、MC-PATTERN-001、MC-SCOPE-001、MC-TEST-001
- Literal Rules: Rule 1、Rule 2、Rule 3、Rule 4、Rule 5、Rule 6、Rule 7、Rule 9、Rule 10、Rule 11
- Ordered files:

#### File 1 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogContextDTO.java`

- Purpose: 可信调用快照
- Symbols: OperationLogContextDTO
- Repository evidence: DdcTenantContextDTO 与 EgonModel 的可信租户语义
- Dependencies and consumers: 入口上下文提供者 → Recorder。天枢 Servlet 采集在 Step 4
- Why now: 首先冻结本步编译合同或行为 RED 证据。
- Contract/signature changes: OperationLogContextDTO。可信调用快照按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 只持有认证 tenant/actor、HTTP 快照、trace、requestId 和实际签名所需值。不可变 record，防御复制。无请求对象与凭证字段
- Error and edge behavior: 未知 tenant、actor 或来源需拒绝。空 HTTP 字段允许后台。身份不得由动作投影覆盖
- Standards impact: MC-VALID-001、MC-MODEL-001、MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，POJO Lombok、不可变值对象及 Egon 转换约束，Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 3、Rule 6、Rule 10、Rule 11。只持有认证 tenant/actor、HTTP 快照、trace、requestId 和实际签名所需值。不可变 record，防御复制。无请求对象与凭证字段。
- Required change: 设定不可变边界和代理/显式路径共用的字段来源。
- Verification contribution: 本 Step 定向命令与 G01。证明 可信调用快照：未知 tenant、actor 或来源需拒绝。空 HTTP 字段允许后台。身份不得由动作投影覆盖
- After this file: 可信调用快照已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 2 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogWriteModeEnum.java`

- Purpose: 声明稳定边界枚举
- Symbols: OperationLogWriteModeEnum。SOURCE_LOCAL、OUTBOX
- Repository evidence: EgonModel 合同与 §10.3.1、§10.6
- Dependencies and consumers: Event、PO、Properties、天枢读投影
- Why now: File 1 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: OperationLogWriteModeEnum。SOURCE_LOCAL、OUTBOX。声明稳定边界枚举按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 写入显式 code。Phase/Outcome 的 code 用 @EnumValue 与 @JsonValue。按已登记字符串解析。未知拒绝，ordinal 不进任何边界
- Error and edge behavior: 只能启动选定策略，无按异常 fallback
- Standards impact: MC-NAME-001、MC-JSON-001、MC-PATTERN-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 6、Rule 9、Rule 11。写入显式 code。Phase/Outcome 的 code 用 @EnumValue 与 @JsonValue。按已登记字符串解析。未知拒绝，ordinal 不进任何边界。
- Required change: 固定 Spec 枚举并以同一编码参与 API 与持久化断言。
- Verification contribution: 本 Step 定向命令与 G01。证明 声明稳定边界枚举：只能启动选定策略，无按异常 fallback
- After this file: 声明稳定边界枚举已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 3 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogPhaseEnum.java`

- Purpose: 声明稳定边界枚举
- Symbols: OperationLogPhaseEnum。COMMAND、EXECUTION
- Repository evidence: EgonModel 合同与 §10.3.1、§10.6
- Dependencies and consumers: Event、PO、Properties、天枢读投影
- Why now: File 2 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: OperationLogPhaseEnum。COMMAND、EXECUTION。声明稳定边界枚举按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 写入显式 code。Phase/Outcome 的 code 用 @EnumValue 与 @JsonValue。按已登记字符串解析。未知拒绝，ordinal 不进任何边界
- Error and edge behavior: 衔接业务受理与执行
- Standards impact: MC-NAME-001、MC-JSON-001、MC-PATTERN-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 6、Rule 9、Rule 11。写入显式 code。Phase/Outcome 的 code 用 @EnumValue 与 @JsonValue。按已登记字符串解析。未知拒绝，ordinal 不进任何边界。
- Required change: 固定 Spec 枚举并以同一编码参与 API 与持久化断言。
- Verification contribution: 本 Step 定向命令与 G01。证明 声明稳定边界枚举：衔接业务受理与执行
- After this file: 声明稳定边界枚举已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 4 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogOutcomeEnum.java`

- Purpose: 声明稳定边界枚举
- Symbols: OperationLogOutcomeEnum。ACCEPTED、SUCCEEDED、FAILED、PARTIAL_SUCCESS、UNKNOWN、REJECTED、ROLLED_BACK
- Repository evidence: EgonModel 合同与 §10.3.1、§10.6
- Dependencies and consumers: Event、PO、Properties、天枢读投影
- Why now: File 3 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: OperationLogOutcomeEnum。ACCEPTED、SUCCEEDED、FAILED、PARTIAL_SUCCESS、UNKNOWN、REJECTED、ROLLED_BACK。声明稳定边界枚举按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 写入显式 code。Phase/Outcome 的 code 用 @EnumValue 与 @JsonValue。按已登记字符串解析。未知拒绝，ordinal 不进任何边界
- Error and edge behavior: 保留显式 outcome，与 Outbox 技术状态独立
- Standards impact: MC-NAME-001、MC-JSON-001、MC-PATTERN-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 6、Rule 9、Rule 11。写入显式 code。Phase/Outcome 的 code 用 @EnumValue 与 @JsonValue。按已登记字符串解析。未知拒绝，ordinal 不进任何边界。
- Required change: 固定 Spec 枚举并以同一编码参与 API 与持久化断言。
- Verification contribution: 本 Step 定向命令与 G01。证明 声明稳定边界枚举：保留显式 outcome，与 Outbox 技术状态独立
- After this file: 声明稳定边界枚举已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 5 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogEvent.java`

- Purpose: 唯一版本化事件契约
- Symbols: OperationLogEvent、schemaVersion、eventId、operationId
- Repository evidence: Outbox payload 为 JSON 对象，§9.2.4 定义命名与字段
- Dependencies and consumers: Recorder、MapStruct 实现、天枢主写/重验和目标 Repository
- Why now: File 4 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: OperationLogEvent、schemaVersion、eventId、operationId。唯一版本化事件契约按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 普通 Lombok POJO。eventId/tenantId 数字语义采用正 bigint，JSON 十进制字符串。occurredAt 用微秒 Instant，durationMs 非负。details Spring Jackson ObjectNode 明确空对象。原方法/URL 字段上限见 §11.2.1
- Error and edge behavior: 拒绝未知根字段、不可用 schema、类型错配、超限、缺可信来源与非法 HTTP 组合。禁止原 body
- Standards impact: MC-NAME-001、MC-VALID-001、MC-MODEL-001、MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，使用 Jakarta 约束、边界组和明确负例，POJO Lombok、不可变值对象及 Egon 转换约束，Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 2、Rule 3、Rule 6、Rule 10、Rule 11。普通 Lombok POJO。eventId/tenantId 数字语义采用正 bigint，JSON 十进制字符串。occurredAt 用微秒 Instant，durationMs 非负。details Spring Jackson ObjectNode 明确空对象。原方法/URL 字段上限见 §11.2.1。
- Required change: 建 Event schema 1 与字段注解，用标准约束与必要自定义约束承接入口校验。
- Verification contribution: 本 Step 定向命令与 G01。证明 唯一版本化事件契约：拒绝未知根字段、不可用 schema、类型错配、超限、缺可信来源与非法 HTTP 组合。禁止原 body
- After this file: 唯一版本化事件契约已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 6 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogProjection.java`

- Purpose: 定义按动作的投影契约
- Symbols: OperationLogProjection
- Repository evidence: Spec §7.3.1、§13。天枢 DdcConfigService 已拥有实际修改后的版本结果
- Dependencies and consumers: Advisor 和宿主显式入口将实际方法/结果交动作投影
- Why now: File 5 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: OperationLogProjection。定义按动作的投影契约按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 可信快照与业务返回分开。只投影登记的 module/action/resource/operationId、原因与字段级安全详情。重复业务结果无新事实
- Error and edge behavior: 拒绝任意 Map 直通，路径/方法不会被结果覆盖。全规范输出交 Recorder
- Standards impact: MC-VALID-001、MC-MODEL-001、MC-UTIL-001、MC-JSON-001、MC-PATTERN-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，POJO Lombok、不可变值对象及 Egon 转换约束，仅使用既有 JDK/Spring/Egon 能力，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 3、Rule 5、Rule 6、Rule 9、Rule 11。可信快照与业务返回分开。只投影登记的 module/action/resource/operationId、原因与字段级安全详情。重复业务结果无新事实。
- Required change: 声明可验证的结果投影与跳过条件，复用同一写契约。
- Verification contribution: 本 Step 定向命令与 G01。证明 定义按动作的投影契约：拒绝任意 Map 直通，路径/方法不会被结果覆盖。全规范输出交 Recorder
- After this file: 定义按动作的投影契约已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 7 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogDestination.java`

- Purpose: 定义固定写策略 SPI
- Symbols: OperationLogDestination 与 OperationLogWriteModeEnum
- Repository evidence: Spring named strategy 与 §13.1。业务侧现有 TransactionalOutbox.enqueue
- Dependencies and consumers: Recorder 按已选模式调用。Step 2 SOURCE_LOCAL、Step 4 Tianshu OUTBOX
- Why now: File 6 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: OperationLogDestination 与 OperationLogWriteModeEnum。定义固定写策略 SPI按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 接收合法 Event 和源事务绑定。返回代表当次事务执行成功，事务提交仍由宿主管理
- Error and edge behavior: 唯一匹配 Bean，找不到或重复拒绝启用。不吞原异常
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-PATTERN-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，按已选 Strategy/Adapter/Projection 与现有职责划分，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 9、Rule 11。接收合法 Event 和源事务绑定。返回代表当次事务执行成功，事务提交仍由宿主管理。
- Required change: 定义验证、模式识别和源事务共用的最小 SPI。
- Verification contribution: 本 Step 定向命令与 G01。证明 定义固定写策略 SPI：唯一匹配 Bean，找不到或重复拒绝启用。不吞原异常
- After this file: 定义固定写策略 SPI已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 8 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLog.java`

- Purpose: 提供显式标记
- Symbols: @OperationLog(actionCode, resourceType, projector)
- Repository evidence: Spring AOP 现有 Bean 代理、Spec INTERNAL-001
- Dependencies and consumers: 宿主应用服务方式与 Advisor matcher
- Why now: File 7 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: @OperationLog(actionCode, resourceType, projector)。提供显式标记按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 只作用在能代理的目标方法。指向注册投影 Bean 与固定动作码
- Error and edge behavior: 自调用或显式 TransactionTemplate 使用 Recorder 路径。冲突同层单一入口
- Standards impact: MC-NAME-001、MC-VALID-001、MC-PATTERN-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，使用 Jakarta 约束、边界组和明确负例，按已选 Strategy/Adapter/Projection 与现有职责划分，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 2、Rule 9、Rule 11。只作用在能代理的目标方法。指向注册投影 Bean 与固定动作码。
- Required change: 定义注解元数据，供启动检查和反射目标签名使用。
- Verification contribution: 本 Step 定向命令与 G01。证明 提供显式标记：自调用或显式 TransactionTemplate 使用 Recorder 路径。冲突同层单一入口
- After this file: 提供显式标记已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 9 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/test/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogCaptureTest.java`

- Purpose: 先写采集合同 RED
- Symbols: disabledNeverWrites、proxyAndExplicitWriteOnce、outerRollbackNeverWritesSuccess、rejectsSpoofedAndNontxContext
- Repository evidence: EgonColaMybatisPlusAutoConfigurationTest 的 Spring context 与事务探针
- Dependencies and consumers: 仅用上述类型与测试 Destination。实际入口暂用容器按名称查找
- Why now: 在生产行为变化前准备和执行真实断言，可编译的声明不计实现。
- Contract/signature changes: disabledNeverWrites、proxyAndExplicitWriteOnce、outerRollbackNeverWritesSuccess、rejectsSpoofedAndNontxContext。先写采集合同 RED按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 测试事务外、嵌套/同事务显式、重载/bridge、错顺序、非法事件、目标失败与源回滚
- Error and edge behavior: RED 是没有注册 Recorder/Advisor 或未通过已存在的回滚断言。单纯编译失败不计
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 6、Rule 9、Rule 10、Rule 11。测试事务外、嵌套/同事务显式、重载/bridge、错顺序、非法事件、目标失败与源回滚。
- Required change: 构造可执行容器契约，观察调用次数、签名和 Spring 事务，而非 mock 事务提交。
- Verification contribution: OperationLogCaptureTest 作为本步先行验证。本 Step 定向命令与 G01。证明 先写采集合同 RED：RED 是没有注册 Recorder/Advisor 或未通过已存在的回滚断言。单纯编译失败不计
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 10 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/EgonOperationLogProperties.java`

- Purpose: 绑定启动策略配置
- Symbols: EgonOperationLogProperties.enabled、writeMode
- Repository evidence: EgonColaMybatisPlusProperties.PREFIX 与 DdcProfileParityTest
- Dependencies and consumers: 后续 AutoConfiguration、Admin 共用 YAML。无依赖服务 API
- Why now: File 9 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: EgonOperationLogProperties.enabled、writeMode。绑定启动策略配置按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 默认关闭。只绑定已选写模式及 Spec 有界配置。`Clock` 注入与外部容量值分开
- Error and edge behavior: 禁止错误 mode、重复或不完整部署配置支撑隐式启用
- Standards impact: MC-NAME-001、MC-VALID-001、MC-MODEL-001、MC-CONFIG-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，使用 Jakarta 约束、边界组和明确负例，POJO Lombok、不可变值对象及 Egon 转换约束，保持实际各环境配置结构相同，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 2、Rule 3、Rule 7、Rule 10、Rule 11。默认关闭。只绑定已选写模式及 Spec 有界配置。`Clock` 注入与外部容量值分开。
- Required change: 建立校验元数据和稳定的属性键结构。
- Verification contribution: 本 Step 定向命令与 G01。证明 绑定启动策略配置：禁止错误 mode、重复或不完整部署配置支撑隐式启用
- After this file: 绑定启动策略配置已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 11 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogRecorder.java`

- Purpose: 约束真实事务与单个根事实
- Symbols: recordInTransaction(OperationLogEvent)、帧绑定和恢复
- Repository evidence: EgonColaTransactionWriteTarget、EgonColaModelValidationGroups、SnowflakeIdGenerator
- Dependencies and consumers: 显式服务和 Advisor → 配置选定 Strategy → 原源事务
- Why now: File 10 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: recordInTransaction(OperationLogEvent)、帧绑定和恢复。约束真实事务与单个根事实按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 由已验上下文填身份/方法/URL与初始 Snowflake。on-thread 限定事务。微秒归一。已存在源业务回执抑制新 fact。终态保持独立 eventId
- Error and edge behavior: 无真实事务、跨源管理器/路由和非法身份按 §9.2.3 拒绝。失败跟随源回滚，恢复 MDC。禁用时显式入口零写
- Standards impact: MC-NAME-001、MC-VALID-001、MC-BEAN-001、MC-UTIL-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，仅使用既有 JDK/Spring/Egon 能力，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 2、Rule 4、Rule 5、Rule 6、Rule 9、Rule 10、Rule 11。由已验上下文填身份/方法/URL与初始 Snowflake。on-thread 限定事务。微秒归一。已存在源业务回执抑制新 fact。终态保持独立 eventId。
- Required change: 集中校验并保证同事务仅一个命令事实，使用具名 writer 策略。
- Verification contribution: 本 Step 定向命令与 G01。证明 约束真实事务与单个根事实：无真实事务、跨源管理器/路由和非法身份按 §9.2.3 拒绝。失败跟随源回滚，恢复 MDC。禁用时显式入口零写
- After this file: 约束真实事务与单个根事实已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 12 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogAdvisor.java`

- Purpose: 将注解调用接到 Recorder
- Symbols: OperationLogAdvisor、最具体 bridged method、投影调用
- Repository evidence: Spring Named PointcutAdvisor、Spring AopUtils 与 BridgeMethodResolver。Spec §7.3.1
- Dependencies and consumers: 已有事务 advisor 外层，实际业务调用后转 Recorder
- Why now: File 11 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: OperationLogAdvisor、最具体 bridged method、投影调用。将注解调用接到 Recorder按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 目标类完全限定名/方法名/参数类型。按实际 outcome 保留业务响应，计时用单调时钟
- Error and edge behavior: 校验执行顺序、不可代理方法与嵌套。未知提交不伪造事实，不从 proxy 名生成签名
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-UTIL-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，仅使用既有 JDK/Spring/Egon 能力，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 5、Rule 9、Rule 10、Rule 11。目标类完全限定名/方法名/参数类型。按实际 outcome 保留业务响应，计时用单调时钟。
- Required change: 使用已有代理机制，实现业务调用只一次和事务内正确投影。
- Verification contribution: 本 Step 定向命令与 G01。证明 将注解调用接到 Recorder：校验执行顺序、不可代理方法与嵌套。未知提交不伪造事实，不从 proxy 名生成签名
- After this file: 将注解调用接到 Recorder已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 13 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/EgonOperationLogAutoConfiguration.java`

- Purpose: 装配采集扩展点
- Symbols: EgonOperationLogAutoConfiguration、operationLogRecorder、operationLogAdvisor
- Repository evidence: EgonColaShardingAutoConfiguration 的具名 Bean 和旧 MP guards
- Dependencies and consumers: 组件属性与宿主选定 Strategy。Step 2 在同文件补内部独立目标装配
- Why now: File 12 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: EgonOperationLogAutoConfiguration、operationLogRecorder、operationLogAdvisor。装配采集扩展点按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 显式 Recorder 无写禁用分支可供宿主稳定注入。enabled 与策略检查控制实际 advisor。按有事务的代理链校验顺序
- Error and edge behavior: 默认不创建数据源或 AOP 副作用。只启用适用宿主，不影响原数据源 Boot 自动配置。缺有效策略 enabled 启动失败
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-CONFIG-001、MC-PATTERN-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，保持实际各环境配置结构相同，按已选 Strategy/Adapter/Projection 与现有职责划分，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 7、Rule 9、Rule 11。显式 Recorder 无写禁用分支可供宿主稳定注入。enabled 与策略检查控制实际 advisor。按有事务的代理链校验顺序。
- Required change: 把公共采集接入现有自动装配和唯一 Bean，验证禁用与事务边界。
- Verification contribution: 本 Step 定向命令与 G01。证明 装配采集扩展点：默认不创建数据源或 AOP 副作用。只启用适用宿主，不影响原数据源 Boot 自动配置。缺有效策略 enabled 启动失败
- After this file: 装配采集扩展点已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 14 — `MODIFY stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- Purpose: 注册公共自动配置
- Symbols: EgonOperationLogAutoConfiguration
- Repository evidence: 现有 EgonColaMybatisPlusAutoConfiguration 已在同一 imports
- Dependencies and consumers: Boot → 组件采集，后续手工白名单 Worker 另见 Step 4
- Why now: File 13 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: EgonOperationLogAutoConfiguration。注册公共自动配置按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 新增唯一类注册，原注册及顺序依赖不删
- Error and edge behavior: enabled=false 条件下无审计 Mapper 扫描或数据源装配
- Standards impact: MC-CONFIG-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。保持实际各环境配置结构相同，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 7、Rule 11。新增唯一类注册，原注册及顺序依赖不删。
- Required change: 注册自动配置并测试条件下的 Bean 存在性。
- Verification contribution: 本 Step 定向命令与 G01。证明 注册公共自动配置：enabled=false 条件下无审计 Mapper 扫描或数据源装配
- After this file: 注册公共自动配置已具备该职责，继续下一个 File 或本步的整体集成验证。

- Validation working directory: `/Users/mario/SelfProject/Stellar-Enclosure/Stellar-Enclosure-Components`
- Verification command: `./mvnw -B -ntp -Dmaven.repo.local="$EGON_AUDIT_PLAN_M2" -pl stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=OperationLogCaptureTest,EgonColaMybatisPlusAutoConfigurationTest test`。运行及证据顺序见 §8。
- Expected result: 命名用例逐个通过。目标选配、禁用、嵌套、回滚/Bridge 证据齐备
- Failure returns to: 回到 File 8 的 fixture 或实际 Advisor/Recorder。顺序未证实保持 enabled=false
- Completion criteria: 可由 source-only 宿主装配，并可在组件内作为下一步的持久写/查询通用契约
- Rollback: 本提交可单独 revert，默认无已启用宿主或表
- Commit paths: `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogContextDTO.java`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogWriteModeEnum.java`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogPhaseEnum.java`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogOutcomeEnum.java`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogEvent.java`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogProjection.java`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogDestination.java`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLog.java`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/test/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogCaptureTest.java`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/EgonOperationLogProperties.java`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogRecorder.java`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogAdvisor.java`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/EgonOperationLogAutoConfiguration.java`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Commit: `feat(mp-ext): add transactional operation log capture`

### Step 2 — 交付分片审计表与目标读写装配

- Requirements: REQ-003、REQ-004、REQ-008
- Dependencies: Step 1
- Baseline state: 当前 MP 只自动创建业务逻辑源。无审计 schema、模型和同组 receipt
- Observable outcome: 两主每主两桶，目标键和事实同事务，回执重放与副本查询可证明
- End state: 登记两份目标 migration，复用 MP/SDJ，独立具名 Bean 和 SOURCE_LOCAL 策略。下一步安装 Components 制品
- Test-first gate: Required。先执行资源和装配合同 RED。真实 2×2 缺 manifest 与表是预期。编译期转换类型只在被调用前就绪
- Manual Checks: MC-ARCH-001、MC-REUSE-001、MC-DEP-001、MC-NAME-001、MC-VALID-001、MC-MODEL-001、MC-CONVERT-001、MC-LOG-001、MC-BEAN-001、MC-UTIL-001、MC-JSON-001、MC-TIME-001、MC-CONFIG-001、MC-PATTERN-001、MC-SCOPE-001、MC-TEST-001
- Literal Rules: Rule 1、Rule 2、Rule 3、Rule 4、Rule 5、Rule 6、Rule 7、Rule 9、Rule 10、Rule 11
- Ordered files:

#### File 1 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/test/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogPersistenceContractTest.java`

- Purpose: 先断言实际装配拓扑和资源
- Symbols: manifestPerSlot、boundFactoriesAndWriteGuard、preciseReadAndRecvOnlyPrimary
- Repository evidence: EgonColaDdlManifestFamilyTest、EgonColaShardingReadwriteIntegrationTest 与 EgonColaMybatisPlusContractValidator
- Dependencies and consumers: 使用 YAML/Mapper 元数据、实际 context 和已有 role guard，未建逻辑写时先断言资源缺失
- Why now: 首先冻结本步编译合同或行为 RED 证据。
- Contract/signature changes: manifestPerSlot、boundFactoriesAndWriteGuard、preciseReadAndRecvOnlyPrimary。先断言实际装配拓扑和资源按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 测试脚本/manifest SHA、每主物理父表、DDL 角色、factory+manager 唯一，验证 read/allowlist 拒绝服务无关 SQL
- Error and edge behavior: RED 是缺新 manifest/Mapper 参数绑定和目的 Bean。同名 Bean 误扫或跨库事务必须失败
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-CONFIG-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，保持实际各环境配置结构相同，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 6、Rule 7、Rule 9、Rule 10、Rule 11。测试脚本/manifest SHA、每主物理父表、DDL 角色、factory+manager 唯一，验证 read/allowlist 拒绝服务无关 SQL。
- Required change: 建立模板和 wiring 断言，覆盖资源、JSON 数字精度与 PostgreSQL 物理验证入口。
- Verification contribution: OperationLogPersistenceContractTest 作为本步先行验证。本 Step 定向命令与 G02。证明 先断言实际装配拓扑和资源：RED 是缺新 manifest/Mapper 参数绑定和目的 Bean。同名 Bean 误扫或跨库事务必须失败
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 2 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/test/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogPostgresReadwriteIT.java`

- Purpose: 验证组件的真实 PostgreSQL 路径
- Symbols: componentWriteAndReadAcrossBuckets、retryReceiptRace、rollbackWithoutHalfWrites
- Repository evidence: EgonColaPostgreSqlReadwriteIT 的 EGON_MP_PG_SHARDING_IT 及四 URL
- Dependencies and consumers: 使用组件实际 MP/SDJ 入口和显式事务。管理连接仅为夹具及证据
- Why now: 在生产行为变化前准备和执行真实断言，可编译的声明不计实现。
- Contract/signature changes: componentWriteAndReadAcrossBuckets、retryReceiptRace、rollbackWithoutHalfWrites。验证组件的真实 PostgreSQL 路径按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 两主各一副本，至少四个租户/ID 分桶样本，月末/次月同 ID、并发与回滚。观测实际节点、叶表、WAL
- Error and edge behavior: 缺脚本 RED、角色错、两组提交未知、重复 hash 异常分别验证。非启用环境不计 runtime PASS
- Standards impact: MC-VALID-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 6、Rule 9、Rule 10、Rule 11。两主各一副本，至少四个租户/ID 分桶样本，月末/次月同 ID、并发与回滚。观测实际节点、叶表、WAL。
- Required change: 让分桶、服务模式、PostgreSQL 时精度和受管 DDL 同时有隔离实证。
- Verification contribution: OperationLogPostgresReadwriteIT 作为本步先行验证。本 Step 定向命令与 G02。证明 验证组件的真实 PostgreSQL 路径：缺脚本 RED、角色错、两组提交未知、重复 hash 异常分别验证。非启用环境不计 runtime PASS
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 3 — `MODIFY stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/pom.xml`

- Purpose: 仅启用获准的编译处理器
- Symbols: maven-compiler-plugin、annotationProcessorPaths
- Repository evidence: Components parent 的 lombok/boot processors，Common Core 对 MapStruct API 使用 test scope，不能传递至 MP-Ext，RPC Tianshu adapter 已有同版 MapStruct/binding
- Dependencies and consumers: 仅 MP-Ext 构建。MapStruct 生成转换实现由编译产生并被 auto-config 消费
- Why now: File 2 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: maven-compiler-plugin、annotationProcessorPaths。仅启用获准的编译处理器按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 继承现有处理器并加 mapstruct-processor:1.6.3 和 lombok-mapstruct-binding:0.2.0。使用 parent 的 MapStruct 版本属性
- Error and edge behavior: 有效 POM 若丢旧 processor 或 runtime tree 新增坐标则返回本文件。禁止手改 generated-sources
- Standards impact: MC-DEP-001、MC-CONVERT-001、MC-MODEL-001、MC-UTIL-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。POJO Lombok、不可变值对象及 Egon 转换约束，仅使用既有 JDK/Spring/Egon 能力，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 3、Rule 5、Rule 11。继承现有处理器并加 mapstruct-processor:1.6.3 和 lombok-mapstruct-binding:0.2.0。使用 parent 的 MapStruct 版本属性。
- Required change: 局部补齐用户明确确认的编译能力，保持公共 runtime 依赖与组件版本策略。
- Verification contribution: 本 Step 定向命令与 G02。证明 仅启用获准的编译处理器：有效 POM 若丢旧 processor 或 runtime tree 新增坐标则返回本文件。禁止手改 generated-sources
- After this file: 仅启用获准的编译处理器已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 4 — `MODIFY stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/ddl/EgonColaDdlManifestBO.java`

- Purpose: 允许受管审计 DDL family
- Symbols: EgonColaDdlManifestBO family 校验
- Repository evidence: 现有 component-outbox/tianshu 白名单与脚本递增规则
- Dependencies and consumers: EgonColaPostgreDdlRunner → 两个审计 manifest
- Why now: File 3 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: EgonColaDdlManifestBO family 校验。允许受管审计 DDL family按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 只加入 component-operation-log，不变更语义、路径模式或校验长度
- Error and edge behavior: 错误 family、非法路径/校验和、重复版本继续拒绝
- Standards impact: MC-VALID-001、MC-UTIL-001、MC-JSON-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，仅使用既有 JDK/Spring/Egon 能力，Jackson 协议与安全字段一致，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 5、Rule 6、Rule 11。只加入 component-operation-log，不变更语义、路径模式或校验长度。
- Required change: 让已有受管 DDL runner 识别本实施的专用 SQL 目录。
- Verification contribution: 本 Step 定向命令与 G02。证明 允许受管审计 DDL family：错误 family、非法路径/校验和、重复版本继续拒绝
- After this file: 允许受管审计 DDL family已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 5 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/resources/db/egon-operation-log/V20261123_001__initialize_operation_log_slot_0.sql`

- Purpose: 在审计主组 0 安装两桶事实和接收键
- Symbols: V20261123_001、egon_cola_operation_log_t0_b0/b1、egon_cola_operation_log_receipt_t0_b0/b1
- Repository evidence: EgonColaComplexTenantThenBusinessShardingStrategy 实际物理名。Spec §11.2.1/2
- Dependencies and consumers: runner 的显式 PRIMARY 目标 → 本组表、分区和索引。DDL 不经逻辑数据源
- Why now: File 4 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: V20261123_001、egon_cola_operation_log_t0_b0/b1、egon_cola_operation_log_receipt_t0_b0/b1。在审计主组 0 安装两桶事实和接收键按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 完整采用 §11 字段，PK(occurred_at,id) 对应 UTC 月，PK(tenant_id,id) 去重。初始化当月和之后两个月，落地 append 权限与 §11索引
- Error and edge behavior: 角色身份、错误 slot 或脚本失败阻断该目标，不接触另主、旧原始表或副本。不允许非当前事实时间写入 DEFAULT 分区
- Standards impact: MC-VALID-001、MC-CONFIG-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，保持实际各环境配置结构相同，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 7、Rule 10、Rule 11。完整采用 §11 字段，PK(occurred_at,id) 对应 UTC 月，PK(tenant_id,id) 去重。初始化当月和之后两个月，落地 append 权限与 §11索引。
- Required change: 一次只交付一个物理目标的原子脚本及其表约束。
- Verification contribution: 本 Step 定向命令与 G02。证明 在审计主组 0 安装两桶事实和接收键：角色身份、错误 slot 或脚本失败阻断该目标，不接触另主、旧原始表或副本。不允许非当前事实时间写入 DEFAULT 分区
- After this file: 在审计主组 0 安装两桶事实和接收键已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 6 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/resources/db/egon-operation-log/manifest-slot-0.json`

- Purpose: 绑定审计主组 0 版史资源
- Symbols: family component-operation-log、V20261123_001 与 SHA-256
- Repository evidence: EgonColaDdlManifestBO.ScriptBO 和 initializer.target.manifestResource
- Dependencies and consumers: 目标配置将此 manifest 交给已核验 slot 的 DDL runner
- Why now: File 5 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: family component-operation-log、V20261123_001 与 SHA-256。绑定审计主组 0 版史资源按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 保持独立 validation 目标身份和资源类路径，对本 SQL 的最终字节登记哈希
- Error and edge behavior: 拒绝对错资源、跨目标、跳项、修改已安装 checksum。重复运行确认 prefix 已提交
- Standards impact: MC-JSON-001、MC-CONFIG-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。Jackson 协议与安全字段一致，保持实际各环境配置结构相同，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 6、Rule 7、Rule 11。保持独立 validation 目标身份和资源类路径，对本 SQL 的最终字节登记哈希。
- Required change: 用现有版史管理该主组的增量脚本和可重入安装。
- Verification contribution: 本 Step 定向命令与 G02。证明 绑定审计主组 0 版史资源：拒绝对错资源、跨目标、跳项、修改已安装 checksum。重复运行确认 prefix 已提交
- After this file: 绑定审计主组 0 版史资源已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 7 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/resources/db/egon-operation-log/V20261123_001__initialize_operation_log_slot_1.sql`

- Purpose: 在审计主组 1 安装两桶事实和接收键
- Symbols: V20261123_001、egon_cola_operation_log_t1_b0/b1、egon_cola_operation_log_receipt_t1_b0/b1
- Repository evidence: EgonColaComplexTenantThenBusinessShardingStrategy 实际物理名。Spec §11.2.1/2
- Dependencies and consumers: runner 的显式 PRIMARY 目标 → 本组表、分区和索引。DDL 不经逻辑数据源
- Why now: File 6 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: V20261123_001、egon_cola_operation_log_t1_b0/b1、egon_cola_operation_log_receipt_t1_b0/b1。在审计主组 1 安装两桶事实和接收键按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 完整采用 §11 字段，PK(occurred_at,id) 对应 UTC 月，PK(tenant_id,id) 去重。初始化当月和之后两个月，落地 append 权限与 §11索引
- Error and edge behavior: 角色身份、错误 slot 或脚本失败阻断该目标，不接触另主、旧原始表或副本。不允许非当前事实时间写入 DEFAULT 分区
- Standards impact: MC-VALID-001、MC-CONFIG-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，保持实际各环境配置结构相同，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 7、Rule 10、Rule 11。完整采用 §11 字段，PK(occurred_at,id) 对应 UTC 月，PK(tenant_id,id) 去重。初始化当月和之后两个月，落地 append 权限与 §11索引。
- Required change: 一次只交付一个物理目标的原子脚本及其表约束。
- Verification contribution: 本 Step 定向命令与 G02。证明 在审计主组 1 安装两桶事实和接收键：角色身份、错误 slot 或脚本失败阻断该目标，不接触另主、旧原始表或副本。不允许非当前事实时间写入 DEFAULT 分区
- After this file: 在审计主组 1 安装两桶事实和接收键已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 8 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/resources/db/egon-operation-log/manifest-slot-1.json`

- Purpose: 绑定审计主组 1 版史资源
- Symbols: family component-operation-log、V20261123_001 与 SHA-256
- Repository evidence: EgonColaDdlManifestBO.ScriptBO 和 initializer.target.manifestResource
- Dependencies and consumers: 目标配置将此 manifest 交给已核验 slot 的 DDL runner
- Why now: File 7 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: family component-operation-log、V20261123_001 与 SHA-256。绑定审计主组 1 版史资源按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 保持独立 validation 目标身份和资源类路径，对本 SQL 的最终字节登记哈希
- Error and edge behavior: 拒绝对错资源、跨目标、跳项、修改已安装 checksum。重复运行确认 prefix 已提交
- Standards impact: MC-JSON-001、MC-CONFIG-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。Jackson 协议与安全字段一致，保持实际各环境配置结构相同，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 6、Rule 7、Rule 11。保持独立 validation 目标身份和资源类路径，对本 SQL 的最终字节登记哈希。
- Required change: 用现有版史管理该主组的增量脚本和可重入安装。
- Verification contribution: 本 Step 定向命令与 G02。证明 绑定审计主组 1 版史资源：拒绝对错资源、跨目标、跳项、修改已安装 checksum。重复运行确认 prefix 已提交
- After this file: 绑定审计主组 1 版史资源已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 9 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogPO.java`

- Purpose: 承载分区事实存储行
- Symbols: OperationLogPO extends EgonModel、§11.2.1 行字段
- Repository evidence: EgonModel、OutboxMessagePO、EgonColaModelValidationInterceptor
- Dependencies and consumers: Mapper XML、Event 转换、查询 DTO
- Why now: File 8 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: OperationLogPO extends EgonModel、§11.2.1 行字段。承载分区事实存储行按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 持久字段与 §11.2.1 类型、可空一致，继承基字段。使用 @TableName、Lombok @SuperBuilder 和 callSuper。Instant 走已有 JDBC 路径，details 字段级 JSONB。稳定 code
- Error and edge behavior: Persisted 和 Insert 分组分离，ID/租户正值、details 非数组且有界。禁止聚合字段赋值覆盖绑定 tenant
- Standards impact: MC-NAME-001、MC-VALID-001、MC-MODEL-001、MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，使用 Jakarta 约束、边界组和明确负例，POJO Lombok、不可变值对象及 Egon 转换约束，Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 2、Rule 3、Rule 6、Rule 10、Rule 11。持久字段与 §11.2.1 类型、可空一致，继承基字段。使用 @TableName、Lombok @SuperBuilder 和 callSuper。Instant 走已有 JDBC 路径，details 字段级 JSONB。稳定 code。
- Required change: 形成唯一事实 PO。复用 EgonModel 及插入填充，不添加通用可变生命周期。
- Verification contribution: 本 Step 定向命令与 G02。证明 承载分区事实存储行：Persisted 和 Insert 分组分离，ID/租户正值、details 非数组且有界。禁止聚合字段赋值覆盖绑定 tenant
- After this file: 承载分区事实存储行已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 10 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogReceiptPO.java`

- Purpose: 承载独立幂等接收行
- Symbols: OperationLogReceiptPO extends EgonModel、occurredAt、payloadSha256
- Repository evidence: Outbox 技术 PO 的持久化惯例。Spec §11.2.2
- Dependencies and consumers: accept 先写目标 receipt。按路由用原 id、tenant 匹配
- Why now: File 9 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: OperationLogReceiptPO extends EgonModel、occurredAt、payloadSha256。承载独立幂等接收行按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: Lombok @SuperBuilder、callSuper。id/tenant 取经过验证的 Event，指纹小写64 hex，occurredAt 微秒
- Error and edge behavior: 不可业务更新或软删除。same id 不同摘要隐式覆盖禁止
- Standards impact: MC-NAME-001、MC-VALID-001、MC-MODEL-001、MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，使用 Jakarta 约束、边界组和明确负例，POJO Lombok、不可变值对象及 Egon 转换约束，Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 2、Rule 3、Rule 6、Rule 10、Rule 11。Lombok @SuperBuilder、callSuper。id/tenant 取经过验证的 Event，指纹小写64 hex，occurredAt 微秒。
- Required change: 形成跨月独立幂等键 PO 和数据库形状校验。
- Verification contribution: 本 Step 定向命令与 G02。证明 承载独立幂等接收行：不可业务更新或软删除。same id 不同摘要隐式覆盖禁止
- After this file: 承载独立幂等接收行已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 11 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogConverter.java`

- Purpose: 生成事实写侧单向转换
- Symbols: OperationLogConverter extends BaseForwardConverter<OperationLogEvent,OperationLogPO>
- Repository evidence: Common Core BaseForwardConverter。MP 构建 processor 按本 Step 已审批配置
- Dependencies and consumers: 由具名 Bean 提供 Repository，MapStruct 在编译生成实现
- Why now: File 10 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: OperationLogConverter extends BaseForwardConverter<OperationLogEvent,OperationLogPO>。生成事实写侧单向转换按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: eventId 映射 id，事实字段同名。技术字段通过 Egon 填充，详情隔离复制，空为对象，occurredAt 已归一化
- Error and edge behavior: MapStruct unmapped target 检查只显式忽略技术字段，禁止把信任身份从其他 Mapper 拼进来
- Standards impact: MC-NAME-001、MC-MODEL-001、MC-BEAN-001、MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，POJO Lombok、不可变值对象及 Egon 转换约束，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 3、Rule 4、Rule 6、Rule 10、Rule 11。eventId 映射 id，事实字段同名。技术字段通过 Egon 填充，详情隔离复制，空为对象，occurredAt 已归一化。
- Required change: 按 Spec 生成并注册可验证的单向映射。
- Verification contribution: 本 Step 定向命令与 G02。证明 生成事实写侧单向转换：MapStruct unmapped target 检查只显式忽略技术字段，禁止把信任身份从其他 Mapper 拼进来
- After this file: 生成事实写侧单向转换已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 12 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogDAO.java`

- Purpose: 声明固定事实读写契约
- Symbols: OperationLogDAO、append、page、findDetail
- Repository evidence: EgonColaMapper、OutboxMessageDAO 对不适用生命周期方法默认拒绝
- Dependencies and consumers: OperationLogRepository → XML。底层读写侧按已有 guard
- Why now: File 11 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: OperationLogDAO、append、page、findDetail。声明固定事实读写契约按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 继承 EgonColaMapper，固定逻辑名。Insert/Persisted/Query 形状约束。详情参数 tenant/id/time，列表 tenant/范围/成对游标/筛选/limit
- Error and edge behavior: 不支持按 ID 无发生时间的泛读、更新、归档或软删。保留逻辑角色同时封闭通用入口
- Standards impact: MC-NAME-001、MC-VALID-001、MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，使用 Jakarta 约束、边界组和明确负例，Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 2、Rule 6、Rule 10、Rule 11。继承 EgonColaMapper，固定逻辑名。Insert/Persisted/Query 形状约束。详情参数 tenant/id/time，列表 tenant/范围/成对游标/筛选/limit。
- Required change: 声明 append-only 所需的 named statements 和边界校验。
- Verification contribution: 本 Step 定向命令与 G02。证明 声明固定事实读写契约：不支持按 ID 无发生时间的泛读、更新、归档或软删。保留逻辑角色同时封闭通用入口
- After this file: 声明固定事实读写契约已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 13 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogReceiptDAO.java`

- Purpose: 声明接收键写入与同组确认
- Symbols: OperationLogReceiptDAO、insertIfAbsent、confirm
- Repository evidence: EgonColaMapper、OutboxMessageDAO 技术主读先例
- Dependencies and consumers: OperationLogRepository → XML。既有 role guard 回调
- Why now: File 12 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: OperationLogReceiptDAO、insertIfAbsent、confirm。声明接收键写入与同组确认按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 继承 EgonColaMapper。记录经 Insert group，确认只按可信 tenant/id 并含活动行识别。期望影响 0 或 1
- Error and edge behavior: 禁止其他主读 SQL 或 update/deletion。冲突由旧摘要比较，不得覆盖
- Standards impact: MC-NAME-001、MC-VALID-001、MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，使用 Jakarta 约束、边界组和明确负例，Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 2、Rule 6、Rule 10、Rule 11。继承 EgonColaMapper。记录经 Insert group，确认只按可信 tenant/id 并含活动行识别。期望影响 0 或 1。
- Required change: 把持久幂等键的两条技术语句固定成逻辑 Mapper 合同。
- Verification contribution: 本 Step 定向命令与 G02。证明 声明接收键写入与同组确认：禁止其他主读 SQL 或 update/deletion。冲突由旧摘要比较，不得覆盖
- After this file: 声明接收键写入与同组确认已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 14 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/resources/mapper/operationlog/OperationLogMapper.xml`

- Purpose: 接通事实写入和受限分页
- Symbols: OperationLogDAO 三条 named statements、摘要 resultMap
- Repository evidence: Tianshu DdcOperationLogMapper.xml 现有 active 谓词。Spec §11.2.1
- Dependencies and consumers: 指定审计 Factory, 精确 shard 由 Egon 规则推导
- Why now: File 13 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: OperationLogDAO 三条 named statements、摘要 resultMap。接通事实写入和受限分页按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 带绑定参数、精确发生时间和租户路由。摘要列不加载 details、request_url、handler_method。组合时间与 ID 倒序及每桶 limit+1
- Error and edge behavior: 任一桶错误不产生部分结果。没有 COUNT/offset 深分页。证明参数安全和实际裁剪
- Standards impact: MC-VALID-001、MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 6、Rule 10、Rule 11。带绑定参数、精确发生时间和租户路由。摘要列不加载 details、request_url、handler_method。组合时间与 ID 倒序及每桶 limit+1。
- Required change: 交付可核查的分区命中和摘要最小选择。
- Verification contribution: 本 Step 定向命令与 G02。证明 接通事实写入和受限分页：任一桶错误不产生部分结果。没有 COUNT/offset 深分页。证明参数安全和实际裁剪
- After this file: 接通事实写入和受限分页已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 15 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/resources/mapper/operationlog/OperationLogReceiptMapper.xml`

- Purpose: 接通同一主组的竞争安全确认
- Symbols: OperationLogReceiptDAO.insertIfAbsent、confirm
- Repository evidence: MybatisPlusOutboxStore 的技术 Mapper/主读边界与 Spec §11.2.2
- Dependencies and consumers: 只在目标实际事务内参与写/确认。映射到接收表
- Why now: File 14 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: OperationLogReceiptDAO.insertIfAbsent、confirm。接通同一主组的竞争安全确认按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 冲突无操作写入键，固定正租户/ID 请求确认精确摘要和时间。JSON 指纹已在上层生成
- Error and edge behavior: 无先查再写。次语句当前主库可见性需在实际 READ_COMMITTED 且两语句同组确认
- Standards impact: MC-VALID-001、MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 6、Rule 10、Rule 11。冲突无操作写入键，固定正租户/ID 请求确认精确摘要和时间。JSON 指纹已在上层生成。
- Required change: 供真实 ShardingSphere PostgreSQL 证明 upsert 解析与幂等语义。
- Verification contribution: 本 Step 定向命令与 G02。证明 接通同一主组的竞争安全确认：无先查再写。次语句当前主库可见性需在实际 READ_COMMITTED 且两语句同组确认
- After this file: 接通同一主组的竞争安全确认已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 16 — `CREATE stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogRepository.java`

- Purpose: 封装同组事务和有界读
- Symbols: append、accept、page、findDetail
- Repository evidence: EgonColaRepository 受管模式、EgonColaTransactionWriteTarget、ShardingWriteTargetResolver
- Dependencies and consumers: 走 DM/DAO，返回给目标 handler 事务结果及 Step 5 Query
- Why now: File 15 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: append、accept、page、findDetail。封装同组事务和有界读按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 对外只暴露 Spec 四种能力，受管 Mapper/Repository 基础以内部实现组合。目标事务先写键再写事实。zero-insert 比对规范摘要与 occurredAt。保留原 ID，详情读取副本
- Error and edge behavior: 目标断连/超时可重试，内容冲突永久，失败整事务回滚。SOURCE_LOCAL 强制真实归属及事务数据源一致，零跨源写。按选定策略固定
- Standards impact: MC-NAME-001、MC-VALID-001、MC-MODEL-001、MC-BEAN-001、MC-UTIL-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，使用 Jakarta 约束、边界组和明确负例，POJO Lombok、不可变值对象及 Egon 转换约束，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，仅使用既有 JDK/Spring/Egon 能力，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 2、Rule 3、Rule 4、Rule 5、Rule 6、Rule 9、Rule 10、Rule 11。对外只暴露 Spec 四种能力，受管 Mapper/Repository 基础以内部实现组合。目标事务先写键再写事实。zero-insert 比对规范摘要与 occurredAt。保留原 ID，详情读取副本。
- Required change: 让事务写、幂等、无部分页和明确读写边界走真实 Mapper。
- Verification contribution: 本 Step 定向命令与 G02。证明 封装同组事务和有界读：目标断连/超时可重试，内容冲突永久，失败整事务回滚。SOURCE_LOCAL 强制真实归属及事务数据源一致，零跨源写。按选定策略固定
- After this file: 封装同组事务和有界读已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 17 — `MODIFY stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/EgonOperationLogAutoConfiguration.java`

- Purpose: 装配隔离目标或同组 writer
- Symbols: operationLogDataSource、operationLogSqlSessionFactory、operationLogTransactionManager、SOURCE_LOCAL Destination
- Repository evidence: EgonColaShardingAutoConfiguration.bootstrapper 与 LogicalDataSourceFactory 回调。EgonColaMybatisPlusContractValidator 会遍历所有 Factory
- Dependencies and consumers: 源原 MP/Outbox 继续按原名称。目标单独 Mapper、profiles、fingerprint 与 Tx Manager。本文件只追加目标注册不再调整 Step 1 Advisor
- Why now: File 16 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: operationLogDataSource、operationLogSqlSessionFactory、operationLogTransactionManager、SOURCE_LOCAL Destination。装配隔离目标或同组 writer按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 复用 bootstrapper、role/route guard，具名目标独立 resolver 与等同 MP interceptors/meta/identifier。exact receipt 主读 allowlist，页面只副本。SOURCE_LOCAL 同工厂验证，禁用零加载。目标 Bean 只在被选择使用隔离目标的宿主，Producer-only Worker 只装配 Recorder
- Error and edge behavior: 禁止抢占原 MyBatis/Tx 默认候选、同事务双数据源及公用 SQL 整体放权。在 commit 返回前不得 ACK，组件 refused-start 与读取失败均可观测
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-UTIL-001、MC-JSON-001、MC-CONFIG-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，仅使用既有 JDK/Spring/Egon 能力，Jackson 协议与安全字段一致，保持实际各环境配置结构相同，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 5、Rule 6、Rule 7、Rule 9、Rule 10、Rule 11。复用 bootstrapper、role/route guard，具名目标独立 resolver 与等同 MP interceptors/meta/identifier。exact receipt 主读 allowlist，页面只副本。SOURCE_LOCAL 同工厂验证，禁用零加载。目标 Bean 只在被选择使用隔离目标的宿主，Producer-only Worker 只装配 Recorder。
- Required change: 用已有 logical factory hook 组合第二套配置而不复制基础设施。定向注册审计 Mapper 并验证 Bean 归属。
- Verification contribution: 本 Step 定向命令与 G02。证明 装配隔离目标或同组 writer：禁止抢占原 MyBatis/Tx 默认候选、同事务双数据源及公用 SQL 整体放权。在 commit 返回前不得 ACK，组件 refused-start 与读取失败均可观测
- After this file: 装配隔离目标或同组 writer已具备该职责，继续下一个 File 或本步的整体集成验证。

- Validation working directory: `/Users/mario/SelfProject/Stellar-Enclosure/Stellar-Enclosure-Components`
- Verification command: `./mvnw -B -ntp -Dmaven.repo.local="$EGON_AUDIT_PLAN_M2" -pl stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=OperationLogPersistenceContractTest,OperationLogCaptureTest,EgonColaDdlManifestFamilyTest test`。运行及证据顺序见 §8。
- Expected result: 命名测试通过。批准的 2×2 环境补 §8 G02 的明确非跳过证据，证明同组收件事务、DDL 与读侧
- Failure returns to: 主读/角色/实际解析失败回 Mapper/Factory，不扩大权限。单组版史失败回目标 manifest
- Completion criteria: 输出本 Step 源提交、JAR/SHA、manifest 源校验和与独立运行证据。不部署天枢
- Rollback: 未应用可 revert。已应用只暂停目标/前向追加，保留已应用 SQL、校验和与事实
- Commit paths: `stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/test/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogPersistenceContractTest.java`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/test/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogPostgresReadwriteIT.java`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/pom.xml`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/ddl/EgonColaDdlManifestBO.java`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/resources/db/egon-operation-log/V20261123_001__initialize_operation_log_slot_0.sql`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/resources/db/egon-operation-log/manifest-slot-0.json`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/resources/db/egon-operation-log/V20261123_001__initialize_operation_log_slot_1.sql`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/resources/db/egon-operation-log/manifest-slot-1.json`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogPO.java`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogReceiptPO.java`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogConverter.java`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogDAO.java`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogReceiptDAO.java`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/resources/mapper/operationlog/OperationLogMapper.xml`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/resources/mapper/operationlog/OperationLogReceiptMapper.xml`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/OperationLogRepository.java`、`stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter/src/main/java/top/egon/cola/component/common/mybatis/operationlog/EgonOperationLogAutoConfiguration.java`
- Commit: `feat(mp-ext): persist operation logs on sharded postgresql`

### Step 3 — 把 PAC 业务回执迁出并构建兼容回退制品

- Requirements: REQ-005、REQ-006、REQ-008
- Dependencies: 与 Steps 1–2 可并行。本 Step 完整验证后才可进入 Step 4 启用
- Baseline state: 原 ordinary log 与 PAC 回执共表。旧 validator 对历史条数、路径、指纹均做精确匹配
- Observable outcome: 混合旧记录仅迁 PAC，原操作重读字节一致。不会使用读取新表之前的未兼容原制品回退
- End state: 保留旧普通日志行为、旧 route 和旧表。产出带 PAC 新版史/新 CLI 的独立回退制品及 SHA
- Test-first gate: Required。先用现有 bootstrap/DDL 测试做 RED。原 CLI 选旧表或旧 manifest 不支持新增回执
- Manual Checks: MC-ARCH-001、MC-REUSE-001、MC-NAME-001、MC-VALID-001、MC-MODEL-001、MC-LOG-001、MC-BEAN-001、MC-UTIL-001、MC-JSON-001、MC-TIME-001、MC-CONFIG-001、MC-SCOPE-001、MC-TEST-001
- Literal Rules: Rule 1、Rule 2、Rule 4、Rule 5、Rule 6、Rule 7、Rule 10、Rule 11
- Ordered files:

#### File 1 — `MODIFY stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/bootstrap/DdcPermissionMetadataBootstrapTest.java`

- Purpose: 验证兼容回执重放
- Symbols: initialize、receipt、expectedReceipt 的重复命令/不同 digest
- Repository evidence: 现有 Bootstrap 现行幂等 fixture。§11.2.3
- Dependencies and consumers: 重放由旧 fixture 导出的原 bytes 和新表记录
- Why now: 首先冻结本步编译合同或行为 RED 证据。
- Contract/signature changes: initialize、receipt、expectedReceipt 的重复命令/不同 digest。验证兼容回执重放按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 当旧来源改用新 table 后保持相同输出、锁与事务。没有第一次回执的 expectedReceipt 仍冲突
- Error and edge behavior: RED 为仍访问旧表、新表空或不同时仍执行元数据。错误候选不会写半行
- Standards impact: MC-VALID-001、MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 6、Rule 10、Rule 11。当旧来源改用新 table 后保持相同输出、锁与事务。没有第一次回执的 expectedReceipt 仍冲突。
- Required change: 先写读兼容和失败边界的目标测试，普通旧日志丢弃继续可证。
- Verification contribution: DdcPermissionMetadataBootstrapTest 作为本步先行验证。本 Step 定向命令与 G03。证明 验证兼容回执重放：RED 为仍访问旧表、新表空或不同时仍执行元数据。错误候选不会写半行
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 2 — `MODIFY stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/migration/DdcTianshuDdlContractTest.java`

- Purpose: 冻结 PAC 增量和旧版史
- Symbols: manifest prefix、schema version、PAC-only fixture
- Repository evidence: 现有 DdcTianshuDdlContractTest 对已应用 SQL 的位置校验
- Dependencies and consumers: 交叉检查脚本目标、新旧版本试读和 manifest 行
- Why now: 在生产行为变化前准备和执行真实断言，可编译的声明不计实现。
- Contract/signature changes: manifest prefix、schema version、PAC-only fixture。冻结 PAC 增量和旧版史按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 从当前 20261119_001 后增加且只增加 20261123_001。旧 SHA 原样保留，捕获 ordinary 迁移漏网
- Error and edge behavior: RED 为缺新表或 prefix。记录原版制品在历史追加后拒启。预留 Step 8 独立历史
- Standards impact: MC-JSON-001、MC-CONFIG-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。Jackson 协议与安全字段一致，保持实际各环境配置结构相同，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 6、Rule 7、Rule 10、Rule 11。从当前 20261119_001 后增加且只增加 20261123_001。旧 SHA 原样保留，捕获 ordinary 迁移漏网。
- Required change: 证明新脚本只迁最小集合，回退需兼容新历史。
- Verification contribution: DdcTianshuDdlContractTest 作为本步先行验证。本 Step 定向命令与 G03。证明 冻结 PAC 增量和旧版史：RED 为缺新表或 prefix。记录原版制品在历史追加后拒启。预留 Step 8 独立历史
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 3 — `MODIFY stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/integration/DdcTianshuDdlIT.java`

- Purpose: 验证新旧版本的实际 PostgreSQL 内容
- Symbols: 迁移精确回执、rollback-on-bad-candidate、history prefix 检查
- Repository evidence: 现有同文件使用真实 PostgreSQL/Testcontainers 和原 validateTianshuSchema
- Dependencies and consumers: 将故障注入和原 CLI 重读直连业务主组 fixture。Step 7 使用完整兼容制品实际启动
- Why now: 在生产行为变化前准备和执行真实断言，可编译的声明不计实现。
- Contract/signature changes: 迁移精确回执、rollback-on-bad-candidate、history prefix 检查。验证新旧版本的实际 PostgreSQL 内容按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 混入普通行及 PAC 旧内容，从原发布 baseline 追加 V001。比对 tenant/key/saved bytes/UTC 与仅新表记录。保留已应用脚本
- Error and edge behavior: 异常行、中途失败、提交未知都必须阻断或重新对账。运行绕过/全跳过不能算 PASS
- Standards impact: MC-VALID-001、MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 6、Rule 10、Rule 11。混入普通行及 PAC 旧内容，从原发布 baseline 追加 V001。比对 tenant/key/saved bytes/UTC 与仅新表记录。保留已应用脚本。
- Required change: 用当前实际 runner 吞吐和角色执行证明只迁 PAC，并给回退制品的启动验收构建 fixture。
- Verification contribution: DdcTianshuDdlIT 作为本步先行验证。本 Step 定向命令与 G03。证明 验证新旧版本的实际 PostgreSQL 内容：异常行、中途失败、提交未知都必须阻断或重新对账。运行绕过/全跳过不能算 PASS
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 4 — `CREATE stellar-enclosure-tianshu-admin/src/main/resources/db/egon-mp/V20261123_001__tianshu_metadata_bootstrap_receipt.sql`

- Purpose: 创建并迁出 PAC 业务回执
- Symbols: ddc_metadata_bootstrap_receipt_t0、PK tenant_id/operation_id
- Repository evidence: DdcPermissionMetadataBootstrapRepository 的 pac-b0 前缀、原 UTC created_at 和原 JSON 字符串。当前 PostgreSQL MP baseline
- Dependencies and consumers: EgonColaPostgreDdlRunner 使用业务主库显式目标。新 CLI consume
- Why now: File 3 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: ddc_metadata_bootstrap_receipt_t0、PK tenant_id/operation_id。创建并迁出 PAC 业务回执按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 只校验/取有效 PAC，双向匹配 operation_type/public_id，规范 UUID 与 digest。recorded_at 按旧 UTC。原文本按原编码，无 JSON 重排
- Error and edge behavior: 历史异常阻断，普通记录零读取路径意图与零迁入。暂停旧 CLI 并锁/核验，失败整版本回滚或重新验证 commit
- Standards impact: MC-VALID-001、MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 6、Rule 10、Rule 11。只校验/取有效 PAC，双向匹配 operation_type/public_id，规范 UUID 与 digest。recorded_at 按旧 UTC。原文本按原编码，无 JSON 重排。
- Required change: 以业务主组一个版本原子创建新表和有效 PAC 回执，保留旧物理表和 fingerprint。
- Verification contribution: 本 Step 定向命令与 G03。证明 创建并迁出 PAC 业务回执：历史异常阻断，普通记录零读取路径意图与零迁入。暂停旧 CLI 并锁/核验，失败整版本回滚或重新验证 commit
- After this file: 创建并迁出 PAC 业务回执已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 5 — `MODIFY stellar-enclosure-tianshu-admin/src/main/resources/db/egon-mp/repository-manifest.json`

- Purpose: 追加第一个业务版史
- Symbols: V20261123_001 路径与 SHA-256
- Repository evidence: 当前最后 20261119_001，freshBaselineVersion 20261018_001
- Dependencies and consumers: 原受管安装构件、DdcDatabaseReadyValidator、Step 8 再追加下一版本
- Why now: File 4 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: V20261123_001 路径与 SHA-256。追加第一个业务版史按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 完全保留 manifest prefix 和 fresh baseline，只加 PAC。记录制品 checksum
- Error and edge behavior: 验证误改原字节时失败。旧制品历史上多一项会拒启，以本 Step 兼容制品回退
- Standards impact: MC-JSON-001、MC-CONFIG-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。Jackson 协议与安全字段一致，保持实际各环境配置结构相同，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 6、Rule 7、Rule 11。完全保留 manifest prefix 和 fresh baseline，只加 PAC。记录制品 checksum。
- Required change: 发布侧固定本 Step SQL 字节，单独报出可回退制品。
- Verification contribution: 本 Step 定向命令与 G03。证明 追加第一个业务版史：验证误改原字节时失败。旧制品历史上多一项会拒启，以本 Step 兼容制品回退
- After this file: 追加第一个业务版史已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 6 — `MODIFY stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/bootstrap/DdcPermissionMetadataBootstrapRepository.java`

- Purpose: 将 CLI 新写和重读切到专表
- Symbols: initialize、receipt、table 物理选择
- Repository evidence: 现有 initialize 以 -tenant advisory_xact_lock、SERIALIZABLE、read-for-update 核对 digest/expectedReceipt
- Dependencies and consumers: DdcPermissionMetadataBootstrapCli → 持久业务权限初始化。无审计库
- Why now: File 5 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: initialize、receipt、table 物理选择。将 CLI 新写和重读切到专表按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 原请求/响应字段和 ID 校验保留，改为 tenant_id+operation_id 查专表，记录原 commandDigest/正文/UTC。首次元数据和新凭证同连接事务
- Error and edge behavior: 历史不存在且有 expectedReceipt 仍冲突。迁出后绝不回读 ddc_operation_log。错误不暴露原内容
- Standards impact: MC-NAME-001、MC-VALID-001、MC-BEAN-001、MC-UTIL-001、MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，仅使用既有 JDK/Spring/Egon 能力，Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 2、Rule 4、Rule 5、Rule 6、Rule 10、Rule 11。原请求/响应字段和 ID 校验保留，改为 tenant_id+operation_id 查专表，记录原 commandDigest/正文/UTC。首次元数据和新凭证同连接事务。
- Required change: 保持 PAC 公共回执与并发兼容，解除回执对普通日志表的持久依赖。
- Verification contribution: 本 Step 定向命令与 G03。证明 将 CLI 新写和重读切到专表：历史不存在且有 expectedReceipt 仍冲突。迁出后绝不回读 ddc_operation_log。错误不暴露原内容
- After this file: 将 CLI 新写和重读切到专表已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 7 — `MODIFY stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/config/DdcDatabaseReadyValidator.java`

- Purpose: 使回退制品兼容追加后的业务版史
- Symbols: validateTianshuSchema、validateHistory 与 PAC 表 metadata
- Repository evidence: 当前 validator 精确校验每条 history、旧表和旧约束
- Dependencies and consumers: Admin 与 Publisher/Observer 持久化进程复用，Step 8 再改变旧表依赖
- Why now: File 6 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: validateTianshuSchema、validateHistory 与 PAC 表 metadata。使回退制品兼容追加后的业务版史按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 新增回执 schema/PK 验证并使用新 manifest，业务合法 fingerprint 保持。回退用该 Step 编译制品，普通旧行为继续只作回退候选
- Error and edge behavior: 旧已装版史与未迁表不能启动新制品。旧原制品在多出版本时不可用。安全起见不宽化历史比较
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-CONFIG-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，保持实际各环境配置结构相同，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 6、Rule 7、Rule 11。新增回执 schema/PK 验证并使用新 manifest，业务合法 fingerprint 保持。回退用该 Step 编译制品，普通旧行为继续只作回退候选。
- Required change: 验证迁出后继续使用原普通日志链路的兼容制品用于回退。
- Verification contribution: 本 Step 定向命令与 G03。证明 使回退制品兼容追加后的业务版史：旧已装版史与未迁表不能启动新制品。旧原制品在多出版本时不可用。安全起见不宽化历史比较
- After this file: 使回退制品兼容追加后的业务版史已具备该职责，继续下一个 File 或本步的整体集成验证。

- Validation working directory: `/Users/mario/SelfProject/Stellar-Enclosure/tianshu-service`
- Verification command: `./mvnw -B -ntp -Dmaven.repo.local="$EGON_AUDIT_PLAN_M2" -pl stellar-enclosure-tianshu-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DdcPermissionMetadataBootstrapTest,DdcTianshuDdlContractTest test`。运行及证据顺序见 §8。
- Expected result: 命名用例通过。§8 G03 真 PG 得到混合样本仅 PAC 精确对账/失配回滚，且 backout artifact 试启动
- Failure returns to: 历史前缀或内容异常停止迁移和发布，回脚本/CLI 落点。禁止重置历史
- Completion criteria: 回执 CLI 重试、业务读写和实际启动证据独立。封存可识别新 PAC 史的 Admin/Worker 兼容制品
- Rollback: PAC migration 前可源回退。其后同一数据库只允许支持 V001 的兼容制品或前向修复
- Commit paths: `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/bootstrap/DdcPermissionMetadataBootstrapTest.java`、`stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/migration/DdcTianshuDdlContractTest.java`、`stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/integration/DdcTianshuDdlIT.java`、`stellar-enclosure-tianshu-admin/src/main/resources/db/egon-mp/V20261123_001__tianshu_metadata_bootstrap_receipt.sql`、`stellar-enclosure-tianshu-admin/src/main/resources/db/egon-mp/repository-manifest.json`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/bootstrap/DdcPermissionMetadataBootstrapRepository.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/config/DdcDatabaseReadyValidator.java`
- Commit: `feat(tianshu): separate permission bootstrap receipts`

### Step 4 — 切换业务操作采集和已有 Outbox 投递

- Requirements: REQ-001、REQ-002、REQ-003、REQ-004、REQ-005、REQ-008
- Dependencies: Steps 2–3。目标路径和 source 版本清单、Phase 3 兼容制品已锁定
- Baseline state: 旧6个服务写普通操作日志。Admin registry 与 native Publisher 路径各自受控
- Observable outcome: 符合动作范围的每个成功操作只经新采集入队一次。独立 Publisher 最终状态确实能产生事件
- End state: 唯一新写者，旧 YAML route 过渡保留。业务接口与部署默认保持预期，目标先准备后开新采集
- Test-first gate: Required。现有业务和 Worker fixtures 先转可观测的审计 contract，缺事务/仍写旧日志/缺 channel 为 RED
- Manual Checks: MC-ARCH-001、MC-REUSE-001、MC-NAME-001、MC-VALID-001、MC-MODEL-001、MC-LOG-001、MC-BEAN-001、MC-UTIL-001、MC-JSON-001、MC-TIME-001、MC-CONFIG-001、MC-PATTERN-001、MC-SCOPE-001、MC-TEST-001
- Literal Rules: Rule 1、Rule 2、Rule 3、Rule 4、Rule 5、Rule 6、Rule 7、Rule 9、Rule 10、Rule 11
- Ordered files:

#### File 1 — `MODIFY stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/config/DdcConfigServiceTest.java`

- Purpose: 先用现有测试锁定切换路径
- Symbols: create/upsert/update/delete/rollback 与回滚动作
- Repository evidence: 当前 DdcConfigServiceTest 测试和 Spec §7/§14
- Dependencies and consumers: 走原 Controller/Service/Factory 入口与现有 fixture，使用下一组实现的 Recorder/adapter
- Why now: 首先冻结本步编译合同或行为 RED 证据。
- Contract/signature changes: create/upsert/update/delete/rollback 与回滚动作。先用现有测试锁定切换路径按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点
- Error and edge behavior: 一个成功事实与真实方法/HTTP。受控失败不能残留成功或旧仓写入。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 6、Rule 9、Rule 10、Rule 11。用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点。
- Required change: 把当前回归用例扩为新入口与旧写切断的行为门。
- Verification contribution: DdcConfigServiceTest 作为本步先行验证。本 Step 定向命令与 G04。证明 先用现有测试锁定切换路径：一个成功事实与真实方法/HTTP。受控失败不能残留成功或旧仓写入。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 2 — `MODIFY stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/publish/DdcPublishPreparationTest.java`

- Purpose: 先用现有测试锁定切换路径
- Symbols: publish/prepare 及重放与源 enqueue
- Repository evidence: 当前 DdcPublishPreparationTest 测试和 Spec §7/§14
- Dependencies and consumers: 走原 Controller/Service/Factory 入口与现有 fixture，使用下一组实现的 Recorder/adapter
- Why now: 在生产行为变化前准备和执行真实断言，可编译的声明不计实现。
- Contract/signature changes: publish/prepare 及重放与源 enqueue。先用现有测试锁定切换路径按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点
- Error and edge behavior: 首次 COMMAND ACCEPTED。已有 changeId 原追溯，无新 ID，源提交失败回读仍生效。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 6、Rule 9、Rule 10、Rule 11。用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点。
- Required change: 把当前回归用例扩为新入口与旧写切断的行为门。
- Verification contribution: DdcPublishPreparationTest 作为本步先行验证。本 Step 定向命令与 G04。证明 先用现有测试锁定切换路径：首次 COMMAND ACCEPTED。已有 changeId 原追溯，无新 ID，源提交失败回读仍生效。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 3 — `MODIFY stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/persistence/DdcConfigDeliveryPersistenceIT.java`

- Purpose: 先用现有测试锁定切换路径
- Symbols: publish 真实事务失败夹具
- Repository evidence: 当前 DdcConfigDeliveryPersistenceIT 测试和 Spec §7/§14
- Dependencies and consumers: 走原 Controller/Service/Factory 入口与现有 fixture，使用下一组实现的 Recorder/adapter
- Why now: 在生产行为变化前准备和执行真实断言，可编译的声明不计实现。
- Contract/signature changes: publish 真实事务失败夹具。先用现有测试锁定切换路径按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点
- Error and edge behavior: 与新依赖签名一致，平台事务回滚时业务和审计意图都不提交。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 6、Rule 9、Rule 10、Rule 11。用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点。
- Required change: 把当前回归用例扩为新入口与旧写切断的行为门。
- Verification contribution: DdcConfigDeliveryPersistenceIT 作为本步先行验证。本 Step 定向命令与 G04。证明 先用现有测试锁定切换路径：与新依赖签名一致，平台事务回滚时业务和审计意图都不提交。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 4 — `MODIFY stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/publish/DdcNativeConfigRolloutServiceTest.java`

- Purpose: 先用现有测试锁定切换路径
- Symbols: finish/版本 CAS/阶段结果
- Repository evidence: 当前 DdcNativeConfigRolloutServiceTest 测试和 Spec §7/§14
- Dependencies and consumers: 走原 Controller/Service/Factory 入口与现有 fixture，使用下一组实现的 Recorder/adapter
- Why now: 在生产行为变化前准备和执行真实断言，可编译的声明不计实现。
- Contract/signature changes: finish/版本 CAS/阶段结果。先用现有测试锁定切换路径按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点
- Error and edge behavior: 同 attempt 同阶段重投零新增，原状态映射正确，CAS=0 零记录。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 6、Rule 9、Rule 10、Rule 11。用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点。
- Required change: 把当前回归用例扩为新入口与旧写切断的行为门。
- Verification contribution: DdcNativeConfigRolloutServiceTest 作为本步先行验证。本 Step 定向命令与 G04。证明 先用现有测试锁定切换路径：同 attempt 同阶段重投零新增，原状态映射正确，CAS=0 零记录。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 5 — `MODIFY stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/i18n/DdcI18nPageServiceTest.java`

- Purpose: 先用现有测试锁定切换路径
- Symbols: create、replaceDraft、publish 的原 audit
- Repository evidence: 当前 DdcI18nPageServiceTest 测试和 Spec §7/§14
- Dependencies and consumers: 走原 Controller/Service/Factory 入口与现有 fixture，使用下一组实现的 Recorder/adapter
- Why now: 在生产行为变化前准备和执行真实断言，可编译的声明不计实现。
- Contract/signature changes: create、replaceDraft、publish 的原 audit。先用现有测试锁定切换路径按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点
- Error and edge behavior: 提交后才出现，只有原作用范围，禁止翻译正文。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 6、Rule 9、Rule 10、Rule 11。用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点。
- Required change: 把当前回归用例扩为新入口与旧写切断的行为门。
- Verification contribution: DdcI18nPageServiceTest 作为本步先行验证。本 Step 定向命令与 G04。证明 先用现有测试锁定切换路径：提交后才出现，只有原作用范围，禁止翻译正文。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 6 — `MODIFY stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/i18n/DdcI18nSourceImportServiceTest.java`

- Purpose: 先用现有测试锁定切换路径
- Symbols: importSource 的 trusted producer 和重复回执
- Repository evidence: 当前 DdcI18nSourceImportServiceTest 测试和 Spec §7/§14
- Dependencies and consumers: 走原 Controller/Service/Factory 入口与现有 fixture，使用下一组实现的 Recorder/adapter
- Why now: 在生产行为变化前准备和执行真实断言，可编译的声明不计实现。
- Contract/signature changes: importSource 的 trusted producer 和重复回执。先用现有测试锁定切换路径按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点
- Error and edge behavior: 最小字段含 importer 加工结果，源原文及密钥绝不出现。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 6、Rule 9、Rule 10、Rule 11。用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点。
- Required change: 把当前回归用例扩为新入口与旧写切断的行为门。
- Verification contribution: DdcI18nSourceImportServiceTest 作为本步先行验证。本 Step 定向命令与 G04。证明 先用现有测试锁定切换路径：最小字段含 importer 加工结果，源原文及密钥绝不出现。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 7 — `MODIFY stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/list/DdcListTemplateServiceTest.java`

- Purpose: 先用现有测试锁定切换路径
- Symbols: 模板保存/切换/删除的原 audit
- Repository evidence: 当前 DdcListTemplateServiceTest 测试和 Spec §7/§14
- Dependencies and consumers: 走原 Controller/Service/Factory 入口与现有 fixture，使用下一组实现的 Recorder/adapter
- Why now: 在生产行为变化前准备和执行真实断言，可编译的声明不计实现。
- Contract/signature changes: 模板保存/切换/删除的原 audit。先用现有测试锁定切换路径按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点
- Error and edge behavior: 用实际模板 ID 和版本，失败无虚报，字段授权与业务语义保持。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 6、Rule 9、Rule 10、Rule 11。用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点。
- Required change: 把当前回归用例扩为新入口与旧写切断的行为门。
- Verification contribution: DdcListTemplateServiceTest 作为本步先行验证。本 Step 定向命令与 G04。证明 先用现有测试锁定切换路径：用实际模板 ID 和版本，失败无虚报，字段授权与业务语义保持。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 8 — `MODIFY stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/list/DdcNamespaceExportServiceTest.java`

- Purpose: 先用现有测试锁定切换路径
- Symbols: export 生成回调及二次权限检查
- Repository evidence: 当前 DdcNamespaceExportServiceTest 测试和 Spec §7/§14
- Dependencies and consumers: 走原 Controller/Service/Factory 入口与现有 fixture，使用下一组实现的 Recorder/adapter
- Why now: 在生产行为变化前准备和执行真实断言，可编译的声明不计实现。
- Contract/signature changes: export 生成回调及二次权限检查。先用现有测试锁定切换路径按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点
- Error and edge behavior: 仅成功生成、通过复核后发一个事实。正文与选择行数据零入队。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 6、Rule 9、Rule 10、Rule 11。用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点。
- Required change: 把当前回归用例扩为新入口与旧写切断的行为门。
- Verification contribution: DdcNamespaceExportServiceTest 作为本步先行验证。本 Step 定向命令与 G04。证明 先用现有测试锁定切换路径：仅成功生成、通过复核后发一个事实。正文与选择行数据零入队。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 9 — `MODIFY stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/integration/DdcConfigWorkerApplicationTest.java`

- Purpose: 先用现有测试锁定切换路径
- Symbols: 独立 Publisher/Observer 和手工导入集
- Repository evidence: 当前 DdcConfigWorkerApplicationTest 测试和 Spec §7/§14
- Dependencies and consumers: 走原 Controller/Service/Factory 入口与现有 fixture，使用下一组实现的 Recorder/adapter
- Why now: 在生产行为变化前准备和执行真实断言，可编译的声明不计实现。
- Contract/signature changes: 独立 Publisher/Observer 和手工导入集。先用现有测试锁定切换路径按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点
- Error and edge behavior: Publisher 出站审计仅使用源 MP manager，无审计库/HTTP。Observer 不新增消费者。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 6、Rule 9、Rule 10、Rule 11。用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点。
- Required change: 把当前回归用例扩为新入口与旧写切断的行为门。
- Verification contribution: DdcConfigWorkerApplicationTest 作为本步先行验证。本 Step 定向命令与 G04。证明 先用现有测试锁定切换路径：Publisher 出站审计仅使用源 MP manager，无审计库/HTTP。Observer 不新增消费者。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 10 — `MODIFY stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/config/DdcAdminOutboxDispatchConfigurationTest.java`

- Purpose: 先用现有测试锁定切换路径
- Symbols: adminDeliveryRegistry 的 channel 集
- Repository evidence: 当前 DdcAdminOutboxDispatchConfigurationTest 测试和 Spec §7/§14
- Dependencies and consumers: 走原 Controller/Service/Factory 入口与现有 fixture，使用下一组实现的 Recorder/adapter
- Why now: 在生产行为变化前准备和执行真实断言，可编译的声明不计实现。
- Contract/signature changes: adminDeliveryRegistry 的 channel 集。先用现有测试锁定切换路径按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点
- Error and edge behavior: Admin 能认领 audit schema 1。native rollout 仍被原 Admin 排除。旧 registry 不处理新 channel。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 6、Rule 9、Rule 10、Rule 11。用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点。
- Required change: 把当前回归用例扩为新入口与旧写切断的行为门。
- Verification contribution: DdcAdminOutboxDispatchConfigurationTest 作为本步先行验证。本 Step 定向命令与 G04。证明 先用现有测试锁定切换路径：Admin 能认领 audit schema 1。native rollout 仍被原 Admin 排除。旧 registry 不处理新 channel。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 11 — `MODIFY stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/contract/DdcProfileParityTest.java`

- Purpose: 先用现有测试锁定切换路径
- Symbols: 配置键对比
- Repository evidence: 当前 DdcProfileParityTest 测试和 Spec §7/§14
- Dependencies and consumers: 走原 Controller/Service/Factory 入口与现有 fixture，使用下一组实现的 Recorder/adapter
- Why now: 在生产行为变化前准备和执行真实断言，可编译的声明不计实现。
- Contract/signature changes: 配置键对比。先用现有测试锁定切换路径按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点
- Error and edge behavior: base 与 local/test/si-catalog 有相同的有效结构，默认关闭。分离采集与 handler 兼容控制。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 6、Rule 9、Rule 10、Rule 11。用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点。
- Required change: 把当前回归用例扩为新入口与旧写切断的行为门。
- Verification contribution: DdcProfileParityTest 作为本步先行验证。本 Step 定向命令与 G04。证明 先用现有测试锁定切换路径：base 与 local/test/si-catalog 有相同的有效结构，默认关闭。分离采集与 handler 兼容控制。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 12 — `MODIFY stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/migration/DdcLegacyImportContractTest.java`

- Purpose: 先用现有测试锁定切换路径
- Symbols: 旧普通日志导入检查
- Repository evidence: 当前 DdcLegacyImportContractTest 测试和 Spec §7/§14
- Dependencies and consumers: 走原 Controller/Service/Factory 入口与现有 fixture，使用下一组实现的 Recorder/adapter
- Why now: 在生产行为变化前准备和执行真实断言，可编译的声明不计实现。
- Contract/signature changes: 旧普通日志导入检查。先用现有测试锁定切换路径按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点
- Error and edge behavior: 新版本导入路径略过普通 ddc_operation_log，即便旧表过渡保留。保住其他既有 legacy 输入。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 6、Rule 9、Rule 10、Rule 11。用原 fixture 业务输入到原响应与新事件或拒绝。保留已有断言并替换旧 audit mock 观察点。
- Required change: 把当前回归用例扩为新入口与旧写切断的行为门。
- Verification contribution: DdcLegacyImportContractTest 作为本步先行验证。本 Step 定向命令与 G04。证明 先用现有测试锁定切换路径：新版本导入路径略过普通 ddc_operation_log，即便旧表过渡保留。保住其他既有 legacy 输入。预期 RED 为旧仓仍被调用、旧注册不识别新事件或无正确新事务。已有类型可先编译
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 13 — `CREATE stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/outbox/DdcOperationLogOutboxAdapterTest.java`

- Purpose: 锁定本地 Outbox 双事务边界
- Symbols: acceptOnlyAfterCommit、retryCommitUnknown、permanentEnvelopeConflict、restoreTenantOnFailure
- Repository evidence: DdcConfigDispatchDeliveryHandler、DdcAiPublicationOutboxTest 既有宿主测试
- Dependencies and consumers: 用 Spring context 观察策略和 Handler 的固定名称及隔离作用域，真实 PG 在 Step 7
- Why now: 在生产行为变化前准备和执行真实断言，可编译的声明不计实现。
- Contract/signature changes: acceptOnlyAfterCommit、retryCommitUnknown、permanentEnvelopeConflict、restoreTenantOnFailure。锁定本地 Outbox 双事务边界按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 已信 envelope → 单正 tenant → 目标事务 commit → DeliveryResult。非法 header/schema/重复变形不触库或不修改。重试保持事件 ID
- Error and edge behavior: RED 由新通道未注册/没有合规成功分类驱动。以注入的连接失败、timeout、甲乙 tenant 连续交付查上下文释放
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 6、Rule 9、Rule 10、Rule 11。已信 envelope → 单正 tenant → 目标事务 commit → DeliveryResult。非法 header/schema/重复变形不触库或不修改。重试保持事件 ID。
- Required change: 让适配器在接入各业务入口前先有成功、重试、永久失败的断言。
- Verification contribution: DdcOperationLogOutboxAdapterTest 作为本步先行验证。本 Step 定向命令与 G04。证明 锁定本地 Outbox 双事务边界：RED 由新通道未注册/没有合规成功分类驱动。以注入的连接失败、timeout、甲乙 tenant 连续交付查上下文释放
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 14 — `CREATE stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/outbox/DdcOperationLogOutboxAdapter.java`

- Purpose: 复用现有 Outbox 生产与消费接口
- Symbols: DdcOperationLogOutboxAdapter、固定 tianshu.operation-log/tianshu-audit、OutboxMessage、DeliveryResult
- Repository evidence: DdcOutboxConfiguration 的具名 source 事务和 `DdcConfigDispatchDeliveryHandler` 的可信 tenant 绑定。current Outbox typed API
- Dependencies and consumers: 同一文件分别装配生产 Strategy 和本地 DeliveryHandler，Publisher 只取生产路径。Admin 可加载消费路径与目标
- Why now: File 13 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: DdcOperationLogOutboxAdapter、固定 tianshu.operation-log/tianshu-audit、OutboxMessage、DeliveryResult。复用现有 Outbox 生产与消费接口按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 源注册 actor、schema、route、event 的可信元数据及稳定 transport key，在源 tx enqueue。消费 schema 1/正租户重验，由同一 tenant 闭包进入独立目标事务。成功只在提交或已存在同摘要。记录既有指标
- Error and edge behavior: commit 未知或临时故障保留原事件并 retryable。非法通道、内容不同则 permanent。无外部 HTTP、无新技术 Outbox/StateMachine、更不自动处理 DEAD
- Standards impact: MC-NAME-001、MC-VALID-001、MC-BEAN-001、MC-UTIL-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，仅使用既有 JDK/Spring/Egon 能力，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 2、Rule 4、Rule 5、Rule 6、Rule 9、Rule 10、Rule 11。源注册 actor、schema、route、event 的可信元数据及稳定 transport key，在源 tx enqueue。消费 schema 1/正租户重验，由同一 tenant 闭包进入独立目标事务。成功只在提交或已存在同摘要。记录既有指标。
- Required change: 将 Spec EVENT-001 变成宿主现有本地双事务协议，消费 Bean 与采集开关可分别按生命周期准备。
- Verification contribution: 本 Step 定向命令与 G04。证明 复用现有 Outbox 生产与消费接口：commit 未知或临时故障保留原事件并 retryable。非法通道、内容不同则 permanent。无外部 HTTP、无新技术 Outbox/StateMachine、更不自动处理 DEAD
- After this file: 复用现有 Outbox 生产与消费接口已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 15 — `CREATE stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/config/DdcOperationLogWebConfiguration.java`

- Purpose: 提供来自可信 Web/宿主的调用快照与声明
- Symbols: DdcOperationLogWebConfiguration、operationLogContextProvider、审计投影注册
- Repository evidence: DdcTenantContextExecutor、DdcAdminAuth* 已验证权限，Spring MVC HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE
- Dependencies and consumers: Admin 业务代理/显式 recorder → Tianshu Outbox Strategy。Publisher 不导入 MVC Bean
- Why now: File 14 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: DdcOperationLogWebConfiguration、operationLogContextProvider、审计投影注册。提供来自可信 Web/宿主的调用快照与声明按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 获取服务器认证 tenant/actor、验证 origin 和清洗 path、HTTP 动词与模板。真实目标签名继续从应用方法取。按动作投影安全 scope 和范围字段。匹配稳定 Bean 和源 manager
- Error and edge behavior: Host/Forwarded 不可信、token/query/fragment、非法路径退化模板或拒绝。权限前拒绝延用安全日志。后台无 Servlet 字段，不串 MDC
- Standards impact: MC-NAME-001、MC-VALID-001、MC-BEAN-001、MC-UTIL-001、MC-JSON-001、MC-CONFIG-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，仅使用既有 JDK/Spring/Egon 能力，Jackson 协议与安全字段一致，保持实际各环境配置结构相同，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 2、Rule 4、Rule 5、Rule 6、Rule 7、Rule 9、Rule 10、Rule 11。获取服务器认证 tenant/actor、验证 origin 和清洗 path、HTTP 动词与模板。真实目标签名继续从应用方法取。按动作投影安全 scope 和范围字段。匹配稳定 Bean 和源 manager。
- Required change: 提供同一个受控上下文与策略注册点，先有消费能力再允许生产。
- Verification contribution: 本 Step 定向命令与 G04。证明 提供来自可信 Web/宿主的调用快照与声明：Host/Forwarded 不可信、token/query/fragment、非法路径退化模板或拒绝。权限前拒绝延用安全日志。后台无 Servlet 字段，不串 MDC
- After this file: 提供来自可信 Web/宿主的调用快照与声明已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 16 — `MODIFY stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/config/DdcConfigService.java`

- Purpose: 替换现有配置写入调用
- Symbols: create、upsert、update、delete、rollback、saveOperation
- Repository evidence: 当前版本与日志同 transaction，存在包装重载和手写 DI 构造器
- Dependencies and consumers: 原 DdcConfigController/Repository/版本路径，改用 OperationLogRecorder
- Why now: File 15 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: create、upsert、update、delete、rollback、saveOperation。替换现有配置写入调用按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 原版本写成功后的资源/修订/状态进入动作投影，选真实外层入口或事务内部显式单次，避免包装入口重复写。时间和身份由 Recorder
- Error and edge behavior: 保留原业务响应、错误、路由和版本前置条件。原副作用回滚时零成功，禁用时不新写。不留旧仓并写
- Standards impact: MC-NAME-001、MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 2、Rule 4、Rule 6、Rule 9、Rule 10、Rule 11。原版本写成功后的资源/修订/状态进入动作投影，选真实外层入口或事务内部显式单次，避免包装入口重复写。时间和身份由 Recorder。
- Required change: 按原事务边界替换旧 saveOperation 调用点，具名 Lombok DI 并保持业务构造中必要的既有非 DI 初始化。
- Verification contribution: 本 Step 定向命令与 G04。证明 替换现有配置写入调用：保留原业务响应、错误、路由和版本前置条件。原副作用回滚时零成功，禁用时不新写。不留旧仓并写
- After this file: 替换现有配置写入调用已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 17 — `MODIFY stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/publish/DdcPublishService.java`

- Purpose: 在实际发布受理时入队
- Symbols: publish、prepare、retry 与原 changeId 核对
- Repository evidence: TransactionTemplate 内实际创建任务与 enqueueNative
- Dependencies and consumers: DdcConfigController → 原任务/版本/配置事件 → 新审计 producer
- Why now: File 16 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: publish、prepare、retry 与原 changeId 核对。在实际发布受理时入队按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 任务首次改变才追加 COMMAND/ACCEPTED，按现有 changeId 和 attempt 形成根关联及结构化 scope。保留业务事件
- Error and edge behavior: 提交未知按原 changeId 回查，原 idempotency 不漏 ACK、不重写。不得把受理当执行成功
- Standards impact: MC-NAME-001、MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 2、Rule 4、Rule 6、Rule 9、Rule 10、Rule 11。任务首次改变才追加 COMMAND/ACCEPTED，按现有 changeId 和 attempt 形成根关联及结构化 scope。保留业务事件。
- Required change: 在最接近已确认任务写入的事务代码中替换旧审计并保留原发布事务合同。
- Verification contribution: 本 Step 定向命令与 G04。证明 在实际发布受理时入队：提交未知按原 changeId 回查，原 idempotency 不漏 ACK、不重写。不得把受理当执行成功
- After this file: 在实际发布受理时入队已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 18 — `MODIFY stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/publish/DdcNativeConfigRolloutService.java`

- Purpose: 记录真正提交的执行阶段
- Symbols: finish、updateNativeEvidence、PUBLISHING、PLATFORM_APPLIED、SUPERSEDED 等
- Repository evidence: 原 row lock、wakeup 匹配、CAS 和专用 PublisherApplication
- Dependencies and consumers: 沿用作业 tenant 执行器，在真实配置 Publisher 的同一源事务生产 EVENT-001
- Why now: File 17 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: finish、updateNativeEvidence、PUBLISHING、PLATFORM_APPLIED、SUPERSEDED 等。记录真正提交的执行阶段按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 前置当前尝试与版本校验，CAS 改一行且有新确认阶段才追加 EXECUTION，按 Spec §10.6 状态映射。与 COMMAND 共根，事实 ID 独立，后台实际方法
- Error and edge behavior: PENDING/PUBLISHING、重复回调与 CAS=0 无执行事实。PLATFORM_APPLIED 不代表端侧完成。原 wakeup 错配返回和超时行为保留
- Standards impact: MC-NAME-001、MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 2、Rule 4、Rule 6、Rule 9、Rule 10、Rule 11。前置当前尝试与版本校验，CAS 改一行且有新确认阶段才追加 EXECUTION，按 Spec §10.6 状态映射。与 COMMAND 共根，事实 ID 独立，后台实际方法。
- Required change: 在事务确认点提供可追踪的新阶段事实并用实际 worker 组装回归。
- Verification contribution: 本 Step 定向命令与 G04。证明 记录真正提交的执行阶段：PENDING/PUBLISHING、重复回调与 CAS=0 无执行事实。PLATFORM_APPLIED 不代表端侧完成。原 wakeup 错配返回和超时行为保留
- After this file: 记录真正提交的执行阶段已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 19 — `MODIFY stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/config/DdcConfigWorkerApplicationConfiguration.java`

- Purpose: 装配已有非 Web 发布 Worker 的生产边界
- Symbols: PublisherApplication#rollouts、PersistenceApplication 白名单
- Repository evidence: PublisherApplication 手工创建 DdcNativeConfigRolloutService，Observer 独立，Jackson alias、Clock 已固定
- Dependencies and consumers: 只有 Publisher 得到 producer/Recorder。Admin 本地接收。Observer 继续既有配置
- Why now: File 18 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: PublisherApplication#rollouts、PersistenceApplication 白名单。装配已有非 Web 发布 Worker 的生产边界按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 保留原 source TransactionTemplate、租户注册和回调范围。导入最窄的新 producer 依赖，不装目标库、审计消费或 Web 动态对象
- Error and edge behavior: 没有 Advisor/Recorder 不应默默吞 terminal。当采集关，生产入口稳定 no-op，不影响原业务任务
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-CONFIG-001、MC-PATTERN-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，保持实际各环境配置结构相同，按已选 Strategy/Adapter/Projection 与现有职责划分，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 7、Rule 9、Rule 11。保留原 source TransactionTemplate、租户注册和回调范围。导入最窄的新 producer 依赖，不装目标库、审计消费或 Web 动态对象。
- Required change: 补上实际独立进程构造链，核验旧与新 channel 的 claimant 没有冲突。
- Verification contribution: 本 Step 定向命令与 G04。证明 装配已有非 Web 发布 Worker 的生产边界：没有 Advisor/Recorder 不应默默吞 terminal。当采集关，生产入口稳定 no-op，不影响原业务任务
- After this file: 装配已有非 Web 发布 Worker 的生产边界已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 20 — `MODIFY stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/i18n/DdcI18nPageService.java`

- Purpose: 替换页面语言写操作
- Symbols: create、replaceDraft、publish 触达原 audit helper
- Repository evidence: 已有 source scope、回执和发布命令的事务边界
- Dependencies and consumers: 现有 i18n 入口、权限作为事实来源 → Recorder
- Why now: File 19 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: create、replaceDraft、publish 触达原 audit helper。替换页面语言写操作按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 只投影已验证 page/code/revision 与结果，已完成操作的重查依原回执，不采翻译正文
- Error and edge behavior: 信任域与发布可见性保持，rollback 零成功
- Standards impact: MC-NAME-001、MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 2、Rule 4、Rule 6、Rule 9、Rule 10、Rule 11。只投影已验证 page/code/revision 与结果，已完成操作的重查依原回执，不采翻译正文。
- Required change: 移除对旧审计仓的运行调用，将实际边界换成一条统一记录。
- Verification contribution: 本 Step 定向命令与 G04。证明 替换页面语言写操作：信任域与发布可见性保持，rollback 零成功
- After this file: 替换页面语言写操作已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 21 — `MODIFY stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/i18n/DdcI18nSourceImportService.java`

- Purpose: 替换可信来源导入审计
- Symbols: importSource、事务内日志追加、receipt
- Repository evidence: 已有 DdcSourceImportIdentityBO 与来源 grant 事务
- Dependencies and consumers: 已验 producer、scope 与 source 操作回执 → Recorder
- Why now: File 20 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: importSource、事务内日志追加、receipt。替换可信来源导入审计按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: actor 是可信 producer，使用审计后的 import id、数目与状态。重放原回执无新记录
- Error and edge behavior: 保留签名/租户约束，不接 token、原 bundle 与外部 requestId 伪值
- Standards impact: MC-NAME-001、MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 2、Rule 4、Rule 6、Rule 9、Rule 10、Rule 11。actor 是可信 producer，使用审计后的 import id、数目与状态。重放原回执无新记录。
- Required change: 接入可信非用户入口的 `SERVICE` 动作，目标事件仍经过 schema 验证。
- Verification contribution: 本 Step 定向命令与 G04。证明 替换可信来源导入审计：保留签名/租户约束，不接 token、原 bundle 与外部 requestId 伪值
- After this file: 替换可信来源导入审计已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 22 — `MODIFY stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/list/DdcListTemplateService.java`

- Purpose: 替换模板命令审计
- Symbols: 原 audit helper、模板可写操作
- Repository evidence: 已验 SystemAuthorizationSnapshot 和模板 CAS
- Dependencies and consumers: 现有模板 Repository/Mapper → Recorder，与原业务 local tx 相同
- Why now: File 21 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: 原 audit helper、模板可写操作。替换模板命令审计按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 记录授权范围、模板公开键和原操作类别。load/preview 等纯查无审计
- Error and edge behavior: 竞争失败与重复保存无伪造事实。保留原字段及权限校验
- Standards impact: MC-NAME-001、MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 2、Rule 4、Rule 6、Rule 9、Rule 10、Rule 11。记录授权范围、模板公开键和原操作类别。load/preview 等纯查无审计。
- Required change: 围绕原权限复核和模板真实修改改写 audit helper。
- Verification contribution: 本 Step 定向命令与 G04。证明 替换模板命令审计：竞争失败与重复保存无伪造事实。保留原字段及权限校验
- After this file: 替换模板命令审计已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 23 — `MODIFY stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/list/DdcNamespaceExportService.java`

- Purpose: 在文件生成回调记录事实
- Symbols: export、audit、XlsxExport 回调
- Repository evidence: 当前 export 不是长写事务，已有两次权限快照和生成成功 callback
- Dependencies and consumers: XlsxExport 生成结束→原 callback→既有源 TransactionTemplate 的短写，追加 count/range/template ID
- Why now: File 22 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: export、audit、XlsxExport 回调。在文件生成回调记录事实按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 完成回调中创建事实。原查询和 XLSX 构造不纳入写事务。仍走可信 actor 和真实 export 方法签名
- Error and edge behavior: 失败不进入成功回调，强审计 enqueue 失败保留原错误。无正文和浏览器下载完成声明
- Standards impact: MC-NAME-001、MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 2、Rule 4、Rule 6、Rule 9、Rule 10、Rule 11。完成回调中创建事实。原查询和 XLSX 构造不纳入写事务。仍走可信 actor 和真实 export 方法签名。
- Required change: 把原生成成功 audit 移到短源事务，控制事务长度及事实含义。
- Verification contribution: 本 Step 定向命令与 G04。证明 在文件生成回调记录事实：失败不进入成功回调，强审计 enqueue 失败保留原错误。无正文和浏览器下载完成声明
- After this file: 在文件生成回调记录事实已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 24 — `MODIFY stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/migration/DdcLegacyTableStrategy.java`

- Purpose: 停止 legacy 导入普通历史
- Symbols: ddc_operation_log 注册项与单表策略分派
- Repository evidence: 现有 ordered 老表迁移包含 ddc_operation_log
- Dependencies and consumers: legacy import 服务/CLI，旧物理表仍用于兼容制品
- Why now: File 23 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: ddc_operation_log 注册项与单表策略分派。停止 legacy 导入普通历史按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 新版导入明确不消费普通旧日志。PAC 由 Step 3 独占，其他迁移链顺序保持
- Error and edge behavior: 拒绝显式请求旧操作历史导入，保留原 legacy 检查的已登记其他表。不提前删业务路由
- Standards impact: MC-VALID-001、MC-JSON-001、MC-CONFIG-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Jackson 协议与安全字段一致，保持实际各环境配置结构相同，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 6、Rule 7、Rule 11。新版导入明确不消费普通旧日志。PAC 由 Step 3 独占，其他迁移链顺序保持。
- Required change: 切新时阻止维护路径把普通历史带回，新旧制品区分由 release gate 负责。
- Verification contribution: 本 Step 定向命令与 G04。证明 停止 legacy 导入普通历史：拒绝显式请求旧操作历史导入，保留原 legacy 检查的已登记其他表。不提前删业务路由
- After this file: 停止 legacy 导入普通历史已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 25 — `MODIFY stellar-enclosure-tianshu-admin/src/main/resources/egon-tianshu-persistence.yml`

- Purpose: 在现有共享配置放新能力的默认设置
- Symbols: egon.cola.component.mybatis-plus.operation-log.enabled、write-mode、隔离目标及读取/留存预算键
- Repository evidence: 原共享持久化 YAML 提供业务 MP 与 Outbox，application/本地/测试/si-catalog 按实际 import 复用
- Dependencies and consumers: Admin 选 OUTBOX，独立 worker 用 source-only 注入。发布环境填验证目标和有界配置
- Why now: File 24 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: egon.cola.component.mybatis-plus.operation-log.enabled、write-mode、隔离目标及读取/留存预算键。在现有共享配置放新能力的默认设置按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 保留以同一 schema 验证四配置族，默认采集关闭。已有 source 角色、Outbox manager 和业务 route 继续现状。目标连接认证从发布系统注入
- Error and edge behavior: 缺映射、版本、角色、连接池/窗口预算、错误对多策略使启用失败。退役之前维持旧表 route
- Standards impact: MC-VALID-001、MC-CONFIG-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，保持实际各环境配置结构相同，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 7、Rule 10、Rule 11。保留以同一 schema 验证四配置族，默认采集关闭。已有 source 角色、Outbox manager 和业务 route 继续现状。目标连接认证从发布系统注入。
- Required change: 增加唯一共享配置入口和 parity 验证，使新旧切换独立于当前源码默认。
- Verification contribution: 本 Step 定向命令与 G04。证明 在现有共享配置放新能力的默认设置：缺映射、版本、角色、连接池/窗口预算、错误对多策略使启用失败。退役之前维持旧表 route
- After this file: 在现有共享配置放新能力的默认设置已具备该职责，继续下一个 File 或本步的整体集成验证。

- Validation working directory: `/Users/mario/SelfProject/Stellar-Enclosure/tianshu-service`
- Verification command: `./mvnw -B -ntp -Dmaven.repo.local="$EGON_AUDIT_PLAN_M2" -pl stellar-enclosure-tianshu-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DdcOperationLogOutboxAdapterTest,DdcConfigServiceTest,DdcPublishPreparationTest,DdcNativeConfigRolloutServiceTest,DdcConfigWorkerApplicationTest,DdcI18nPageServiceTest,DdcI18nSourceImportServiceTest,DdcListTemplateServiceTest,DdcNamespaceExportServiceTest,DdcAdminOutboxDispatchConfigurationTest,DdcProfileParityTest,DdcLegacyImportContractTest test`。运行及证据顺序见 §8。
- Expected result: 所有命名用例通过。既有物理源 rollback、原发布状态与审计重复矩阵同 §8
- Failure returns to: 真实 endpoint 编译/fixture 失败回对应 File，channel 或未明来源失败回 host adapter/worker 装配
- Completion criteria: 日志和业务 payload 双边版本可对照、目标不可用源面不等待、禁用零审计 SQL 或 enqueue
- Rollback: 生产前源码可 revert。启用后先暂停生产并持久化保留队列，之后用 Step 3 制品回退，细则 §9
- Commit paths: `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/config/DdcConfigServiceTest.java`、`stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/publish/DdcPublishPreparationTest.java`、`stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/persistence/DdcConfigDeliveryPersistenceIT.java`、`stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/publish/DdcNativeConfigRolloutServiceTest.java`、`stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/i18n/DdcI18nPageServiceTest.java`、`stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/i18n/DdcI18nSourceImportServiceTest.java`、`stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/list/DdcListTemplateServiceTest.java`、`stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/list/DdcNamespaceExportServiceTest.java`、`stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/integration/DdcConfigWorkerApplicationTest.java`、`stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/config/DdcAdminOutboxDispatchConfigurationTest.java`、`stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/contract/DdcProfileParityTest.java`、`stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/migration/DdcLegacyImportContractTest.java`、`stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/outbox/DdcOperationLogOutboxAdapterTest.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/outbox/DdcOperationLogOutboxAdapter.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/config/DdcOperationLogWebConfiguration.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/config/DdcConfigService.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/publish/DdcPublishService.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/publish/DdcNativeConfigRolloutService.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/config/DdcConfigWorkerApplicationConfiguration.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/i18n/DdcI18nPageService.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/i18n/DdcI18nSourceImportService.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/list/DdcListTemplateService.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/list/DdcNamespaceExportService.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/migration/DdcLegacyTableStrategy.java`、`stellar-enclosure-tianshu-admin/src/main/resources/egon-tianshu-persistence.yml`
- Commit: `feat(tianshu): capture operation logs through existing outbox`

### Step 5 — 发布受权限保护的天枢审计查询合同

- Requirements: REQ-002、REQ-004、REQ-007
- Dependencies: Steps 2、4。受限查询 DAO、目标工厂及现有 Admin security 在位
- Baseline state: 原安全链拒绝未知路由，缺审计 HTTP/权限/语言页
- Observable outcome: 实际两个 GET 受租户和审计权限约束，并输出文档合同
- End state: 后端生成 Springdoc 和 PAC Java manifest。保留 API 测试与可锁定的 RC 阶段证据给 Web
- Test-first gate: Required。WebMvc 在最小已可编译 DTO 后对真实安全配置 RED。缺审计 route/结果排序/二级权限可观察
- Manual Checks: MC-ARCH-001、MC-REUSE-001、MC-NAME-001、MC-VALID-001、MC-MODEL-001、MC-CONVERT-001、MC-LOG-001、MC-BEAN-001、MC-UTIL-001、MC-JSON-001、MC-TIME-001、MC-PATTERN-001、MC-SCOPE-001、MC-TEST-001
- Literal Rules: Rule 1、Rule 2、Rule 3、Rule 4、Rule 5、Rule 6、Rule 9、Rule 10、Rule 11
- Ordered files:

#### File 1 — `CREATE stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/web/DdcOperationLogWebContractTest.java`

- Purpose: 固定有真实安全链的 API RED
- Symbols: supportsPageAndDetail、forbidsGeneralRead、deniesCrossTenant、openApiAndNoStore
- Repository evidence: DdcAdminSecurityIntegrationTest 和 DdcAdminWebResourceTest WebMvc/真实路由 patterns
- Dependencies and consumers: 观察 release 模式下两条 GET、resource manifest、Repo fake/真实 DTO 实际序列化
- Why now: 首先冻结本步编译合同或行为 RED 证据。
- Contract/signature changes: supportsPageAndDetail、forbidsGeneralRead、deniesCrossTenant、openApiAndNoStore。固定有真实安全链的 API RED按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 权限矩阵区分现有 OAuth scope、Audit READ、DETAIL、错租户。400/404/500/503 两种 envelope，ID/微秒/字段白名单，分页排序
- Error and edge behavior: 基线 URL 未放行或无路由。落实后普通配置 READ/未知参数/错误 route 必须仍拒绝，拒绝时 DAO 零访问
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 6、Rule 10、Rule 11。权限矩阵区分现有 OAuth scope、Audit READ、DETAIL、错租户。400/404/500/503 两种 envelope，ID/微秒/字段白名单，分页排序。
- Required change: 使用同一 Admin security 生产配置证明 REQ-007，产出实际 springdoc 契约。
- Verification contribution: DdcOperationLogWebContractTest 作为本步先行验证。本 Step 定向命令与 G05。证明 固定有真实安全链的 API RED：基线 URL 未放行或无路由。落实后普通配置 READ/未知参数/错误 route 必须仍拒绝，拒绝时 DAO 零访问
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 2 — `CREATE stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/audit/DdcOperationLogQueryServiceTest.java`

- Purpose: 固定只读有界分页行为
- Symbols: equalTimeCrossBuckets、sameAnchor、failedBucketNoResult、detailExactInstant
- Repository evidence: 现有 DdcConfigServiceTest 快照检查与组件 Spec §11.2.1
- Dependencies and consumers: 使用已有 OperationLogRepository 门面和新 VO/Query 编译前置类型。不先写页归并实现
- Why now: 在生产行为变化前准备和执行真实断言，可编译的声明不计实现。
- Contract/signature changes: equalTimeCrossBuckets、sameAnchor、failedBucketNoResult、detailExactInstant。固定只读有界分页行为按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 时间/ID 边界、两个桶相同时间、空页/有下一页、租户切换、数据源不可用映射。观察 Repo 参数及 SQL 合同，不访问业务仓
- Error and edge behavior: RED 由尚未实现的合并/定位/错误合同驱动，不以未编译冒充
- Standards impact: MC-VALID-001、MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 6、Rule 10、Rule 11。时间/ID 边界、两个桶相同时间、空页/有下一页、租户切换、数据源不可用映射。观察 Repo 参数及 SQL 合同，不访问业务仓。
- Required change: 把查询顺序、授权后的载荷和错误固定为独立数据契约。
- Verification contribution: DdcOperationLogQueryServiceTest 作为本步先行验证。本 Step 定向命令与 G05。证明 固定只读有界分页行为：RED 由尚未实现的合并/定位/错误合同驱动，不以未编译冒充
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 3 — `CREATE stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/query/DdcOperationLogPageQuery.java`

- Purpose: 承载查询输入与归一化
- Symbols: DdcOperationLogPageQuery、from/to、beforeTime/beforeId、limit 与 §9 筛选
- Repository evidence: DdcConfigPreviewQuery 与现有 Jakarta 校验
- Dependencies and consumers: Controller → QueryService → OperationLogRepository。tenant 来已认上下文并作为受检 bound 值
- Why now: File 2 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: DdcOperationLogPageQuery、from/to、beforeTime/beforeId、limit 与 §9 筛选。承载查询输入与归一化按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: class Lombok @Builder。成对及最大 31×24h。offset/Z 转微秒 Instant，limit 50/100，正 bigint decimal 游标和登记字段
- Error and edge behavior: 未知/重复、不合法时间、control 字符、溢出、错组合 400。Query 无 tenant、HTTP 原字符串不混入 PO
- Standards impact: MC-NAME-001、MC-VALID-001、MC-MODEL-001、MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，使用 Jakarta 约束、边界组和明确负例，POJO Lombok、不可变值对象及 Egon 转换约束，Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 2、Rule 3、Rule 6、Rule 10、Rule 11。class Lombok @Builder。成对及最大 31×24h。offset/Z 转微秒 Instant，limit 50/100，正 bigint decimal 游标和登记字段。
- Required change: 用 Jakarta 跨字段约束和 UTC Clock 形成唯一无歧义查询输入。
- Verification contribution: 本 Step 定向命令与 G05。证明 承载查询输入与归一化：未知/重复、不合法时间、control 字符、溢出、错组合 400。Query 无 tenant、HTTP 原字符串不混入 PO
- After this file: 承载查询输入与归一化已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 4 — `CREATE stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/vo/DdcOperationLogSummaryVO.java`

- Purpose: 固定列表最小可见字段
- Symbols: DdcOperationLogSummaryVO、§9.2.1 records 项
- Repository evidence: DdcPublishTaskController 的 ResultRecord/VO 与 MP 摘要投影
- Dependencies and consumers: QueryService 转换。Web 保留 eventId/occurredAt
- Why now: File 3 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: DdcOperationLogSummaryVO、§9.2.1 records 项。固定列表最小可见字段按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 普通 Lombok class。十进制 eventId、微秒 UTC 时刻、源和资源摘要、actor、phase/outcome、可空 routePattern
- Error and edge behavior: 不得有 URL、handlerMethod、details 或不可授权输入字段
- Standards impact: MC-NAME-001、MC-MODEL-001、MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，POJO Lombok、不可变值对象及 Egon 转换约束，Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 3、Rule 6、Rule 10、Rule 11。普通 Lombok class。十进制 eventId、微秒 UTC 时刻、源和资源摘要、actor、phase/outcome、可空 routePattern。
- Required change: 按 Spec 发布摘要 wire shape 与浏览权限范围。
- Verification contribution: 本 Step 定向命令与 G05。证明 固定列表最小可见字段：不得有 URL、handlerMethod、details 或不可授权输入字段
- After this file: 固定列表最小可见字段已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 5 — `CREATE stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/vo/DdcOperationLogDetailVO.java`

- Purpose: 固定获权详情字段
- Symbols: DdcOperationLogDetailVO、§9.2.2 data
- Repository evidence: DdcPublishTaskController 既有 DTO/VO wire。底层 §11 行
- Dependencies and consumers: 精确读 PO → 单向详情投影 → 已二级授权用户
- Why now: File 4 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: DdcOperationLogDetailVO、§9.2.2 data。固定获权详情字段按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 普通 Lombok class。带实际 requestUrl、handlerMethod、recordedAt、独立 trace、结果注册白名单 details。ID 十进制字符串
- Error and edge behavior: 归档未找到一致 404。HTTP 缺失可空规则按入口。拒绝 PAC 和任意 JSON 渲染
- Standards impact: MC-NAME-001、MC-MODEL-001、MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，POJO Lombok、不可变值对象及 Egon 转换约束，Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 3、Rule 6、Rule 10、Rule 11。普通 Lombok class。带实际 requestUrl、handlerMethod、recordedAt、独立 trace、结果注册白名单 details。ID 十进制字符串。
- Required change: 按 Spec 建详情内容及空字段校验，不复用写模型给客户端。
- Verification contribution: 本 Step 定向命令与 G05。证明 固定获权详情字段：归档未找到一致 404。HTTP 缺失可空规则按入口。拒绝 PAC 和任意 JSON 渲染
- After this file: 固定获权详情字段已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 6 — `CREATE stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/vo/DdcOperationLogPageResult.java`

- Purpose: 固定页返回和下一页位置
- Symbols: DdcOperationLogPageResult、from/to、records、nextCursor.beforeTime/beforeId
- Repository evidence: 既有 Egon ResultRecord。Spec 选择 keyset 代替 count 容器
- Dependencies and consumers: QueryService → Controller → Web 分页与详情跳转
- Why now: File 5 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: DdcOperationLogPageResult、from/to、records、nextCursor.beforeTime/beforeId。固定页返回和下一页位置按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 普通 Lombok class。游标不可变值，由每页最后一行的 tuple 导出。记录空列表、游标末页 null。from/to 为已解析 UTC 窗口
- Error and edge behavior: 不计算总数、不用 offset。时间比较不以 JavaScript Date 重新格式化
- Standards impact: MC-NAME-001、MC-MODEL-001、MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，POJO Lombok、不可变值对象及 Egon 转换约束，Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 3、Rule 6、Rule 10、Rule 11。普通 Lombok class。游标不可变值，由每页最后一行的 tuple 导出。记录空列表、游标末页 null。from/to 为已解析 UTC 窗口。
- Required change: 承载一次查询结果及稳定分页定位元数据。
- Verification contribution: 本 Step 定向命令与 G05。证明 固定页返回和下一页位置：不计算总数、不用 offset。时间比较不以 JavaScript Date 重新格式化
- After this file: 固定页返回和下一页位置已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 7 — `CREATE stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/convertor/DdcOperationLogViewConverter.java`

- Purpose: 用现有 MapStruct 生成读投影
- Symbols: Summary extends BaseForwardConverter<OperationLogPO,DdcOperationLogSummaryVO>。Detail extends BaseForwardConverter<OperationLogPO,DdcOperationLogDetailVO>
- Repository evidence: Tianshu 根 POM 的 MapStruct/binding 与原 DdcPublishTaskConverter
- Dependencies and consumers: 以同文件所需具名投影 Bean 给 QueryService。代码编译期生成
- Why now: File 6 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: Summary extends BaseForwardConverter<OperationLogPO,DdcOperationLogSummaryVO>。Detail extends BaseForwardConverter<OperationLogPO,DdcOperationLogDetailVO>。用现有 MapStruct 生成读投影按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: id→十进制 eventId、createTime→recordedAt。保留相同纳秒截断，详情只映射经过安全白名单的 ObjectNode。摘要映射无权限敏感字段
- Error and edge behavior: 严格输出字段审阅，拒绝 raw serialization 和反射 mapping。不动现有 converter
- Standards impact: MC-NAME-001、MC-MODEL-001、MC-BEAN-001、MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，POJO Lombok、不可变值对象及 Egon 转换约束，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 3、Rule 4、Rule 6、Rule 10、Rule 11。id→十进制 eventId、createTime→recordedAt。保留相同纳秒截断，详情只映射经过安全白名单的 ObjectNode。摘要映射无权限敏感字段。
- Required change: 用两个明确的单向转换保持有意不同的公开载荷。
- Verification contribution: 本 Step 定向命令与 G05。证明 用现有 MapStruct 生成读投影：严格输出字段审阅，拒绝 raw serialization 和反射 mapping。不动现有 converter
- After this file: 用现有 MapStruct 生成读投影已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 8 — `MODIFY stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/permission/DdcPermissionCodes.java`

- Purpose: 声明独立审计权限
- Symbols: TIANSHU_OPERATION_LOG_READ、TIANSHU_OPERATION_LOG_DETAIL、@PermissionDefinition、@PermissionMessage
- Repository evidence: 既有 READ/WRITE 与 i18n 资源编目，permission-producer profile
- Dependencies and consumers: Controller + Admin安全链+ manifest。Web 依据同码访问
- Why now: File 7 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: TIANSHU_OPERATION_LOG_READ、TIANSHU_OPERATION_LOG_DETAIL、@PermissionDefinition、@PermissionMessage。声明独立审计权限按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 两码定义含 zh-CN/en-US，旧 TIANSHU_READ 保持控制台准入及原功能
- Error and edge behavior: 编目发布不自动授予角色。原普通配置 READ 不能打开审计页。详情必须两码
- Standards impact: MC-BEAN-001、MC-JSON-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 4、Rule 6、Rule 11。两码定义含 zh-CN/en-US，旧 TIANSHU_READ 保持控制台准入及原功能。
- Required change: 加入两个最窄授权码及双语，不变更普通角色与权限聚合。
- Verification contribution: 本 Step 定向命令与 G05。证明 声明独立审计权限：编目发布不自动授予角色。原普通配置 READ 不能打开审计页。详情必须两码
- After this file: 声明独立审计权限已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 9 — `MODIFY stellar-enclosure-tianshu-starter/src/main/java/top/egon/cola/component/tianshu/error/DdcErrorStatus.java`

- Purpose: 注册可区分读取不可用的业务错误
- Symbols: TIANSHU_OPERATION_LOG_UNAVAILABLE 56998
- Repository evidence: 现有 INVALID_REQUEST 56000 和 INTERNAL_FAILURE 56999。Repo 不会创建新错误协议
- Dependencies and consumers: QueryService 错误、Controller 范围 HTTP 映射、客户端
- Why now: File 8 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: TIANSHU_OPERATION_LOG_UNAVAILABLE 56998。注册可区分读取不可用的业务错误按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 保留代码 int 与稳定字符串状态，服务不可用只含安全文案与查询 trace
- Error and edge behavior: 目标不可用不映射空列表或权限拒绝，原业务错误不变
- Standards impact: MC-NAME-001、MC-JSON-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，Jackson 协议与安全字段一致，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 6、Rule 11。保留代码 int 与稳定字符串状态，服务不可用只含安全文案与查询 trace。
- Required change: 新增 Spec 明确的唯一业务错误码。
- Verification contribution: 本 Step 定向命令与 G05。证明 注册可区分读取不可用的业务错误：目标不可用不映射空列表或权限拒绝，原业务错误不变
- After this file: 注册可区分读取不可用的业务错误已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 10 — `CREATE stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/audit/DdcOperationLogQueryService.java`

- Purpose: 执行授权上下文中的只读查询
- Symbols: page(DdcOperationLogPageQuery)、detail(eventId,occurredAt)
- Repository evidence: DdcTenantContextExecutor、既有 publish 查询型服务、Step 2 OperationLogRepository
- Dependencies and consumers: Controller 完成权限边界后传已规范查询，按当前租户查受限 repo。MapStruct 映射
- Why now: File 9 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: page(DdcOperationLogPageQuery)、detail(eventId,occurredAt)。执行授权上下文中的只读查询按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 默认 Clock 24h，传半开窗口，两个桶最多各 limit+1，稳定归并 `occurred_at` 和 bigint id 后取一页，详情精确一组一桶一月
- Error and edge behavior: 任一桶/副本失败统一 503，无 fallback 与无部分页。越权请求不达 service，授权租户无该行 404，转换严守字段白名单
- Standards impact: MC-NAME-001、MC-VALID-001、MC-BEAN-001、MC-UTIL-001、MC-JSON-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，仅使用既有 JDK/Spring/Egon 能力，Jackson 协议与安全字段一致，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 2、Rule 4、Rule 5、Rule 6、Rule 9、Rule 10、Rule 11。默认 Clock 24h，传半开窗口，两个桶最多各 limit+1，稳定归并 `occurred_at` 和 bigint id 后取一页，详情精确一组一桶一月。
- Required change: 用组件窗口查询和既有 DTO 映射实现 Spec 的数据获取。
- Verification contribution: 本 Step 定向命令与 G05。证明 执行授权上下文中的只读查询：任一桶/副本失败统一 503，无 fallback 与无部分页。越权请求不达 service，授权租户无该行 404，转换严守字段白名单
- After this file: 执行授权上下文中的只读查询已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 11 — `CREATE stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/controller/audit/DdcOperationLogController.java`

- Purpose: 暴露两条只读合同和 springdoc
- Symbols: GET page、GET detail、operationId tianshu.ddcOperationLogController.page/.detail
- Repository evidence: DdcPublishTaskController 使用 @EgonGatewayPolicy、@PermissionResource、@RequiresPermissions。现有 ResultRecord
- Dependencies and consumers: 经 Web Security 进入 QueryService。@Parameter 和 @ApiResponses 生成文档
- Why now: File 10 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: GET page、GET detail、operationId tianshu.ddcOperationLogController.page/.detail。暴露两条只读合同和 springdoc按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 请求验证 header/path/query，显式 unknown param rejection。采用现有 ResultRecord 七字段、no-store。同时文档写 security 的两个字符串错误字段
- Error and edge behavior: 400/404/500/503 明确 HTTP 及原封套，由本 Controller 范围衔接既有 DdcGlobalExceptionHandler。401 Bearer 与 403 边界保持。仅可见自己的租户。只读
- Standards impact: MC-NAME-001、MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 2、Rule 4、Rule 6、Rule 10、Rule 11。请求验证 header/path/query，显式 unknown param rejection。采用现有 ResultRecord 七字段、no-store。同时文档写 security 的两个字符串错误字段。
- Required change: 按 Spec URL 与 wire 建可执行 Query，并出具真实 OpenAPI。
- Verification contribution: 本 Step 定向命令与 G05。证明 暴露两条只读合同和 springdoc：400/404/500/503 明确 HTTP 及原封套，由本 Controller 范围衔接既有 DdcGlobalExceptionHandler。401 Bearer 与 403 边界保持。仅可见自己的租户。只读
- After this file: 暴露两条只读合同和 springdoc已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 12 — `MODIFY stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/security/tenancy/DdcTenantContextFilter.java`

- Purpose: 将操作审计纳入预期租户验证
- Symbols: requiresExpectedTenant
- Repository evidence: 目前对 /api/v1/tianshu/i18n/、/ai/ 显式校验
- Dependencies and consumers: 新增审计路径从可信 Rbac3UserDetails 绑定租户、Web ddcApi 的 expected header 只核对
- Why now: File 11 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: requiresExpectedTenant。将操作审计纳入预期租户验证按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 新增 audit list/detail 正确匹配。正常 decode 仍用原 decoder，拒绝缺失/重复/错租户或控制字符
- Error and edge behavior: 按真实 security handler 生成原 401/403 形状，不放宽 tenant 逻辑
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-UTIL-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，仅使用既有 JDK/Spring/Egon 能力，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 5、Rule 11。新增 audit list/detail 正确匹配。正常 decode 仍用原 decoder，拒绝缺失/重复/错租户或控制字符。
- Required change: 复用原租户边界的 deny-by-default，实现 Spec Header 兼容。
- Verification contribution: 本 Step 定向命令与 G05。证明 将操作审计纳入预期租户验证：按真实 security handler 生成原 401/403 形状，不放宽 tenant 逻辑
- After this file: 将操作审计纳入预期租户验证已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 13 — `MODIFY stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/security/management/DdcAdminSecurityConfiguration.java`

- Purpose: 在现有 denyAll 前放行具体方法和审计权限
- Symbols: requestMatchers、scopeAndPermission 底层检查
- Repository evidence: 既有显式 GET 列表、`anyRequest().denyAll()`，scopeAndPermission 校 OAuth 与 Rbac3UserDetails
- Dependencies and consumers: 现有网关配置 → Web Security → Controller 和 PAC 双层
- Why now: File 12 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: requestMatchers、scopeAndPermission 底层检查。在现有 denyAll 前放行具体方法和审计权限按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 仅列表/详情 GET 使用现有 OAuth 读 scope，RBAC 独立要求指定 READ/DETAIL，顺序保证 `/page` 不误匹配 detail。保留原 app 准入
- Error and edge behavior: 原通用权限不单独授权这两条路径。其余 HTTP 方法、非审计 URL 和异常协议不变
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-UTIL-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，仅使用既有 JDK/Spring/Egon 能力，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 5、Rule 11。仅列表/详情 GET 使用现有 OAuth 读 scope，RBAC 独立要求指定 READ/DETAIL，顺序保证 `/page` 不误匹配 detail。保留原 app 准入。
- Required change: 在第一层真实 filter chain 落地 Spec 已决定的最小权限。
- Verification contribution: 本 Step 定向命令与 G05。证明 在现有 denyAll 前放行具体方法和审计权限：原通用权限不单独授权这两条路径。其余 HTTP 方法、非审计 URL 和异常协议不变
- After this file: 在现有 denyAll 前放行具体方法和审计权限已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 14 — `CREATE stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/permission/DdcTianshuOperationLogsMessages.java`

- Purpose: 声明操作日志页双语消息
- Symbols: @PermissionMessage、pageCode tianshu.operationLogs
- Repository evidence: DdcTianshuPublishTasksMessages 为每页独立的源默认
- Dependencies and consumers: DdcAdminUiI18nCatalog 与 permission producer 收集 → Web fixture
- Why now: File 13 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: @PermissionMessage、pageCode tianshu.operationLogs。声明操作日志页双语消息按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: zh-CN/en-US 同名键用于标题列、筛选、页/空态、失败、复制和详情
- Error and edge behavior: 消息模板参数一致。不包含数据或敏感信息。源码是唯一默认来源
- Standards impact: MC-NAME-001、MC-JSON-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，Jackson 协议与安全字段一致，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 6、Rule 11。zh-CN/en-US 同名键用于标题列、筛选、页/空态、失败、复制和详情。
- Required change: 接入已批准页面文案的现有发布链。
- Verification contribution: 本 Step 定向命令与 G05。证明 声明操作日志页双语消息：消息模板参数一致。不包含数据或敏感信息。源码是唯一默认来源
- After this file: 声明操作日志页双语消息已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 15 — `MODIFY stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/i18n/DdcAdminUiI18nCatalog.java`

- Purpose: 在正统语言目录登记新页面
- Symbols: PAGE_CODES、fromDeclarations
- Repository evidence: 现有 tianshu.publishTasks 静态页面注册和 locale 动态覆盖
- Dependencies and consumers: App PageLanguageBoundary 与版本加载，原 registry 不变
- Why now: File 14 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: PAGE_CODES、fromDeclarations。在正统语言目录登记新页面按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 登记 tianshu.operationLogs、双语 defaults 及现有版本计算。继续复用既有 admin bundle
- Error and edge behavior: 拒绝重复键与未登记 locale。原所有 pageCodes 仍可发现
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 6、Rule 11。登记 tianshu.operationLogs、双语 defaults 及现有版本计算。继续复用既有 admin bundle。
- Required change: 使新页面确实通过后端已存在的语言桥接载入。
- Verification contribution: 本 Step 定向命令与 G05。证明 在正统语言目录登记新页面：拒绝重复键与未登记 locale。原所有 pageCodes 仍可发现
- After this file: 在正统语言目录登记新页面已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 16 — `MODIFY stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/i18n/DdcAdminUiI18nCatalogTest.java`

- Purpose: 核对翻译源与完整消息列表
- Symbols: PAGE_CODES、新 page bundle locale coverage
- Repository evidence: 已有页面注册和 Bundle set 断言
- Dependencies and consumers: 生成 manifest 与 Step 6 fixture 同键
- Why now: 在生产行为变化前准备和执行真实断言，可编译的声明不计实现。
- Contract/signature changes: PAGE_CODES、新 page bundle locale coverage。核对翻译源与完整消息列表按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 两个支持 locale 无缺字段，原目录仍完备
- Error and edge behavior: 失败阻止输出 Web 消费的虚假成功产物
- Standards impact: MC-JSON-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。Jackson 协议与安全字段一致，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 6、Rule 11。两个支持 locale 无缺字段，原目录仍完备。
- Required change: 为本 Step 新增编目执行已有回归证据。
- Verification contribution: DdcAdminUiI18nCatalogTest 作为本步先行验证。本 Step 定向命令与 G05。证明 核对翻译源与完整消息列表：失败阻止输出 Web 消费的虚假成功产物
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

- Validation working directory: `/Users/mario/SelfProject/Stellar-Enclosure/tianshu-service`
- Verification command: `./mvnw -B -ntp -Dmaven.repo.local="$EGON_AUDIT_PLAN_M2" -pl stellar-enclosure-tianshu-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DdcOperationLogWebContractTest,DdcOperationLogQueryServiceTest,DdcAdminSecurityIntegrationTest,DdcAdminUiI18nCatalogTest test`。运行及证据顺序见 §8。
- Expected result: 覆盖 200/400/401/403/404/500/503、no-store、BigInt、跨桶边界、无泄密。§8 G05 的 OpenAPI + manifest 同制品
- Failure returns to: 回条件不满足的 security、query 或 converter，确认实际授权/编码再进入 Step 6
- Completion criteria: 两个受限 API、两权限及 i18n 编目同一版本产物，证明没有 COUNT 或写副作用
- Rollback: 停 Web 公告/权限启用，回退到 Step 4 的服务制品，保留既有持久采集
- Commit paths: `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/web/DdcOperationLogWebContractTest.java`、`stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/audit/DdcOperationLogQueryServiceTest.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/query/DdcOperationLogPageQuery.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/vo/DdcOperationLogSummaryVO.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/vo/DdcOperationLogDetailVO.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/vo/DdcOperationLogPageResult.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/convertor/DdcOperationLogViewConverter.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/permission/DdcPermissionCodes.java`、`stellar-enclosure-tianshu-starter/src/main/java/top/egon/cola/component/tianshu/error/DdcErrorStatus.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/audit/DdcOperationLogQueryService.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/controller/audit/DdcOperationLogController.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/security/tenancy/DdcTenantContextFilter.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/security/management/DdcAdminSecurityConfiguration.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/permission/DdcTianshuOperationLogsMessages.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/service/i18n/DdcAdminUiI18nCatalog.java`、`stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/service/i18n/DdcAdminUiI18nCatalogTest.java`
- Commit: `feat(tianshu): expose authorized operation log queries`

### Step 6 — 上线管理台操作日志浏览入口

- Requirements: REQ-002、REQ-007
- Dependencies: Step 5 已产出同版本 OpenAPI 与 PAC manifest。UI 编码可在固定契约下并行准备
- Baseline state: 管理台支持现有权限/多语言和请求上下文，缺审计客户端和页面
- Observable outcome: 有权从正常菜单查询与看详情。首屏可空，服务错误与跨租户状态安全
- End state: 加入独立页面、最小导航和源翻译同步，交付测试、类型检查和锁定 Web 制品
- Test-first gate: Required。Vitest 首写缺路由/页面/错误状态，使用布置好的权限和语言 fixture，真实断言失败
- Manual Checks: MC-ARCH-001、MC-REUSE-001、MC-JSON-001、MC-TIME-001、MC-SCOPE-001、MC-TEST-001
- Literal Rules: Rule 6、Rule 10、Rule 11
- Ordered files:

#### File 1 — `CREATE src/pages/OperationLogsPage.test.tsx`

- Purpose: 按真实 UI 使用路径写 RED
- Symbols: listsAndKeepsCursor、showsUnavailableAndEmpty、deniesDetail、tenantGenerationDropsOldResult
- Repository evidence: PublishTasksPage.test.tsx、AdminI18nProvider.test.tsx、access.test.tsx
- Dependencies and consumers: 观察新页面、ddcApi 管道与权限和请求代次。类型可先独立满足编译
- Why now: 首先冻结本步编译合同或行为 RED 证据。
- Contract/signature changes: listsAndKeepsCursor、showsUnavailableAndEmpty、deniesDetail、tenantGenerationDropsOldResult。按真实 UI 使用路径写 RED按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 双语标题/筛选、页与抽屉，向 API 原样传 eventId/occurredAt，401/403/404/503 与空区分。旧租户慢回包扔掉
- Error and edge behavior: RED 因路由/组件未暴露或独立页行为不存在。测试拒绝调用无授权详情及无越权缓存
- Standards impact: MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 6、Rule 10、Rule 11。双语标题/筛选、页与抽屉，向 API 原样传 eventId/occurredAt，401/403/404/503 与空区分。旧租户慢回包扔掉。
- Required change: 用现有 Vitest+Testing Library 门先固定 UI 验收。
- Verification contribution: OperationLogsPage.test 作为本步先行验证。本 Step 定向命令与 G06。证明 按真实 UI 使用路径写 RED：RED 因路由/组件未暴露或独立页行为不存在。测试拒绝调用无授权详情及无越权缓存
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 2 — `CREATE src/api/operationLog.ts`

- Purpose: 承载两条已审批 GET 合同
- Symbols: pageOperationLogs、getOperationLogDetail、审计读取 shape
- Repository evidence: src/api/client.ts 的 ddcApi<T>、DdcRequestOptions、X-Egon-Tianshu-Tenant
- Dependencies and consumers: OperationLogsPage → BFF/host REST。只读无新依赖
- Why now: File 1 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: pageOperationLogs、getOperationLogDetail、审计读取 shape。承载两条已审批 GET 合同按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 序列化成对参数和明确 offset，按服务原始 ISO 和 BigInt string 回传锚点。AbortSignal 走现有 RequestContext Header，不允许页面自己派生 tenant
- Error and edge behavior: 保留 DdcApiError 分类，拒绝伪造 ID 或无授权触发。不隐式重试切换 tenant
- Standards impact: MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 6、Rule 10、Rule 11。序列化成对参数和明确 offset，按服务原始 ISO 和 BigInt string 回传锚点。AbortSignal 走现有 RequestContext Header，不允许页面自己派生 tenant。
- Required change: 让 Web 只消费已验证的 OpenAPI/ResultRecord 和 existing auth 机制。
- Verification contribution: 本 Step 定向命令与 G06。证明 承载两条已审批 GET 合同：保留 DdcApiError 分类，拒绝伪造 ID 或无授权触发。不隐式重试切换 tenant
- After this file: 承载两条已审批 GET 合同已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 3 — `MODIFY src/permissions/permissions.ts`

- Purpose: 同步审计权限码
- Symbols: 审计 READ/DETAIL 常量
- Repository evidence: 现有 READ、PUBLISH、AI_READ 等常量
- Dependencies and consumers: resources、OperationLogsPage、Web scanner
- Why now: File 2 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: 审计 READ/DETAIL 常量。同步审计权限码按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 和 Step 5 后端 manifest 完全一致的固定值
- Error and edge behavior: 旧配置 READ 不替代审计权限。无新角色自动授予
- Standards impact: MC-JSON-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。Jackson 协议与安全字段一致，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 6、Rule 11。和 Step 5 后端 manifest 完全一致的固定值。
- Required change: 复用目前权限常量入口并验证 artifact cross-check。
- Verification contribution: 本 Step 定向命令与 G06。证明 同步审计权限码：旧配置 READ 不替代审计权限。无新角色自动授予
- After this file: 同步审计权限码已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 4 — `MODIFY src/permissions/resources.ts`

- Purpose: 声明菜单和页面资源并标注语言键
- Symbols: tianshu.page.operationLogs、对应菜单、父 tianshu.app.console
- Repository evidence: publishTasks 页面与菜单的标准定义
- Dependencies and consumers: RequirePermission、菜单构建和 PAC scanner
- Why now: File 3 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: tianshu.page.operationLogs、对应菜单、父 tianshu.app.console。声明菜单和页面资源并标注语言键按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 沿用已有 console 准入。审计读限定菜单/页面。zh-CN/en-US 标签与原命名规则一致
- Error and edge behavior: 未授予 audit READ 不显示入口且跳转被拒。不调整 root app gate
- Standards impact: MC-JSON-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。Jackson 协议与安全字段一致，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 6、Rule 11。沿用已有 console 准入。审计读限定菜单/页面。zh-CN/en-US 标签与原命名规则一致。
- Required change: 按已批准 page 资源完成现有管理台导航登记。
- Verification contribution: 本 Step 定向命令与 G06。证明 声明菜单和页面资源并标注语言键：未授予 audit READ 不显示入口且跳转被拒。不调整 root app gate
- After this file: 声明菜单和页面资源并标注语言键已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 5 — `MODIFY src/test/i18nCatalogs.json`

- Purpose: 同步 server 编译后的语言 fixture
- Symbols: tianshu.operationLogs 与权限资源测试数据
- Repository evidence: scripts/sync-i18n-fixtures.mjs 读取 tianshu-java manifest，产出已有 JSON 文件
- Dependencies and consumers: Vitest、catalogCoverage 使用。由实际产物生成，禁止手写
- Why now: File 4 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: tianshu.operationLogs 与权限资源测试数据。同步 server 编译后的语言 fixture按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 新增双语服务默认和页面资源消息，其他内容只接受已预期源差异
- Error and edge behavior: 生产源 publish/manifest 缺失阻断生成，--check 差异禁止提交
- Standards impact: MC-JSON-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。Jackson 协议与安全字段一致，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 6、Rule 11。新增双语服务默认和页面资源消息，其他内容只接受已预期源差异。
- Required change: 用现成同步命令更新测试 fixture 并证明与 Step 5 产物一致。
- Verification contribution: 本 Step 定向命令与 G06。证明 同步 server 编译后的语言 fixture：生产源 publish/manifest 缺失阻断生成，--check 差异禁止提交
- After this file: 同步 server 编译后的语言 fixture已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 6 — `CREATE src/pages/OperationLogsPage.tsx`

- Purpose: 展示租户内的可用审计列表和获权详情
- Symbols: OperationLogsPage、筛选与已访问页、Drawer
- Repository evidence: PublishTasksPage、现有 usePageMessages、access usePermission、Ant Design
- Dependencies and consumers: api/operationLog、源码双语 catalog 和 App route
- Why now: File 5 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: OperationLogsPage、筛选与已访问页、Drawer。展示租户内的可用审计列表和获权详情按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: UTC 原文作为稳定查询状态，显示时按用户时区或浏览器仅格式化。排序按服务，不额外改写。每次筛选切到首屏且有覆盖代次
- Error and edge behavior: 分别处理 loading/empty/503/401/403/404。无权限或切租户不继续显示详情。出口文案普通文本，复制和焦点回归
- Standards impact: MC-JSON-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。Jackson 协议与安全字段一致，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 6、Rule 10、Rule 11。UTC 原文作为稳定查询状态，显示时按用户时区或浏览器仅格式化。排序按服务，不额外改写。每次筛选切到首屏且有覆盖代次。
- Required change: 复用现有界面组件完成列表/抽屉/链接与可访问性。
- Verification contribution: 本 Step 定向命令与 G06。证明 展示租户内的可用审计列表和获权详情：分别处理 loading/empty/503/401/403/404。无权限或切租户不继续显示详情。出口文案普通文本，复制和焦点回归
- After this file: 展示租户内的可用审计列表和获权详情已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 7 — `MODIFY src/App.tsx`

- Purpose: 接通实际路由和双语边界
- Symbols: operation-logs、tianshu.page.operationLogs、tianshu.operationLogs
- Repository evidence: 与 publish-tasks 同一管理路由组及 PageLanguageBoundary
- Dependencies and consumers: 菜单与直链都经 RequirePermission。页面使用已有 AuthProvider
- Why now: File 6 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: operation-logs、tianshu.page.operationLogs、tianshu.operationLogs。接通实际路由和双语边界按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 新增单个子路由，原 publish tasks 和 app gate 无变
- Error and edge behavior: 菜单未授权直链不能绕过。旧后端或新能力未启用时保留既有不公开部署顺序
- Standards impact: MC-JSON-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。Jackson 协议与安全字段一致，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 6、Rule 11。新增单个子路由，原 publish tasks 和 app gate 无变。
- Required change: 让新查询通过正常管理导航与安全边界浏览。
- Verification contribution: 本 Step 定向命令与 G06。证明 接通实际路由和双语边界：菜单未授权直链不能绕过。旧后端或新能力未启用时保留既有不公开部署顺序
- After this file: 接通实际路由和双语边界已具备该职责，继续下一个 File 或本步的整体集成验证。

- Validation working directory: `/Users/mario/SelfProject/Stellar-Enclosure/tianshu-web`
- Verification command: `npm test -- src/pages/OperationLogsPage.test.tsx src/i18n/catalogCoverage.test.ts src/permissions/access.test.tsx src/api/client.test.ts && npm run typecheck && npm run lint`。运行及证据顺序见 §8。
- Expected result: 测试具名用例通过，类型和 lint 退出 0。PAC 构建用相同产物输入，不允许 mock build 当 release
- Failure returns to: 回权限/时间/请求 cancel 或 catalog 同步。无服务端就绪和正式权限制品不向用户开放
- Completion criteria: 对照 OpenAPI contract、fixture 和 Web 通常入口，拒绝越权、微秒保真并持有原始 cursor
- Rollback: 回退 Web 制品或撤掉该资源可见性，新日志继续写
- Commit paths: `src/pages/OperationLogsPage.test.tsx`、`src/api/operationLog.ts`、`src/permissions/permissions.ts`、`src/permissions/resources.ts`、`src/test/i18nCatalogs.json`、`src/pages/OperationLogsPage.tsx`、`src/App.tsx`
- Commit: `feat(tianshu-web): browse operation logs in the admin console`

### Step 7 — 完成两主两副本端到端证据和切新回退演练

- Requirements: REQ-003、REQ-004、REQ-005、REQ-006、REQ-007、REQ-008
- Dependencies: Steps 1–6。干净的隔离数据库与兼容回退制品，按 §8 准备真实运行
- Baseline state: 代码两边已定向验证。原 PostgreSQL 路由命令缺跨版本、整链 Outbox ACK 和实际对照压力门
- Observable outcome: 可按清单切换唯一生产、回退并恢复，两个真实 replica 与 PAC bytes/普通空表均有证据
- End state: 交付可重放的 IT 与运行手册，临时脚本在本步结束前移除。生产启用留审批
- Test-first gate: Required。IT 先对基线、反例或故障注入有意义地 RED。对照兼容制品+真 DB，否则不作为回退证明
- Manual Checks: MC-ARCH-001、MC-REUSE-001、MC-VALID-001、MC-LOG-001、MC-BEAN-001、MC-UTIL-001、MC-JSON-001、MC-TIME-001、MC-CONFIG-001、MC-PATTERN-001、MC-SCOPE-001、MC-TEST-001
- Literal Rules: Rule 2、Rule 4、Rule 5、Rule 6、Rule 7、Rule 9、Rule 10、Rule 11
- Ordered files:

#### File 1 — `CREATE stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/integration/DdcOperationLogCutoverIT.java`

- Purpose: 先写端到端故障和回退验收
- Symbols: physicalAuditAndFailureRecovery、pacOnlyAndRollbackArtifact、replicaLagAndCapacity
- Repository evidence: DdcTianshuDdlIT 使用真实版史与 metadata，DdcConfigOutboxIntegrationTest 已使用原 Outbox，Components EgonColaPostgreSqlReadwriteIT 复用四物理 URL 约定
- Dependencies and consumers: 真实 Admin + Publisher Bean/业务主源/Outbox/审计逻辑 DS。单次启动闭环。隔离的另一套数据用于退役预览
- Why now: 首先冻结本步编译合同或行为 RED 证据。
- Contract/signature changes: physicalAuditAndFailureRecovery、pacOnlyAndRollbackArtifact、replicaLagAndCapacity。先写端到端故障和回退验收按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 真实起待分发→投递→事实。在 commit/ACK 窗口注入源 ACK 丢失。两个组各两桶、两个 replica LSN。目标隔离、同 ID 异月冲突。旧普通和 PAC 混合、兼容制品回退
- Error and edge behavior: 使用 opt-in TIANSHU_OPERATION_LOG_IT_ENABLED、隔离目标证据。旧制品 baseline 看到额外 PAC 版本时拒启是预期。缺依赖、跳过、mock 或单库不通过运行门
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-CONFIG-001、MC-PATTERN-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，保持实际各环境配置结构相同，按已选 Strategy/Adapter/Projection 与现有职责划分，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 6、Rule 7、Rule 9、Rule 10、Rule 11。真实起待分发→投递→事实。在 commit/ACK 窗口注入源 ACK 丢失。两个组各两桶、两个 replica LSN。目标隔离、同 ID 异月冲突。旧普通和 PAC 混合、兼容制品回退。
- Required change: 使零普通历史、真实物理落点、源事务与角色权限从同一运行路径可复核。
- Verification contribution: DdcOperationLogCutoverIT 作为本步先行验证。本 Step 定向命令与 G07。证明 先写端到端故障和回退验收：使用 opt-in TIANSHU_OPERATION_LOG_IT_ENABLED、隔离目标证据。旧制品 baseline 看到额外 PAC 版本时拒启是预期。缺依赖、跳过、mock 或单库不通过运行门
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 2 — `CREATE scripts/work/2026-11-23-04-18-operation-log-verify.sh`

- Purpose: 仅在实施环境执行缺省无命令的 release 对照演练
- Symbols: 双实例带权读写、旧制品切换、故障注入和指标抓取
- Repository evidence: 现有 Maven test selector 可定向实跑。但跨制品、物理备库回放/暂停和旧 worker 水位没有单命令
- Dependencies and consumers: 只使用已批准隔离库和两个制品目录。用 Step 7 IT、现有 CI 制品与 PG 只读观测
- Why now: File 1 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: 双实例带权读写、旧制品切换、故障注入和指标抓取。仅在实施环境执行缺省无命令的 release 对照演练按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 验证并记录源制品/目标制品指纹、每组角色、被允许访问的 schema、真实运行模式和用例报告
- Error and edge behavior: 不传生产站点或真实秘密到证据。确保恢复暂停的副本并退出所拥有进程，测试无跳过。证据固化后在本 Step 删除脚本
- Standards impact: MC-UTIL-001、MC-CONFIG-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。仅使用既有 JDK/Spring/Egon 能力，保持实际各环境配置结构相同，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 5、Rule 7、Rule 10、Rule 11。验证并记录源制品/目标制品指纹、每组角色、被允许访问的 schema、真实运行模式和用例报告。
- Required change: 临时串出隔离环境证据，绝不纳入 commit 或永久 top-level scripts。
- Verification contribution: 本 Step 定向命令与 G07。证明 仅在实施环境执行缺省无命令的 release 对照演练：不传生产站点或真实秘密到证据。确保恢复暂停的副本并退出所拥有进程，测试无跳过。证据固化后在本 Step 删除脚本
- After this file: 达到隔离演练可执行状态，收证后按本 Step 删除。

#### File 3 — `CREATE docs/tianshu-operation-log-rollout.md`

- Purpose: 沉淀审核可用的发布和回退手册
- Symbols: 目标诊断、PAC 兼容制品、2×2 检查、暂停/恢复、保留与退役门
- Repository evidence: docs/tianshu-ai-migration-runbook.md 与 README 的 immutable GAV/部署边界
- Dependencies and consumers: 运维执行已验证的 Step 7/§9 程序，独立按 Step 8 条件退役
- Why now: File 2 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: 目标诊断、PAC 兼容制品、2×2 检查、暂停/恢复、保留与退役门。沉淀审核可用的发布和回退手册按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 记录实际 CI/artifact ID、路由/manifest hash、授权动作和预算配置输入，列出 payload 和指标标签约束。仅保留清洗过的报告
- Error and edge behavior: 期望错误、暂停/反向恢复各有明确回点。禁止自动 DROP、DEAD 重驱和覆写既有 POM 工件
- Standards impact: MC-UTIL-001、MC-CONFIG-001、MC-TIME-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。仅使用既有 JDK/Spring/Egon 能力，保持实际各环境配置结构相同，使用 java.time 与 Spec 精度，展示不改定位，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 5、Rule 7、Rule 10、Rule 11。记录实际 CI/artifact ID、路由/manifest hash、授权动作和预算配置输入，列出 payload 和指标标签约束。仅保留清洗过的报告。
- Required change: 用耐久手册代替临时脚本，保留可复核报告链接和逐主组操作边界。
- Verification contribution: 本 Step 定向命令与 G07。证明 沉淀审核可用的发布和回退手册：期望错误、暂停/反向恢复各有明确回点。禁止自动 DROP、DEAD 重驱和覆写既有 POM 工件
- After this file: 沉淀审核可用的发布和回退手册已具备该职责，继续下一个 File 或本步的整体集成验证。

- Validation working directory: `/Users/mario/SelfProject/Stellar-Enclosure/tianshu-service`
- Verification command: `TIANSHU_OPERATION_LOG_IT_ENABLED=true ./mvnw -B -ntp -Dmaven.repo.local="$EGON_AUDIT_PLAN_M2" -pl stellar-enclosure-tianshu-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DdcOperationLogCutoverIT test`。运行及证据顺序见 §8。
- Expected result: 真实 2×2 加独立源物理写、PG 角色、WAL 回放、失败及切回轨迹全部非跳过。容量报告在批准预算内
- Failure returns to: 回实际责任 Step 2/3/4/5/6。预算缺或拓扑/权限不符暂停启用，继续补齐隔离证据
- Completion criteria: 手册 + 哈希 + 测试证据 + 采集暂停/ACK 保留/回退切回矩阵，且临时脚本已删
- Rollback: 只撤本 Step 验证文件/文档。升级后回退采用 §9 验过兼容制品和原技术租约
- Commit paths: `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/integration/DdcOperationLogCutoverIT.java`、`scripts/work/2026-11-23-04-18-operation-log-verify.sh`（临时路径，收证后删除，禁止暂存）、`docs/tianshu-operation-log-rollout.md`
- Commit: `test(tianshu): verify sharded operation log cutover and rollback`

### Step 8 — 按独立验收门评估旧表物理退役

- Requirements: REQ-005、REQ-006、REQ-008
- Dependencies: Step 7。只在旧制品退出、回退窗关闭且兼容两个实际 fingerprint/history 的独立审批后执行
- Baseline state: 新版已切新，旧表和声明无人读写。当前仓无已证删除兼容的工具路径
- Observable outcome: 候选退役制品在两个现有历史保护下可启动，新 CLI 同 key 可读，且老表可安全移除。未证则保留旧表并记录独立后续
- End state: 通过时追加受管 V002 并删除已断 consumer 的旧实现。未通过时无 SQL/代码提交，不阻塞 Step 7 切新
- Test-first gate: Required。先校验两份实际 route/history 就绪 proof 与旧引用测试，基线缺兼容时禁止进入下一个 File
- Manual Checks: MC-ARCH-001、MC-REUSE-001、MC-NAME-001、MC-VALID-001、MC-MODEL-001、MC-CONVERT-001、MC-LOG-001、MC-BEAN-001、MC-JSON-001、MC-CONFIG-001、MC-SCOPE-001、MC-TEST-001
- Literal Rules: Rule 1、Rule 2、Rule 3、Rule 4、Rule 6、Rule 7、Rule 11
- Ordered files:

#### File 1 — `MODIFY stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/migration/DdcTianshuDdlContractTest.java`

- Purpose: 先锁定两个历史对退役路由的兼容性
- Symbols: prefixAndRouteAfterDrop、retirementDoesNotTouchOldHistory
- Repository evidence: 现有 EgonColaPostgreDdlRunner 与 DdcDatabaseReadyValidator 精确 route 对比。Outbox 历史独立检查
- Dependencies and consumers: Step 7 compatibility proof 到候选退役制品，并继续有新 PAC 回执
- Why now: 首先冻结本步编译合同或行为 RED 证据。
- Contract/signature changes: prefixAndRouteAfterDrop、retirementDoesNotTouchOldHistory。先锁定两个历史对退役路由的兼容性按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 测试收集旧/候选 route fingerprint，演练有/没有旧声明时两个真实历史、老 PAC 重读、已升级 worker startup
- Error and edge behavior: RED 是仍有旧表声明、兼容失败或 runtime 仍访问旧表。若真实守卫不支持删除路线，停在验证返回，Step 1–7 完成
- Standards impact: MC-VALID-001、MC-JSON-001、MC-CONFIG-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Jackson 协议与安全字段一致，保持实际各环境配置结构相同，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 6、Rule 7、Rule 11。测试收集旧/候选 route fingerprint，演练有/没有旧声明时两个真实历史、老 PAC 重读、已升级 worker startup。
- Required change: 在开发和部署任何破坏性 SQL 前先证实既有合规退役路径。
- Verification contribution: DdcTianshuDdlContractTest 作为本步先行验证。本 Step 定向命令与 G09。证明 先锁定两个历史对退役路由的兼容性：RED 是仍有旧表声明、兼容失败或 runtime 仍访问旧表。若真实守卫不支持删除路线，停在验证返回，Step 1–7 完成
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

#### File 2 — `CREATE stellar-enclosure-tianshu-admin/src/main/resources/db/egon-mp/V20261123_002__retire_tianshu_operation_log.sql`

- Purpose: 登记独立物理清退版本
- Symbols: V20261123_002、ddc_operation_log_t0
- Repository evidence: §11.2.4、业务 runner 和独立 cutoff
- Dependencies and consumers: 仅进入通过第一个 File gate 的单独 release candidate。Admin/Publisher 与新 CLI 都先确认兼容
- Why now: File 1 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: V20261123_002、ddc_operation_log_t0。登记独立物理清退版本按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 追加一个新受管版本通过现有 primary 角色核验后依赖限定移除，保有 PAC、业务版史和审计表
- Error and edge behavior: 不存在连带依赖和受控锁、超时及备份范围证据才允许操作。按 guard 阻断则整 Step 留待另行退役
- Standards impact: MC-VALID-001、MC-CONFIG-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，保持实际各环境配置结构相同，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 7、Rule 11。追加一个新受管版本通过现有 primary 角色核验后依赖限定移除，保有 PAC、业务版史和审计表。
- Required change: 仅在已证明现有路径可兼容时提交退役脚本。
- Verification contribution: 本 Step 定向命令与 G09。证明 登记独立物理清退版本：不存在连带依赖和受控锁、超时及备份范围证据才允许操作。按 guard 阻断则整 Step 留待另行退役
- After this file: 登记独立物理清退版本已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 3 — `MODIFY stellar-enclosure-tianshu-admin/src/main/resources/db/egon-mp/repository-manifest.json`

- Purpose: 为单独退役制品追加 checksum
- Symbols: V20261123_002
- Repository evidence: Step 3 manifest 新末尾。当前 history prefix 严查
- Dependencies and consumers: 未来退役 runner 及两个真实版史验证
- Why now: File 2 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: V20261123_002。为单独退役制品追加 checksum按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 追加 V002 的路径及 checksum，保留所有已有当前 entries。不可提前加进第一次切新制品
- Error and edge behavior: 某目标不支持兼容时，回退候选差异，不改已执行 SQL/history
- Standards impact: MC-JSON-001、MC-CONFIG-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。Jackson 协议与安全字段一致，保持实际各环境配置结构相同，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 6、Rule 7、Rule 11。追加 V002 的路径及 checksum，保留所有已有当前 entries。不可提前加进第一次切新制品。
- Required change: 使独立退役具备真实可续跑版史。
- Verification contribution: 本 Step 定向命令与 G09。证明 为单独退役制品追加 checksum：某目标不支持兼容时，回退候选差异，不改已执行 SQL/history
- After this file: 为单独退役制品追加 checksum已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 4 — `MODIFY stellar-enclosure-tianshu-admin/src/main/resources/egon-tianshu-persistence.yml`

- Purpose: 移除不再有效的逻辑声明
- Symbols: sharding.tables.ddc_operation_log
- Repository evidence: Step 4 保持旧指纹，没有 runtime 写读。strict fingerprint 依赖声明
- Dependencies and consumers: 业务 bootstrapper 与 Outbox 独立 schema validator 同时验证
- Why now: File 3 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: sharding.tables.ddc_operation_log。移除不再有效的逻辑声明按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 只在确定实际支持兼容旧/新 fingerprint 的候选路径删除 route 条目。无全局豁免
- Error and edge behavior: 已有 previous-route-exclusions 仅加表，不能用作删除修复。当前路径若拒启保留原表与声明
- Standards impact: MC-CONFIG-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。保持实际各环境配置结构相同，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 7、Rule 11。只在确定实际支持兼容旧/新 fingerprint 的候选路径删除 route 条目。无全局豁免。
- Required change: 把旧 route 变更的影响留在独立候选 release，并强制两份历史验证。
- Verification contribution: 本 Step 定向命令与 G09。证明 移除不再有效的逻辑声明：已有 previous-route-exclusions 仅加表，不能用作删除修复。当前路径若拒启保留原表与声明
- After this file: 移除不再有效的逻辑声明已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 5 — `MODIFY stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/config/DdcDatabaseReadyValidator.java`

- Purpose: 移除旧表启动硬依赖并留 PAC
- Symbols: TIANSHU_TABLES、EXPECTED_COLUMNS、TABLE_CONSTRAINTS 中 ddc_operation_log_t0
- Repository evidence: 原明确列/约束和 Step 3 新 PAC readiness
- Dependencies and consumers: Admin/Publisher/Observer 启动复用
- Why now: File 4 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: TIANSHU_TABLES、EXPECTED_COLUMNS、TABLE_CONSTRAINTS 中 ddc_operation_log_t0。移除旧表启动硬依赖并留 PAC按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 与退役 manifest 一起移除旧表存在性约束，不弱化其他任何表或校验和
- Error and edge behavior: 根因是路由指纹时不短路 validateHistory。验证缺 PAC 仍阻断
- Standards impact: MC-VALID-001、MC-BEAN-001、MC-JSON-001、MC-CONFIG-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Spring 具名 Bean、@Qualifier 与 Lombok constructor。如属业务类使用 @Slf4j，Jackson 协议与安全字段一致，保持实际各环境配置结构相同，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 4、Rule 6、Rule 7、Rule 11。与退役 manifest 一起移除旧表存在性约束，不弱化其他任何表或校验和。
- Required change: 只有退役后历史实际一致才可上线此启动校验。
- Verification contribution: 本 Step 定向命令与 G09。证明 移除旧表启动硬依赖并留 PAC：根因是路由指纹时不短路 validateHistory。验证缺 PAC 仍阻断
- After this file: 移除旧表启动硬依赖并留 PAC已具备该职责，继续下一个 File 或本步的整体集成验证。

#### File 6 — `DELETE stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/repository/impl/DdcOperationLogRepositoryImpl.java`

- Purpose: 移除已切断的旧表实现
- Symbols: DdcOperationLogRepositoryImpl
- Repository evidence: 现有 DdcOperationLogRepositoryImpl 启动/编译引用和 Step 4 写切断证据
- Dependencies and consumers: 静态搜索后只有不可变 SQL、PAC 迁移脚本和兼容制品代表旧来源
- Why now: File 5 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: DdcOperationLogRepositoryImpl。移除已切断的旧表实现按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 确认 Step 4 已剪业务调用，移除此实现 Bean
- Error and edge behavior: 仍有 live consumer 或 CI generated 引用则补切换复验，不批准 DROP。旧制品不再允许读写该表
- Standards impact: MC-NAME-001、MC-MODEL-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，POJO Lombok、不可变值对象及 Egon 转换约束，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 3、Rule 11。确认 Step 4 已剪业务调用，移除此实现 Bean。
- Required change: 删除该旧实现路径，保留已应用历史及新组件对应类型。
- Verification contribution: 本 Step 定向命令与 G09。证明 移除已切断的旧表实现：仍有 live consumer 或 CI generated 引用则补切换复验，不批准 DROP。旧制品不再允许读写该表
- After this file: 旧引用清退后该路径不再进入编译或扫描，继续验证 §9.4。

#### File 7 — `DELETE stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/repository/DdcOperationLogRepository.java`

- Purpose: 移除已切断的旧表实现
- Symbols: DdcOperationLogRepository
- Repository evidence: 现有 DdcOperationLogRepository 启动/编译引用和 Step 4 写切断证据
- Dependencies and consumers: 静态搜索后只有不可变 SQL、PAC 迁移脚本和兼容制品代表旧来源
- Why now: File 6 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: DdcOperationLogRepository。移除已切断的旧表实现按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 证明没有注入 consumer 后删旧接口
- Error and edge behavior: 仍有 live consumer 或 CI generated 引用则补切换复验，不批准 DROP。旧制品不再允许读写该表
- Standards impact: MC-NAME-001、MC-MODEL-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，POJO Lombok、不可变值对象及 Egon 转换约束，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 3、Rule 11。证明没有注入 consumer 后删旧接口。
- Required change: 删除该旧实现路径，保留已应用历史及新组件对应类型。
- Verification contribution: 本 Step 定向命令与 G09。证明 移除已切断的旧表实现：仍有 live consumer 或 CI generated 引用则补切换复验，不批准 DROP。旧制品不再允许读写该表
- After this file: 旧引用清退后该路径不再进入编译或扫描，继续验证 §9.4。

#### File 8 — `DELETE stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/dao/DdcOperationLogMapper.java`

- Purpose: 移除已切断的旧表实现
- Symbols: DdcOperationLogMapper
- Repository evidence: 现有 DdcOperationLogMapper 启动/编译引用和 Step 4 写切断证据
- Dependencies and consumers: 静态搜索后只有不可变 SQL、PAC 迁移脚本和兼容制品代表旧来源
- Why now: File 7 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: DdcOperationLogMapper。移除已切断的旧表实现按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 确认 package scanning 无硬引用，删除旧 Mapper
- Error and edge behavior: 仍有 live consumer 或 CI generated 引用则补切换复验，不批准 DROP。旧制品不再允许读写该表
- Standards impact: MC-NAME-001、MC-MODEL-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，POJO Lombok、不可变值对象及 Egon 转换约束，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 3、Rule 11。确认 package scanning 无硬引用，删除旧 Mapper。
- Required change: 删除该旧实现路径，保留已应用历史及新组件对应类型。
- Verification contribution: 本 Step 定向命令与 G09。证明 移除已切断的旧表实现：仍有 live consumer 或 CI generated 引用则补切换复验，不批准 DROP。旧制品不再允许读写该表
- After this file: 旧引用清退后该路径不再进入编译或扫描，继续验证 §9.4。

#### File 9 — `DELETE stellar-enclosure-tianshu-admin/src/main/resources/mybatis/mapper/tianshu/DdcOperationLogMapper.xml`

- Purpose: 移除已切断的旧表实现
- Symbols: DdcOperationLogMapper XML
- Repository evidence: 现有 DdcOperationLogMapper XML 启动/编译引用和 Step 4 写切断证据
- Dependencies and consumers: 静态搜索后只有不可变 SQL、PAC 迁移脚本和兼容制品代表旧来源
- Why now: File 8 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: DdcOperationLogMapper XML。移除已切断的旧表实现按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 与 DAO 同候选提交删除，检查 no fallback 路径和旧 SQL
- Error and edge behavior: 仍有 live consumer 或 CI generated 引用则补切换复验，不批准 DROP。旧制品不再允许读写该表
- Standards impact: MC-NAME-001、MC-MODEL-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，POJO Lombok、不可变值对象及 Egon 转换约束，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 3、Rule 11。与 DAO 同候选提交删除，检查 no fallback 路径和旧 SQL。
- Required change: 删除该旧实现路径，保留已应用历史及新组件对应类型。
- Verification contribution: 本 Step 定向命令与 G09。证明 移除已切断的旧表实现：仍有 live consumer 或 CI generated 引用则补切换复验，不批准 DROP。旧制品不再允许读写该表
- After this file: 旧引用清退后该路径不再进入编译或扫描，继续验证 §9.4。

#### File 10 — `DELETE stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/convertor/DdcOperationLogConverter.java`

- Purpose: 移除已切断的旧表实现
- Symbols: DdcOperationLogConverter
- Repository evidence: 现有 DdcOperationLogConverter 启动/编译引用和 Step 4 写切断证据
- Dependencies and consumers: 静态搜索后只有不可变 SQL、PAC 迁移脚本和兼容制品代表旧来源
- Why now: File 9 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: DdcOperationLogConverter。移除已切断的旧表实现按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 确认原 MapStruct 生成类只由旧实现消耗，移除源编译声明
- Error and edge behavior: 仍有 live consumer 或 CI generated 引用则补切换复验，不批准 DROP。旧制品不再允许读写该表
- Standards impact: MC-NAME-001、MC-MODEL-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，POJO Lombok、不可变值对象及 Egon 转换约束，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 3、Rule 11。确认原 MapStruct 生成类只由旧实现消耗，移除源编译声明。
- Required change: 删除该旧实现路径，保留已应用历史及新组件对应类型。
- Verification contribution: 本 Step 定向命令与 G09。证明 移除已切断的旧表实现：仍有 live consumer 或 CI generated 引用则补切换复验，不批准 DROP。旧制品不再允许读写该表
- After this file: 旧引用清退后该路径不再进入编译或扫描，继续验证 §9.4。

#### File 11 — `DELETE stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/bo/DdcOperationLogBO.java`

- Purpose: 移除已切断的旧表实现
- Symbols: DdcOperationLogBO
- Repository evidence: 现有 DdcOperationLogBO 启动/编译引用和 Step 4 写切断证据
- Dependencies and consumers: 静态搜索后只有不可变 SQL、PAC 迁移脚本和兼容制品代表旧来源
- Why now: File 10 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: DdcOperationLogBO。移除已切断的旧表实现按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 只删除旧普通日志值对象
- Error and edge behavior: 仍有 live consumer 或 CI generated 引用则补切换复验，不批准 DROP。旧制品不再允许读写该表
- Standards impact: MC-NAME-001、MC-MODEL-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，POJO Lombok、不可变值对象及 Egon 转换约束，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 3、Rule 11。只删除旧普通日志值对象。
- Required change: 删除该旧实现路径，保留已应用历史及新组件对应类型。
- Verification contribution: 本 Step 定向命令与 G09。证明 移除已切断的旧表实现：仍有 live consumer 或 CI generated 引用则补切换复验，不批准 DROP。旧制品不再允许读写该表
- After this file: 旧引用清退后该路径不再进入编译或扫描，继续验证 §9.4。

#### File 12 — `DELETE stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/po/DdcOperationLogPO.java`

- Purpose: 移除已切断的旧表实现
- Symbols: DdcOperationLogPO
- Repository evidence: 现有 DdcOperationLogPO 启动/编译引用和 Step 4 写切断证据
- Dependencies and consumers: 静态搜索后只有不可变 SQL、PAC 迁移脚本和兼容制品代表旧来源
- Why now: File 11 后接入该项。前置的合同、夹具与资源已在本步前序或依赖 Step 可定位。
- Contract/signature changes: DdcOperationLogPO。移除已切断的旧表实现按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 删除旧 ORM 表模型，同时检查扫描不会要求旧表
- Error and edge behavior: 仍有 live consumer 或 CI generated 引用则补切换复验，不批准 DROP。旧制品不再允许读写该表
- Standards impact: MC-NAME-001、MC-MODEL-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。角色命名和文件一致，POJO Lombok、不可变值对象及 Egon 转换约束，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 1、Rule 3、Rule 11。删除旧 ORM 表模型，同时检查扫描不会要求旧表。
- Required change: 删除该旧实现路径，保留已应用历史及新组件对应类型。
- Verification contribution: 本 Step 定向命令与 G09。证明 移除已切断的旧表实现：仍有 live consumer 或 CI generated 引用则补切换复验，不批准 DROP。旧制品不再允许读写该表
- After this file: 旧引用清退后该路径不再进入编译或扫描，继续验证 §9.4。

#### File 13 — `MODIFY stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/repository/DdcPublishPersistenceContractTest.java`

- Purpose: 剔除仅验证旧普通 PO 的契约项
- Symbols: DdcOperationLog 条目
- Repository evidence: 现有 case DdcOperationLog 对逻辑表映射和字段，其他领域仍应保留
- Dependencies and consumers: 退役候选 CI 和旧脚本历史测试
- Why now: 在生产行为变化前准备和执行真实断言，可编译的声明不计实现。
- Contract/signature changes: DdcOperationLog 条目。剔除仅验证旧普通 PO 的契约项按目标 Spec 对应接口、行字段或测试契约。
- Input/output and state mapping: 只删除旧普通日志的 runtime carrier 断言，固定旧 migration fixture 仍存在
- Error and edge behavior: 任何其他旧行为回归不随清理一起删除，失败返回具体 File
- Standards impact: MC-VALID-001、MC-JSON-001、MC-ARCH-001、MC-SCOPE-001、MC-TEST-001。使用 Jakarta 约束、边界组和明确负例，Jackson 协议与安全字段一致，保留已批准三层和组件边界。
- Literal rule enforcement: Rule 2、Rule 6、Rule 11。只删除旧普通日志的 runtime carrier 断言，固定旧 migration fixture 仍存在。
- Required change: 让保留的原业务 mapper 继续有契约检查。
- Verification contribution: DdcPublishPersistenceContractTest 作为本步先行验证。本 Step 定向命令与 G09。证明 剔除仅验证旧普通 PO 的契约项：任何其他旧行为回归不随清理一起删除，失败返回具体 File
- After this file: 用最小合同运行并观察本步的缺行为 RED。有对应实现后以原断言重新检查 GREEN。

- Validation working directory: `/Users/mario/SelfProject/Stellar-Enclosure/tianshu-service`
- Verification command: `./mvnw -B -ntp -Dmaven.repo.local="$EGON_AUDIT_PLAN_M2" -pl stellar-enclosure-tianshu-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DdcTianshuDdlContractTest,DdcPermissionMetadataBootstrapTest,DdcPublishPersistenceContractTest test`。运行及证据顺序见 §8。
- Expected result: 候选命名测试通过。实际退役 2 个历史、全部 role startup 与 V001 回执一致证明，独立环境不复用生产
- Failure returns to: 工具现有逻辑无法证明原指纹兼容，结论为保留旧物理表/声明，单独退役设计后续审查
- Completion criteria: 保留或删除的选择由原 Spec 门决定。若退役不通过保留旧台账、证明零普通使用，跳过 commit
- Rollback: 退役前可撤候选差异。实际已删只走前向修复，兼容回退制品不再适用
- Commit paths: `stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/migration/DdcTianshuDdlContractTest.java`、`stellar-enclosure-tianshu-admin/src/main/resources/db/egon-mp/V20261123_002__retire_tianshu_operation_log.sql`、`stellar-enclosure-tianshu-admin/src/main/resources/db/egon-mp/repository-manifest.json`、`stellar-enclosure-tianshu-admin/src/main/resources/egon-tianshu-persistence.yml`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/config/DdcDatabaseReadyValidator.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/repository/impl/DdcOperationLogRepositoryImpl.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/repository/DdcOperationLogRepository.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/dao/DdcOperationLogMapper.java`、`stellar-enclosure-tianshu-admin/src/main/resources/mybatis/mapper/tianshu/DdcOperationLogMapper.xml`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/convertor/DdcOperationLogConverter.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/bo/DdcOperationLogBO.java`、`stellar-enclosure-tianshu-admin/src/main/java/top/egon/cola/component/tianshu/admin/model/po/DdcOperationLogPO.java`、`stellar-enclosure-tianshu-admin/src/test/java/top/egon/cola/component/tianshu/admin/repository/DdcPublishPersistenceContractTest.java`
- Commit: `refactor(tianshu): retire legacy operation log storage after validation`

## 8. Test, Validation, and Quality Gates

### 8.1 Named gates and commands

| Gate/order | Working directory | Command or method | Scope | Expected result | Failure returns to | Requirements/runtime boundary |
| --- | --- | --- | --- | --- | --- | --- |
| G01 capture RED/GREEN | C | `./mvnw -B -ntp -Dmaven.repo.local="$EGON_AUDIT_PLAN_M2" -pl stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=OperationLogCaptureTest,EgonColaMybatisPlusAutoConfigurationTest test` | 真代理、嵌套、禁用、重载、源回滚 | RED 为缺采集。GREEN 命名用例通过，构造参数 Qualifier 和正确事务顺序有证据 | Step 1 Recorder/Advisor | REQ-001/002/003，组件 |
| G02 MP shape and 2×2 | C | `./mvnw -B -ntp -Dmaven.repo.local="$EGON_AUDIT_PLAN_M2" -pl stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=OperationLogPersistenceContractTest,OperationLogCaptureTest,EgonColaDdlManifestFamilyTest test`，再用 `EGON_MP_PG_SHARDING_IT=true ./mvnw -B -ntp -Dmaven.repo.local="$EGON_AUDIT_PLAN_M2" -pl stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=OperationLogPostgresReadwriteIT,EgonColaPostgreSqlReadwriteIT test` | 两目标 DDL、角色、事务、重复和读写 | 指定 IT 非跳过，定向 XML、父表和叶表真实可执行。仅固定主读被允许 | Step 2 目标装配、Mapper 或 DDL | REQ-003/004/008，真 PG |
| G02a cross-repo artifact | C 后 T | `./mvnw -B -ntp -Dmaven.repo.local="$EGON_AUDIT_PLAN_M2" -pl stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter -am -Dsurefire.failIfNoSpecifiedTests=false -DskipTests -DskipITs install`，T 执行 `./mvnw -B -ntp -Dmaven.repo.local="$EGON_AUDIT_PLAN_M2" -pl stellar-enclosure-tianshu-admin -am -Dsurefire.failIfNoSpecifiedTests=false dependency:tree -Dincludes=top.egon:stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter,top.egon:stellar-enclosure-component-transactional-outbox-starter` | 跨仓运行工件 | SHA、依赖和提交在同一隔离 Maven 库可追溯，无旧同 GAV 缓存 | Step 2 或依赖 bootstrap | REQ-001/004/008，静态构件 |
| G03 PAC RED/GREEN/real | T | `./mvnw -B -ntp -Dmaven.repo.local="$EGON_AUDIT_PLAN_M2" -pl stellar-enclosure-tianshu-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DdcPermissionMetadataBootstrapTest,DdcTianshuDdlContractTest test`，再用 `TIANSHU_AI_PERSISTENCE_IT_ENABLED=true ./mvnw -B -ntp -Dmaven.repo.local="$EGON_AUDIT_PLAN_M2" -pl stellar-enclosure-tianshu-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DdcTianshuDdlIT test` | 仅 PAC 迁出、原字节及异常 | PAC 目标用例实际在 PG 执行，普通记录零进入，异常回滚。封存 Step 3 构件用于 G07 | Step 3 SQL、CLI 或 manifest | REQ-005/006/008，隔离 PG |
| G04 host production | T | `./mvnw -B -ntp -Dmaven.repo.local="$EGON_AUDIT_PLAN_M2" -pl stellar-enclosure-tianshu-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DdcOperationLogOutboxAdapterTest,DdcConfigServiceTest,DdcPublishPreparationTest,DdcNativeConfigRolloutServiceTest,DdcConfigWorkerApplicationTest,DdcI18nPageServiceTest,DdcI18nSourceImportServiceTest,DdcListTemplateServiceTest,DdcNamespaceExportServiceTest,DdcAdminOutboxDispatchConfigurationTest,DdcProfileParityTest,DdcLegacyImportContractTest test` | 六处业务原写替换、实际 Publisher、持久配置 | 新源事务行为与旧 API 回归、成功/失败入队以及无旧读写一致 | Step 4 adapter、现有业务或配置 | REQ-001/002/003/005/008，模块 |
| G05 REST / PAC | T | `./mvnw -B -ntp -Dmaven.repo.local="$EGON_AUDIT_PLAN_M2" -pl stellar-enclosure-tianshu-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DdcOperationLogWebContractTest,DdcOperationLogQueryServiceTest,DdcAdminSecurityIntegrationTest,DdcAdminUiI18nCatalogTest test`，再用 `./mvnw -B -ntp -Dmaven.repo.local="$EGON_AUDIT_PLAN_M2" -pl stellar-enclosure-tianshu-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Ppermission-producer -DskipTests verify` | 两个 API、鉴权、真实 OpenAPI、Java manifest | 200/400/401/403/404/500/503、no-store、字段白名单。文档和权限由同版制品导出 | Step 5 query/security/manifest | REQ-002/004/007，模块及产物 |
| G06 Web | W | `node scripts/sync-i18n-fixtures.mjs --manifest "$AUDIT_TIANSHU_MANIFEST" --check`，`npm test -- src/pages/OperationLogsPage.test.tsx src/i18n/catalogCoverage.test.ts src/permissions/access.test.tsx src/api/client.test.ts && npm run typecheck && npm run lint` 及 `npm run build` | 页/抽屉、空态、错误、授权、游标与请求取消 | 两种语言覆盖。所有指定用例通过，实际 PAC artifact record 发布前可复验 | Step 6 页面或 Step 5 源契约 | REQ-002/007，模块/工件 |
| G07 cutover and capacity | T | `TIANSHU_OPERATION_LOG_IT_ENABLED=true ./mvnw -B -ntp -Dmaven.repo.local="$EGON_AUDIT_PLAN_M2" -pl stellar-enclosure-tianshu-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DdcOperationLogCutoverIT test`，再执行 §8.2 和 §9.3 的跨制品演练 | 独立业务源、Admin、Publisher、审计两主两副和现有 Outbox | 无跳过地得到物理/时序证明，预算符合批准基线，原回执与回退可复现 | 相应 Step 2–6，停止上线 | REQ-003/004/005/006/007/008，隔离运行 |
| G08 static + regression | C、T、W | C `./mvnw -B -ntp -Dmaven.repo.local="$EGON_AUDIT_PLAN_M2" -pl stellar-enclosure-component-common/stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter -am -Dsurefire.failIfNoSpecifiedTests=false test`，T `./mvnw -B -ntp -Dmaven.repo.local="$EGON_AUDIT_PLAN_M2" -pl stellar-enclosure-tianshu-admin -am -Dsurefire.failIfNoSpecifiedTests=false test` 及 `./mvnw -B -ntp -Dmaven.repo.local="$EGON_AUDIT_PLAN_M2" -pl stellar-enclosure-tianshu-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DdcArchitectureContractTest,DdcProfileParityTest test`，W `npm test && npm run typecheck && npm run lint`。每仓 `git diff --check` 与暂存路径复核 | 全量模块、原 IAM/契约、不变历史 | 退出 0。人工检查 touched Java 禁用 Date/Calendar/SimpleDateFormat、敏感日志和未经授权 POM 变化。单列 profile 跳过 | 所有记录的 owning Step | 全 REQ，静态/已选测试 |
| G09 optional retire | T | `./mvnw -B -ntp -Dmaven.repo.local="$EGON_AUDIT_PLAN_M2" -pl stellar-enclosure-tianshu-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DdcTianshuDdlContractTest,DdcPermissionMetadataBootstrapTest,DdcPublishPersistenceContractTest test` 和 §9.4 | 双实际 DDL 历史、退役制品在隔离库启动 | 两版史都兼容且 guard 实际启用才通过，否则保留 | Step 8 File 1 或另行退役 | REQ-005/006/008，隔离运行 |

### 8.2 MP 两主两副本实证

| Order | Fixture/failure | Evidence required | Pass condition |
| --- | --- | --- | --- |
| 1 角色与拓扑 | 独立业务主组。审计 slot 0→replica 0、slot 1→replica 1 | 四个连接目标核验真实角色、配对的系统标识及回放位置。记录源/目标 dataSource、Factory、Txn 与指纹 | replica 实际 recovery、与该主物理 streaming。错角色/错绑定拒启，DDL 只在指定主组 |
| 2 物理落点 | 至少两个租户和四个合法 tenant/id 组合，两个月 | 根键 bucket、实际父表/叶表、事务 SQL 归属、写前后行数。管理连接只查验证证据 | 两主各两桶真实写到，fact/receipt 同主同桶，无源事务接目标库、跨主或广播 |
| 3 副本和 query | 等每主已提交 WAL 各自回放再读，然后暂停一副本 | list/detail 实际 SQL、limit、时间裁剪与 EXPLAIN、两桶同时间不同 ID 归并和原毫秒以外的微秒 | 列表最多两桶各 limit+1，精确详情一个物理组/桶/月，副本异常整页 503 无降级，另一组可查 |
| 4 投递一致性 | 同 Event 并发、月末与次月、变动 payload/id。注入收件后失败、提交未知、源 ACK 丢失 | 源消息 ID、source eventId、receipt/fact 及原技术状态轨迹。目标事务前后 row delta | 源回滚零成功。相同身份最终一事实一凭证，变形永久冲突。未知 ACK 使用旧身份确认，无孤儿 |
| 5 主组故障及 DDL | 停组 1 目标、恢复。slot 0 DDL 成功后 slot 1 失败，再验证坏哈希和 commit unknown | 各组 history 与 SHA、角色、进度与重投相同 ID | 组 0 正常推进。下次按真实已提交前缀续跑，一目标版本只生效一次 |
| 6 容量及保留 | 同负载 baseline/开启后，均衡+热点租户，按实际典型与上限详情 | 业务和审计 p99、每事件 SQL、WAL/池等待、积压和最老年龄、副本 lag，当月及后两月分区和保留边界 | 预算以发布输入为准。失败不会跳月，过窗拒绝，积压可恢复。无预算或证据保持禁用 |

### 8.3 Reproducible evidence handoff

按 commit、镜像/JAR/权限产物 digest、SQL 版史和测试证据关联。现有组件单元测试及隔离运行结果分开。对应的集成跳过、模拟数据库和未绑定真正 Spring Factory 的结果不能当作验收。日志只保存非敏感键、有限指标维度和 trace。临时 `scripts/work/2026-11-23-04-18-operation-log-verify.sh` 在 Step 7 沉淀运行手册和报告后删除。

## 9. Migration, Compatibility, Rollout, and Rollback

### 9.1 Migration and transaction boundaries

| Asset | When and where | Verification and recovery |
| --- | --- | --- |
| C 审计 slot 0/1，各自 V20261123_001 | 逐个显式 PRIMARY 安装，各创建自己的两桶事实父表、UTC 月叶、receipt 和索引 | 现有 schema advisory lock。确认 target identity 和 hash，脚本/版史同事务。未知提交以新连接先验账，已成功组保持历史。副本只随流复制 |
| T PAC V20261123_001 | 源业务组，暂停旧 Bootstrap CLI。表从原有效 PAC 行迁专用回执 | 双向核对前缀、类型、UUID、digest、有效标记和原字节，重复原命令读得到同结果。异常阻断，普通行零迁移 |
| T 旧表 V20261123_002 | 独立退役候选，首次切新制品始终不应用 | 两版史、两 fingerprint、老工具退出和有效依赖核验后才能创建/应用 |

### 9.2 Direct release sequence

1. 固定当前制品、Step 3 兼容回退制品和目标新 Admin/Publisher/Web 的 Git SHA 和镜像/JAR/权限 SHA。采集前准备正式 Outbox 模式证据、目标池和 PostgreSQL 两组角色。各环境有效键相同。
2. 受管创建空审计库、两套主副与分区。暂停旧 CLI，执行并核对 PAC 迁出。新版普通历史从空开始。V001 生效后回退须使用 Step 3 的兼容版。
3. 进入短业务写入切换窗，先协调 Admin 与独立 Publisher 暂停源产生和确认旧在途工作。旧消费实例退出新通道的领取域，或必须有真实 channel 过滤可证。新版只读/handler 和机器配置先就绪。新采集关闭时也可排空已经存在的审计消息。
4. 核对目标版本、权限、旧新 claim scope/水位后再启用采集并恢复写入口。每动作有唯一新写者。业务接口照常，普通旧表不存在新读写/导入。Web 在实际源 API 就绪及角色编目后启用。新首屏空符合预期。
5. 通过源/目标指标和 §8.2 的故障、容量与读侧验证后结束切换。回退窗口保留旧物理表/route。授权的分区维护先预建再清理，按物理桶以 create_time/tenant/id 游标，每批不超过 1000。只有原事实分区已退役且允许重投窗关闭才清理相应 receipt。

### 9.3 Executable rollback rehearsal

| Point | Action | Verification |
| --- | --- | --- |
| Before PAC DDL | 关闭新生产与审计入口候选，恢复原版本 | 原业务命令及原 CLI 正常，原版史无追加 |
| After PAC before enable | 用 Step 3 的旧行为 Admin/Worker + 新 CLI，在 V001 已应用的同一数据库启动 | 验 raw 老制品会拒绝 extra history，而兼容制品真正启动并读原回执字节。新普通审计表从空开始 |
| After new events before drop | 暂停新源，记队列与 in-flight lease。由兼容的新 handler 处理可投事件，保留未处理原状态，并证明旧 worker 不抢新 channel。隔离证据缺失时维持暂停 | 对照源原 messageId、ack、receipt/fact。允许重投同一事件，禁止丢弃源消息、改版本或自动驱动 DEAD |
| Restore old behavior | 仅恢复 Step 3 兼容制品与新版 PAC CLI，撤旧 Web 新入口 | API、回执和指标均正常。新审计库和新版史保留。旧行为制品期间单独采样普通写者，恢复时不补旧数据 |
| Re-enable | 重新按 §9.2 确认角色和唯一写者，然后重开新采集 | 保留下线/回退/回切 SHA、水位与物理表证据，验证新增事实正确且无多写 |

### 9.4 Retirement gate

确认旧 Admin/Publisher/CLI 已退出。比对测试真实 YAML fingerprint、public.ddl_history 与 egon_outbox.ddl_history 的实际内容。DdcDatabaseReadyValidator 精确比较历史条数与指纹，previous-route-exclusions 仅支持可证加表，不是删路由授权。当前未发现删旧路由后兼容两份历史的证据。未通过时保留旧物理表和 route 声明，跳过 Step 8 SQL/提交，保持 Steps 1–7 的首次切新结论。通过才引入独立 V002，实际删除后优先向前修复。

## 10. Requirement-to-Step Traceability Matrix

| Requirement | Spec acceptance | Owning Steps/files | Validation gates | Completion evidence |
| --- | --- | --- | --- | --- |
| REQ-001 | §4/7.3.1/9.2.3 真实代理与显式模板均产生一条命令事实。关闭后零 SQL/入队，嵌套不重复 | Steps 1、4，§5/7 路径 | TEST-001/003，见 §8 | 组件、配置、宿主事务和业务入口 的工件与定向报告 |
| REQ-002 | §4/7.3.1/9.2.4/10 HTTP 事件还能区分桥接和重载。后台使用实际执行方法且不虚构 HTTP。字段安全与时间精度保持 | Steps 1、4、5、6，§5/7 路径 | TEST-002/010，见 §8 | 共用快照、天枢来源适配、API 与 UI 的工件与定向报告 |
| REQ-003 | §4/7.3.3/9.2.4 源回滚无成功事实。目标不可用恢复后能补投。已提交未 ACK 的目标只落一次 | Steps 1、2、4、7，§5/7 路径 | TEST-001/003/004，见 §8 | 源事务、Outbox 本地 channel、目标 receipt+fact 的工件与定向报告 |
| REQ-004 | §4/7/9/11 真实 2×2 主副、每主两桶、UTC 月裁剪、重复跨月冲突以及精确详情 | Steps 2、4、5、7，§5/7 路径 | TEST-004/007/009/010，见 §8 | MP 托管、路由、读合并及真实物理验证 的工件与定向报告 |
| REQ-005 | §4/8.3/11.2.4/16 普通旧数据 0 导入、无双读双写，新首屏空，且新写表唯一。旧表物理退役按独立门 | Steps 3、4、7、8，§5/7 路径 | TEST-005/009，见 §8 | 源调用及 legacy 导入、上线和退役隔离 的工件与定向报告 |
| REQ-006 | §4/11.2.3/16 复用原锁、原操作键和 fingerprint。旧表删时仍返回原结果字节，不重复初始化 | Steps 3、7、8，§5/7 路径 | TEST-006/010，见 §8 | 受管 SQL、CLI、验收制品 的工件与定向报告 |
| REQ-007 | §4/9/12 真实两个 GET、字段权限及独立错误。UI 保留游标精度并丢弃旧租户迟到结果 | Steps 5、6、7，§5/7 路径 | TEST-007/008，见 §8 | Web/PAC/springdoc/页面/只读 Mapper 的工件与定向报告 |
| REQ-008 | §4/11/14–16 两主分别续跑。已应用版本/源指纹受保护。峰值预算和退役前回退可核实 | Steps 2、3、4、7、8，§5/7 路径 | TEST-004/005/009，见 §8 | 应用与组件版史门、手册、容量和演练 的工件与定向报告 |

## 11. Risks, Blockers, and User Decisions

| ID | Risk or decision | Impacted Steps/files | Evidence | Owner | Status/action |
| --- | --- | --- | --- | --- | --- |
| R-01 | MP-Ext 生成处理器未启用 | Step 2 POM 和 Converter | C root 默认处理器无 MapStruct，用户已批准两项同版本，执行时另确认 provided API | Components | 明确已批准。验证有效 POM 与 generated class |
| R-02 | 第二 Factory 抢占业务或出站默认 Bean | Steps 2/4 | ContractValidator 遍历所有 Factory。业务 source manager 具名 | Components / 天枢 | G02/G04 真实容器验证，失败保持禁用 |
| R-03 | 原版制品不认 PAC 版史，旧 worker 抢领审计 | Steps 3/4/7 | 精确 validateHistory 与 Admin/Publisher 实际 channel 配置 | 天枢发布 | 以兼容 V001 制品和实际过滤矩阵演练 |
| R-04 | 本地产物与制品仓同 GAV 不同内容 | G02a、G07 | C/T README 均要求发布坐标不可变 | 发布方 | 精确记录摘要、使用隔离 Maven 与已批准正式版本 |
| R-05 | 热租户及副本滞后拖累实际写读 | G02/G07 | 每租户仅一主组，两桶同主。目标首写有 receipt 和 fact | DBA | 容量预算及真实部署证据为启用门 |
| R-06 | PAC 生产产物发布了但角色未启用 | Steps 5/6/7 | 现有权限工具链要求 pinned 证据与有效编目 | 权限方 | 验真实权限，禁止用 fixture 宣称上线 |
| R-07 | 退役导致两套历史不一致 | Step 8 | 尚无兼容删除路由的证据 | DBA / 天枢 | 保留原表和 route 是 Spec 已允许路径。退役另过门 |
| R-08 | 真实 ON CONFLICT 或分页归并与 ShardingSphere 不兼容 | Steps 2/5/7 | SQL 资源检查不能证明真正 PostgreSQL 执行 | Components | 以 G02/G07 为执行门。需要超出 Spec 才能修时回 Spec |

## 12. Review and Acceptance

### 12.1 Original requirement fidelity

逐项覆盖 REQ-001 至 REQ-008、HTTP/方法定位、审计两主两副本、新版起步零普通历史、PAC 原业务回执，以及实际回退步骤。

### 12.2 Spec consistency

§4.5 已核对必要性、参数来源和交互成本。§6.4 记录六个仓库约束和实现细化点，保留原契约与宿主职责。未证可安全退役时采用已批准的留表路径。

### 12.3 Repository executability

§5/7 对应真实仓、当前已验证的复用类型、逐文件顺序、编译条件、定向验证和分步提交。所有上线受管版本及现有 Outbox 文档保持。

### 12.4 Test and release completeness

测试顺序包含有意义 RED、最小 GREEN、两主两副本角色和物理路由、真实 ACK 丢失、PAC 原字节、同库回退和重新启用。本文只规定后续运行的验证步骤。本轮执行的是文档静态校验。

### 12.5 Blocking Manual Check

| Check ID | Applicability | Status | Evidence | Finding | Required action/exception |
| --- | --- | --- | --- | --- | --- |
| MC-ARCH-001 | Applicable | PASS | §4.7、DdcArchitectureContractTest | 保持现有三层与组件适配 | G08 执行 |
| MC-REUSE-001 | Applicable | PASS | §4.7 ledger、Bootstrapper、TransactionalOutbox | 复用既有 MP、Outbox、Spring 能力 | G02/G04 |
| MC-DEP-001 | Applicable | PASS | §2.1、PC-001 与已有 RPC Tianshu POM | 两个处理器和 provided API 已精确批准，无运行时新增 | G02/G02a 有效 POM |
| MC-NAME-001 | Applicable | PASS | §5/7 定义 Event、VO、PO、DAO | 语义后缀与真实路径闭合 | G01/G02/G05 |
| MC-VALID-001 | Applicable | PASS | Rule 2 与 Steps 1/2/4/5 | 携带可信上下文，代理/PO 分组/重入逐层覆盖 | G01/G02/G04/G05 |
| MC-MODEL-001 | Applicable | PASS | Rule 3、Spec §11、Step 2 PO | 事实和接收键、PAC 业务永久键独立于软删除 | G02/G03 |
| MC-CONVERT-001 | Applicable | PASS | OperationLogConverter、DdcOperationLogViewConverter | 生成不可逆投影，摘要及详情最少字段 | G02/G05 |
| MC-LOG-001 | Applicable | PASS | §4.7 与 §8.3 | 仅有限安全日志及追踪字段 | G01/G04/G07 |
| MC-BEAN-001 | Applicable | PASS | 两仓 lombok.config、Step 2/4 具名 Bean | constructor 和 Qualifier 传播可验证 | G01/G02/G04/G05 |
| MC-UTIL-001 | Applicable | PASS | §4.7/4.8 | 复用 JDK/Spring/Jackson 和标准 builder | G08 |
| MC-JSON-001 | Applicable | PASS | §4.8 Rule 6、具体 ResultRecord/事件 | 稳定 code、UTC、BigInt 字符串与安全体 | G04/G05/G06 |
| MC-TIME-001 | Applicable | PASS | §4.9、TEST-010 | UTC 月、微秒、原始游标、PAC 原文本 | G02/G03/G05/G06/G07 |
| MC-CONFIG-001 | Applicable | PASS | Step 4 共享 YAML、DdcProfileParityTest | 默认关闭，生效键集合对等 | G04/G08 |
| MC-PATTERN-001 | Applicable | PASS | §4.7 Strategy/Adapter/Projection | 复杂模式与简单 PAC 值分别落实 | G01/G02/G04/G05 |
| MC-SCOPE-001 | Applicable | PASS | §2.3/6.1 和各步 commit paths | 分仓限定，退役独立 gate，普通历史不迁 | 各 Step 暂存复核 |
| MC-TEST-001 | Applicable | PASS | §8–10 及具名选择器 | 真实 failover/回退设计和非跳过判定 | 实施期收证 |
| MC-BLOCKER-001 | Applicable | PASS | §11 与已有用户确认 | 规划所需取舍完成，发布环境证据和可选退役独立 | 待用户审核 Plan |

### 12.6 Final verdict

PASS — Ready for user review

## Java / CQE contract review

| Concern | Decision and evidence | Execution boundary |
| --- | --- | --- |
| POJO/value-object/enum | §4.8 普通类 Lombok、EgonModel、稳定显式码 | G01/G02/G05 |
| Components / MP starter | Bootstrapper、role guard、Mapper、受管 DDL 复用 | G02/G07 |
| Annotation-driven validation | Rule 2，代理校验、手工入口和 Outbox 重入 | G01/G04/G05 |
| Business uniqueness / soft-delete | 事实、接收键及 PAC 永久键。原业务唯一与逻辑删除范围保持 | G02/G03/G08 |
| CQE | Command 同源事务。Query 纯读。Event 经已有本地 Outbox | G04/G05/G07 |
| Architecture | 既有三层和共享基础设施 | G08 |

## Backend generation and dependency evidence

现有项目和共享组件采用传统三层，受管 PostgreSQL 分区脚本及读侧均属既有模块增量工作。MapStruct 走批准的处理器，Web fixture 用现有生成工具，权限清单由现有 producer 生成，均按真实源对比。具体坐标及审批见 PC-001。正式环境的验证和发布由后续实施交付。
