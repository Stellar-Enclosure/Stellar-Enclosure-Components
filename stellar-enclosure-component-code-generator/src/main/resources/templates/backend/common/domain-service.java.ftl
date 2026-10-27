package [=domainServicePackage];

import [=domainFqn];
import [=domainQueryFqn];
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import top.egon.cola.component.common.core.pojo.PageSlice;

public interface [=domainServiceType] {

    [=domainType] create(@Valid [=domainType] value);

    [=domainType] update(@Valid [=domainType] value);

    void delete(@NotNull @Positive Long id, @NotNull @Min(0) Long expectedVersion);

    [=domainType] detail(@NotNull @Positive Long id);

    PageSlice<[=domainType]> query(@Valid [=domainQueryType] query);
}
