import {useSearchParams} from 'react-router-dom'
import {useState} from 'react'
import {api,useApi,nf} from '../api/client.js'
import {calendarDate} from '../lib/workday.js'
import './VehicleCosts.css'
const STATUS={pending:'Chờ duyệt',approved:'Đã duyệt',rejected:'Từ chối',cancelled:'Đã hủy'}
const when=value=>value?new Date(value).toLocaleString('vi-VN',{timeZone:'Asia/Ho_Chi_Minh'}):'—'
export default function VehicleCosts({driverMode=false}){
 const [params,setParams]=useSearchParams(),receiptId=params.get('receipt'),[history,setHistory]=useState(false)
 const path=driverMode?'/api/driver/vehicle-costs':'/api/vehicle-costs',rows=useApi(path+(receiptId?'?id='+encodeURIComponent(receiptId):''),10000),vehicles=useApi(driverMode?'/api/driver/maintenance':'/api/vehicles'),[form,setForm]=useState(null),[review,setReview]=useState(null),[error,setError]=useState(''),[busy,setBusy]=useState(false)
 const shown=rows.data?.filter(r=>receiptId||(history?r.status!=='pending':r.status==='pending'))
 const fleet=driverMode?vehicles.data?.vehicles:vehicles.data
 async function send(url,body){setBusy(true);setError('');try{await api(url,{method:'POST',body});setForm(null);setReview(null);rows.reload()}catch(e){setError(e.message)}finally{setBusy(false)}}
 function toggleHistory(){if(receiptId)setParams(old=>{const next=new URLSearchParams(old);next.delete('receipt');return next});else setHistory(h=>!h)}
 return <section className="card vehicle-costs">
  <header className="vehicle-cost-heading"><h2>Phiếu nhiên liệu & chi phí xe</h2><div className="vehicle-cost-actions"><button type="button" className="btn ghost" disabled={busy} onClick={toggleHistory}>{receiptId?'Tất cả phiếu':history?'Phiếu chờ duyệt':'Xem phiếu đã xử lý'}</button><button type="button" className="btn" disabled={busy} onClick={()=>{setError('');setForm({requestId:crypto.randomUUID(),vehicleId:'',kind:'fuel',date:calendarDate(),amount:'',description:'Đổ nhiên liệu',reference:''})}}>Lập phiếu</button></div></header>
  {error&&<p className="callout red" role="alert">{error}</p>}{rows.error&&<p className="callout red" role="alert">{rows.error.message}</p>}
  {form&&<form className="vehicle-cost-form" onSubmit={e=>{e.preventDefault();send(path,{...form,vehicleId:Number(form.vehicleId)})}}><h3>Lập phiếu chi phí xe</h3><div className="vehicle-cost-fields">
   <label>Xe *<select required disabled={busy} value={form.vehicleId} onChange={e=>setForm(f=>({...f,vehicleId:e.target.value}))}><option value="">Chọn xe</option>{fleet?.map(v=><option key={v.id} value={v.id}>{v.code} · {v.plate}</option>)}</select></label>
   <label>Loại phiếu<select disabled={busy} value={form.kind} onChange={e=>setForm(f=>({...f,kind:e.target.value}))}><option value="fuel">Đổ nhiên liệu</option><option value="other">Chi phí xe khác</option></select></label>
   <label>Ngày chứng từ *<input required disabled={busy} type="date" min="2026-09-01" max={calendarDate()} value={form.date} onChange={e=>setForm(f=>({...f,date:e.target.value}))}/></label>
   <label>Số tiền (đ) *<input required disabled={busy} type="number" min="1" max="1000000000" step="1" value={form.amount} onChange={e=>setForm(f=>({...f,amount:e.target.value}))}/></label>
   <label>Nội dung *<input required disabled={busy} minLength={3} maxLength={500} value={form.description} onChange={e=>setForm(f=>({...f,description:e.target.value}))}/></label>
   <label>Mã hóa đơn / phiếu<input disabled={busy} maxLength={120} value={form.reference} onChange={e=>setForm(f=>({...f,reference:e.target.value}))}/></label>
  </div><div className="vehicle-cost-form-actions"><button className="btn" disabled={busy}>Gửi duyệt</button><button type="button" className="btn ghost" disabled={busy} onClick={()=>setForm(null)}>Đóng</button></div></form>}
  <div className="vehicle-cost-list">{!rows.data&&!rows.error&&<p className="empty">Đang tải phiếu…</p>}{shown?.length===0&&<p className="empty">{receiptId?'Không tìm thấy phiếu.':history?'Chưa có phiếu đã xử lý.':'Không có phiếu chờ duyệt.'}</p>}
  {shown?.map(r=><article className="vehicle-cost-item" key={r.id}>
   <div className="vehicle-cost-item-heading"><div><h3>PX-{r.id} · {r.vehicle}</h3><p>{r.kind==='fuel'?'Nhiên liệu':'Chi phí xe khác'} · {String(r.cost_date).slice(0,10)}</p></div><div className="vehicle-cost-amount"><strong>{nf(r.amount)} đ</strong><span className="tag">{STATUS[r.status]||r.status}</span></div></div>
   <p className="vehicle-cost-description">{r.description}</p>
   <details className="vehicle-cost-details" open={Boolean(receiptId)||undefined}><summary>Xem chi tiết phiếu</summary><dl className="repair-details"><div><dt>Biển số xe</dt><dd>{r.plate||'—'}</dd></div><div><dt>Tài xế</dt><dd>{r.driver||'Điều phối viên lập phiếu'}</dd></div><div><dt>Mã hóa đơn / phiếu</dt><dd>{r.reference||'Chưa cung cấp'}</dd></div><div><dt>Ngày lập phiếu</dt><dd>{when(r.created_at)}</dd></div>{r.reviewed_at&&<div><dt>Ngày xử lý</dt><dd>{when(r.reviewed_at)}</dd></div>}{r.review_note&&<div><dt>Phản hồi</dt><dd>{r.review_note}</dd></div>}<div><dt>Ghi nhận chi phí</dt><dd>{r.status==='approved'?'Đã ghi sổ':r.status==='pending'?'Chờ duyệt, chưa ghi chi phí':'Không ghi nhận chi phí'}</dd></div></dl></details>
   {!driverMode&&r.status==='pending'&&<div className="vehicle-cost-review-actions"><button type="button" className="btn" disabled={busy} onClick={()=>send(`/api/vehicle-costs/${r.id}/approve`,{})}>Duyệt phiếu</button><button type="button" className="btn ghost" disabled={busy} onClick={()=>setReview({id:r.id,action:'reject',reason:''})}>Từ chối</button></div>}
   {review?.id===r.id&&<form className="vehicle-cost-rejection" onSubmit={e=>{e.preventDefault();send(`/api/vehicle-costs/${review.id}/${review.action}`,{reason:review.reason})}}><label>Lý do từ chối *<input required disabled={busy} minLength={3} maxLength={400} value={review.reason} onChange={e=>setReview(v=>({...v,reason:e.target.value}))}/></label><div className="vehicle-cost-form-actions"><button className="btn" disabled={busy}>Xác nhận từ chối</button><button type="button" className="btn ghost" disabled={busy} onClick={()=>setReview(null)}>Đóng</button></div></form>}
  </article>)}
  </div>
 </section>
}
