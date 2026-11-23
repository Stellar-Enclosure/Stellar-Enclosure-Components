# Stellar Enclosure 事务消息 Starter

[English](README.md) | [中文](README.zh-CN.md)

## 解决什么问题

`stellar-enclosure-component-transactional-outbox-starter` 把业务变更和待发送消息原子地写入同一个
PostgreSQL 本地事务，再通过 HTTP、RabbitMQ 或自定义通道异步投递。如果应用在数据库
提交后停止，轮询任务会恢复这条已落库消息。

组件只包含一个 Maven 模块：

| 模块 | 用途 |
|---|---|
| `stellar-enclosure-component-transactional-outbox-starter` | CQE Outbox API、MP 与单库 JDBC PostgreSQL 存储、技术与可选业务 Statemachine、轮询、重试、清理、投递适配、受管 DDL、维护迁移、指标和测试 |

单测仍按组件包路径放在 `src/test/java` 下；集成测试和可执行示例统一放在
`src/test/java/top/egon/cola/component/outbox/integration` 下，并通过 Maven
Failsafe 执行。

实现采用 Transactional Outbox 模式。`TransactionalOutbox` 是业务侧门面，
`DeliveryHandler` 是不同投递通道的策略/适配器扩展点。

## 投递保证与重复窗口

本组件保证的是 **at-least-once（至少一次）**，不是 exactly-once：

1. `enqueue` 在调用方当前业务事务中写入 outbox 记录。
2. 提交后事件只作为降低延迟的唤醒提示。
3. 轮询领取到期或租约过期的记录时跳过被其他事务锁住的行：JDBC 使用 `FOR UPDATE SKIP LOCKED`；MP 使用 advisory lock、`FOR UPDATE NOWAIT` 和版本条件，锁冲突时回滚当前分组事务并排除被锁行后重试。高竞争批次可能产生多次有界重试。
4. 对外投递在数据库事务之外执行。
5. 重试、租约恢复和带 owner 条件的更新使记录依次处于
   `PENDING`、`PROCESSING`、`RETRY_WAIT`、`SUCCEEDED` 或 `DEAD`。

远端处理成功后、本应用记录本地成功前如果崩溃，同一个远端副作用可能再次执行。因此，
所有下游都必须使用稳定的 `messageId` 去重。HTTP 会把它放在 `Idempotency-Key` 和
`X-Egon-Cola-Message-Id`，RabbitMQ 会把它设置为 AMQP `messageId`。

可选的 `idempotencyKey` 会在对应记录仍保留时阻止重复 outbox 记录。同一个 key
如果对应不同的消息内容会直接报冲突，不会静默接受；`availableAt` 不参与内容指纹。

## 单数据库 JDBC 存储

默认仍为 `MYBATIS_PLUS`。独立流程引擎等只使用一个普通 PostgreSQL `DataSource` 的宿主可选择 `JDBC`。
如果宿主本身不使用 MP，应排除这两个 MP 专用传递依赖；已有 MP 宿主的默认依赖关系保持不变。
新 Store 使用独立的 `0.0.2-SNAPSHOT` 版本；消费前从源码构建或发布该版本，不覆盖已有 `0.0.1` 制品：

```xml
<dependency>
    <groupId>top.egon</groupId>
    <artifactId>stellar-enclosure-component-transactional-outbox-starter</artifactId>
    <version>0.0.2-SNAPSHOT</version>
    <exclusions>
        <exclusion>
            <groupId>top.egon</groupId>
            <artifactId>stellar-enclosure-component-common-mybatis-plus-sharding-jdbc-ext-spring-boot-starter</artifactId>
        </exclusion>
        <exclusion>
            <groupId>top.egon</groupId>
            <artifactId>stellar-enclosure-component-common-id-starter</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

```yaml
egon:
  cola:
    component:
      transactional-outbox:
        storage:
          type: JDBC
          data-source-bean-name: businessDataSource
          transaction-manager-bean-name: businessTransactionManager
          jdbc:
            schema: egon_outbox
          validate-schema: true
```

当数据源和事务管理器能够唯一确定时，可省略 Bean 名称。`enqueue` 必须处于绑定到同一个数据源的业务事务中，
worker 使用独立事务。JDBC 模式不会自动建表或修复表结构。先在新建的专属 schema 中，以该 schema 为迁移默认
schema 执行未修改的 `db/transactional-outbox/postgresql/V1__create_transactional_outbox_schema.sql`，
再启用只读结构校验。若使用已有旧 schema，切换运行模式前须停止旧生产者和 worker，并明确评估数据和所有权。

V1 的技术幂等键全局唯一，表中没有租户列。多租户宿主必须从可信授权上下文获取租户，构造消息时使用
`TenantScopedOutboxKey.of(tenantId, operation, commandId)`，并在业务事件契约中携带可信租户；该工具不执行鉴权。
租约过期后，即使尚未被其他 worker 重新领取，旧 owner 也不能确认成功、重试或写死信。投递仍为至少一次。

## 状态机与 CQE

Starter 使用两个职责分离的 Spring Statemachine：

| 状态机 | 所有者 | 决策范围 | 已提交事实的权威存储 |
|---|---|---|---|
| Outbox 生命周期 | 本 Starter，始终启用 | 是否允许入队、领取、回收、重试、完成或进入死信状态 | 当前存储实现的 outbox 行 |
| 业务事件 | 消费方应用，可选启用 | 已发生的业务事实能否推进已注册聚合状态 | 应用业务行与事件 receipt，在同一事务写入 |

生命周期状态包括 `__NEW__`、`PENDING`、`PROCESSING`、`RETRY_WAIT`、`SUCCEEDED` 和
`DEAD`。状态机决定迁移是否合法；数据库的 owner、租约及 MP 模式下的版本条件决定哪个 worker
可以提交。`DELIVERY_RETRYABLE` 根据尝试次数选择 `RETRY_WAIT` 或 `DEAD`。Store 的
显式 `SCHEDULE_RETRY` 操作只负责在 Dispatcher 已决定目标状态后安排下一次重试；
而当租约过期的 `PROCESSING` 行重试预算已耗尽时，`RECLAIM` 会将其直接收敛到 `DEAD`。
Worker 领取只查询当前节点已注册投递通道对应的候选记录（通过 `polling.jitter`
分散多实例轮询节奏，通过 `polling.max-claim-contention-retries` 限制 MP 单批锁冲突重试次数）。

CQE 保持应用边界清晰：

| 角色 | 职责 |
|---|---|
| `Command` | 校验并授权状态变更，在同一个本地事务更新业务聚合并 enqueue 由此产生的 Event |
| `Query` | 只读取业务事实，不进入任一状态机，也不发布 Event |
| `Event` | 记录已经发生的事实；outbox 负责可靠投递，`OutboxCommittedEvent` 仅用于本地唤醒 |

### 可选的业务事件消费者

`statemachine` 投递通道默认关闭。只有注册了应用自有定义及业务聚合持久化实现后，才启用：

```yaml
egon:
  cola:
    component:
      transactional-outbox:
        state-machine:
          execution-timeout: 100ms
          claim-evaluation-timeout: 1s
          business:
            enabled: true
            timeout: 5s
            retry-on-missing-definition: true
```

| 配置键 | 默认值 | 含义 |
|---|---:|---|
| `egon.cola.component.transactional-outbox.state-machine.execution-timeout` | `100ms` | 一次内存状态迁移的最长执行时间 |
| `egon.cola.component.transactional-outbox.state-machine.claim-evaluation-timeout` | `1s` | 技术消息领取状态决策的时间预算 |
| `egon.cola.component.transactional-outbox.state-machine.business.enabled` | `false` | 仅为 `true` 时注册可选业务消费者 |
| `egon.cola.component.transactional-outbox.state-machine.business.timeout` | `5s` | 单次业务迁移及事务的最长执行时间 |
| `egon.cola.component.transactional-outbox.state-machine.business.retry-on-missing-definition` | `true` | 滚动发布期间遇到尚未注册的新版本业务定义时按可重试失败退避，而非立即死信 |

MyBatis-Plus Store 的受管结构与显式迁移配置如下：

| 配置键 | 默认值 | 含义 |
|---|---:|---|
| `egon.cola.component.transactional-outbox.storage.mp.sql-session-factory-bean-name` | `sqlSessionFactory` | Store 使用的 Common MP `SqlSessionFactory` |
| `egon.cola.component.transactional-outbox.storage.mp.auto-initialize-ddl` | `true` | bootstrap 时执行组件 manifest；设为 `false` 时只准备只读结构校验，要求 schema 已存在 |
| `egon.cola.component.transactional-outbox.storage.mp.migration-mode` | `false` | 维护搬迁期间阻止本实例 enqueue 和调度投递 |
| `egon.cola.component.transactional-outbox.storage.mp.migration-lock-timeout` | `30s` | 等待来源表维护锁的最长时间 |
| `egon.cola.component.transactional-outbox.storage.mp.manifest-resource` | `db/egon-outbox-mp/manifest.json` | Common MP 加载的受管 DDL manifest |
| `egon.cola.component.transactional-outbox.storage.mp.previous-route-fingerprints` | `[]` | 已核验的旧拓扑指纹，供版本迁移验证历史 |

`auto-initialize-ddl` 只控制逻辑数据源 bootstrap 是否执行建表脚本。设为
`false` 后，组件仍会只读校验已安装的 manifest、history、列、索引和约束；
schema 缺失或漂移会导致启动失败。它与 `migration-mode` 相互独立，后者只在
维护期间阻止本实例 enqueue 和调度投递。宿主若单独管理组件 schema，可设为
`false`；天枢正常运行配置如此设置，显式迁移 CLI 则用同一个组件 initializer
执行 DDL。

启用业务适配后，注册一个名为 `outboxBusinessStateMachineContextExecutor` 的
`BusinessStateMachineContextExecutor` Bean，以及至少一个
`BusinessStateMachineDefinitionStrategy`。每个定义提供唯一 destination
`machineKey:definitionVersion`、每次创建全新且平面单 region 的
`StateMachineFactory<String, String>`，以及应用自有的
`BusinessStateMachineRepository`。定义应在开启业务事务前校验 payload。Factory
不能返回缓存或已启动的状态机，不能使用 deferred events；状态 Action 不得产生数据库、网络或消息代理副作用。

Repository 必须在事件 tenant 内锁定业务聚合，读取权威状态、版本和 receipt 指纹，并在一次事务中
写入新状态/版本及已消费 `eventId` 的 receipt。`saveTransition` 应基于已加载版本执行 compare-and-set。
Starter 不会为业务应用创建聚合表或 receipt 表。

生产方在 Command 事务中通过现有 outbox 发送已发生的事实。`eventId` 是业务 receipt 身份；
`OutboxMessage.messageId` 是传输身份，两者可以不同：

```java
BusinessStateMachineEvent event = BusinessStateMachineEvent.builder()
        .eventId(eventId)
        .tenantId(tenantId)
        .machineKey("order-payment")
        .definitionVersion(1)
        .businessId(orderId)
        .eventType("PAYMENT_CONFIRMED")
        .expectedVersion(orderVersion)
        .occurredAt(clock.instant())
        .payload(objectMapper.createObjectNode().put("paymentReference", paymentReference))
        .build();

transactionalOutbox.enqueue(OutboxMessage.builder()
        .idempotencyKey("payment-confirmed:" + eventId)
        .channel("statemachine")
        .destination("order-payment:1")
        .contentType("application/json")
        .schemaVersion("1")
        .headers(Map.of(
                "egon-event-id", event.getEventId(),
                "egon-tenant-id", event.getTenantId().toString(),
                "egon-machine-key", event.getMachineKey(),
                "egon-definition-version", event.getDefinitionVersion().toString()))
        .payload(event)
        .build());
```

消费者先校验 envelope 与 tenant，再访问聚合。已持久化且指纹相同的 `eventId` 会在版本校验前返回成功；
相同身份但内容不同属于永久冲突。未来版本可重试，过期版本属于永久失败。任何数据库或迁移错误都会回滚
业务状态与 receipt，之后由既有 outbox 重试/死信流程处理。投递仍是 at-least-once，其他外部消费者仍需自行幂等。

## 不适用的场景

以下需求不适合使用本组件：

- 不允许依赖下游去重、但要求远端副作用 exactly-once；
- 要求跨数据库或外部服务的分布式事务；
- 要求参与 Reactive/R2DBC 事务；
- 业务数据和 outbox 表位于不同数据库；
- 需要 Inbox、管理后台、重放 API 或自动声明 Broker 拓扑。

兼容边界是 Java 25、Spring Boot 4.1.x、PostgreSQL、Stellar Enclosure MyBatis-Plus 和命令式 Spring 事务。
组件不依赖 Redis。

## Maven 依赖

引入组件 BOM，业务应用只依赖 starter：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>top.egon</groupId>
            <artifactId>stellar-enclosure-components-bom</artifactId>
            <version>${stellar-enclosure.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>top.egon</groupId>
        <artifactId>stellar-enclosure-component-transactional-outbox-starter</artifactId>
    </dependency>
</dependencies>
```

Starter 传递依赖 Common MP 扩展与 Spring Statemachine core。应用需要将 Common MP 配置为单个
PostgreSQL `PRIMARY` 或一个及以上主从分片组，并提供现有 MyBatis-Plus `SqlSessionFactory` 和匹配的
`DataSourceTransactionManager`。业务写入和 enqueue 必须使用同一本地数据库事务。仅 HTTP 投递时
增加 `spring-web`，仅 RabbitMQ 投递时增加 `spring-rabbit`。

组件异常位于 `top.egon.cola.component.outbox.common.exception`，统一继承 common-core 的
`CommonException`，因此各 starter 的 `getCode()`、`getStatus()` 与 `isRetryable()` 保持一致。
Starter 仅在应用尚未定义时发布规范的 `egonColaValidationUtils` Bean，并注入自身的
validator；这些 validator 继承 `BaseValidator`，并保留各自的协议校验。

## 受管 MP Schema 与旧数据搬迁

运行期 Store 使用 Stellar Enclosure MyBatis-Plus 扩展，由 Starter 传递引入；不再保留 JDBC fallback。
首次启动前先创建专用且为空的 PostgreSQL schema，并授予应用所需 DDL 权限：

```sql
CREATE SCHEMA egon_outbox;
```

启动时，逻辑数据源 hook 会在创建 logical datasource 前，为每个物理 `PRIMARY` 调用共享的
`EgonColaPostgreDdlRunner`。受管脚本包括原始的
`db/egon-outbox-mp/V20260924_001__initialize_outbox_mp_schema.sql` 和新增的
`db/egon-outbox-mp/V20261027_001__add_outbox_shard_slot.sql`，其校验清单为
`db/egon-outbox-mp/manifest.json`（`family: component-outbox`）。Runner 创建/校验消息表与
`ddl_history`，不发现或修改旧表。非空且没有有效受管历史的 schema 会快速失败。不要复制该脚本到
应用迁移目录、编辑组件 SQL 或手工修复 checksum。

Common MP 必须使用 `LOCAL` 事务。可使用单数据源 `SHARDING`，也可使用一个或多个主从组的
`SHARDING_READWRITE`。多组时 Outbox 表使用 `TECHNICAL_GROUP`，每个事务只能向一个主库组写入；
尝试跨组写入会标记整个事务回滚。
下面 datasource 与 transaction-manager 必须是业务 Command 使用的同一数据源。
合并配置时保留宿主现有 `ignored-tables` 和 mapper locations：

```yaml
egon:
  cola:
    component:
      transactional-outbox:
        enabled: true
        storage:
          data-source-bean-name: orderDataSource
          transaction-manager-bean-name: orderTransactionManager
          validate-schema: true
          mp:
            sql-session-factory-bean-name: sqlSessionFactory
            auto-initialize-ddl: true
            migration-mode: false
            migration-lock-timeout: 30s
            manifest-resource: db/egon-outbox-mp/manifest.json
      mybatis-plus:
        enabled: true
        tenant-id:
          ignored-tables:
            - egon_cola_outbox_message
        ddl:
          enabled: true
        sharding:
          enabled: true
          mode: SHARDING
          config-style: NATIVE
          transaction-default-type: LOCAL
          native-rules-resource: classpath:sharding/outbox-native.yml
      cache:
        enabled: false # 仅适用于本来就没有缓存需求的新宿主。
mybatis-plus:
  mapper-locations: classpath*:/mapper/**/*.xml
```

物理数据源凭据由宿主的 secret 配置管理。唯一 `PRIMARY` 的名称由宿主决定；native rules 必须把以下两张表
准确路由到该主数据源和 schema：

```yaml
databaseName: egon
rules:
  - !SINGLE
    tables:
      - primary.egon_outbox.egon_cola_outbox_message
      - primary.egon_outbox.ddl_history
transaction:
  defaultType: LOCAL
```

将示例 `primary` 替换为实际配置的 `PRIMARY` 名称。保留应用业务表的 route，不使用 `*.*.*`，运行期 mapper
也不应读取旧来源 schema。只有新宿主确实没有缓存需求时才可设置 `cache.enabled: false`；已有宿主保留现有缓存配置。
`mapper-locations` 必须覆盖 `mapper/outbox/OutboxMessageMapper.xml`，否则启动校验失败。

多主从组可使用以下 STRATEGY 配置替换上例的 `sharding` 部分，业务表规则需一并保留；所有主库组
必须包含在 `shard-groups` 中，`data-source` 指定旧消息所在的历史组：

```yaml
mode: SHARDING_READWRITE
config-style: STRATEGY
transaction-default-type: LOCAL
data-sources:
  - { name: shard_0_primary, logical-name: shard_0, role: PRIMARY, driver-class-name: org.postgresql.Driver, jdbc-url: "${PG_0_PRIMARY_URL}", username: "${PG_USER}", password: "${PG_PASSWORD}" }
  - { name: shard_0_replica, logical-name: shard_0, role: REPLICA, driver-class-name: org.postgresql.Driver, jdbc-url: "${PG_0_REPLICA_URL}", username: "${PG_USER}", password: "${PG_PASSWORD}" }
  - { name: shard_1_primary, logical-name: shard_1, role: PRIMARY, driver-class-name: org.postgresql.Driver, jdbc-url: "${PG_1_PRIMARY_URL}", username: "${PG_USER}", password: "${PG_PASSWORD}" }
  - { name: shard_1_replica, logical-name: shard_1, role: REPLICA, driver-class-name: org.postgresql.Driver, jdbc-url: "${PG_1_REPLICA_URL}", username: "${PG_USER}", password: "${PG_PASSWORD}" }
tables:
  egon_cola_outbox_message: { type: TECHNICAL_GROUP, data-source: shard_0, shard-groups: [shard_0, shard_1], sharding-column: shard_slot, schema: egon_outbox }
  ddl_history: { type: SINGLE, data-source: shard_0, schema: egon_outbox }
```

每个主库都必须先有空的 `egon_outbox` schema；逻辑 `ddl_history` 只映射历史组，但组件会在每个
主库创建并检查独立的历史表。事务内首个业务写入确定目标组，随后的 Outbox 写入使用同组与同一
Spring 事务。如果先入队，可在 `OutboxMessage.builder()` 中设置 `.shardGroup("shard_1")`；之后
业务写入必须命中该组，否则整个事务回滚。没有已确定的组且未显式指定时会拒绝入队。
业务 JDBC 必须通过 `JdbcTemplate` 或 `DataSourceUtils` 使用 Spring 绑定连接；多分片 Outbox 模式在已有事务连接
时拒绝直接调用 `DataSource.getConnection()` 创建未托管连接。

全局 `messageId` 和 `idempotencyKey` 使用稳定的分片归属。默认生成的 `messageId` 自动加前缀；
自定义值可通过 `OutboxShardIdentity.forGroup("shard_1", "shard_0", value)` 生成，其中第二个参数
必须是配置的历史组。无前缀的旧标识只归属历史组；若标识与事务目标组不匹配则拒绝并回滚。
启动时逐主库扫描现有 Outbox 的槽位及标识，发现归属错误或槽位碰撞就拒绝启动，不自动移动或改写数据。
固定的组名和历史组名属于持久路由契约，不能随意重命名。
新版本的数据库检查约束也会拒绝标识前缀与 `shard_slot` 不一致的写入；多组启用前必须停掉所有
旧版生产者并核验历史数据。如果旧标识恰好占用了保留前缀且归属不匹配，迁移会失败，需要人工规划，
不能自动改写标识。自定义生成器的原始 `messageId` 最长 52 字符，前缀后的总长度不得超过 64。

普通业务查询及 Outbox 候选扫描走副本，业务写入和 Outbox 写入走主库。为保证同一事务内的
幂等校验、领取和租约确认，Starter 仅放行固定的 Outbox 技术锁定查询到主库；业务锁定读和通用
路由 Hint 仍被拒绝。副本延迟可能使提交后的即时唤醒暂时找不到候选消息，后续轮询会重试。
Outbox 受管 DDL 完成后会等待副本回放到主库 WAL 位置，等待时间使用 Common MP 的
`ddl.topology-ready-timeout`（默认 60 秒）。如果启用了 Common MP `ddl.auto-migrate`，其
`targets` 也必须覆盖 Outbox schema，manifest 使用 `classpath:db/egon-outbox-mp/manifest.json`。
从旧拓扑升级时，先核验旧 Outbox `ddl_history` 中的路由指纹，并将其填入
`egon.cola.component.transactional-outbox.storage.mp.previous-route-fingerprints`；该配置只接受
已核验的前序指纹，新增版本必须尚未执行，以便记录新拓扑。若新增版本已在旧拓扑执行，需要另行
规划新的版本化迁移，不能修改历史。Common MP 的业务表迁移和跨组数据迁移仍需独立规划。
MP 领取、完成和清理按组分别开启事务；某组故障后，已领取但尚未交付的消息会在租约到期后恢复。
直接调用 `OutboxStore` 完成、重试或死信时，多组模式必须传入领取结果的 `shardSlot`；旧的无槽位
重载仅适用于单组，避免相同内部 ID 在不同库中产生歧义。
旧表搬迁需要读取即时一致的来源和目标，在 `SHARDING_READWRITE` 下会以
`OUTBOX_MIGRATION_REQUIRES_SINGLE_SOURCE_MODE` 拒绝；应先在单数据源模式完成并验证搬迁。
可选业务状态机如果使用业务表 `SELECT ... FOR UPDATE`，也不适用于该严格路由模式。

如果存在旧数据，不要复制旧 `V1` 文件启动切换。保持
`db/transactional-outbox/postgresql/V1__create_transactional_outbox_schema.sql` 不变，并将旧表保留在原 schema。
对于 MP 运行模式，旧 schema 仅作为显式、只读搬迁来源。

受控搬迁时，先停止**所有**新旧生产者和 worker，并等待在途事务结束。随后在每个新实例启用
`migration-mode: true`。该本地开关拒绝新的 `enqueue`、`submitDue` 和 `submitMessageIds` 调用；它不能停止其他副本或旧进程。
受管 DDL 就绪后，使用 `OutboxLegacyMigrationService` 维护 Bean 和显式旧来源 schema：

```java
OutboxMigrationResult result = outboxLegacyMigrationService.migrate(
        "legacy_outbox", 100, false);
if (!result.isVerified()) {
    throw new IllegalStateException("Do not switch traffic before verifying every outbox row");
}
```

`verifyOnly: true` 只读比较。复制模式保留旧消息的全部 22 列，包括原始 payload/header 字符串、身份、指纹、五种状态、尝试次数、
owner 和时间戳。目标额外设置技术 tenant `0`、搬迁审计身份、`deleted_at = NULL` 与 `version = 0`。每批独立提交。
中断或提交结果未知时可以显式重试：完全匹配的身份会跳过；ID、message ID、key 或内容冲突会停止，且不会覆盖已有数据。
只有完整比较来源与目标、确认没有缺失/多余/不同记录时 `verified` 才为真，单看行数不够。服务不会在启动时运行、清空来源或自动切换 `migration-mode`。

只有报告验证通过且所有新消费者/定义都已就绪后，才部署 `migration-mode: false` 并重新启用生产者。保留旧表与 SQL 历史。
新表开始接收写入前，可用旧应用版本和旧表回退；新写入开始后旧表就已过期，不能只改配置回退。先停写并向前核对修复。

## 直接 API 示例

推荐显式调用 API，让业务写入和 enqueue 处于同一个命令式 Spring 事务：

```java
@Service
public class OrderApplicationService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionalOutbox transactionalOutbox;

    public OrderApplicationService(
            JdbcTemplate jdbcTemplate,
            TransactionalOutbox transactionalOutbox
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionalOutbox = transactionalOutbox;
    }

    @Transactional
    public OutboxReceipt createOrder(String orderId) {
        jdbcTemplate.update(
                "insert into business_order(id) values (?) on conflict do nothing",
                orderId
        );
        return transactionalOutbox.enqueue(OutboxMessage.builder()
                .idempotencyKey("order:created:" + orderId)
                .channel("order-events")
                .destination("order-created-v1")
                .payload(new OrderCreatedEvent(orderId))
                .schemaVersion("1")
                .build());
    }

    public record OrderCreatedEvent(String orderId) {
    }
}
```

`enqueue` 要求当前存在已开启事务同步、且绑定到组件配置 `DataSource` 的 Spring
事务。缺少兼容事务时，会在写入前直接失败。

## 注解示例

`@TransactionalMessage` 可以从同步方法的返回值中解析一条 `OutboxMessage`。它会使用
指定事务管理器创建或加入 `REQUIRED` 事务：

```java
@Service
public class AnnotatedOrderApplicationService {

    private final JdbcTemplate jdbcTemplate;

    public AnnotatedOrderApplicationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @TransactionalMessage(message = "#result.outboxMessage()")
    public CreateOrderResult createOrder(String orderId) {
        jdbcTemplate.update("insert into business_order(id) values (?)", orderId);
        return new CreateOrderResult(
                orderId,
                OutboxMessage.builder()
                        .idempotencyKey("order:annotated-created:" + orderId)
                        .channel("order-events")
                        .destination("order-created-v1")
                        .payload(new OrderCreatedEvent(orderId))
                        .build()
        );
    }

    public record CreateOrderResult(String orderId, OutboxMessage outboxMessage) {
    }

    public record OrderCreatedEvent(String orderId) {
    }
}
```

表达式必须解析出非空 `OutboxMessage`，否则整个事务回滚。方法必须是经过代理的 Spring
Bean 方法，并且为 public、非 static、非 final、同步返回；`Future`、
`CompletionStage` 和响应式返回类型会被拒绝。如果方法同时声明 `@Transactional`，
必须使用 `REQUIRED`、非只读并选择同一个事务管理器。Spring 代理的常规限制（例如
self-invocation）同样适用。

## DataSource 选择

只有一个 `DataSource` 和一个事务管理器时无需额外配置。存在多个候选时，组件会选择唯一
的 `@Primary`；否则必须同时显式指定两个 Bean：

```yaml
egon:
  cola:
    component:
      transactional-outbox:
        storage:
          data-source-bean-name: orderDataSource
          transaction-manager-bean-name: orderTransactionManager
```

启动校验会检查选定 `DataSourceTransactionManager` 持有 Common MP 选定的数据源，且指定的
`SqlSessionFactory` 使用相同数据源及 `SpringManagedTransactionFactory`。业务写入和 outbox 写入必须处于同一个
本地 PostgreSQL 事务；logical datasource 名称相同并不能证明跨库原子性。

## 核心运行配置

默认值均为有限、有界配置：

```yaml
egon:
  cola:
    component:
      transactional-outbox:
        enabled: true
        polling:
          enabled: true
          fixed-delay: 1s
          batch-size: 100
          concurrency: 4
        delivery:
          timeout: 10s
          lease-duration: 60s
          queue-capacity: 1000
        retry:
          max-attempts: 10
          initial-delay: 1s
          multiplier: 2.0
          max-delay: 5m
          jitter: 0.2
          recover-exhausted: false
          exhausted-retry-delay: 5m
        payload:
          max-bytes: 1MB
          max-header-count: 64
          max-header-bytes: 16KB
        shutdown:
          grace-period: 30s
```

到期时间和租约过期均以数据库时间为准。每次 claim 都使用包含节点标识和 claim UUID 的
唯一 owner token，成功、重试、死亡状态都只允许当前 owner 以 CAS 方式更新。

### 安全调度与同版本回退

以下配置键使用前缀 `egon.cola.component.transactional-outbox`。

| 配置键 | 默认值 | 作用 |
| --- | --- | --- |
| `egon.cola.component.transactional-outbox.dispatch.mode` | `LEGACY` | LOCAL 使用就绪 worker 和带租约校验的消息 ACK。COORDINATED 同时要求队列归属。 |
| `egon.cola.component.transactional-outbox.dispatch.consumption-enabled` | `true` | 设为 false 保留入队，暂停新领取和队列续约。排空中的合法在途消息仍可 ACK。 |
| `egon.cola.component.transactional-outbox.dispatch.max-claim-batch` | `1` | 受实际就绪 worker 数和 polling.batch-size 约束。MP 固定为 1。 |
| `egon.cola.component.transactional-outbox.dispatch.max-concurrent-shards` | `2` | 限制并行操作的分片数，每实例每分片至多一个领取事务。 |
| `egon.cola.component.transactional-outbox.dispatch.ack-reserve` | `1s` | 消息租约到期之前为结果写入预留的时间。 |
| `egon.cola.component.transactional-outbox.coordination.heartbeat-interval` | `5s` | 队列归属和成员刷新基准周期。 |
| `egon.cola.component.transactional-outbox.coordination.lease-duration` | `20s` | 队列归属租约，与消息租约独立。 |
| `egon.cola.component.transactional-outbox.coordination.membership-ttl` | `30s` | 成员上次心跳后保持参选资格的期限。 |
| `egon.cola.component.transactional-outbox.coordination.claim-timeout` | `2s` | 单分片短事务预算，由两个安全模式共用。 |
| `egon.cola.component.transactional-outbox.coordination.member-retention` | `1h` | 过期成员行保留期。 |

启用 `LOCAL` 或 `COORDINATED` 消费时，保持轮询开启与
`retry.recover-exhausted=false`。投递超时加 ACK 预留应短于消息租约。
JDBC 的 `COORDINATED` 依赖部署方安装 V2。MP 使用受管清单，队列成员表
`egon_cola_outbox_queue_member` 和归属表 `egon_cola_outbox_queue_lease`
沿用消息表的 slot、actual data nodes、主从组以及 tenant ignore 配置。
放开消费前逐组核对主库历史与各副本回放。

同制品从 `COORDINATED` 切回 `LOCAL` 的顺序如下。

1. 在共享存储域统一暂停新领取，停止并排空协调消费者。仅生产实例以消费关闭
   方式继续入队。记录积压、在途任务和剩余租约。
2. 确认旧消费者停止领取。等待合法在途 ACK 完成，未完成任务交给消息租约自然
   到期。保留消息 ID、任务状态、队列 epoch 和受管版史。
3. 使用同制品启动少量 `LOCAL` 消费者，消费打开且领取上限为 1。逐分片核对
   投递、生产写入、队列 epoch 和受管版史再逐步扩容。任一核对失败可再次暂停
   消费。旧制品可能因新增 MP 版史拒绝启动。

`egon.cola.outbox.dispatch` 和 `egon.cola.outbox.dispatch.duration` 按分片、
channel、阶段聚合。对照 `claim_committed`、`handoff`、`handler_started`、
`ack_confirmed`。已有 delivery 指标记录 handler 返回，`indeterminate`
表示数据库 ACK 仍未确定。

上线前执行下文的真实拓扑验证，并在目标环境测量连接池借用、语句／锁等待和提交
预算。在相同负载下对比每次成功投递的 SQL 工作量、业务事务 p95/p99、WAL 和副本
延迟。以目标环境数据决定是否扩大批量，期间保留上限 1。回放暂停仅用于指定隔离
测试副本。

## HTTP 投递

HTTP 默认关闭。消息只保存逻辑 destination，不允许 outbox 记录携带任意 URL：

```yaml
egon:
  cola:
    component:
      transactional-outbox:
        http:
          enabled: true
          destinations:
            order-api:
              uri: https://service.example.internal/events/orders
              method: POST
              connect-timeout: 2s
              read-timeout: 10s
              fixed-headers:
                X-Event-Source: order-service
```

enqueue 时使用 `channel("http")` 和 `destination("order-api")`。客户端不会跟随重定向。
凭证应由 `HttpCredentialProvider` Bean 在投递时提供，不要把请求头写入 `OutboxMessage`：
`Authorization`、`Proxy-Authorization`、`Cookie`、`Set-Cookie`、`Host`、`Content-Length`、
`Transfer-Encoding`、`Connection` 在 enqueue 校验阶段就会被拒绝，投递时也会再次过滤。

```java
@Bean
HttpCredentialProvider outboxHttpCredentials(TokenSupplier tokenSupplier) {
    return destination -> Map.of(
            HttpHeaders.AUTHORIZATION,
            "Bearer " + tokenSupplier.currentToken()
    );
}
```

组件日志不会记录 payload、凭证、完整 URL 或响应体。

## RabbitMQ 投递

RabbitMQ 默认关闭，也不会创建 exchange、queue 或 binding。业务应用必须单独维护拓扑，
并开启 correlated publisher confirm、publisher return 和 mandatory：

```yaml
spring:
  rabbitmq:
    publisher-confirm-type: correlated
    publisher-returns: true
    template:
      mandatory: true

egon:
  cola:
    component:
      transactional-outbox:
        rabbitmq:
          enabled: true
          confirm-timeout: 5s
          destinations:
            order-events:
              exchange: business.events
              routing-key: order.created.v1
              mandatory: true
```

enqueue 时使用 `channel("rabbitmq")` 和 `destination("order-events")`。只有收到关联 ACK
且没有 mandatory return 才算投递成功。NACK、return、超时或连接异常会按规则重试或
进入 `DEAD`；每次尝试都保留同一个稳定 `messageId`。

## 自定义 DeliveryHandler

每个自定义通道注册一个 handler。destination 会在 enqueue 阶段、插入记录前完成校验：

```java
@Bean
DeliveryHandler orderEventsDeliveryHandler(OrderEventClient client) {
    return new DeliveryHandler() {
        @Override
        public String channel() {
            return "order-events";
        }

        @Override
        public void validateDestination(String destination) {
            if (!"order-created-v1".equals(destination)) {
                throw new OutboxValidationException("Unknown order destination");
            }
        }

        @Override
        public DeliveryResult deliver(DeliveryContext context) {
            return client.send(context.messageId(), context.payload())
                    ? DeliveryResult.success()
                    : DeliveryResult.retryableFailure(
                            "ORDER_DELIVERY_UNAVAILABLE",
                            "ORDER_DELIVERY_UNAVAILABLE"
                    );
        }
    };
}
```

handler 在业务/数据库事务之外执行，必须遵守 `context.deadline()`，不能无限等待；对明确
不可重试的数据应返回 permanent failure。

## 重试、租约、DEAD 与崩溃恢复

重试采用带 jitter 的有界指数退避。可重试结果进入 `RETRY_WAIT`；耗尽
`max-attempts`、永久失败或未知 channel 会进入 `DEAD`。可以注册
`OutboxDeadLetterListener` 观察死亡状态，但监听器异常不会撤销已保存的状态。

可设置 `retry.recover-exhausted=true`，在 `exhausted-retry-delay` 后按分片和版本条件重新投递
因可重试错误耗尽次数而进入 `DEAD` 的消息。每次恢复重置本轮尝试次数，保留相同消息标识；
永久错误和未知 channel 不会自动恢复。默认关闭，消费者仍须处理重复投递。

worker 在 `PROCESSING` 时崩溃，其他 worker 可在 `locked_until` 后重新 claim。旧 worker
不能覆盖新 owner，因为所有终态更新都比较 owner token。单条毒消息也不会阻断同批次的
其他记录。

## 清理与幂等窗口

清理默认关闭：

```yaml
egon:
  cola:
    component:
      transactional-outbox:
        cleanup:
          enabled: true
          success-retention: 7d
          fixed-delay: 1h
          batch-size: 500
```

清理只删除过期的 `SUCCEEDED`，始终保留 `DEAD`。成功记录删除后，对应
`idempotencyKey` 在 outbox 一侧的去重窗口也随之结束；下游去重保留期必须覆盖业务真正
需要的时长。

## 指标与安全日志

当容器中同时存在 Micrometer `MeterRegistry` 与 `DataSource`，且应用未自定义
`OutboxMetrics` 时，组件记录：

- `egon.cola.outbox.backlog`
- `egon.cola.outbox.enqueue`
- `egon.cola.outbox.claim`
- `egon.cola.outbox.delivery`
- `egon.cola.outbox.delivery.duration`
- `egon.cola.outbox.retry`
- `egon.cola.outbox.dead`
- `egon.cola.outbox.lease_lost`

指标标签只使用 channel、result 等有界值，不使用 messageId、destination 或错误文本。
日志和持久化错误摘要不会记录 payload、凭证、`Authorization`、`Cookie`、响应体或
完整 URL。

## PostgreSQL 集成测试

默认 Maven Reactor 不连接开发机上的 PostgreSQL。需要验证真实 PostgreSQL 事务、租约、旧数据复制/续跑、
并发恢复和查询计划时，确保 Docker 可用并执行：

```bash
EGON_OUTBOX_TEST_POSTGRES_ENABLED=true ./mvnw -B -ntp \
  -pl :stellar-enclosure-component-transactional-outbox-starter \
  -am clean verify
```

测试使用隔离的 PostgreSQL 16.6 Testcontainer，不读取本机 PostgreSQL 用户名或密码。
`OutboxMpMigrationIntegrationTest` 也受该开关控制：`test-compile` 会编译它，但只有显式设置该环境变量才会执行。
GitHub CI 的 `CI Backend` 任务为整个 Reactor 设置
`EGON_OUTBOX_TEST_POSTGRES_ENABLED=true`，因此这组测试始终执行、不会 assume-skip；
Docker 或数据库启动失败会使任务失败，不会静默跳过。

`OutboxPostgresqlReadwriteIntegrationTest` 是独立的主从集群测试，默认跳过，不创建数据库进程。
仅对专用、空的 PostgreSQL 主从数据库显式设置 `EGON_OUTBOX_PG_READWRITE_IT=true`，以及
`EGON_OUTBOX_PG_PRIMARY_URL`、`EGON_OUTBOX_PG_REPLICA_URL`、`EGON_OUTBOX_PG_USER` 和
`EGON_OUTBOX_PG_PASSWORD` 后执行。测试会创建并清理 `egon_outbox` schema 及测试业务表；
若该 schema 已存在则立即失败，不会修改它。账号还需要执行 `pg_control_system()` 的权限。

`OutboxPostgresqlMultishardIntegrationTest` 同样默认跳过。仅在两套专用、空的主从数据库上，显式设置
`EGON_OUTBOX_PG_MULTISHARD_IT=true`、共享的 `EGON_OUTBOX_PG_USER` / `EGON_OUTBOX_PG_PASSWORD`，以及
`EGON_OUTBOX_PG_SHARD_0_PRIMARY_URL`、`EGON_OUTBOX_PG_SHARD_0_REPLICA_URL`、
`EGON_OUTBOX_PG_SHARD_1_PRIMARY_URL`、`EGON_OUTBOX_PG_SHARD_1_REPLICA_URL`。测试在两套主库创建并清理
`egon_outbox` schema 和专用业务表；任一 schema 已存在时不会覆盖它。两项主从测试都需要
专用副本进程，以及能执行 `pg_wal_replay_pause()` 和 `pg_wal_replay_resume()` 的账号。
多分片测试启动两个协调消费者，再使用同一版本切回 `LOCAL`。串行运行两项测试并核对跳过数。

```bash
./mvnw -B -ntp -pl :stellar-enclosure-component-transactional-outbox-starter -am \
  -Dtest=OutboxQueueCoordinatorTest,OutboxDispatcherTest,OutboxPollerTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=OutboxPostgresqlMultishardIntegrationTest,OutboxPostgresqlReadwriteIntegrationTest \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

## 明确的支持边界

支持：Java 25、Spring Boot 4.1.x、PostgreSQL、基于 Egon MP Store 的命令式 Spring 事务、技术生命周期及可选业务 Statemachine、
HTTP 投递、RabbitMQ 投递和自定义同步 handler。

不支持：Reactive/R2DBC 事务、跨数据库事务、分布式事务、exactly-once、自动旧数据搬迁或破坏性回滚、
Admin/UI、重放 API、Inbox 处理和自动声明 Broker 拓扑。
