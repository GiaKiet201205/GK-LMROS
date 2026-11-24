import PaymentChoice from '../../../shared/components/PaymentChoice.jsx'
import FormField from '../../../shared/components/FormField.jsx'
import { orderErrors, normalizedOrder, focusInvalid } from '../../../shared/lib/validation.js'
import { addDays, calendarDate, dispatchDate } from '../../../shared/lib/workday.js'
import ShippingQuote from '../../../shared/components/ShippingQuote.jsx'
import GoodsFields from '../../../shared/components/GoodsFields.jsx'
import { useShippingQuote } from '../../../shared/lib/shipping.js'
import { defaultOrderWindows } from '../../../shared/lib/workday.js'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../../../shared/api/client.js'
import { PageHead } from '../../../shared/components/ui.jsx'
import AddressPicker from '../../../shared/components/AddressPicker.jsx'
import { confirmedLocation } from '../../../shared/lib/locations.js'
export default function Dynamic() {
  const [form, setForm] = useState({ pickupName: '', pickupPhone: '', customerName: '', phone: '', goodsDescription:'',goodsType:'other',paymentPayer:'recipient',weightKg: 1, volumeM3: .08,
    ...defaultOrderWindows() })
  const [pickup, setPickup] = useState(null), [delivery, setDelivery] = useState(null)
  const [message, setMessage] = useState(null), [busy, setBusy] = useState(false)
  const pricing=useShippingQuote(pickup,delivery,form.weightKg,'/api/pricing/quote')
  const [errors,setErrors]=useState({})
  const check=()=>orderErrors(form,pickup,delivery)
  const blur=key=>()=>setErrors(e=>({...e,[key]:check()[key]}))
  const change = key => e => setForm(f => ({ ...f, [key]: e.target.value }))
  async function submit(event) {
    event.preventDefault()
    const problems=check();setErrors(problems);if(Object.keys(problems).length){setMessage(null);focusInvalid(problems);return}
    if (!confirmedLocation(pickup) || !confirmedLocation(delivery)) { setMessage({ ok: false, text: 'Hãy xác nhận đúng vị trí lấy và giao trước khi tạo đơn.' }); return }
    if(!pricing.quote){setMessage({ok:false,text:'Hãy tính cước trước khi tạo đơn.'});return}
    setBusy(true); setMessage(null)
    try {
      const body = normalizedOrder(form,pickup,delivery,pricing.quote)
      const response = await api('/api/orders', { method: 'POST', body })
      setMessage({ ok: true, text: `Đã tạo đơn ${response.code}. Hệ thống đang tìm chuyến lấy phù hợp và tự sắp xếp phần tuyến chưa đi.` })
    } catch (error) { setErrors(error.fields || {});setMessage({ ok: false, text: error.message }) } finally { setBusy(false) }
  }
  const text=(key,label,type='text')=><FormField name={key} label={label} type={type} value={form[key]} onChange={change(key)} onBlur={blur(key)} error={errors[key]} disabled={busy} {...(type==='text'?{minLength:2,maxLength:100}:type==='tel'?{maxLength:30,inputMode:'tel'}:{min:`${dispatchDate()}T08:00`,max:`${addDays(calendarDate(),90)}T20:00`})}/>

  return <div className="page"><PageHead title="Tạo đơn lấy & giao" />
    <form onSubmit={submit} noValidate className="order-form"><div className="grid2 equal">
      <section className="card"><h2>Điểm lấy hàng</h2><div className="form">
        {text('pickupName', 'Tên người gửi')}{text('pickupPhone', 'Điện thoại người gửi', 'tel')}
        {text('pickupFrom', 'Có thể lấy từ', 'datetime-local')}{text('pickupTo', 'Hạn lấy hàng', 'datetime-local')}
      </div><AddressPicker id="pickup" title="Tìm địa chỉ lấy hàng" value={pickup} onChange={setPickup} /></section>
      <section className="card"><h2>Điểm giao hàng</h2><div className="form">
        {text('customerName', 'Tên người nhận')}{text('phone', 'Điện thoại người nhận', 'tel')}
        {text('deliveryFrom', 'Có thể giao từ', 'datetime-local')}{text('deliveryTo', 'Hạn giao hàng', 'datetime-local')}
      </div><AddressPicker id="delivery" title="Tìm địa chỉ giao hàng" value={delivery} onChange={setDelivery} /></section>
    </div><section className="card"><GoodsFields form={form} setForm={setForm} errors={errors} onBlur={blur('goodsDescription')}/><div className="form"><FormField name="weightKg" label="Khối lượng (kg)" type="number" min="0.01" max="10000" step="any" value={form.weightKg} onChange={change('weightKg')} onBlur={blur('weightKg')} error={errors.weightKg}/><FormField name="volumeM3" label="Thể tích (m³)" type="number" min="0.001" max="100" step="any" value={form.volumeM3} onChange={change('volumeM3')} onBlur={blur('volumeM3')} error={errors.volumeM3}/></div>
      
      <PaymentChoice value={form.paymentPayer} onChange={value=>setForm(f=>({...f,paymentPayer:value}))} disabled={busy}/><ShippingQuote pricing={pricing}/><div className="toolbar"><button className="btn" disabled={busy || pricing.busy || !pricing.quote || !confirmedLocation(pickup) || !confirmedLocation(delivery)}>{busy ? 'Đang tạo…' : 'Tạo đơn & tự động xếp tuyến'}</button><Link to="/routing">Theo dõi điều phối</Link></div>
    </section></form>{message && <div className={`callout ${message.ok ? 'green' : 'red'}`} role="status">{message.text}</div>}</div>
}
