# Stellar-Enclosure-Components

[English](README.md) | [中文](README.zh-CN.md)

Reusable Java libraries, Spring Boot Starters and offline development tools for Stellar Enclosure. All first-party modules start at **0.0.1**.

## Component catalog

| Component | Current responsibility | Documentation |
|---|---|---|
| Common | Response/error contracts, MyBatis-Plus/Sharding-JDBC persistence support, tenant isolation, cache, trace propagation, IDs, crypto and data masking. | [Common](stellar-enclosure-component-common/README.md) |
| Dynamic Thread Pool | Managed platform/virtual-thread executors, runtime updates, context propagation and execution monitoring. | [Dynamic Thread Pool](stellar-enclosure-component-dynamic-thread-pool/README.md) |
| RPC | Protobuf/gRPC service contracts, provider/consumer runtime and the Tianshu transport adapter. | [RPC](stellar-enclosure-component-rpc/README.md) |
| Rule Engine | Java rule chains, rule trees and execution control. | [Rule Engine](stellar-enclosure-component-rule-engine-starter/README.md) |
| Access Guard | Rule-based admission checks around annotated business methods. | [Access Guard](stellar-enclosure-component-access-guard-starter/README.md) |
| Method Extension | Business-defined decisions before an annotated method executes. | [Method Extension](stellar-enclosure-component-method-extension/README.md) |
| Transactional Outbox | PostgreSQL transaction-local outbound messages, asynchronous delivery and polling recovery. | [Transactional Outbox](stellar-enclosure-component-transactional-outbox-starter/README.md) |
| Bytecode | ASM architecture validation, executor enhancement, method observation and method extensions. | [Bytecode](stellar-enclosure-component-bytecode/README.md) |
| Agent Flow | Configuration-driven Spring AI/Google ADK flow compilation, in-memory sessions, events, streaming, cancellation and timeouts. | [Agent Flow](stellar-enclosure-component-agent-flow-starter/README.md) |
| RAG | Document extraction, chunking, embeddings and similarity retrieval. | [RAG](stellar-enclosure-component-rag-starter/README.md) |
| Code Generator | Offline backend CRUD generation from PostgreSQL DDL or a managed persistence manifest. | [Code Generator](stellar-enclosure-component-code-generator/README.md) |

Import the [Components BOM](stellar-enclosure-components-bom/README.md), then depend on the runtime artifacts needed by the application. Aggregator POMs, sample Admin applications and test modules are separate from consumer entry points. Code Generator is a development tool rather than a runtime library. Agent Flow is disabled by default; its host supplies models, credentials, tools and persistence.

Platform applications live in their own repositories. The RPC Tianshu adapter is owned here and depends on the Tianshu base Starter; Tianshu's Admin and client lifecycle are owned by [tianshu-service](https://github.com/Stellar-Enclosure/tianshu-service). See the [component architecture rules](egon-cola-components-architecture.md) before adding a module.

## Build

Requires Java 25 and the included Maven Wrapper. The shared parent manages Spring Boot 4.1.2. Once the 0.0.1 dependencies are published:

```bash
./mvnw -B -ntp test-compile
```

Before the first Central publication, clone the eight Java repositories next to one another and bootstrap their artifact graph explicitly:

```bash
bash ../Stellar-Enclosure-Parent/scripts/bootstrap-workspace.sh \
  --workspace "$(cd .. && pwd)" \
  --local-repository "$HOME/.m2/repository"

# Resolve subsequent Maven commands and generator subprocesses from this same cache.
export MAVEN_ARGS="${MAVEN_ARGS:-} -Dmaven.repo.local=$HOME/.m2/repository"
```

The bootstrap installs parent/BOM models before building components and business starters in Maven reactor order. It compiles test sources and installs packages with runtime tests disabled. It never runs applications, databases, containers, browsers, or deploy.

## Publish to Maven Central

Publishes the Components parent, standalone BOM, reusable components, Starters, code generator and Maven plugin. The `common-test` artifact contains reusable boundary assertions used by component tests. Dynamic thread pool Admin/sample tests, RPC test applications, bytecode tests and benchmarks are excluded by both module properties and the Central plugin. The RPC Tianshu adapter requires the published Tianshu base Starter.

Run these commands from this repository. Configure a Central Portal token under Maven server ID `central` and a local GPG signing key; credentials stay outside Git. See the [credential setup and initial dependency order](https://github.com/Stellar-Enclosure/Stellar-Enclosure-Parent/blob/main/docs/first-release.md).

```bash
# Verify release artifacts locally; signing and upload are disabled.
./mvnw -B -ntp -Prelease -Dgpg.skip=true -DskipITs -Dinvoker.skip=true -Darchetype.test.skip=true clean verify

# Sign and publish this repository's public artifacts.
./mvnw -B -ntp -Prelease -DskipITs -Dinvoker.skip=true -Darchetype.test.skip=true clean deploy
```

Unit tests remain enabled. `release` attaches sources, Javadoc and GPG signatures, then publishes through the Central Portal. Ordinary builds and Git pushes do not publish. Each published GAV is immutable: use a new version for changed content, and use `-pl` to select only unpublished modules after a partial release. Before the first release, use the Build section's local bootstrap or publish the prerequisite artifacts first; bootstrap installation alone does not publish dependencies.

## Compatibility and publication

Java packages/classes, Bean names, configuration keys, OAuth/RPC identities, APIs, and database ownership are retained. Existing SQL and DDL history are copied byte for byte. Artifact/build versions and necessary build paths change. A successful build does not imply application startup or live infrastructure acceptance.

Git pushes run validation only. Central and npm publication are separate user actions. CI defaults to initial source bootstrap; after publication set `STELLAR_USE_SOURCE_BOOTSTRAP=false` to consume published artifacts directly. Private organizations may supply the Actions secret `STELLAR_ENCLOSURE_READ_TOKEN` with repository read access.

Original MIT/LGPL licenses and author attribution are preserved.
