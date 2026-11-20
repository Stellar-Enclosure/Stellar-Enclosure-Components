package top.egon.cola.component.rpc.tianshu.contract;

import org.junit.jupiter.api.Test;
import java.net.JarURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;
import static org.assertj.core.api.Assertions.assertThat;

/** The executable classpath may contain one SI definition and no retired transport surface. */
class DdcSiOnlyArtifactTest {
    private static final String ROOT="top/egon/cola/component/rpc/tianshu/";
    private static final String SI=ROOT+"contract/proto/v1/DdcSiResourceGrpcServiceGrpc.class";

    private Set<String> entries() throws Exception {
        var resources=java.util.Collections.list(getClass().getClassLoader().getResources(SI));
        assertThat(resources).hasSize(1);
        var resource=resources.getFirst();
        Set<String> entries=new TreeSet<>();
        if (resource.getProtocol().equals("jar")) {
            var connection=(JarURLConnection) resource.openConnection();
            try(var zip=new ZipFile(connection.getJarFileURL().getPath())) { zip.stream().forEach(entry->entries.add(entry.getName())); }
        } else {
            Path base=Path.of(resource.toURI());
            for(String ignored:SI.split("/")) { base=base.getParent(); }
            final Path classes=base;
            try(var files=Files.walk(classes)) { files.filter(Files::isRegularFile).forEach(file->entries.add(classes.relativize(file).toString().replace('\\','/'))); }
        }
        return entries;
    }
    @Test void currentSiWireAndMapperArePresentExactlyOnce() throws Exception {
        assertThat(entries()).contains(ROOT+"contract/proto/v1/SiDependencyManifestProto.class",ROOT+"mapping/DdcSiProtoMapperImpl.class");
    }
    @Test void artifactHasNoRetiredRuntimeOrSchema() throws Exception {
        Set<String> entries=entries();
        assertThat(entries.stream().filter(name->name.endsWith(".proto")).toList()).containsExactly("tianshu_si_resource.proto");
        assertThat(entries).doesNotContain("META-INF/spring.factories");
        List<String> forbidden=entries.stream().filter(name->name.startsWith(ROOT) && name.endsWith(".class"))
                .filter(name->!name.startsWith(ROOT+"contract/proto/v1/") && !name.startsWith(ROOT+"mapping/DdcSiProtoMapper")
                        && !name.startsWith(ROOT+"client/si/DdcSiCatalogRpcClient")
                        && !name.startsWith(ROOT+"autoconfigure/DdcRpcAutoConfiguration") && !name.startsWith(ROOT+"autoconfigure/DdcRpcProperties")
                        && !name.endsWith("package-info.class")).toList();
        assertThat(forbidden).as("retired physical discovery/config/management/AI/HMAC classes").isEmpty();
        assertThat(entries).doesNotContain(ROOT+"contract/proto/v1/DdcRpcErrorDetail.class",ROOT+"contract/proto/v1/PullConfigRequest.class",
                ROOT+"contract/proto/v1/AiResourceQuery.class",ROOT+"contract/proto/v1/GetServiceInstancesRequest.class",ROOT+"contract/proto/v1/UpsertConfigRequest.class");
    }
    @Test void clientCannotBeConstructedWithAnUnmanagedBlockingStub() throws Exception {
        var constructors=Class.forName("top.egon.cola.component.rpc.tianshu.client.si.DdcSiCatalogRpcClient").getConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(java.util.Arrays.stream(constructors[0].getParameterTypes()).map(Class::getName).toList()).containsExactly(
                "top.egon.cola.component.rpc.consumer.generic.RpcGenericInvoker",
                "top.egon.cola.component.rpc.tianshu.mapping.DdcSiProtoMapper",
                "top.egon.cola.component.rpc.consumer.reference.RpcServiceTargetVO", "java.time.Duration");
    }
}
