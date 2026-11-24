import {useFleetManagement} from '../components/FleetManagement.jsx'
import { useApi } from '../../../shared/api/client.js'
import { DataTable, PageHead } from '../../../shared/components/ui.jsx'

export default function WarehousesPage() {
  const {data,error,reload}=useApi('/api/warehouses',5000)
  const fleet=useFleetManagement('warehouses',reload)
  return (<div className="page"><PageHead title="Kho">{fleet.toolbar}</PageHead>{fleet.controls}{fleet.ui}
    <DataTable error={error} rows={fleet.filter(data)} columns={[{ key: 'code', label: 'Mã' }, { key: 'name', label: 'Tên kho' }, { key: 'vehicles', label: 'Số xe' },
      { key: 'orders', label: 'Số đơn' }, { key: 'address', label: 'Địa chỉ' },{ key: 'lat', label: 'Vĩ độ' }, { key: 'lng', label: 'Kinh độ' },{key:'actions',label:'Quản lý',render:fleet.actions}]} /></div>)
}
