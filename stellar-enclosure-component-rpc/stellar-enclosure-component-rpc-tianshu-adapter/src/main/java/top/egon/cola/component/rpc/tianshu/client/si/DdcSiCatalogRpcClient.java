package top.egon.cola.component.rpc.tianshu.client.si;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.Deadline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.validation.annotation.Validated;
import org.springframework.lang.Nullable;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.DdcSiResourceGrpcServiceGrpc.DdcSiResourceGrpcServiceBlockingStub;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.ListSiCatalogResponse;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.RegisterManagedSiResponse;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.ResolveSiResourceRequest;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.ResolveSiResourceResponse;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.SemanticSearchSiResponse;
import top.egon.cola.component.rpc.tianshu.mapping.DdcSiProtoMapper;
import top.egon.cola.component.tianshu.api.client.DdcSiCatalogClient;
import top.egon.cola.component.tianshu.model.si.DdcSiCatalogQuery;
import top.egon.cola.component.tianshu.model.si.DdcSiKindEnum;
import top.egon.cola.component.tianshu.model.si.DdcSiResourceDescriptor;
import top.egon.cola.component.tianshu.model.si.DdcSiSearchHit;
import top.egon.cola.component.tianshu.model.si.DdcSiSelfRegistrationRequest;
import top.egon.cola.component.tianshu.model.si.DdcSiSemanticSearchQuery;
import top.egon.cola.component.tianshu.model.si.DdcSiLifecycleStatusEnum;
import top.egon.cola.component.tianshu.format.DdcConfigCanonicalJson;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * gRPC client adapter implementing {@link DdcSiCatalogClient} over DdcSiResourceGrpcService.
 */
@Slf4j
@Validated
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class DdcSiCatalogRpcClient implements DdcSiCatalogClient {

    @Qualifier("ddcSiResourceBlockingStub")
    private final DdcSiResourceGrpcServiceBlockingStub blockingStub;

    @Qualifier("ddcSiProtoMapperImpl")
    private final DdcSiProtoMapper protoMapper;

    @Qualifier("ddcSiRpcDeadline")
    private final Duration rpcDeadline;

    @Override
    public List<DdcSiResourceDescriptor> listResources(DdcSiCatalogQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        String requestId = UUID.randomUUID().toString();
        try {
            ListSiCatalogResponse response = stubWithDeadline().listSiCatalog(protoMapper.toProto(query, requestId));
            return response.getItemsList().stream().map(protoMapper::fromProto).toList();
        } catch (StatusRuntimeException ex) {
            logRpcFailure("listSiCatalog", query.getTenantId(), query.getEnvCode(), "*", requestId, ex);
            throw ex;
        }
    }

    @Override
    public Optional<DdcSiResourceDescriptor> resolveResource(
            long tenantId,
            String bizCode,
            String namespaceCode,
            String envCode,
            String appCode,
            DdcSiKindEnum kind,
            String resourceKey,
            Integer pinnedVersionNo) {
        Objects.requireNonNull(kind, "kind must not be null");
        String requestId = UUID.randomUUID().toString();
        ResolveSiResourceRequest.Builder builder = ResolveSiResourceRequest.newBuilder()
                .setTenantId(tenantId)
                .setBizCode(nullToEmpty(bizCode))
                .setNamespaceCode(nullToEmpty(namespaceCode))
                .setEnvCode(nullToEmpty(envCode))
                .setAppCode(nullToEmpty(appCode))
                .setKind(kind.getCode())
                .setResourceKey(nullToEmpty(resourceKey))
                .setPinnedVersionNo(pinnedVersionNo != null && pinnedVersionNo > 0 ? pinnedVersionNo : 0)
                .setRequestId(requestId);
        try {
            ResolveSiResourceResponse response = stubWithDeadline().resolveSiResource(builder.build());
            if (!response.hasResource()) {
                return Optional.empty();
            }
            if (pinnedVersionNo != null && pinnedVersionNo > 0) {
                var resource = response.getResource();
                long selected = resource.hasResolvedVersionNo() ? resource.getResolvedVersionNo() : resource.getActiveVersionNo();
                if (selected != pinnedVersionNo.longValue()) { throw historyUnavailable(); }
            }
            return Optional.of(protoMapper.fromProto(response.getResource()));
        } catch (StatusRuntimeException ex) {
            if (ex.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            logRpcFailure("resolveSiResource", tenantId, envCode, resourceKey, requestId, ex);
            throw ex;
        }
    }

    /** Strict execution reads cannot consume the legacy metadata response or retry another version. */
    @Override
    public Optional<DdcSiResourceDescriptor> resolveExecutionResource(long tenantId,
            String bizCode, String namespaceCode, String envCode,
            @Nullable String ownerAppCode, String callerAppCode, DdcSiKindEnum kind,
            String resourceKey, @Nullable Long pinnedVersionNo) {
        if (tenantId <= 0 || kind == null || pinnedVersionNo != null && pinnedVersionNo <= 0) {
            throw new IllegalArgumentException("SI_EXECUTION_REQUEST_INVALID");
        }
        requireCoordinate(bizCode); requireCoordinate(namespaceCode); requireCoordinate(envCode);
        requireCoordinate(callerAppCode); requireCoordinate(resourceKey);
        if (ownerAppCode != null) { requireCoordinate(ownerAppCode); }
        var request = ResolveSiResourceRequest.newBuilder().setTenantId(tenantId).setBizCode(bizCode)
                .setNamespaceCode(namespaceCode).setEnvCode(envCode).setCallerAppCode(callerAppCode)
                .setKind(kind.getCode()).setResourceKey(resourceKey).setExecutionRequired(true)
                .setRequestId(UUID.randomUUID().toString());
        if (ownerAppCode != null) { request.setOwnerAppCode(ownerAppCode); }
        if (pinnedVersionNo != null) { request.setExactVersionNo(pinnedVersionNo); }
        final ResolveSiResourceResponse response;
        try { response = executionStub().resolveSiResource(request.build()); }
        catch (StatusRuntimeException failure) {
            if (failure.getStatus().getCode() == Status.Code.NOT_FOUND) { throw historyUnavailable(); }
            throw failure;
        }
        try {
            if (!response.hasResource()) { throw historyUnavailable(); }
            var result = protoMapper.fromProto(response.getResource());
            if (!Objects.equals(result.getTenantId(), tenantId) || !bizCode.equals(result.getBizCode())
                    || !namespaceCode.equals(result.getNamespaceCode()) || !envCode.equals(result.getEnvCode())
                    || !Objects.equals(ownerAppCode, result.getOwnerAppCode()) || kind != result.getKind()
                    || !resourceKey.equals(result.getResourceKey()) || result.getResourceId() == null || result.getResourceId() <= 0
                    || result.getResolvedVersionId() == null || result.getResolvedVersionId() <= 0
                    || result.getResolvedVersionNo() == null || result.getResolvedVersionNo() <= 0
                    || pinnedVersionNo != null && !pinnedVersionNo.equals(result.getResolvedVersionNo())
                    || result.getDependencyManifest() == null || !result.isExecutionSnapshotShapeValid()
                    || result.getStatus() == DdcSiLifecycleStatusEnum.DRAFT
                    || result.getSpecJson() == null
                    || result.getSpecJson().getBytes(StandardCharsets.UTF_8).length > 1_048_576
                    || result.getDigestSha256() == null || !result.getDigestSha256().matches("[0-9a-f]{64}")
                    || !result.getDigestSha256().equals(bodyDigest(result.getSpecJson()))
                    || !DdcConfigCanonicalJson.parse(result.getSpecJson().getBytes(StandardCharsets.UTF_8)).isObject()) {
                throw historyUnavailable();
            }
            return Optional.of(result);
        } catch (IllegalArgumentException invalidProof) { throw historyUnavailable(); }
    }

    private DdcSiResourceGrpcServiceBlockingStub executionStub() {
        if (rpcDeadline == null || rpcDeadline.isNegative() || rpcDeadline.isZero() || rpcDeadline.toMillis() <= 0) {
            throw new IllegalStateException("SI_EXECUTION_DEADLINE_REQUIRED");
        }
        Deadline limit = Deadline.after(rpcDeadline.toMillis(), TimeUnit.MILLISECONDS);
        Deadline existing = blockingStub.getCallOptions().getDeadline();
        return blockingStub.withDeadline(existing == null ? limit : existing.minimum(limit));
    }

    private static void requireCoordinate(String value) {
        if (value == null || value.isBlank() || value.length() > 256
                || !value.chars().allMatch(character -> character >= 0x21 && character <= 0x7e)) {
            throw new IllegalArgumentException("SI_EXECUTION_REQUEST_INVALID");
        }
    }

    private static String bodyDigest(String normalizedBody) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalizedBody.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static StatusRuntimeException historyUnavailable() {
        return Status.FAILED_PRECONDITION.withDescription("SI_PINNED_HISTORY_UNAVAILABLE").asRuntimeException();
    }

    @Override
    public List<DdcSiSearchHit> semanticSearch(DdcSiSemanticSearchQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        String requestId = UUID.randomUUID().toString();
        try {
            SemanticSearchSiResponse response = stubWithDeadline()
                    .semanticSearchSiResources(protoMapper.toProto(query, requestId));
            return response.getHitsList().stream().map(protoMapper::fromProto).toList();
        } catch (StatusRuntimeException ex) {
            logRpcFailure("semanticSearchSiResources", query.getTenantId(), query.getEnvCode(), "semantic", requestId, ex);
            throw ex;
        }
    }

    @Override
    public DdcSiResourceDescriptor registerManagedResource(DdcSiSelfRegistrationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String requestId = UUID.randomUUID().toString();
        try {
            RegisterManagedSiResponse response = stubWithDeadline()
                    .registerManagedSiResource(protoMapper.toProto(request, requestId));
            return protoMapper.fromProto(response.getResource());
        } catch (StatusRuntimeException ex) {
            logRpcFailure("registerManagedSiResource", request.getTenantId(), request.getEnvCode(),
                    request.getResourceKey(), requestId, ex);
            throw ex;
        }
    }

    private DdcSiResourceGrpcServiceBlockingStub stubWithDeadline() {
        long millis = rpcDeadline != null && rpcDeadline.toMillis() > 0 ? rpcDeadline.toMillis() : 5000L;
        return blockingStub.withDeadlineAfter(millis, TimeUnit.MILLISECONDS);
    }

    private void logRpcFailure(
            String operation,
            Long tenantId,
            String envCode,
            String resourceKey,
            String requestId,
            StatusRuntimeException ex) {
        log.warn("Tianshu SI gRPC failed op={} tenantId={} env={} key={} requestId={} status={}",
                operation, tenantId, envCode, resourceKey, requestId, ex.getStatus().getCode());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
