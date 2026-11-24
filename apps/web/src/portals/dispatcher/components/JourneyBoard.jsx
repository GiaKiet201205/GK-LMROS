import { filterJourneys } from '../../../shared/lib/journey-filter.js'
import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Truck, Navigation, Package, Warehouse, RefreshCw } from 'lucide-react'
import { useApi, nf } from '../../../shared/api/client.js'
import JourneyTimeline from '../../../shared/components/JourneyTimeline.jsx'
import NavigationSegments from '../../../shared/components/NavigationSegments.jsx'
function CompactVehicleJourney({ vehicle, updates }) {
  const trip = vehicle.trips[0], stops = trip.stops || [], done = stops.filter(s => ['completed', 'failed'].includes(s.state)).length
  const next = stops.find(s => ['pending', 'en_route'].includes(s.state)), update = updates[trip.id]
  return <article className={`card fleet-mini-card${trip.status === 'in_progress' ? ' active' : ''}`}><header><span className="vehicle-icon"><Truck size={20} /></span><div><h3>{vehicle.code}</h3><p>{vehicle.plate}</p></div><span className={`tag ${trip.status === 'in_progress' ? 'green' : 'gray'}`}>{trip.status === 'in_progress' ? 'Đang chạy' : 'Chờ xuất phát'}</span></header><p className="fleet-mini-driver">{trip.driver || 'Chưa phân tài xế'} · {trip.phase === 'pickup' ? 'Lấy hàng' : trip.phase === 'return' ? 'Hoàn hàng' : 'Giao hàng'}</p>
    <div className="fleet-mini-target"><small>{next?.state === 'en_route' ? 'ĐANG ĐI TỚI' : 'ĐIỂM TIẾP THEO'}</small><strong>{next?.customer_name || 'Đã xử lý các điểm'}</strong><p>{next?.address || trip.warehouse}</p></div>
    <div className="fleet-mini-progress"><span>{done}/{stops.length} điểm đã xử lý</span><b>{nf(trip.remaining_km, 1)} km còn lại</b></div><div className="journey-progress" role="progressbar" aria-label={`Tiến độ ${vehicle.code}`} aria-valuemin={0} aria-valuemax={stops.length || 1} aria-valuenow={done}><span style={{ width: `${stops.length ? done / stops.length * 100 : 0}%` }} /></div>
    <footer><span>{update && Date.now() - update.at < 180000 ? 'Hành trình vừa cập nhật' : trip.warehouse}</span><Link to={`/live?vehicle=${vehicle.id}`}>Xem hành trình</Link></footer></article>
}
function VehicleJourney({ vehicle, compact, updates }) {
  const [selected, setSelected] = useState(null), [expanded, setExpanded] = useState(false)
  const trip = vehicle.trips.find(t => t.id === selected) || vehicle.trips[0]
  const stops = trip.stops || [], done = stops.filter(s => ['completed', 'failed'].includes(s.state)).length
  const successful = stops.filter(s => s.state === 'completed' && s.kind !== 'warehouse').length
  const next = stops.find(s => ['pending', 'en_route'].includes(s.state)), last = stops.filter(s => ['completed', 'failed'].includes(s.state)).at(-1)
  const update = updates[trip.id]
  const recent = update && Date.now() - update.at < 180000
  const origin = trip.status === 'planned' ? stops.find(s => s.kind === 'warehouse')
    : trip.gps_age_s != null && trip.gps_age_s < 300 ? { lat: trip.position_lat, lng: trip.position_lng } : last
  return <article className={`vehicle-journey${trip.status === 'in_progress' ? ' is-active' : ''}`}>
    <header className="vehicle-journey-head"><div className="vehicle-identity"><span className="vehicle-icon"><Truck size={21} /></span><div><h3>{vehicle.code}</h3><p>{vehicle.plate} · {trip.driver || 'Chưa có tài xế'}{trip.driver_code && ` (${trip.driver_code})`}</p></div></div>
      <div className="journey-badges"><span className={`tag ${trip.status === 'in_progress' ? 'green' : 'gray'}`}>{trip.status === 'in_progress' ? 'Đang hoạt động' : 'Chờ xuất phát'}</span><span className="tag blue">{trip.phase === 'pickup' ? 'Chuyến lấy hàng' : trip.phase === 'return' ? 'Chuyến hoàn hàng' : 'Chuyến giao hàng'}</span></div></header>
    {recent && <div className="journey-update" role="status"><RefreshCw size={15} />{update.isNew ? 'Vừa được phân chuyến mới' : update.added.length ? `Tuyến vừa cập nhật · thêm ${update.added.length} điểm` : 'Thứ tự hành trình vừa được cập nhật'}</div>}
    {!compact && vehicle.trips.length > 1 && <div className="trip-switches" aria-label={`Các chuyến của ${vehicle.code}`}>{vehicle.trips.map(t => <button type="button" key={t.id} className={`btn ghost${trip.id === t.id ? ' selected' : ''}`} onClick={() => setSelected(t.id)}>{t.phase === 'pickup' ? 'Lấy' : t.phase === 'return' ? 'Hoàn' : 'Giao'} · {t.code}{t.status === 'in_progress' ? ' · đang chạy' : ' · chờ'}</button>)}</div>}
    <div className="journey-overview">{trip.work_date && <span>Ngày {trip.work_date.split('-').reverse().join('/')}</span>}<span><Package size={15} />{successful} điểm {trip.phase === 'pickup' ? 'đã lấy' : trip.phase === 'return' ? 'đã hoàn' : 'đã giao'}</span><span>{done}/{stops.length} điểm đã xử lý</span><span>{nf(trip.remaining_km, 1)} km còn lại theo lần tối ưu</span></div>
    <div className="journey-progress" role="progressbar" aria-label="Tiến độ chuyến" aria-valuemin={0} aria-valuemax={stops.length || 1} aria-valuenow={done}><span style={{ width: `${stops.length ? done / stops.length * 100 : 0}%` }} /></div>
    <div className="journey-current"><Navigation size={18} /><div><small>{next?.state === 'en_route' ? 'ĐANG ĐI TỚI' : trip.status === 'planned' ? 'ĐIỂM ĐẦU KHI XUẤT PHÁT' : 'ĐIỂM TIẾP THEO'}</small><strong>{next?.customer_name || 'Đã xử lý các điểm'}</strong>{next && <p>{next.address}</p>}</div></div>
    <JourneyTimeline stops={stops} compact={compact || !expanded} highlightedIds={recent ? update.added : []} />
    <footer className="journey-footer"><div className="toolbar">{!compact && stops.length > 4 && <button type="button" className="btn ghost" onClick={() => setExpanded(v => !v)}>{expanded ? 'Thu gọn' : `Xem đủ ${stops.length} điểm`}</button>}
      {compact && <Link className="btn ghost" to={`/live?vehicle=${vehicle.id}`}>Xem hành trình</Link>}
    </div><small>{trip.warehouse} · cập nhật {new Date(trip.route_updated_at).toLocaleTimeString('vi-VN')}{compact && vehicle.trips.length > 1 && ` · còn ${vehicle.trips.length - 1} chuyến khác`}</small></footer>
    {!compact && <NavigationSegments stops={stops} origin={origin} />}
  </article>
}
export default function JourneyBoard({ warehouse = '', compact = false, vehicleId }) {
  const [phase,setPhase]=useState('all')
  const params=new URLSearchParams({...(warehouse?{warehouse}:{}),...(phase!=='all'?{phase}:{})})
  const { data, error } = useApi(`/api/journeys?${params}`, 3000)
  const [filter, setFilter] = useState(compact || vehicleId ? 'all' : 'active'), [query, setQuery] = useState(''), [updates, setUpdates] = useState({})
  const previous = useRef(null)
  useEffect(() => { if (vehicleId) setFilter('all') }, [vehicleId])
  useEffect(() => {
    if (!data) return
    const current = new Map(), changed = {}
    for (const vehicle of data.vehicles) for (const trip of vehicle.trips) {
      const ids = trip.stops.map(s => s.stop_id), old = previous.current?.get(trip.id)
      current.set(trip.id, { version: trip.route_version, ids })
      if (previous.current && (!old || old.version !== trip.route_version)) changed[trip.id] = { at: Date.now(), isNew: !old, added: old ? ids.filter(id => !old.ids.includes(id)) : [] }
    }
    if (Object.keys(changed).length) setUpdates(values => ({ ...values, ...changed }))
    previous.current = current
  }, [data])
  useEffect(() => { previous.current = null; setUpdates({}) }, [warehouse,phase])
  const vehicles = filterJourneys(data?.vehicles||[],{phase,state:filter,vehicleId,query})
  const shown = compact ? vehicles.slice(0, 3) : vehicles
  return <section className={`journey-board${compact ? ' compact' : ''}`} aria-label="Hành trình từng xe">
    <div className="journey-board-heading"><div><h2>{compact ? 'Hành trình đội xe' : 'Hành trình từng xe'}</h2><p>Tự cập nhật mỗi 3 giây · {data?.vehicles?.filter(v => v.trips.some(t => t.status === 'in_progress')).length || 0} xe đang chạy</p></div>{compact && <Link to="/live">Xem tất cả hành trình</Link>}</div>
    {!compact && <div className="toolbar journey-filters"><label>Loại chuyến<select value={phase} onChange={e=>setPhase(e.target.value)}><option value="all">Tất cả lấy / giao / hoàn</option><option value="pickup">Lấy hàng</option><option value="delivery">Giao hàng</option><option value="return">Hoàn hàng</option></select></label><label>Tình trạng<select value={filter} onChange={e => setFilter(e.target.value)}><option value="active">Đang hoạt động</option><option value="planned">Chờ xuất phát</option><option value="all">Tất cả chuyến đang mở</option></select></label><label>Tìm xe / tài xế<input value={query} onChange={e => setQuery(e.target.value)} placeholder="Mã xe, biển số hoặc tên tài xế" /></label>{vehicleId && <Link to="/live">Bỏ lọc xe đã chọn</Link>}</div>}
    {error && <div className="callout red" role="alert">{error.message}</div>}
    {!compact && data?.waiting > 0 && <div className="journey-queue"><Warehouse size={17} /><span><b>{nf(data.waiting)} đơn</b> đang chờ phân phối lấy, giao hoặc hoàn hàng.</span></div>}
    {!data && !error && <p className="empty">Đang tải hành trình…</p>}
    {data && !shown.length && <p className="empty">{compact ? 'Chưa có hành trình được phân. Các chuyến mới sẽ xuất hiện tại đây khi đủ điều kiện.' : 'Chưa có xe phù hợp với bộ lọc. Chọn “Chờ xuất phát” hoặc “Tất cả chuyến đang mở” để xem các chuyến được phân.'}</p>}
    <div className="vehicle-journey-list">{shown.map(v => compact ? <CompactVehicleJourney key={v.id} vehicle={v} updates={updates} /> : <VehicleJourney key={v.id} vehicle={v} compact={false} updates={updates} />)}</div>
    {compact && vehicles.length > shown.length && <div className="fleet-more"><span>Đang hiển thị {shown.length}/{vehicles.length} xe có chuyến mở</span><Link to="/live">Xem thêm {vehicles.length - shown.length} xe</Link></div>}
  </section>
}
