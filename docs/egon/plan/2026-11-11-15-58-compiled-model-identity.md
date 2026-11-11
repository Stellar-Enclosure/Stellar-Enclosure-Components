# T4-01 Agent Flow compiled model identity implementation plan

| Field | Value |
| --- | --- |
| Document | docs/egon/plan/2026-11-11-15-58-compiled-model-identity.md |
| Template Version | 5 |
| Status | Review |
| Created | 2026-11-11 15:58 Asia/Shanghai |
| Updated | 2026-11-11 15:58 Asia/Shanghai |
| Owner | Stellar Enclosure Components |
| Repository | Stellar-Enclosure-Components |
| Scope | One component-owned predicate and its existing finite test |
| Source Requirement | Master T4-01 |
| Baseline Revision | 6d26bf8724e8993b631db68c95b7238d7a93fb8c |
| Implements Spec | [Component Spec](../spec/2026-11-11-15-57-compiled-model-identity.md) |
| Spec Status | Review |
| Spec Revision | 2026-11-11 15:57 Asia/Shanghai SHA-256 8770760c2c72f952eefed7b1886b898a35bf4b840a5a198992b911484d83af7a |
| Effective Specs | [Component Spec](../spec/2026-11-11-15-57-compiled-model-identity.md) |
| Depends On Plans | None |
| Supersedes | None |
| Superseded By | None |
| Related Plans | None |

## 1. Summary

Inspect the actual compiled Agent Flow adapter through an additive Component predicate. Deliver one Component-local Step and its exact finite and package evidence.

## 2. Target Spec and Effective Design

The pinned Spec defines final adapter recognition, case-sensitive native model name and actual backing model identity. Product policy and downstream activation are separate.

## 3. Effective Requirements and Acceptance

| ID | Requirement | Observable acceptance |
| --- | --- | --- |
| REQ-001 | Identify the existing compiled Spring AI adapter. | Only the final component adapter can produce a positive result. A different BaseLlm with the same name returns false. |
| REQ-002 | Compare the exact name and ChatModel object. | Same exact name and instance returns true. Distinct value-equal model, differing names, appended whitespace and nulls return false. |
| REQ-003 | Keep the predicate pure and additive. | It accesses no options or provider, allocates no runner, adds no dependency and changes no existing creation or invocation behavior. |
| REQ-004 | Provide reproducible Component evidence. | Exact mutation tests, owner finite reactor, actual package inspection and six-path scoped evidence. |

## 4. Implementation Strategy and Dependency Order

### 4.1 Ordered strategy

Freeze baseline and six paths. Use the existing compiled-root fixture and separate mutation controls, then run the owning module source reactor and package inspections.

### 4.2 Test-first strategy

R1 removes one exact condition per isolated variant. Keep the final method signature and all other conditions. Check each single selected method's expected false assertion.

### 4.3 Sequential and parallel boundaries

R1 and G1 precede P1. Use a full isolated Component checkout for Maven or serialize the owner build with shared workspace builds. Review Spec and Plan independently.

### 4.4 Commit boundaries

One Component commit. The Tianji consumer follows after the API and tested artifact are available to the intended source or binary build.

### 4.5 Spec Simplicity and Implementation-necessity Audit

| Spec element | Spec necessity verdict/section | Current repository evidence | Direct/reuse alternative | Plan decision |
| --- | --- | --- | --- | --- |
| Adapter predicate | Required §7/9 | Known final adapter and pinned native name | Use existing factory and private model | Add methods in two existing classes. |
| Exact finite proof | Required §14 | Existing AgentFlowFactoryTest | Extend actual compiled leaf fixture | No new fixture framework. |

### 4.6 Change-unit Dependency Matrix

| Change unit | Requirements | Proof/RED point | Files | Dependency |
| --- | --- | --- | --- | --- |
| Exact predicate | REQ-001–003 | R1/G1 | 1–3 | Existing final adapter, native name and Spring AI object |
| Review and packaging evidence | REQ-004 | G1/P1/S1 | 1–6 | Exact source and package outputs |

### 4.7 Java, Spring, and Egon-COLA Implementation Standards

| Concern | Current repository evidence | Effective Spec decision |
| --- | --- | --- |
| Architecture | EVD-001/002 existing runtime factory | Add a typed predicate and private-field check. |
| Validation | EVD-003/004 final adapter, native name and model reference | Reject ambiguity without a provider or object getter. |
| Tests | EVD-005 owning Component fake | Exact mutation and compiled-leaf checks. |

#### Capability reuse ledger

| Need | Candidates inspected | Exact evidence | Fit/gap | Decision | Added dependency/custom code | Owning Step/check |
| --- | --- | --- | --- | --- | --- | --- |
| Compiled model | `AgentFlowFactory#compileRoot`, native LlmAgent | EVD-001 | Actual typed leaf | Reuse production compiler | No dependency | Step 1/G1 |
| Backing model | Final `SpringAiModelAdapter` | EVD-002/003 | Private owned object | Compare by reference within package | Two additive methods | Step 1/R1 |
| Regression | Existing AgentFlowFactoryTest | EVD-005 | Existing finite coverage | Extend two cases | No dependency | Step 1/G1 |

### 4.8 User-mandated Java Rule Implementation Matrix

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

| Literal rule | Spec source | Repository evidence | Exact files and order | Pseudocode obligations | Validation gate | Steps | Status/blocker |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Rule 1 | §6.2 | EVD-001–005 | Files 1–6 | Reuse named component runtime types and a semantic predicate. | R1/G1/P1/S1 | Step 1 | PASS |
| Rule 2 | §6.2 | EVD-001–005 | Files 1–6 | The unproxied typed predicate returns false for null, different type, name or reference. | R1/G1/P1/S1 | Step 1 | PASS |
| Rule 3 | §6.2 | EVD-001–005 | Files 1–6 | No production carrier is introduced. The test-only record is immutable with value semantics. | R1/G1/P1/S1 | Step 1 | N/A |
| Rule 4 | §6.2 | EVD-001–005 | Files 1–6 | Existing adapter/factory creation, Bean and logging annotations stay outside the predicate. | R1/G1/P1/S1 | Step 1 | N/A |
| Rule 5 | §6.2 | EVD-001–005 | Files 1–6 | Reuse Java identity and the pinned ADK typed accessor. | R1/G1/P1/S1 | Step 1 | PASS |
| Rule 6 | §6.2 | EVD-001–005 | Files 1–6 | No JSON or enumeration changes. | R1/G1/P1/S1 | Step 1 | N/A |
| Rule 7 | §6.2 | EVD-001–005 | Files 1–6 | No application properties or YAML. | R1/G1/P1/S1 | Step 1 | N/A |
| Rule 9 | §6.2 | EVD-001–005 | Files 1–6 | Reuse existing factory and adapter encapsulation. | R1/G1/P1/S1 | Step 1 | PASS |
| Rule 10 | §6.2 | EVD-001–005 | Files 1–6 | No time fields, deadline or calendar logic. | R1/G1/P1/S1 | Step 1 | N/A |
| Rule 11 | §6.2 | EVD-001–005 | Files 1–6 | Extend the existing Component runtime module without persistence, Event or API layers. | R1/G1/P1/S1 | Step 1 | PASS |

### 4.9 Time semantics and conversion contract

Time contract: Not applicable

Reason: The predicate checks immutable adapter identity and performs no I/O, clock read, lease or time conversion.

## 5. Change File Tree

| Operation | Exact path | Responsibility |
| --- | --- | --- |
| MODIFY | `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/runtime/AgentFlowFactoryTest.java` | Compile the actual leaf and assert exact model identity. |
| MODIFY | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/runtime/SpringAiModelAdapter.java` | Compare the contained ChatModel by object identity. |
| MODIFY | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/runtime/SpringAiModelAdapterFactory.java` | Expose the typed, side-effect-free predicate. |
| CREATE | `docs/egon/spec/2026-11-11-15-57-compiled-model-identity.md` | Freeze the API and proof boundary. |
| CREATE | `docs/egon/plan/2026-11-11-15-58-compiled-model-identity.md` | Freeze source hashes, test selectors and packaging gates. |
| CREATE | `docs/egon/evidence/2026-11-11-compiled-model-identity.md` | Record observed checks and outstanding owner Maven gates. |

## 6. Prerequisites, Constraints, and Plan Clarifications

Run owning gates from a full Components checkout at `6d26bf8724e8993b631db68c95b7238d7a93fb8c` with Java 25 and the existing offline repository settings. G1 and P1 use the Component root wrapper directly. No Tianji source, dependency or Maven coordinates change in this Step.

| Candidate path | SHA-256 |
| --- | --- |
| `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/runtime/AgentFlowFactoryTest.java` | `648d089b5374b9403c4820c7b56c8764fa1c5f8451bf5ea1a77f50e3e26ae1ef` |
| `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/runtime/SpringAiModelAdapter.java` | `0d1f8941ebdbb4697b033d9f431a55c70a6961384a5c4a4ddfef584b6b2b8730` |
| `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/runtime/SpringAiModelAdapterFactory.java` | `11fd0b1e27149bb878e0ffbd52087fb2647e9f2e197b1b12fde44df9bb5b47e5` |
| `docs/egon/spec/2026-11-11-15-57-compiled-model-identity.md` | `8770760c2c72f952eefed7b1886b898a35bf4b840a5a198992b911484d83af7a` |
| `docs/egon/evidence/2026-11-11-compiled-model-identity.md` | `068b3d079c2260a54e85882e3e7a7c66d5662c3a0f36ac8ec2dd2c22465e1c13` |

The frozen external `full-candidate.sha256` covers the Plan itself and the other five files without a circular digest. The existing source-coordinate is `top.egon:stellar-enclosure-component-agent-flow-starter:0.0.2-SNAPSHOT`.

## 7. Ordered File-by-file Implementation Steps

### Step 1 — Verify exact compiled Spring AI model identity

- Requirements: REQ-001, REQ-002, REQ-003, REQ-004.
- Dependencies: Existing Component Agent Flow runtime, pinned SDK and tests.
- Baseline state: The adapter owns a private model and the host can resolve the compiled ADK object.
- Observable outcome: Known adapter and exact name and object succeed. Similar objects and foreign adapters fail.
- End state: The compiled class exposes an additive typed identity predicate.
- Test-first gate: Required. R1 selects only the method targeted by each mutation.
- Manual Checks: MC-ARCH-001, MC-REUSE-001, MC-DEP-001, MC-NAME-001, MC-VALID-001, MC-UTIL-001, MC-PATTERN-001, MC-SCOPE-001, MC-TEST-001, MC-BLOCKER-001.
- Literal Rules: Rule 1, Rule 2, Rule 5, Rule 9, Rule 11.
- Ordered files:

#### File 1 — `MODIFY stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/runtime/AgentFlowFactoryTest.java`

- Purpose: Compile the actual leaf and assert exact model identity.
- Symbols: AgentFlowFactoryTest.
- Repository evidence: EVD-001–005 and Spec §7/9/14.
- Dependencies and consumers: Existing pinned ADK/Spring AI and owning Component test.
- Why now: Capture this file in the single Component identity proof.
- Contract/signature changes: Compile the actual leaf and assert exact model identity.
- Input/output and state mapping: The predicate uses a compiled object, exact name and expected reference. The output is a boolean.
- Error and edge behavior: Exercise or document mismatches, null input and no provider calls.
- Standards impact: MC-REUSE-001, MC-VALID-001, MC-SCOPE-001 and MC-TEST-001.
- Literal rule enforcement: Rule 1, Rule 2, Rule 5, Rule 9, Rule 11.
- Implementation pseudocode:

```java
var root = (LlmAgent) factory.compileRoot(flowName, leafFlow(), Map.of(bean, expected), List.of());
var compiled = root.model().flatMap(Model::model).orElseThrow();
assertThat(matchesCompiledModel(compiled, exactName, expected)).isTrue();
assertThat(matchesCompiledModel(compiled, exactName, distinctValueEqual)).isFalse();
assertThat(matchesCompiledModel(otherAdapter, exactName, expected)).isFalse();
```

- Verification contribution: R1/G1/P1/S1.
- After this file: Preserve existing Component construction and invocation semantics.

#### File 2 — `MODIFY stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/runtime/SpringAiModelAdapter.java`

- Purpose: Compare the contained ChatModel by object identity.
- Symbols: SpringAiModelAdapter#usesChatModel.
- Repository evidence: EVD-001–005 and Spec §7/9/14.
- Dependencies and consumers: Existing pinned ADK/Spring AI and owning Component test.
- Why now: Capture this file in the single Component identity proof.
- Contract/signature changes: Compare the contained ChatModel by object identity.
- Input/output and state mapping: The predicate uses a compiled object, exact name and expected reference. The output is a boolean.
- Error and edge behavior: Exercise or document mismatches, null input and no provider calls.
- Standards impact: MC-REUSE-001, MC-VALID-001, MC-SCOPE-001 and MC-TEST-001.
- Literal rule enforcement: Rule 1, Rule 2, Rule 5, Rule 9, Rule 11.
- Implementation pseudocode:

```java
boolean usesChatModel(ChatModel expected) {
    return expected != null && model == expected;
}
// The stored ChatModel field remains private in the existing final adapter.
```

- Verification contribution: R1/G1/P1/S1.
- After this file: Preserve existing Component construction and invocation semantics.

#### File 3 — `MODIFY stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/runtime/SpringAiModelAdapterFactory.java`

- Purpose: Expose the typed, side-effect-free predicate.
- Symbols: SpringAiModelAdapterFactory#matchesCompiledModel.
- Repository evidence: EVD-001–005 and Spec §7/9/14.
- Dependencies and consumers: Existing pinned ADK/Spring AI and owning Component test.
- Why now: Capture this file in the single Component identity proof.
- Contract/signature changes: Expose the typed, side-effect-free predicate.
- Input/output and state mapping: The predicate uses a compiled object, exact name and expected reference. The output is a boolean.
- Error and edge behavior: Exercise or document mismatches, null input and no provider calls.
- Standards impact: MC-REUSE-001, MC-VALID-001, MC-SCOPE-001 and MC-TEST-001.
- Literal rule enforcement: Rule 1, Rule 2, Rule 5, Rule 9, Rule 11.
- Implementation pseudocode:

```java
return adapter instanceof SpringAiModelAdapter known
        && expectedModelName != null
        && expectedModelName.equals(known.model())
        && known.usesChatModel(expectedModel);
```

- Verification contribution: R1/G1/P1/S1.
- After this file: Preserve existing Component construction and invocation semantics.

#### File 4 — `CREATE docs/egon/spec/2026-11-11-15-57-compiled-model-identity.md`

- Purpose: Freeze the API and proof boundary.
- Symbols: Specification.
- Repository evidence: EVD-001–005 and Spec §7/9/14.
- Dependencies and consumers: Existing pinned ADK/Spring AI and owning Component test.
- Why now: Capture this file in the single Component identity proof.
- Contract/signature changes: Freeze the API and proof boundary.
- Input/output and state mapping: The predicate uses a compiled object, exact name and expected reference. The output is a boolean.
- Error and edge behavior: Exercise or document mismatches, null input and no provider calls.
- Standards impact: MC-REUSE-001, MC-VALID-001, MC-SCOPE-001 and MC-TEST-001.
- Literal rule enforcement: Rule 1, Rule 2, Rule 5, Rule 9, Rule 11.
- Implementation pseudocode:

```text
Trace identity and no-provider-work requirements to the existing adapter and native model accessor.
Describe exact expected name and model object semantics.
Record independent downstream policy and distribution limits.
```

- Verification contribution: R1/G1/P1/S1.
- After this file: Preserve existing Component construction and invocation semantics.

#### File 5 — `CREATE docs/egon/plan/2026-11-11-15-58-compiled-model-identity.md`

- Purpose: Freeze source hashes, test selectors and packaging gates.
- Symbols: Implementation plan.
- Repository evidence: EVD-001–005 and Spec §7/9/14.
- Dependencies and consumers: Existing pinned ADK/Spring AI and owning Component test.
- Why now: Capture this file in the single Component identity proof.
- Contract/signature changes: Freeze source hashes, test selectors and packaging gates.
- Input/output and state mapping: The predicate uses a compiled object, exact name and expected reference. The output is a boolean.
- Error and edge behavior: Exercise or document mismatches, null input and no provider calls.
- Standards impact: MC-REUSE-001, MC-VALID-001, MC-SCOPE-001 and MC-TEST-001.
- Literal rule enforcement: Rule 1, Rule 2, Rule 5, Rule 9, Rule 11.
- Implementation pseudocode:

```text
Pin exact Component baseline, candidate hashes and owning module selector.
Select each mutation case independently and check actual failure message.
Require actual Component package inspection before reporting P1.
```

- Verification contribution: R1/G1/P1/S1.
- After this file: Preserve existing Component construction and invocation semantics.

#### File 6 — `CREATE docs/egon/evidence/2026-11-11-compiled-model-identity.md`

- Purpose: Record observed checks and outstanding owner Maven gates.
- Symbols: Evidence.
- Repository evidence: EVD-001–005 and Spec §7/9/14.
- Dependencies and consumers: Existing pinned ADK/Spring AI and owning Component test.
- Why now: Capture this file in the single Component identity proof.
- Contract/signature changes: Record observed checks and outstanding owner Maven gates.
- Input/output and state mapping: The predicate uses a compiled object, exact name and expected reference. The output is a boolean.
- Error and edge behavior: Exercise or document mismatches, null input and no provider calls.
- Standards impact: MC-REUSE-001, MC-VALID-001, MC-SCOPE-001 and MC-TEST-001.
- Literal rule enforcement: Rule 1, Rule 2, Rule 5, Rule 9, Rule 11.
- Implementation pseudocode:

```text
Record isolated javac/JUnit counts and exact isolated mutation results.
Keep owning Maven and package results pending until actually executed.
Separate same-coordinate source reactor from local SNAPSHOT installation or publication.
```

- Verification contribution: R1/G1/P1/S1.
- After this file: Preserve existing Component construction and invocation semantics.


- Validation working directory: /Users/mario/SelfProject/Stellar-Enclosure/Stellar-Enclosure-Components or full isolated Components checkout.
- Verification command: §8 R1/G1/P1/S1.
- Expected result: Typed R1 failures, six owning tests and actual packaged methods.
- Failure returns to: The corresponding type, name, reference or package check.
- Completion criteria: Fresh owning finite results, inspected package and exact staged scope.
- Rollback: Revert the one Component commit after removing any downstream use.
- Commit paths: `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/runtime/AgentFlowFactoryTest.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/runtime/SpringAiModelAdapter.java`, `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/runtime/SpringAiModelAdapterFactory.java`, `docs/egon/spec/2026-11-11-15-57-compiled-model-identity.md`, `docs/egon/plan/2026-11-11-15-58-compiled-model-identity.md`, `docs/egon/evidence/2026-11-11-compiled-model-identity.md`.
- Commit: feat(agent-flow): verify compiled model identity.

## 8. Test, Validation, and Quality Gates

| Gate | Exact phase | Expected |
| --- | --- | --- |
| R1 | Equality and omitted-name mutations select `AgentFlowFactoryTest#matches_only_the_exact_compiled_adapter_name_and_model_instance`. Foreign adapter acceptance selects `AgentFlowFactoryTest#rejects_another_adk_adapter_with_the_same_model_name`. | Each variant runs one test and reports `Expecting value to be false but was true`. Compilation failure is insufficient. |
| G1 | Clean owning Component `-pl :stellar-enclosure-component-agent-flow-starter -am` reactor with `-Dtest=AgentFlowFactoryTest`. | Fresh owner Surefire XML has six tests, zero failures, errors or skips. |
| P1 | Clean owning package with the same selector and exact JAR class/method inspection. | JAR is the Component SNAPSHOT, contains both classes, public static predicate has the expected descriptor, and adapter bytecode uses reference comparison. Record SHA-256. |
| S1 | Strict Spec/Plan, hashes and exact six staged paths. | Full candidate digest and scope match. No POM, schema or runtime configuration diff. |

```sh
identity_scratch=/Users/mario/.codex/tmp/tianji-agent-upgrade-20261105/t4-01/component-compiled-model-identity
identity_settings=/Users/mario/.codex/tmp/tianji-agent-upgrade-20261105/settings.xml
identity_skills=/Users/mario/SelfProject/Stellar-Enclosure/.agents/skills
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
test "$(git rev-parse HEAD)" = 6d26bf8724e8993b631db68c95b7238d7a93fb8c
"$identity_scratch/run-scratch.sh"
"$identity_scratch/run-negative.sh"
./mvnw -gs "$identity_settings" -s "$identity_settings" -o -B -ntp \
  -pl :stellar-enclosure-component-agent-flow-starter -am \
  -DskipTests=false -DskipITs=true -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=AgentFlowFactoryTest clean test
./mvnw -gs "$identity_settings" -s "$identity_settings" -o -B -ntp \
  -pl :stellar-enclosure-component-agent-flow-starter -am \
  -DskipTests=false -DskipITs=true -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=AgentFlowFactoryTest clean package
identity_jar=stellar-enclosure-component-agent-flow-starter/target/stellar-enclosure-component-agent-flow-starter-0.0.2-SNAPSHOT.jar
"$JAVA_HOME/bin/jar" tf "$identity_jar" | rg 'SpringAiModelAdapter(Factory)?\.class$'
"$JAVA_HOME/bin/javap" -classpath "$identity_jar" -s top.egon.cola.component.agentflow.runtime.SpringAiModelAdapterFactory
"$JAVA_HOME/bin/javap" -classpath "$identity_jar" -c -p top.egon.cola.component.agentflow.runtime.SpringAiModelAdapter
shasum -a 256 "$identity_jar"
python3 "$identity_skills/stellar-enclosure-coding-writing-spec/scripts/validate_spec.py" --strict docs/egon/spec/2026-11-11-15-57-compiled-model-identity.md
python3 "$identity_skills/stellar-enclosure-coding-writing-plan/scripts/validate_plan.py" --strict docs/egon/plan/2026-11-11-15-58-compiled-model-identity.md
shasum -a 256 -c "$identity_scratch/full-candidate.sha256"
git diff --cached --name-only | LC_ALL=C sort | diff - "$identity_scratch/full-expected-paths"
git diff --check
git diff --cached --check
git diff HEAD --exit-code -- '*.sql' '**/pom.xml' pom.xml
```

Preserve the G1 report before P1 runs `clean`. Record the archive listing, predicate descriptor and equality bytecode from P1. The predicate descriptor must contain `BaseLlm`, `String`, and `ChatModel` parameters with boolean return. `usesChatModel` must use reference comparison and must not expose the field through a getter.

## 9. Migration, Compatibility, Rollout, and Rollback

The source reactor can use a full checkout with this exact Component source by setting `STELLAR_COMPONENTS_DIR` for Tianji's existing `scripts/verify-source-reactor.sh`. Its generated aggregator and `-am` build the already managed `0.0.2-SNAPSHOT` from source. An independently resolved Maven snapshot must be verified separately. This Step runs package only, with no install, deployment or publication.

## 10. Requirement-to-Step Traceability Matrix

| Requirement | Owning Step | Exact files | Verification |
| --- | --- | --- | --- |
| REQ-001 | Step 1 | Files 1–3 | R1/G1/P1 |
| REQ-002 | Step 1 | Files 1–3 | R1/G1/P1 |
| REQ-003 | Step 1 | Files 1–3 | G1/P1 |
| REQ-004 | Step 1 | Files 1–6 | R1/G1/P1/S1 |

## 11. Risks, Blockers, and User Decisions

The isolated test proof exists. Owning JDK 25 Maven and actual package gates remain execution work. Runtime options, caller policy, source distribution and actual deployment are outside this Component check.

## 12. Review and Acceptance

### 12.5 Blocking Manual Check

| Check ID | Applicability | Status | Evidence | Finding | Required action/exception |
| --- | --- | --- | --- | --- | --- |
| MC-ARCH-001 | Applicable | PASS | §7/8 | Extend the existing Component runtime owner. | None |
| MC-REUSE-001 | Applicable | PASS | EVD-001–005 | Use the actual compiled adapter, native model accessor and existing tests. | None |
| MC-DEP-001 | Applicable | PASS | §6/16 | Pinned ADK, Spring AI and test dependencies already supply every type. | None |
| MC-NAME-001 | Applicable | PASS | §7/9 | matchesCompiledModel and usesChatModel describe exact checks. | None |
| MC-VALID-001 | Applicable | PASS | §7/9/14 | Fail closed for nulls and type, name or reference mismatch. | None |
| MC-MODEL-001 | Not applicable | N/A | §10 | Reuse pinned SDK interfaces. Only the immutable test model uses a record. | None |
| MC-CONVERT-001 | Not applicable | N/A | §10 | No mapping, conversion or DTO handoff. | None |
| MC-LOG-001 | Not applicable | N/A | §15 | The predicate produces a boolean and uses no logging or error payload. | None |
| MC-BEAN-001 | Not applicable | N/A | §6/7 | The existing factory gains a static predicate without injection changes. | None |
| MC-UTIL-001 | Applicable | PASS | §6/7 | Use Java object identity and the existing model accessor. | None |
| MC-JSON-001 | Not applicable | N/A | §10 | No serialized contract or enum. | None |
| MC-TIME-001 | Not applicable | N/A | §6.3 | Identity comparison reads no clock and uses no temporal input. | None |
| MC-CONFIG-001 | Not applicable | N/A | §5/16 | No host properties or config files are touched. | None |
| MC-PATTERN-001 | Applicable | PASS | §7/13 | Continue encapsulating the model in the existing factory/adapter. | None |
| MC-SCOPE-001 | Applicable | PASS | §8/16 | Exactly three Java and three Component documentation files. | None |
| MC-TEST-001 | Applicable | PASS | §14 | Exact R1, owner G1/P1 and bytecode/scoped S1 verification are specified. | None |
| MC-BLOCKER-001 | Applicable | PASS | §18 | Owner Maven and artifact verification are execution gates. Product use and distribution remain separate. | None |

### 12.6 Final verdict

PASS — Ready for user review
