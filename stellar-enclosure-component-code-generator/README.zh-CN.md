# stellar-enclosure-component-code-generator

[English](README.md) | [中文](README.zh-CN.md)

离线开发工具。它读取 PostgreSQL DDL、MP-SDJ manifest 或显式的独立事件定义，为 native Light、Web、Service 生成后端代码。它不启动 Spring，不连接数据库，也不修改目标项目的 POM。

已批准的模板引擎只有 `org.freemarker:freemarker:2.3.35`，并且只存在于本工具。生成出的业务工程不依赖 FreeMarker。

## 构建与 classpath

在仓库根目录执行：

```bash
./mvnw -B -ntp -pl stellar-enclosure-component-code-generator -am install -DskipTests
./mvnw -B -ntp -pl stellar-enclosure-component-code-generator dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=/tmp/egon-codegen.cp
```

第一条命令用 `install` 而不是 `package`，把上游模块写入本地仓库；第二条命令不带 `-am`，
否则 reactor 中每个模块都会覆写同一个 `/tmp/egon-codegen.cp`，结果只取决于构建顺序。

把本模块 `target/classes` 加到该 classpath 前面，再导出：

```bash
export EGON_CODEGEN_CLASSPATH="stellar-enclosure-component-code-generator/target/classes:$(cat /tmp/egon-codegen.cp)"
```

`scripts/stellar-enclosure-codegen.sh` 只使用已经给出的 `EGON_CODEGEN_CLASSPATH`，不执行 Maven、curl，也不启动应用。Java 可执行文件按 `JAVA`、`JAVA_HOME/bin/java`、`PATH` 中的 `java` 顺序选取。缺少 Java、`EGON_CODEGEN_CLASSPATH` 为空、或该 classpath 中任一条目在磁盘上不存在，都会向标准错误打印 `{"code":"BLOCKED_TOOLING",...}` 并以退出码 7 结束。

## 命令

```text
scripts/stellar-enclosure-codegen.sh templates
scripts/stellar-enclosure-codegen.sh plan --config codegen.json
scripts/stellar-enclosure-codegen.sh check --config codegen.json
scripts/stellar-enclosure-codegen.sh apply --plan <plan-id> --config codegen.json
scripts/stellar-enclosure-codegen.sh recover --config codegen.json
scripts/stellar-enclosure-codegen.sh retire-legacy --config codegen.json --paths-file reviewed-retirement.json
```

命令成功时，JSON 对象是标准输出的最后一行。本模块不内置日志配置，classpath 上的 Logback 默认控制台 appender 会把日志行（例如 Hibernate Validator 版本行）也写到标准输出，解析前需要丢弃 JSON 之前的内容。错误与诊断以 `{"code":...,"message":...}` 形式只写到标准错误。退出码：0 成功或无变化，2 配置或语法，3 缺类型或不规范，4 人工冲突，5 破坏性变更未确认，6 过期计划，7 恢复或 IO，8 检查发现待同步差异。

`check` 不改项目文件，只逐项比较渲染结果与磁盘内容。`apply` 是全有或全无：预检一旦发现 `CONFLICT`，就在写入任何文件之前中止并返回 4；人工改过的文件保持原样，只有磁盘内容仍等于上次生成内容的文件会被替换。退出码 5 `DESTRUCTIVE_ACTION` 对应需要显式受理的 `DELETE`/`RENAME` 动作，但 `plan` 只产出 `CONFLICT`、`NO_CHANGE`、`ADD`、`UPDATE`，命令行也不传受理集合，因此该分支在当前 CLI 下不可达。

`backend-crud` 生成 PO、DAO、Mapper XML、Repository、领域模型与业务端口、Command/Query/Result、对应 CmdExe/QueryExe、转换器和 Manage/实现；Light、Web 还生成带授权边界的 Controller、DTO、VO 和 Adapter 转换器，Service 不生成 HTTP 入口。`persistence-crud` 只生成前四项。单选 `command` / `query` 时仍分别生成全部配对执行器；生成器不会因缺依赖而扩大产物范围。

`artifacts` 可以逐项选择，`pojo` 和 `mapper` 分别是 `po` 和 `mapper-xml` 的别名。未选中的依赖必须已存在于规范源码路径，或通过 `existingTypeMappings` 显式声明；PO/DAO 可映射到已有类型，其余类型映射须与生成器引用的完整类名一致，可使用 `orders.domain-service` 等按表声明的键。未声明且找不到源码时返回 `MISSING_TYPE`。映射是调用方对外部类型的声明，生成器不能证明它已在目标工程 classpath 上或具有兼容方法，仍须编译目标工程。

`configVersion` 为 `1`，`projectType` 为 `light` / `web` / `service`；未知 JSON 属性会被拒绝。`input.mode` 为 `schema`（按顺序读取 `schemaFiles`）、`manifest`（按资源根目录读取带 SHA-256 的清单），或仅用于独立事件的 `event`。DDL 模式必须显式选择 `logicalTables`；每张表使用全局 `fieldPolicies`，也可通过 `tableFieldPolicies.<表名>` 覆盖。无效、重复或冲突的字段在计划前拒绝；筛选字段不能与分页属性 `page`、`pageNo`、`pageSize` 冲突。`sort` 是固定升序列清单，生成 SQL 会追加 `id ASC` 作为稳定的分页顺序；它不是客户端可自由指定的排序参数。HTTP 分页支持 `pageNo` / `pageSize` 和 `page.pageNo` / `page.pageSize`，同一参数的两种写法同时出现且值不一致时拒绝。

## CQERS、领域端口与 HTTP 授权

新代码按 `domain/<业务域>/{vos,service}`、`application/<业务域>/{pojo,manage,executor}` 和 `adapter/<业务域>/{controller,pojo,event}` 组织。Manage 委托匹配的 Exe；Command 执行器负责写用例，Query 执行器只读。需要生成或引用 Domain Service 的范围必须为每个表显式提供五个互不相同的业务方法名，不能使用裸 `create`、`update`、`delete`、`detail`、`query` 等通用技术方法名。生成器只提供持久化骨架；领域不变量、权限策略和方法名称是否准确表达业务仍须由业务开发者审阅。

生成 `domain-impl` 时，所有 profile 都必须配置目标工程已有的 `apiContract.existingErrorMapper`。Light / Web 生成 Controller 时，还须为每个表配置唯一的原型允许路径、五个操作的 OAuth scope、权限码、API 资源码和本地化键，以及所有返回或可写字段的字段资源。以下是配置中的相关片段（值必须替换为目标工程的真实契约；Web 路径须在 `/api/v1/` 下，Light 在 `/api/` 下）：

```json
{
  "domainMethods": {
    "orders": {
      "create": "createOrders",
      "update": "changeOrders",
      "delete": "removeOrders",
      "detail": "findOrders",
      "query": "pageOrders"
    }
  },
  "apiContract": {
    "existingErrorMapper": "com.example.order.support.OrdersErrorMapper",
    "transportMode": "generate",
    "permissionReferences": [
      "fixture:orders:create",
      "fixture:orders:update",
      "fixture:orders:delete",
      "fixture:orders:detail",
      "fixture:orders:page",
      "fixture:orders:read",
      "fixture:orders:field:write"
    ],
    "tables": {
      "orders": {
        "basePath": "/api/v1/orders",
        "operations": {
          "create": {
            "scope": "fixture:orders:create",
            "permission": "fixture:orders:create",
            "resourceCode": "fixture.api.orders.create",
            "labelPage": "fixture.orders",
            "labelKey": "api.create"
          },
          "update": {
            "scope": "fixture:orders:update",
            "permission": "fixture:orders:update",
            "resourceCode": "fixture.api.orders.update",
            "labelPage": "fixture.orders",
            "labelKey": "api.update"
          },
          "delete": {
            "scope": "fixture:orders:delete",
            "permission": "fixture:orders:delete",
            "resourceCode": "fixture.api.orders.delete",
            "labelPage": "fixture.orders",
            "labelKey": "api.delete"
          },
          "detail": {
            "scope": "fixture:orders:detail",
            "permission": "fixture:orders:detail",
            "resourceCode": "fixture.api.orders.detail",
            "labelPage": "fixture.orders",
            "labelKey": "api.detail"
          },
          "page": {
            "scope": "fixture:orders:page",
            "permission": "fixture:orders:page",
            "resourceCode": "fixture.api.orders.page",
            "labelPage": "fixture.orders",
            "labelKey": "api.page"
          }
        },
        "fields": {
          "id": {
            "permission": "fixture:orders:read",
            "labelPage": "fixture.orders",
            "labelKey": "field.id",
            "resultKey": "fixture.field.orders.result.id",
            "createKey": "fixture.field.orders.create.id",
            "updateKey": "fixture.field.orders.update.id",
            "createPermission": "fixture:orders:field:write",
            "updatePermission": "fixture:orders:field:write"
          },
          "version": {
            "permission": "fixture:orders:read",
            "labelPage": "fixture.orders",
            "labelKey": "field.version",
            "resultKey": "fixture.field.orders.result.version",
            "createKey": "fixture.field.orders.create.version",
            "updateKey": "fixture.field.orders.update.version",
            "createPermission": "fixture:orders:field:write",
            "updatePermission": "fixture:orders:field:write"
          },
          "code": {
            "permission": "fixture:orders:read",
            "labelPage": "fixture.orders",
            "labelKey": "field.code",
            "resultKey": "fixture.field.orders.result.code",
            "createKey": "fixture.field.orders.create.code",
            "updateKey": "fixture.field.orders.update.code",
            "createPermission": "fixture:orders:field:write",
            "updatePermission": "fixture:orders:field:write"
          },
          "expectedVersion": {
            "permission": "fixture:orders:read",
            "labelPage": "fixture.orders",
            "labelKey": "field.expectedVersion",
            "resultKey": "fixture.field.orders.result.expectedVersion",
            "createKey": "fixture.field.orders.create.expectedVersion",
            "updateKey": "fixture.field.orders.update.expectedVersion",
            "createPermission": "fixture:orders:field:write",
            "updatePermission": "fixture:orders:field:write"
          }
        }
      }
    }
  }
}
```

`scope` 填 OAuth scope 本身，不加 `SCOPE_` 前缀。每个操作生成 `@PreAuthorize`、`@PermissionResource` 和 `@RequiresPermissions`；请求 DTO 与结果 VO 的字段分别声明独立资源键和 `@FieldPermissions`。必须明确每个字段的读取、创建或更新权限，`id` 和 `expectedVersion` 也需要更新请求的字段契约。`permissionReferences` 是其他生产者拥有的权限码；`localPermissions` 是目标模块已有 `@PermissionDefinition` 定义的权限码，二者不能重复，全部用到的权限码均须归入其中。`labelPage` / `labelKey` 引用已有本地化资源，不由生成器猜测。

`transportMode: "generate"` 生成一个共享 MVC 配置，将目标工程的 HTTP JSON 转换器接入既有 `PermissionJsonMapperFactory`；它影响该应用所有 HTTP JSON 转换器，须审阅与现有配置的兼容性。如果项目已有同等配置，使用 `transportMode: "existing"` 通过 `transportConfigType` 指向其源码并以 `transportBean` 指定实际配置 Bean 名；若配置在 Starter 等模块，还须在 `modulePaths` 中列出该模块。源码检查只能核对声明与接线线索，仍须在目标部署配置中验证其确实生效；检测到重复配置时计划会停止。目标工程必须配置现有 Permission-as-Code 编译处理器、完整清单及运行时权限目录，并接入可信身份与租户上下文；生成的 Controller 依赖权限守卫、转换工厂和所选配置 Bean；缺失时启动失败，不能声称权限已经生效。清单登记、激活和角色授权是独立步骤，**登记不授予角色**。分页接口保守地同时要求所有允许筛选和固定排序字段（包括 `id`）的读取权限，即使本次未使用该筛选字段。读取 `version` 的权限是调用方进行乐观锁更新的前提。

旧配置中的 `contextSymbol` 为兼容保留，但不再作为生成的安全保证；HTTP 身份与租户上下文必须由所选原型的现有过滤器建立，事件消费者必须建立可信上下文。 生成的权限资源默认只允许 USER 主体；SERVICE／客户端凭证场景须由目标项目按自身契约扩展并验证。生成的 Repository 默认不启用缓存；需要缓存时应由业务按现有组件配置读写入口、失效和一致性。

Result/VO 总是包含字符串 `id` 与 `version`。更新请求的 `id` 以路径为准，请求体可以省略；若同时提供则必须一致。更新字段为 `null` 时表示不修改，不能用此脚手架将列显式清空为 SQL NULL。更新与创建后重新读取持久化结果，以返回数据库默认值、未修改字段和实际版本；事务及异常映射仍由现有端口实现负责。

## 入站业务事件

业务事件处理只在显式配置时生成；目标工程须提供已有的幂等 Bean，其方法兼容 `void executeOnce(String eventType, String eventId, Object event, Runnable action)`，实现必须同步执行 action，并与业务写入使用兼容的事务。幂等键须覆盖可信租户、消费方、事件类型与 ID；重复载荷跳过、同 ID 不同载荷拒绝，失败传播并允许重试。调用方负责来源校验、可信租户上下文、订阅、重试／死信及提交后确认；生成器不建立幂等存储或自动发布消息。

CRUD 事件可选择 `create`、`update`、`delete`，`table` 指向已选择的逻辑表，`fieldMappings` 把目标 Command 字段映射到事件字段；它必须覆盖该 Command 的全部字段。`fields` 可补充不参与 Command 映射的事件字段，包含 `name`、`javaType`、`required` 和可选字符串 `maxLength`。生成 Event、EventConverter、EventExe 和传输无关的 Adapter EventHandler。

不对应任何 CRUD 的事件使用 `action: "delegate"`，显式指定已有的 Application 业务处理类型、Bean 和 `void` 方法，该方法接收生成的事件对象；Domain 不依赖 Application 事件类型，不会生成空实现，也不会虚构业务逻辑。可单独使用 `artifacts: ["event"]`、`input: {"mode":"event"}` 和空 `logicalTables`，无需 DDL。以下是独立事件的配置片段：

```json
{
  "artifacts": ["event"],
  "input": {"mode": "event"},
  "logicalTables": [],
  "events": {"enabled": true, "definitions": [{
    "name": "PaymentSettledEvent", "action": "delegate", "schemaVersion": "v1",
    "idempotencyType": "com.example.order.application.order.service.EventGuard",
    "idempotencyBean": "eventGuard", "idempotencyMethod": "executeOnce",
    "handlerType": "com.example.order.application.order.service.PaymentEventPort",
    "handlerBean": "paymentEventPort", "handlerMethod": "acceptPayment",
    "fields": [
      {"name": "paymentId", "javaType": "String", "required": true, "maxLength": 64},
      {"name": "occurredAt", "javaType": "java.time.Instant", "required": true}
    ]
  }]}
}
```

事件字段支持 `String`、`Long`、`Integer`、`Boolean`、`java.math.BigDecimal`、`java.util.UUID`、`java.time` 的 Instant／LocalDate／LocalDateTime／LocalTime，以及目标工程已有的 Application、Domain 或共享契约完整类名；也可使用 `java.util.List<T>`、`java.util.Set<T>`、`java.util.Map<String,T>` 或数组，容器最多嵌套三层。复杂字段会级联 `@Valid`；类型必须由目标工程提供，不能引用本工程的 Infrastructure、Adapter 或 Starter 实现。`eventId` 和 `schemaVersion` 由生成器提供，不能重复声明。升级版本需要自行处理生产者兼容与在途消息。独立事件生成保留已有 DDL 状态与校验和历史。

旧版平铺目录中的同名文件、仍存在的生成记录，变更模块路径后被遗留的同一产物记录，业务端口方法变更，或切换已生成的 MVC 传输配置时会触发 `LEGACY_LAYOUT_CONFLICT`（退出码 4）；
已生成事件被移除或改名时会触发 `EVENT_SCOPE_CONFLICT`（退出码 4），`check` 则返回
`DRIFT` 及相关路径。工具不会自动删除、搬迁或接管这些文件。已有工程须先审阅调用方和
文件归属，单独完成迁移；不要通过删除状态文件或重置哈希绕过保护。针对不再使用、且已有生成归属记录的路径：

1. 使用准备采用的新配置运行 `check`，审阅 `legacyPaths` / `omittedEventPaths`，将输出中完整的
   `retirementReview` 内层 JSON 对象保存为 `reviewed-retirement.json`。其中只包含有生成归属的冲突路径及本次审阅的指纹。
2. 自行备份或迁移相关源码并调整调用方；解除记录前，所审阅路径的原位置必须不存在。没有生成归属的冲突也需要单独处理。
3. 执行 `retire-legacy --config codegen.json --paths-file reviewed-retirement.json`。该命令在输出锁内重新校验
   输入、归属及路径状态，把原状态备份到 `.egon/codegen/state-history/<sha256>.json`，只解除审阅的归属记录，
   不移动或删除源码。之后重新 `plan` 并审阅新文件；目标位置已有但未托管的文件仍会冲突，不会被自动接管。
   输入或状态变化后须重新审阅，状态变化也会使旧计划过期；存在未完成的生成时须先 `recover`。

升级现有工程时，旧的后端生成配置需要补齐 `domainMethods`；生成 Controller 还需要补齐权限、字段、本地化和 MVC 传输配置。旧配置或旧计划不会自动推断新契约。变更业务端口前须检查所有手写调用方；未启用 Permission-as-Code 清单处理器或未完成目录激活时，不能把生成接口视为已可上线。

DDL 模式的生成器只读取 `CREATE TABLE` 建表定义（包括现有按角色分支的 `DO` 块）。独立的
`CREATE INDEX` 和种子 `INSERT`/`UPDATE`/`DELETE` 不参与 Schema 建模；索引及软删除
唯一性由 agent 另行检查，不能从生成计划推断已校验。`ALTER`、`DROP`、`COMMENT` 等结构变更
返回 `UNSUPPORTED_DDL`；由 agent 检查 DDL 并显式修改受影响代码，不能用生成器刷新旧产物。

DDL 必须定义 `EgonModel` 所需的 id、tenant、审计、deleted_at 和 version 列，
生成器校验其类型和空值语义。PO 继承 `EgonModel`，只声明业务字段；Mapper XML 仍映射
继承字段。生成器不会把这些通用字段再声明到 PO 中。
业务列的 PO 模板按 DDL 空值（`NOT NULL` 对应 `@NotNull`，不擅自增加非空白规则）、
字符串长度和简单字符串 `CHECK ... IN (...)` 生成 Jakarta
约束，使用已有 Insert/Update/Delete/Query/Persisted 分组；Insert 对应 CRUD Create。
Update 允许 MP 部分更新省略字段，Persisted 校验已加载行。复杂 CHECK 表达式由 agent
核对并实现，生成器不猜测业务规则。
生成的 DAO 在适用方法上使用 `@Validated`/`@Valid`；继承的 MP 写入由 Starter 的 Mapper
拦截器在填充后直接调用 Jakarta `Validator` 校验。

计划格式当前为 v2。无生成记录的同名文件、人工编辑的文件，以及计划后新增或改变的文件
均返回冲突，不自动接管或覆盖。`apply` 重新核对配置、DDL、模板、工具代码和生成状态的
指纹，并在同一输出目录锁下预检所有目标文件；旧 v1 计划需要重新运行 `plan`。

`plan`、`apply`、`recover` 与 `retire-legacy` 只在输出根目录下维护 `.egon/codegen/`：
`plans/<plan-id>.json` 由 `plan` 写出，`state.json` 由 `apply` 更新，其中的 journal 记录
中断阶段并供 `recover` 回滚，`apply.lock` 是写入锁。删除该目录等于让生成器失去所有文件的
归属记录，此后同名文件都会判为冲突。

当前验收覆盖源码编译和隔离测试：`./mvnw -B -ntp -pl stellar-enclosure-component-code-generator -am test`。真实权限处理器集成测试需要目标工程已提供的处理器及运行时 classpath；未提供时该项会跳过，可显式执行：

```bash
export EGON_PAC_TEST_CLASSPATH="/path/to/stellar-enclosure-tianquan-jianshen-processor.jar:$(cat /path/to/target-runtime.cp)"
./mvnw -B -ntp -f stellar-enclosure-component-code-generator/pom.xml -Dtest=PermissionProcessorIntegrationTest "-Dcodegen.permissionProcessorClasspath=$EGON_PAC_TEST_CLASSPATH" test
```

该测试在临时目录编译生成物并检查权限片段；不会登记、激活权限或授予角色。上述测试不代表 PostgreSQL、ShardingSphere、权限服务或 MQ 已在真实环境运行。


## UTC 时间默认值

时间 DEFAULT 必须独立于数据库会话时区保持 UTC 语义。`TIMESTAMPTZ(6)` 使用
`CURRENT_TIMESTAMP(6)` / `now()`；Legacy UTC timestamp 使用
`(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')`；UTC 今天使用
`((CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date)`；UTC 钟表时间使用
`((CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::time(6))`。
固定日期/本地时间字面值保留其声明语义；发生时刻字面值要求 Z 或零偏移。
两个渲染入口对依赖会话时区或不支持的时间默认表达式返回 `TIME_DEFAULT_UNSUPPORTED`。
`TIMESTAMP 'now'` 会固定为建表时刻；`timezone('UTC', now())` 返回无时区值，不能作为发生时刻列默认值。
排课起止仍要求调用者显式提供，`deleted_at` 保持 nullable。MP 提案只推导类型，不自动补默认值。
历史迁移保持不可变；本规则不执行或重写 SQL。

## 生成 Java 契约与模板纠正

计划写出候选文件之前，全部选中 Java 产物必须满足源码路径、声明包和公开类型名一致；
不一致返回 `JAVA_SOURCE_MISMATCH`，退出码 2。持久化转换器属于
`infrastructure.<domain>.converter`，Domain Service 实现显式导入它。Archetype 的纯协议
入参载体使用 `DTO`；`Command`/`Query` 入参与 `Result`/`PageResult` 输出保留语义名称。
框架类型和生成的协议类型保持不变。

`CLEAN` 只表示选中文件匹配当前模板，不代表架构验收。升级工具/模板后重新生成计划。
路径未变且未被人工修改的生成 converter 可以原位更新，基线哈希由 `apply` 维护；旧版平铺
Application 转换器需要按上文进行目录迁移审阅。Domain Service
已有业务修改时，整组 apply 必须在写入前停止；只选择未改动的生成文件，将 Service 的
import 调整单独审阅合并，保留其原始基线和预期差异。不得重置哈希或删除状态来强行
得到 `CLEAN`。使用干净编译重新生成 MapStruct 实现，确认新包落点并清除旧包残留。
