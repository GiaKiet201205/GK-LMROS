import {useState} from 'react'
import {api,nf} from '../../../shared/api/client.js'
import {PAYERS,PAYMENT_STATUS} from '../../../shared/lib/payments.js'
export default function DriverPayment({stop,online,onSaved}){
 const [busy,setBusy]=useState(false),[error,setError]=useState(''),[requestId,setRequestId]=useState(()=>crypto.randomUUID())
 const paid=['paid','no_charge','refunded'].includes(stop.payment_status)
 async function send(path){setBusy(true);setError('');try{await api(`/api/driver/stops/${stop.stop_id}/${path}`,{method:'POST',body:{requestId,...(path==='collect'?{method:'external',receivedBy:'driver',reference:''}:{})}});setRequestId(crypto.randomUUID());await onSaved()}catch(e){setError(e.message);setRequestId(crypto.randomUUID());await onSaved()}finally{setBusy(false)}}
 return <section className="driver-payment"><div className="row"><h3>Cước giao hàng</h3><b>{stop.shipping_fee_vnd==null?'Chờ báo cước':`${nf(stop.shipping_fee_vnd)} đ`}</b></div><p className={paid?'payment-paid':'payment-unpaid'}><b>{PAYMENT_STATUS[stop.payment_status]||'Chưa thanh toán'}</b> · {PAYERS[stop.payment_payer]}</p>
 {!paid&&['pickup','delivery'].includes(stop.kind)&&<div className="payment-actions"><button type="button" className="p-btn" disabled={!online||busy||stop.shipping_fee_vnd==null||Number(stop.shipping_fee_vnd)<=0} onClick={()=>send('collect')}>Đã thanh toán</button><button type="button" className="p-btn ghost" aria-pressed={Boolean(stop.payment_acknowledged)} disabled={!online||busy||stop.payment_acknowledged} onClick={()=>send('unpaid')}>Chưa thanh toán{stop.payment_acknowledged?' ✓':''}</button></div>}
 {error&&<p className="callout red" role="alert">{error}</p>}
 </section>
}
