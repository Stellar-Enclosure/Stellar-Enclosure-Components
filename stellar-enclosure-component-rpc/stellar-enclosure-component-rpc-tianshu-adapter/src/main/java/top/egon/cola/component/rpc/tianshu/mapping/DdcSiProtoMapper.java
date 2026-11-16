package top.egon.cola.component.rpc.tianshu.mapping;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import top.egon.cola.component.common.core.converter.BaseConverter;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.ListSiCatalogRequest;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.RegisterManagedSiRequest;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.SemanticSearchSiRequest;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.SiReferenceProto;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.SiResourceProto;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.SiSearchHitProto;
import top.egon.cola.component.tianshu.model.si.DdcSiCatalogQuery;
import top.egon.cola.component.tianshu.model.si.DdcSiKindEnum;
import top.egon.cola.component.tianshu.model.si.DdcSiLifecycleStatusEnum;
import top.egon.cola.component.tianshu.model.si.DdcSiReferenceDescriptor;
import top.egon.cola.component.tianshu.model.si.DdcSiReferenceTargetTypeEnum;
import top.egon.cola.component.tianshu.model.si.DdcSiResourceDescriptor;
import top.egon.cola.component.tianshu.model.si.DdcSiSearchHit;
import top.egon.cola.component.tianshu.model.si.DdcSiSelfRegistrationRequest;
import top.egon.cola.component.tianshu.model.si.DdcSiSemanticSearchQuery;
import top.egon.cola.component.tianshu.model.si.DdcSiSourceTypeEnum;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Bidirectional MapStruct converter between Tianshu SI DTO contracts and protobuf v1 messages.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface DdcSiProtoMapper extends BaseConverter<DdcSiResourceDescriptor, SiResourceProto> {

    @Override
    default SiResourceProto toTarget(DdcSiResourceDescriptor source) {
        return toProto(source);
    }

    @Override
    default DdcSiResourceDescriptor toSource(SiResourceProto target) {
        return fromProto(target);
    }

    default SiResourceProto toProto(DdcSiResourceDescriptor source) {
        if (source == null) {
            return null;
        }
        SiResourceProto.Builder builder = SiResourceProto.newBuilder()
                .setTenantId(source.getTenantId() != null ? source.getTenantId() : 0L)
                .setBizCode(nullToEmpty(source.getBizCode()))
                .setNamespaceCode(nullToEmpty(source.getNamespaceCode()))
                .setEnvCode(nullToEmpty(source.getEnvCode()))
                .setResourceId(source.getResourceId() != null ? source.getResourceId() : 0L)
                .setKind(source.getKind() != null ? source.getKind().getCode() : "")
                .setResourceKey(nullToEmpty(source.getResourceKey()))
                .setDisplayName(nullToEmpty(source.getDisplayName()))
                .setDescription(nullToEmpty(source.getDescription()))
                .setStatus(source.getStatus() != null ? source.getStatus().getCode() : "")
                .setSourceType(source.getSourceType() != null ? source.getSourceType().getCode() : "")
                .setActiveVersionNo(source.getActiveVersionNo() != null ? source.getActiveVersionNo() : 0)
                .setDigestSha256(nullToEmpty(source.getDigestSha256()))
                .setSpecJson(nullToEmpty(source.getSpecJson()))
                .setPublishedEpochMillis(instantToEpochMillis(source.getPublishedAt()))
                .setUpdatedEpochMillis(instantToEpochMillis(source.getUpdatedAt()));
        if (source.getLabels() != null && !source.getLabels().isEmpty()) {
            builder.putAllLabels(source.getLabels());
        }
        if (source.getReferences() != null) {
            for (DdcSiReferenceDescriptor ref : source.getReferences()) {
                if (ref != null) {
                    builder.addReferences(toProto(ref));
                }
            }
        }
        return builder.build();
    }

    @Named("protoToDescriptor")
    @BeanMapping(ignoreByDefault = false)
    @Mapping(target = "resourceId", source = "resourceId", qualifiedByName = "positiveLongOrNull")
    @Mapping(target = "kind", source = "kind", qualifiedByName = "toKind")
    @Mapping(target = "description", source = "description", qualifiedByName = "emptyToNull")
    @Mapping(target = "status", source = "status", qualifiedByName = "toStatus")
    @Mapping(target = "sourceType", source = "sourceType", qualifiedByName = "toSourceType")
    @Mapping(target = "activeVersionNo", source = "activeVersionNo", qualifiedByName = "positiveIntOrNull")
    @Mapping(target = "digestSha256", source = "digestSha256", qualifiedByName = "emptyToNull")
    @Mapping(target = "labels", source = "labelsMap", qualifiedByName = "copyLabels")
    @Mapping(target = "references", source = "referencesList")
    @Mapping(target = "specJson", source = "specJson", qualifiedByName = "emptyToNull")
    @Mapping(target = "publishedAt", source = "publishedEpochMillis", qualifiedByName = "epochMillisToInstant")
    @Mapping(target = "updatedAt", source = "updatedEpochMillis", qualifiedByName = "epochMillisToInstant")
    DdcSiResourceDescriptor fromProto(SiResourceProto proto);

    default SiReferenceProto toProto(DdcSiReferenceDescriptor source) {
        if (source == null) {
            return null;
        }
        return SiReferenceProto.newBuilder()
                .setTargetType(source.getTargetType() != null ? source.getTargetType().getCode() : "")
                .setTargetKey(nullToEmpty(source.getTargetKey()))
                .setPinnedVersionNo(source.getPinnedVersionNo() != null ? source.getPinnedVersionNo() : 0)
                .setRequired(source.isRequired())
                .setRoleHint(nullToEmpty(source.getRoleHint()))
                .build();
    }

    @Mapping(target = "targetType", source = "targetType", qualifiedByName = "toTargetType")
    @Mapping(target = "pinnedVersionNo", source = "pinnedVersionNo", qualifiedByName = "positiveIntOrNull")
    @Mapping(target = "roleHint", source = "roleHint", qualifiedByName = "emptyToNull")
    DdcSiReferenceDescriptor fromProto(SiReferenceProto proto);

    default SiSearchHitProto toProto(DdcSiSearchHit source) {
        if (source == null) {
            return null;
        }
        SiSearchHitProto.Builder builder = SiSearchHitProto.newBuilder()
                .setHybridScore(source.getHybridScore())
                .setVectorScore(source.getVectorScore())
                .setFtsScore(source.getFtsScore())
                .setMatchedChunkText(nullToEmpty(source.getMatchedChunkText()))
                .setDegradedToFtsOnly(source.isDegradedToFtsOnly())
                .setRank(source.getRank());
        if (source.getResource() != null) {
            builder.setResource(toProto(source.getResource()));
        }
        return builder.build();
    }

    @Mapping(target = "resource", source = "resource", qualifiedByName = "protoToDescriptor")
    @Mapping(target = "matchedChunkText", source = "matchedChunkText", qualifiedByName = "emptyToNull")
    DdcSiSearchHit fromProto(SiSearchHitProto proto);

    default ListSiCatalogRequest toProto(DdcSiCatalogQuery query, String requestId) {
        if (query == null) {
            return null;
        }
        ListSiCatalogRequest.Builder builder = ListSiCatalogRequest.newBuilder()
                .setTenantId(query.getTenantId() != null ? query.getTenantId() : 0L)
                .setBizCode(nullToEmpty(query.getBizCode()))
                .setNamespaceCode(nullToEmpty(query.getNamespaceCode()))
                .setEnvCode(nullToEmpty(query.getEnvCode()))
                .setAppCode(nullToEmpty(query.getAppCode()))
                .setKind(query.getKind() != null ? query.getKind().getCode() : "")
                .setIncludeDeprecated(query.isIncludeDeprecated())
                .setKeyword(nullToEmpty(query.getKeyword()))
                .setPageNo(query.getPageNo())
                .setPageSize(query.getPageSize())
                .setRequestId(nullToEmpty(requestId));
        if (query.getResourceKeys() != null && !query.getResourceKeys().isEmpty()) {
            builder.addAllResourceKeys(query.getResourceKeys());
        }
        if (query.getLabels() != null && !query.getLabels().isEmpty()) {
            builder.putAllLabels(query.getLabels());
        }
        return builder.build();
    }

    @Mapping(target = "appCode", source = "appCode", qualifiedByName = "emptyToNull")
    @Mapping(target = "kind", source = "kind", qualifiedByName = "toOptionalKind")
    @Mapping(target = "resourceKeys", source = "resourceKeysList", qualifiedByName = "copyStrings")
    @Mapping(target = "labels", source = "labelsMap", qualifiedByName = "copyLabels")
    @Mapping(target = "keyword", source = "keyword", qualifiedByName = "emptyToNull")
    DdcSiCatalogQuery fromProto(ListSiCatalogRequest proto);

    default SemanticSearchSiRequest toProto(DdcSiSemanticSearchQuery query, String requestId) {
        if (query == null) {
            return null;
        }
        SemanticSearchSiRequest.Builder builder = SemanticSearchSiRequest.newBuilder()
                .setTenantId(query.getTenantId() != null ? query.getTenantId() : 0L)
                .setBizCode(nullToEmpty(query.getBizCode()))
                .setNamespaceCode(nullToEmpty(query.getNamespaceCode()))
                .setEnvCode(nullToEmpty(query.getEnvCode()))
                .setKind(query.getKind() != null ? query.getKind().getCode() : "")
                .setQueryText(nullToEmpty(query.getQueryText()))
                .setTopK(query.getTopK())
                .setIncludeDeprecated(query.isIncludeDeprecated())
                .setRequestId(nullToEmpty(requestId));
        if (query.getLabels() != null && !query.getLabels().isEmpty()) {
            builder.putAllLabels(query.getLabels());
        }
        return builder.build();
    }

    @Mapping(target = "kind", source = "kind", qualifiedByName = "toOptionalKind")
    @Mapping(target = "labels", source = "labelsMap", qualifiedByName = "copyLabels")
    DdcSiSemanticSearchQuery fromProto(SemanticSearchSiRequest proto);

    default RegisterManagedSiRequest toProto(DdcSiSelfRegistrationRequest req, String requestId) {
        if (req == null) {
            return null;
        }
        RegisterManagedSiRequest.Builder builder = RegisterManagedSiRequest.newBuilder()
                .setTenantId(req.getTenantId() != null ? req.getTenantId() : 0L)
                .setBizCode(nullToEmpty(req.getBizCode()))
                .setNamespaceCode(nullToEmpty(req.getNamespaceCode()))
                .setEnvCode(nullToEmpty(req.getEnvCode()))
                .setServiceName(nullToEmpty(req.getServiceName()))
                .setInstanceId(nullToEmpty(req.getInstanceId()))
                .setKind(req.getKind() != null ? req.getKind().getCode() : "")
                .setResourceKey(nullToEmpty(req.getResourceKey()))
                .setDisplayName(nullToEmpty(req.getDisplayName()))
                .setDescription(nullToEmpty(req.getDescription()))
                .setSpecJson(nullToEmpty(req.getSpecJson()))
                .setSpecHash(nullToEmpty(req.getSpecHash()))
                .setAutoPublishIfNew(req.isAutoPublishIfNew())
                .setRequestId(nullToEmpty(requestId));
        if (req.getLabels() != null && !req.getLabels().isEmpty()) {
            builder.putAllLabels(req.getLabels());
        }
        if (req.getReferences() != null) {
            for (DdcSiReferenceDescriptor ref : req.getReferences()) {
                if (ref != null) {
                    builder.addReferences(toProto(ref));
                }
            }
        }
        return builder.build();
    }

    @Mapping(target = "kind", source = "kind", qualifiedByName = "toKind")
    @Mapping(target = "description", source = "description", qualifiedByName = "emptyToNull")
    @Mapping(target = "specHash", source = "specHash", qualifiedByName = "emptyToNull")
    @Mapping(target = "labels", source = "labelsMap", qualifiedByName = "copyLabels")
    @Mapping(target = "references", source = "referencesList")
    DdcSiSelfRegistrationRequest fromProto(RegisterManagedSiRequest proto);

    @Named("instantToEpochMillis")
    default long instantToEpochMillis(Instant value) {
        return value == null ? 0L : value.toEpochMilli();
    }

    @Named("epochMillisToInstant")
    default Instant epochMillisToInstant(long millis) {
        return millis <= 0L ? null : Instant.ofEpochMilli(millis);
    }

    @Named("positiveIntOrNull")
    default Integer positiveIntOrNull(int value) {
        return value <= 0 ? null : value;
    }

    @Named("positiveLongOrNull")
    default Long positiveLongOrNull(long value) {
        return value <= 0L ? null : value;
    }

    @Named("emptyToNull")
    default String emptyToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }

    @Named("toKind")
    default DdcSiKindEnum toKind(String code) {
        return DdcSiKindEnum.fromCode(code);
    }

    @Named("toOptionalKind")
    default DdcSiKindEnum toOptionalKind(String code) {
        return (code == null || code.isEmpty()) ? null : DdcSiKindEnum.fromCode(code);
    }

    @Named("toStatus")
    default DdcSiLifecycleStatusEnum toStatus(String code) {
        return DdcSiLifecycleStatusEnum.fromCode(code);
    }

    @Named("toSourceType")
    default DdcSiSourceTypeEnum toSourceType(String code) {
        return DdcSiSourceTypeEnum.fromCode(code);
    }

    @Named("toTargetType")
    default DdcSiReferenceTargetTypeEnum toTargetType(String code) {
        return DdcSiReferenceTargetTypeEnum.fromCode(code);
    }

    @Named("copyLabels")
    default Map<String, String> copyLabels(Map<String, String> source) {
        return (source == null || source.isEmpty()) ? Collections.emptyMap() : Map.copyOf(source);
    }

    @Named("copyStrings")
    default List<String> copyStrings(List<String> source) {
        return (source == null || source.isEmpty()) ? Collections.emptyList() : List.copyOf(source);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
