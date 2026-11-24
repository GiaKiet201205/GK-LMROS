export function filterJourneys(vehicles,{phase='all',state='all',vehicleId,query=''}={}){
 const search=query.trim().toLocaleLowerCase('vi-VN')
 return vehicles.map(v=>({...v,trips:v.trips.filter(t=>(phase==='all'||t.phase===phase)&&(state==='all'||t.status===(state==='active'?'in_progress':'planned')))})).filter(v=>{
  if(!v.trips.length||vehicleId&&String(v.id)!==String(vehicleId))return false
  return `${v.code} ${v.plate} ${v.trips.map(t=>`${t.driver||''} ${t.driver_code||''}`).join(' ')}`.toLocaleLowerCase('vi-VN').includes(search)
 })
}
