package [=eventConverterPackage];

import [=eventPackage].[=eventType];
import [=commandPackage].[=eventCommandType];
import org.mapstruct.Mapper;
import org.mapstruct.AnnotateWith;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.springframework.stereotype.Component;
import top.egon.cola.component.common.core.converter.BaseForwardConverter;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
@AnnotateWith(value = Component.class, elements = @AnnotateWith.Element(strings = "[=eventConverterType?uncap_first]Impl"))
public interface [=eventConverterType] extends BaseForwardConverter<[=eventType], [=eventCommandType]> {

    @Override
[#list eventFields as field]
[#if field.target?has_content]
    @Mapping(target = "[=field.target]", source = "[=field.source]")
[/#if]
[/#list]
    [=eventCommandType] toTarget([=eventType] source);
}
