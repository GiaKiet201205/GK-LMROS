import DriverPayment from './components/DriverPayment.jsx'
import {needsCollection} from '../../shared/lib/payments.js'
import { useEffect, useRef, useState } from 'react'
import { Home, Route, User, Navigation, Check, Warehouse, Package, Calendar, Bell, Wrench, MoreHorizontal, X } from 'lucide-react'
import { useAuth } from '../../shared/auth/AuthProvider.jsx'
import { api, nf, useApi } from '../../shared/api/client.js'
import Brand from '../../shared/components/Brand.jsx'
import JourneyTimeline from '../../shared/components/JourneyTimeline.jsx'
import WeeklySchedule from './components/WeeklySchedule.jsx'
import MaintenanceWorkspace from '../../shared/components/MaintenanceWorkspace.jsx'
import DriverNotifications from './components/DriverNotifications.jsx'
import { directionLinks, pointLabel } from '../../shared/lib/maps.js'

const read = key => { try { return JSON.parse(localStorage.getItem(key)) || [] } catch { return [] } }
const write = (key, value) => localStorage.setItem(key, JSON.stringify(value))
export default function DriverApp() {
  const { user, logout } = useAuth()
  const feed = useApi('/api/driver/notifications', 5000)
  const lastNotification = useRef(null)
  const [data, setData] = useState(null), [tab, setTab] = useState('today'), [error, setError] = useState(''), [notice, setNotice] = useState('')
  const [online, setOnline] = useState(navigator.onLine), [pending, setPending] = useState(0), [busy, setBusy] = useState(false)
  const [position, setPosition] = useState(null), [reason, setReason] = useState(''), [failing, setFailing] = useState(false)
  const [moreOpen,setMoreOpen]=useState(false),moreDialog=useRef(null),moreTrigger=useRef(null)
  useEffect(()=>{const dialog=moreDialog.current;if(moreOpen&&!dialog.hasAttribute('open')){if(dialog.showModal)dialog.showModal();else{dialog.setAttribute('open','');dialog.querySelector('button')?.focus()}}else if(!moreOpen&&dialog.hasAttribute('open')){if(dialog.close)dialog.close();else{dialog.removeAttribute('open');moreTrigger.current?.focus()}}},[moreOpen])
  useEffect(()=>{const media=window.matchMedia('(min-width:700px)'),change=()=>{if(media.matches)setMoreOpen(false)};if(media.addEventListener){media.addEventListener('change',change);return()=>media.removeEventListener('change',change)}media.addListener(change);return()=>media.removeListener(change)},[])
  const changeTab=key=>{setTab(key);setMoreOpen(false);setFailing(false);window.scrollTo({top:0,behavior:'instant'})}
  const version = useRef(null), flushing = useRef(false), gpsAt = useRef(0)
  const actionKey = `gk_outbox_${user.id}`, gpsKey = `gk_gps_${user.id}`
  useEffect(() => {
    for (const [old, key] of [[`vr_outbox_${user.id}`, actionKey], [`vr_gps_${user.id}`, gpsKey]]) {
      if (!localStorage.getItem(key) && localStorage.getItem(old)) { write(key, read(old)); localStorage.removeItem(old) }
    }
  }, [user.id])
  useEffect(() => {
    const n=feed.data?.items?.[0]
    if(n && lastNotification.current!==null && n.id>lastNotification.current && !n.read_at) setNotice(`${n.title} · ${n.message}`)
    if(feed.data) lastNotification.current=n?.id || 0
  }, [feed.data])
  async function refresh() {
    try {
      const response = await api('/api/driver/today')
      const signature = response.currentTrip ? `${response.currentTrip.id}:${response.currentTrip.route_version}` : null
      if (version.current && signature && version.current !== signature) setNotice('Tuyến còn lại đã cập nhật. Hãy mở lại Google Maps trước khi đi tiếp.')
      version.current = signature
      setData(response); setError('')
    } catch (e) { setError(e.message) }
    setPending(read(actionKey).length + read(gpsKey).length)
  }
  async function flush() {
    if (flushing.current) return
    flushing.current = true
    try {
      for (const action of read(actionKey)) {
        try { await api(`/api/driver/stops/${action.stopId}/${action.type}`, { method: 'POST', body: action.payload || {} }); write(actionKey, read(actionKey).filter(a => a.id !== action.id)) }
        catch (e) { setError(`Thao tác vẫn được giữ trên máy: ${e.message}`); break }
      }
      const points = read(gpsKey)
      if (points.length) {
        try { await api('/api/driver/gps', { method: 'POST', body: points }); write(gpsKey, read(gpsKey).filter(p => !points.some(sent => sent.ts === p.ts))) } catch { /* keep queued positions */ }
      }
      setPending(read(actionKey).length + read(gpsKey).length)
    } finally { flushing.current = false }
  }
  useEffect(() => {
    refresh()
    const timer = setInterval(() => { if (navigator.onLine) flush().then(refresh) }, 5000)
    const on = () => { setOnline(true); flush().then(refresh) }, off = () => setOnline(false)
    window.addEventListener('online', on); window.addEventListener('offline', off)
    return () => { clearInterval(timer); window.removeEventListener('online', on); window.removeEventListener('offline', off) }
  }, [user.id, user.vehicle_code])
  useEffect(() => {
    if (!navigator.geolocation || data?.driver?.status !== 'on_duty') return
    const watcher = navigator.geolocation.watchPosition(p => {
      const point = { lat: p.coords.latitude, lng: p.coords.longitude, speed: (p.coords.speed || 0) * 3.6, ts: Date.now(), vehicleCode: data.vehicle.code }
      setPosition(point)
      if (Date.now() - gpsAt.current < 15000) return
      gpsAt.current = Date.now(); write(gpsKey, [...read(gpsKey), point]); if (navigator.onLine) flush()
    }, () => {}, { enableHighAccuracy: true, maximumAge: 10000 })
    return () => navigator.geolocation.clearWatch(watcher)
  }, [data?.driver?.status, data?.vehicle?.code])
  const trip = data?.currentTrip
  const queued = read(actionKey)
  const stops = (trip?.stopList || []).map(s => {
    const action = queued.find(a => a.stopId === s.stop_id)
    return action ? { ...s, state: action.type === 'complete' ? 'completed' : 'failed', queued: true } : s
  })
  const remaining = stops.filter(s => ['pending', 'en_route'].includes(s.state)), next = remaining[0]
  const origin = position || data?.position || (trip?.status === 'planned' ? data?.vehicle : undefined)
  async function act(type, stop, payload = {}) {
    if(type==='fail'&&(!String(payload.reason||'').trim()||String(payload.reason).length>200)){setError('Nhập lý do từ 1 đến 200 ký tự.');return}
    setBusy(true); setError('')
    try {
      if (!navigator.onLine) {
        if (!['complete', 'fail'].includes(type)) throw new Error('Cần kết nối mạng để bắt đầu hoặc khóa điểm đang đi')
        write(actionKey, [...read(actionKey), { id: Date.now(), stopId: stop.stop_id, type, payload }]); setPending(read(actionKey).length + read(gpsKey).length)
        setNotice('Đã lưu trên thiết bị. Hệ thống chỉ xác nhận sau khi đồng bộ.')
      } else {
        await api(`/api/driver/stops/${stop.stop_id}/${type}`, { method: 'POST', body: payload })
        setNotice(stop.kind === 'warehouse' ? 'Đã xác nhận về kho. Điều phối có thể duyệt chuyến giao tiếp theo.' : 'Đã cập nhật điểm dừng và lưu thông báo trong hệ thống.')
        await refresh()
      }
      setFailing(false)
    } catch (e) { setError(e.message) } finally { setBusy(false) }
  }
  async function navigate(url) {
    const windowRef = window.open('', '_blank')
    if (windowRef) windowRef.opener = null
    try {
      await api(`/api/driver/stops/${next.stop_id}/depart`, { method: 'POST', body: {} })
      if (windowRef) windowRef.location.href = url
      await refresh()
    } catch (e) { windowRef?.close(); setError(e.message) }
  }
  const phase = trip?.phase === 'pickup' ? 'Lấy hàng' : trip?.phase === 'return' ? 'Hoàn hàng' : 'Giao hàng'
  return <div className="phone driver-app"><div className="driver-brand"><Brand compact /><span>KHÔNG GIAN TÀI XẾ</span></div>
    <div className="phone-top"><div><h1>{tab === 'maintenance' ? 'Chi phí & bảo dưỡng' : tab === 'notifications' ? 'Thông báo' : tab === 'schedule' ? 'Lịch làm việc' : tab === 'account' ? 'Tài khoản' : tab === 'route' ? 'Tuyến đường' : 'Hành trình hôm nay'}</h1><small>{data?.driver?.name || user.display_name}</small></div>
      <div className="driver-header-actions"><button type="button" className="driver-bell" aria-label={feed.data?.unread?`Thông báo, ${feed.data.unread} chưa đọc`:'Thông báo tài xế'} onClick={()=>changeTab('notifications')}><Bell size={20}/>{feed.data?.unread>0&&<span>{feed.data.unread>99?'99+':feed.data.unread}</span>}</button><button className="p-btn ghost driver-header-logout" style={{ width: 'auto' }} onClick={() => logout().catch(e => setError(e.message))}>Đăng xuất</button></div></div>
    <main className="phone-body">
      {error && <div className="callout red" role="alert">{error}</div>}
      {notice && <div className="callout blue driver-notice" role="status"><span>{notice}</span><button type="button" aria-label="Đóng thông báo trạng thái" onClick={()=>setNotice('')}><X size={18}/></button></div>}
      {trip?.status === 'in_progress' && !position && !data?.position && <div className="callout amber">Chưa có GPS mới. Cho phép truy cập vị trí để tính tuyến từ vị trí xe hiện tại.</div>}
      {(!online || pending > 0) && <div className="callout amber">{online ? 'Đang kết nối' : 'Đang ngoại tuyến'} · {pending} cập nhật chờ gửi. <button className="btn ghost" disabled={!online} onClick={() => flush().then(refresh)}>Đồng bộ</button></div>}
      {!data && !error && <div className="empty">Đang tải hành trình…</div>}
      {tab === 'maintenance' ? <MaintenanceWorkspace driverMode onChange={()=>{refresh();feed.reload()}} /> : tab === 'notifications' ? <DriverNotifications feed={{...feed,onError:setError}} /> : tab === 'schedule' ? <WeeklySchedule /> : tab === 'account' && data ? <>
        <div className="pcard"><h2>{data.driver?.name}</h2><dl className="kv"><dt>Tài khoản</dt><dd>{user.username}</dd><dt>Điện thoại</dt><dd>{data.driver?.phone}</dd><dt>Phương tiện</dt><dd>{data.vehicle ? `${data.vehicle.code} · ${data.vehicle.plate}` : 'Chưa gán xe'}</dd><dt>Kho</dt><dd>{data.vehicle?.warehouse || data.driver?.warehouse || '–'}</dd><dt>Ca hôm nay</dt><dd>{data.driver?.registered_working ? `${data.driver.shift_start}–${data.driver.shift_end}` : data.driver?.registered_working === false ? 'Đăng ký nghỉ' : 'Chưa đăng ký lịch'}</dd></dl><button className="btn ghost" onClick={()=>setTab('maintenance')}>Chi phí và bảo dưỡng xe</button></div>
      <DriverPayrolls/><button className="p-btn ghost driver-account-logout" onClick={()=>logout().catch(e=>setError(e.message))}>Đăng xuất</button></> : <>
        {trip ? <section className="driver-trip-layout" data-trip-state={trip.status}>
          <div className="pcard driver-trip-summary"><div className="row"><h2><Package size={16} /> {phase}</h2><span className="tag blue">{trip.status === 'planned' ? 'Chờ xuất phát' : 'Đang hoạt động'}</span></div>
            <p>{trip.code}</p><p className="muted">Ngày xuất phát {trip.work_date || ''} · ca 08:00–20:00</p>{trip.status === 'planned' && trip.start_block_reason && <p className="callout amber">{trip.start_block_reason}</p>}<p className="muted">{stops.length - remaining.length}/{stops.length} điểm hoàn tất · {nf(trip.remaining_km, 1)} km còn lại</p>
            <p className="muted">Cập nhật {new Date(trip.route_updated_at).toLocaleTimeString('vi-VN')}</p>
            {trip.status === 'planned' && <button className="p-btn" disabled={!online || busy || trip.can_start === false} onClick={async () => { setBusy(true); try { await api(`/api/driver/trips/${trip.id}/start`, { method: 'POST', body: {} }); setNotice('Đã bắt đầu chuyến.'); await refresh(); feed.reload() } catch (e) { setError(e.message) } finally { setBusy(false) } }}>Bắt đầu chuyến {trip.phase === 'pickup' ? 'lấy' : trip.phase === 'return' ? 'hoàn' : 'giao'}</button>}
          </div>

          {trip.status === 'in_progress' && next && <div className="pcard next driver-next-stop"><span className="tag blue">Tiếp theo · {pointLabel(next)}</span><h2>{next.customer_name}</h2><p>{next.address}</p>{next.goods_description&&<p><b>Hàng hóa:</b> {next.goods_description}</p>}
            {next.kind !== 'warehouse' && <p className="muted">Khung giờ {next.tw_start}–{next.tw_end} · {nf(next.weight_kg, 1)} kg</p>}
            {next.kind!=='warehouse'&&<DriverPayment key={next.stop_id} stop={next} online={online} onSaved={refresh}/>}<div className="route-actions"><button className="p-btn" disabled={!online || busy} onClick={() => navigate(directionLinks([next], origin)[0].url)}><Navigation size={16} />Đi tới điểm này</button>
              {next.phone && <a className="p-btn ghost" href={`tel:${next.phone}`}>Gọi khách</a>}
              <button className="p-btn" disabled={busy||needsCollection(next)} onClick={() => act('complete', next)}><Check size={16} />{next.kind === 'warehouse' ? 'Đã về kho / bàn giao hàng' : next.kind === 'pickup' ? 'Đã nhận hàng' : next.kind === 'return' ? 'Đã trả hàng cho người gửi' : 'Đã giao hàng'}</button>
              {next.kind !== 'warehouse' && <button className="p-btn ghost" disabled={busy} onClick={() => setFailing(v => !v)}>Không hoàn tất được</button>}
            </div>
            {failing && <div className="field"><label htmlFor="failure-reason">Lý do</label><textarea id="failure-reason" required maxLength={200} value={reason} onChange={e => setReason(e.target.value)} /><button className="btn" disabled={!reason.trim() || busy} onClick={() => act('fail', next, { reason })}>Lưu lý do</button></div>}
          </div>}
          {tab === 'route' && <div className="pcard driver-route-history"><h2>Hành trình chuyến</h2><JourneyTimeline stops={stops} /></div>}
        </section> : data && <div className="pcard center"><Warehouse size={28} /><h2>{data.vehicle?.physical_status === 'maintenance' ? 'Xe đang bảo dưỡng' : 'Đang chờ chuyến tiếp theo'}</h2>{data.vehicle?.physical_status === 'maintenance' && <button className="btn" onClick={()=>setTab('maintenance')}>Cập nhật bảo dưỡng</button>}</div>}
      </>}
    </main><nav className="tabbar" aria-label="Điều hướng tài xế">{[['today','Hôm nay',Home],['route','Tuyến đường',Route],['schedule','Lịch tuần',Calendar],['maintenance','Chi phí xe',Wrench],['notifications','Thông báo',Bell],['account','Tài khoản',User]].map(([key,label,Icon])=><button type="button" key={key} data-tab={key} className={tab===key?'on':''} aria-current={tab===key?'page':undefined} onClick={()=>changeTab(key)}><Icon size={21}/><span>{label}</span>{key==='notifications'&&feed.data?.unread>0&&<i className="driver-nav-count">{feed.data.unread>99?'99+':feed.data.unread}</i>}</button>)}<button type="button" ref={moreTrigger} className={`driver-more-trigger${['maintenance','notifications','account'].includes(tab)?' on':''}`} aria-label="Thêm tiện ích" aria-haspopup="dialog" aria-expanded={moreOpen} onClick={()=>setMoreOpen(true)}><MoreHorizontal size={22}/><span>Thêm</span>{feed.data?.unread>0&&<i className="driver-more-dot"/>}</button></nav>
    <dialog ref={moreDialog} className="driver-more-panel" onKeyDown={e=>{if(e.key==='Escape')setMoreOpen(false)}} onClose={()=>setMoreOpen(false)} onCancel={()=>setMoreOpen(false)} onClick={e=>{if(e.target===e.currentTarget)setMoreOpen(false)}} aria-labelledby="driver-more-title"><header><h2 id="driver-more-title">Tiện ích</h2><button type="button" aria-label="Đóng tiện ích" onClick={()=>setMoreOpen(false)}><X size={22}/></button></header><div>{[['maintenance','Chi phí xe',Wrench],['notifications','Thông báo',Bell],['account','Tài khoản',User]].map(([key,label,Icon])=><button type="button" key={key} onClick={()=>changeTab(key)}><Icon size={22}/><span>{label}</span>{key==='notifications'&&feed.data?.unread>0&&<b>{feed.data.unread>99?'99+':feed.data.unread}</b>}</button>)}</div></dialog>
   </div>
}

function DriverPayrolls(){
 const {data,error,reload}=useApi('/api/finance/mine',15000)
 return <section className="pcard"><h2>Lương của tôi</h2>{error?<div className="callout red">{error.message}<button className="btn ghost" onClick={reload}>Thử lại</button></div>:!data?<p>Đang tải bảng lương…</p>:!data.length?<p>Chưa có bảng lương được duyệt.</p>:data.map(p=><details key={p.id} className="driver-payroll"><summary>Tháng {String(p.period).slice(0,7).split('-').reverse().join('/')} · <b>{nf(p.net_amount)} đ</b></summary><dl className="kv"><dt>Ngày tính lương</dt><dd>{p.worked_days} ngày</dd><dt>{p.salary_mode==='fixed'?'Lương cố định tháng':'Đơn giá ngày'}</dt><dd>{nf(p.salary_mode==='fixed'?p.monthly_salary:p.daily_rate)} đ</dd><dt>Phụ cấp</dt><dd>{nf(p.allowance)} đ</dd><dt>Thưởng</dt><dd>{nf(p.bonus)} đ</dd><dt>Khấu trừ</dt><dd>{nf(p.deduction)} đ</dd><dt>Thực nhận</dt><dd><b>{nf(p.net_amount)} đ</b></dd></dl>{p.adjustment_reason&&<p>{p.adjustment_reason}</p>}</details>)}</section>
}
