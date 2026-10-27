package [=adapterConverterPackage];

import [=adapterDtoPackage].[=createDtoType];
import [=adapterDtoPackage].[=updateDtoType];
import [=adapterDtoPackage].[=pageDtoType];
import [=adapterVoPackage].[=viewType];
import [=commandPackage].[=createCommandType];
import [=commandPackage].[=updateCommandType];
import [=queryPackage].[=pageQueryType];
import [=resultPackage].[=resultType];
import org.mapstruct.Mapper;
import org.mapstruct.AnnotateWith;
import org.mapstruct.ReportingPolicy;
import org.springframework.stereotype.Component;
import top.egon.cola.component.common.core.converter.BaseForwardConverter;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
@AnnotateWith(value = Component.class, elements = @AnnotateWith.Element(strings = "[=adapterConverterType?uncap_first]Impl"))
public interface [=adapterConverterType] extends BaseForwardConverter<[=createDtoType], [=createCommandType]> {

    @Override
    [=createCommandType] toTarget([=createDtoType] source);

    [=updateCommandType] toUpdateCommand([=updateDtoType] source);

    [=pageQueryType] toPageQuery([=pageDtoType] source);

    [=viewType] toView([=resultType] source);
}
