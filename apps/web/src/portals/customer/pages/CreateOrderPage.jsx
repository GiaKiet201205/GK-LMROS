import PaymentChoice from '../../../shared/components/PaymentChoice.jsx'
import FormField from '../../../shared/components/FormField.jsx'
import { orderErrors, normalizedOrder, focusInvalid } from '../../../shared/lib/validation.js'
import { addDays, calendarDate, dispatchDate } from '../../../shared/lib/workday.js'
import ShippingQuote from '../../../shared/components/ShippingQuote.jsx'
import GoodsFields from '../../../shared/components/GoodsFields.jsx'
import { useShippingQuote } from '../../../shared/lib/shipping.js'
import { defaultOrderWindows } from '../../../shared/lib/workday.js'
import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Box, MapPin, Package } from 'lucide-react'
import { useAuth } from '../../../shared/auth/AuthProvider.jsx'
import { api, nf } from '../../../shared/api/client.js'
import AddressPicker from '../../../shared/components/AddressPicker.jsx'
import { confirmedLocation } from '../../../shared/lib/locations.js'


export default function CustomerCreate() {
  const { user } = useAuth(), nav = useNavigate()
  const [form, setForm] = useState(() => ({ pickupName: user.display_name, pickupPhone: user.phone, customerName: '', phone: '', goodsDescription:'',goodsType:'other',paymentPayer:'recipient',weightKg: 1, volumeM3: .01, ...defaultOrderWindows(), note: '' }))
  const [pickup, setPickup] = useState(null), [delivery, setDelivery] = useState(null), [busy, setBusy] = useState(false), [error, setError] = useState(''), [agreed, setAgreed] = useState(false)
  const pricing=useShippingQuote(pickup,delivery,form.weightKg,'/api/customer/pricing/quote')
  const [errors,setErrors]=useState({})
  const check=()=>orderErrors(form,pickup,delivery)
  const blur=key=>()=>setErrors(e=>({...e,[key]:check()[key]}))
  const requestId = useRef(crypto.randomUUID())
  const set = key => e => setForm(value => ({ ...value, [key]: e.target.value }))
  const field = (key,label,type='text',rest={}) => <FormField className="c-field" name={key} label={label} type={type} value={form[key]} error={errors[key]} onChange={set(key)} onBlur={blur(key)} disabled={busy} {...(type==='tel'?{maxLength:30,inputMode:'tel'}:type==='text'?{minLength:2,maxLength:100}:type==='datetime-local'?{min:`${dispatchDate()}T08:00`,max:`${addDays(calendarDate(),90)}T20:00`}:{})} {...rest}/>

  async function submit(e) {
    e.preventDefault();const problems=check();setErrors(problems);if(Object.keys(problems).length){setError('');focusInvalid(problems);return} if (!confirmedLocation(pickup) || !confirmedLocation(delivery) || !agreed || !pricing.quote) { setError('Hãy xác nhận hai vị trí và kiểm tra lại thông tin đơn.'); return }
    setBusy(true); setError('')
    try {
      const order = normalizedOrder(form,pickup,delivery,pricing.quote)
      const booking = await api('/api/customer/bookings', { method: 'POST', body: { requestId: requestId.current, order } })
      nav(`/orders/${booking.id}`, { state: { justCreated: true } })
    } catch (e) { setError(e.message);setErrors(e.fields || {}) } finally { setBusy(false) }
  }
  return <div className="c-container c-page"><div className="c-page-heading"><p className="c-eyebrow">GỬI HÀNG CÙNG GK</p><h1>Tạo đơn lấy & giao</h1></div>
    <form className="c-booking-layout" noValidate onSubmit={submit}><div className="c-booking-sections">
      <section className="c-panel"><div className="c-panel-title"><MapPin size={22} /><div><h2>Lấy hàng ở đâu?</h2></div></div><div className="c-form-grid">{field('pickupName', 'Tên người gửi', 'text', { maxLength: 100, autoComplete: 'name' })}{field('pickupPhone', 'Điện thoại người gửi', 'tel', { maxLength: 20, autoComplete: 'tel' })}</div><AddressPicker id="customer-pickup" title="Địa chỉ lấy hàng" endpoint="/api/customer/locations" value={pickup} onChange={setPickup} /><div className="c-form-grid c-time-grid">{field('pickupFrom', 'Có thể lấy từ', 'datetime-local')}{field('pickupTo', 'Lấy chậm nhất', 'datetime-local')}</div></section>
      <section className="c-panel"><div className="c-panel-title"><Package size={22} /><div><h2>Giao đến ai?</h2></div></div><div className="c-form-grid">{field('customerName', 'Tên người nhận', 'text', { maxLength: 100 })}{field('phone', 'Điện thoại người nhận', 'tel', { maxLength: 20 })}</div><AddressPicker id="customer-delivery" title="Địa chỉ giao hàng" endpoint="/api/customer/locations" value={delivery} onChange={setDelivery} /><div className="c-form-grid c-time-grid">{field('deliveryFrom', 'Có thể giao từ', 'datetime-local')}{field('deliveryTo', 'Giao chậm nhất', 'datetime-local')}</div></section>
      <section className="c-panel"><div className="c-panel-title"><Box size={22} /><div><h2>Kiện hàng của bạn</h2></div></div><div className="c-form-grid">{field('weightKg', 'Khối lượng (kg)', 'number', { min: '.01', max: 10000, step: 'any' })}<label className="c-field">Kích thước tham khảo<select value={String(form.volumeM3)} onChange={set('volumeM3')}><option value="0.01">Nhỏ · khoảng 20 × 20 × 25 cm (0,01 m³)</option><option value="0.05">Vừa · khoảng 50 × 40 × 25 cm (0,05 m³)</option><option value="0.15">Lớn · khoảng 60 × 50 × 50 cm (0,15 m³)</option></select></label></div><GoodsFields form={form} setForm={setForm} errors={errors} onBlur={blur('goodsDescription')}/><label className="c-field">Ghi chú (không bắt buộc)<textarea maxLength={1000} rows={3} placeholder="Ví dụ: gọi trước khi đến, số tầng, hàng dễ vỡ…" value={form.note} onChange={set('note')} /></label></section>
    </div><aside className="c-panel c-booking-summary"><p className="c-eyebrow">KIỂM TRA TRƯỚC KHI GỬI</p><h2>Chuyến hàng của bạn</h2><div className="c-summary-route"><div><i /><small>LẤY TẠI</small><b>{pickup?.confirmed ? pickup.label : 'Chưa xác nhận địa chỉ lấy'}</b></div><div><i /><small>GIAO ĐẾN</small><b>{delivery?.confirmed ? delivery.label : 'Chưa xác nhận địa chỉ giao'}</b></div></div><dl><div><dt>Khối lượng</dt><dd>{nf(form.weightKg, 2)} kg</dd></div><div><dt>Thể tích tham khảo</dt><dd>{nf(form.volumeM3, 2)} m³</dd></div></dl><PaymentChoice value={form.paymentPayer} onChange={value=>setForm(f=>({...f,paymentPayer:value}))} disabled={busy}/><ShippingQuote pricing={pricing}/><label className="c-checkbox"><input type="checkbox" checked={agreed} onChange={e => setAgreed(e.target.checked)} /><span>Tôi đã kiểm tra đúng người nhận, số điện thoại và hai địa chỉ.</span></label>
      {error && <p className="c-alert error" role="alert">{error}</p>}<button className="c-btn" disabled={busy || pricing.busy || !pricing.quote || !agreed || !confirmedLocation(pickup) || !confirmedLocation(delivery)}>{busy ? 'Đang gửi đơn…' : 'Gửi yêu cầu lấy & giao'}</button></aside>
    </form></div>
}
