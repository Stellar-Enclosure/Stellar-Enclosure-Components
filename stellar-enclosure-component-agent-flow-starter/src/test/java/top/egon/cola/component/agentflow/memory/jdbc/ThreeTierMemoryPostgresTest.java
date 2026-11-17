package top.egon.cola.component.agentflow.memory.jdbc;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import top.egon.cola.component.agentflow.api.memory.DocMemoryQuery;
import top.egon.cola.component.agentflow.api.memory.DocMemoryVO;
import top.egon.cola.component.agentflow.api.memory.MemoryOwnerTypeEnum;
import top.egon.cola.component.agentflow.api.memory.TextMemoryBindCommand;
import top.egon.cola.component.agentflow.api.memory.TextMemorySaveCommand;
import top.egon.cola.component.agentflow.api.memory.TextMemoryVO;
import top.egon.cola.component.agentflow.autoconfigure.AgentFlowProperties;
import top.egon.cola.component.agentflow.memory.TurnAlignedChatMemory;
import top.egon.cola.component.common.core.exception.CommonException;
import top.egon.cola.component.common.core.validation.ValidationUtils;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(120)
@Isolated("Requires an explicitly provisioned fresh loopback PostgreSQL database")
class ThreeTierMemoryPostgresTest {

    private static final String URL_PROPERTY = "egon.agent.memory.postgres.test.url";
    private static final String RUNTIME_PROPERTY = "egon.agent.memory.postgres.test.runtime-user";
    private static final String ADMIN_USER_PROPERTY = "egon.agent.memory.postgres.test.admin-user";
    private static final String ADMIN_PASSWORD_PROPERTY = "egon.agent.memory.postgres.test.admin-password";
    private static final String SCHEMA = "egon_agent_memory";

    @Test
    void verifiesV2DdlLeastPrivilegeConcurrencyAndReadonlyDocMemoryOnPostgres() throws Exception {
        String url = System.getProperty(URL_PROPERTY, "");
        String runtimeUser = System.getProperty(RUNTIME_PROPERTY, "");
        Assumptions.assumeTrue(!url.isBlank() && !runtimeUser.isBlank(),
                "Skipped PostgreSQL integration test: system properties "
                        + URL_PROPERTY + " and " + RUNTIME_PROPERTY + " are absent");

        String adminUser = System.getProperty(ADMIN_USER_PROPERTY, "postgres");
        String adminPassword = System.getProperty(ADMIN_PASSWORD_PROPERTY, "");

        try (Connection admin = DriverManager.getConnection(url, adminUser, adminPassword);
             InputStream ddlStream = getClass().getResourceAsStream("/db/egon-agent-memory/V2__create_three_tier_memory.sql")) {
            assertThat(ddlStream).isNotNull();
            String ddl = new String(ddlStream.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement st = admin.createStatement()) {
                st.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
                st.execute(ddl);
                st.execute("GRANT USAGE ON SCHEMA " + SCHEMA + " TO " + runtimeUser);
                st.execute("REVOKE ALL ON ALL TABLES IN SCHEMA " + SCHEMA + " FROM " + runtimeUser);
                st.execute("GRANT SELECT, INSERT, DELETE ON " + SCHEMA + ".session_message TO " + runtimeUser);
                st.execute("GRANT SELECT, INSERT, UPDATE ON " + SCHEMA + ".text_memory TO " + runtimeUser);
                st.execute("GRANT SELECT ON " + SCHEMA + ".doc_memory TO " + runtimeUser);

                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                st.execute("INSERT INTO " + SCHEMA + ".doc_memory (id, tenant_key, title, content_md, content_sha256, deleted, updated_at) "
                        + "VALUES ('d1', 'tenant-a', 'Doc 1', '# One', NULL, false, '" + now + "'), "
                        + "('d3', 'tenant-a', 'Doc 3', '# Three', NULL, false, '" + now + "'), "
                        + "('d_deleted', 'tenant-a', 'Deleted', '# Deleted', NULL, true, '" + now + "'), "
                        + "('d_other', 'tenant-b', 'Other', '# Other', NULL, false, '" + now + "') "
                        + "ON CONFLICT DO NOTHING");
            }
        }

        PGSimpleDataSource runtimeDs = new PGSimpleDataSource();
        runtimeDs.setURL(url);
        runtimeDs.setUser(runtimeUser);
        runtimeDs.setPassword(System.getProperty("egon.agent.memory.postgres.test.runtime-password", ""));

        new ThreeTierMemoryStorageVerifier(runtimeDs, SCHEMA, "doc_memory", true).verify();

        verifyLevel1SessionOnPostgres(runtimeDs);
        verifyLevel2TextMemoryOnPostgres(runtimeDs);
        verifyLevel3DocMemoryOnPostgres(runtimeDs);
    }

    private static void verifyLevel1SessionOnPostgres(DataSource ds) throws Exception {
        var repo = new JdbcSessionChatMemoryRepository(ds, SCHEMA, Clock.systemUTC());
        var chatMemory = new TurnAlignedChatMemory(repo, 20, Clock.systemUTC());

        int threads = 4;
        var latch = new CountDownLatch(threads);
        var executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    var tc = new AssistantMessage.ToolCall("c-" + idx, "function", "ping", "{\"n\":" + idx + "}");
                    chatMemory.appendTurn("tenant-a", "pg-conv-1", "t-" + idx, List.of(
                            new UserMessage("Q-" + idx),
                            AssistantMessage.builder().content("").toolCalls(List.of(tc)).build(),
                            ToolResponseMessage.builder().responses(List.of(
                                    new ToolResponseMessage.ToolResponse("c-" + idx, "ping", "{\"ok\":true}")))
                                    .build(),
                            new AssistantMessage("A-" + idx)));
                } finally {
                    latch.countDown();
                }
            });
        }
        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        var loaded = chatMemory.get("tenant-a", "pg-conv-1", 20);
        assertThat(loaded).hasSize(threads * 4);
    }

    private static void verifyLevel2TextMemoryOnPostgres(DataSource ds) {
        var validation = new ValidationUtils(Validation.buildDefaultValidatorFactory().getValidator());
        var service = new JdbcTextMemoryService(ds, SCHEMA, 10_000, Clock.systemUTC(), validation);

        TextMemoryVO bound = service.bindOrCreate(TextMemoryBindCommand.builder()
                .tenantKey("tenant-a").namespace("ns")
                .ownerType(MemoryOwnerTypeEnum.AGENT).ownerKey("pg-agent").build());
        TextMemoryVO saved = service.save(TextMemorySaveCommand.builder()
                .tenantKey("tenant-a").id(bound.id())
                .contentText("x".repeat(10_000)).expectedVersion(1L).build());
        assertThat(saved.version()).isEqualTo(2L);

        assertThatThrownBy(() -> service.save(TextMemorySaveCommand.builder()
                .tenantKey("tenant-a").id(bound.id())
                .contentText("x".repeat(10_001)).expectedVersion(2L).build()))
                .isInstanceOf(CommonException.class)
                .hasMessage("TEXT_MEMORY_TOO_LARGE");

        assertThatThrownBy(() -> service.save(TextMemorySaveCommand.builder()
                .tenantKey("tenant-a").id(bound.id())
                .contentText("stale").expectedVersion(1L).build()))
                .isInstanceOf(CommonException.class)
                .hasMessage("VERSION_CONFLICT");
    }

    private static void verifyLevel3DocMemoryOnPostgres(DataSource ds) throws SQLException {
        var validation = new ValidationUtils(Validation.buildDefaultValidatorFactory().getValidator());
        var reader = new JdbcDocMemoryReader(ds, SCHEMA, AgentFlowProperties.DocSettings.defaults(), validation);

        List<DocMemoryVO> docs = reader.findByIds(DocMemoryQuery.builder()
                .tenantKey("tenant-a")
                .ids(List.of("d3", "d1", "d_deleted", "d_other"))
                .build());
        assertThat(docs).extracting(DocMemoryVO::id).containsExactly("d3", "d1");

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate(
                    "INSERT INTO " + SCHEMA + ".doc_memory VALUES ('evil','tenant-a','T','M',NULL,false,now())"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }
}
