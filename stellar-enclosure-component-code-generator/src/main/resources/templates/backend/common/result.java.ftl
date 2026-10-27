package [=resultPackage];

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
public class [=resultType] implements BasePojo {

    private String id;
[#list resultFields as field]

    private [=field.javaType] [=field.javaName];
[/#list]
}
