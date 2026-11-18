package top.egon.cola.component.outbox.persistence.dao;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.common.mybatis.extension.EgonColaExplicitTenantScopeMapper;
import top.egon.cola.component.common.mybatis.extension.EgonColaMapper;
import top.egon.cola.component.common.mybatis.model.EgonColaModelValidationGroups;
import top.egon.cola.component.outbox.persistence.po.OutboxMessagePO;
import top.egon.cola.component.outbox.store.OutboxStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Explicit persistence operations with fixed tenant predicates for the technical outbox table. */
@EgonColaExplicitTenantScopeMapper(
        technicalModel = OutboxMessagePO.class,
        technicalTable = "egon_outbox.egon_cola_outbox_message")
@Mapper
@Validated
public interface OutboxMessageDAO extends EgonColaMapper<OutboxMessagePO> {

    int insertMessage(@Param("et") @NotNull @Valid OutboxMessagePO entity);

    int insertMigrationMessage(@Param("et") @NotNull @Valid OutboxMessagePO entity);

    List<OutboxMessagePO> selectExistingByIdentity(
            @Param("messageId") @NotBlank @Size(max = 64) String messageId,
            @Param("idempotencyKey") @Size(max = 256) String idempotencyKey
    );

    List<OutboxMessagePO> selectReplicaByMessageId(
            @Param("messageId") @NotBlank @Size(max = 64) String messageId
    );

    List<OutboxMessagePO> selectDueCandidates(@Param("limit") @Min(1) @Max(10_000) int limit);

    List<OutboxMessagePO> selectDueCandidatesByChannels(
            @Param("channels") @NotEmpty Collection<@NotBlank @Size(max = 64) String> channels,
            @Param("limit") @Min(1) @Max(10_000) int limit
    );

    List<OutboxMessagePO> selectByMessageIdsCandidates(
            @Param("messageIds") @NotEmpty Collection<@NotBlank @Size(max = 64) String> messageIds,
            @Param("limit") @Min(1) @Max(10_000) int limit
    );

    List<OutboxMessagePO> selectByMessageIdsCandidatesByChannels(
            @Param("messageIds") @NotEmpty Collection<@NotBlank @Size(max = 64) String> messageIds,
            @Param("channels") @NotEmpty Collection<@NotBlank @Size(max = 64) String> channels,
            @Param("limit") @Min(1) @Max(10_000) int limit
    );

    Boolean tryAcquireTransactionLock(@Param("id") @Positive long id);

    int updateClaim(
            @Param("id") @Positive long id,
            @Param("version") @Min(0) long version,
            @Param("source") @NotNull OutboxStatus source,
            @Param("target") @NotNull OutboxStatus target,
            @Param("owner") @NotBlank @Size(max = 128) String owner,
            @Param("leaseMillis") @Positive long leaseMillis,
            @Param("updateTime") @NotNull Instant updateTime,
            @Param("updateUserId") @NotBlank @Size(max = 128) String updateUserId
    );

    OutboxMessagePO selectProcessingForUpdate(
            @Param("id") @Positive long id,
            @Param("owner") @NotBlank @Size(max = 128) String owner
    );

    int updateSucceeded(
            @Param("id") @Positive long id,
            @Param("version") @Min(0) long version,
            @Param("owner") @NotBlank @Size(max = 128) String owner,
            @Param("target") @NotNull OutboxStatus target,
            @Param("updateTime") @NotNull Instant updateTime,
            @Param("updateUserId") @NotBlank @Size(max = 128) String updateUserId
    );

    int updateRetry(
            @Param("id") @Positive long id,
            @Param("version") @Min(0) long version,
            @Param("owner") @NotBlank @Size(max = 128) String owner,
            @Param("target") @NotNull OutboxStatus target,
            @Param("delayMillis") @Min(0) long delayMillis,
            @Param("errorCode") @Size(max = 64) String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("updateTime") @NotNull Instant updateTime,
            @Param("updateUserId") @NotBlank @Size(max = 128) String updateUserId
    );

    int updateDead(
            @Param("id") @Positive long id,
            @Param("version") @Min(0) long version,
            @Param("owner") @NotBlank @Size(max = 128) String owner,
            @Param("target") @NotNull OutboxStatus target,
            @Param("errorCode") @Size(max = 64) String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("updateTime") @NotNull Instant updateTime,
            @Param("updateUserId") @NotBlank @Size(max = 128) String updateUserId
    );

    int updateReclaimDead(
            @Param("id") @Positive long id,
            @Param("version") @Min(0) long version,
            @Param("errorCode") @Size(max = 64) String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("updateTime") @NotNull Instant updateTime,
            @Param("updateUserId") @NotBlank @Size(max = 128) String updateUserId
    );

    List<OutboxMessagePO> selectExpiredSucceededCandidates(
            @Param("retentionMillis") @Min(0) long retentionMillis,
            @Param("limit") @Min(1) @Max(10_000) int limit
    );

    List<OutboxMessagePO> selectExhaustedCandidates(@Param("cooldownMillis") @Positive long cooldownMillis,
            @Param("limit") @Min(1) @Max(10_000) int limit);

    int redriveExhausted(@Param("id") @Positive long id, @Param("version") @Min(0) long version,
            @Param("cooldownMillis") @Positive long cooldownMillis, @Param("updateTime") @NotNull Instant updateTime,
            @Param("updateUserId") @NotBlank @Size(max = 128) String updateUserId);

    int deleteSucceededByIdVersion(
            @Param("id") @Positive long id,
            @Param("version") @Min(0) long version,
            @Param("retentionMillis") @Min(0) long retentionMillis
    );

    long countBacklog();

    List<OutboxMessagePO> selectMigrationPage(
            @Param("afterId") @Min(0) long afterId,
            @Param("limit") @Min(1) int limit
    );

    @Override
    default int deleteVersionedById(@Param("et") OutboxMessagePO entity) {
        throw new UnsupportedOperationException("OUTBOX_SOFT_DELETE_UNSUPPORTED");
    }

    @Override
    @Validated(EgonColaModelValidationGroups.Restore.class)
    default int restoreVersionedById(
            @Param("et") @Valid @NotNull(groups = EgonColaModelValidationGroups.Restore.class) OutboxMessagePO entity) {
        throw new UnsupportedOperationException("OUTBOX_RESTORE_UNSUPPORTED");
    }
}
