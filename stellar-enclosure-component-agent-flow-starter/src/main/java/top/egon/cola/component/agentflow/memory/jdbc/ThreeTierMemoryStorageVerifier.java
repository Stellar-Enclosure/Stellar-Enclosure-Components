package top.egon.cola.component.agentflow.memory.jdbc;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only startup verifier for the V2 three-tier memory schema and least-privilege PostgreSQL runtime role.
 */
@Slf4j
public final class ThreeTierMemoryStorageVerifier {

    static final String MIGRATION_ROOT = "db/egon-agent-memory/";
    static final String MANIFEST_FILE = "three-tier-manifest.json";
    static final String MIGRATION_FILE = "V2__create_three_tier_memory.sql";
    private static final String TABLE_SESSION_MESSAGE = "session_message";
    private static final String TABLE_TEXT_MEMORY = "text_memory";

    private static final JsonMapper MANIFEST = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final DataSource source;
    private final String schema;
    private final String docTable;
    private final boolean verifyDocTable;

    public ThreeTierMemoryStorageVerifier(DataSource source, String schema, String docTable) {
        this(source, schema, docTable, true);
    }

    public ThreeTierMemoryStorageVerifier(DataSource source, String schema, String docTable, boolean verifyDocTable) {
        this.source = Objects.requireNonNull(source, "Three-tier memory data source");
        this.schema = requireSchema(schema);
        this.docTable = requireIdentifier(docTable);
        this.verifyDocTable = verifyDocTable;
    }

    public static String requireSchema(String schema) {
        return MemoryStoreStorageVerifier.requireSchema(schema);
    }

    public static String requireIdentifier(String identifier) {
        if (identifier == null || !identifier.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Invalid Agent Flow three-tier SQL identifier");
        }
        return identifier;
    }

    /** Executes manifest checksum verification, PostgreSQL runtime role check, and table privilege boundaries. */
    public void verify() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw unavailable();
        }
        verifiedMigration();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setReadOnly(true);
        transaction.setTimeout(10);
        try {
            transaction.executeWithoutResult(status -> {
                requireRuntimeRole(jdbc);
                requireTablesAndPrivileges(jdbc);
            });
        } catch (DataAccessException failure) {
            log.warn("Three-tier memory storage verification failed for schema {}", schema);
            throw unavailable();
        }
    }

    static byte[] verifiedMigration() {
        try {
            JsonNode root = MANIFEST.readTree(uniqueResource(MANIFEST_FILE, 8 * 1024));
            JsonNode scripts = root.path("scripts");
            if (!exact(root, "schemaVersion", "scripts")
                    || !root.path("schemaVersion").isIntegralNumber()
                    || root.path("schemaVersion").asInt() != 2
                    || !scripts.isArray()
                    || scripts.size() != 1) {
                throw unavailable();
            }
            JsonNode entry = scripts.get(0);
            if (!exact(entry, "version", "file", "sha256")
                    || !entry.path("version").isIntegralNumber()
                    || entry.path("version").asInt() != 2
                    || !MIGRATION_FILE.equals(entry.path("file").textValue())) {
                throw unavailable();
            }
            byte[] sql = uniqueResource(MIGRATION_FILE, 256 * 1024);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql));
            if (!digest.equals(entry.path("sha256").textValue())) {
                throw unavailable();
            }
            return sql;
        } catch (IOException | NoSuchAlgorithmException | RuntimeException invalidManifest) {
            throw unavailable();
        }
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
        if (!Boolean.TRUE.equals(allowed)) {
            throw unavailable();
        }
    }

    private void requireTablesAndPrivileges(JdbcTemplate jdbc) {
        Set<String> expectedTables = verifyDocTable
                ? Set.of(TABLE_SESSION_MESSAGE, TABLE_TEXT_MEMORY, docTable)
                : Set.of(TABLE_SESSION_MESSAGE, TABLE_TEXT_MEMORY);

        List<String> found = jdbc.query("""
                SELECT c.relname, c.relkind, c.relpersistence, c.relrowsecurity, c.relforcerowsecurity,
                       has_table_privilege(current_user, c.oid, 'SELECT') AS can_select,
                       has_table_privilege(current_user, c.oid, 'INSERT') AS can_insert,
                       has_table_privilege(current_user, c.oid, 'UPDATE') AS can_update,
                       has_any_column_privilege(current_user, c.oid, 'UPDATE') AS can_update_column,
                       has_table_privilege(current_user, c.oid, 'DELETE') AS can_delete,
                       has_table_privilege(current_user, c.oid, 'TRUNCATE,TRIGGER') AS can_destroy_ddl,
                       pg_has_role(current_user, c.relowner, 'MEMBER') AS owner_role,
                       EXISTS(SELECT FROM pg_trigger t WHERE t.tgrelid = c.oid AND NOT t.tgisinternal) AS custom_triggers
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relname = ANY(?)
                """, (rs, rowNum) -> {
            String table = rs.getString("relname");
            if (!"r".equals(rs.getString("relkind"))
                    || !"p".equals(rs.getString("relpersistence"))
                    || rs.getBoolean("relrowsecurity")
                    || rs.getBoolean("relforcerowsecurity")
                    || !rs.getBoolean("can_select")
                    || rs.getBoolean("can_destroy_ddl")
                    || rs.getBoolean("owner_role")
                    || rs.getBoolean("custom_triggers")) {
                throw unavailable();
            }
            if (TABLE_SESSION_MESSAGE.equals(table)) {
                if (!rs.getBoolean("can_insert") || rs.getBoolean("can_update")
                        || rs.getBoolean("can_update_column") || !rs.getBoolean("can_delete")) {
                    throw unavailable();
                }
            } else if (TABLE_TEXT_MEMORY.equals(table)) {
                if (!rs.getBoolean("can_insert") || !rs.getBoolean("can_update") || rs.getBoolean("can_delete")) {
                    throw unavailable();
                }
            } else if (verifyDocTable && docTable.equals(table)) {
                if (rs.getBoolean("can_insert") || rs.getBoolean("can_update")
                        || rs.getBoolean("can_update_column") || rs.getBoolean("can_delete")) {
                    throw unavailable();
                }
            }
            return table;
        }, schema, expectedTables.toArray(String[]::new));

        if (!expectedTables.equals(new HashSet<>(found))) {
            throw unavailable();
        }
    }

    private static byte[] uniqueResource(String name, int limit) throws IOException {
        List<URL> matches = Collections.list(
                ThreeTierMemoryStorageVerifier.class.getClassLoader().getResources(MIGRATION_ROOT + name));
        if (matches.size() != 1) {
            throw unavailable();
        }
        try (var stream = matches.getFirst().openStream()) {
            byte[] bytes = stream.readNBytes(limit + 1);
            if (bytes.length == 0 || bytes.length > limit) {
                throw unavailable();
            }
            return bytes;
        }
    }

    private static boolean exact(JsonNode object, String... names) {
        if (object == null || !object.isObject() || object.size() != names.length) {
            return false;
        }
        return Arrays.stream(names).allMatch(object::has);
    }

    static IllegalStateException unavailable() {
        return new IllegalStateException("THREE_TIER_MEMORY_STORAGE_UNAVAILABLE");
    }
}
