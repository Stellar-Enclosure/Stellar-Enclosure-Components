# Agent Flow explicit native resume evidence

Components baseline `87af09fea9a9d58e7127b009d76f60438c79e569`. [Spec](../spec/2026-11-06-02-33-agent-flow-explicit-resume.md) and [Plan](../plan/2026-11-06-02-34-agent-flow-explicit-resume.md).

A trusted host can construct an explicit resume runner for an existing native invocation with no new message, state delta or session creation. The six-argument path uses the existing trace and cancellation lifecycle. In the finite fixture, the ordinary constructor produced the first stored input. A second runner used the same invocation, preserved one user input and made one Fake model call. Repeating the completed invocation produced no additional model call. Invalid arguments, a missing Session and an unknown invocation failed before provider work.

| Gate | Observed result |
| --- | --- |
| R1 | With only the inner lifecycle omitted, the owner reactor reached the Fake stream and produced exactly one expected cached-model cancellation assertion failure. |
| G1 | Clean source reactor passed 5 Runner tests and 16 Spring AI compatibility tests. Cancellation and close each disposed the resumed cached model and retained caller trace state. |
| G2 | The verification build repeated all 21 tests. Actual Agent Flow jar contains the trusted factory and native six-argument overload alongside the original constructor. Archive SHA-256 `9fe689d861e8d6a892a70385c46a0e2a04d2fbc0b3406c3d77687c411669b4d0`. |
| S1 | Strict Spec and Plan pass. Exact two Java and three document paths. Service callers, dependencies, configuration and migrations match their baseline. |

The source evidence uses an in-memory Session and Fake model on pinned ADK Java 1.13.0. Actual V16 PostgreSQL continuation, product execution authority and fenced Run takeover remain separate.
