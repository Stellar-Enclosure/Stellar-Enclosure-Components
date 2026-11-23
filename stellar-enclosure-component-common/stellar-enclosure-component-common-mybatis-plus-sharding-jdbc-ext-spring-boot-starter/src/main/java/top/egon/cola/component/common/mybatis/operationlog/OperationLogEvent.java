package top.egon.cola.component.common.mybatis.operationlog;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;

/** The version-one fact. Recorded fields are assigned by the component, never by an action projection. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class OperationLogEvent {

    public static final int SCHEMA_VERSION = 1;
    private static final Set<String> HTTP_METHODS = Set.of(
            "GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "TRACE", "CONNECT");

    /** Additional constraints for a fact after trusted context and ID assignment. */
    public interface Recorded {
    }

    @NotNull(groups = Recorded.class)
    private Integer schemaVersion;
    @NotNull(groups = Recorded.class)
    @Positive(groups = Recorded.class)
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long eventId;
    @NotBlank(groups = Recorded.class)
    @Size(max = 64)
    private String operationId;
    @NotNull(groups = Recorded.class)
    @Positive(groups = Recorded.class)
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long tenantId;
    @NotBlank(groups = Recorded.class)
    @Size(max = 128)
    private String sourceService;
    @NotBlank
    @Size(max = 64)
    private String moduleCode;
    @NotBlank
    @Size(max = 96)
    private String actionCode;
    @NotBlank
    @Size(max = 64)
    private String resourceType;
    @Size(max = 128)
    private String resourceId;
    @Size(max = 128)
    private String bizCode;
    @Size(max = 128)
    private String namespaceCode;
    @Size(max = 32)
    private String env;
    @Size(max = 128)
    private String appCode;
    @NotBlank(groups = Recorded.class)
    @Pattern(regexp = "USER|SERVICE|SYSTEM", groups = Recorded.class)
    private String actorType;
    @NotBlank(groups = Recorded.class)
    @Size(max = 512)
    private String actorId;
    @Size(max = 128)
    private String actorName;
    @NotBlank(groups = Recorded.class)
    @Pattern(regexp = "HTTP|RPC|MESSAGE|JOB", groups = Recorded.class)
    private String triggerType;
    @NotNull
    private OperationLogPhaseEnum phase;
    @NotNull
    private OperationLogOutcomeEnum outcome;
    @Size(max = 96)
    private String reasonCode;
    @Size(max = 16)
    private String httpMethod;
    @Size(max = 2048)
    private String requestUrl;
    @Size(max = 512)
    private String routePattern;
    @NotBlank(groups = Recorded.class)
    @Size(max = 1024)
    private String handlerMethod;
    @Size(max = 64)
    private String traceId;
    @Size(max = 128)
    private String requestId;
    @NotNull(groups = Recorded.class)
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant occurredAt;
    @NotNull(groups = Recorded.class)
    @PositiveOrZero
    private Long durationMs;
    @NotNull(groups = Recorded.class)
    private ObjectNode details;

    @JsonIgnore
    @AssertTrue(groups = Recorded.class)
    public boolean isRecordedFormatValid() {
        return Integer.valueOf(SCHEMA_VERSION).equals(schemaVersion)
                && (occurredAt == null || occurredAt.getNano() % 1_000 == 0)
                && handlerMethod != null && handlerMethod.matches("[^#()\\s]+#[^#()\\s]+\\([^#()\\s]*\\)")
                && isHttpContextValid();
    }

    @JsonIgnore
    @AssertTrue
    public boolean isDetailsWithinBudget() {
        return details == null || details.toString().getBytes(StandardCharsets.UTF_8).length <= 4096;
    }

    private boolean isHttpContextValid() {
        if (!"HTTP".equals(triggerType) && httpMethod == null && requestUrl == null && routePattern == null) {
            return true;
        }
        if (!"HTTP".equals(triggerType) && phase != OperationLogPhaseEnum.EXECUTION) {
            return false;
        }
        if (requestUrl == null || httpMethod == null || !HTTP_METHODS.contains(httpMethod)) {
            return false;
        }
        try {
            URI uri = URI.create(requestUrl);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
