import {ActionGuard, FieldGuard, useFieldAccess, useRbac3Authorization} from '@stellar-enclosure/stellar-enclosure-tianquan-jianshen-react-sdk'
import {ListTemplateSettings, PageTemplate} from '@stellar-enclosure/stellar-enclosure-web-shared'
import {permissionContextKey} from '@stellar-enclosure/stellar-enclosure-web-shared/permissions'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {Alert, Button, Drawer, Form, Input, InputNumber, Popconfirm, Select, Space, Table, type TableColumnsType} from 'antd'
import {useState} from 'react'
import {[=featureIdentifier]Api} from './[=feature].api'
import {[=stem]Detail} from './[=stem]Detail'
import {[=stem]Form} from './[=stem]Form'
import type {[=stem]PageQuery, [=stem]View} from './[=feature].types'
[#if hasFilterDecimal]
import {isDecimal} from './[=feature].decimal'
[/#if]

export interface [=stem]PageProps {
  /** The host supplies UI capabilities from its permission resources; the backend checks them again. */
  readonly listTemplateAccess?: {readonly maySystem: boolean; readonly mayPersonal: boolean}
}

export const [=stem]Page = ({listTemplateAccess}: [=stem]PageProps = {}) => {
  const queryClient = useQueryClient()
  const {binding} = useRbac3Authorization()
  const scope = binding ? permissionContextKey(binding) : null
  const [templateError, setTemplateError] = useState<unknown>()
[#list resultFields as field]
  const listField[=field.index] = useFieldAccess([=field.resultKeyJson])
[/#list]
  const [pageNo, setPageNo] = useState(1)
  const [filters, setFilters] = useState<Partial<[=stem]PageQuery>>({})
  const [detailId, setDetailId] = useState<string | null>(null)
  const [editing, setEditing] = useState<{mode: 'create' | 'update'; record?: [=stem]View} | null>(null)
  const query = useQuery({queryKey: [[=featureJson], 'page', scope, pageNo, filters], enabled: !!scope,
    queryFn: () => [=featureIdentifier]Api.page({...filters, pageNo, pageSize: 20})})
  const listView = useQuery({queryKey: [[=featureJson], 'list-view', scope], enabled: !!binding && !!scope,
    queryFn: () => [=featureIdentifier]Api.listView(binding!.catalogVersion)})
  const maySystem = listTemplateAccess?.maySystem ?? listView.data?.mayManageSystem ?? false
  const mayPersonal = listTemplateAccess?.mayPersonal ?? listView.data?.mayManagePersonal ?? false
  const managementView = useQuery({queryKey: [[=featureJson], 'list-view-management', scope],
    enabled: !!binding && !!scope && maySystem,
    queryFn: () => [=featureIdentifier]Api.listView(binding!.catalogVersion, true)})
  const refresh = () => queryClient.invalidateQueries({queryKey: [[=featureJson]]})
  const remove = useMutation({mutationFn: ({id, version}: {id: string; version: number}) => [=featureIdentifier]Api.remove(id, version),
    onSuccess: () => { void refresh() }})
  const availableColumns: {key: string; label: string}[] = [
[#list resultFields as field]
    ...(listField[=field.index].read === 'OMITTED' ? [] : [{key: [=field.nameJson], label: [=field.labelJson]}]),
[/#list]
  ]
  const availableKeys = new Set(availableColumns.map(column => column.key))
  const view = listView.data && {...listView.data,
    columns: listView.data.columns.filter(column => availableKeys.has(column.columnKey)),
    visibleColumns: [...new Set(listView.data.visibleColumns)].filter(key => availableKeys.has(key))}
  const listError = listView.error ?? managementView.error ?? templateError
  const visibleKeys = view?.visibleColumns ?? []
  const columnLabels = new Map<string, string>([
[#list resultFields as field]
    [[=field.nameJson], [=field.labelJson]],
[/#list]
  ])
  const columnsByKey = new Map<string, TableColumnsType<[=stem]View>[number]>([
[#list resultFields as field]
    [[=field.nameJson], {title: [=field.labelJson], dataIndex: [=field.nameJson], render: (_value: unknown, row: [=stem]View) =>
      <FieldGuard fieldKey={[=field.resultKeyJson]} value={row[[=field.nameJson]] == null ? null : String(row[[=field.nameJson]])}
        maskedValue={row[[=field.nameJson]] == null ? null : String(row[[=field.nameJson]])} />}],
[/#list]
  ])
  return <PageTemplate title={[=titleJson]} pageState={{loading: query.isPending, error: query.error,
    empty: false, onRetry: () => void query.refetch()}}>
    <Space orientation="vertical" style={{width: '100%'}}>
[#if filterFields?has_content]
      <Form layout="inline" onFinish={values => {setFilters(values); setPageNo(1)}}>
[#list filterFields as field]
        <Form.Item name={[=field.nameJson]} label={[=field.labelJson]}[#if field.integer] rules={[{validator: async (_, value) => {
          if (value !== undefined && value !== null && !Number.isSafeInteger(value)) throw new Error('请输入安全范围内的整数')
        }}]}[/#if][#if field.control == "decimal"] rules={[{validator: async (_, value) => {
          if (value !== undefined && value !== null && value !== '' && !isDecimal(value[#if field.precision?has_content], [=field.precision], [#if field.scale?has_content][=field.scale][#else]0[/#if][/#if])) throw new Error('小数无效或超出精度')
        }}]}[/#if]>
[#if field.control == "number"]
          <InputNumber[#if field.integer] precision={0}[/#if] />
[#elseif field.control == "select"]
          <Select allowClear options={[=field.optionsJson]} style={{minWidth: 120}} />
[#elseif field.control == "switch"]
          <Select allowClear options={[{label: '是', value: true}, {label: '否', value: false}]} style={{minWidth: 100}} />
[#elseif field.control == "date"]
          <Input type="date" />
[#elseif field.control == "decimal"]
          <Input inputMode="decimal" />
[#else]
          <Input allowClear />
[/#if]
        </Form.Item>
[/#list]
        <Button type="primary" htmlType="submit">查询</Button>
      </Form>
[/#if]
      <ActionGuard resourceKey={[=createKeyJson]}><Button type="primary" onClick={() => setEditing({mode: 'create'})}>新增</Button></ActionGuard>
      {!!listError && <Alert type="error" showIcon message={listError instanceof Error ? listError.message : '字段模板暂不可用'} />}
      {view && <ListTemplateSettings key={scope ?? 'unavailable'} view={view} managementView={managementView.data}
        maySystem={maySystem} mayPersonal={mayPersonal} selectionWritable={!!scope && !listView.isFetching}
        actions={{save: [=featureIdentifier]Api.saveListTemplate, select: [=featureIdentifier]Api.selectListTemplate,
          remove: [=featureIdentifier]Api.removeListTemplate}}
        refresh={async () => {await Promise.all([queryClient.invalidateQueries({queryKey: [[=featureJson], 'list-view']}),
          queryClient.invalidateQueries({queryKey: [[=featureJson], 'list-view-management']})])}}
        labelColumn={column => columnLabels.get(column.columnKey) ?? column.columnKey} onError={setTemplateError} />}
      {remove.error && <Alert type="error" showIcon message={remove.error instanceof Error ? remove.error.message : '删除失败'} />}
      <Table<[=stem]View> rowKey={row => row.id ?? ''} dataSource={query.data?.records ?? []} pagination={false} loading={listView.isFetching}
        columns={[
          ...visibleKeys.flatMap(key => {const column = columnsByKey.get(key); return column ? [column] : []}),
          {title: '操作', render: (_value: unknown, row: [=stem]View) => <Space>
            <ActionGuard resourceKey={[=detailKeyJson]}><Button onClick={() => row.id && setDetailId(row.id)}>详情</Button></ActionGuard>
            <ActionGuard resourceKey={[=updateKeyJson]}><Button disabled={!row.id || typeof row.version !== 'number' || !Number.isSafeInteger(row.version)}
              onClick={() => setEditing({mode: 'update', record: row})}>编辑</Button></ActionGuard>
            <ActionGuard resourceKey={[=deleteKeyJson]}><Popconfirm title="确认删除？" onConfirm={() => {
              if (row.id && typeof row.version === 'number' && Number.isSafeInteger(row.version)) remove.mutate({id: row.id, version: row.version})
            }}><Button danger disabled={!row.id || typeof row.version !== 'number' || !Number.isSafeInteger(row.version) || remove.isPending}>删除</Button></Popconfirm></ActionGuard>
          </Space>},
        ]} />
      <Space><Button disabled={pageNo <= 1} onClick={() => setPageNo(pageNo - 1)}>上一页</Button>
        <span>第 {pageNo} 页</span><Button disabled={!query.data?.hasNext} onClick={() => setPageNo(pageNo + 1)}>下一页</Button></Space>
    </Space>
    {detailId && <[=stem]Detail id={detailId} onClose={() => setDetailId(null)} />}
    {editing && <Drawer open title={editing.mode === 'create' ? '新增' : '编辑'} width={560} onClose={() => setEditing(null)}>
      <[=stem]Form mode={editing.mode} record={editing.record} onSaved={() => {setEditing(null); void refresh()}} />
    </Drawer>}
  </PageTemplate>
}
