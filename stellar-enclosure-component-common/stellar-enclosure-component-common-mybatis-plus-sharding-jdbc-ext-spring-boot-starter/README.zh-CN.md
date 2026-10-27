# MyBatis-Plus + ShardingSphere-JDBC Starter

消费本 Starter 必须同时使用 PostgreSQL、MyBatis-Plus 与 ShardingSphere-JDBC。排除 `shardingsphere-jdbc` 不受支持，编译或启动必须失败。逻辑 `@Primary DataSource` 由一份 YAML（前缀 `egon.cola.component.mybatis-plus.sharding`）描述。`config-style: STRATEGY` 与 `config-style: NATIVE` 互斥。

## 分片主从读写分离

`mode: SHARDING_READWRITE` 按 `logical-name` 将物理数据源组成主从组，每组必须恰好有一个 `PRIMARY` 和至少一个 `REPLICA`。分片节点使用组名；普通查询和事务内查询路由到该组副本，写入路由到该组主库，副本不可用时不回退到主库。`SHARDING` 保留原有单数据源/纯分片行为，不提供只读副本保证。

```yaml
egon:
  cola:
    component:
      mybatis-plus:
        sharding:
          enabled: true
          mode: SHARDING_READWRITE
          config-style: STRATEGY
          transaction-default-type: LOCAL
          data-sources:
            - { name: shard_0_primary, logical-name: shard_0, role: PRIMARY, driver-class-name: org.postgresql.Driver, jdbc-url: "${PG_0_PRIMARY_URL}", username: "${PG_USER}", password: "${PG_PASSWORD}" }
            - { name: shard_0_replica, logical-name: shard_0, role: REPLICA, driver-class-name: org.postgresql.Driver, jdbc-url: "${PG_0_REPLICA_URL}", username: "${PG_USER}", password: "${PG_PASSWORD}" }
            - { name: shard_1_primary, logical-name: shard_1, role: PRIMARY, driver-class-name: org.postgresql.Driver, jdbc-url: "${PG_1_PRIMARY_URL}", username: "${PG_USER}", password: "${PG_PASSWORD}" }
            - { name: shard_1_replica, logical-name: shard_1, role: REPLICA, driver-class-name: org.postgresql.Driver, jdbc-url: "${PG_1_REPLICA_URL}", username: "${PG_USER}", password: "${PG_PASSWORD}" }
          tables:
            orders:
              type: COMPLEX_TENANT_THEN_BUSINESS
              shard-groups: [shard_0, shard_1]
              sharding-column: tenant_id
              table-columns: [tenant_id, order_id]
              root-key-name: order_id
```

组件使用 ShardingSphere `DYNAMIC` 事务读策略和 `ROUND_ROBIN` 副本选择。启动时检查每组的 `pg_control_system()` 系统标识与数据库名一致，每次借用连接检查角色与系统标识，执行 SQL 前再次检查 `pg_is_in_recovery()`；严格模式禁止 `HintManager` 和影响路由的 SQL Hint，锁定读等路由到错误角色的 SQL 也会被拒绝。数据库账号必须能执行这些系统函数，每条业务 SQL 会增加一次角色检查往返。严格模式不支持通过 `unwrap` 绕过数据源守卫，也不支持业务存储过程调用。事务内副本读看不到本事务未提交的写入，也可能受到复制延迟影响；不要将它用于要求读己写的业务。故障切换、复制健康和延迟监控仍由 PostgreSQL 集群管理，应用级缓存不提供强一致保证。

启用 transactional-outbox 时，其逻辑数据源工厂仅放行固定的 Outbox 技术锁定查询和 schema 校验查询到主库，业务查询仍遵循严格路由。多组使用 `TECHNICAL_GROUP` 与固定 `shard_slot`，同一事务的业务与 Outbox 写入必须落在同一主库组；物理数据源守卫也会拦截跨组 JDBC 写入。详见 [Outbox 配置](../../stellar-enclosure-component-transactional-outbox-starter/README.zh-CN.md#受管-mp-schema-与旧数据搬迁)。

`STANDARD_TENANT_ID` 和 `COMPLEX_TENANT_THEN_BUSINESS` 可用 `shard-groups` 选择该表参与分片的逻辑组，避免把仅用于元数据的 PRIMARY 包含进去；不配置时沿用所有 PRIMARY 组。NATIVE 模式可以提供 `!READWRITE_SPLITTING`，严格模式要求其组映射与数据源配置完全一致且事务读策略为 `DYNAMIC`；分片算法必须能与本组件的写目标守卫验证一致，无法验证的自定义算法会被拒绝。原有 `SHARDING` NATIVE 配置不受这一严格模式限制。更改组顺序、组数量或规则前必须规划既有数据迁移。

推荐 STRATEGY：订单/明细使用 `COMPLEX_TENANT_THEN_BUSINESS`（先 `tenant_id` 再 `order_id`）。默认事务 LOCAL。STRATEGY 下 `transaction-default-type` 一定作为生成规则的 `defaultType` 写出；NATIVE 下它只是宿主 YAML 未写 `defaultType` 时的回退。校验只接受 `LOCAL` 或 `XA`，其他取值以 `LOCAL_TRANSACTION_REQUIRED` 失败。选择 XA（`transaction-default-type: XA`）只是转发该取值：本 starter 不依赖任何 XA 事务 provider，宿主必须自行引入 provider 及其恢复管理器。

`tables.<name>.schema` 可选，缺省为 `public`。仅 `SINGLE` 表支持非 `public` schema；其他表策略配置时以 `SCHEMA_OVERRIDE_REQUIRES_SINGLE` 拒绝。解析后的 schema 会纳入路由 profile 和拓扑指纹。

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

NATIVE 逃生舱：`native-rules-resource` 指向一份 ShardingSphere rules 文件。同一 YAML 不得再声明 STRATEGY `tables`。

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

本组件使用 MyBatis-Plus 3.5.17，为 PostgreSQL 提供模型约束、Repository 命令、显式 SQL 查询、MybatisBatch、租户/版本保护、分片拓扑及受管 DDL 运行器。业务层保持 `Controller → Service → Repository → Mapper`；在现有 COLA 项目中，Application/Domain Service 保留业务职责，Repository 位于 infrastructure。

## 模型与主键

业务 PO 继承 `EgonModel<PO>`，配置 `@TableName`；不要重复声明技术字段。

| Java 字段 | SQL 列 | 类型与语义 |
| --- | --- | --- |
| id | id | Long / BIGINT，继承强制 `@TableId(type=ASSIGN_ID)` |
| tenantId | tenant_id | Long / BIGINT NOT NULL |
| createUserId / updateUserId | create_user_id / update_user_id | String，来自当前用户上下文 |
| createTime / updateTime | create_time / update_time | Instant，UTC、微秒精度 |
| deletedAt | deleted_at | LocalDateTime / timestamp(6)，未删除 NULL |
| version | version | Long / BIGINT NOT NULL，插入 0，更新与软删递增 |

逻辑删除使用 `@TableLogic` 与 `(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')`。主键适配器 `EgonColaIdentifierGenerator` 调用进程级静态 `SnowflakeIdGenerator`，该引擎由 `IdGeneratorAutoConfiguration` 在校验 ID 配置后一次性初始化，不重新实现分布式算法。实例必须配置唯一 `EGON_COLA_COMPONENT_ID_MACHINE_ID`；计数器只用于隔离测试。`@KeySequence` 与该 ASSIGN_ID 合同冲突，启动校验拒绝组合。

Common 允许任意非空 Long tenantId；ShardingSphere 宿主要求正 Long 分片键。技术字段统一由 MetaObjectHandler 填充，MyBatis-Plus 启用即装配该 handler，没有开关；扩展钩子只允许处理业务字段。SQL Injector 不承担元数据填充职责。

租户身份不再以注入 Provider 的方式传递。`EgonColaTenantIdProvider` 是基于 MDC 的静态不可实例化入口，MDC 键名由 `tenant-id.mdc-key` 配置（默认 `tenantId`），每次调用重新读取 SLF4J；上下文缺失或非数值时以 `TENANT_CONTEXT_MISSING` / `TENANT_CONTEXT_MALFORMED` 失败。模型校验共享同一静态绑定入口，若已有存活的冲突 owner 则以 `MODEL_VALIDATION_BINDING_CONFLICT` 拒绝重复绑定。

## Repository 与 CQRS

技术接口为 `EgonColaIRepository<T>`（扩展官方 `IRepository`），实现基类为 `EgonColaRepository<M,T>`。业务 Domain Service 不继承技术 CRUD 接口，也不携带 PO 泛型。具体 Repository 使用具名 Bean、Lombok 构造注入，只提供 mapper 与 properties 两个 getter，基类守卫从上述静态入口读取租户与校验上下文；参考脚手架中的具体实现。

- 命令使用 save、带版本的 updateById/removeById、受保护的批量 API；调用方检查影响行数。
- 业务 Query 使用命名 Mapper XML。每个 Mapper 都需提供 `selectActiveById`、`selectActiveByIds`、`deleteVersionedById`。
- ActiveRecord 不可用。QueryChain、lambdaQuery 等链式查询及通用 Query Wrapper 入口快速拒绝；不要使用 `.last()` 拼接 SQL。
- 自定义 UPDATE 中 MP 乐观锁插件会先增加实体版本，WHERE 必须绑定 `#{MP_OPTLOCK_VERSION_ORIGINAL}`。同时保留 `deleted_at IS NULL`、租户以及业务期望状态。
- 更新前从调用方或同一事务加载的行保留 id、tenant、create metadata、version。零行冲突不得当作成功。
- MybatisBatch 必须在相同 DataSource 的真实 Spring 事务内运行；空集合不发 SQL，重复/非法 ID 提前拒绝。默认分块 1000，总集合上限 10000，失败标记 rollback-only。

只在明确需要批量/特殊 SQL 的场景使用 Mapper 扩展。没有注册 MyBatis-Plus SQL Injector：启动校验要求上述三个语句必须来自 Mapper XML。字段 TypeHandler 用于 JSONB、数组等真实类型差异；Agent 的 JSONB 使用字段专用 handler，不覆盖全局 String handler。持久化枚举需唯一 `@EnumValue`，对外枚举值需匹配 `@JsonValue`/Jackson 合同，启动时校验。

## 注解缓存

`EgonColaRepository` 仅负责持久化，已移除 `getByCache`、`listByCache`、`getCachePortProvider`、`cacheRegionName` 与 CRUD 自动失效。
具体 Repository 在 public、非 final 业务方法上使用 Spring `@CacheConfig`、`@Cacheable`、`@CachePut`、`@CacheEvict`、`@Caching`，由外部 Bean 经代理调用，方法内部沿用基类 CRUD；基类的数据校验、租户与批量事务守卫保持不变。
单键读/写/失效统一使用具名 `keyGenerator = "egonColaRepositoryKeyGenerator"`，由它产出可信的 `tenant:id`；多参数与集合批量签名不支持该策略，需使用独立区域或显式失效。
所有影响缓存数据的写路径必须声明失效，普通 CRUD 不再自动失效；`@CachePut` 缓存方法返回值，不能挂在返回 boolean 的更新方法上。

两级缓存是必需组件（`egon.cola.component.cache.enabled` 缺省 `true`），本 starter 依赖受管理的 common-cache starter 并在启动时校验 CacheManager：组件启用而宿主仅存在不兼容的 `CacheManager` 时以 `CACHE_MANAGER_INCOMPATIBLE` 失败，不静默退回单级缓存；显式关闭组件时跳过该校验，键生成器仍可用。
Key、TTL 采样、事务提交/回滚、`sync` 与 `unless` 的限制、组合注解和手动操作以 [缓存 starter 文档](../stellar-enclosure-component-common-cache-spring-boot-starter/README.zh-CN.md) 为单一事实源。

## SQL 与事务保护

原始 SQL Guard 在执行前验证正 ID 范围；TenantLine 后再次验证最终 SQL 的租户、active、版本和审计条件。全表更新/删除拦截、乐观锁、PG 分页与 LOCAL 写目标保护统一装配。动态表名默认关闭，只接受显式白名单映射。

LOCAL Guard 与声明的事务类型无关，跨 SqlSessionFactory 检查事务目标。一个事务可写同一物理组的多表，跨组写入以 `LOCAL_WRITE_TARGET_MISMATCH` 拒绝，无真实事务的多目标写入以 `LOCAL_TRANSACTION_REQUIRED` 拒绝。`BASE` 在拓扑校验阶段即被拒绝，本组件既不提供也不验证 XA 恢复管理器。精确 root-key 批量语句必须通过 `local-write-guard.allowed-root-statements` 注册完整 statementId 和列名；不接受普通业务以任意 Wrapper 绕过 ID/版本保护。

分页上限 500。IllegalSQL 仅 `dev` 允许。同时启用 dev/prod 会被拒绝。

## 受管 DDL 与 ShardingSphere

`EgonColaPostgreDdlRunner` 接受显式物理 PRIMARY、schema、role 和 SHA-256 manifest。先执行 schema advisory lock，再校验空/受管历史与脚本前缀；SQL 和 ddl_history 在同一连接、同一事务提交。未知提交结果用新连接核实，不直接重放。非空未受管库、校验和漂移、路由指纹变化需要人工处理；没有自动 DROP/repair/历史导入。

不要把 `EgonColaDdlTargetBO` 注册为默认 MP IDdl Bean，也不要混用 MP 默认 DdlApplicationRunner。本 Starter 负责物理池、拓扑校验、TableInfo 维护、脚本 DDL 与逻辑数据源；应用不得再复制 `ShardingDataSourceBootstrapper`。

可以显式配置启动前迁移。`auto-migrate` 默认关闭；仅设置旧的 `ddl.enabled` 不会自动运行脚本。每个分片组和 schema 必须提供独立目标，脚本需创建规则中实际使用的物理表名。

```yaml
egon:
  cola:
    component:
      mybatis-plus:
        ddl:
          enabled: true
          auto-migrate: true
          topology-ready-timeout: PT60S
          targets:
            - { logical-name: shard_0, schema: public, role: SHARD, manifest-resource: "classpath:db/egon-mp/repository-manifest.json" }
            - { logical-name: shard_1, schema: public, role: SHARD, manifest-resource: "classpath:db/egon-mp/repository-manifest.json" }
```

组件在逻辑数据源创建前对指定的物理 PRIMARY 执行已有的版本化 manifest；验证所有路由物理表存在后，在主从模式等待每个副本回放到主库迁移完成后的 WAL 位置，超时启动失败。回放查询受剩余等待时间限制，连接获取仍受连接池和 JDBC 的超时配置约束。各主库独立提交，失败后重试会继续校验已完成的版本；并非跨分片原子迁移。只支持显式脚本迁移，TableInfo 生成器仍是未执行的 SQL 建议，不能自动推断索引、约束和安全的列类型变更。已有 Flyway 版本不可修改；需要变更时追加新版本。

新增逻辑表会改变路由指纹。若只新增表、原有表的路由和主库组完全不变，可在 `ddl.previous-route-exclusions` 中逐项列出某个历史拓扑尚未包含的当前逻辑表，例如 `previous-route-exclusions: [[new_orders]]`。组件从当前拓扑排除这些表并重新计算历史指纹，只有与已安装历史完全匹配且 manifest 有新版本时才执行迁移；原有表改分片或更换主库组仍会失败。以后启动需要保留覆盖所有已安装历史指纹的声明，不会修改历史记录。

## 配置与验证

核心配置位于 `egon.cola.component.mybatis-plus`；源脚手架四个 profile 给出完整配置。Common 的 `ddl.enabled` 默认 false，六个非 Agent 脚手架显式启用，Agent 显式关闭。主键配置位于 `egon.cola.component.id`。

CPU/Mock/H2 用例参考官方 MyBatis-Plus 测试的真实 Mapper/插件调用方式。它们不证明 PG DDL、复制、分片实际落点或性能。真实 PG/SS 测试默认跳过，由环境变量开启：`EGON_MP_PG_MODEL_TEST=true` 启用逻辑删除与乐观锁用例，`EGON_MP_PG_DDL_TEST=true` 启用受管 DDL 用例，两者都从 `EGON_MP_PG_URL` / `EGON_MP_PG_USER` / `EGON_MP_PG_PASSWORD` 读取专用测试库连接。`EgonColaPostgreSqlReadwriteIT` 须显式指定 Maven 测试名并设置 `EGON_MP_PG_SHARDING_IT=true`、用户密码以及 `EGON_MP_PG_SHARD_0_PRIMARY_URL`、`EGON_MP_PG_SHARD_0_REPLICA_URL`、`EGON_MP_PG_SHARD_1_PRIMARY_URL`、`EGON_MP_PG_SHARD_1_REPLICA_URL`；两组都必须是专用物理复制测试集群。该测试创建并清理随机命名的测试表。这些用例不在 CI 中执行，必须手动运行；SQL 性能需在真实数据分布上以 EXPLAIN 验收。
