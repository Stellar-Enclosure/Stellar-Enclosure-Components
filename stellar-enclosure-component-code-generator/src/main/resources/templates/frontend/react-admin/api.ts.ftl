import {api} from '../../app/api'
import type {ListTemplate, ListTemplateSave, ListTemplateView} from '@stellar-enclosure/stellar-enclosure-web-shared'
[#if responseMode == "result-record"]
import {ApiError} from '@stellar-enclosure/stellar-enclosure-web-shared'
[/#if]
import type {Create[=stem]Request, Update[=stem]Request, [=stem]PageQuery, [=stem]Slice, [=stem]View} from './[=feature].types'
[#if hasResultDecimal]
import {isDecimal} from './[=feature].decimal'
[/#if]

const base = [=apiBasePathJson]

const request = async <T,>(path: string, init?: RequestInit, allowEmptyResponse = false): Promise<T> => {
  const response = await api.request<unknown>(path, init, {allowEmptyResponse})
[#if responseMode == "result-record"]
  if (response === undefined && allowEmptyResponse) return undefined as T
  if (!response || typeof response !== 'object' || !('success' in response) || !('data' in response)) {
    throw new ApiError('Response envelope is invalid', 200, 'INVALID_RESPONSE', {retryable: false})
  }
  if (response.success !== true) {
    const error = response as {status?: unknown; message?: unknown}
    throw new ApiError(typeof error.message === 'string' ? error.message : 'Request failed', 200,
      typeof error.status === 'string' ? error.status : 'REQUEST_FAILED', {retryable: false})
  }
  return response.data as T
[#else]
  return response as T
[/#if]
}

const validateView = (value: unknown): [=stem]View => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid response record')
  const row = value as [=stem]View
  if (row.version != null && !Number.isSafeInteger(row.version)) throw new Error('Unsafe response version')
[#list resultFields as field]
[#if field.integer]
  if (row[[=field.nameJson]] != null && !Number.isSafeInteger(row[[=field.nameJson]])) throw new Error('Unsafe integer response')
[#elseif field.control == "decimal"]
  if (row[[=field.nameJson]] != null && !isDecimal(row[[=field.nameJson]])) throw new Error('Invalid decimal response')
[/#if]
[/#list]
  return row
}

export const [=featureIdentifier]Api = {
  listView: (catalogVersion: string, includeSystemMetadata = false): Promise<ListTemplateView> => {
    const query = new URLSearchParams({catalogVersion, includeSystemMetadata: String(includeSystemMetadata)})
    return request<ListTemplateView>(base + '/list-view?' + query.toString())
  },
  selectListTemplate: (id: string, view: ListTemplateView): Promise<unknown> => request(base + '/list-view/selection', {
    method: 'POST', body: JSON.stringify({templateId: id, catalogVersion: view.catalogVersion, definitionDigest: view.definitionDigest}),
  }),
  saveListTemplate: (type: ListTemplate['type'], command: ListTemplateSave): Promise<ListTemplate> =>
    request(base + '/list-view/' + type.toLowerCase(), {method: 'POST', body: JSON.stringify(command)}),
  removeListTemplate: (template: ListTemplate, catalogVersion: string): Promise<unknown> => {
    const query = new URLSearchParams({catalogVersion, version: template.version})
    return request(base + '/list-view/' + template.type.toLowerCase() + '/' + encodeURIComponent(template.id) + '?' + query.toString(), {method: 'DELETE'})
  },
  page: async (query: [=stem]PageQuery): Promise<[=stem]Slice> => {
    const parameters = new URLSearchParams()
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== '') parameters.set(key, String(value))
    }
    const result = await request<[=stem]Slice>(base + '?' + parameters.toString())
    if (!result || !Array.isArray(result.records) || typeof result.hasNext !== 'boolean'
      || result.records.some(row => typeof row.id !== 'string' || !/^[0-9]+$/.test(row.id))) {
      throw new Error('Invalid page response')
    }
    result.records.forEach(validateView)
    return result
  },
  detail: async (id: string) => validateView(await request<unknown>(base + '/' + encodeURIComponent(id))),
  create: async (body: Create[=stem]Request) => validateView(await request<unknown>(base, {method: 'POST', body: JSON.stringify(body)})),
  update: async (id: string, body: Update[=stem]Request) => validateView(await request<unknown>(base + '/' + encodeURIComponent(id),
    {method: 'PUT', body: JSON.stringify(body)})),
  remove: async (id: string, expectedVersion: number): Promise<void> => {
    await request<unknown>(base + '/' + encodeURIComponent(id)
      + '?expectedVersion=' + encodeURIComponent(String(expectedVersion)), {method: 'DELETE'}, true)
  },
}
