# stellar-enclosure-component-code-generator

[English](README.md) | [中文](README.zh-CN.md)

离线开发工具。它读取 PostgreSQL DDL 或 MP-SDJ manifest，为 native Light、Web、Service 生成后端 CRUD。它不启动 Spring，不连接数据库，也不修改目标项目的 POM。

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

`backend-crud` 会生成完整基础链路：PO、DAO、Mapper XML、Repository、领域模型与
Domain Service/实现、Command/Query/Result、对应的 CmdExe/QueryExe、转换器和 Manage/实现；
Light 和 Web 还会生成 Controller、DTO、VO 和 Adapter 转换器，Service 不生成 HTTP 入口；
`persistence-crud` 只展开为前四项。Command、Query 和 Controller 是各自成组的产物：单选
`command` 同时生成三组 Command/CmdExe，单选 `query` 同时生成两组 Query/QueryExe，
单选 `controller` 包含其 DTO、VO 与转换器；计划会逐项列出这些文件。
`artifacts` 可以逐个选择，别名 `pojo`→`po`、`mapper`→`mapper-xml` 会先归一化。
省略下层产物时必须用 `existingTypeMappings` 声明已有类型：缺 `po` 或缺 `dao` 返回
`MISSING_TYPE`（退出码 3）。生成器只强制这两项，catalog 里其余条目的 `requiredTypes`
当前不参与校验；除上述成组文件及显式配置的事件外，不会自动增加其他产物。查询和写入均由 Repository 持有 Mapper/XML 访问，
Domain Service 实现不能直接调用 DAO。`logicalTables` 必须明确指定，且逐个匹配 DDL 中的逻辑表，
不存在的表名直接报错。

配置 JSON 的 `configVersion` 必须是 `1`，`projectType` 取 `light`/`web`/`service`，
未知键一律拒绝（反序列化开启 `FAIL_ON_UNKNOWN_PROPERTIES`）。`input.mode` 只有两种：
`schema` 按顺序重放 `schemaFiles`，`manifest` 读取 `resourceRoot` 下的 `manifest` 路径。
manifest 形如 `{"family":...,"scripts":[{"version":...,"path":...,"sha256":...}]}`；
脚本字节与记录的 `sha256` 不符，或已记录的版本前缀发生变化，都返回 `CHECKSUM_DRIFT`。

## CQERS 与入站事件

新生成的业务代码按 `domain/<业务域>/{vos,service}`、
`application/<业务域>/{pojo,manage,executor}` 和 `adapter/<业务域>/{controller,pojo,event}`
组织。Manage 委托对应 Exe；Command 保留事务，Query 保持只读。HTTP 的 JSON 字段与原
Command/Result 语义一致，但生成的 Controller 不提供目标项目的资源、scope 或字段权限配置；
接入前须按目标服务的身份和授权机制完成这些配置，资源登记本身不授予角色权限。

不配置事件时不会生成事件类或空处理器。要生成入站事件，显式选择 `command` 或
`backend-crud`，并提供完整定义；多模块项目还须提供 `modulePaths.adapter`。以下是完整配置中的事件部分：

```json
{
  "events": {
    "enabled": true,
    "definitions": [{
      "table": "orders",
      "name": "OrderImportedEvent",
      "action": "create",
      "schemaVersion": "v1",
      "idempotencyType": "com.example.order.support.EventDedupService",
      "idempotencyBean": "eventDedupService",
      "idempotencyMethod": "executeOnce",
      "fieldMappings": {"code": "externalCode"}
    }]
  }
}
```

`table` 必须在 `logicalTables` 中；`name` 必须以 `Event` 结尾且不重复，`action` 为
`create`、`update` 或 `delete`。`fieldMappings` 的键是目标 Command 的 Java 字段，值是
事件里的 lowerCamel 字段：create 覆盖全部 create 字段；update 覆盖 `id`、
`expectedVersion` 和全部 update 字段；delete 覆盖 `id` 与 `expectedVersion`。
事件模型固定包含 `eventId`、`schemaVersion`，这两个名称不能作为映射来源。生成物包含
Event、MapStruct EventConverter、EventExe 和 Adapter EventHandler；Handler 是可供现有
消费者调用的入口，不会自行订阅队列、主题或发布事件。
修改 `schemaVersion` 会改变接收端接受的版本；部署前须处理在途消息与生产者兼容性，生成器不会迁移或重放旧消息。

配置必须指向目标项目已有的幂等 Bean，其方法须兼容
`void executeOnce(String eventType, String eventId, Object event, Runnable action)`；方法名可由
`idempotencyMethod` 指定。类型应是 Application/Domain 端口或共享契约，不能直接引用目标项目
的 Infrastructure、Adapter 或 Starter 实现；实现由 Spring 注入。该方法必须同步执行 action，并在与业务写入兼容的同一事务中，
按可信租户、消费方、事件类型和 ID 原子记录处理结果；重复且内容一致时跳过，ID 相同但
内容不同时拒绝；处理失败时传播运行时异常，使事务回滚并允许重试。事件对象用于计算稳定的内容指纹，不应记录敏感载荷。
调用方负责验证消息来源、建立可信租户上下文、配置重试／死信及提交后确认；生成器不会创建
幂等存储，也不声称跨数据库或跨服务 exactly-once。

旧版平铺目录中的同名文件、仍存在的生成记录，或变更模块路径后被遗留的同一产物记录会触发 `LEGACY_LAYOUT_CONFLICT`（退出码 4）；
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

生成器只读取 `CREATE TABLE` 建表定义（包括现有按角色分支的 `DO` 块）。独立的
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

当前验收覆盖源码编译和隔离测试：`./mvnw -B -ntp -pl stellar-enclosure-component-code-generator -am test`。它不代表 PostgreSQL、ShardingSphere 或 MQ 已经在真实环境运行。


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
