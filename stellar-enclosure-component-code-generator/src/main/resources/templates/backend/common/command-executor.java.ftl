package [=commandExecutorPackage];

import [=domainServiceFqn];
[#if executorKind == "create"]
import [=commandPackage].[=createCommandType];
import [=applicationConverterPackage].[=createConverterType];
import [=applicationConverterPackage].[=resultConverterType];
import [=resultPackage].[=resultType];
[#elseif executorKind == "update"]
import [=commandPackage].[=updateCommandType];
import [=applicationConverterPackage].[=updateConverterType];
import [=applicationConverterPackage].[=resultConverterType];
import [=domainFqn];
import [=resultPackage].[=resultType];
[#else]
import [=commandPackage].[=deleteCommandType];
import [=applicationConverterPackage].[=deleteConverterType];
import [=domainFqn];
[/#if]
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

[#if executorKind == "create"]
@Service("[=createExecutorType?uncap_first]")
[#elseif executorKind == "update"]
@Service("[=updateExecutorType?uncap_first]")
[#else]
@Service("[=deleteExecutorType?uncap_first]")
[/#if]
@Validated
@Slf4j(topic = "[=manageImplPackage].[=manageImplType]")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
[#if executorKind == "create"]
public class [=createExecutorType] {
[#elseif executorKind == "update"]
public class [=updateExecutorType] {
[#else]
public class [=deleteExecutorType] {
[/#if]

    @Qualifier("[=domainServiceBean]")
    private final [=domainServiceType] domainService;

[#if executorKind == "create"]
    @Qualifier("[=createConverterType?uncap_first]Impl")
    private final [=createConverterType] converter;
[#elseif executorKind == "update"]
    @Qualifier("[=updateConverterType?uncap_first]Impl")
    private final [=updateConverterType] converter;
[#else]
    @Qualifier("[=deleteConverterType?uncap_first]Impl")
    private final [=deleteConverterType] converter;
[/#if]
[#if executorKind != "delete"]

    @Qualifier("[=resultConverterType?uncap_first]Impl")
    private final [=resultConverterType] resultConverter;
[/#if]

    @Transactional
[#if executorKind == "create"]
    public [=resultType] execute(@Valid [=createCommandType] command) {
        log.info("create {}", "[=tableName]");
        return resultConverter.toTarget(domainService.create(converter.toTarget(command)));
    }
[#elseif executorKind == "update"]
    public [=resultType] execute(@Valid [=updateCommandType] command) {
        [=domainType] domain = converter.toTarget(command);
        log.info("update {} id={} version={}", "[=tableName]", command.getId(), domain.getVersion());
        return resultConverter.toTarget(domainService.update(domain));
    }
[#else]
    public void execute(@Valid [=deleteCommandType] command) {
        [=domainType] domain = converter.toTarget(command);
        log.info("delete {} id={} version={}", "[=tableName]", command.getId(), domain.getVersion());
        domainService.delete(domain.getId(), domain.getVersion());
    }
[/#if]
}
