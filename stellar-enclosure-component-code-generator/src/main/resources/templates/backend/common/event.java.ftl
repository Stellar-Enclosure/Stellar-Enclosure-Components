package [=eventPackage];

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
@AllArgsConstructor
@Accessors(chain = true)
@Builder
public class [=eventType] implements BasePojo {

    @NotBlank
    @Size(max = 256)
    private String eventId;

    @NotBlank
    @Size(max = 32)
    private String schemaVersion;
[#list eventFields as field]

[#if field.nullable == "false"]
    @NotNull
[/#if]
[#if field.constraint == "positive"]
    @Positive
[#elseif field.constraint == "nonnegative"]
    @Min(0)
[/#if]
[#if field.length?has_content]
    @Size(max = [=field.length])
[/#if]
    private [=field.javaType] [=field.source];
[/#list]
}
