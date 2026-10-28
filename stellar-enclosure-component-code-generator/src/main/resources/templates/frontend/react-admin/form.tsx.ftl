import {ActionGuard, useFieldAccess} from '@stellar-enclosure/stellar-enclosure-tianquan-jianshen-react-sdk'
import {useMutation} from '@tanstack/react-query'
import {Alert, Button, Form, Input, InputNumber, Select, Switch} from 'antd'
import {[=featureIdentifier]Api} from './[=feature].api'
import type {Create[=stem]Request, Update[=stem]Request, [=stem]View} from './[=feature].types'
[#if hasFormDecimal]
import {isDecimal} from './[=feature].decimal'
[/#if]

interface Props {
  readonly mode: 'create' | 'update'
  readonly record?: [=stem]View
  readonly onSaved: () => void
}

export const [=stem]Form = ({mode, record, onSaved}: Props) => {
[#list formFields as field]
  const field[=field.index] = useFieldAccess(mode === 'create' ? [=field.createKeyJson] : [=field.updateKeyJson])
[/#list]
  const versionAccess = useFieldAccess([=expectedVersionKeyJson])
  const cannotCreate = mode === 'create' && ([#list createFields as field][#if field.nullable == "false"]!field[=field.index].writable || [/#if][/#list]false)
  const version = record?.version
  const cannotUpdate = mode === 'update' && (!record?.id || typeof version !== 'number'
    || !Number.isSafeInteger(version) || version < 0 || !versionAccess.writable)
  const save = useMutation({
    mutationFn: async (values: Record<string, unknown>) => {
      if (cannotCreate || cannotUpdate) throw new Error('当前字段不可写或版本不可用')
      const body: Record<string, unknown> = {}
[#list formFields as field]
      if (field[=field.index].writable && (mode === 'create' ? [=field.createPresent?c] : [=field.updatePresent?c])
        && values[[=field.nameJson]] !== undefined) body[[=field.nameJson]] = values[[=field.nameJson]]
[/#list]
      if (mode === 'create') return [=featureIdentifier]Api.create(body as unknown as Create[=stem]Request)
      if (!record?.id || typeof version !== 'number' || !Number.isSafeInteger(version)) throw new Error('数据版本无效')
      return [=featureIdentifier]Api.update(record.id, {...body, expectedVersion: version} as Update[=stem]Request)
    },
    onSuccess: onSaved,
  })
  return <Form layout="vertical" initialValues={record} onFinish={values => save.mutate(values)}>
    {save.error && <Alert type="error" showIcon message={save.error instanceof Error ? save.error.message : '保存失败'} />}
[#list formFields as field]
    <Form.Item name={[=field.nameJson]} label={[=field.labelJson]}
      hidden={!field[=field.index].writable || (mode === 'create' ? ![=field.createPresent?c] : ![=field.updatePresent?c])}
      preserve={false}[#if field.control == "switch"] valuePropName="checked"[/#if]
      rules={[{required: mode === 'create' && [=field.requiredCreate?c], message: '请填写必填项'}[#if field.length?has_content], {max: [=field.length], message: '超出最大长度'}[/#if][#if field.integer], {validator: async (_, value) => {
        if (value !== undefined && value !== null && !Number.isSafeInteger(value)) throw new Error('请输入安全范围内的整数')
      }}[/#if][#if field.control == "decimal"], {validator: async (_, value) => {
        if (value === undefined || value === null || value === '') return
        if (!isDecimal(value[#if field.precision?has_content], [=field.precision], [#if field.scale?has_content][=field.scale][#else]0[/#if][/#if])) throw new Error('小数无效或超出精度')
      }}[/#if]]}>
[#if field.control == "number"]
      <InputNumber style={{width: '100%'}}[#if field.integer] precision={0}[/#if] />
[#elseif field.control == "select"]
      <Select allowClear options={[=field.optionsJson]} />
[#elseif field.control == "switch"]
      <Switch />
[#elseif field.control == "date"]
      <Input type="date" />
[#elseif field.control == "decimal"]
      <Input inputMode="decimal" />
[#else]
      <Input />
[/#if]
    </Form.Item>
[/#list]
    <ActionGuard resourceKey={mode === 'create' ? [=createKeyJson] : [=updateKeyJson]}>
      <Button type="primary" htmlType="submit" loading={save.isPending} disabled={cannotCreate || cannotUpdate}>保存</Button>
    </ActionGuard>
  </Form>
}
