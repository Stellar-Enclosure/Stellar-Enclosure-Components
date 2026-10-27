package [=commandPackage];

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

@Data
@NoArgsConstructor
[#if commandKind != "create" || createFields?has_content]
@AllArgsConstructor
[/#if]
@Accessors(chain = true)
[#if commandKind != "create" || createFields?has_content]
@Builder
[/#if]
[#if commandKind == "create"]
public class [=createCommandType] implements BasePojo {
[#list createFields as field]
[#if field.length?has_content]
    @Size(max = [=field.length])
[/#if]
[#if field.nullable == "false"]
    @NotNull
[/#if]
    private [=field.javaType] [=field.javaName];
[/#list]
}
[#elseif commandKind == "update"]
public class [=updateCommandType] implements BasePojo {

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
}
[#else]
public class [=deleteCommandType] implements BasePojo {

    @NotNull
    @Positive
    private Long id;

    @NotNull
    @Min(0)
    private Long expectedVersion;
}
[/#if]
