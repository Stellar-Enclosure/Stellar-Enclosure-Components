import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {afterEach, beforeAll, expect, it, vi} from 'vitest'
import {[=stem]Page} from './[=stem]Page'

[#assign first = resultFields[0]]
const state = vi.hoisted(() => ({selected: '1',
  page: vi.fn(async () => ({records: [], hasNext: false})),
  listView: vi.fn(async () => ({definitionDigest: 'digest', catalogVersion: '1',
    columns: [{columnKey: [=first.nameJson], labelPage: 'test', labelKey: 'column'}],
    templates: [{id: '1', type: 'SYSTEM', name: '默认', defaultTemplate: true, version: '1', columnKeys: [[=first.nameJson]]},
      {id: '2', type: 'PERSONAL', name: '空视图', defaultTemplate: false, version: '1', columnKeys: []}],
    selectedTemplateId: state.selected, visibleColumns: state.selected === '1' ? [[=first.nameJson]] : [],
    mayManageSystem: false, mayManagePersonal: false})),
  select: vi.fn(async (id: string) => {state.selected = id}),
}))
vi.mock('@stellar-enclosure/stellar-enclosure-tianquan-jianshen-react-sdk', () => ({
  ActionGuard: ({children}: {children: React.ReactNode}) => children,
  FieldGuard: ({value}: {value: React.ReactNode}) => value,
  useFieldAccess: () => ({read: 'PLAIN', writable: false}),
  useRbac3Authorization: () => ({binding: {
    issuer: 'https://identity.example/tenants/42', subject: 'alice',
    catalogScope: {catalogScopeId: '101', tenantId: '42', businessCode: 'example', applicationCode: 'fixture', namespaceCode: 'default', environment: 'dev'},
    catalogVersion: '1', authVersion: '1', policyVersion: '1', requestEpoch: 1,
    activeRoleContext: {applicationCode: 'fixture', rootRoleIds: ['1'], effectiveRoleIds: ['1']},
  }}),
}))
vi.mock('./[=feature].api', () => ({[=featureIdentifier]Api: {page: state.page, listView: state.listView,
  selectListTemplate: state.select, saveListTemplate: vi.fn(), removeListTemplate: vi.fn(), remove: vi.fn()}}))

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', {writable: true, value: (query: string) => ({matches: false, media: query,
    addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {}})})
  Object.defineProperty(globalThis, 'ResizeObserver', {writable: true, value: class {observe() {} unobserve() {} disconnect() {}}})
})
afterEach(() => {cleanup(); state.selected = '1'; vi.clearAllMocks()})

it('switches the server template without changing or repeating the list query', async () => {
  const client = new QueryClient({defaultOptions: {queries: {retry: false}}})
  render(<QueryClientProvider client={client}><[=stem]Page /></QueryClientProvider>)
  await screen.findByRole('columnheader', {name: [=first.labelJson]})
  expect(state.page).toHaveBeenCalledTimes(1)
  fireEvent.mouseDown(screen.getByRole('combobox', {name: '当前字段模板'}))
  fireEvent.click(await screen.findByText('个人模板 · 空视图'))
  await waitFor(() => expect(state.select).toHaveBeenCalledWith('2', expect.objectContaining({catalogVersion: '1'})))
  await waitFor(() => expect(screen.queryByRole('columnheader', {name: [=first.labelJson]})).toBeNull())
  expect(state.page).toHaveBeenCalledTimes(1)
  expect(state.page).toHaveBeenCalledWith({pageNo: 1, pageSize: 20})
  expect(state.listView.mock.calls.length).toBeGreaterThan(1)
})
