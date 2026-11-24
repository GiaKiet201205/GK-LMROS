import {useFleetManagement} from '../components/FleetManagement.jsx'
import { Link } from 'react-router-dom'
import { useApi } from '../../../shared/api/client.js'
import { DataTable, PageHead, Tag, VEHICLE_STATUS } from '../../../shared/components/ui.jsx'

export default function VehiclesPage() {
  const {data,error,reload}=useApi('/api/vehicles',3000)
  const fleet=useFleetManagement('vehicles',reload)
  return (<div className="page"><PageHead title="Đội xe">{fleet.toolbar}<Link className="btn ghost" to="/maintenance">Quản lý bảo dưỡng</Link></PageHead>{fleet.controls}{fleet.ui}
    <DataTable error={error} rows={fleet.filter(data)} columns={[{ key: 'code', label: 'Mã xe' }, { key: 'plate', label: 'Biển số' }, { key: 'warehouse', label: 'Kho' },
      { key: 'capacity_kg', label: 'Tải (kg)' }, { key: 'capacity_m3', label: 'Thể tích (m³)' }, { key: 'driver', label: 'Tài xế' },
      { key: 'trip', label: 'Chuyến đã nhận', render: r => r.trip ? <>{r.trip}<small className="block">Ngày {r.trip_date}</small></> : 'Chưa nhận chuyến' },
      { key: 'maintenance', label: 'Bảo dưỡng', render: r => r.maintenance_id ? <Link to="/maintenance">{r.maintenance_state === 'pending_approval' ? 'Chờ duyệt phiếu sửa' : r.maintenance_state === 'awaiting_return' ? 'Chờ bàn giao' : 'Đang bảo dưỡng'}</Link> : '–' },
      { key: 'status', label: 'Trạng thái', render: (r) => r.archived_at?<span className="tag gray">Ngừng sử dụng</span>:<Tag map={VEHICLE_STATUS} value={r.status} /> },{key:'actions',label:'Quản lý',render:fleet.actions}]} /></div>)
}
