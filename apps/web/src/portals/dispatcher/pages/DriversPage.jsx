import {useFleetManagement} from '../components/FleetManagement.jsx'
import { dispatchDate } from '../../../shared/lib/workday.js'
import { useEffect, useState } from 'react'
import { useApi } from '../../../shared/api/client.js'
import { DataTable, PageHead, Tag, DRIVER_STATUS } from '../../../shared/components/ui.jsx'

export default function DriversPage() {
  const [date, setDate] = useState(dispatchDate), [customDate,setCustomDate] = useState(false)
  const workday=useApi('/api/workday',5000)
  useEffect(()=>{if(!customDate && workday.data?.date)setDate(workday.data.date)},[workday.data?.date,customDate])
  const {data,error,reload}=useApi(`/api/drivers?date=${date}`,5000)
  const fleet=useFleetManagement('drivers',reload)
  const schedule = useApi(`/api/work-schedules?date=${date}`, 5000)
  const rows = data && schedule.data ? data.map(d => ({ ...d, schedule: schedule.data.find(s => s.id === d.id) })) : null
  return (<div className="page"><PageHead title="Tài xế & lịch làm">{fleet.toolbar}</PageHead>{fleet.controls}{fleet.ui}
    <div className="card"><label>Xem ngày <input type="date" value={date} onChange={e => { if (e.target.value) {setCustomDate(true);setDate(e.target.value)} }} /></label></div>
    <DataTable error={error || schedule.error} rows={fleet.filter(rows)} columns={[{ key: 'code', label: 'Mã' }, { key: 'name', label: 'Họ tên' }, { key: 'phone', label: 'Điện thoại' },
      { key: 'warehouse', label: 'Kho' }, { key: 'vehicle', label: 'Xe', render: (r) => r.vehicle || '–' }, { key: 'shift', label: 'Ca hồ sơ' },
      { key: 'registered', label: 'Lịch đăng ký', render: r => r.schedule?.working ? `${r.schedule.shift_start}–${r.schedule.shift_end}` : r.schedule?.working === false ? 'Đăng ký nghỉ' : 'Chưa đăng ký' },
      { key: 'trip', label: 'Chuyến đã nhận', render: r => r.trip ? <><b>{r.trip}</b><small className="block">Ngày {r.trip_date} · {r.vehicle}</small></> : 'Chưa nhận chuyến' },
      { key: 'busy', label: 'Phân công', render: r => r.archived_at?<span className="tag gray">Ngừng sử dụng</span>:<Tag map={DRIVER_STATUS} value={r.status || r.schedule?.dispatch_status} /> },{key:'actions',label:'Quản lý',render:fleet.actions}]} /></div>)
}
