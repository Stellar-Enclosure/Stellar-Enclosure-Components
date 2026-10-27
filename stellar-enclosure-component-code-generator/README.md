# stellar-enclosure-component-code-generator

[English](README.md) | [中文](README.zh-CN.md)

An offline development tool. It reads PostgreSQL DDL, an MP-SDJ manifest or explicit standalone
event definitions and generates backend code for native Light, Web and Service. It does not start Spring, does not connect
to a database, and does not modify the target project's POM.

`org.freemarker:freemarker:2.3.35` is the only approved template engine, and it exists only inside
this tool. Generated business projects do not depend on FreeMarker.

## Build and classpath

Run from the repository root:

```bash
./mvnw -B -ntp -pl stellar-enclosure-component-code-generator -am install -DskipTests
./mvnw -B -ntp -pl stellar-enclosure-component-code-generator dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=/tmp/egon-codegen.cp
```

The first command uses `install` instead of `package` so the upstream modules land in the local
repository; the second one omits `-am`, otherwise every module in the reactor overwrites the same
`/tmp/egon-codegen.cp` and the result depends on build order.

Prepend this module's `target/classes` to that classpath, then export it:

```bash
export EGON_CODEGEN_CLASSPATH="stellar-enclosure-component-code-generator/target/classes:$(cat /tmp/egon-codegen.cp)"
```

`scripts/stellar-enclosure-codegen.sh` only consumes an `EGON_CODEGEN_CLASSPATH` that has already been provided:
it runs no Maven, no curl, and starts no application. The Java executable is selected in the order
`JAVA`, then `JAVA_HOME/bin/java`, then `java` on `PATH`. Missing Java, an empty
`EGON_CODEGEN_CLASSPATH`, or any entry of that classpath that does not exist on disk all print
`{"code":"BLOCKED_TOOLING",...}` to standard error and exit with code 7.

## Commands

```text
scripts/stellar-enclosure-codegen.sh templates
scripts/stellar-enclosure-codegen.sh plan --config codegen.json
scripts/stellar-enclosure-codegen.sh check --config codegen.json
scripts/stellar-enclosure-codegen.sh apply --plan <plan-id> --config codegen.json
scripts/stellar-enclosure-codegen.sh recover --config codegen.json
scripts/stellar-enclosure-codegen.sh retire-legacy --config codegen.json --paths-file reviewed-retirement.json
```

On success the JSON object is the last line of standard output. This module ships no logging
configuration, so a Logback default console appender on the classpath also writes log lines (for
example the Hibernate Validator version line) to standard output; discard everything before the JSON
when parsing. Errors and diagnostics go only to standard error, as `{"code":...,"message":...}`.
Exit codes: 0 success or no change, 2 configuration or syntax, 3 missing type or non-canonical
input, 4 manual conflict, 5 unconfirmed destructive change, 6 stale plan, 7 recovery or IO,
8 check found drift awaiting synchronization.

`check` never touches project files; it compares each rendered result with the content on disk.
`apply` is all-or-nothing: when the preflight finds a `CONFLICT` it stops before writing any file
and returns 4. Hand-edited files stay as they are, and only files whose disk content still equals
the previously generated content are replaced. Exit code 5, `DESTRUCTIVE_ACTION`, covers `DELETE`
and `RENAME` actions that need explicit acceptance, but `plan` only emits `CONFLICT`, `NO_CHANGE`,
`ADD` and `UPDATE`, and the command line passes no acceptance set, so that branch is unreachable in
the current CLI.

`backend-crud` generates PO, DAO, Mapper XML, Repository, domain model and business port, Command/Query/Result, matching CmdExe/QueryExe, converters and Manage. Native Light/Web also generate an authorized Controller with DTO/VO; Service has no HTTP entry point. `persistence-crud` selects only the first four. Selecting `command` or `query` also selects its matching executors, without silently expanding the rest of the scope.

Dependencies outside the selection must exist at their canonical source paths or be explicitly declared in `existingTypeMappings`; PO/DAO may use an existing mapped type, while other mappings must match the fully qualified type used by generated code. Table-qualified keys such as `orders.domain-service` are supported. Missing dependencies return `MISSING_TYPE`; an explicit external mapping is a caller assertion, so compile the target project to check its actual classpath and signatures.

`configVersion` is `1`; profiles are native `light`, `web`, and `service`. Unknown JSON keys are rejected. Input mode is `schema`, `manifest`, or `event` for standalone events only. DDL modes require explicit `logicalTables`. Global `fieldPolicies` may be overridden per table with `tableFieldPolicies`; invalid, duplicate or colliding fields are rejected before planning, including filter properties named `page`, `pageNo` or `pageSize`. `sort` defines fixed ascending columns; `id ASC` is appended for stable pagination. It is not a client-supplied sort parameter. HTTP paging accepts `pageNo` / `pageSize` or `page.pageNo` / `page.pageSize`; conflicting aliases are rejected.

## CQERS, business ports and HTTP authorization

Generated packages follow `domain/<domain>/{vos,service}`, `application/<domain>/{pojo,manage,executor}`, and `adapter/<domain>/{controller,pojo,event}`. Manage delegates to matching executors. For every table whose Domain Service is generated or referenced, `domainMethods` must explicitly name five distinct business methods; bare generic names such as `create`, `update`, `delete`, `detail`, and `query` are rejected. DDL cannot define domain invariants or prove the business meaning of a name; the generated persistence implementation remains a scaffold for business review.

Every `domain-impl`, including Service, requires the existing target `apiContract.existingErrorMapper`. A Light/Web Controller additionally requires a unique native API path, explicit OAuth scope, permission, API resource code and localization keys for each operation, and field-resource metadata for every returned or writable field. Supply the real target contracts; do not infer or fabricate permissions. This example shows the relevant fragment for Web (Light paths start with `/api/`):

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

Supply the OAuth scope without the `SCOPE_` prefix. Each operation declares `@PreAuthorize`, `@PermissionResource` and `@RequiresPermissions`; request DTO and response VO fields each declare distinct resource keys and `@FieldPermissions`. Configure read, create and update permissions explicitly, including update input resources for `id` and `expectedVersion`. `permissionReferences` lists codes owned by other producers; `localPermissions` lists codes already declared with `@PermissionDefinition` in this module. They must be disjoint and cover all used permissions. `labelPage` / `labelKey` refer to existing localization sources.

`transportMode: "generate"` emits one shared MVC configuration that applies the existing `PermissionJsonMapperFactory` to HTTP JSON converters; this affects all HTTP JSON converters in the target application and requires compatibility review. If an equivalent configuration already exists, choose `transportMode: "existing"` and provide its source type in `transportConfigType` and actual bean name in `transportBean`; include its module in `modulePaths` when necessary. Source inspection checks the declaration and wiring evidence, not whether the configuration is active in a deployment profile. A detected duplicate configuration blocks the plan. The target must configure its existing Permission-as-Code processor, complete manifest and runtime catalog, and establish trusted identity/tenant context. Generated Controllers depend on the permission guard, mapper factory and selected configuration bean, so missing beans fail startup. Registration, activation and role grants are separate; registration never grants roles. Page access conservatively requires read permissions for all configured filter and fixed-sort fields, including `id`, even when a filter is omitted. Read access to `version` is required for optimistic updates.

The legacy `contextSymbol` remains accepted for compatibility but does not establish a security context. Existing native HTTP filters must provide trusted identity/tenant context, and event consumers must establish their own trusted context. Generated permission resources allow USER principals by default; SERVICE/client-credentials cases require target-specific extension and verification. Generated repositories do not enable caching; add cache read/write and consistency policy through existing components when the business requires it.

Result/VO always include a string `id` and `version`. A request-body update ID is optional but must match the path when present. A null update field means omitted, not an explicit SQL NULL. Create and update reread the persisted record to return database defaults, unchanged fields and the actual version; the existing transaction and error contracts still apply.

## Inbound events

Events are generated only from explicit definitions. The target must provide an existing synchronous transaction-compatible idempotency bean with a method compatible with `void executeOnce(String eventType, String eventId, Object event, Runnable action)`. Its scope must include trusted tenant, consumer, type, ID and a payload fingerprint: identical duplicates are skipped, conflicting reuse is rejected, and failures propagate for retry. The caller owns source authentication, trusted tenant context, transport, retry/dead-letter and acknowledgement after commit; generation does not create a store, subscribe or publish.

CRUD actions `create`, `update`, and `delete` require a selected `table` and `fieldMappings` covering the generated Command fields. Optional `fields` add event-specific payload that is not mapped to the Command. For an event with independent business handling, use `action: "delegate"` and specify an existing Application-layer handler type, bean and `void` method accepting the generated event. EventExe invokes that port under the idempotency guard; no empty implementation is generated. An event-only run can use `artifacts: ["event"]`, `input: {"mode":"event"}` and empty `logicalTables`, with no DDL:

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

Fields support `String`, `Long`, `Integer`, `Boolean`, `java.math.BigDecimal`, `java.util.UUID`, fully qualified `java.time` Instant/LocalDate/LocalDateTime/LocalTime, and existing Application, Domain or shared contract types. `java.util.List<T>`, `java.util.Set<T>`, `java.util.Map<String,T>` and arrays may nest up to three levels. Complex fields cascade `@Valid`; the target must provide those types and may not reference its Infrastructure, Adapter or Starter implementation. `eventId` and `schemaVersion` are always present and cannot be redefined. Version changes require producer and in-flight-message migration planning. Event-only generation preserves existing DDL state and checksum history.

An old flat-layout file, retained ownership record, previously owned artifact left behind by a
module-path change, an existing Domain Service with different business method names, or switching away from a generated MVC transport triggers `LEGACY_LAYOUT_CONFLICT` (exit 4).
Removing or renaming a previously generated event triggers `EVENT_SCOPE_CONFLICT` (exit 4); `check`
returns `DRIFT` and the affected paths. The tool never deletes, moves or adopts those files
automatically. Review callers and ownership and migrate existing projects separately; do not delete
the state file or reset hashes to bypass protection. For obsolete owned paths:

1. Run `check` with the intended new configuration. Review `legacyPaths` / `omittedEventPaths` and
   save the complete nested `retirementReview` JSON object to `reviewed-retirement.json`. It contains
   only conflicting paths with generator ownership, together with the reviewed fingerprints.
2. Preserve or migrate the affected source files and update their callers yourself. Each reviewed path
   must be absent from its original location before retirement; unowned conflicts also need separate review.
3. Run `retire-legacy --config codegen.json --paths-file reviewed-retirement.json`. Under the output
   lock it revalidates the current inputs, ownership and absent paths, backs up the original state to
   `.egon/codegen/state-history/<sha256>.json`, and removes only the reviewed ownership records.
   It does not move or delete source files. Re-run `plan` and review the new files; existing unmanaged
   target files still conflict and are never adopted. Changed inputs/state require a new review; previous
   plans are stale after state changes. Resolve an interrupted generation with `recover` first.

Existing backend configurations must add `domainMethods`; Controller generation also needs explicit
permissions, fields, localization and MVC transport configuration. Old configurations and plans do
not infer the new contracts. Review handwritten port consumers before changing business methods; a
generated API is not ready for exposure until its Permission-as-Code processor and catalog are active.

DDL generation reads only `CREATE TABLE` definitions, including those inside the existing role-scoped
`DO` branches. Standalone `CREATE INDEX` and seed `INSERT`/`UPDATE`/`DELETE` statements are ignored
for schema modeling. Indexes and soft-delete uniqueness must be reviewed separately by the agent;
the generator cannot certify them. `ALTER`, `DROP`, `COMMENT` and other structural changes return `UNSUPPORTED_DDL`;
an agent must inspect the changed DDL and update affected code explicitly. Do not use the generator
to refresh previously generated files after an `ALTER`.

The DDL must define the id, tenant, audit, `deleted_at` and version columns that `EgonModel`
requires, and the generator validates their types and null semantics. A PO extends `EgonModel` and
declares only business fields; the Mapper XML still maps the inherited fields. The generator never
re-declares those common fields on the PO.
For business columns, the PO template emits Jakarta constraints for DDL nullability (`NOT NULL`
becomes `@NotNull`, without inventing a nonblank rule), string length
and simple string `CHECK ... IN (...)` values, using the existing Insert/Update/Delete/Query/Persisted
groups as applicable. Insert means CRUD Create. Update permits omitted fields for partial MP writes;
Persisted checks loaded rows. Complex CHECK expressions need agent review and cannot be inferred as
business rules by this generator.
Generated DAO methods use `@Validated`/`@Valid` where applicable; inherited MP writes are checked
after fill by the Starter's mapper interceptor using Jakarta `Validator` directly.

The plan format is currently v2. A same-named file with no generation record, a hand-edited file,
and files added or changed after the plan was cut all return conflicts; nothing is adopted or
overwritten automatically. `apply` re-checks the fingerprints of the configuration, DDL, templates,
tool code and generation state, and preflights every target file under a lock on the same output
directory. A v1 plan needs a fresh `plan` run.

`plan`, `apply`, `recover` and `retire-legacy` maintain only `.egon/codegen/` below the output root:
`plans/<plan-id>.json` is written by `plan`, `state.json` is updated by `apply`, the journal inside
it records the interrupted phase so `recover` can roll back, and `apply.lock` is the write lock.
Deleting that directory strips the generator of every file-ownership record, after which all
same-named files are judged as conflicts.

Current acceptance covers compiling from source and isolated tests:
`./mvnw -B -ntp -pl stellar-enclosure-component-code-generator -am test`. The real permission-processor
integration test is skipped unless the existing processor and target runtime classpath are supplied:

```bash
export EGON_PAC_TEST_CLASSPATH="/path/to/stellar-enclosure-tianquan-jianshen-processor.jar:$(cat /path/to/target-runtime.cp)"
./mvnw -B -ntp -f stellar-enclosure-component-code-generator/pom.xml -Dtest=PermissionProcessorIntegrationTest "-Dcodegen.permissionProcessorClasspath=$EGON_PAC_TEST_CLASSPATH" test
```

It compiles in a temporary directory and inspects the permission fragment; it does not register or
activate permissions or grant roles. These tests do not establish real PostgreSQL, ShardingSphere,
permission-service or MQ readiness.


## UTC temporal defaults

Temporal DEFAULT expressions must retain UTC semantics independently of the database session zone.
Use `CURRENT_TIMESTAMP(6)` / `now()` for `TIMESTAMPTZ(6)`; use
`(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')` for a legacy UTC timestamp,
`((CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date)` for UTC today, and
`((CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::time(6))` for UTC wall-clock time.
Fixed date/time literals retain their declared semantics; instant literals require `Z` or a zero offset.
Unsupported or session-dependent temporal defaults fail with `TIME_DEFAULT_UNSUPPORTED` in both render paths.
`TIMESTAMP 'now'` freezes the creation-time value; `timezone('UTC', now())` returns a naive value and is
not an instant-column default. Scheduling endpoints remain required inputs; `deleted_at` stays nullable.
The MP schema maintainer proposes types without inventing defaults. Historical migrations are immutable.

## Generated Java contract and template corrections

Before staging a plan, all selected Java outputs must match their source path, package and public
type; a mismatch returns `JAVA_SOURCE_MISMATCH` with exit code 2. Persistence converters belong to
`infrastructure.<domain>.converter`, and Domain Service implementations import them explicitly.
Archetype protocol-only input carriers use `DTO`; `Command`/`Query` inputs and `Result`/`PageResult`
outputs keep their semantic names. Framework and generated protocol types are unchanged.

`CLEAN` means the selected files match the current templates; it is not an architecture verdict.
After upgrading the tool/templates, create a fresh plan. A previously generated, unedited converter
whose path is unchanged can be updated in place, with its baseline hash maintained by `apply`.
The older flat Application converter path requires the legacy-layout review described above. If a Domain Service contains
business edits, a combined apply stops before any writes. Select only the unedited generated files,
and review the import change in the edited service separately. Preserve its recorded baseline and
expected drift; do not reset hashes or delete state to force `CLEAN`. A clean compile regenerates
MapStruct implementations in the corrected package and removes stale classes from the old package.
