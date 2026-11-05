# T4-03 Agent Flow 原生显式续跑桥

| Field | Value |
| --- | --- |
| Document | 2026-11-06-02-33-agent-flow-explicit-resume.md |
| Template Version | 9 |
| Archetype Cleanup | Not applicable |
| Status | Review |
| Type | Feature |
| Complexity | Simple |
| Complexity Drivers | 现有 Runner 新增显式调用入口并复用同一 trace 与取消生命周期 |
| Created | 2026-11-06 02:33 Asia/Shanghai |
| Updated | 2026-11-06 02:33 Asia/Shanghai |
| Owner | Stellar Enclosure Components |
| Repository | Stellar-Enclosure-Components |
| Scope | Existing Agent Flow Session Runner and its compatibility tests |
| Change Surface | Additive trusted in-process factory, explicit native continuation guard, finite compatibility proof |
| Affected Chapters | §7, §8, §13, §14, §15, §16 |
| Source Requirement | [Master §19 T4-03 and S11](../../../../docs/plans/agent-framework-upgrade-plan.md) native continuation prerequisite |
| Baseline Revision | Components 87af09fea9a9d58e7127b009d76f60438c79e569 |
| Amends | None |
| Supersedes | None |
| Depends On | None |
| Related Specs | None |
| Related Plans | [Explicit native resume bridge](../plan/2026-11-06-02-34-agent-flow-explicit-resume.md) |

## 1. Summary

Extend the existing supplied-Session Agent Flow Runner with an explicitly selected native invocation continuation mode. A trusted host supplies the stored invocation identity and an existing Session. The bridge uses the same trace, cancellation and model/tool guards as ordinary execution. The actual Component tests verify compatibility with an ordinary producer and cancellation of a cached Fake model stream. Product recovery, grants and activation are separate scope.

## 2. Background and Current State

| Evidence ID | Exact source | Observed fact |
| --- | --- | --- |
| EVD-001 | [AgentFlowSessionRunner](../../../stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/runtime/AgentFlowSessionRunner.java) | Current BaseAgent constructor creates a nonresumable Runner with supplied Session service and installs Agent Flow cancellation before host plugins. Ordinary execution enters the protected lifecycle hook. |
| EVD-002 | [AgentFlowInMemoryRunner lifecycle](../../../stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/runtime/AgentFlowInMemoryRunner.java) | Package-local shared lifecycle captures trace, registers active cancellation and disposes it on terminal, subscriber cancellation and runner close. |
| EVD-003 | [SpringAiModelAdapter](../../../stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/runtime/SpringAiModelAdapter.java), [Agent Flow POM](../../../stellar-enclosure-component-agent-flow-starter/pom.xml) | Model and tool adapters already consult the shared cancellation context; ADK Java dependency is pinned to 1.13.0. |
| EVD-004 | Actual google-adk 1.13.0 Runner and Runner.Builder bytecode | Protected BaseAgent constructor accepts ResumabilityConfig. Six-argument explicit runAsync goes directly to private runResumableFromSession, bypassing the four-argument protected runAsyncImpl hook. Runner.Builder.app builds a plain Runner and uses App plugins. |
| EVD-005 | [AgentFlowSessionRunnerTest](../../../stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/compatibility/AgentFlowSessionRunnerTest.java), [AdkSpringAiCompatibilityTest](../../../stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/compatibility/AdkSpringAiCompatibilityTest.java) | Existing in-memory Session and Fake model tests own ordinary Runner compatibility, trace and cached-provider cancellation. |

### 2.5 Archetype example cleanup

Cleanup disposition: Not applicable

Reason: Existing library Runtime and compatibility test paths with no generated business project.

## 3. Goals and Non-goals

Supply a finite-proven Component bridge for explicit, existing-Session continuation with fixed ADK semantics. Preserve the current constructor and ordinary execution. Tenant authorization, product Run fencing and new leases, checkpoint mutation and storage, automatic recovery, Flowable, tenant UI and full S11 remain outside this step.

### 3.3 Change Surface and Design Depth

| Area/layer | Disposition | Exact repository evidence | Changed or preserved behavior/contract | Required Spec treatment | Chapter(s) |
| --- | --- | --- | --- | --- | --- |
| Agent Flow Runner and compatibility tests | Affected | EVD-001–005 | Trusted Java factory and guarded ADK invocation reuse existing lifecycle | Typed contract, mapping, boundaries, tests and compatibility | §7, §8, §13, §14, §15, §16 |
| Tianji Agent Runtime and storage | Context-only | Master T4-03 | Existing product consumers remain on existing constructor | State explicit prerequisite and scope | §11 |
| External HTTP and Web | Not applicable | EVD-001/005 | Library execution only | Scope disposition | §9, §12 |

## 4. Requirements and Acceptance Criteria

| ID | Requirement | Priority | Observable acceptance | Source |
| --- | --- | --- | --- | --- |
| REQ-001 | Preserve ordinary Runner behavior and opt in explicitly to native continuation | Must | Existing constructor and two existing Runner cases pass; explicit factory uses native BaseAgent resumability with plain-text auto-resume off | EVD-001/004/005 |
| REQ-002 | Accept only an existing stored invocation without an added message or state | Must | Reject blank identity, missing/invalid config, overlength invocation, auto-create, new Content or nonempty state delta; a valid legacy producer cut continues exactly the stored invocation | EVD-001/004 |
| REQ-003 | Carry existing trace and cancellation guards on explicit execution | Must | Component lifecycle is exercised on explicit calls; typed RED without inner guard leaves Fake subscribed; final explicit cancel reaches cached Fake provider stream | EVD-002/003/004 |
| REQ-004 | Produce finite source evidence only and keep ownership scoped | Must | Owning and adjacent suites pass using existing Java 25 offline reactor. Exact Component files and docs only. No new dependency, product consumer wiring or activation. | EVD-003/005, Master |

### 4.2 Use-case analysis

#### 4.2.1 Actor inventory

| Actor ID | Actor | Goal | Permission/context |
| --- | --- | --- | --- |
| ACTOR-001 | Trusted in-process Agent Flow host | Explicitly continue one stored native invocation | Owns supplied Session service and has separately established authority |
| ACTOR-002 | Component source reviewer | Verify lifecycle and compatibility | Executes only reviewed finite Component tests |

#### 4.2.2 Use-case artifact

| ID | Use case/goal | Primary actor | Supporting actors/systems | Trigger | Preconditions | Main success outcome | Alternatives/failures | Postconditions | Requirements | Interfaces/pages | Tests |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| UC-001 | Explicitly continue a stored invocation | ACTOR-001 | ADK, supplied Session service | Host selects explicit factory and six-argument method | Existing Session and exact invocation, no new input | Same invocation continues with original user prefix intact | Invalid command, missing Session or missing invocation fails | No duplicate user input or repeat model work after completion | REQ-001/002 | In-process Runner | TEST-002/003 |
| UC-002 | Cancel explicit execution | ACTOR-001 | Shared lifecycle and Fake provider stream | Downstream cancellation or close | Active guarded invocation | Provider subscription disposed | Rejection before subscription leaves model untouched | Existing trace and lifecycle semantics | REQ-003 | In-process Runner | TEST-001/004 |
| UC-003 | Validate scoped source | ACTOR-002 | Component compatibility suites and source reactor | Offline gates | Reviewed exact source | Correct RED reason and actual finite GREEN | Skip, unrelated error or scope mismatch fails | Evidence suitable for review | REQ-004 | Source gates | TEST-001/004/005 |

## 5. Constraints, Assumptions, and Decisions

Use the existing Component package, Java 25, pinned ADK Java 1.13.0, supplied Session service, existing plugins and Reactor/RxJava cancellation. The trusted Java entry cannot establish caller authority. A future product consumer must authorize the exact Run before supplying any stored invocation. No repository dependency, profile, SQL, bean, IAM declaration or product call-site changes are in scope.

## 6. Project Technology Context

Agent Flow starter is a reusable library. It uses ADK BaseAgent, supplied BaseSessionService, existing Spring AI model adapter, OTel context and RxJava with JUnit fixture tests. This addition belongs beside the existing Session Runner.

### 6.2 User-mandated Java rule compliance

| Literal rule | Affected? | Repository evidence | Exact design decision | Files/types/interfaces | Validation/test evidence | Status/blocker |
| --- | --- | --- | --- | --- | --- | --- |
| Rule 1 | Yes | EVD-001/005 | Reuse established AgentFlowSessionRunner and compatibility test names | Existing two Java files | TEST-002/004 | PASS |
| Rule 2 | Yes | EVD-001/004 | Guard the direct library boundary with existing typed values and exact checks | Six-argument Runner override | TEST-003 | PASS |
| Rule 3 | No | EVD-001 | Existing Runner and ADK types only | No POJO/converter | Scope | N/A |
| Rule 4 | No | EVD-001/002 | Existing non-Bean Runner and existing tracing | No business bean/log | Scope | N/A |
| Rule 5 | Yes | EVD-001/003/005 | Reuse actual ADK/RxJava and current JDK test latches | Same dependencies | TEST-004/005 | PASS |
| Rule 6 | No | EVD-001/004 | Existing typed events only | No JSON or persistent enum change | Scope | N/A |
| Rule 7 | No | EVD-001 | Trusted factory selection only | No YAML/environment config change | Scope | N/A |
| Rule 9 | No | EVD-002 | Direct additive bridge through existing lifecycle | No new framework/pattern participants | Scope | N/A |
| Rule 10 | Yes | EVD-005 | Keep bounded elapsed finite test waits | Tests only | TEST-001/004 | PASS |
| Rule 11 | Yes | EVD-001/003 | Maintain current starter runtime and compatibility package architecture | No persistence/DDL/CQE expansion | TEST-005 | PASS |

### 6.3 Time semantics and conversion contract

Time contract: Required

| Time ID | Fields/symbols | Semantics | Java/runtime type | Storage policy | Wire policy | Business zone source | Display zone source | Boundary / DST rules | Precision / Clock | Compatibility | Verification |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| TIME-001 | Finite test timeout, Fake stream latches | Duration | JUnit @Timeout and JDK TimeUnit | Not persisted | Internal | Elapsed duration has no zone | No display | Bound active subscription wait and test cleanup | JDK elapsed wait | Existing Component test policy | TEST-001/004 |

## 7. Architecture Design

### 7.0 Minimum-design baseline and element-necessity audit

| Proposed element | Change | Requirements | Existing/direct alternative | Concrete inadequacy of alternative | Added calls/state/coupling/failures/migration/operations | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| Trusted factory and explicit invocation lifecycle wrapper | Add | REQ-001/002/003 | Existing nonresumable constructor or plain ADK Runner.builder().app | Constructor cannot continue stored invocations; plain Runner bypasses Component cancellation context | One existing class, one fixed constructor mode and one override | Add |
| Shared existing Runner lifecycle | Reuse | REQ-003 | AR-local cancellation implementation | Existing adapters consult package-local context; duplication risks a still-running cached stream | Same trace and active cancellation | Keep |
| Component compatibility cases | Add | REQ-001–004 | Only native V9 or mock product test | Neither proves ordinary Agent Flow producer to explicit bridge under existing lifecycle | Fake/in-memory Component test cases | Add |

| Path | Network calls | Client states | Server contracts/state | Failure and TOCTOU points | Additional user/business value |
| --- | --- | --- | --- | --- | --- |
| Ordinary constructor | Existing host behavior | Existing | Existing ADK execution | Existing semantics | Preserved compatibility |
| Explicit trusted host method | No new remote calls | Exact stored invocation and supplied Session | Existing native Runner and shared guard | Validation before native work; product authorization and lease atomicity are outside library | Reusable cancellation-safe prerequisite |
| Source validation | None | Finite Fake model and in-memory Session | Component source only | Does not verify product activation | Evidence-bounded review |

### 7.1 System Architecture Design

The new trusted factory constructs the existing final Runner subclass. It uses ADK's protected BaseAgent constructor with explicit ResumabilityConfig and existing supplied Session service, in-memory artifact and memory stores, and cancellation-first plugin list. Existing callers retain the public constructor.

### 7.2 High-Level Design

For an explicit factory instance, validate the six-argument Java call before subscribing to native work. Capture current trace through the existing outer lifecycle. Register the same cancellation token through the inner lifecycle because ADK calls its private resume path directly. Delegate the exact invocation id and empty inputs to the pinned SDK. Finite tests cover the ordinary writer prefix, same invocation completion, native refusal and actual stream cancellation.

### 7.3 Detailed Design

`withExplicitResume(BaseAgent, String, BaseSessionService, List<Plugin>)` sets resumable true and plainTextContinuationAutoResume false. It uses the pinned protected constructor and leaves compaction/cache unset. The existing public constructor retains disabled mode. Explicit `runAsync(userId, sessionId, invocationId, message, config, stateDelta)` checks nonblank user, Session and invocation; invocation length at most 128; message null; config nonnull with autoCreateSession false; and state delta null or empty. Invalid arguments return a typed Flowable error before native Session/model work. Existing nonresumable explicit behavior is delegated unchanged.

The explicit override nests `lifecycle.runAsync(() -> lifecycle.runAsyncImpl(() -> super.runAsync(...)))` and delegates null Content/state delta for the exact stored id. The ordinary override and protected hook remain on the established lifecycle. Close retains the shared registration and cleanup. A missing Session or invocation cannot produce a new user event. Repeating a successfully completed exact invocation must add no events and make no model call. No trusted invocation identity is written into logs.

## 8. Package Structure and Code File Tree

| Operation | Exact path |
| --- | --- |
| Modify | `stellar-enclosure-component-agent-flow-starter/src/test/java/top/egon/cola/component/agentflow/compatibility/AgentFlowSessionRunnerTest.java` |
| Modify | `stellar-enclosure-component-agent-flow-starter/src/main/java/top/egon/cola/component/agentflow/runtime/AgentFlowSessionRunner.java` |
| Create | `docs/egon/spec/2026-11-06-02-33-agent-flow-explicit-resume.md` |
| Create | `docs/egon/plan/2026-11-06-02-34-agent-flow-explicit-resume.md` |
| Create | `docs/egon/evidence/2026-11-06-agent-flow-explicit-resume.md` |

## 9. Interface Definitions

The in-process Java signatures, arguments and outcomes are specified in §7.3. External REST, OpenAPI, RPC, Java service contracts and remote endpoints are outside this Component library scope.

## 10. POJO and Data Model Design

Reuse ADK BaseAgent, BaseSessionService, Event, RunConfig, ResumabilityConfig and the existing Runner lifecycle. No new DTO, persistence format or external transport.

## 11. Database Design

Context-only. The Component does not inspect AgentDB, V16, Session leases or product Run state. Compatibility uses an in-memory Session fixture. Existing V16 remains outside the Component scope.

## 12. Frontend Page Design

Not applicable. The library change has no browser page or transport consumer.

## 13. Design Patterns and Architecture Principles

Extend the existing Runner adapter at its exact native entry and reuse the shared lifecycle. Keep host authority in the owning product service. A second lifecycle or product-specific lease coupling would duplicate responsibility.

## 14. Test Design

| Test ID | Design |
| --- | --- |
| TEST-001 | Typed R1 selects AgentFlowSessionRunnerTest#explicit_resume_cancels_the_cached_model_subscription and removes only the new inner lifecycle wrapper. Require one expected cancellation assertion and no unrelated failures/skips. |
| TEST-002 | Existing ordinary nonresumable producer records first input before model work. New bridge resumes same id under the same stored Session, preserves original event prefix, produces one expected Fake result and repeats completed invocation with zero extra calls/events. Existing two ordinary Runner cases pass. |
| TEST-003 | Exact input admission rejects invalid modes and new input; wrong invocation or missing Session fails before Fake execution. |
| TEST-004 | Both explicit subscriber cancellation and runner close reach the actual Fake model subscription under the shared trace. Adjacent pinned native Spring AI cases pass. |
| TEST-005 | Validate strict docs, exact Component diff, existing dependency/config and no Service edits. Optional actual Component artifact inspection is source evidence only. |

## 15. Non-functional and Cross-cutting Design

Continue to use the scoped trace and bounded lifecycle. No separate provider, app, database, network listener or hosted job is part of source verification. Keep cancellation and release inside the existing reactive lifecycle.

## 16. Compatibility, Migration, Rollout, and Rollback

Existing ordinary Runner constructor and its Spring consumers are preserved. No migration or deployed feature switch. Revert the one Component source commit to remove the opt-in API. A later product decision owns source locks, authority and operator integration proof.

## 17. Alternatives and Decisions

An AR-only plain Runner can set App ResumabilityConfig but loses package-local model cancellation and native trace wrapper. Directly extend the existing Component Runner with its guarded explicit mode. Defer product integration until independent V16, lease/authorization and runtime evidence exists.

## 18. Risks and Open Questions

Pinned SDK overload behavior is covered by actual finite Component cases. Actual product process death, current authorization before takeover, atomic Run/Session claim and product replay safety remain separate. Test-only legacy in-memory producer compatibility does not establish PostgreSQL acceptance.

## 19. Traceability Matrix

| Requirement | Use case | Design | Tests |
| --- | --- | --- | --- |
| REQ-001 | UC-001 | §7.2/7.3/16 | TEST-002 |
| REQ-002 | UC-001 | §7.3/9 | TEST-002/003 |
| REQ-003 | UC-002 | §7.1/7.3/15 | TEST-001/004 |
| REQ-004 | UC-003 | §8/14/18 | TEST-001/004/005 |

## 20. Review and Acceptance

### 20.5 Blocking Manual Check

| Check ID | Applicability | Status | Evidence | Finding | Required action/exception |
| --- | --- | --- | --- | --- | --- |
| MC-ARCH-001 | Applicable | PASS | §6/7/8 | Existing Component runtime owner | None |
| MC-REUSE-001 | Applicable | PASS | EVD-001/002/003/004 | Existing Runner and cancellation adapter | None |
| MC-DEP-001 | Applicable | PASS | §6/8 | Existing pinned SDK and tests | None |
| MC-NAME-001 | Applicable | PASS | §7.3/8 | Existing type and explicit factory | None |
| MC-VALID-001 | Applicable | PASS | §7.3/14 | Existing Session and closed invocation inputs | None |
| MC-MODEL-001 | Not applicable | N/A | §10 | Existing SDK types | None |
| MC-CONVERT-001 | Not applicable | N/A | §10 | No new mapping | None |
| MC-LOG-001 | Not applicable | N/A | §15 | Existing trace and diagnostics | None |
| MC-BEAN-001 | Not applicable | N/A | §6 | No Bean addition | None |
| MC-UTIL-001 | Applicable | PASS | §6/7 | Existing SDK, RxJava and JDK | None |
| MC-JSON-001 | Not applicable | N/A | §10 | Typed Event only | None |
| MC-TIME-001 | Applicable | PASS | §6.3/14 | Bounded elapsed waits | None |
| MC-CONFIG-001 | Not applicable | N/A | §5/16 | Factory only | None |
| MC-PATTERN-001 | Applicable | PASS | §13 | Existing Runner adapter and lifecycle | None |
| MC-SCOPE-001 | Applicable | PASS | §8/16 | Five exact Component paths | None |
| MC-TEST-001 | Applicable | PASS | §14 | Actual finite gate planned | None |
| MC-BLOCKER-001 | Applicable | PASS | §18 | Product activation outside this source gate | None |

### 20.6 Final verdict

PASS — Ready for user review
