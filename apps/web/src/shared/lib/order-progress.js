// Fixed milestones for every order. Operational events remain a separate audit log.
export const ORDER_MILESTONES = [
  ['created', 'GK đã tiếp nhận đơn'],
  ['pickup_assigned', 'Đã phân tài xế đến lấy'],
  ['picked_up', 'Tài xế đã nhận hàng'],
  ['at_warehouse', 'Hàng đã được bàn giao tại kho'],
  ['delivery_assigned', 'Đã phân xe giao hàng'],
  ['out_for_delivery', 'Hàng đã xuất kho, đang giao'],
  ['delivered', 'Đã giao hàng thành công'],
]
const STAGE_INDEX = { awaiting_pickup: 0, pickup_assigned: 1, picking_up: 1, picked_up: 2, at_warehouse: 3, delivery_assigned: 4, out_for_delivery: 5, delivered: 6 }
export function orderProgress(stage, events = [], accepted = true) {
  // On a retry use the current stage; old attempts belong in the audit log.
  let current = STAGE_INDEX[stage] ?? -1
  if (stage === 'failed') {
    const failure = [...events].reverse().find(e => /(?:pickup|delivery)_failed/.test(e.type))
    current = failure?.type === 'delivery_failed' ? 5 : failure ? 1 : Math.max(0, ...events.map(e => STAGE_INDEX[e.type] ?? -1))
  }
  if (!accepted) current = -1
  if (['return_pending','return_assigned','returning','returned','return_failed'].includes(stage)) {
    const keys = [['created','GK đã tiếp nhận đơn'],['picked_up','Tài xế đã nhận hàng'],['at_warehouse','Hàng đã được bàn giao tại kho'],['delivery_cancelled','Đã hủy giao sau 3 lần thất bại'],['return_assigned','Đã phân xe hoàn về người gửi'],['returning','Đang hoàn hàng'],['returned','Đã hoàn hàng cho người gửi']]
    const at = !accepted ? -1 : { return_pending: 3, return_assigned: 4, returning: 5, return_failed: 5, returned: 6 }[stage]
    return keys.map(([key,label],index)=>({ key,label,reached:index<=at,state:index>at?'pending':index===at?(stage==='return_failed'?'issue':'current'):'completed',event:index<=at?[...events].reverse().find(e=>e.type===key):null }))
  }
  return ORDER_MILESTONES.map(([key, label], index) => {
    const match = [...events].reverse().find(e => e.type === key || key.endsWith('_assigned') && e.type === key.replace('_assigned', '_reassigned'))
    const reached = index <= current
    return { key, label, state: !reached ? 'pending' : index === current ? (stage === 'failed' ? 'issue' : 'current') : 'completed',
      event: reached ? match : null, reached }
  })
}
