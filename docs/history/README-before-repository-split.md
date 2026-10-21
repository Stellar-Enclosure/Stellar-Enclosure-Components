# Stellar-Enclosure Components

[English](README.md) | [中文](README.zh-CN.md)

`stellar-enclosure-components` is the Maven reactor for reusable Java libraries and Spring Boot starters. Each runtime capability is independently testable and published through the [Components BOM](stellar-enclosure-components-bom/README.md).

## Component entry points

| Component | Capability | Consumer documentation |
|---|---|---|
| Access Guard | Rule-based admission control and guarded business execution through Spring AOP | [Access Guard Starter](stellar-enclosure-component-access-guard-starter/README.md) |
| Agent Flow | Flat configuration-driven Spring AI + Google ADK flow compilation, in-memory sessions, synchronous events, and streaming execution | [Agent Flow Starter](stellar-enclosure-component-agent-flow-starter/README.md) |
| Bytecode | ASM architecture verification plus runtime executor, method-observation, and Method Extension enhancement | [Bytecode](stellar-enclosure-component-bytecode/README.md) |
| Code Generator | Offline build-time CRUD generation from PostgreSQL DDL or MyBatis-Plus/Sharding-JDBC manifests | [Code Generator](stellar-enclosure-component-code-generator/README.md) |
| Common | Shared contracts and utility modules | [Common](stellar-enclosure-component-common/README.md) |
| Dynamic Thread Pool | Runtime executor management | [Dynamic Thread Pool](stellar-enclosure-component-dynamic-thread-pool/README.md) |
| Method Extension | One business-defined decision handler executed before an annotated method | [Method Extension](stellar-enclosure-component-method-extension/README.md) |
| RAG | Document extraction, chunking, embedding, and similarity retrieval mechanics | [RAG Starter](stellar-enclosure-component-rag-starter/README.md) |
| RPC | Protobuf/gRPC and Tianshu integration | [RPC](stellar-enclosure-component-rpc/README.md) |
| Rule Engine | Java rule chains, trees, and execution control | [Rule Engine](stellar-enclosure-component-rule-engine-starter/README.md) |
| Transactional Outbox | Message storage inside the local PostgreSQL transaction with asynchronous delivery and polling recovery | [Transactional Outbox Starter](stellar-enclosure-component-transactional-outbox-starter/README.md) |

Every module above is a reactor module, but not every one is a dependency entry point. Access Guard, Agent Flow, Bytecode, Common, Dynamic Thread Pool, Method Extension, RAG, RPC, Rule Engine, and Transactional Outbox publish BOM-managed runtime artifacts; Code Generator is a build-time tool with no runtime artifact.

The Agent Flow starter is default-off and follows the flat component profile. It owns configuration, ADK graph compilation, registry/session execution, cancellation, timeout, and bounded close. The host owns the provider `ChatModel`, credentials, tools, MCP, HTTP, authentication, and database or other persistence boundaries.

## BOM

Import [`stellar-enclosure-components-bom`](stellar-enclosure-components-bom/README.md) to consume `top.egon:stellar-enclosure-component-agent-flow-starter` and other published component entry points without repeating versions. The BOM manages 22 Egon runtime artifacts and pins the third-party versions that those artifacts expose to consumers (`org.apache.commons:commons-lang3` and the Redisson artifacts). Provider libraries, ADK internals, admin applications, test modules, aggregator POMs, and the offline code generator stay outside its dependency list.

## Architecture

Read the [Components architecture guide](stellar-enclosure-components-architecture.md) before adding a module. The guide distinguishes the ordinary starter/admin/test layout from the approved flat Agent Flow starter and records its Java 21, Spring Boot 3.5.16, Spring AI 1.1.8, and Google ADK 0.7.0 compatibility line.
