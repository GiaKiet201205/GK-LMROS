import { useRef, useState } from 'react'
import { api } from '../api/client.js'
import { confirmedLocation } from './locations.js'
export const GOODS_TYPES={documents:'Giấy tờ',food:'Thực phẩm',clothing:'Quần áo',electronics:'Điện tử',fragile:'Hàng dễ vỡ',other:'Hàng khác'}
export const PRICE_STATUS={confirmed:'Cước đã xác nhận',estimated:'Cước tạm tính',manual_quote:'Chờ báo giá',unquoted:'Chưa có cước'}
export function useShippingQuote(pickup,delivery,weight,endpoint){
 const [saved,setSaved]=useState(null),[error,setError]=useState(''),[busy,setBusy]=useState(false),request=useRef(0)
 const input={pickupLat:pickup?.lat,pickupLng:pickup?.lng,lat:delivery?.lat,lng:delivery?.lng,weightKg:Number(weight)},key=JSON.stringify(input)
 const ready=confirmedLocation(pickup)&&confirmedLocation(delivery)&&Number(weight)>0&&Number(weight)<=10000
 async function calculate(){const id=++request.current;setBusy(true);setError('');try{const value=await api(endpoint,{method:'POST',body:input});if(request.current===id)setSaved({key,value})}catch(e){if(request.current===id){setSaved(null);setError(e.message)}}finally{if(request.current===id)setBusy(false)}}
 return {quote:ready&&saved?.key===key?saved.value:null,ready,busy,error,calculate}
}
