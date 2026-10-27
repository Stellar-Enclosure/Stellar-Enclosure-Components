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

    @NotNull
    @Positive
    private Long id;

    @NotNull
    @Min(0)
    private Long expectedVersion;
[#list updateFields as field]
[#if field.length?has_content]
    @Size(max = [=field.length])
[/#if]
[#if field.nullable == "false"]
    @NotNull
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
