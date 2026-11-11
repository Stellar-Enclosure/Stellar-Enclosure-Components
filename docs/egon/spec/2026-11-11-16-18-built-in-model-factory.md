# T4-01 Agent Flow 内置模型工厂检查规格

| Field | Value |
| --- | --- |
| Document | docs/egon/spec/2026-11-11-16-18-built-in-model-factory.md |
| Template Version | 9 |
| Archetype Cleanup | Not applicable |
| Status | Review |
| Type | Feature |
| Complexity | Simple |
| Complexity Drivers | None |
| Created | 2026-11-11 16:18 Asia/Shanghai |
| Updated | 2026-11-11 16:18 Asia/Shanghai |
| Owner | Stellar Enclosure Components |
| Repository | Stellar-Enclosure-Components |
| Scope | Agent Flow compiler built-in model factory predicate |
| Change Surface | One existing production class and one existing test |
| Affected Chapters | §7, §8, §9, §14, §15, §16 |
| Source Requirement | [主计划 T4-01](../../../../docs/plans/agent-framework-upgrade-plan.md) |
| Baseline Revision | a9539edf1c420132fff04024a51ee7f6d4faff2a |
| Amends | None |
| Supersedes | None |
| Depends On | [原编译模型身份 §7](./2026-11-11-15-57-compiled-model-identity.md#7-architecture-design) |
| Related Specs | None |
| Related Plans | [本步骤](../plan/2026-11-11-16-19-built-in-model-factory.md) |

## 1. Summary

在既有 AgentFlowFactory 增加只读静态谓词。仅当工厂本身及其私有模型适配工厂分别是确切内置类时返回 true。宿主可在严格发布就绪判断中先检查编译器来源，原普通编译继续支持覆盖。

## 2. Background and Current State

| Evidence ID | Exact source | Observed fact |
| --- | --- | --- |
| EVD-001 | AgentFlowFactory#compileRoot | 按名称从宿主 map 选 ChatModel，调用私有字段 modelAdapterFactory#create。 |
| EVD-002 | AgentFlowAutoConfiguration#agentFlowFactory/#springAiModelAdapterFactory | 宿主可按原 Bean 名替换整个工厂或模型工厂。外层确切类无法证明内部来源。 |
| EVD-003 | SpringAiModelAdapterFactory#matchesCompiledModel | 既有编译产物身份校验检查名称与同一 ChatModel 引用，发生在编译后。 |
| EVD-004 | AgentFlowFactoryTest | 既有真实叶子与工作流编译路径具备有限回归。 |

### 2.5 Archetype example cleanup

Cleanup disposition: Not applicable

Reason: 增量修改现有可复用 Component，未创建新业务工程。

## 3. Goals and Non-goals

对实际 compiler 的两层 Java 工厂来源提供独立的本地预检查。调用方自己的 validator、ChatModel getter、策略、部署与后续编译结果各有独立边界。

### 3.3 Change Surface and Design Depth

| Area/layer | Disposition | Exact repository evidence | Changed or preserved behavior/contract | Required Spec treatment | Chapter(s) |
| --- | --- | --- | --- | --- | --- |
| Component runtime factory | Affected | EVD-001–004 | 只读静态谓词及有限回归 | 精确 Java 合同、断言和兼容范围 | §7, §8, §9, §14, §15, §16 |
| Host runtime and auto configuration | Context-only | EVD-002/003 | 覆盖关系、Bean、供应商及配置继续由原主体持有 | 现有边界 | §18 |

## 4. Requirements and Acceptance Criteria

| ID | Requirement | Priority | Observable acceptance | Source |
| --- | --- | --- | --- | --- |
| REQ-001 | 外层与私有内层分别确切内置 | Must | 确切双工厂为 true；任一缺失或覆盖类型为 false。 | 主计划 T4-01 |
| REQ-002 | 查询本身不执行覆盖或编译 | Must | 使用只读 null/类型检查；两个有副作用的覆盖方法在查询后计数仍为零。 | 主计划 T4-01 |
| REQ-003 | 保持普通编译覆盖行为 | Must | 使用同一对覆盖工厂直接调用原编译入口仍构造叶子，调用计数各一，无模型请求。 | 主计划 T4-01 |
| REQ-004 | 可复验的组件边界 | Must | 两个独立反证、七个定向测试、实际包字节码检查、精确五路径。 | 主计划 T4-01 |

### 4.2 Use-case analysis

| Actor ID | Actor | Context |
| --- | --- | --- |
| ACTOR-001 | Local host | 持有已装配的 AgentFlowFactory 对象。 |
| ACTOR-002 | Component reviewer | 核对源码及包。 |

| ID | Use case/goal | Actors | Trigger/preconditions | Main flow | Alternative/failure | Postcondition | Requirements |
| --- | --- | --- | --- | --- | --- | --- | --- |
| UC-001 | 核对两层工厂来源 | ACTOR-001 | 有本地对象或 null | 调用静态谓词 | 任一层缺失、子类或代理返回 false | 无编译副作用 | REQ-001/002/003 |
| UC-002 | 独立复核新增行为 | ACTOR-002 | 精确源码基线 | 两项反证、回归、包校验 | 任何门禁失败即返回 | 同一组件包边界 | REQ-004 |

## 5. Constraints, Assumptions, and Decisions

确切 Java 类型比较，不使用可覆盖实例探针。Spring 代理及其他派生类拒绝。现有普通编译的覆盖关系保持。

## 6. Project Technology Context

JDK 25、已有 Agent Flow 工厂模块、Lombok、ADK 与现有 JUnit/AssertJ。不引入依赖。

### 6.2 User-mandated Java rule compliance

```text
1 类名规范，必须以 java的pojo规范命名。以dao po bo vo dto query command event等结尾
2 每层之间必须被 springboot-validation 校验，复用对象使用分组校验；基于原生和自定义约束注解、@Valid、@Validated 及 ConstraintValidator 扩展实现。ValidationUtils 只承担通用手工校验，不承载全部业务校验；电话号码复用 libphonenumber。
3 Java POJO 默认使用 class，注解为 @Data @NoArgsConstructor @AllArgsConstructor @Accessors(chain = true)，按构造目标选择 @Builder 或 @SuperBuilder；父类状态参与相等性时使用 @EqualsAndHashCode(callSuper = true)。只有不可变 value object 可以使用 record，并豁免 class 的 Lombok 规范；普通实体不能因简单而使用 record。使用 MapStruct、MapStructPlus 和 egon-cola-component-common-core 的 BaseConverter（双向）或 BaseForwardConverter（不可逆投影）进行转换。
4业务类必须使用@Slf4j注解注入log对象。如果业务类被spring管理，必须指定名称，如果是单例的情况下，参考@Service("userService")。如果需要依赖注入，必须@RequiredArgsConstructor进行修饰，不要代码中写。且属性必须被@qualify修饰。
5 工具类只允许使用jdk原生、Apache Commons(commons-lang3、commons-collections4、commons-io、commons-text、commons-codec、commons-beanutils)、Guava。针对Tika按需引入。
6 JSON 使用 Spring Boot Jackson；持久化枚举值使用 @EnumValue，向前端输出的枚举值使用 @JsonValue，禁止 ordinal 作为业务编码。
7 springboot 多环境配置文件，必须保持配置一致，但值不一定一致。
9 复杂业务必须引入设计模式，不允许硬编码
10 日期相关的必须使用java.time下的实体类，不允许使用java.util下的
11 只允许现有三层结构或当前 egon-cola-archetypes 的 DDD 结构，三层结构保持现状。必须尽量复用 egon-cola-components；持久化模块必须使用 Egon COLA MP Starter，DDL 统一由 egon-mp-sdj-ext-starter 分布式管理。业务唯一键必须组合业务列与 deletedAt（数据库 deleted_at），并验证 NULL、租户及重复软删除语义。采用 CQE（Command Query Event）；Event 必须经 egon-cola-component-transactional-outbox-starter 或 MQ 中间件投递。
```

| Literal rule | Affected? | Repository evidence | Exact design decision | Files/types/interfaces | Validation/test evidence | Status/blocker |
| --- | --- | --- | --- | --- | --- | --- |
| Rule 1 | Yes | EVD-001–004 | usesBuiltInModelFactory 表达确切内置类型的谓词。 | §7/9 | TEST-001–004 | PASS |
| Rule 2 | Yes | EVD-001–004 | 外层和内层均用 getClass 引用相等，null 保守拒绝。 | §7/9 | TEST-001–004 | PASS |
| Rule 3 | No | EVD-001–004 | 无该类型的变更。 | §7/9 | TEST-001–004 | N/A |
| Rule 4 | No | EVD-001–004 | 无该类型的变更。 | §7/9 | TEST-001–004 | N/A |
| Rule 5 | Yes | EVD-001–004 | 仅 JDK 类型与引用操作。 | §7/9 | TEST-001–004 | PASS |
| Rule 6 | No | EVD-001–004 | 无该类型的变更。 | §7/9 | TEST-001–004 | N/A |
| Rule 7 | No | EVD-001–004 | 无该类型的变更。 | §7/9 | TEST-001–004 | N/A |
| Rule 9 | Yes | EVD-001–004 | 在现有工厂封装内检查私有下游工厂。 | §7/9 | TEST-001–004 | PASS |
| Rule 10 | No | EVD-001–004 | 无该类型的变更。 | §7/9 | TEST-001–004 | N/A |
| Rule 11 | Yes | EVD-001–004 | 继续使用现有 Component Agent Flow 工厂边界。 | §7/9 | TEST-001–004 | PASS |

### 6.3 Time semantics and conversion contract

Time contract: Not applicable

Reason: The static predicate only checks two Java references and their exact types, with no clock, temporal input, timeout or conversion.

## 7. Architecture Design

### 7.0 Minimum-design baseline and element-necessity audit

| Proposed element | Change | Requirements | Existing alternative | Gap | Cost | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| Static predicate | Add | REQ-001/002 | getClass of outer factory only | 无法证明私有模型工厂来源 | 一个 Java 方法 | Use |
| Existing compileRoot | Reuse | REQ-003 | 统一禁止覆盖 | 会破坏普通宿主扩展 | 无生产修改 | Use |

| Path | Network calls | Client states | Persisted evidence | Failure boundary |
| --- | --- | --- | --- | --- |
| Local predicate | 0 | 0 | 0 | 任一类型或存在性不符返回 false。 |
| Existing compile | 原行为 | 原行为 | 原行为 | 既有工厂验证和异常。 |

### 7.1 System Architecture Design

检查放在 `AgentFlowFactory` 本身，以读取已构造实例的私有 final 字段。保持原 Bean、模型工厂及编译路径。

### 7.2 High-Level Design

宿主提供实际 factory 对象。先短路缺失和外层不符，再读取私有下游 factory 的存在性和确切类型。返回 boolean，不返回或调用下游对象。

### 7.3 Detailed Design

`factory != null && factory.getClass() == AgentFlowFactory.class && factory.modelAdapterFactory != null && factory.modelAdapterFactory.getClass() == SpringAiModelAdapterFactory.class`。不调用 validate、create、compileRoot、模型 getter、runner 或外部系统。现有普通运行仍调用原覆盖。证据只说明这两个对象的类型，不承诺宿主 validator 或 ChatModel 的副作用或实际运行状态。

## 8. Package Structure and Code File Tree

| Operation | Exact path | Purpose | Step | Gate |
| --- | --- | --- | --- | --- |
| MODIFY | `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/runtime/AgentFlowFactoryTest.java` | 原编译工厂单测加确切外层/内层类型案例 | Step 1 | R1/R2/G1 |
| MODIFY | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/runtime/AgentFlowFactory.java` | 追加不执行覆盖方法的静态谓词 | Step 1 | R1/R2/G1/P1 |
| CREATE | `docs/egon/spec/2026-11-11-16-18-built-in-model-factory.md` | 固化 Java 谓词合同和宿主边界 | Step 1 | S1 |
| CREATE | `docs/egon/plan/2026-11-11-16-19-built-in-model-factory.md` | 固化定向验证和五个路径 | Step 1 | S1 |
| CREATE | `docs/egon/evidence/2026-11-11-built-in-model-factory.md` | 记录已运行的隔离证据 | Step 1 | S1 |

## 9. Interface Definitions

### 9.1 Interface Inventory

| ID | Kind | Owner | Operation | Input | Output | Requirement |
| --- | --- | --- | --- | --- | --- | --- |
| INTERNAL-001 | Internal Java | Agent Flow runtime | AgentFlowFactory.usesBuiltInModelFactory | nullable AgentFlowFactory | boolean | REQ-001/002 |

### 9.2 Per-interface Detailed Contracts

#### 9.2.1 INTERNAL-001 — Probe the exact installed compiler model factory

##### Necessity and interaction-cost decision

| Concern | Decision |
| --- | --- |
| Change classification | New additive Java static predicate |
| Independent consumer goal | 在受限发布 readiness 中核对真实编译工厂来源。 |
| Parameter ownership and derivation | 宿主传已装配实例，私有字段由组件读取。 |
| Direct/no-new-interface alternative | 仅外层类或编译后模型检查均缺少编译前的私有证据。 |
| Caller use of result | true 允许宿主继续自己的检查。 |
| Round trips and failure points | 一次本地 Java 调用，无网络和写入，任一缺失或不符为 false。 |
| Verdict | Add. REQ-001/002，EVD-001/002。 |

##### Identity and purpose

Agent Flow runtime 所有。宿主在严格模式使用实际组件装配对象，从编译前检查两层类型，且保留普通编译调用的独立语义。

##### Request parameters

一个 `AgentFlowFactory` 对象，允许 null。调用方不得用新构造替代实际装配对象来声称运行验证。

##### Success response

返回 boolean。只有外层和私有内层均为确切内置类型时为 true。无其他返回字段。

##### Error responses

任一对象缺失、外层覆盖或内层覆盖时返回 false。该短路检查不提供额外异常或诊断。

##### Interface logic for frontend and consumers

无前端调用。宿主可以在调用可扩展编译之前自行拒绝；本方法不修改组件所有者的原路径。

##### Compatibility and verification

现有普通编译函数及覆盖语义维持，工具代码和依赖未变化。验证两项独立变异及实际公共字节码。

## 10. POJO and Data Model Design

沿用原工厂类型，结果为原始 boolean。

## 11. Database Design

Relational model change: No. Java 局部检查不访问存储。

## 12. Frontend Page Design

本步骤仅涉及内部 Java 组件。

## 13. Design Patterns and Architecture Principles

在既有工厂封装内读取引用和类型，提供无状态的独立谓词。

## 14. Test Design

| Test ID | Design |
| --- | --- |
| TEST-001 | 双内置 true，两个 null 路径和两个可委托覆盖 false。 |
| TEST-002 | 分别删去外层或内层确切类型检查，单用例在对应断言失败。 |
| TEST-003 | 探针后覆盖计数为零，直接普通编译仍调用各覆盖一次，未触发模型生成。 |
| TEST-004 | 七项定向，实际包签名/字节码及五个路径。 |

## 15. Non-functional and Cross-cutting Design

谓词无 I/O、无日志、无 runner、无 DTO，仅常量数目的引用与类型比较。宿主其他可替换对象不由此检查证明。

## 16. Compatibility, Migration, Rollout, and Rollback

同一既有组件坐标，新增公共静态方法。原配置、Java 入口、日志、依赖和产物发布保持。下游用源 reactor 或验证实际解析的同版本 SNAPSHOT。回滚撤销该组件提交。

## 17. Alternatives and Decisions

检查私有字段只在原工厂内进行，避免反射和额外 getter。编译产物身份检查继续承担自己的职责。

## 18. Risks and Open Questions

宿主仍需验证自己的 validator、模型选项及运行证据。实际 Maven SNAPSHOT 安装或发布另行处理。

## 19. Traceability Matrix

| Requirement | Use case | Design | Tests |
| --- | --- | --- | --- |
| REQ-001 | UC-001 | §7/9 | TEST-001/002 |
| REQ-002 | UC-001 | §7/9/15 | TEST-003 |
| REQ-003 | UC-001 | §7/16 | TEST-003 |
| REQ-004 | UC-002 | §8/14 | TEST-004 |

## 20. Review and Acceptance

### 20.5 Blocking Manual Check

| Check ID | Applicability | Status | Evidence | Finding | Required action/exception |
| --- | --- | --- | --- | --- | --- |
| MC-ARCH-001 | Applicable | PASS | §7/9/14 | 继续使用现有 Component Agent Flow 工厂边界。 | None |
| MC-REUSE-001 | Applicable | PASS | §7/9/14 | 复用真实私有字段和现有编译测试。 | None |
| MC-DEP-001 | Applicable | PASS | §7/9/14 | 已有 Java 25、ADK、Lombok、JUnit、AssertJ。 | None |
| MC-NAME-001 | Applicable | PASS | §7/9/14 | usesBuiltInModelFactory 表达确切内置类型的谓词。 | None |
| MC-VALID-001 | Applicable | PASS | §7/9/14 | 外层和内层均用 getClass 引用相等，null 保守拒绝。 | None |
| MC-MODEL-001 | Not applicable | N/A | §7/9/14 | 没有该职责或新增边界。 | None |
| MC-CONVERT-001 | Not applicable | N/A | §7/9/14 | 没有该职责或新增边界。 | None |
| MC-LOG-001 | Not applicable | N/A | §7/9/14 | 没有该职责或新增边界。 | None |
| MC-BEAN-001 | Not applicable | N/A | §7/9/14 | 没有该职责或新增边界。 | None |
| MC-UTIL-001 | Applicable | PASS | §7/9/14 | 仅 JDK 类型与引用操作。 | None |
| MC-JSON-001 | Not applicable | N/A | §7/9/14 | 没有该职责或新增边界。 | None |
| MC-TIME-001 | Not applicable | N/A | §7/9/14 | 没有该职责或新增边界。 | None |
| MC-CONFIG-001 | Not applicable | N/A | §7/9/14 | 没有该职责或新增边界。 | None |
| MC-PATTERN-001 | Applicable | PASS | §7/9/14 | 在现有工厂封装内检查私有下游工厂。 | None |
| MC-SCOPE-001 | Applicable | PASS | §7/9/14 | 现有生产类、现有测试和三份审查文档。 | None |
| MC-TEST-001 | Applicable | PASS | §7/9/14 | 两项反证、七项回归、实际包及严格路径。 | None |
| MC-BLOCKER-001 | Applicable | PASS | §7/9/14 | 源码门禁可执行；下游正式接入和运行独立。 | None |

### 20.6 Final verdict

PASS — Ready for user review
