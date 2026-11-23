package top.egon.cola.component.outbox.autoconfigure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.util.unit.DataSize;
import top.egon.cola.component.common.core.enums.EgonEnum;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "egon.cola.component.transactional-outbox")
public class TransactionalOutboxProperties {

    private boolean enabled = true;
    private String nodeId;
    private final Annotation annotation = new Annotation();
    private final Storage storage = new Storage();
    private final Polling polling = new Polling();
    private final Delivery delivery = new Delivery();
    private final Retry retry = new Retry();
    private final Payload payload = new Payload();
    private final Http http = new Http();
    private final Rabbitmq rabbitmq = new Rabbitmq();
    private final Cleanup cleanup = new Cleanup();
    private final Shutdown shutdown = new Shutdown();
    @Valid
    private final Dispatch dispatch = new Dispatch();
    @Valid
    private final Coordination coordination = new Coordination();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public Annotation getAnnotation() {
        return annotation;
    }

    public Storage getStorage() {
        return storage;
    }

    public Polling getPolling() {
        return polling;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public Retry getRetry() {
        return retry;
    }

    public Payload getPayload() {
        return payload;
    }

    public Http getHttp() {
        return http;
    }

    public Rabbitmq getRabbitmq() {
        return rabbitmq;
    }

    public Cleanup getCleanup() {
        return cleanup;
    }

    public Shutdown getShutdown() {
        return shutdown;
    }

    public Dispatch getDispatch() {
        return dispatch;
    }

    public Coordination getCoordination() {
        return coordination;
    }

    public enum DispatchMode {
        LEGACY,
        LOCAL,
        COORDINATED
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    @Builder
    public static class Dispatch {
        @NotNull
        @Builder.Default
        private DispatchMode mode = DispatchMode.LEGACY;
        @Builder.Default
        private boolean consumptionEnabled = true;
        @Positive
        @Builder.Default
        private int maxClaimBatch = 1;
        @Positive
        @Builder.Default
        private int maxConcurrentShards = 2;
        @NotNull
        @Builder.Default
        private Duration ackReserve = Duration.ofSeconds(1);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    @Builder
    public static class Coordination {
        @NotNull
        @Builder.Default
        private Duration heartbeatInterval = Duration.ofSeconds(5);
        @NotNull
        @Builder.Default
        private Duration leaseDuration = Duration.ofSeconds(20);
        @NotNull
        @Builder.Default
        private Duration membershipTtl = Duration.ofSeconds(30);
        @NotNull
        @Builder.Default
        private Duration claimTimeout = Duration.ofSeconds(2);
        @NotNull
        @Builder.Default
        private Duration memberRetention = Duration.ofHours(1);
    }

    public static class Annotation {

        private boolean enabled = true;
        private int order = Ordered.HIGHEST_PRECEDENCE + 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getOrder() {
            return order;
        }

        public void setOrder(int order) {
            this.order = order;
        }
    }

    public static class Storage {

        private StorageType type = StorageType.MYBATIS_PLUS;
        private final Jdbc jdbc = new Jdbc();
        private String dataSourceBeanName;
        private String transactionManagerBeanName;
        private boolean validateSchema = true;

        public StorageType getType() {
            return type;
        }

        public void setType(StorageType type) {
            this.type = type;
        }

        public Jdbc getJdbc() {
            return jdbc;
        }

        public String getDataSourceBeanName() {
            return dataSourceBeanName;
        }

        public void setDataSourceBeanName(String dataSourceBeanName) {
            this.dataSourceBeanName = dataSourceBeanName;
        }

        public String getTransactionManagerBeanName() {
            return transactionManagerBeanName;
        }

        public void setTransactionManagerBeanName(String transactionManagerBeanName) {
            this.transactionManagerBeanName = transactionManagerBeanName;
        }

        public boolean isValidateSchema() {
            return validateSchema;
        }

        public void setValidateSchema(boolean validateSchema) {
            this.validateSchema = validateSchema;
        }

        public enum StorageType implements EgonEnum {
            MYBATIS_PLUS(0, "MYBATIS_PLUS"),
            JDBC(1, "JDBC");

            private final int code;
            private final String message;

            StorageType(int code, String message) {
                this.code = code;
                this.message = message;
            }

            @Override public int getCode() { return code; }
            @Override public String getMessage() { return message; }
        }

        public static class Jdbc {
            private String schema = "egon_outbox";

            public String getSchema() {
                return schema;
            }

            public void setSchema(String schema) {
                this.schema = schema;
            }
        }
    }

    public static class Polling {

        private boolean enabled = true;
        private Duration fixedDelay = Duration.ofSeconds(1);
        private double jitter = 0.2;
        private int batchSize = 100;
        private int concurrency = 4;
        private int maxClaimContentionRetries = 8;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getFixedDelay() {
            return fixedDelay;
        }

        public void setFixedDelay(Duration fixedDelay) {
            this.fixedDelay = fixedDelay;
        }

        public double getJitter() {
            return jitter;
        }

        public void setJitter(double jitter) {
            this.jitter = jitter;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public int getMaxClaimContentionRetries() {
            return maxClaimContentionRetries;
        }

        public void setMaxClaimContentionRetries(int maxClaimContentionRetries) {
            this.maxClaimContentionRetries = maxClaimContentionRetries;
        }
    }

    public static class Delivery {

        private Duration timeout = Duration.ofSeconds(10);
        private Duration leaseDuration = Duration.ofSeconds(60);
        private int queueCapacity = 1000;

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Duration getLeaseDuration() {
            return leaseDuration;
        }

        public void setLeaseDuration(Duration leaseDuration) {
            this.leaseDuration = leaseDuration;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }

    public static class Retry {

        private int maxAttempts = 10;
        private Duration initialDelay = Duration.ofSeconds(1);
        private double multiplier = 2.0;
        private Duration maxDelay = Duration.ofMinutes(5);
        private double jitter = 0.2;
        private boolean recoverExhausted = false;
        private Duration exhaustedRetryDelay = Duration.ofMinutes(5);

        public boolean isRecoverExhausted() { return recoverExhausted; }
        public void setRecoverExhausted(boolean recoverExhausted) { this.recoverExhausted = recoverExhausted; }
        public Duration getExhaustedRetryDelay() { return exhaustedRetryDelay; }
        public void setExhaustedRetryDelay(Duration exhaustedRetryDelay) { this.exhaustedRetryDelay = exhaustedRetryDelay; }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialDelay() {
            return initialDelay;
        }

        public void setInitialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public Duration getMaxDelay() {
            return maxDelay;
        }

        public void setMaxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
        }

        public double getJitter() {
            return jitter;
        }

        public void setJitter(double jitter) {
            this.jitter = jitter;
        }
    }

    public static class Payload {

        private DataSize maxBytes = DataSize.ofMegabytes(1);
        private int maxHeaderCount = 64;
        private DataSize maxHeaderBytes = DataSize.ofKilobytes(16);

        public DataSize getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(DataSize maxBytes) {
            this.maxBytes = maxBytes;
        }

        public int getMaxHeaderCount() {
            return maxHeaderCount;
        }

        public void setMaxHeaderCount(int maxHeaderCount) {
            this.maxHeaderCount = maxHeaderCount;
        }

        public DataSize getMaxHeaderBytes() {
            return maxHeaderBytes;
        }

        public void setMaxHeaderBytes(DataSize maxHeaderBytes) {
            this.maxHeaderBytes = maxHeaderBytes;
        }
    }

    public static class Http {

        private boolean enabled;
        private final Map<String, HttpDestination> destinations = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Map<String, HttpDestination> getDestinations() {
            return destinations;
        }
    }

    public static class HttpDestination {

        private URI uri;
        private String method = "POST";
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration readTimeout = Duration.ofSeconds(10);
        private final Map<String, String> fixedHeaders = new LinkedHashMap<>();

        public URI getUri() {
            return uri;
        }

        public void setUri(URI uri) {
            this.uri = uri;
        }

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public Map<String, String> getFixedHeaders() {
            return fixedHeaders;
        }
    }

    public static class Rabbitmq {

        private boolean enabled;
        private Duration confirmTimeout = Duration.ofSeconds(5);
        private final Map<String, RabbitDestination> destinations = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getConfirmTimeout() {
            return confirmTimeout;
        }

        public void setConfirmTimeout(Duration confirmTimeout) {
            this.confirmTimeout = confirmTimeout;
        }

        public Map<String, RabbitDestination> getDestinations() {
            return destinations;
        }
    }

    public static class RabbitDestination {

        private String exchange;
        private String routingKey;
        private boolean mandatory = true;
        private Duration confirmTimeout;
        private final Map<String, String> fixedHeaders = new LinkedHashMap<>();

        public String getExchange() {
            return exchange;
        }

        public void setExchange(String exchange) {
            this.exchange = exchange;
        }

        public String getRoutingKey() {
            return routingKey;
        }

        public void setRoutingKey(String routingKey) {
            this.routingKey = routingKey;
        }

        public boolean isMandatory() {
            return mandatory;
        }

        public void setMandatory(boolean mandatory) {
            this.mandatory = mandatory;
        }

        public Duration getConfirmTimeout() {
            return confirmTimeout;
        }

        public void setConfirmTimeout(Duration confirmTimeout) {
            this.confirmTimeout = confirmTimeout;
        }

        public Map<String, String> getFixedHeaders() {
            return fixedHeaders;
        }
    }

    public static class Cleanup {

        private boolean enabled;
        private Duration successRetention = Duration.ofDays(7);
        private Duration fixedDelay = Duration.ofHours(1);
        private int batchSize = 500;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getSuccessRetention() {
            return successRetention;
        }

        public void setSuccessRetention(Duration successRetention) {
            this.successRetention = successRetention;
        }

        public Duration getFixedDelay() {
            return fixedDelay;
        }

        public void setFixedDelay(Duration fixedDelay) {
            this.fixedDelay = fixedDelay;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }

    public static class Shutdown {

        private Duration gracePeriod = Duration.ofSeconds(30);

        public Duration getGracePeriod() {
            return gracePeriod;
        }

        public void setGracePeriod(Duration gracePeriod) {
            this.gracePeriod = gracePeriod;
        }
    }
}
