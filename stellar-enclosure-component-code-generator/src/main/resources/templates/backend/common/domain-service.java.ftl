package [=domainServicePackage];

import [=domainFqn];
import [=domainQueryFqn];
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import top.egon.cola.component.common.core.pojo.PageSlice;

public interface [=domainServiceType] {

    [=domainType] [=createMethod](@NotNull @Valid [=domainType] value);

    [=domainType] [=updateMethod](@NotNull @Valid [=domainType] value);

    void [=deleteMethod](@NotNull @Positive Long id, @NotNull @Min(0) Long expectedVersion);

    [=domainType] [=detailMethod](@NotNull @Positive Long id);

    PageSlice<[=domainType]> [=queryMethod](@NotNull @Valid [=domainQueryType] query);
}
