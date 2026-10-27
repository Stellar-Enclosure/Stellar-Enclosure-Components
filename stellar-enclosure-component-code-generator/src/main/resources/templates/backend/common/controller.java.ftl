package [=controllerPackage];

import [=managePackage].[=manageType];
import [=commandPackage].[=updateCommandType];
import [=commandPackage].[=deleteCommandType];
import [=queryPackage].[=detailQueryType];
import [=resultPackage].[=resultType];
import [=adapterDtoPackage].[=createDtoType];
import [=adapterDtoPackage].[=updateDtoType];
import [=adapterDtoPackage].[=pageDtoType];
import [=adapterVoPackage].[=viewType];
import [=adapterConverterPackage].[=adapterConverterType];
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.egon.cola.component.common.core.pojo.PageSlice;

@RestController("[=controllerBean]")
@RequestMapping("[=apiBasePath]")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
@Slf4j
public class [=controllerType] {

    @Qualifier("[=manageBean]")
    private final [=manageType] manage;

    @Qualifier("[=adapterConverterType?uncap_first]Impl")
    private final [=adapterConverterType] converter;

    @PostMapping
    public [=viewType] create(@Valid @RequestBody [=createDtoType] request) {
        log.info("http create {}", "[=tableName]");
        return converter.toView(manage.create(converter.toTarget(request)));
    }

    @GetMapping("/{id}")
    public [=viewType] detail(@PathVariable String id) {
        return converter.toView(manage.detail(new [=detailQueryType]().setId(parseId(id))));
    }

    @GetMapping
    public PageSlice<[=viewType]> page(@Valid [=pageDtoType] request) {
        PageSlice<[=resultType]> slice = manage.page(converter.toPageQuery(request));
        return PageSlice.of(slice.records().stream().map(converter::toView).toList(), slice.hasNext());
    }

    @PutMapping("/{id}")
    public [=viewType] update(@PathVariable String id, @Valid @RequestBody [=updateDtoType] request) {
        [=updateCommandType] command = converter.toUpdateCommand(request);
        command.setId(parseId(id));
        return converter.toView(manage.update(command));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id, @RequestParam("expectedVersion") long expectedVersion) {
        manage.delete(new [=deleteCommandType]().setId(parseId(id)).setExpectedVersion(expectedVersion));
    }

    private static long parseId(String id) {
        if (id == null || !id.matches("[0-9]+")) {
            throw new IllegalArgumentException("id must be a positive decimal string");
        }
        return Long.parseLong(id);
    }
}
