package [=queryPackage];

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import top.egon.cola.component.common.core.pojo.BasePojo;
[#if queryKind == "page"]
import top.egon.cola.component.common.core.pojo.PageQuery;
[/#if]

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Builder
[#if queryKind == "detail"]
public class [=detailQueryType] implements BasePojo {

    @NotNull
    @Positive
    private Long id;
}
[#else]
public class [=pageQueryType] implements BasePojo {
[#list filterFields as field]

    private [=field.javaType] [=field.javaName];
[/#list]

    @Valid
    @NotNull
    @Builder.Default
    private PageQuery page = PageQuery.defaultPage();
}
[/#if]
