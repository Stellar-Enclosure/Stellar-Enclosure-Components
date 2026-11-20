package top.egon.cola.component.common.id.autoconfigure;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.ReadMode;
import org.redisson.config.SslVerificationMode;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Owns the dedicated connection to the registered Redis authority for one common-ID process. */
final class RedisLeaseAuthority {
    static final String CONFIG_FILE = "STELLAR_SHARED_ID_AUTHORITY_CONFIG_FILE";
    static final String REGISTERED_NODES = "STELLAR_SHARED_ID_REDIS_ENDPOINTS";

    private static final int MAX_CONFIG_BYTES = 32 * 1024;
    private static final int MAX_CA_BYTES = 128 * 1024;
    private static final Set<String> ROOT_KEYS = Set.of("username", "password", "clusterServersConfig");
    private static final Set<String> CLUSTER_KEYS = Set.of("nodeAddresses", "connectTimeout", "timeout", "readMode", "retryAttempts");

    private RedisLeaseAuthority() {
    }

    static RedissonClient open(String file, String registeredEndpoints) {
        Config config = configuration(file, registeredEndpoints);
        try {
            return Redisson.create(config);
        } catch (RuntimeException unavailable) {
            throw new IllegalStateException("Managed Redis ID authority is unavailable");
        }
    }

    private static Config configuration(String file, String registeredEndpoints) {
        try {
            if (file == null || registeredEndpoints == null) {
                throw invalid();
            }
            Path reference = Path.of(file);
            if (!reference.isAbsolute() || !reference.normalize().equals(reference)
                    || !reference.getFileName().toString().equals("client.yml")) {
                throw invalid();
            }
            // Kubernetes projects both files beneath the same Secret generation directory.
            Path configFile = reference.toRealPath();
            Path caFile = configFile.resolveSibling("ca.crt");
            Map<?, ?> root = configurationMap(configFile);
            if (!root.keySet().equals(ROOT_KEYS) || !(root.get("clusterServersConfig") instanceof Map<?, ?> cluster)
                    || !CLUSTER_KEYS.containsAll(cluster.keySet())) {
                throw invalid();
            }
            List<String> nodes = addresses(cluster.get("nodeAddresses"));
            List<String> approved = addresses(List.of(registeredEndpoints.split(",", -1)));
            if (!new HashSet<>(nodes).equals(new HashSet<>(approved))
                    || cluster.containsKey("readMode") && !"MASTER".equals(cluster.get("readMode"))
                    || cluster.containsKey("retryAttempts") && !Integer.valueOf(0).equals(cluster.get("retryAttempts"))) {
                throw invalid();
            }
            String username = string(root.get("username"));
            String password = string(root.get("password"));
            if (!username.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") || password.length() > 8192
                    || password.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f)) {
                throw invalid();
            }
            Config config = new Config();
            config.useClusterServers().addNodeAddress(nodes.toArray(String[]::new))
                    .setUsername(username).setPassword(password).setReadMode(ReadMode.MASTER)
                    .setConnectTimeout(timeout(cluster, "connectTimeout")).setTimeout(timeout(cluster, "timeout"))
                    .setRetryAttempts(0).setSslVerificationMode(SslVerificationMode.STRICT)
                    .setSslEnableEndpointIdentification(true).setSslTrustManagerFactory(trustManager(caFile));
            return config;
        } catch (IOException | GeneralSecurityException | RuntimeException invalid) {
            // A parser or TLS setup error may contain sensitive configuration. Report only the boundary.
            throw invalid();
        }
    }

    private static Map<?, ?> configurationMap(Path path) throws IOException {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(8);
        options.setCodePointLimit(MAX_CONFIG_BYTES);
        try (var source = new ByteArrayInputStream(readFile(path, MAX_CONFIG_BYTES))) {
            Object configuration = new Yaml(new SafeConstructor(options)).load(source);
            if (configuration instanceof Map<?, ?> fields) {
                return fields;
            }
            throw invalid();
        }
    }

    private static List<String> addresses(Object value) {
        if (!(value instanceof List<?> entries) || entries.isEmpty() || entries.size() > 16) {
            throw invalid();
        }
        List<String> nodes = new ArrayList<>(entries.size());
        Set<String> unique = new HashSet<>();
        for (Object entry : entries) {
            URI uri = URI.create(string(entry));
            if (!"rediss".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
                    || uri.getRawUserInfo() != null || !uri.getRawPath().isEmpty() || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw invalid();
            }
            String address = "rediss://" + uri.getHost().toLowerCase(Locale.ROOT) + ':' + uri.getPort();
            if (!unique.add(address)) {
                throw invalid();
            }
            nodes.add(address);
        }
        return nodes;
    }

    private static TrustManagerFactory trustManager(Path caFile) throws IOException, GeneralSecurityException {
        var certificates = CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(readFile(caFile, MAX_CA_BYTES)));
        if (certificates.isEmpty()) {
            throw invalid();
        }
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        int index = 0;
        for (var certificate : certificates) {
            if (!(certificate instanceof X509Certificate x509) || x509.getBasicConstraints() < 0) {
                throw invalid();
            }
            x509.checkValidity();
            trust.setCertificateEntry("redis-ca-" + index++, x509);
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        return factory;
    }

    private static byte[] readFile(Path path, int limit) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw invalid();
        }
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length == 0 || bytes.length > limit) {
                throw invalid();
            }
            return bytes;
        }
    }

    private static String string(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalid();
        }
        return text;
    }

    private static int timeout(Map<?, ?> settings, String name) {
        if (!settings.containsKey(name)) {
            return 3000;
        }
        if (settings.get(name) instanceof Integer value && value >= 100 && value <= 10_000) {
            return value;
        }
        throw invalid();
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("Managed Redis ID authority configuration is invalid");
    }
}
