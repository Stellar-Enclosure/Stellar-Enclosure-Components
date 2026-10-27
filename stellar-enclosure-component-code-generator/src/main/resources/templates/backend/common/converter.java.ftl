[#if converterKind == "persistence"]
package [=persistenceConverterPackage];

import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.AnnotateWith;
import org.springframework.stereotype.Component;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import [=poFqn];
import [=domainFqn];
import top.egon.cola.component.common.core.converter.BaseConverter;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR, builder = @Builder(disableBuilder = true))
@AnnotateWith(value = Component.class, elements = @AnnotateWith.Element(strings = "[=persistenceConverterType?uncap_first]Impl"))
public interface [=persistenceConverterType] extends BaseConverter<[=poType], [=domainType]> {

    @Override
    [=domainType] toTarget([=poType] source);

[#list ignoredPoFields as ignored]
    @Mapping(target = "[=ignored]", ignore = true)
[/#list]
    @Override
    [=poType] toSource([=domainType] target);
}
[#elseif converterKind == "result"]
package [=applicationConverterPackage];

import org.mapstruct.Mapper;
import org.mapstruct.AnnotateWith;
import org.springframework.stereotype.Component;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import [=domainFqn];
import [=resultPackage].[=resultType];
import top.egon.cola.component.common.core.converter.BaseForwardConverter;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
@AnnotateWith(value = Component.class, elements = @AnnotateWith.Element(strings = "[=resultConverterType?uncap_first]Impl"))
public interface [=resultConverterType] extends BaseForwardConverter<[=domainType], [=resultType]> {

    @Override
    @Mapping(target = "id", expression = "java(source.getId() == null ? null : Long.toString(source.getId()))")
    [=resultType] toTarget([=domainType] source);
}
[#elseif converterKind == "create"]
package [=applicationConverterPackage];

import org.mapstruct.Mapper;
import org.mapstruct.AnnotateWith;
import org.springframework.stereotype.Component;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import [=commandPackage].[=createCommandType];
import [=domainFqn];
import top.egon.cola.component.common.core.converter.BaseForwardConverter;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
@AnnotateWith(value = Component.class, elements = @AnnotateWith.Element(strings = "[=createConverterType?uncap_first]Impl"))
public interface [=createConverterType] extends BaseForwardConverter<[=createCommandType], [=domainType]> {

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "version", ignore = true)
[#list domainFields as field]
[#if !createColumns?seq_contains(field.column)]
    @Mapping(target = "[=field.javaName]", ignore = true)
[/#if]
[/#list]
    [=domainType] toTarget([=createCommandType] source);
}
[#elseif converterKind == "update"]
package [=applicationConverterPackage];

import org.mapstruct.Mapper;
import org.mapstruct.AnnotateWith;
import org.springframework.stereotype.Component;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import [=commandPackage].[=updateCommandType];
import [=domainFqn];
import top.egon.cola.component.common.core.converter.BaseForwardConverter;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
@AnnotateWith(value = Component.class, elements = @AnnotateWith.Element(strings = "[=updateConverterType?uncap_first]Impl"))
public interface [=updateConverterType] extends BaseForwardConverter<[=updateCommandType], [=domainType]> {

    @Override
    @Mapping(target = "version", source = "expectedVersion")
[#list domainFields as field]
[#if !updateColumns?seq_contains(field.column)]
    @Mapping(target = "[=field.javaName]", ignore = true)
[/#if]
[/#list]
    [=domainType] toTarget([=updateCommandType] source);
}
[#elseif converterKind == "delete"]
package [=applicationConverterPackage];

import org.mapstruct.Mapper;
import org.mapstruct.AnnotateWith;
import org.springframework.stereotype.Component;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import [=commandPackage].[=deleteCommandType];
import [=domainFqn];
import top.egon.cola.component.common.core.converter.BaseForwardConverter;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
@AnnotateWith(value = Component.class, elements = @AnnotateWith.Element(strings = "[=deleteConverterType?uncap_first]Impl"))
public interface [=deleteConverterType] extends BaseForwardConverter<[=deleteCommandType], [=domainType]> {

    @Override
    @Mapping(target = "version", source = "expectedVersion")
[#list domainFields as field]
    @Mapping(target = "[=field.javaName]", ignore = true)
[/#list]
    [=domainType] toTarget([=deleteCommandType] source);
}
[#else]
package [=applicationConverterPackage];

import org.mapstruct.Mapper;
import org.mapstruct.AnnotateWith;
import org.springframework.stereotype.Component;
import org.mapstruct.ReportingPolicy;
import [=queryPackage].[=pageQueryType];
import [=domainQueryFqn];
import top.egon.cola.component.common.core.converter.BaseForwardConverter;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
@AnnotateWith(value = Component.class, elements = @AnnotateWith.Element(strings = "[=pageConverterType?uncap_first]Impl"))
public interface [=pageConverterType] extends BaseForwardConverter<[=pageQueryType], [=domainQueryType]> {

    @Override
    [=domainQueryType] toTarget([=pageQueryType] source);
}
[/#if]
