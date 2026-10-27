package top.egon.cola.component.codegen.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import top.egon.cola.component.codegen.validation.CodegenGroups;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * External generator request. Nested types keep one lifecycle instead of one file per field.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder(alphabetic = true)
public class CodegenConfigBO {

    @NotNull(groups = CodegenGroups.Plan.class)
    private Integer configVersion;

    @NotNull(groups = CodegenGroups.Plan.class)
    @JsonProperty("projectType")
    private CodegenProfileEnum profile;

    @NotBlank(groups = CodegenGroups.Plan.class)
    private String basePackage;

    @NotBlank(groups = CodegenGroups.Plan.class)
    private String domain;

    @NotBlank(groups = {CodegenGroups.Plan.class, CodegenGroups.Apply.class})
    private String outputRoot;

    @NotNull(groups = CodegenGroups.Plan.class)
    @Valid
    private InputBO input;

    @JsonProperty("modulePaths")
    @Builder.Default
    private Map<String, String> roots = new LinkedHashMap<>();

    @JsonProperty("logicalTables")
    @Builder.Default
    private List<String> tables = new ArrayList<>();

    @NotEmpty(groups = CodegenGroups.Plan.class)
    @Builder.Default
    private List<String> artifacts = new ArrayList<>();

    @JsonProperty("existingTypeMappings")
    @Builder.Default
    private Map<String, String> existingTypes = new LinkedHashMap<>();

    @Valid
    private FieldPoliciesBO fieldPolicies;

    @Builder.Default
    private Map<String, FieldPoliciesBO> tableFieldPolicies = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, DomainMethodsBO> domainMethods = new LinkedHashMap<>();

    @Valid
    private ApiContractBO apiContract;

    @Valid
    private EventsBO events;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    @Builder
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder(alphabetic = true)
    public static class InputBO {

        @NotBlank(groups = CodegenGroups.Plan.class)
        private String mode;

        private String resourceRoot;

        @Builder.Default
        private List<String> schemaFiles = new ArrayList<>();

        private String manifest;

        InputBO snapshot() {
            return InputBO.builder()
                    .mode(mode)
                    .resourceRoot(resourceRoot)
                    .schemaFiles(copyList(schemaFiles))
                    .manifest(manifest)
                    .build();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    @Builder
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder(alphabetic = true)
    public static class FieldPoliciesBO {

        private List<String> create;

        private List<String> update;

        private List<String> result;

        private List<String> filter;

        private List<String> sort;

        FieldPoliciesBO snapshot() {
            return FieldPoliciesBO.builder()
                    .create(copyList(create))
                    .update(copyList(update))
                    .result(copyList(result))
                    .filter(copyList(filter))
                    .sort(copyList(sort))
                    .build();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    @Builder
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder(alphabetic = true)
    public static class ApiContractBO {

        private String existingErrorMapper;

        private String contextSymbol;

        private String basePath;

        private String transportMode;

        private String transportConfigType;

        private String transportBean;

        @Builder.Default
        private List<String> permissionReferences = new ArrayList<>();

        @Builder.Default
        private List<String> localPermissions = new ArrayList<>();

        @Builder.Default
        private Map<String, ApiTableBO> tables = new LinkedHashMap<>();

        ApiContractBO snapshot() {
            return ApiContractBO.builder()
                    .existingErrorMapper(existingErrorMapper)
                    .contextSymbol(contextSymbol)
                    .basePath(basePath)
                    .transportMode(transportMode)
                    .transportConfigType(transportConfigType)
                    .transportBean(transportBean)
                    .permissionReferences(copyList(permissionReferences))
                    .localPermissions(copyList(localPermissions))
                    .tables(copyApiTables(tables))
                    .build();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    @Builder
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder(alphabetic = true)
    public static class ApiTableBO {
        private String basePath;
        @Builder.Default
        private Map<String, ApiOperationBO> operations = new LinkedHashMap<>();
        @Builder.Default
        private Map<String, ApiFieldBO> fields = new LinkedHashMap<>();

        ApiTableBO snapshot() {
            Map<String, ApiOperationBO> operationCopy = new LinkedHashMap<>();
            if (operations != null) operations.forEach((key, value) -> operationCopy.put(key, value == null ? null : value.snapshot()));
            Map<String, ApiFieldBO> fieldCopy = new LinkedHashMap<>();
            if (fields != null) fields.forEach((key, value) -> fieldCopy.put(key, value == null ? null : value.snapshot()));
            return ApiTableBO.builder().basePath(basePath).operations(operationCopy).fields(fieldCopy).build();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    @Builder
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder(alphabetic = true)
    public static class ApiOperationBO {
        private String scope;
        private String permission;
        private String resourceCode;
        private String labelPage;
        private String labelKey;

        ApiOperationBO snapshot() {
            return ApiOperationBO.builder().scope(scope).permission(permission).resourceCode(resourceCode)
                    .labelPage(labelPage).labelKey(labelKey).build();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    @Builder
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder(alphabetic = true)
    public static class ApiFieldBO {
        private String permission;
        private String labelPage;
        private String labelKey;
        private String resultKey;
        private String createKey;
        private String updateKey;
        private String createPermission;
        private String updatePermission;

        ApiFieldBO snapshot() {
            return ApiFieldBO.builder().permission(permission)
                    .labelPage(labelPage).labelKey(labelKey).resultKey(resultKey).createKey(createKey).updateKey(updateKey)
                    .createPermission(createPermission).updatePermission(updatePermission).build();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    @Builder
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder(alphabetic = true)
    public static class DomainMethodsBO {
        private String create;
        private String update;
        private String delete;
        private String detail;
        private String query;

        DomainMethodsBO snapshot() {
            return DomainMethodsBO.builder().create(create).update(update).delete(delete).detail(detail).query(query).build();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    @Builder
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder(alphabetic = true)
    public static class EventsBO {

        private boolean enabled;

        @Builder.Default
        private List<EventDefinitionBO> definitions = new ArrayList<>();

        EventsBO snapshot() {
            List<EventDefinitionBO> copied = new ArrayList<>();
            if (definitions != null) {
                for (EventDefinitionBO definition : definitions) {
                    copied.add(definition == null ? null : definition.snapshot());
                }
            }
            return EventsBO.builder().enabled(enabled).definitions(copied).build();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    @Builder
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder(alphabetic = true)
    public static class EventDefinitionBO {

        private String table;

        private String name;

        private String action;

        private String schemaVersion;

        private String idempotencyType;

        private String idempotencyBean;

        private String idempotencyMethod;

        private Map<String, String> fieldMappings;

        private String handlerType;

        private String handlerBean;

        private String handlerMethod;

        @Builder.Default
        private List<EventFieldBO> fields = new ArrayList<>();

        EventDefinitionBO snapshot() {
            return EventDefinitionBO.builder().table(table).name(name).action(action).schemaVersion(schemaVersion)
                    .idempotencyType(idempotencyType).idempotencyBean(idempotencyBean)
                    .idempotencyMethod(idempotencyMethod).fieldMappings(copyMap(fieldMappings))
                    .handlerType(handlerType).handlerBean(handlerBean).handlerMethod(handlerMethod)
                    .fields(copyEventFields(fields)).build();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    @Builder
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder(alphabetic = true)
    public static class EventFieldBO {
        private String name;
        private String javaType;
        private boolean required;
        private Integer maxLength;

        EventFieldBO snapshot() {
            return EventFieldBO.builder().name(name).javaType(javaType).required(required).maxLength(maxLength).build();
        }
    }

    /**
     * Returns a structural copy. Callers cannot mutate the original lists through the copy.
     *
     * @return detached config
     */
    public CodegenConfigBO snapshot() {
        return CodegenConfigBO.builder()
                .configVersion(configVersion)
                .profile(profile)
                .basePackage(basePackage)
                .domain(domain)
                .outputRoot(outputRoot)
                .input(input == null ? null : input.snapshot())
                .roots(copyMap(roots))
                .tables(copyList(tables))
                .artifacts(copyList(artifacts))
                .existingTypes(copyMap(existingTypes))
                .fieldPolicies(fieldPolicies == null ? null : fieldPolicies.snapshot())
                .tableFieldPolicies(copyPolicies(tableFieldPolicies))
                .domainMethods(copyDomainMethods(domainMethods))
                .apiContract(apiContract == null ? null : apiContract.snapshot())
                .events(events == null ? null : events.snapshot())
                .build();
    }

    private static Map<String, FieldPoliciesBO> copyPolicies(Map<String, FieldPoliciesBO> values) {
        Map<String, FieldPoliciesBO> copy = new LinkedHashMap<>();
        if (values != null) values.forEach((key, value) -> copy.put(key, value == null ? null : value.snapshot()));
        return copy;
    }

    private static Map<String, DomainMethodsBO> copyDomainMethods(Map<String, DomainMethodsBO> values) {
        Map<String, DomainMethodsBO> copy = new LinkedHashMap<>();
        if (values != null) values.forEach((key, value) -> copy.put(key, value == null ? null : value.snapshot()));
        return copy;
    }

    private static Map<String, ApiTableBO> copyApiTables(Map<String, ApiTableBO> values) {
        Map<String, ApiTableBO> copy = new LinkedHashMap<>();
        if (values != null) values.forEach((key, value) -> copy.put(key, value == null ? null : value.snapshot()));
        return copy;
    }

    private static List<EventFieldBO> copyEventFields(List<EventFieldBO> values) {
        List<EventFieldBO> copy = new ArrayList<>();
        if (values != null) values.forEach(value -> copy.add(value == null ? null : value.snapshot()));
        return copy;
    }

    static <T> List<T> copyList(List<T> source) {
        return source == null ? new ArrayList<>() : new ArrayList<>(source);
    }

    static <K, V> Map<K, V> copyMap(Map<K, V> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }
}
