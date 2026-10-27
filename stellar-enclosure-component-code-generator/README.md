# stellar-enclosure-component-code-generator

[English](README.md) | [中文](README.zh-CN.md)

An offline development tool. It reads PostgreSQL DDL or an MP-SDJ manifest and generates the backend
CRUD for the native Light, Web and Service project types. It does not start Spring, does not connect
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

`backend-crud` generates the complete base chain: PO, DAO, Mapper XML, Repository, domain model and
Domain Service/implementation, Command/Query/Result, matching CmdExe/QueryExe classes, converters,
and Manage/implementation. Light and Web also generate Controller, DTO, VO and Adapter converter;
Service generates no HTTP entry point. Selecting `command` emits three Command/CmdExe pairs,
selecting `query` emits two Query/QueryExe pairs, and selecting `controller` includes its DTOs,
VO and converter. The plan lists every file in the selected group.
`persistence-crud` expands to only the first four items. `artifacts` can be selected one by one, and
the aliases `pojo`→`po` and `mapper`→`mapper-xml` are normalized first. When a lower-level artifact
is omitted, `existingTypeMappings` must declare the type that already exists: a missing `po` or
`dao` returns `MISSING_TYPE` (exit code 3). The generator enforces only those two; the
`requiredTypes` of the remaining catalog entries take no part in validation today. Beyond those
grouped files and explicitly configured events, no additional artifacts are selected. Both queries and writes keep Mapper/XML access inside the
Repository, and a Domain Service implementation must not call a DAO directly. `logicalTables` must
be stated explicitly and matched one by one against the logical tables in the DDL; a table name that
does not exist fails immediately.

In the configuration JSON, `configVersion` must be `1`, `projectType` is one of
`light`/`web`/`service`, and unknown keys are always rejected (deserialization enables
`FAIL_ON_UNKNOWN_PROPERTIES`). `input.mode` has exactly two values: `schema` replays `schemaFiles`
in order, `manifest` reads the `manifest` path below `resourceRoot`. A manifest looks like
`{"family":...,"scripts":[{"version":...,"path":...,"sha256":...}]}`; script bytes that do not match
the recorded `sha256`, or a changed prefix among the already recorded versions, return
`CHECKSUM_DRIFT`.

## CQERS and inbound events

Generated code uses `domain/<domain>/{vos,service}`,
`application/<domain>/{pojo,manage,executor}`, and `adapter/<domain>/{controller,pojo,event}`.
Manage delegates to the matching executor; Commands keep their transaction boundaries and Queries
remain read-only. HTTP JSON fields preserve the prior Command/Result semantics, but generated
Controllers do not configure the target service's resource, scope or field authorization. Integrate
them with the target identity and authorization mechanisms before exposure; resource registration
alone does not grant roles.

No event class or empty handler is generated without a contract. To generate an inbound event,
explicitly select `command` or `backend-crud` and provide complete definitions. Multi-module
projects also need `modulePaths.adapter`. The following shows the event portion of a full configuration:

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

The table must be selected in `logicalTables`; names must be unique Java types ending in `Event`,
and action is `create`, `update`, or `delete`. Each `fieldMappings` key is a target Command Java
field and its value is a lowerCamel event field. Create maps all create fields; update maps `id`,
`expectedVersion` and all update fields; delete maps `id` and `expectedVersion`. The event always
contains `eventId` and `schemaVersion`, which cannot be mapping sources. The generator emits the
Event, MapStruct EventConverter, EventExe and an Adapter EventHandler. The handler is an entry point
for an existing consumer; it does not subscribe to a queue/topic or publish events.
Changing `schemaVersion` changes what the receiver accepts. Handle in-flight messages and producer
compatibility during rollout; the generator does not migrate or replay old messages.

The configured existing idempotency bean must provide a method compatible with
`void executeOnce(String eventType, String eventId, Object event, Runnable action)`; the method name
is configured by `idempotencyMethod`. Its type must be an Application/Domain port or shared contract,
not a target-project Infrastructure, Adapter or Starter implementation; Spring injects the
implementation. It must execute the action synchronously and atomically record
the result in a transaction compatible with the business write, scoped by trusted tenant, consumer,
event type and ID. Matching duplicates are skipped, a reused ID with different content is rejected,
and processing failures propagate as runtime exceptions so the transaction rolls back for retry. Use
the event to calculate a stable fingerprint without logging sensitive payloads. The caller owns source
validation, trusted tenant context, retry/dead-letter and
acknowledgement after commit. The generator creates no idempotency store and does not claim cross-
database or cross-service exactly-once delivery.

An old flat-layout file, retained ownership record, or previously owned artifact left behind by a
module-path change triggers `LEGACY_LAYOUT_CONFLICT` (exit 4).
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

Generation reads only `CREATE TABLE` definitions, including those inside the existing role-scoped
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

Current acceptance covers compiling from source and the isolated tests:
`./mvnw -B -ntp -pl stellar-enclosure-component-code-generator -am test`. It does not
mean PostgreSQL, ShardingSphere or MQ have run in a real environment.


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
