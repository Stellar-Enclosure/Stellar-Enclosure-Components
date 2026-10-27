package [=adapterDtoPackage];

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import top.egon.cola.component.common.core.pojo.BasePojo;
import com.fasterxml.jackson.annotation.JsonFormat;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;
[#if dtoKind == "page"]
import top.egon.cola.component.common.core.pojo.PageQuery;
[/#if]

@Data
@NoArgsConstructor
[#if dtoKind != "create" || createFields?has_content]
@AllArgsConstructor
[/#if]
@Accessors(chain = true)
[#if dtoKind != "create" || createFields?has_content]
@Builder
[/#if]
[#if dtoKind == "create"]
public class [=createDtoType] implements BasePojo {
[#list createFields as field]
[#if field.javaType?starts_with("java.time.")]
    @JsonFormat(shape = JsonFormat.Shape.STRING)
[/#if]
    @PermissionResource(key = "[=apiFields[field.javaName].createKey]", type = ResourceType.FIELD, labelPage = "[=apiFields[field.javaName].labelPage]", labelKey = "[=apiFields[field.javaName].labelKey]")
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.[=field.jsonType], write = @PermissionGroup(codes = {"[=apiFields[field.javaName].createPermission]"}))
[#if field.length?has_content]
    @Size(max = [=field.length])
[/#if]
[#if field.nullable == "false"]
    @NotNull
[/#if]
    private [=field.javaType] [=field.javaName];
[/#list]
[#elseif dtoKind == "update"]
public class [=updateDtoType] implements BasePojo {

    @PermissionResource(key = "[=apiFields.id.updateKey]", type = ResourceType.FIELD, labelPage = "[=apiFields.id.labelPage]", labelKey = "[=apiFields.id.labelKey]")
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER, write = @PermissionGroup(codes = {"[=apiFields.id.updatePermission]"}))
    @Positive
    private Long id;

    @PermissionResource(key = "[=apiFields.expectedVersion.updateKey]", type = ResourceType.FIELD, labelPage = "[=apiFields.expectedVersion.labelPage]", labelKey = "[=apiFields.expectedVersion.labelKey]")
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER, write = @PermissionGroup(codes = {"[=apiFields.expectedVersion.updatePermission]"}))
    @NotNull
    @Min(0)
    private Long expectedVersion;
[#list updateFields as field]
[#if field.javaType?starts_with("java.time.")]
    @JsonFormat(shape = JsonFormat.Shape.STRING)
[/#if]
    @PermissionResource(key = "[=apiFields[field.javaName].updateKey]", type = ResourceType.FIELD, labelPage = "[=apiFields[field.javaName].labelPage]", labelKey = "[=apiFields[field.javaName].labelKey]")
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.[=field.jsonType], write = @PermissionGroup(codes = {"[=apiFields[field.javaName].updatePermission]"}))
[#if field.length?has_content]
    @Size(max = [=field.length])
[/#if]
    private [=field.javaType] [=field.javaName];
[/#list]
[#else]
public class [=pageDtoType] implements BasePojo {
[#list filterFields as field]

    private [=field.javaType] [=field.javaName];
[/#list]

    @Valid
    @NotNull
    @Builder.Default
    private PageQuery page = PageQuery.defaultPage();
[/#if]
}
