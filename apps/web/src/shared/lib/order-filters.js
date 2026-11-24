export const ORDER_GROUPS=[
 {value:'pickup',label:'Lấy hàng',stages:['awaiting_pickup','pickup_assigned','picking_up','picked_up']},
 {value:'warehouse',label:'Tại kho',stages:['at_warehouse']},
 {value:'delivery',label:'Giao hàng',stages:['delivery_assigned','out_for_delivery']},
 {value:'return',label:'Hoàn hàng',stages:['return_pending','return_assigned','returning']},
 {value:'completed',label:'Hoàn tất',stages:['delivered','returned']},
 {value:'issues',label:'Cần xử lý',stages:['failed','return_failed']},
]
export const groupForStage=stage=>ORDER_GROUPS.find(g=>g.stages.includes(stage))?.value||''
export const deadlineText=value=>value==null?'—':new Date(Number(value)*60000).toLocaleString('vi-VN',{timeZone:'Asia/Ho_Chi_Minh',hour:'2-digit',minute:'2-digit',day:'2-digit',month:'2-digit',year:'numeric'})
