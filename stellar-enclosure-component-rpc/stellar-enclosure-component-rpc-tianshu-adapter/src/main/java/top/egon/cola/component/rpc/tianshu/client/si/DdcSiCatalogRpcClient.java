package top.egon.cola.component.rpc.tianshu.client.si;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * gRPC client adapter implementing {@link DdcSiCatalogClient} over DdcSiResourceGrpcService.
 */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class DdcSiCatalogRpcClient implements DdcSiCatalogClient {

    @Qualifier("ddcSiResourceBlockingStub")
    private final DdcSiResourceGrpcServiceBlockingStub blockingStub;

    @Qualifier("ddcSiProtoMapperImpl")
    private final DdcSiProtoMapper protoMapper;

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
            return Optional.of(protoMapper.fromProto(response.getResource()));
        } catch (StatusRuntimeException ex) {
            if (ex.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            logRpcFailure("resolveSiResource", tenantId, envCode, resourceKey, requestId, ex);
            throw ex;
        }
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
