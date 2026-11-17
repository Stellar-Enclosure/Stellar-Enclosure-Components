package top.egon.cola.component.agentflow.memory.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.io.InputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ThreeTierMemoryStorageVerifierTest {

    private static final String V1_EXPECTED_SHA256 =
            "7aa621490c953b38a6231724e85d8fad4433e493d99d1bda37c8b749a6bc8654";

    @Test
    void verifiesManifestChecksumsAndPreservesV1() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        try (InputStream v1Stream = loader.getResourceAsStream("db/egon-agent-memory/V1__create_memory_store.sql");
             InputStream v1Manifest = loader.getResourceAsStream("db/egon-agent-memory/manifest.json");
             InputStream v2Stream = loader.getResourceAsStream("db/egon-agent-memory/V2__create_three_tier_memory.sql");
             InputStream v2Manifest = loader.getResourceAsStream("db/egon-agent-memory/three-tier-manifest.json")) {
            assertThat(v1Stream).isNotNull();
            assertThat(v1Manifest).isNotNull();
            assertThat(v2Stream).isNotNull();
            assertThat(v2Manifest).isNotNull();

            byte[] v1Bytes = v1Stream.readAllBytes();
            assertThat(sha256(v1Bytes)).isEqualTo(V1_EXPECTED_SHA256);

            JsonMapper mapper = JsonMapper.builder().build();
            JsonNode v1Root = mapper.readTree(v1Manifest);
            assertThat(v1Root.path("scripts")).hasSize(1);

            byte[] v2Bytes = v2Stream.readAllBytes();
            JsonNode v2Root = mapper.readTree(v2Manifest);
            assertThat(v2Root.path("schemaVersion").asInt()).isEqualTo(2);
            assertThat(v2Root.path("scripts")).hasSize(1);
            assertThat(v2Root.path("scripts").get(0).path("file").asText())
                    .isEqualTo("V2__create_three_tier_memory.sql");
            assertThat(v2Root.path("scripts").get(0).path("sha256").asText())
                    .isEqualTo(sha256(v2Bytes));
        }

        byte[] ddl = ThreeTierMemoryStorageVerifier.verifiedMigration();
        assertThat(new String(ddl))
                .contains("CREATE TABLE egon_agent_memory.session_message")
                .contains("CREATE TABLE egon_agent_memory.text_memory")
                .contains("CREATE TABLE egon_agent_memory.doc_memory");
    }

    @Test
    void rejectsInvalidDocTableIdentifierAndSchema() {
        DataSource dataSource = mock(DataSource.class);
        assertThatThrownBy(() -> new ThreeTierMemoryStorageVerifier(dataSource, "public", "doc_memory", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ThreeTierMemoryStorageVerifier(dataSource, "egon_agent_memory", "doc;drop", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingV2TablesOrWritableDocMemoryRole() throws Exception {
        DataSource validDs = mockPgCatalogDataSource(true, false);
        assertThatCode(() -> new ThreeTierMemoryStorageVerifier(validDs, "egon_agent_memory", "doc_memory", true).verify())
                .doesNotThrowAnyException();

        DataSource missingTableDs = mockPgCatalogDataSource(false, false);
        assertThatThrownBy(() -> new ThreeTierMemoryStorageVerifier(missingTableDs, "egon_agent_memory", "doc_memory", true).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("THREE_TIER_MEMORY_STORAGE_UNAVAILABLE");

        DataSource writableDocDs = mockPgCatalogDataSource(true, true);
        assertThatThrownBy(() -> new ThreeTierMemoryStorageVerifier(writableDocDs, "egon_agent_memory", "doc_memory", true).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("THREE_TIER_MEMORY_STORAGE_UNAVAILABLE");
    }

    private static DataSource mockPgCatalogDataSource(boolean allTablesPresent, boolean docWritable) throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(true);
        when(conn.isReadOnly()).thenReturn(true);

        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0, String.class);
            PreparedStatement ps = mock(PreparedStatement.class);
            ResultSet rs = mock(ResultSet.class);
            ResultSetMetaData meta = mock(ResultSetMetaData.class);
            when(ps.executeQuery()).thenReturn(rs);
            when(rs.getMetaData()).thenReturn(meta);
            when(rs.wasNull()).thenReturn(false);

            if (sql.contains("FROM pg_catalog.pg_roles")) {
                when(meta.getColumnCount()).thenReturn(1);
                when(meta.getColumnLabel(1)).thenReturn("allowed");
                when(meta.getColumnType(1)).thenReturn(Types.BOOLEAN);
                when(rs.next()).thenReturn(true, false);
                when(rs.getObject(1)).thenReturn(Boolean.TRUE);
                when(rs.getBoolean(1)).thenReturn(true);
            } else if (sql.contains("FROM pg_catalog.pg_class")) {
                when(meta.getColumnCount()).thenReturn(13);
                String[] tables = allTablesPresent
                        ? new String[]{"session_message", "text_memory", "doc_memory"}
                        : new String[]{"session_message", "text_memory"};
                final int[] cursor = {-1};
                when(rs.next()).thenAnswer(n -> ++cursor[0] < tables.length);
                when(rs.getString("relname")).thenAnswer(n -> tables[cursor[0]]);
                when(rs.getString("relkind")).thenReturn("r");
                when(rs.getString("relpersistence")).thenReturn("p");
                when(rs.getBoolean("relrowsecurity")).thenReturn(false);
                when(rs.getBoolean("relforcerowsecurity")).thenReturn(false);
                when(rs.getBoolean("can_select")).thenReturn(true);
                when(rs.getBoolean("can_insert")).thenAnswer(n -> switch (tables[cursor[0]]) {
                    case "session_message", "text_memory" -> true;
                    case "doc_memory" -> docWritable;
                    default -> false;
                });
                when(rs.getBoolean("can_update")).thenAnswer(n -> "text_memory".equals(tables[cursor[0]]));
                when(rs.getBoolean("can_update_column")).thenAnswer(n -> "text_memory".equals(tables[cursor[0]]));
                when(rs.getBoolean("can_delete")).thenAnswer(n -> "session_message".equals(tables[cursor[0]]));
                when(rs.getBoolean("can_destroy_ddl")).thenReturn(false);
                when(rs.getBoolean("owner_role")).thenReturn(false);
                when(rs.getBoolean("custom_triggers")).thenReturn(false);
            } else {
                when(meta.getColumnCount()).thenReturn(1);
                when(rs.next()).thenReturn(false);
            }
            return ps;
        });
        return ds;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
