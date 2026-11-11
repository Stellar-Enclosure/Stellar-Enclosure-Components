# Agent Flow compiled model identity evidence

Component baseline `6d26bf8724e8993b631db68c95b7238d7a93fb8c`. [Spec](../spec/2026-11-11-15-57-compiled-model-identity.md) and [Plan](../plan/2026-11-11-15-58-compiled-model-identity.md).

`SpringAiModelAdapterFactory.matchesCompiledModel` checks the actual compiled adapter, exact ADK model name and backing Spring AI object reference. It returns false for null, wrong name, different reference or a foreign implementation. The finite fixture compiles an actual leaf and extracts its pinned native model. It includes a distinct instance with identical options and value equality and a foreign BaseLlm with the same name. The fake providers throw if model execution or connection occurs.

| Gate | Observed result |
| --- | --- |
| S0 | Isolated Java 25 compilation used the cached pinned ADK 1.13.0, Spring AI 2.0.2 and Lombok 1.18.46; owning Maven was not invoked. |
| R1 | Three independent scratch variants each ran one exact method. Identity replaced by equality, omitted name check and foreign-adapter fallback each caused the expected false assertion. The owning Maven reactor reproduced the identity mutation failure. |
| S0 finite | Scratch JUnit ran all six `AgentFlowFactoryTest` cases with six successes, zero failures or skips. |
| G1 | Clean owning Component Maven ran six tests, with zero failures, errors or skips. |
| P1 | Clean owning package repeated six passes. The actual JAR matches both compiled classes and contains the exact public predicate and reference-comparison bytecode. SHA-256 `2059075d1eb81cf3f38badd3a46d1bb4ec41a27166f829d339e9cbb38df47d25`. |
| S1 | Strict Spec and Plan validation passed in scratch. Review baseline and six-path candidate match the external SHA manifest. |

The Component change retains `top.egon:stellar-enclosure-component-agent-flow-starter:0.0.2-SNAPSHOT`. The Tianji source reactor selects this module with `-am` using the Components checkout. Resolving a Maven SNAPSHOT from a cache or remote repository is a distinct operation. No local SNAPSHOT installation, publication, application startup or real model execution was performed.
