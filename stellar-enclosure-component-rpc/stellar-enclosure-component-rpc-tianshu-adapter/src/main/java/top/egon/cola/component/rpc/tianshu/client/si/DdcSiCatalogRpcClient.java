package top.egon.cola.component.rpc.tianshu.client.si;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import lombok.AccessLevel;
import lombok.NonNull;
import top.egon.cola.component.rpc.common.exception.EgonRpcException;
import top.egon.cola.component.rpc.consumer.generic.RpcGenericInvocation;
import top.egon.cola.component.rpc.consumer.generic.RpcGenericInvoker;
import top.egon.cola.component.rpc.consumer.reference.RpcServiceTargetVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.ListSiCatalogResponse;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.RegisterManagedSiResponse;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.ResolveSiResourceRequest;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.ResolveSiResourceResponse;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.SemanticSearchSiResponse;
import top.egon.cola.component.rpc.tianshu.mapping.DdcSiProtoMapper;
import top.egon.cola.component.tianshu.api.client.DdcSiCatalogClient;
import top.egon.cola.component.tianshu.model.si.DdcSiCatalogQuery;
import top.egon.cola.component.tianshu.model.si.DdcSiKindEnum;
import top.egon.cola.component.tianshu.model.si.DdcSiExecutionBundleVO;
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
import java.util.Set;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Protected native unary adapter implementing {@link DdcSiCatalogClient} over DdcSiResourceGrpcService.
 */
@Slf4j
@Validated
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class DdcSiCatalogRpcClient implements DdcSiCatalogClient {

    public static final String SERVICE = "egon.tianshu.v1.DdcSiResourceGrpcService";
    public static final String GROUP = "tianshu";
    public static final String VERSION = "1.0.0";
    public static final String LIST = SERVICE + "/ListSiCatalog";
    public static final String RESOLVE = SERVICE + "/ResolveSiResource";
    public static final String SEARCH = SERVICE + "/SemanticSearchSiResources";
    public static final String REGISTER = SERVICE + "/RegisterManagedSiResource";
    public static final Set<String> METHODS = Set.of(LIST, RESOLVE, SEARCH, REGISTER);
    private static final int MAX_WIRE_BYTES = 4 * 1024 * 1024;

    @NonNull @Qualifier("rpcGenericInvoker") private final RpcGenericInvoker invoker;
    @NonNull @Qualifier("ddcSiProtoMapperImpl") private final DdcSiProtoMapper protoMapper;
    @NonNull private final NativeBindingVO binding;

    /** The target must be the immutable Service binding used by this invoker's configured resolver. */
    public DdcSiCatalogRpcClient(RpcGenericInvoker invoker, DdcSiProtoMapper mapper,
            RpcServiceTargetVO target, Duration rpcDeadline) {
        this(invoker, mapper, new NativeBindingVO(target, rpcDeadline));
    }

    @Override
    public List<DdcSiResourceDescriptor> listResources(DdcSiCatalogQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        String requestId = "";
        try {
            ListSiCatalogResponse response = unary(LIST, protoMapper.toProto(query, requestId), ListSiCatalogResponse.parser());
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
        String requestId = "";
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
            ResolveSiResourceResponse response = unary(RESOLVE, builder.build(), ResolveSiResourceResponse.parser());
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
                .setRequestId("");
        if (ownerAppCode != null) { request.setOwnerAppCode(ownerAppCode); }
        if (pinnedVersionNo != null) { request.setExactVersionNo(pinnedVersionNo); }
        final ResolveSiResourceResponse response;
        try { response = unary(RESOLVE, request.build(), ResolveSiResourceResponse.parser()); }
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

    /** No metadata, cached pointer or active-version substitute can satisfy this opt-in material read. */
    @Override
    public Optional<DdcSiExecutionBundleVO> resolveExecutionBundle(long tenantId, String bizCode, String namespaceCode,
            String envCode, @Nullable String ownerAppCode, String callerAppCode, DdcSiKindEnum kind,
            String resourceKey, @Nullable Long pinnedVersionNo) {
        if (tenantId <= 0 || kind != DdcSiKindEnum.AGENT || pinnedVersionNo != null && pinnedVersionNo <= 0)
            throw new IllegalArgumentException("SI_EXECUTION_REQUEST_INVALID");
        requireCoordinate(bizCode); requireCoordinate(namespaceCode); requireCoordinate(envCode);
        requireCoordinate(callerAppCode); requireCoordinate(resourceKey);
        if (ownerAppCode != null) requireCoordinate(ownerAppCode);
        var request = ResolveSiResourceRequest.newBuilder().setTenantId(tenantId).setBizCode(bizCode)
                .setNamespaceCode(namespaceCode).setEnvCode(envCode).setCallerAppCode(callerAppCode)
                .setKind(kind.getCode()).setResourceKey(resourceKey).setExecutionRequired(true)
                .setIncludeExecutionMaterial(true);
        if (ownerAppCode != null) request.setOwnerAppCode(ownerAppCode);
        if (pinnedVersionNo != null) request.setExactVersionNo(pinnedVersionNo);
        final ResolveSiResourceResponse response;
        try { response = unary(RESOLVE, request.build(), ResolveSiResourceResponse.parser()); }
        catch (StatusRuntimeException failure) {
            if (failure.getStatus().getCode() == Status.Code.NOT_FOUND) throw historyUnavailable();
            throw failure;
        }
        try {
            if (!response.hasExecutionBundle() || response.hasResource()) throw historyUnavailable();
            var bundle = protoMapper.fromProto(response.getExecutionBundle());
            var root = bundle.root(); var identity = root.identity();
            if (!Long.toString(tenantId).equals(identity.tenantId()) || !bizCode.equals(root.bizCode())
                    || !namespaceCode.equals(root.namespaceCode()) || !envCode.equals(identity.envCode())
                    || !Objects.equals(ownerAppCode, root.ownerAppCode()) || kind != identity.kind()
                    || !resourceKey.equals(root.resourceKey())
                    || pinnedVersionNo != null && !Long.toString(pinnedVersionNo).equals(identity.versionNo())) throw historyUnavailable();
            return Optional.of(bundle);
        } catch (IllegalArgumentException invalidProof) { throw historyUnavailable(); }
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
        String requestId = "";
        try {
            SemanticSearchSiResponse response = unary(SEARCH, protoMapper.toProto(query, requestId), SemanticSearchSiResponse.parser());
            return response.getHitsList().stream().map(protoMapper::fromProto).toList();
        } catch (StatusRuntimeException ex) {
            logRpcFailure("semanticSearchSiResources", query.getTenantId(), query.getEnvCode(), "semantic", requestId, ex);
            throw ex;
        }
    }

    @Override
    public DdcSiResourceDescriptor registerManagedResource(DdcSiSelfRegistrationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String requestId = "";
        try {
            RegisterManagedSiResponse response = unary(REGISTER, protoMapper.toProto(request, requestId), RegisterManagedSiResponse.parser());
            return protoMapper.fromProto(response.getResource());
        } catch (StatusRuntimeException ex) {
            logRpcFailure("registerManagedSiResource", request.getTenantId(), request.getEnvCode(),
                    request.getResourceKey(), requestId, ex);
            throw ex;
        }
    }

    private <T extends Message> T unary(String fullMethodName, Message request, Parser<T> parser) {
        if (!binding.target().methods().contains(fullMethodName)) {
            throw Status.PERMISSION_DENIED.withDescription("SI_RPC_METHOD_NOT_APPROVED").asRuntimeException();
        }
        int size = request.getSerializedSize();
        if (size < 0 || size > MAX_WIRE_BYTES) { throw invalidWire(); }
        byte[] payload = new byte[size];
        CodedOutputStream output = CodedOutputStream.newInstance(payload);
        output.useDeterministicSerialization();
        try {
            request.writeTo(output);
            output.checkNoSpaceLeft();
        } catch (IOException impossible) { throw new IllegalStateException("SI_NATIVE_REQUEST_INVALID"); }
        final byte[] response;
        try {
            response = invoker.invokeBlocking(new RpcGenericInvocation(binding.target().bindingId(), SERVICE, GROUP,
                    VERSION, fullMethodName, payload, binding.deadline().toMillis()));
        } catch (EgonRpcException failure) { throw nativeFailure(failure); }
        if (response == null || response.length > MAX_WIRE_BYTES) { throw invalidWire(); }
        try {
            CodedInputStream input = CodedInputStream.newInstance(response);
            input.setSizeLimit(MAX_WIRE_BYTES);
            input.setRecursionLimit(64);
            T result = parser.parseFrom(input);
            input.checkLastTagWas(0);
            if (!input.isAtEnd()) { throw invalidWire(); }
            return result;
        } catch (IOException malformed) { throw invalidWire(); }
    }

    private static StatusRuntimeException invalidWire() {
        return Status.FAILED_PRECONDITION.withDescription("SI_NATIVE_RESPONSE_INVALID").asRuntimeException();
    }

    private static StatusRuntimeException nativeFailure(EgonRpcException failure) {
        if (failure.getCause() instanceof StatusRuntimeException status) {
            String description = status.getStatus().getCode() == Status.Code.FAILED_PRECONDITION
                    && "SI_PINNED_HISTORY_UNAVAILABLE".equals(status.getStatus().getDescription())
                    ? "SI_PINNED_HISTORY_UNAVAILABLE" : "SI_RPC_" + status.getStatus().getCode().name();
            return Status.fromCode(status.getStatus().getCode()).withDescription(description).asRuntimeException();
        }
        return switch (failure.getRpcErrorCode()) {
            case RPC_CANCELLED -> Status.CANCELLED.withDescription("SI_RPC_CANCELLED").asRuntimeException();
            case RPC_DEADLINE_EXCEEDED -> Status.DEADLINE_EXCEEDED.withDescription("SI_RPC_DEADLINE_EXCEEDED").asRuntimeException();
            case RPC_PROVIDER_REJECTED -> Status.PERMISSION_DENIED.withDescription("SI_RPC_PERMISSION_DENIED").asRuntimeException();
            default -> Status.UNAVAILABLE.withDescription("SI_RPC_UNAVAILABLE").asRuntimeException();
        };
    }

    private record NativeBindingVO(RpcServiceTargetVO target, Duration deadline) {
        private NativeBindingVO {
            Objects.requireNonNull(target, "target");
            if (!METHODS.containsAll(target.methods()) || deadline == null || deadline.isNegative()
                    || deadline.isZero() || deadline.compareTo(Duration.ofSeconds(5)) > 0 || deadline.toMillis() < 1) {
                throw new IllegalArgumentException("SI_NATIVE_BINDING_INVALID");
            }
        }
    }

    private void logRpcFailure(
            String operation,
            Long tenantId,
            String envCode,
            String resourceKey,
            String requestId,
            StatusRuntimeException ex) {
        log.warn("Tianshu SI gRPC failed operation={} status={}", operation, ex.getStatus().getCode());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
