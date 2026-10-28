export interface [=stem]View {
  readonly id?: string
  readonly version?: number
[#list resultFields as field]
  readonly [=field.nameJson]?: [=field.tsType][#if field.nullable == "true"] | null[/#if]
[/#list]
}

export interface Create[=stem]Request {
[#list createFields as field]
  readonly [=field.nameJson][#if field.nullable == "true"]?[/#if]: [=field.tsType][#if field.nullable == "true"] | null[/#if]
[/#list]
}

export interface Update[=stem]Request {
  readonly expectedVersion: number
[#list updateFields as field]
  readonly [=field.nameJson]?: [=field.tsType] | null
[/#list]
}

export interface [=stem]PageQuery {
  readonly pageNo: number
  readonly pageSize: number
[#list filterFields as field]
  readonly [=field.nameJson]?: [=field.tsType]
[/#list]
}

export interface [=stem]Slice {
  readonly records: readonly [=stem]View[]
  readonly hasNext: boolean
}
