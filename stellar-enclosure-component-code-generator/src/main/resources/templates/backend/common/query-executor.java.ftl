package [=queryExecutorPackage];

import [=domainServiceFqn];
import [=applicationConverterPackage].[=resultConverterType];
import [=resultPackage].[=resultType];
[#if executorKind == "detail"]
import [=queryPackage].[=detailQueryType];
[#else]
import [=queryPackage].[=pageQueryType];
import [=domainFqn];
import [=applicationConverterPackage].[=pageConverterType];
import top.egon.cola.component.common.core.pojo.PageSlice;
[/#if]
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

[#if executorKind == "detail"]
@Service("[=detailExecutorType?uncap_first]")
[#else]
@Service("[=pageExecutorType?uncap_first]")
[/#if]
@Validated
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
[#if executorKind == "detail"]
public class [=detailExecutorType] {
[#else]
public class [=pageExecutorType] {
[/#if]

    @Qualifier("[=domainServiceBean]")
    private final [=domainServiceType] domainService;

    @Qualifier("[=resultConverterType?uncap_first]Impl")
    private final [=resultConverterType] resultConverter;
[#if executorKind == "page"]

    @Qualifier("[=pageConverterType?uncap_first]Impl")
    private final [=pageConverterType] pageConverter;
[/#if]

    @Transactional(readOnly = true)
[#if executorKind == "detail"]
    public [=resultType] execute(@Valid [=detailQueryType] query) {
        return resultConverter.toTarget(domainService.detail(query.getId()));
    }
[#else]
    public PageSlice<[=resultType]> execute(@Valid [=pageQueryType] query) {
        PageSlice<[=domainType]> slice = domainService.query(pageConverter.toTarget(query));
        return PageSlice.of(slice.records().stream().map(resultConverter::toTarget).toList(), slice.hasNext());
    }
[/#if]
}
