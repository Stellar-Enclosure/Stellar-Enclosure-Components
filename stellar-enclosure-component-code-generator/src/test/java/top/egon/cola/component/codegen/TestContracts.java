package top.egon.cola.component.codegen;

import com.fasterxml.jackson.databind.node.ObjectNode;
import top.egon.cola.component.codegen.model.CodegenConfigBO;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit fixture contracts for isolated generator tests. */
final class TestContracts {

    private TestContracts() {
    }

    static CodegenConfigBO.DomainMethodsBO methods(String stem) {
        return CodegenConfigBO.DomainMethodsBO.builder().create("create" + stem).update("change" + stem)
                .delete("remove" + stem).detail("find" + stem).query("page" + stem).build();
    }

    static CodegenConfigBO.ApiTableBO apiTable(String table, String path, String... businessFields) {
        Map<String, CodegenConfigBO.ApiOperationBO> operations = new LinkedHashMap<>();
        for (String operation : new String[]{"create", "update", "delete", "detail", "page"}) {
            operations.put(operation, CodegenConfigBO.ApiOperationBO.builder().scope("fixture:" + table + ":" + operation)
                    .permission("fixture:" + table + ":" + operation).resourceCode("fixture.api." + table + "." + operation)
                    .labelPage("fixture." + table).labelKey("api." + operation).build());
        }
        Map<String, CodegenConfigBO.ApiFieldBO> fields = new LinkedHashMap<>();
        for (String field : businessFields) fields.put(field, field(table, field));
        fields.put("id", field(table, "id"));
        fields.put("version", field(table, "version"));
        fields.put("expectedVersion", field(table, "expectedVersion"));
        return CodegenConfigBO.ApiTableBO.builder().basePath(path).operations(operations).fields(fields).build();
    }

    private static CodegenConfigBO.ApiFieldBO field(String table, String field) {
        return CodegenConfigBO.ApiFieldBO.builder().permission("fixture:" + table + ":read")
                .labelPage("fixture." + table).labelKey("field." + field)
                .resultKey("fixture.field." + table + ".result." + field)
                .createKey("fixture.field." + table + ".create." + field)
                .updateKey("fixture.field." + table + ".update." + field)
                .createPermission("fixture:" + table + ":field:write")
                .updatePermission("fixture:" + table + ":field:write").build();
    }

    static List<String> permissions(String table) {
        return List.of("fixture:" + table + ":create", "fixture:" + table + ":update", "fixture:" + table + ":delete",
                "fixture:" + table + ":detail", "fixture:" + table + ":page", "fixture:" + table + ":read",
                "fixture:" + table + ":field:write");
    }

    static void addJson(ObjectNode document, String table, String stem, String path, String... fields) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ObjectNode methods = document.withObject("/domainMethods");
        methods.set(table, mapper.valueToTree(methods(stem)));
        ObjectNode api = document.withObject("/apiContract");
        api.put("transportMode", "generate");
        ObjectNode tables = api.withObject("/tables");
        tables.set(table, mapper.valueToTree(apiTable(table, path, fields)));
        var references = api.withArray("permissionReferences");
        for (String permission : permissions(table)) {
            boolean exists = false;
            for (var item : references) if (permission.equals(item.asText())) exists = true;
            if (!exists) references.add(permission);
        }
    }

    static void writeSecurityStubs(Path sourceRoot) throws Exception {
        write(sourceRoot, "org/springframework/security/access/prepost/PreAuthorize.java", """
                package org.springframework.security.access.prepost;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface PreAuthorize { String value(); }
                """);
        write(sourceRoot, "top/egon/cola/platform/tianquan/jianshen/contract/permission/ResourceType.java", """
                package top.egon.cola.platform.tianquan.jianshen.contract.permission;
                public enum ResourceType { API, FIELD }
                """);
        write(sourceRoot, "top/egon/cola/platform/tianquan/jianshen/contract/permission/PermissionManifest.java", """
                package top.egon.cola.platform.tianquan.jianshen.contract.permission;
                public class PermissionManifest { public enum JsonFieldType { STRING, NUMBER, BOOLEAN } }
                """);
        write(sourceRoot, "top/egon/cola/platform/tianquan/jianshen/contract/permission/annotation/PermissionResource.java", """
                package top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface PermissionResource { String key(); top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType type(); String labelPage(); String labelKey(); String[] oauthScopes() default {}; }
                """);
        write(sourceRoot, "top/egon/cola/platform/tianquan/jianshen/contract/permission/annotation/PermissionGroup.java", """
                package top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface PermissionGroup { String[] codes() default {}; }
                """);
        write(sourceRoot, "top/egon/cola/platform/tianquan/jianshen/contract/permission/annotation/FieldPermissions.java", """
                package top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface FieldPermissions { top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest.JsonFieldType valueType(); PermissionGroup plainRead() default @PermissionGroup; PermissionGroup write() default @PermissionGroup; }
                """);
        write(sourceRoot, "top/egon/cola/platform/tianquan/jianshen/contract/permission/annotation/RequiresPermissions.java", """
                package top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface RequiresPermissions { String[] codes(); }
                """);
        write(sourceRoot, "top/egon/cola/platform/tianquan/jianshen/contract/permission/annotation/PermissionReferences.java", """
                package top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface PermissionReferences { String[] value(); }
                """);
        write(sourceRoot, "top/egon/cola/platform/tianquan/jianshen/starter/field/PermissionJsonMapperFactory.java", """
                package top.egon.cola.platform.tianquan.jianshen.starter.field;
                public class PermissionJsonMapperFactory {
                    public com.fasterxml.jackson.databind.ObjectMapper forTransport(com.fasterxml.jackson.databind.ObjectMapper mapper) {
                        return mapper.copy().registerModule(new com.fasterxml.jackson.databind.module.SimpleModule("fixture-field-guard"));
                    }
                }
                """);
    }

    private static void write(Path root, String relative, String contents) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }
}
