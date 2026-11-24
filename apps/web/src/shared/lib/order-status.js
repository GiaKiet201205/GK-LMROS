export const EVENT_LABELS = { warehouse_assigned:"Đã xác định kho tiếp quản", payment_unpaid:'Cước chưa thanh toán',payment_collected:"Đã thanh toán cước",payment_payer_updated:"Đã cập nhật người thanh toán",payment_refunded:"Đã hoàn cước",
  waiting_return_dispatch: 'Đang chờ phân chuyến hoàn lại', picking_up: 'Tài xế đã xuất phát lấy hàng', delivery_cancelled: 'Đã hủy giao sau 3 lần thất bại · chờ hoàn hàng',
  return_assigned: 'Đã phân chuyến hoàn về người gửi', return_reassigned: 'Đã đổi phân công hoàn hàng', returning: 'Đang hoàn về người gửi',
  approaching_return: 'Tài xế đang đến trả hàng', returned: 'Đã hoàn hàng cho người gửi', return_failed: 'Chưa hoàn được hàng',
  return_at_warehouse: 'Hàng hoàn đã về kho · chờ phân lại', failed_at_warehouse: 'Hàng giao chưa thành công đã về kho', 
  price_updated: 'Đã cập nhật cước giao hàng',
  schedule_updated: 'Đã cập nhật lịch hẹn lấy/giao',
  pickup_reassigned: 'Đã đổi tài xế/xe lấy hàng', delivery_reassigned: 'Đã đổi tài xế/xe giao hàng', assignment_updated: 'Thông tin tài xế/xe đã cập nhật',
  created: 'GK đã tiếp nhận đơn', pickup_assigned: 'Đã phân tài xế đến lấy', approaching_pickup: 'Tài xế đang đến lấy hàng',
  picked_up: 'Tài xế đã nhận hàng', at_warehouse: 'Hàng đã được bàn giao tại kho', delivery_assigned: 'Đã phân xe giao hàng',
  out_for_delivery: 'Hàng đã xuất kho, đang giao', approaching_delivery: 'Tài xế đang đến người nhận', delivered: 'Đã giao hàng thành công',
  pickup_failed: 'Chưa lấy được hàng', delivery_failed: 'Chưa giao được hàng', waiting_dispatch: 'Đang chờ điều phối phân công lại',
}
export const STAGE_LABELS = { picking_up: 'Đang đi lấy hàng', return_pending: 'Đã hủy giao · chờ hoàn', return_assigned: 'Chờ xuất hoàn', returning: 'Đang hoàn hàng', returned: 'Đã hoàn cho người gửi', return_failed: 'Hoàn chưa thành công · đang về kho', awaiting_pickup: 'Chờ phân tài xế lấy', pickup_assigned: 'Đã phân tài xế lấy', picked_up: 'Hàng đang về kho', at_warehouse: 'Tại kho · chờ phân giao', delivery_assigned: 'Đã phân xe giao', out_for_delivery: 'Đang giao hàng', delivered: 'Đã giao thành công', failed: 'Cần xử lý thêm' }
export const NEXT_LABELS = { picking_up: 'Tài xế xác nhận nhận hàng và đưa về kho', return_pending: 'Điều phối phân hoàn sau khi hàng về kho', return_assigned: 'Tài xế nhận hàng hoàn và xuất kho', returning: 'Bàn giao lại cho người gửi', return_failed: 'Đưa hàng về kho để điều phối xử lý',  awaiting_pickup: 'Điều phối sẽ chọn xe và tài xế phù hợp', pickup_assigned: 'Tài xế bắt đầu đến lấy hàng', picked_up: 'Bàn giao hàng tại kho GK', at_warehouse: 'Điều phối duyệt chuyến giao', delivery_assigned: 'Tài xế nhận hàng và xuất kho', out_for_delivery: 'Tài xế hoàn tất giao cho người nhận', failed: 'Điều phối kiểm tra và sắp xếp phương án tiếp theo' }
export const when = date => date ? new Date(date).toLocaleString('vi-VN', { hour: '2-digit', minute: '2-digit', day: '2-digit', month: '2-digit', year: 'numeric' }) : 'Chưa cập nhật'
export function bookingStatus(booking) {
  if (booking.state === 'rejected') return 'Yêu cầu chưa hợp lệ'
  if (booking.state === 'pending') return 'Đã lưu · đang gửi điều phối'
  const last = booking.events?.at(-1)
  if (['pickup_assigned','picking_up'].includes(booking.snapshot?.stage) && last?.type === 'approaching_pickup') return EVENT_LABELS.approaching_pickup
  if (booking.snapshot?.stage === 'return_pending' && !booking.snapshot.in_warehouse) return 'Đã hủy giao · hàng đang về kho để hoàn'
  if (booking.snapshot?.stage === 'out_for_delivery' && last?.type === 'approaching_delivery') return EVENT_LABELS.approaching_delivery
  return STAGE_LABELS[booking.snapshot?.stage] || 'Đã gửi điều phối'
}
export function assignedDriver(snapshot = {}) {
  const stage = snapshot.stage
  const delivery = ['delivery_assigned', 'out_for_delivery', 'delivered'].includes(stage) || stage === 'failed' && snapshot.delivery_driver
  if (['awaiting_pickup', 'at_warehouse', 'return_pending'].includes(stage)) return null
  const returning = ['return_assigned','returning','return_failed','returned'].includes(stage)
  const phase = returning ? 'return' : delivery ? 'delivery' : 'pickup'
  if (!snapshot[`${phase}_driver`] && !snapshot[`${phase}_plate`]) return null
  return { name: snapshot[`${phase}_driver`], phone: snapshot[`${phase}_driver_phone`], plate: snapshot[`${phase}_plate`], label: returning ? 'Tài xế hoàn hàng' : delivery ? 'Tài xế giao hàng' : 'Tài xế lấy hàng' }
}
