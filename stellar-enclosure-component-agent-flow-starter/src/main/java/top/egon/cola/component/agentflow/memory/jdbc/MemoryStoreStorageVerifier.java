package top.egon.cola.component.agentflow.memory.jdbc;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Read-only verification of the installed component schema and the selected runtime role. */
public final class MemoryStoreStorageVerifier {

    static final String MIGRATION_ROOT = "db/egon-agent-memory/";
    static final String MIGRATION_FILE = "V1__create_memory_store.sql";
    private static final Set<String> TABLES = Set.of("scope", "revision", "fact", "receipt");
    private static final Pattern TABLE_START = Pattern.compile("(?m)^CREATE TABLE (?:egon_agent_memory\\.)?([a-z][a-z_]+) \\(");
    private static final Pattern COLUMN = Pattern.compile("(?m)^\\s{4}([a-z_][a-z_0-9]*) (bigint|boolean|jsonb|timestamptz\\(6\\)|char\\([0-9]+\\)|varchar\\([0-9]+\\))([^\\n]*)$");
    private static final Pattern CHECK = Pattern.compile("CONSTRAINT ([a-z_][a-z_0-9]*) CHECK \\(");
    private static final Pattern CASTS = Pattern.compile("(?i)::(?:character varying(?:\\([0-9]+\\))?|character(?:\\([0-9]+\\))?|bigint|integer|text)(?:\\[\\])?");
    private static final List<String> SCOPE_KEY = List.of("scope_hash");
    private static final List<String> FACT_KEY = List.of("scope_hash", "fact_id");
    private static final List<String> REVISION_KEY = List.of("scope_hash", "fact_id", "revision");
    private static final Set<KeyVO> KEYS = Set.of(
            primary("scope", SCOPE_KEY), primary("revision", REVISION_KEY), primary("fact", FACT_KEY),
            primary("receipt", List.of("scope_hash", "command_namespace", "command_id")),
            new KeyVO("revision", "u", List.of("scope_hash", "scope_revision"), "", "", List.of()),
            foreign("revision", SCOPE_KEY, "scope", SCOPE_KEY), foreign("fact", SCOPE_KEY, "scope", SCOPE_KEY),
            foreign("receipt", SCOPE_KEY, "scope", SCOPE_KEY),
            foreign("fact", List.of("scope_hash", "fact_id", "current_revision"), "revision", REVISION_KEY),
            foreign("receipt", List.of("scope_hash", "fact_id", "result_revision"), "revision", REVISION_KEY));
    private static final Set<IndexVO> INDEXES = Set.of(
            index("scope", "egon_agent_memory_scope_pkey", true, true, SCOPE_KEY, "bpchar_ops", "C"),
            index("revision", "egon_agent_memory_revision_pkey", true, true, REVISION_KEY,
                    "bpchar_ops,text_ops,int8_ops", "C,C,-"),
            index("revision", "memory_scope_revision_uq", true, false, List.of("scope_hash", "scope_revision"),
                    "bpchar_ops,int8_ops", "C,-"),
            index("fact", "egon_agent_memory_fact_pkey", true, true, FACT_KEY, "bpchar_ops,text_ops", "C,C"),
            index("fact", "memory_fact_scope_type_id_idx", false, false, List.of("scope_hash", "fact_type", "fact_id"),
                    "bpchar_ops,text_ops,text_ops", "C,C,C"),
            index("receipt", "egon_agent_memory_receipt_pkey", true, true, List.of("scope_hash", "command_namespace", "command_id"),
                    "bpchar_ops,text_ops,text_ops", "C,C,C"));
    private static final JsonMapper MANIFEST = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final DataSource source;
    private final String schema;

    public MemoryStoreStorageVerifier(DataSource source, String schema) {
        this.source = Objects.requireNonNull(source, "Memory data source");
        this.schema = requireSchema(schema);
    }

    public void verify() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        byte[] ddl = verifiedMigration();
        var jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setReadOnly(true);
        transaction.setTimeout(10);
        try {
            transaction.executeWithoutResult(status -> {
                requireRuntimeRole(jdbc);
                requireTables(jdbc);
                requireKeys(jdbc);
                requireIndexes(jdbc);
                requireStructure(jdbc, ddl);
            });
        } catch (DataAccessException invalidStorage) {
            throw unavailable();
        }
    }

    static String requireSchema(String schema) {
        if (schema == null || !schema.matches("[a-z_][a-z0-9_]{0,62}") || schema.startsWith("pg_")
                || "public".equals(schema) || "information_schema".equals(schema)) {
            throw new IllegalArgumentException("Invalid Agent Flow Memory schema");
        }
        return schema;
    }

    private static byte[] verifiedMigration() {
        try {
            JsonNode root = MANIFEST.readTree(uniqueResource("manifest.json", 8 * 1024));
            var scripts = root.path("scripts");
            if (!exact(root, "schemaVersion", "scripts") || !root.path("schemaVersion").isIntegralNumber()
                    || root.path("schemaVersion").asInt() != 1 || !scripts.isArray() || scripts.size() != 1) throw unavailable();
            var entry = scripts.get(0);
            if (!exact(entry, "version", "file", "sha256") || !entry.path("version").isIntegralNumber()
                    || entry.path("version").asInt() != 1 || !MIGRATION_FILE.equals(entry.path("file").textValue())) throw unavailable();
            byte[] sql = uniqueResource(MIGRATION_FILE, 256 * 1024);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql));
            if (!digest.equals(entry.path("sha256").textValue())) throw unavailable();
            return sql;
        } catch (IOException | NoSuchAlgorithmException | RuntimeException invalidManifest) {
            throw unavailable();
        }
    }

    private static byte[] uniqueResource(String name, int limit) throws IOException {
        List<URL> matches = Collections.list(MemoryStoreStorageVerifier.class.getClassLoader().getResources(MIGRATION_ROOT + name));
        if (matches.size() != 1) throw unavailable();
        try (var stream = matches.getFirst().openStream()) {
            byte[] bytes = stream.readNBytes(limit + 1);
            if (bytes.length == 0 || bytes.length > limit) throw unavailable();
            return bytes;
        }
    }

    private static boolean exact(JsonNode object, String... names) {
        if (object == null || !object.isObject() || object.size() != names.length) return false;
        return Arrays.stream(names).allMatch(object::has);
    }

    private void requireRuntimeRole(JdbcTemplate jdbc) {
        Boolean allowed = jdbc.queryForObject("""
                SELECT NOT pg_catalog.pg_is_in_recovery() AND current_setting('server_encoding')='UTF8'
                       AND current_setting('default_transaction_read_only')='off'
                       AND current_setting('session_replication_role')='origin'
                       AND rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole
                       AND NOT rolreplication AND NOT rolbypassrls
                       AND NOT has_database_privilege(current_user,current_database(),'CREATE')
                       AND has_schema_privilege(current_user,to_regnamespace(?),'USAGE')
                       AND NOT has_schema_privilege(current_user,to_regnamespace(?),'CREATE')
                FROM pg_catalog.pg_roles WHERE rolname=current_user
                """, Boolean.class, schema, schema);
        if (!Boolean.TRUE.equals(allowed)) throw unavailable();
    }

    private void requireTables(JdbcTemplate jdbc) {
        var details = jdbc.query("""
                SELECT c.relname,c.relkind,c.relpersistence,c.relrowsecurity,c.relforcerowsecurity,
                       has_table_privilege(current_user,c.oid,'SELECT') AS can_select,
                       has_table_privilege(current_user,c.oid,'INSERT') AS can_insert,
                       has_table_privilege(current_user,c.oid,'UPDATE') AS can_update,
                       has_any_column_privilege(current_user,c.oid,'UPDATE') AS can_update_column,
                       has_table_privilege(current_user,c.oid,'DELETE,TRUNCATE,TRIGGER') AS can_destroy,
                       pg_has_role(current_user,c.relowner,'MEMBER') AS owner_role,
                       EXISTS(SELECT FROM pg_trigger t WHERE t.tgrelid=c.oid AND NOT t.tgisinternal) AS custom_triggers
                FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname=? AND c.relname IN ('scope','revision','fact','receipt')
                """, (rs, row) -> {
            String table = rs.getString("relname");
            boolean shouldUpdate = Set.of("scope", "fact").contains(table);
            if (!"r".equals(rs.getString("relkind")) || !"p".equals(rs.getString("relpersistence"))
                    || rs.getBoolean("relrowsecurity") || rs.getBoolean("relforcerowsecurity")
                    || !rs.getBoolean("can_select") || !rs.getBoolean("can_insert") || shouldUpdate != rs.getBoolean("can_update")
                    || "receipt".equals(table) && rs.getBoolean("can_update_column")
                    || rs.getBoolean("can_destroy") || rs.getBoolean("owner_role") || rs.getBoolean("custom_triggers")) throw unavailable();
            return table;
        }, schema);
        if (!TABLES.equals(Set.copyOf(details))) throw unavailable();
    }

    private void requireKeys(JdbcTemplate jdbc) {
        var found = jdbc.query("""
                SELECT t.relname,c.contype,c.convalidated,c.condeferrable,c.condeferred,c.confdeltype,c.confupdtype,c.confmatchtype,
                       coalesce(nt.nspname,'') AS target_schema,coalesce(tt.relname,'') AS target,
                       ARRAY(SELECT a.attname::text FROM unnest(c.conkey) WITH ORDINALITY k(num,ord)
                             JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.num ORDER BY k.ord) AS columns,
                       ARRAY(SELECT a.attname::text FROM unnest(c.confkey) WITH ORDINALITY k(num,ord)
                             JOIN pg_attribute a ON a.attrelid=c.confrelid AND a.attnum=k.num ORDER BY k.ord) AS target_columns
                FROM pg_catalog.pg_constraint c JOIN pg_catalog.pg_class t ON t.oid=c.conrelid
                JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace
                LEFT JOIN pg_catalog.pg_class tt ON tt.oid=c.confrelid
                LEFT JOIN pg_catalog.pg_namespace nt ON nt.oid=tt.relnamespace
                WHERE n.nspname=? AND t.relname IN ('scope','revision','fact','receipt') AND c.contype IN ('p','u','f','x')
                """, (rs, row) -> {
            String type = rs.getString("contype");
            if (!rs.getBoolean("convalidated") || rs.getBoolean("condeferrable") || rs.getBoolean("condeferred")) throw unavailable();
            String targetSchema = rs.getString("target_schema");
            if ("f".equals(type) && (!schema.equals(targetSchema) || !"a".equals(rs.getString("confdeltype"))
                    || !"a".equals(rs.getString("confupdtype")) || !"s".equals(rs.getString("confmatchtype")))) throw unavailable();
            return new KeyVO(rs.getString("relname"), type, names(rs.getArray("columns")), targetSchema.isEmpty() ? "" : "same",
                    rs.getString("target"), names(rs.getArray("target_columns")));
        }, schema);
        if (found.size() != KEYS.size() || !KEYS.equals(Set.copyOf(found))) throw unavailable();
    }

    private void requireIndexes(JdbcTemplate jdbc) {
        var found = jdbc.query("""
                SELECT t.relname,ix.relname AS index_name,am.amname,i.indisunique,i.indisprimary,
                       i.indisvalid,i.indisready,i.indislive,i.indnkeyatts=i.indnatts AS key_only,
                       i.indpred IS NULL AND i.indexprs IS NULL AS unfiltered,
                       ARRAY(SELECT a.attname::text FROM unnest(i.indkey) WITH ORDINALITY k(num,ord)
                             JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=k.num ORDER BY k.ord) AS columns,
                       ARRAY(SELECT n.nspname::text||'.'||oc.opcname::text FROM unnest(i.indclass) WITH ORDINALITY k(num,ord)
                             JOIN pg_opclass oc ON oc.oid=k.num JOIN pg_namespace n ON n.oid=oc.opcnamespace ORDER BY k.ord) AS opclasses,
                       ARRAY(SELECT COALESCE(co.collname::text,'-') FROM unnest(i.indcollation) WITH ORDINALITY k(num,ord)
                             LEFT JOIN pg_collation co ON co.oid=k.num ORDER BY k.ord) AS collations,
                       NOT EXISTS (SELECT FROM unnest(i.indoption) AS flag WHERE flag <> 0) AS ascending
                FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class t ON t.oid=i.indrelid
                JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace
                JOIN pg_catalog.pg_class ix ON ix.oid=i.indexrelid JOIN pg_catalog.pg_am am ON am.oid=ix.relam
                WHERE n.nspname=? AND t.relname IN ('scope','revision','fact','receipt')
                """, (rs, row) -> {
            if (!"btree".equals(rs.getString("amname")) || !rs.getBoolean("indisvalid") || !rs.getBoolean("indisready")
                    || !rs.getBoolean("indislive") || !rs.getBoolean("key_only") || !rs.getBoolean("unfiltered")
                    || !rs.getBoolean("ascending")) throw unavailable();
            return new IndexVO(rs.getString("relname"), rs.getString("index_name"), rs.getBoolean("indisunique"),
                    rs.getBoolean("indisprimary"), names(rs.getArray("columns")), names(rs.getArray("opclasses")),
                    names(rs.getArray("collations")));
        }, schema);
        if (found.size() != INDEXES.size() || !INDEXES.equals(Set.copyOf(found))) throw unavailable();
    }

    private static List<String> names(Array array) throws SQLException {
        return List.copyOf(Arrays.asList((String[]) array.getArray()));
    }

    private static KeyVO primary(String table, List<String> columns) {
        return new KeyVO(table, "p", columns, "", "", List.of());
    }

    private static KeyVO foreign(String table, List<String> columns, String target, List<String> targetColumns) {
        return new KeyVO(table, "f", columns, "same", target, targetColumns);
    }

    private static IndexVO index(String table, String name, boolean unique, boolean primary, List<String> columns,
                                 String opclasses, String collations) {
        return new IndexVO(table, name, unique, primary, columns,
                Arrays.stream(opclasses.split(",")).map(opclass -> "pg_catalog." + opclass).toList(), List.of(collations.split(",")));
    }

    private void requireStructure(JdbcTemplate jdbc, byte[] ddl) {
        var expected = declaredStructure(new String(ddl, StandardCharsets.UTF_8));
        if (!TABLES.equals(expected.keySet())) throw unavailable();
        for (var table : expected.entrySet()) {
            requireColumns(jdbc, table.getKey(), table.getValue().columns());
            requireChecks(jdbc, table.getKey(), table.getValue().checks());
        }
    }

    private void requireColumns(JdbcTemplate jdbc, String table, Map<String, ColumnVO> expected) {
        var actual = new TreeMap<String, ColumnVO>();
        jdbc.query("""
                SELECT a.attname,format_type(a.atttypid,a.atttypmod) AS type,a.attnotnull,a.atthasdef,a.attgenerated,a.attidentity,
                       coalesce(co.collname,'') AS collation
                FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON c.oid=a.attrelid
                JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                LEFT JOIN pg_catalog.pg_collation co ON co.oid=a.attcollation
                WHERE n.nspname=? AND c.relname=? AND a.attnum>0 AND NOT a.attisdropped
                """, rs -> {
            if (rs.getBoolean("atthasdef") || !rs.getString("attgenerated").isEmpty() || !rs.getString("attidentity").isEmpty()) {
                throw unavailable();
            }
            actual.put(rs.getString("attname"), new ColumnVO(rs.getString("type"), rs.getBoolean("attnotnull"), rs.getString("collation")));
        }, schema, table);
        if (!expected.equals(actual)) throw unavailable();
    }

    private void requireChecks(JdbcTemplate jdbc, String table, Map<String, String> expected) {
        var actual = new TreeMap<String, String>();
        jdbc.query("""
                SELECT k.conname,k.convalidated,coalesce((to_jsonb(k)->>'conenforced')::boolean,true) AS enforced,
                       pg_get_expr(k.conbin,k.conrelid,false) AS expression
                FROM pg_catalog.pg_constraint k JOIN pg_catalog.pg_class c ON c.oid=k.conrelid
                JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname=? AND c.relname=? AND k.contype='c'
                """, rs -> {
            if (!rs.getBoolean("convalidated") || !rs.getBoolean("enforced")) throw unavailable();
            actual.put(rs.getString("conname"), booleanSignature(rs.getString("expression")));
        }, schema, table);
        if (!expected.equals(actual)) throw unavailable();
    }

    private static Map<String, TableVO> declaredStructure(String ddl) {
        var tables = new TreeMap<String, TableVO>();
        var headers = TABLE_START.matcher(ddl);
        while (headers.find()) {
            int start = headers.end() - 1;
            String body = ddl.substring(start + 1, closingParen(ddl, start));
            var fields = new TreeMap<String, ColumnVO>();
            var columns = COLUMN.matcher(body);
            while (columns.find()) {
                String settings = columns.group(3);
                String type = columns.group(2).replace("varchar(", "character varying(")
                        .replace("char(", "character(").replace("timestamptz(6)", "timestamp(6) with time zone");
                if (fields.put(columns.group(1), new ColumnVO(type, settings.contains("NOT NULL"),
                        settings.contains("COLLATE \"C\"") ? "C" : "")) != null) throw unavailable();
            }
            var checks = new TreeMap<String, String>();
            var constraints = CHECK.matcher(body);
            while (constraints.find()) {
                int open = constraints.end() - 1;
                if (checks.put(constraints.group(1), booleanSignature(body.substring(open + 1, closingParen(body, open)))) != null) {
                    throw unavailable();
                }
            }
            if (fields.isEmpty() || checks.isEmpty() || tables.put(headers.group(1), new TableVO(fields, checks)) != null) throw unavailable();
        }
        return tables;
    }

    /** Preserve boolean precedence and quoted values while ignoring the deparser's redundant parentheses and text casts. */
    private static String booleanSignature(String expression) {
        String value = expression.trim();
        while (value.startsWith("(") && closingParen(value, 0) == value.length() - 1) {
            value = value.substring(1, value.length() - 1).trim();
        }
        for (String operator : List.of("OR", "AND")) {
            var parts = splitBoolean(value, operator);
            if (parts.size() > 1) return operator + parts.stream().map(MemoryStoreStorageVerifier::booleanSignature).toList();
        }
        return "ATOM[" + atomicSignature(value) + "]";
    }

    private static List<String> splitBoolean(String source, String operator) {
        var parts = new java.util.ArrayList<String>();
        int nesting = 0;
        int caseDepth = 0;
        int start = 0;
        for (int i = 0; i < source.length(); i++) {
            char token = source.charAt(i);
            if (token == '\'') i = quotedEnd(source, i);
            else if (token == '(' || token == '[') nesting++;
            else if (token == ')' || token == ']') nesting--;
            else if (keyword(source, i, "CASE")) caseDepth++;
            else if (keyword(source, i, "END")) caseDepth--;
            else if (nesting == 0 && caseDepth == 0 && keyword(source, i, operator)) {
                parts.add(source.substring(start, i));
                i += operator.length() - 1;
                start = i + 1;
            }
            if (nesting < 0 || caseDepth < 0) throw unavailable();
        }
        if (nesting != 0 || caseDepth != 0) throw unavailable();
        parts.add(source.substring(start));
        return parts;
    }

    private static String atomicSignature(String source) {
        var result = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char token = source.charAt(i);
            if (token == '\'') {
                int end = quotedEnd(source, i);
                result.append(source, i, end + 1);
                i = end;
            } else if (token == ':' && i + 1 < source.length() && source.charAt(i + 1) == ':') {
                var cast = CASTS.matcher(source).region(i, source.length());
                if (cast.lookingAt()) i = cast.end() - 1;
                else result.append(token);
            } else if (!Character.isWhitespace(token) && token != '(' && token != ')') {
                result.append(Character.toLowerCase(token));
            }
        }
        return result.toString();
    }

    private static int closingParen(String source, int open) {
        int nesting = 1;
        for (int i = open + 1; i < source.length(); i++) {
            char token = source.charAt(i);
            if (token == '\'') i = quotedEnd(source, i);
            else if (token == '(') nesting++;
            else if (token == ')' && --nesting == 0) return i;
        }
        throw unavailable();
    }

    private static int quotedEnd(String source, int start) {
        for (int i = start + 1; i < source.length(); i++) {
            if (source.charAt(i) == '\'') {
                if (i + 1 < source.length() && source.charAt(i + 1) == '\'') i++;
                else return i;
            }
        }
        throw unavailable();
    }

    private static boolean identifier(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }

    private static boolean keyword(String source, int at, String word) {
        int end = at + word.length();
        return source.regionMatches(true, at, word, 0, word.length())
                && (at == 0 || !identifier(source.charAt(at - 1)))
                && (end == source.length() || !identifier(source.charAt(end)));
    }

    static IllegalStateException unavailable() {
        return new IllegalStateException("MEMORY_STORAGE_UNAVAILABLE");
    }

    private record KeyVO(String table, String type, List<String> columns, String targetSchema, String target, List<String> targetColumns) { }

    private record IndexVO(String table, String name, boolean unique, boolean primary, List<String> columns,
                           List<String> opclasses, List<String> collations) { }

    private record ColumnVO(String type, boolean required, String collation) { }

    private record TableVO(Map<String, ColumnVO> columns, Map<String, String> checks) { }
}
