import TableScroll from '../../../shared/components/TableScroll.jsx'
import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, useApi, nf } from '../../../shared/api/client.js'
import { PageHead } from '../../../shared/components/ui.jsx'
import { useWarehouseFilter as useFilter } from '../context/WarehouseFilterContext.js'
import { dispatchDate } from '../../../shared/lib/workday.js'
const localMinute = value => Number.isFinite(Number(value)) ? new Date(Number(value)*60000).toLocaleString('vi-VN',{timeZone:'Asia/Ho_Chi_Minh',hour:'2-digit',minute:'2-digit',day:'2-digit',month:'2-digit',year:'numeric'}) : ''
const reason = item => ({scheduled_later:'Đơn hẹn sau ngày đang chọn. Chọn đúng ngày hẹn để phân chuyến.',missing_location:'Chưa có tọa độ lấy/giao hợp lệ. Cần xác nhận lại địa chỉ.',missing_appointment:'Thiếu khung giờ hẹn. Cần cập nhật lịch lấy/giao.',batch_limit:'Đơn sẽ được xét ở lượt tiếp theo do mỗi lượt tối đa 500 đơn.',order_deadline_passed:`Đã quá hạn hẹn ${localMinute(item.deadline_min??item.window_end_min)}. Cần đổi lịch hẹn trước khi phân chuyến.`,time_budget:'Hết thời gian tính; thử lại hoặc giảm số đơn.',weight_capacity:'Tổng khối lượng vượt tải trọng xe.',volume_capacity:'Tổng thể tích vượt sức chứa xe.',order_limit:'Xe đã đạt giới hạn số đơn/điểm trong chuyến.',time_window_unreachable:'Xe không thể đến kịp giờ hẹn theo tuyến hiện tại.',shift_end_exceeded:'Không đủ thời gian xử lý và về kho trước khi kết thúc ca.',road_unreachable:'Không tìm được đường ô tô tới một điểm trên tuyến.',existing_route_infeasible:'Tuyến hiện tại đã không đáp ứng lịch hẹn hoặc giờ kết thúc ca.',invalid_onboard_load:'Khối lượng hàng trên xe không khớp các điểm lấy/giao.',no_eligible_route:'Không có tuyến cùng kho và đúng loại lấy/giao/hoàn để nhận đơn.',multiple_constraints:'Các phương án chèn đơn vướng nhiều ràng buộc tải trọng, lịch hẹn hoặc giờ về kho.'}[item.reason]||'Không đủ xe, tải trọng hoặc thời gian để xử lý đơn này.')
export default function Distribution() {
  const [params, setParams] = useSearchParams()
  const phase = ['pickup','return'].includes(params.get('phase')) ? params.get('phase') : 'delivery'
  const title = { pickup: 'Phân phối lấy hàng', delivery: 'Phân phối giao hàng', return: 'Phân phối hoàn hàng' }[phase]
  const action = { pickup: 'lấy', delivery: 'giao', return: 'hoàn' }[phase]
  const workday = useApi('/api/workday', 5000)
  const filter = useFilter(), { data: warehouses } = useApi('/api/warehouses')
  const [warehouse, setWarehouse] = useState(params.get('warehouse') || filter.warehouse || ''), [date, setDate] = useState(dispatchDate), [maxOrders, setMaxOrders] = useState(45)
  const [chosen, setChosen] = useState({}), [plan, setPlan] = useState(null), [busy, setBusy] = useState(false), [message, setMessage] = useState(null)
  const { data, error, reload } = useApi(warehouse ? `/api/distribution/resources?date=${date}&warehouse=${warehouse}&phase=${phase}` : null, 5000)
  useEffect(() => { if (!warehouse && warehouses?.length) setWarehouse(String(warehouses[0].id)) }, [warehouses])
  useEffect(() => { setChosen({}); setPlan(null); setMessage(null) }, [warehouse, date, phase])
  useEffect(() => { if (filter.warehouse && !params.get('warehouse')) setWarehouse(filter.warehouse) }, [filter.warehouse, params])
  useEffect(() => { if (workday.data?.date && date < workday.data.date) setDate(workday.data.date) }, [workday.data?.date, date])
  function select(vehicle, driver) { setChosen(values => ({ ...values, [vehicle]: driver })); setPlan(null); setMessage(null) }
  async function preview() {
    setBusy(true); setMessage(null); setPlan(null)
    try { setPlan(await api('/api/distribution/preview', { method: 'POST', body: { phase, date, warehouse: Number(warehouse), maxOrders: Number(maxOrders), assignments: Object.entries(chosen).filter(([, driver]) => driver).map(([vehicle, driver]) => ({ vehicleId: Number(vehicle), driverId: Number(driver) })) } })) }
    catch (e) { setMessage({ ok: false, text: e.message }) } finally { setBusy(false) }
  }
  async function confirm() {
    setBusy(true); setMessage(null)
    try { const result = await api(`/api/distribution/${plan.id}/confirm`, { method: 'POST', body: {} }); setMessage({ ok: true, text: result.alreadyConfirmed ? 'Phương án này đã được phân công trước đó. Đã tải lại tình trạng xe và đơn.' : `Phân công thành công: ${result.trips || ''} chuyến, ${result.orders || ''} đơn. Xe và tài xế đã được giữ chỗ, chờ xuất phát.` }); setPlan(null); setChosen({}); reload() }
    catch (e) { setMessage({ ok: false, text: e.message }); if (e.status === 409) setPlan(null) } finally { setBusy(false) }
  }
  async function availability(vehicle) {
    setBusy(true); setMessage(null)
    try { await api(`/api/vehicles/${vehicle.id}/availability`, { method: 'POST', body: { date, available: !vehicle.available, note: '' } }); select(vehicle.id, ''); reload() }
    catch (e) { setMessage({ ok: false, text: e.message }) } finally { setBusy(false) }
  }
  async function release(trip) {
    setBusy(true); setMessage(null)
    try { await api(`/api/distribution/trips/${trip.id}/release`, { method: 'POST', body: {} }); setPlan(null); setChosen({}); reload(); setMessage({ ok: true, text: `Đã thu hồi ${trip.code}. Đơn chưa thực hiện được trả về hàng chờ để phân lại.` }) }
    catch (e) { setMessage({ ok: false, text: e.message }) } finally { setBusy(false) }
  }
  const pairs = Object.values(chosen).filter(Boolean).length
  const queueCounts=plan?.queue_summary||data?.counts
  const reviewedTotal=plan?(plan.queue_summary?.at_warehouse??plan.result.stats.requested??(Number(plan.result.stats.assigned)+plan.result.stats.unassigned.length)):0
  return <div className="page distribution-page"><PageHead title={title} /><nav className="toolbar" aria-label="Loại phân phối">{[['pickup','Lấy hàng'],['delivery','Giao hàng'],['return','Hoàn hàng']].map(([key,label])=><button key={key} className={`btn${phase===key?'':' ghost'}`} aria-pressed={phase===key} disabled={busy} onClick={()=>setParams(previous=>{const next=new URLSearchParams(previous);next.set('phase',key);return next})}>{label}</button>)}</nav>
    <section className="card"><div className="form"><label>Ngày thực hiện<input type="date" min={workday.data?.date || dispatchDate()} value={date} disabled={busy} onChange={e => { if (e.target.value) setDate(e.target.value) }} /></label><label>Kho phụ trách<select value={warehouse} disabled={busy} onChange={e => setWarehouse(e.target.value)}>{(warehouses || []).map(w => <option key={w.id} value={w.id}>{w.name}</option>)}</select></label><label>Tối đa đơn / chuyến<input type="number" min="1" max="45" value={maxOrders} disabled={busy} onChange={e => { setMaxOrders(e.target.value); setPlan(null) }} /></label></div>
      
      {queueCounts && <p className="distribution-queue-counts"><span><b>{nf(queueCounts.at_warehouse)}</b> đơn chờ phân {action}</span>{queueCounts.ready!=null&&<span><b>{nf(queueCounts.ready)}</b> đơn còn khung giờ phù hợp</span>}<span><b>{nf(queueCounts.expired)}</b> đơn quá hạn hẹn</span>{queueCounts.future>0&&<span><b>{nf(queueCounts.future)}</b> đơn hẹn sau ngày chọn</span>}{queueCounts.invalid>0&&<span><b>{nf(queueCounts.invalid)}</b> đơn thiếu thông tin</span>}{queueCounts.deferred>0&&<span><b>{nf(queueCounts.deferred)}</b> đơn chờ lượt tính tiếp theo</span>}</p>}
    </section>
    {error && <div className="callout red">{error.message}</div>}{message && <div className={`callout ${message.ok ? 'green' : 'red'}`} role="status">{message.text}</div>}
    {data && <><section className="card"><div className="distribution-policy-heading"><h2>1. Chọn xe và tài xế</h2><span className="tag green">Ưu tiên gom ít chuyến</span></div>
      <TableScroll><table><thead><tr><th>Xe / sức chứa</th><th>Tình trạng ngày {date}</th><th>Phân tài xế</th><th>Xe rảnh / bận</th></tr></thead><tbody>{data.vehicles.map(v => { const unavailable = !v.available || v.busy || v.status === 'maintenance'; return <tr key={v.id}><td><b>{v.code}</b><br />{v.plate}<small className="block">{nf(v.capacity_kg)} kg · {nf(v.capacity_m3, 1)} m³</small></td><td>{v.status === 'maintenance' ? 'Bảo trì' : v.busy ? v.status === 'active' ? 'Đang hoạt động' : 'Chờ xuất phát' : !v.available ? 'Đã đánh dấu bận' : 'Rảnh'}</td><td><select aria-label={`Tài xế ${v.code}`} disabled={unavailable || busy} value={chosen[v.id] || ''} onChange={e => select(v.id, e.target.value)}><option value="">Không chọn xe này</option>{data.drivers.map(d => <option key={d.id} value={d.id} disabled={!d.working || d.busy || Object.entries(chosen).some(([vid, did]) => Number(vid) !== v.id && Number(did) === d.id)}>{d.name} · {!d.working ? d.working === false ? 'Nghỉ' : 'Chưa đăng ký' : d.busy ? 'Đã có chuyến' : `${d.shift_start}–${d.shift_end}`}</option>)}</select></td><td><button className="btn ghost" disabled={busy || v.busy || v.status === 'maintenance'} onClick={() => availability(v)}>{v.available ? 'Đánh dấu bận' : 'Cho phép dùng xe'}</button></td></tr> })}</tbody></table></TableScroll>
      {!data.vehicles.length && <p className="empty">Kho chưa có xe.</p>}<div className="toolbar"><button className="btn" disabled={busy || !pairs || Number(maxOrders) < 1 || Number(maxOrders) > 45} onClick={preview}>{busy ? 'Đang xử lý…' : `2. Xem phương án cho ${pairs} xe`}</button><button className="btn ghost" disabled={busy} onClick={reload}>Làm mới xe và lịch</button></div>
    </section>
    {data.trips?.length > 0 && <details className="card"><summary>Chuyến đã phân / cần xử lý ({data.trips.length})</summary>{data.trips.map(t => <div className="distribution-existing" key={t.id}><span><b>{t.code}</b> · {t.vehicle} · {t.driver}<small className="block">Ngày {t.work_date} · {t.status === 'planned' ? 'Chờ xuất phát' : 'Đang chạy'} · {t.orders} đơn</small></span>{t.status === 'planned' && <button className="btn ghost" disabled={busy} onClick={() => release(t)}>Thu hồi phân công</button>}</div>)}</details>}
    </>}
    {plan && <section className="card"><div className="distribution-review-heading"><h2>3. Phân công phương án đã chọn</h2><span className="tag amber">Chưa phân công · xe/tài xế chưa được giữ chỗ</span></div><div className="distribution-totals"><span><b>{plan.result.routes.length}</b> chuyến</span><span><b>{plan.result.stats.assigned}</b> đơn được xếp</span><span><b>{plan.result.routes.length?nf(plan.result.stats.remaining_km, 1):'—'}</b> {plan.result.routes.length?'km':'Chưa có tuyến'}</span><span><b>{nf(plan.result.stats.estimated_cost)}</b> đ dự kiến</span></div>
      <p className="distribution-reconciliation">Đối soát <b>{nf(reviewedTotal)} đơn chờ</b>: <b>{nf(plan.result.stats.assigned)} đơn được xếp</b> + <b>{nf(plan.result.stats.unassigned.length)} đơn chưa phân</b>.</p>
      {plan.result.stats.assigned>0&&plan.result.routes.length===1&&plan.result.stats.unassigned.length===0&&<p className="distribution-batch-result"><b>Gom toàn bộ {plan.result.stats.assigned} đơn vào 1 chuyến</b> · Đáp ứng tải trọng, thể tích và thời gian làm việc.</p>}
      {plan.result.stats.assigned>0&&plan.result.routes.length>1&&<p className="distribution-batch-result">Đã chia {plan.result.stats.assigned} đơn thành {plan.result.routes.length} chuyến theo sức chứa, giới hạn đơn và lịch hẹn.</p>}
      {phase === 'delivery' && <div className="distribution-finances"><span><b>{nf(plan.result.stats.confirmed_revenue || 0)} đ</b>Cước đã xác nhận</span><span><b>{nf(plan.result.stats.estimated_revenue || 0)} đ</b>Tổng cước gồm tạm tính</span><span><b>{nf(plan.result.stats.unpriced_orders || 0)}</b>Đơn chưa có cước</span></div>}
      {plan.result.routes.length>0&&<span className="tag gray">{plan.result.stats.source === 'estimate' ? 'Quãng đường ước tính' : 'Quãng đường theo nguồn cấu hình'}</span>}
      {!plan.result.routes.length&&<p className="callout amber" role="status">Không xếp được chuyến nào. Kiểm tra lý do ở từng đơn bên dưới; quãng đường sẽ có sau khi xếp được tuyến phù hợp.</p>}
      {plan.limited && <p className="callout amber">Lượt này tính tối đa 500 đơn có lịch phù hợp. Các đơn còn lại được liệt kê để xử lý hoặc tính tiếp.</p>}
      <div className="grid2 equal">{plan.result.routes.map(route => { const v = plan.vehicles.find(v => v.id === route.vehicle_id), customers = route.nodes.filter(n => n.kind !== 'warehouse'); return <article key={route.vehicle_id} className="distribution-route"><h3>{v?.code} · {v?.driver_name}</h3><p>{customers.length}/{maxOrders} đơn · {nf(route.distance_km, 1)} km · {nf(customers.reduce((sum, n) => sum + n.weight_kg, 0), 1)} / {nf(v?.capacity_kg)} kg</p><p>{new Date(route.start.time_min * 60000).toLocaleString('vi-VN')} → {new Date(route.end_min * 60000).toLocaleTimeString('vi-VN')}</p><p>Chi phí chuyến: <b>{nf(route.operating_cost || 0)} đ</b>{phase==='delivery' && <> · Cước: <b>{nf(route.estimated_revenue || 0)} đ</b></>}</p>{phase==='delivery' && <p>{route.delivery_contribution==null?'Chưa đủ cước để tính chênh lệch':`Chênh lệch cước − chi phí giao: ${nf(route.delivery_contribution)} đ${route.estimated_fares?' · tạm tính':''}`}</p>}<details><summary>Xem {customers.length} khách theo thứ tự {action}</summary><ol>{customers.map(n => <li key={n.id}><b>{n.customer_name}</b> · {n.code}<br />{n.address}</li>)}</ol></details></article> })}</div>
      {plan.result.stats.unassigned.length > 0 && <details className="callout amber" open={!plan.result.routes.length||undefined}><summary>{plan.result.stats.unassigned.length} đơn chưa phân</summary><ul>{plan.result.stats.unassigned.map(n => <li key={n.id}><Link to={`/orders/${n.id}`}>{n.code||`Đơn #${n.id}`}</Link>{n.customer_name&&` · ${n.customer_name}`}<p>{reason(n)}</p>{n.window_start_min!=null&&<p>Khung giờ hẹn: {localMinute(n.window_start_min)} – {localMinute(n.window_end_min)}</p>}<Link className="btn ghost" to={`/orders/${n.id}`}>{n.reason==='order_deadline_passed'?'Mở đơn để đổi lịch hẹn':'Xem chi tiết đơn'}</Link></li>)}</ul></details>}
      <div className="toolbar distribution-confirm-bar"><strong>Xem trước chưa làm thay đổi hàng chờ.</strong><button className="btn" disabled={busy || !plan.result.routes.length} onClick={confirm}>Phân công & giữ chỗ {plan.result.routes.length} chuyến</button><button className="btn ghost" disabled={busy} onClick={() => setPlan(null)}>Bỏ phương án</button><Link to="/live">Theo dõi hành trình</Link></div>
    </section>}
  </div>
}
