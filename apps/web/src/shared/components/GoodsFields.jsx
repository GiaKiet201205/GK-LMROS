import { useId } from 'react'
import { GOODS_TYPES } from '../lib/shipping.js'
export default function GoodsFields({ form, setForm, errors={},onBlur }) {
 const id=useId()
 return <div className="goods-inputs"><div className="field"><label htmlFor={`${id}-type`}>Loại hàng</label><select id={`${id}-type`} value={form.goodsType} onChange={e=>setForm(f=>({...f,goodsType:e.target.value}))}>{Object.entries(GOODS_TYPES).map(([key,label])=><option key={key} value={key}>{label}</option>)}</select></div><div className="field"><label htmlFor={`${id}-description`}>Bạn gửi hàng gì?<span className="required-mark" aria-hidden="true"> *</span></label><textarea id={`${id}-description`} name="goodsDescription" required minLength={2} maxLength={500} onBlur={onBlur} aria-invalid={!!errors.goodsDescription} aria-describedby={errors.goodsDescription?`${id}-error`:undefined} rows={3} value={form.goodsDescription} onChange={e=>setForm(f=>({...f,goodsDescription:e.target.value}))} placeholder="Ví dụ: 2 hộp bánh đóng kín"/>{errors.goodsDescription&&<p id={`${id}-error`} className="field-error" role="alert">{errors.goodsDescription}</p>}</div></div>
}
