import {PAYERS,PAYMENT_STATUS} from '../../../shared/lib/payments.js'
import {useEffect,useRef,useState} from 'react'
import {Link,useSearchParams} from 'react-router-dom'
import {CalendarClock,ClockAlert,SlidersHorizontal,Eye} from 'lucide-react'
import {useApi,api} from '../../../shared/api/client.js'
import {useWarehouseFilter as useFilter} from '../context/WarehouseFilterContext.js'
import {DataTable,PageHead,Tag,STAGES} from '../../../shared/components/ui.jsx'
import {ORDER_GROUPS,groupForStage,deadlineText} from '../../../shared/lib/order-filters.js'
import BulkReschedule from '../components/BulkReschedule.jsx'
export default function Orders(){
 const [params]=useSearchParams(),{warehouse}=useFilter(),[offset,setOffset]=useState(0),[message,setMessage]=useState(null),[status,setStatus]=useState(''),[group,setGroup]=useState(''),[late,setLate]=useState(false),[search,setSearch]=useState(''),[q,setQ]=useState(''),[selected,setSelected]=useState(()=>new Set()),[bulk,setBulk]=useState(null),allRef=useRef(null)
 useEffect(()=>{const st=params.get('stage')||'';setSearch(params.get('q')||'');setQ(params.get('q')||'');setStatus(st);setGroup(groupForStage(st));setLate(params.get('overdue')==='true')},[params])
 useEffect(()=>{const t=setTimeout(()=>setQ(search),250);return()=>clearTimeout(t)},[search])
 useEffect(()=>{setOffset(0);setSelected(new Set())},[warehouse,status,group,q,late])
 const query=new URLSearchParams({limit:'201',offset:String(offset),...(status?{stage:status}:{}),...(group?{group}:{}),...(late?{overdue:'true'}:{}),...(warehouse?{warehouse}:{}),...(q?{q}:{})})
 const {data,error,reload}=useApi(`/api/orders?${query}`,5000),rows=search===q?data?.slice(0,200):null,available=rows?.filter(r=>r.can_reschedule)||[],selectedRows=rows?.filter(r=>selected.has(r.id))||[],allSelected=available.length>0&&available.every(r=>selected.has(r.id))
 useEffect(()=>{if(data)setSelected(prev=>{const next=new Set([...prev].filter(id=>data.slice(0,200).some(r=>r.id===id&&r.can_reschedule)));return next.size===prev.size?prev:next})},[data])
 useEffect(()=>{if(allRef.current)allRef.current.indeterminate=selectedRows.length>0&&!allSelected},[selectedRows.length,allSelected])
 function toggle(id){setSelected(prev=>{const next=new Set(prev);next.has(id)?next.delete(id):next.add(id);return next})}
 function changePage(value){setOffset(value);setSelected(new Set())}
 const cols=[
 {key:'select',label:<input ref={allRef} className="order-select" type="checkbox" aria-label="Chọn các đơn có thể hẹn lại trên trang" disabled={!available.length} checked={allSelected} onChange={()=>setSelected(allSelected?new Set():new Set(available.map(r=>r.id)))}/>,render:r=><input className="order-select" type="checkbox" aria-label={`Chọn đơn ${r.code}`} title={r.can_reschedule?'Chọn để hẹn lại':'Chỉ hẹn lại đơn chờ lấy hoặc tại kho chưa có chuyến'} disabled={!r.can_reschedule} checked={selected.has(r.id)} onChange={()=>toggle(r.id)}/>},
 {key:'code',label:'Mã đơn',render:r=><><Link to={`/orders/${r.id}`}><b>{r.code}</b></Link>{r.customer_order&&<small className="block">Khách đặt trên web GK</small>}</>},
 {key:'stage',label:'Trạng thái & hẹn',render:r=><div className="orders-status-cell"><Tag map={STAGES} value={r.stage}/>{r.deadline_min!=null&&<small className={r.overdue?'order-overdue-date':''}>{deadlineText(r.deadline_min)}</small>}{r.overdue&&<small className="order-overdue-date">Trễ hạn {r.overdue_kind==='pickup'?'lấy':'giao'}</small>}{r.delivery_failures>0&&<small>Chưa giao thành công {r.delivery_failures}/3 lần</small>}{r.is_dynamic&&<small>Đơn phát sinh</small>}</div>},
 {key:'customer_name',label:'Khách hàng',render:r=><><b className="orders-customer-name">{r.customer_name}</b><small className="block">{Number(r.weight_kg).toLocaleString('vi-VN')} kg</small></>},
 {key:'addresses',label:'Lấy → giao',render:r=><div className="orders-address-cell"><p><span>Lấy</span>{r.pickup_address||'Chưa có địa chỉ'}</p><p><span>Giao</span>{r.address}</p></div>},
 {key:'payment_status',label:'Cước & thanh toán',render:r=><><b>{r.shipping_fee_vnd==null?'Chờ báo cước':`${Number(r.shipping_fee_vnd).toLocaleString('vi-VN')} đ`}</b><small className="block">{PAYMENT_STATUS[r.payment_status]}</small><small className="block">{PAYERS[r.payment_payer]} trả cước</small></>},
 {key:'assignment',label:'Phân công',render:r=><><span>{r.warehouse}</span><small className="block">{r.trip||'Chưa phân chuyến'}</small></>},
 {key:'act',label:'Thao tác',render:r=><div className="order-row-actions"><Link className="btn ghost row-detail-link" to={`/orders/${r.id}`}><Eye size={15}/>Xem chi tiết</Link>{r.stage==='failed'&&!r.delivery_cancelled_at&&r.delivery_failures<3&&<button className="btn ghost" onClick={()=>api(`/api/orders/${r.id}/requeue`,{method:'POST'}).then(reload).catch(e=>setMessage({ok:false,text:e.message}))}>Đưa vào chuyến sau</button>}</div>},
 ]
 const detailStages=Object.entries(STAGES).filter(([key])=>!group||groupForStage(key)===group)
 return <div className="page orders-page"><PageHead title="Đơn hàng"/><section className="card orders-filters"><div className="orders-filter-main"><input aria-label="Tìm đơn hàng" placeholder="Tìm mã đơn, khách, địa chỉ" type="search" value={search} onChange={e=>setSearch(e.target.value)}/><select aria-label="Nhóm trạng thái" value={group} onChange={e=>{setGroup(e.target.value);setStatus('')}}><option value="">Mọi nhóm trạng thái</option>{ORDER_GROUPS.map(g=><option key={g.value} value={g.value}>{g.label}</option>)}</select><button className={`btn${late?'':' ghost'}`} aria-pressed={late} onClick={()=>setLate(v=>!v)}><ClockAlert size={17}/>Trễ hạn</button></div><details className="orders-filter-details" open={status?true:undefined}><summary><SlidersHorizontal size={15}/>Lọc chi tiết{status&&` · ${STAGES[status]?.[0]||status}`}</summary><label>Trạng thái cụ thể<select aria-label="Trạng thái cụ thể" value={status} onChange={e=>setStatus(e.target.value)}><option value="">Tất cả trong nhóm</option>{detailStages.map(([key,[name]])=><option key={key} value={key}>{name}</option>)}</select></label></details></section>
 {(error||message)&&<div className={`callout ${error||!message?.ok?'red':'green'}`} role={error||!message?.ok?'alert':'status'}>{error?.message||message?.text}</div>}
 <section className="orders-table-panel"><div className="orders-selection"><span>{selectedRows.length?`Đã chọn ${selectedRows.length} đơn`:'Chọn đơn để hẹn lại cùng lúc'}</span><button className="btn" disabled={!selectedRows.length} onClick={()=>setBulk(selectedRows)}><CalendarClock size={17}/>Hẹn lại{selectedRows.length?` ${selectedRows.length} đơn`:''}</button>{selectedRows.length>0&&<button className="btn ghost" onClick={()=>setSelected(new Set())}>Bỏ chọn</button>}</div>
 <DataTable columns={cols} rows={rows} empty="Không có đơn nào khớp bộ lọc"/></section>
 <div className="toolbar orders-pagination"><button className="btn ghost" disabled={!offset} onClick={()=>changePage(Math.max(0,offset-200))}>Trang trước</button><span>Trang {offset/200+1}</span><button className="btn ghost" disabled={!data||data.length<=200} onClick={()=>changePage(offset+200)}>Trang sau</button></div>
 {bulk&&<BulkReschedule rows={bulk} onClose={()=>setBulk(null)} onSaved={count=>{setBulk(null);setSelected(new Set());setMessage({ok:true,text:`Đã hẹn lại ${count} đơn.`});reload()}}/>}
 </div>
}
