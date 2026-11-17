package top.egon.cola.component.agentflow.memory.jdbc;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.agentflow.api.context.ContextScopeVO;
import top.egon.cola.component.agentflow.api.memory.TextMemoryBindCommand;
import top.egon.cola.component.agentflow.api.memory.TextMemorySaveCommand;
import top.egon.cola.component.agentflow.api.memory.TextMemoryService;
import top.egon.cola.component.agentflow.api.memory.TextMemoryVO;
import top.egon.cola.component.common.core.enums.ResultCode;
import top.egon.cola.component.common.core.exception.CommonException;
import top.egon.cola.component.common.core.validation.ValidationUtils;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL implementation of {@link TextMemoryService} backed by {@code <schema>.text_memory}.
 */
@Slf4j
@Validated
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class JdbcTextMemoryService implements TextMemoryService {

    private static final int QUERY_TIMEOUT_SECONDS = 5;
    private static final int TRANSACTION_TIMEOUT_SECONDS = 10;

    private final DataSource dataSource;
    private final String schema;
    private final int maxChars;

    @Qualifier("agentExecutionClock")
    private final Clock clock;

    @Qualifier("egonColaValidationUtils")
    private final ValidationUtils validationUtils;

    @Override
    public TextMemoryVO bindOrCreate(@Valid @NotNull TextMemoryBindCommand command) {
        Objects.requireNonNull(command, "command");
        validationUtils.validate(command);
        TextMemoryBindCommand safe = command.snapshot();
        String verifiedSchema = ThreeTierMemoryStorageVerifier.requireSchema(schema);

        String candidateId = "tm_" + UUID.randomUUID().toString().replace("-", "");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);

        TransactionTemplate tx = writeTransaction();
        JdbcTemplate jdbc = writeJdbc();
        try {
            return tx.execute(status -> {
                jdbc.update(
                        "INSERT INTO " + verifiedSchema + ".text_memory "
                                + "(id, tenant_key, namespace, owner_type, owner_key, content_text, version, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, '', 1, ?, ?) "
                                + "ON CONFLICT (tenant_key, namespace, owner_type, owner_key) DO NOTHING",
                        candidateId,
                        safe.getTenantKey(),
                        safe.getNamespace(),
                        safe.getOwnerType().code(),
                        safe.getOwnerKey(),
                        now,
                        now);

                List<TextMemoryVO> rows = jdbc.query(
                        "SELECT id, version, content_text, updated_at FROM " + verifiedSchema + ".text_memory "
                                + "WHERE tenant_key = ? AND namespace = ? AND owner_type = ? AND owner_key = ?",
                        (rs, rowNum) -> {
                            String content = rs.getString("content_text");
                            OffsetDateTime updated = rs.getObject("updated_at", OffsetDateTime.class);
                            Instant instant = updated == null ? now.toInstant() : updated.toInstant();
                            return new TextMemoryVO(rs.getString("id"), rs.getLong("version"),
                                    content, content.length(), instant);
                        },
                        safe.getTenantKey(),
                        safe.getNamespace(),
                        safe.getOwnerType().code(),
                        safe.getOwnerKey());
                if (rows.isEmpty()) {
                    throw unavailable();
                }
                return rows.getFirst();
            });
        } catch (DataAccessException ex) {
            log.warn("Failed binding text memory in schema {}", verifiedSchema);
            throw unavailable();
        }
    }

    @Override
    public Optional<TextMemoryVO> getById(@NotBlank @Size(max = 128) String tenantKey,
                                          @NotBlank @Size(max = 64) String id) {
        ContextScopeVO.requireIdentifier(tenantKey);
        if (id == null || id.isBlank() || id.length() > 64) {
            throw new IllegalArgumentException("Invalid TextMemory id");
        }
        String verifiedSchema = ThreeTierMemoryStorageVerifier.requireSchema(schema);
        JdbcTemplate jdbc = readJdbc();
        try {
            List<TextMemoryVO> rows = jdbc.query(
                    "SELECT id, version, content_text, updated_at FROM " + verifiedSchema + ".text_memory "
                            + "WHERE id = ? AND tenant_key = ?",
                    (rs, rowNum) -> {
                        String content = rs.getString("content_text");
                        OffsetDateTime updated = rs.getObject("updated_at", OffsetDateTime.class);
                        Instant instant = updated == null ? clock.instant() : updated.toInstant();
                        return new TextMemoryVO(rs.getString("id"), rs.getLong("version"),
                                content, content.length(), instant);
                    },
                    id, tenantKey);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
        } catch (DataAccessException ex) {
            log.warn("Failed reading text memory from schema {}", verifiedSchema);
            throw unavailable();
        }
    }

    @Override
    public TextMemoryVO save(@Valid @NotNull TextMemorySaveCommand command) {
        Objects.requireNonNull(command, "command");
        TextMemorySaveCommand safe = command.snapshot(maxChars);
        validationUtils.validate(safe);
        String verifiedSchema = ThreeTierMemoryStorageVerifier.requireSchema(schema);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);

        TransactionTemplate tx = writeTransaction();
        JdbcTemplate jdbc = writeJdbc();
        try {
            return tx.execute(status -> {
                int updated = jdbc.update(
                        "UPDATE " + verifiedSchema + ".text_memory "
                                + "SET content_text = ?, version = version + 1, updated_at = ? "
                                + "WHERE id = ? AND tenant_key = ? AND version = ?",
                        safe.getContentText(),
                        now,
                        safe.getId(),
                        safe.getTenantKey(),
                        safe.getExpectedVersion());

                if (updated == 1) {
                    return new TextMemoryVO(
                            safe.getId(),
                            safe.getExpectedVersion() + 1L,
                            safe.getContentText(),
                            safe.getContentText().length(),
                            now.toInstant());
                }

                List<Long> versions = jdbc.queryForList(
                        "SELECT version FROM " + verifiedSchema + ".text_memory WHERE id = ? AND tenant_key = ?",
                        Long.class,
                        safe.getId(),
                        safe.getTenantKey());
                if (versions.isEmpty()) {
                    throw new CommonException(ResultCode.SYSTEM_ERROR.getCode(),
                            "TEXT_MEMORY_NOT_FOUND", "TEXT_MEMORY_NOT_FOUND");
                }
                throw new CommonException(ResultCode.SYSTEM_ERROR.getCode(),
                        "VERSION_CONFLICT", "VERSION_CONFLICT");
            });
        } catch (DataIntegrityViolationException checkViolation) {
            log.warn("Rejected oversized or invalid text memory write in schema {}", verifiedSchema);
            throw new CommonException(ResultCode.SYSTEM_ERROR.getCode(),
                    "TEXT_MEMORY_TOO_LARGE", "TEXT_MEMORY_TOO_LARGE");
        } catch (DataAccessException ex) {
            log.warn("Failed updating text memory in schema {}", verifiedSchema);
            throw unavailable();
        }
    }

    private JdbcTemplate readJdbc() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        return jdbc;
    }

    private JdbcTemplate writeJdbc() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(TRANSACTION_TIMEOUT_SECONDS);
        return jdbc;
    }

    private TransactionTemplate writeTransaction() {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        return tx;
    }

    private static CommonException unavailable() {
        return new CommonException(ResultCode.SYSTEM_ERROR.getCode(),
                "TEXT_MEMORY_UNAVAILABLE", "TEXT_MEMORY_UNAVAILABLE");
    }
}
