import {defineMessages, definePermissionReferences, defineResourceReferences, defineResources} from '@stellar-enclosure/stellar-enclosure-web-shared/permissions'
[#list modules as module]
import {resources as r[=module.index], messages as m[=module.index]} from [=module.importJson]
[/#list]

export const generatedPermissions = definePermissionReferences([=permissionListJson] as const)
export const generatedResources = defineResources([[#list modules as module]...r[=module.index][#sep], [/#list]] as const)
export const generatedReferences = defineResourceReferences([=referenceListJson] as const)
export const generatedMessages = defineMessages([[#list modules as module]...m[=module.index][#sep], [/#list]] as const)
