export const resources = [
  {key: [=pageKeyJson], type: 'PAGE', target: {route: [=routeJson], label: [=titleJson], order: '100'},
    requirement: {mode: 'ALL', codes: [=pagePermissionsJson]}, i18n: {pageCode: [=pageCodeJson], labelKey: 'page.title'}},
[#list actions as action]
  {key: [=action.keyJson], type: 'BUTTON', target: {component: [=componentJson], action: [=action.nameJson]},
    requirement: {mode: 'ALL', codes: [[=action.permissionJson]]}, i18n: {pageCode: [=pageCodeJson], labelKey: [=action.labelKeyJson]}},
[/#list]
] as const

export const messages = [
  {pageCode: [=pageCodeJson], key: 'page.title', defaultLocale: 'zh-CN', defaults: {'zh-CN': [=titleJson]}},
[#list actions as action]
  {pageCode: [=pageCodeJson], key: [=action.labelKeyJson], defaultLocale: 'zh-CN', defaults: {'zh-CN': [=action.labelJson]}},
[/#list]
] as const
