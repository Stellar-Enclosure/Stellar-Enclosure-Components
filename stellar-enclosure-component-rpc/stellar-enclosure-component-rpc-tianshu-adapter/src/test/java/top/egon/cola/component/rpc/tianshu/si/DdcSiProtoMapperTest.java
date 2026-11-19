package top.egon.cola.component.rpc.tianshu.si;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.ListSiCatalogRequest;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.RegisterManagedSiRequest;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.SemanticSearchSiRequest;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.SiResourceProto;
import top.egon.cola.component.rpc.tianshu.contract.proto.v1.SiSearchHitProto;
import top.egon.cola.component.rpc.tianshu.mapping.DdcSiProtoMapper;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DdcSiProtoMapperTest {

    private final DdcSiProtoMapper mapper = Mappers.getMapper(DdcSiProtoMapper.class);

    @Test
    void roundTripsResourceSearchHitCatalogQueryAndRegistration() {
        Instant publishedAt = Instant.ofEpochMilli(1_763_280_930_123L);
        Instant updatedAt = Instant.ofEpochMilli(1_763_281_200_456L);

        DdcSiReferenceDescriptor ref = DdcSiReferenceDescriptor.builder()
                .targetType(DdcSiReferenceTargetTypeEnum.MCP)
                .targetKey("mcp.weather")
                .pinnedVersionNo(4)
                .required(true)
                .roleHint("weather-tool")
                .build();

        DdcSiResourceDescriptor descriptor = DdcSiResourceDescriptor.builder()
                .tenantId(1001L)
                .bizCode("xingyuan")
                .namespaceCode("default")
                .envCode("prod")
                .resourceId(501L)
                .kind(DdcSiKindEnum.AGENT)
                .resourceKey("agent.planner")
                .displayName("Planner Agent")
                .description("Plans atomic tasks")
                .status(DdcSiLifecycleStatusEnum.PUBLISHED)
                .sourceType(DdcSiSourceTypeEnum.CONSOLE)
                .activeVersionNo(2)
                .digestSha256("b".repeat(64))
                .labels(Map.of("team", "core"))
                .references(List.of(ref))
                .specJson("{\"model\":{\"modelId\":\"gpt-6-luna\"}}")
                .publishedAt(publishedAt)
                .updatedAt(updatedAt)
                .build();

        SiResourceProto proto = mapper.toProto(descriptor);
        assertThat(proto.getPublishedEpochMillis()).isEqualTo(publishedAt.toEpochMilli());
        assertThat(proto.getUpdatedEpochMillis()).isEqualTo(updatedAt.toEpochMilli());

        DdcSiResourceDescriptor restored = mapper.fromProto(proto);
        assertThat(restored).isEqualTo(descriptor);

        DdcSiSearchHit hit = DdcSiSearchHit.builder()
                .resource(descriptor)
                .hybridScore(0.92)
                .vectorScore(0.95)
                .ftsScore(0.81)
                .matchedChunkText("Plans atomic tasks")
                .degradedToFtsOnly(true)
                .rank(1)
                .build();
        SiSearchHitProto hitProto = mapper.toProto(hit);
        assertThat(mapper.fromProto(hitProto)).isEqualTo(hit);

        DdcSiCatalogQuery catalogQuery = DdcSiCatalogQuery.builder()
                .tenantId(1001L)
                .bizCode("xingyuan")
                .namespaceCode("default")
                .envCode("prod")
                .appCode("tianji-agent-server")
                .kind(DdcSiKindEnum.AGENT)
                .resourceKeys(List.of("agent.planner"))
                .labels(Map.of("team", "core"))
                .includeDeprecated(false)
                .keyword("planner")
                .pageNo(2)
                .pageSize(25)
                .build();
        ListSiCatalogRequest catalogProto = mapper.toProto(catalogQuery, "req-1");
        assertThat(mapper.fromProto(catalogProto)).isEqualTo(catalogQuery);

        DdcSiSemanticSearchQuery searchQuery = DdcSiSemanticSearchQuery.builder()
                .tenantId(1001L)
                .bizCode("xingyuan")
                .namespaceCode("default")
                .envCode("prod")
                .kind(DdcSiKindEnum.MCP)
                .queryText("find github pull request tool")
                .labels(Map.of("tier", "prod"))
                .topK(8)
                .includeDeprecated(true)
                .build();
        SemanticSearchSiRequest searchProto = mapper.toProto(searchQuery, "req-2");
        assertThat(mapper.fromProto(searchProto)).isEqualTo(searchQuery);

        DdcSiSelfRegistrationRequest regRequest = DdcSiSelfRegistrationRequest.builder()
                .tenantId(1001L)
                .bizCode("xingyuan")
                .namespaceCode("default")
                .envCode("dev")
                .serviceName("tianji-agent-server")
                .instanceId("inst-9")
                .kind(DdcSiKindEnum.AGENT)
                .resourceKey("agent.local")
                .displayName("Local Agent")
                .description("Built-in")
                .specJson("{\"modelId\":\"gpt-6-luna\"}")
                .specHash("c".repeat(64))
                .labels(Map.of("auto", "true"))
                .references(List.of(ref))
                .autoPublishIfNew(false)
                .build();
        RegisterManagedSiRequest regProto = mapper.toProto(regRequest, "req-3");
        assertThat(mapper.fromProto(regProto)).isEqualTo(regRequest);
    }

    @Test
    void handlesUnsetTimestampsOptionalIntegersAndInvalidEnums() {
        SiResourceProto minimal = SiResourceProto.newBuilder()
                .setTenantId(1L)
                .setBizCode("b")
                .setNamespaceCode("ns")
                .setEnvCode("dev")
                .setKind("AGENT")
                .setResourceKey("a.1")
                .setDisplayName("A1")
                .setStatus("PUBLISHED")
                .setSourceType("CONSOLE")
                .setPublishedEpochMillis(0L)
                .setUpdatedEpochMillis(0L)
                .setActiveVersionNo(0)
                .build();

        DdcSiResourceDescriptor dto = mapper.fromProto(minimal);
        assertThat(dto.getPublishedAt()).isNull();
        assertThat(dto.getUpdatedAt()).isNull();
        assertThat(dto.getActiveVersionNo()).isNull();
        assertThat(dto.getOwnerAppCode()).isNull();
        assertThat(dto.getResolvedVersionId()).isNull();
        assertThat(dto.getResolvedVersionNo()).isNull();
        assertThat(dto.getDependencyManifest()).isNull();
        assertThatThrownBy(() -> mapper.fromProto(minimal.toBuilder().setOwnerAppCode("").build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.fromProto(minimal.toBuilder().setResolvedVersionId(0).build()))
                .isInstanceOf(IllegalArgumentException.class);

        SiResourceProto badEnum = minimal.toBuilder().setKind("INVALID_KIND").build();
        assertThatThrownBy(() -> mapper.fromProto(badEnum))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
