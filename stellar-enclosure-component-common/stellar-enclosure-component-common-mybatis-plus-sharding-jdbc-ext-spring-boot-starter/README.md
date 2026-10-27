# MyBatis-Plus + ShardingSphere-JDBC Starter

Consuming this starter requires PostgreSQL + MyBatis-Plus + ShardingSphere-JDBC. Excluding `shardingsphere-jdbc` is unsupported and must fail at compile or startup. The starter publishes the logical `@Primary DataSource` from one YAML file under `egon.cola.component.mybatis-plus.sharding`. `config-style: STRATEGY` and `config-style: NATIVE` are mutually exclusive.

`SHARDING_READWRITE` groups sources by `logical-name`, requiring exactly one PRIMARY and at least one REPLICA per group. ShardingSphere routes regular and transactional reads to that group's replicas with `DYNAMIC` and `ROUND_ROBIN`, and writes to its primary. Startup compares `pg_control_system()` identifiers and database names within each group; each borrowed connection checks its role and identity, and each SQL execution checks `pg_is_in_recovery()` again. The database account must be allowed to execute these functions, and each business statement incurs an additional role-check round trip. Strict mode rejects `HintManager`, routing SQL hints, wrong-role SQL and locking reads that ShardingSphere forces to the primary. `unwrap` and business stored procedures are unsupported in this mode. Replica reads cannot observe the transaction's uncommitted writes and can be stale. `SHARDING` retains the existing non-replica behavior. See the [Chinese configuration example](README.zh-CN.md#分片主从读写分离).

When transactional-outbox is enabled, its logical datasource factory permits only fixed Outbox locking and schema-verification reads on the primary. Business reads retain strict routing. Multiple groups use `TECHNICAL_GROUP` with a stable `shard_slot`; each transaction's business and Outbox writes must share one primary group, including for direct JDBC writes. See the [Outbox configuration](../../stellar-enclosure-component-transactional-outbox-starter/README.md#managed-mp-schema-and-legacy-data-migration).

Set a table's `shard-groups` to the logical groups that should hold its partitions; omitting it retains the existing behavior of including every PRIMARY group. Native read/write rules must exactly match the configured physical groups and use `DYNAMIC` transactional reads. Strict mode rejects sharding algorithms whose write-target calculation cannot be reconciled with the component's guard. Replication health, topology changes and failover remain deployment responsibilities.

Recommended STRATEGY yaml uses `tenant_id` first, then a business root such as `order_id` for orders/order_items (`COMPLEX_TENANT_THEN_BUSINESS`). The default transaction type is LOCAL. In STRATEGY the `transaction-default-type` value is always emitted as the generated rule's `defaultType`; in NATIVE it is only the fallback for a host YAML that omits `defaultType`. Validation accepts exactly `LOCAL` or `XA` and rejects anything else with `LOCAL_TRANSACTION_REQUIRED`. Selecting XA (`transaction-default-type: XA`) forwards the value only — this starter does not depend on an XA transaction provider, so the host must add the provider and its recovery manager itself.

`tables.<name>.schema` is optional and defaults to `public`. A non-public schema is supported only for `SINGLE` tables; other table strategies reject it with `SCHEMA_OVERRIDE_REQUIRES_SINGLE`. The resolved schema is included in the routing profile and topology fingerprint.

```yaml
egon:
  cola:
    component:
      mybatis-plus:
        sharding:
          enabled: true
          mode: SHARDING
          config-style: STRATEGY
          transaction-default-type: LOCAL
          data-sources:
            - name: master_data
              logical-name: master_data
              role: PRIMARY
              driver-class-name: org.postgresql.Driver
              jdbc-url: jdbc:postgresql://localhost:5432/master_data
              username: postgres
              password: postgres
            - name: shard_0
              logical-name: shard_0
              role: PRIMARY
              driver-class-name: org.postgresql.Driver
              jdbc-url: jdbc:postgresql://localhost:5432/shard_0
              username: postgres
              password: postgres
            - name: shard_1
              logical-name: shard_1
              role: PRIMARY
              driver-class-name: org.postgresql.Driver
              jdbc-url: jdbc:postgresql://localhost:5432/shard_1
              username: postgres
              password: postgres
          tables:
            users:
              type: SINGLE
              data-source: master_data
            outbox_message:
              type: SINGLE
              data-source: master_data
              schema: egon_outbox
            dict_region:
              type: BROADCAST
            orders:
              type: COMPLEX_TENANT_THEN_BUSINESS
              sharding-column: tenant_id
              table-columns: [tenant_id, order_id]
              root-key-name: order_id
            order_items:
              type: COMPLEX_TENANT_THEN_BUSINESS
              sharding-column: tenant_id
              table-columns: [tenant_id, order_id]
              root-key-name: order_id
```

NATIVE escape hatch: point `native-rules-resource` at one ShardingSphere rules file. Do not declare STRATEGY `tables` in the same YAML.

```yaml
egon:
  cola:
    component:
      mybatis-plus:
        sharding:
          enabled: true
          mode: SHARDING
          config-style: NATIVE
          transaction-default-type: LOCAL
          native-rules-resource: classpath:egon-ss-native.yml
          data-sources:
            - name: shard_0
              logical-name: shard_0
              role: PRIMARY
              driver-class-name: org.postgresql.Driver
              jdbc-url: jdbc:postgresql://localhost:5432/shard_0
              username: postgres
              password: postgres
            - name: shard_1
              logical-name: shard_1
              role: PRIMARY
              driver-class-name: org.postgresql.Driver
              jdbc-url: jdbc:postgresql://localhost:5432/shard_1
              username: postgres
              password: postgres
```

```yaml
# classpath:egon-ss-native.yml
databaseName: egon
rules:
  - !SHARDING
    tables:
      orders:
        actualDataNodes: shard_0.orders,shard_1.orders
        databaseStrategy:
          standard:
            shardingColumn: tenant_id
            shardingAlgorithmName: tenant_db
    shardingAlgorithms:
      tenant_db:
        type: CLASS_BASED
        props:
          strategy: STANDARD
          algorithmClassName: top.egon.cola.component.common.mybatis.sharding.algorithm.EgonColaLongTenantShardingAlgorithm
  - !SINGLE
    tables:
      - master_data.users
```

MyBatis-Plus 3.5.17 integration for PostgreSQL: common models, guarded Repository commands, explicit query SQL, MybatisBatch, tenancy, optimistic locking, ShardingSphere topology and managed DDL. Keep `Controller → Service → Repository → Mapper`; COLA Application/Domain services retain business ownership and repositories live in infrastructure.

## Model contract

Extend `EgonModel<PO>` and declare `@TableName`. Do not shadow technical fields.

| Java field | SQL column | Contract |
| --- | --- | --- |
| id | id | Long/BIGINT; inherited mandatory `@TableId(type=ASSIGN_ID)` |
| tenantId | tenant_id | Non-null Long/BIGINT |
| createUserId / updateUserId | create_user_id / update_user_id | Current user context |
| createTime / updateTime | create_time / update_time | Instant, UTC microseconds |
| deletedAt | deleted_at | LocalDateTime/timestamp(6); NULL means active |
| version | version | Long/BIGINT; insert 0, increment on update/delete |

`@TableLogic` uses `(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')`. `EgonColaIdentifierGenerator` calls the process-wide static `SnowflakeIdGenerator`, which `IdGeneratorAutoConfiguration` initializes once from validated ID properties; configure a unique `EGON_COLA_COMPONENT_ID_MACHINE_ID` per instance. Counters belong only in isolated tests. `@KeySequence` conflicts with this ASSIGN_ID contract and is rejected.

Common accepts any non-null Long tenant; ShardingSphere hosts require positive Long sharding keys. The MetaObjectHandler is assembled whenever MyBatis-Plus is enabled — there is no enable switch for it — and owns technical fields. Extension hooks may fill business fields only.

Tenant identity has no injected provider. `EgonColaTenantIdProvider` is a static, non-instantiable entry over the MDC key named by `tenant-id.mdc-key` (default `tenantId`); it re-reads SLF4J on every call, and an absent or unparsable value fails with `TENANT_CONTEXT_MISSING` / `TENANT_CONTEXT_MALFORMED`. Model validation binds through the same static entry, which refuses a conflicting live owner with `MODEL_VALIDATION_BINDING_CONFLICT`.

## Repository and CQRS

`EgonColaIRepository<T>` extends official `IRepository`; `EgonColaRepository<M,T>` supplies guarded operations. Business service ports expose domain types without PO generics or technical CRUD inheritance. Concrete named repositories use qualified Lombok constructor injection and expose only the mapper and properties getters; the inherited guards read tenant and validation state from the static entries.

- Commands use save, versioned updateById/removeById and guarded batches; callers check affected rows.
- Queries use named Mapper XML. Every mapper supplies `selectActiveById`, `selectActiveByIds`, `deleteVersionedById`.
- ActiveRecord is unavailable. QueryChain/lambdaQuery and generic Query Wrapper entry points fail fast; do not concatenate `.last()` SQL.
- For custom UPDATE, MP increments the entity version before execution: bind `#{MP_OPTLOCK_VERSION_ORIGINAL}` in WHERE, together with tenant, active and expected business state.
- Preserve id, tenant, creation metadata and version from the caller or a row loaded in the same transaction. Zero affected rows are not successful updates.
- MybatisBatch requires the same DataSource and an actual Spring transaction. Empty input emits no SQL; invalid/duplicate IDs fail early. Default chunk 1000, collection limit 10000; failures mark rollback-only.

No MyBatis-Plus SQL Injector is registered: startup validation requires those three statements to come from Mapper XML. Use mapper extensions for concrete non-generic SQL needs, and field handlers for real JSONB/array differences. Agent keeps its field-specific JSONB handler; the global String handler stays standard. Persisted enums require one `@EnumValue` and matching public `@JsonValue`/Jackson semantics, checked at startup.

## Annotation-based caching

`EgonColaRepository` only persists. `getByCache`, `listByCache`, `getCachePortProvider` and
`cacheRegionName` are removed, and CRUD no longer evicts caches automatically. Concrete repositories use
the Spring `@CacheConfig`, `@Cacheable`, `@CachePut`, `@CacheEvict` and `@Caching` annotations, and the
host enables `@EnableCaching` explicitly. Annotations belong on public, non-final business methods of
the concrete repository, invoked through the proxy by an external bean, while the method body keeps
calling the inherited CRUD; the base class keeps its data validation, tenancy and batch transaction
guards. The generated projects demonstrate this with `findCachedById` / `updateCachedById`. Every write
path that touches cached data must declare its own eviction, because plain CRUD no longer evicts.
Single-key reads, writes and evictions share the named `keyGenerator = "egonColaRepositoryKeyGenerator"`,
which produces the trusted `tenant:id`; multi-parameter and batch signatures do not support this
strategy and need a separate region or explicit eviction. `@CachePut` caches the return value, so it
cannot sit on a boolean-returning update and still yield an entity entry.

The two-level cache is a required component (`egon.cola.component.cache.enabled` defaults to `true`).
This starter depends on the managed common-cache starter and validates the CacheManager at startup: when
the component is enabled and the host only offers a foreign, incompatible `CacheManager`, startup fails
with `CACHE_MANAGER_INCOMPATIBLE` instead of silently falling back to a single-level cache. Explicitly
disabling the component skips that check, and the key generator remains usable.

Keys, TTL sampling, commit/rollback timing, the `sync` and `unless` restrictions, composite annotations
and manual operation are documented in the
[cache starter guide](../stellar-enclosure-component-common-cache-spring-boot-starter/README.md).

## SQL guards and transactions

The original SQL guard proves positive ID bounds before execution. Final SQL checks enforce tenancy, active rows, versions and audit fields after TenantLine. Block-attack, optimistic locking, PostgreSQL pagination and LOCAL write-target checks share one interceptor chain. Dynamic table names are disabled by default and require explicit mappings.

The LOCAL write guard runs regardless of the declared transaction type and spans SqlSessionFactory instances. Multi-table writes within one physical group are allowed; cross-group writes fail with `LOCAL_WRITE_TARGET_MISMATCH`, and a multi-target write without an actual transaction fails with `LOCAL_TRANSACTION_REQUIRED`. `BASE` is rejected during topology validation, and no XA recovery manager is provisioned or verified here. Root-key bulk writes require the exact statement ID and column in `local-write-guard.allowed-root-statements`.

Page limit is 500. IllegalSQL is permitted only in `dev`. Mixed dev/prod activation is rejected.

## Managed DDL

`EgonColaPostgreDdlRunner` receives explicit physical PRIMARY/schema/role targets and SHA-256 manifests. Schema advisory locks, managed-prefix checks, script SQL and ddl_history run on one transaction connection. Unknown commit outcomes are checked through a new connection before any retry. Non-empty unmanaged schemas, checksum drift and route fingerprint changes require operator action; no automatic DROP, repair or history adoption exists.

Do not register DDL target records as default MP IDdl beans or combine this runner with DdlApplicationRunner. This starter owns pool creation, topology validation, TableInfo schema maintenance, DDL scripts, and logical datasource construction. Applications must not rebuild local `ShardingDataSourceBootstrapper` copies.

Opt-in `ddl.auto-migrate: true` requires `ddl.enabled: true` and explicit `ddl.targets` entries containing `logical-name`, `schema`, `role` and `manifest-resource`. Before creating the logical datasource, the starter applies the existing versioned manifest to each designated physical PRIMARY, verifies the routed physical tables, and in read/write mode waits for all replicas to replay the primary WAL position. Targets commit independently; retries validate the installed prefix. See the [Chinese example](README.zh-CN.md#受管-ddl-与-shardingsphere). `ddl.enabled` alone retains its existing behavior and does not trigger migration. TableInfo SQL remains a proposal rather than an automatic schema diff; add a new versioned migration instead of editing an existing Flyway version.

For an additive logical table, `ddl.previous-route-exclusions` may list the current tables absent from each earlier topology, for example `[[new_orders]]`. The starter reconstructs and verifies the earlier fingerprint; a pending versioned migration must record the new fingerprint. Changes to existing routes or primary groups are still rejected, and declarations covering installed history must be retained.

## Configuration and proof

Settings live under `egon.cola.component.mybatis-plus`; source profiles provide complete examples. Common defaults DDL to disabled, the six non-Agent archetypes enable it, and Agent disables it. ID settings use `egon.cola.component.id`.

Tests exercise actual Mapper/plugin paths following the official MP test style. CPU/Mock/H2 results do not prove PostgreSQL DDL, replication, physical placement or query performance. Explicit PostgreSQL tests are environment-gated and skipped by default: `EGON_MP_PG_MODEL_TEST=true` enables the logic-delete and optimistic-lock suites, `EGON_MP_PG_DDL_TEST=true` enables the managed-DDL case, and both read `EGON_MP_PG_URL` / `EGON_MP_PG_USER` / `EGON_MP_PG_PASSWORD` for a dedicated database. `EgonColaPostgreSqlReadwriteIT` additionally requires `EGON_MP_PG_SHARDING_IT=true`, the shared user/password and `EGON_MP_PG_SHARD_{0,1}_{PRIMARY,REPLICA}_URL` for two dedicated physical replication groups; explicitly select this test with Maven. It creates and removes uniquely named test tables. These tests are not part of CI and must be run manually. Validate SQL plans with EXPLAIN on realistic data.
