package top.egon.cola.component.agentflow.memory.jdbc;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.agentflow.api.memory.DocMemoryQuery;
import top.egon.cola.component.agentflow.api.memory.DocMemoryReader;
import top.egon.cola.component.agentflow.api.memory.DocMemoryVO;
import top.egon.cola.component.agentflow.autoconfigure.AgentFlowProperties;
import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;
import top.egon.cola.component.common.core.validation.ValidationUtils;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * PostgreSQL read-only {@link DocMemoryReader} executing caller-order-preserving
 * {@code unnest(...) WITH ORDINALITY} queries against a whitelisted host-owned table.
 */
@Slf4j
@Validated
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class JdbcDocMemoryReader implements DocMemoryReader {

    private static final int QUERY_TIMEOUT_SECONDS = 5;

    private final DataSource dataSource;
    private final String schema;
    private final AgentFlowProperties.DocSettings docSettings;

    @Qualifier("egonColaValidationUtils")
    private final ValidationUtils validationUtils;

    @Override
    public List<DocMemoryVO> findByIds(@Valid @NotNull DocMemoryQuery query) {
        Objects.requireNonNull(query, "query");
        DocMemoryQuery safe = query.snapshot(docSettings.maxBatchSize());
        validationUtils.validate(safe);
        if (safe.getIds().isEmpty()) {
            return List.of();
        }

        String verifiedSchema = ThreeTierMemoryStorageVerifier.requireSchema(schema);
        String table = ThreeTierMemoryStorageVerifier.requireIdentifier(docSettings.tableName());
        String idCol = ThreeTierMemoryStorageVerifier.requireIdentifier(docSettings.idColumn());
        String tenantCol = ThreeTierMemoryStorageVerifier.requireIdentifier(docSettings.tenantColumn());
        String titleCol = ThreeTierMemoryStorageVerifier.requireIdentifier(docSettings.titleColumn());
        String contentCol = ThreeTierMemoryStorageVerifier.requireIdentifier(docSettings.contentColumn());
        String shaCol = ThreeTierMemoryStorageVerifier.requireIdentifier(docSettings.sha256Column());
        String deletedCol = ThreeTierMemoryStorageVerifier.requireIdentifier(docSettings.deletedColumn());
        String updatedCol = ThreeTierMemoryStorageVerifier.requireIdentifier(docSettings.updatedAtColumn());

        String sql = "SELECT d." + idCol + " AS doc_id, d." + titleCol + " AS doc_title, "
                + "d." + contentCol + " AS doc_content, d." + shaCol + " AS doc_sha256, d." + updatedCol + " AS doc_updated "
                + "FROM " + verifiedSchema + "." + table + " d "
                + "JOIN unnest(?::varchar[]) WITH ORDINALITY AS req(id, ord) ON d." + idCol + " = req.id "
                + "WHERE d." + tenantCol + " = ? AND d." + deletedCol + " = false "
                + "ORDER BY req.ord";

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        String[] idsArray = safe.getIds().toArray(String[]::new);

        try {
            return jdbc.query(con -> {
                PreparedStatement ps = con.prepareStatement(sql);
                Array pgArray = con.createArrayOf("varchar", idsArray);
                ps.setArray(1, pgArray);
                ps.setString(2, safe.getTenantKey());
                return ps;
            }, (rs, rowNum) -> {
                OffsetDateTime updated = rs.getObject("doc_updated", OffsetDateTime.class);
                Instant updatedInstant = Objects.requireNonNull(updated, "doc_memory updated_at").toInstant();
                return new DocMemoryVO(
                        rs.getString("doc_id"),
                        rs.getString("doc_title"),
                        rs.getString("doc_content"),
                        rs.getString("doc_sha256"),
                        updatedInstant);
            });
        } catch (DataAccessException ex) {
            log.warn("Failed reading doc memory from schema {} table {}", verifiedSchema, table);
            throw new CommonException(ResultCode.SYSTEM_ERROR.getCode(),
                    "DOC_MEMORY_UNAVAILABLE", "DOC_MEMORY_UNAVAILABLE");
        }
    }
}
