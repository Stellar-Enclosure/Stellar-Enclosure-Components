package top.egon.cola.component.common.mybatis.operationlog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class OperationLogPersistenceContractTest {

    @Test
    void eachManifestOwnsOnlyItsPhysicalBucketsAndMatchesTheScriptDigest() throws Exception {
        for (int slot = 0; slot < 2; slot++) {
            String location = "db/egon-operation-log/manifest-slot-" + slot + ".json";
            JsonNode manifest = new ObjectMapper().readTree(resource(location));
            assertThat(manifest.path("family").asText()).isEqualTo("component-operation-log");
            assertThat(manifest.path("scripts").size()).isEqualTo(1);
            JsonNode script = manifest.path("scripts").get(0);
            byte[] bytes = resource(script.path("path").asText());
            assertThat(script.path("sha256").asText())
                    .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            assertThat(new String(bytes, StandardCharsets.UTF_8))
                    .contains("egon_cola_operation_log_t" + slot + "_b0", "egon_cola_operation_log_t" + slot + "_b1",
                            "egon_cola_operation_log_receipt_t" + slot + "_b0",
                            "egon_cola_operation_log_receipt_t" + slot + "_b1")
                    .doesNotContain("egon_cola_operation_log_t" + (1 - slot) + "_b");
        }
    }

    private static byte[] resource(String name) throws Exception {
        try (var in = OperationLogPersistenceContractTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as(name).isNotNull();
            return in.readAllBytes();
        }
    }
}
