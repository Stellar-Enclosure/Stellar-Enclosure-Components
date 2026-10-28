import {FieldGuard} from '@stellar-enclosure/stellar-enclosure-tianquan-jianshen-react-sdk'
import {PageState} from '@stellar-enclosure/stellar-enclosure-web-shared'
import {useQuery} from '@tanstack/react-query'
import {Descriptions, Drawer} from 'antd'
import {[=featureIdentifier]Api} from './[=feature].api'

export const [=stem]Detail = ({id, onClose}: {readonly id: string; readonly onClose: () => void}) => {
  const query = useQuery({queryKey: [[=featureJson], 'detail', id], queryFn: () => [=featureIdentifier]Api.detail(id)})
  return <Drawer open title="详情" onClose={onClose} width={560}>
    <PageState loading={query.isPending} error={query.error} empty={!query.data} onRetry={() => void query.refetch()}>
      {query.data && <Descriptions column={1} bordered size="small">
        <Descriptions.Item label="ID"><FieldGuard fieldKey={[=idResultKeyJson]} value={query.data.id} maskedValue={query.data.id} /></Descriptions.Item>
[#list resultFields as field]
        <Descriptions.Item label={[=field.labelJson]}><FieldGuard fieldKey={[=field.resultKeyJson]}
          value={query.data[[=field.nameJson]] == null ? null : String(query.data[[=field.nameJson]])}
          maskedValue={query.data[[=field.nameJson]] == null ? null : String(query.data[[=field.nameJson]])} /></Descriptions.Item>
[/#list]
      </Descriptions>}
    </PageState>
  </Drawer>
}
