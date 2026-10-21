package top.egon.cola.component.rag.reactor;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the new module was added to the delivery surface without disturbing what was already there.
 *
 * <p>The parent module list and the BOM export list are the two places a new component must appear,
 * and the two places where an accidental edit would silently change an existing module's identity.
 */
class RagDeliverySurfaceTest {

    private static final String MODULE = "stellar-enclosure-component-rag-starter";

    private static final Set<String> EXISTING_MODULES = Set.of(
            "stellar-enclosure-components-bom",
            "stellar-enclosure-component-common",
            "stellar-enclosure-component-dynamic-thread-pool",
            "stellar-enclosure-component-rpc",
            "stellar-enclosure-component-rule-engine-starter",
            "stellar-enclosure-component-agent-flow-starter",
            "stellar-enclosure-component-access-guard-starter",
            "stellar-enclosure-component-method-extension",
            "stellar-enclosure-component-transactional-outbox-starter",
            "stellar-enclosure-component-bytecode");

    private static final Path COMPONENTS = Path.of("..");

    @Test
    void parent_pom_lists_the_new_module_alongside_the_existing_ones() throws Exception {
        List<String> modules = childTexts(COMPONENTS.resolve("pom.xml"), "modules", "module");

        assertThat(modules).hasSize(EXISTING_MODULES.size() + 1).contains(MODULE);
        assertThat(modules).containsAll(EXISTING_MODULES);
    }

    @Test
    void bom_exports_the_new_dependency_once() throws Exception {
        List<String> artifacts = exportedArtifacts(COMPONENTS.resolve("stellar-enclosure-components-bom/pom.xml"));

        assertThat(artifacts).contains(MODULE);
        assertThat(artifacts.stream().filter(MODULE::equals)).hasSize(1);
        assertThat(artifacts).doesNotContain("spring-ai-pgvector-store", "spring-ai-tika-document-reader");
    }

    @Test
    void module_declares_the_parent_and_no_provider_dependency() throws Exception {
        String text = Files.readString(Path.of("pom.xml"));

        assertThat(text).contains("<artifactId>stellar-enclosure-components-parent</artifactId>")
                .contains("<relativePath>../pom.xml</relativePath>")
                .doesNotContain("spring-ai-pgvector-store");
    }

    /** Artifact ids of the managed dependencies, which sit one level below the container element. */
    private static List<String> exportedArtifacts(Path pom) throws Exception {
        List<String> artifacts = new ArrayList<>();
        NodeList dependencies = parse(pom).getElementsByTagName("dependency");
        for (int index = 0; index < dependencies.getLength(); index++) {
            NodeList children = dependencies.item(index).getChildNodes();
            for (int child = 0; child < children.getLength(); child++) {
                if (children.item(child) instanceof Element element && "artifactId".equals(element.getNodeName())) {
                    artifacts.add(element.getTextContent().trim());
                }
            }
        }
        return artifacts;
    }

    private static List<String> childTexts(Path pom, String section, String element) throws Exception {
        List<String> values = new ArrayList<>();
        NodeList sections = parse(pom).getElementsByTagName(section);
        for (int index = 0; index < sections.getLength(); index++) {
            NodeList children = sections.item(index).getChildNodes();
            for (int child = 0; child < children.getLength(); child++) {
                if (children.item(child) instanceof Element node && element.equals(node.getNodeName())) {
                    values.add(node.getTextContent().trim());
                }
            }
        }
        return values;
    }

    private static Document parse(Path pom) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
    }
}
