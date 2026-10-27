package [=managePackage];

import [=commandPackage].[=createCommandType];
import [=commandPackage].[=updateCommandType];
import [=commandPackage].[=deleteCommandType];
import [=queryPackage].[=detailQueryType];
import [=queryPackage].[=pageQueryType];
import [=resultPackage].[=resultType];
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.common.core.pojo.PageSlice;

@Validated
public interface [=manageType] {

    [=resultType] create(@NotNull @Valid [=createCommandType] command);

    [=resultType] update(@NotNull @Valid [=updateCommandType] command);

    void delete(@NotNull @Valid [=deleteCommandType] command);

    [=resultType] detail(@NotNull @Valid [=detailQueryType] query);

    PageSlice<[=resultType]> page(@NotNull @Valid [=pageQueryType] query);
}
