package [=daoPackage];

import [=poFqn];
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.apache.ibatis.annotations.Mapper;
[#if hasSecondaryRoot]
import org.apache.ibatis.annotations.Param;
[/#if]
import org.springframework.validation.annotation.Validated;
[#if includeQuery!false]
import [=domainQueryFqn];
[#if !hasSecondaryRoot]
import org.apache.ibatis.annotations.Param;
[/#if]
import java.util.List;
[/#if]
import top.egon.cola.component.common.mybatis.extension.EgonColaMapper;
import top.egon.cola.component.common.mybatis.model.EgonColaModelValidationGroups;

@Mapper
@Validated
public interface [=daoType] extends EgonColaMapper<[=poType]> {
[#if hasSecondaryRoot]
    @Validated(EgonColaModelValidationGroups.Update.class)
    int updateRouted(@Param("et") @Valid @NotNull(groups = EgonColaModelValidationGroups.Update.class) [=poType] entity);
[/#if]
[#if includeQuery!false]
    @Validated(EgonColaModelValidationGroups.Query.class)
    List<[=poType]> selectByQuery(@Param("query") @Valid @NotNull(groups = EgonColaModelValidationGroups.Query.class) [=domainQueryType] query,
                                  @Param("limit") @Min(value = 1, groups = EgonColaModelValidationGroups.Query.class) int limit,
                                  @Param("offset") @Min(value = 0, groups = EgonColaModelValidationGroups.Query.class) long offset);
[/#if]
}
