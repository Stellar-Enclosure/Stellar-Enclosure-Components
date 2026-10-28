package [=permissionPackage];

import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.LocalizedText;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionDefinition;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionMessage;

@PermissionDefinition(code = [=codeJson], meaning = [=meaningJson], labelPage = [=labelPageJson], labelKey = [=labelKeyJson])
@PermissionMessage(pageCode = [=labelPageJson], key = [=labelKeyJson], defaultLocale = "zh-CN",
        defaults = @LocalizedText(locale = "zh-CN", text = [=defaultTextJson]))
public final class [=permissionType] {
    private [=permissionType]() { }
}
