package top.egon.cola.component.rpc.provider.metadata;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import top.egon.cola.component.rpc.contract.identity.RpcServiceIdentity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RpcProviderMetadataMergerTest {

    private static final RpcServiceIdentity SERVICE =
            new RpcServiceIdentity("echo.Service", "default", "1.0.0");

    @Test
    void invokesContributorsInSpringOrderAndAllowsIdempotentValues() {
        List<String> calls = new ArrayList<>();
        RpcProviderMetadataContributor later = contributor(
                20,
                calls,
                "later",
                Map.of("catalog.owner", "identity-team")
        );
        RpcProviderMetadataContributor earlier = contributor(
                10,
                calls,
                "earlier",
                Map.of(
                        "catalog.resource-kind", "identity-directory",
                        "catalog.schema-version", "1"
                )
        );
        RpcProviderMetadataMerger merger =
                new RpcProviderMetadataMerger(List.of(later, earlier));

        Map<String, String> metadata = merger.merge(
                SERVICE,
                Map.of(
                        "catalog.schema-version", "1",
                        "custom.key", "value"
                )
        );

        assertThat(calls).containsExactly("earlier", "later");
        assertThat(metadata)
                .containsEntry("catalog.resource-kind", "identity-directory")
                .containsEntry("catalog.owner", "identity-team")
                .containsEntry("catalog.schema-version", "1")
                .containsEntry("custom.key", "value");
        assertThatThrownBy(() -> metadata.put("another", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsDifferentValuesForTheSameKeyAcrossAnySource() {
        RpcProviderMetadataMerger merger = new RpcProviderMetadataMerger(
                List.of(service -> Map.of("catalog.owner", "other-team"))
        );

        assertThatThrownBy(() -> merger.merge(
                SERVICE,
                Map.of("catalog.owner", "identity-team")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflict");
    }

    @Test
    void rejectsFrameworkMetadataFromPropertiesOrContributors() {
        RpcProviderMetadataMerger merger =
                new RpcProviderMetadataMerger(List.of());
        assertThatThrownBy(() -> merger.merge(
                SERVICE,
                Map.of("egon.rpc.transport", "grpc")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");

        RpcProviderMetadataMerger contributed =
                new RpcProviderMetadataMerger(List.of(
                        service -> Map.of(
                                "egon.rpc.runtime-version",
                                "forged"
                        )
                ));
        assertThatThrownBy(() -> contributed.merge(SERVICE, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void preservesOpaqueLogicalMetadataWithoutInterpretingItAsRouting() {
        RpcProviderMetadataMerger merger =
                new RpcProviderMetadataMerger(List.of());

        assertThat(merger.merge(SERVICE, Map.of(
                "catalog.category", "application-defined",
                "tianshu.extension", "adapter-owned",
                "egon.internal.extension", "application-owned"
        ))).containsExactly(
                Map.entry("catalog.category", "application-defined"),
                Map.entry("egon.internal.extension", "application-owned"),
                Map.entry("tianshu.extension", "adapter-owned")
        );
    }

    @Test
    void logicalSnapshotOrderAndContentsDoNotDependOnSourceMapOrder() {
        Map<String, String> configured = new LinkedHashMap<>();
        configured.put("catalog.version", "1");
        configured.put("catalog.owner", "identity-team");
        RpcProviderMetadataMerger merger = new RpcProviderMetadataMerger(List.of());
        Map<String, String> snapshot = merger.merge(SERVICE, configured);
        configured.put("catalog.owner", "changed-after-snapshot");

        assertThat(snapshot.entrySet()).containsExactly(
                Map.entry("catalog.owner", "identity-team"),
                Map.entry("catalog.version", "1"));
        assertThat(snapshot).isEqualTo(merger.merge(SERVICE, Map.of(
                "catalog.owner", "identity-team", "catalog.version", "1")));
    }

    @Test
    void rejectsBlankKeysAndValues() {
        RpcProviderMetadataMerger merger =
                new RpcProviderMetadataMerger(List.of());

        assertThatThrownBy(() -> merger.merge(SERVICE, Map.of(" ", "value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> merger.merge(SERVICE, Map.of("custom", " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void acceptsNullAndEmptyContributorResults() {
        RpcProviderMetadataMerger merger = new RpcProviderMetadataMerger(
                List.of(service -> null, service -> Map.of())
        );

        assertThat(merger.merge(SERVICE, null)).isEmpty();
    }

    private RpcProviderMetadataContributor contributor(
            int order,
            List<String> calls,
            String name,
            Map<String, String> metadata
    ) {
        return new OrderedContributor(order, calls, name, metadata);
    }

    private record OrderedContributor(
            int order,
            List<String> calls,
            String name,
            Map<String, String> metadata
    ) implements RpcProviderMetadataContributor, Ordered {

        @Override
        public Map<String, String> contribute(
                RpcServiceIdentity serviceIdentity
        ) {
            calls.add(name);
            return new LinkedHashMap<>(metadata);
        }

        @Override
        public int getOrder() {
            return order;
        }
    }
}
