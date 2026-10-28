import {beforeEach, describe, expect, it, vi} from 'vitest'

const transport = vi.hoisted(() => ({request: vi.fn()}))
vi.mock('../../app/api', () => ({api: transport}))
import {[=featureIdentifier]Api} from './[=feature].api'

beforeEach(() => transport.request.mockReset())

describe('[=stem] API contract', () => {
  it('loads and selects a list template through separate backend requests', async () => {
    const view = {definitionDigest: 'digest', catalogVersion: '1', columns: [], templates: [],
      selectedTemplateId: null, visibleColumns: [], mayManageSystem: false, mayManagePersonal: false}
[#if responseMode == "result-record"]
    transport.request.mockResolvedValueOnce({success: true, data: view}).mockResolvedValueOnce({success: true, data: true})
[#else]
    transport.request.mockResolvedValueOnce(view).mockResolvedValueOnce(true)
[/#if]
    await expect([=featureIdentifier]Api.listView('1')).resolves.toEqual(view)
    expect(transport.request).toHaveBeenNthCalledWith(1, [=apiBasePathJson] + '/list-view?catalogVersion=1&includeSystemMetadata=false',
      undefined, {allowEmptyResponse: false})
    await [=featureIdentifier]Api.selectListTemplate('7', view)
    expect(transport.request).toHaveBeenNthCalledWith(2, [=apiBasePathJson] + '/list-view/selection',
      {method: 'POST', body: JSON.stringify({templateId: '7', catalogVersion: '1', definitionDigest: 'digest'})}, {allowEmptyResponse: false})
  })

  it('reads a slice using the backend pagination contract', async () => {
    const slice = {records: [{id: '42', version: 1}], hasNext: false}
[#if responseMode == "result-record"]
    transport.request.mockResolvedValue({success: true, data: slice})
[#else]
    transport.request.mockResolvedValue(slice)
[/#if]
    await expect([=featureIdentifier]Api.page({pageNo: 1, pageSize: 20})).resolves.toEqual(slice)
    expect(transport.request).toHaveBeenCalledWith([=apiBasePathJson] + '?pageNo=1&pageSize=20', undefined, {allowEmptyResponse: false})
  })

  it('accepts an empty delete response and sends the expected version once', async () => {
    transport.request.mockResolvedValue(undefined)
    await expect([=featureIdentifier]Api.remove('42', 3)).resolves.toBeUndefined()
    expect(transport.request).toHaveBeenCalledOnce()
    expect(transport.request).toHaveBeenCalledWith([=apiBasePathJson] + '/42?expectedVersion=3', {method: 'DELETE'}, {allowEmptyResponse: true})
  })
[#list resultFields as field]
[#if field.control == "decimal"]

  it('preserves [=field.javaName] as an exact decimal string', async () => {
    const record = {id: '42', version: 1, [=field.nameJson]: '9007199254740993.01'}
[#if responseMode == "result-record"]
    transport.request.mockResolvedValue({success: true, data: record})
[#else]
    transport.request.mockResolvedValue(record)
[/#if]
    await expect([=featureIdentifier]Api.detail('42')).resolves.toEqual(record)
    const small = {...record, [=field.nameJson]: '1E-7'}
[#if responseMode == "result-record"]
    transport.request.mockResolvedValue({success: true, data: small})
[#else]
    transport.request.mockResolvedValue(small)
[/#if]
    await expect([=featureIdentifier]Api.detail('42')).resolves.toEqual(small)
  })
[/#if]
[/#list]
})
