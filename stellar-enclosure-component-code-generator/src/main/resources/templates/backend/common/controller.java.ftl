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
import org.springframework.context.annotation.DependsOn;
import org.springframework.security.access.prepost.PreAuthorize;
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
import top.egon.cola.component.common.core.pojo.PageQuery;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionReferences;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.RequiresPermissions;

@RestController("[=controllerBean]")
@RequestMapping("[=apiBasePath]")
@DependsOn({"permissionExecutionGuard", "permissionJsonMapperFactory", "[=permissionTransportBean]"})
@RequiredArgsConstructor(onConstructor_ = @Autowired)
@Slf4j
[#if permissionReferences?has_content]
@PermissionReferences({[#list permissionReferences as permission]"[=permission]"[#if permission?has_next], [/#if][/#list]})
[/#if]
public class [=controllerType] {

    @Qualifier("[=manageBean]")
    private final [=manageType] manage;

    @Qualifier("[=adapterConverterType?uncap_first]Impl")
    private final [=adapterConverterType] converter;

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_[=apiOperations.create.scope]')")
    @PermissionResource(key = "[=apiOperations.create.resourceCode]", type = ResourceType.API, labelPage = "[=apiOperations.create.labelPage]", labelKey = "[=apiOperations.create.labelKey]", oauthScopes = {"[=apiOperations.create.scope]"})
    @RequiresPermissions(codes = {"[=apiOperations.create.permission]"})
    public [=viewType] create(@Valid @RequestBody [=createDtoType] request) {
        log.info("http create {}", "[=tableName]");
        return converter.toView(manage.create(converter.toTarget(request)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_[=apiOperations.detail.scope]')")
    @PermissionResource(key = "[=apiOperations.detail.resourceCode]", type = ResourceType.API, labelPage = "[=apiOperations.detail.labelPage]", labelKey = "[=apiOperations.detail.labelKey]", oauthScopes = {"[=apiOperations.detail.scope]"})
    @RequiresPermissions(codes = {"[=apiOperations.detail.permission]"})
    public [=viewType] detail(@PathVariable String id) {
        return converter.toView(manage.detail(new [=detailQueryType]().setId(parseId(id))));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_[=apiOperations.page.scope]')")
    @PermissionResource(key = "[=apiOperations.page.resourceCode]", type = ResourceType.API, labelPage = "[=apiOperations.page.labelPage]", labelKey = "[=apiOperations.page.labelKey]", oauthScopes = {"[=apiOperations.page.scope]"})
    @RequiresPermissions(codes = { [#list pagePermissions as permission]"[=permission]"[#if permission?has_next], [/#if][/#list] })
    public PageSlice<[=viewType]> page(@Valid [=pageDtoType] request,
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "page.pageNo", required = false) Integer nestedPageNo,
            @RequestParam(value = "page.pageSize", required = false) Integer nestedPageSize) {
        if ((pageNo != null && nestedPageNo != null && !pageNo.equals(nestedPageNo))
                || (pageSize != null && nestedPageSize != null && !pageSize.equals(nestedPageSize))) {
            throw new IllegalArgumentException("conflicting pagination parameters");
        }
        PageQuery page = request.getPage();
        request.setPage(new PageQuery(pageNo != null ? pageNo : nestedPageNo != null ? nestedPageNo : page.pageNo(),
                pageSize != null ? pageSize : nestedPageSize != null ? nestedPageSize : page.pageSize()));
        PageSlice<[=resultType]> slice = manage.page(converter.toPageQuery(request));
        return PageSlice.of(slice.records().stream().map(converter::toView).toList(), slice.hasNext());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_[=apiOperations.update.scope]')")
    @PermissionResource(key = "[=apiOperations.update.resourceCode]", type = ResourceType.API, labelPage = "[=apiOperations.update.labelPage]", labelKey = "[=apiOperations.update.labelKey]", oauthScopes = {"[=apiOperations.update.scope]"})
    @RequiresPermissions(codes = {"[=apiOperations.update.permission]"})
    public [=viewType] update(@PathVariable String id, @Valid @RequestBody [=updateDtoType] request) {
        long pathId = parseId(id);
        if (request.getId() != null && request.getId().longValue() != pathId) {
            throw new IllegalArgumentException("body id does not match path id");
        }
        [=updateCommandType] command = converter.toUpdateCommand(request);
        command.setId(pathId);
        return converter.toView(manage.update(command));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_[=apiOperations.delete.scope]')")
    @PermissionResource(key = "[=apiOperations.delete.resourceCode]", type = ResourceType.API, labelPage = "[=apiOperations.delete.labelPage]", labelKey = "[=apiOperations.delete.labelKey]", oauthScopes = {"[=apiOperations.delete.scope]"})
    @RequiresPermissions(codes = {"[=apiOperations.delete.permission]"})
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
