import { addDays, calendarDate, dispatchDate } from './workday.js'
export function normalizePhone(value){const phone=String(value??'').replace(/[\s.()\-]/gu,'');return phone.startsWith('+84')?'0'+phone.slice(3):phone}
export function validPhone(value){return /^0[0-9]{9}$/u.test(normalizePhone(value))}
export function cleanName(value){return String(value??'').normalize('NFC').trim().replace(/\s+/gu,' ')}
export function validName(value){const name=cleanName(value);return name.length>=2&&name.length<=100&&/\p{L}/u.test(name)&&!/[\u0000-\u001f\u007f]/u.test(name)}
export function orderErrors(form,pickup,delivery,now=new Date()){
 const errors={}
 for(const [key,label] of [['pickupName','Tên người gửi'],['customerName','Tên người nhận']])if(!validName(form[key]))errors[key]=`${label} phải có chữ, từ 2 đến 100 ký tự.`
 for(const [key,label] of [['pickupPhone','Điện thoại người gửi'],['phone','Điện thoại người nhận']])if(!validPhone(form[key]))errors[key]=`${label} phải gồm 10 chữ số, bắt đầu bằng 0.`
 for(const [key,point,label] of [['pickupAddress',pickup,'địa chỉ lấy'],['address',delivery,'địa chỉ giao']])if(!point?.confirmed||!point.label?.trim()||point.label.trim().length<5||point.label.trim().length>400||!Number.isFinite(point.lat)||!Number.isFinite(point.lng)||Math.abs(point.lat)>90||Math.abs(point.lng)>180)errors[key]=`Xác nhận ${label} hợp lệ.`
 if(!['documents','food','clothing','electronics','fragile','other'].includes(form.goodsType))errors.goodsType='Chọn loại hàng.'
 const description=String(form.goodsDescription??'').trim();if(description.length<2||description.length>500)errors.goodsDescription='Mô tả hàng từ 2 đến 500 ký tự.'
 if(String(form.note??'').trim().length>1000)errors.note='Ghi chú tối đa 1000 ký tự.'
 for(const [key,min,max,label] of [['weightKg',.01,10000,'Khối lượng từ 0,01 đến 10.000 kg.'],['volumeM3',.001,100,'Thể tích từ 0,001 đến 100 m³.']])if(form[key]===''||!Number.isFinite(Number(form[key]))||Number(form[key])<min||Number(form[key])>max)errors[key]=label
 const earliest=dispatchDate(now),last=addDays(calendarDate(now),90),dates={}
 for(const key of ['pickupFrom','pickupTo','deliveryFrom','deliveryTo']){
  const text=String(form[key]??''),date=new Date(`${text}+07:00`)
  if(!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?$/u.test(text)||!Number.isFinite(date.getTime())||calendarDate(date)!==text.slice(0,10)){errors[key]='Nhập ngày giờ hợp lệ.';continue}
  dates[key]=date
  if(text.slice(0,10)<earliest||text.slice(0,10)>last)errors[key]=`Chọn ngày từ ${earliest} đến ${last}.`
  else if((Number(text.slice(11,13))*3600+Number(text.slice(14,16))*60+Number(text.slice(17,19)||0))<28800||(Number(text.slice(11,13))*3600+Number(text.slice(14,16))*60+Number(text.slice(17,19)||0))>72000)errors[key]='Chọn giờ trong ca 08:00–20:00.'
 }
 for(const prefix of ['pickup','delivery'])if(dates[`${prefix}From`]&&dates[`${prefix}To`]){
  if(dates[`${prefix}From`]>=dates[`${prefix}To`]||form[`${prefix}From`].slice(0,10)!==form[`${prefix}To`].slice(0,10))errors[`${prefix}To`]='Giờ kết thúc phải sau giờ bắt đầu trong cùng ngày.'
  else if(dates[`${prefix}To`]<=now)errors[`${prefix}To`]='Khung giờ đã hết hạn.'
 }
 if(dates.pickupFrom&&dates.deliveryTo&&dates.pickupFrom>=dates.deliveryTo)errors.deliveryTo='Hạn giao phải sau thời điểm có thể lấy hàng.'
 return errors
}
export function normalizedOrder(form,pickup,delivery,quote){return {...form,pickupName:cleanName(form.pickupName),customerName:cleanName(form.customerName),pickupPhone:normalizePhone(form.pickupPhone),phone:normalizePhone(form.phone),goodsDescription:form.goodsDescription.trim(),note:String(form.note??'').trim(),weightKg:Number(form.weightKg),volumeM3:Number(form.volumeM3),pickupAddress:pickup.label.trim(),pickupLat:pickup.lat,pickupLng:pickup.lng,address:delivery.label.trim(),lat:delivery.lat,lng:delivery.lng,shippingQuoteId:quote.id}}

export function focusInvalid(fields){const key=Object.keys(fields)[0];requestAnimationFrame(()=>{const target=document.querySelector(`[name="${key}"]`)||(key==='pickupAddress'?document.querySelector('.address-picker input'):key==='address'?document.querySelectorAll('.address-picker input')[1]:null);target?.focus();target?.scrollIntoView({block:'center'})})}
