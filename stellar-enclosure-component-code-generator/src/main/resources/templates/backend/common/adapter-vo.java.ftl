package [=adapterVoPackage];

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

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
public class [=viewType] implements BasePojo {

    @PermissionResource(key = "[=apiFields.id.resultKey]", type = ResourceType.FIELD, labelPage = "[=apiFields.id.labelPage]", labelKey = "[=apiFields.id.labelKey]")
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING, plainRead = @PermissionGroup(codes = {"[=apiFields.id.permission]"}))
    private String id;

    @PermissionResource(key = "[=apiFields.version.resultKey]", type = ResourceType.FIELD, labelPage = "[=apiFields.version.labelPage]", labelKey = "[=apiFields.version.labelKey]")
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER, plainRead = @PermissionGroup(codes = {"[=apiFields.version.permission]"}))
    private Long version;
[#list resultFields as field]

[#if field.javaType?starts_with("java.time.")]
    @JsonFormat(shape = JsonFormat.Shape.STRING)
[/#if]
    @PermissionResource(key = "[=apiFields[field.javaName].resultKey]", type = ResourceType.FIELD, labelPage = "[=apiFields[field.javaName].labelPage]", labelKey = "[=apiFields[field.javaName].labelKey]")
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.[=field.jsonType], plainRead = @PermissionGroup(codes = {"[=apiFields[field.javaName].permission]"}))
    private [=field.javaType] [=field.javaName];
[/#list]
}
