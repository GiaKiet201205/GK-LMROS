export const PAYERS={sender:'Người gửi',recipient:'Người nhận'}
export const PAYMENT_STATUS={paid:'Đã thanh toán',unpaid:'Chưa thanh toán',no_charge:'Miễn cước',refunded:'Đã hoàn cước',unverified:'Chưa có xác nhận thu tiền cũ'}
export function needsCollection(stop){return Boolean(stop?.payment_required)&&!stop.payment_acknowledged&&['delivery','pickup'].includes(stop.kind)&&(stop.kind==='delivery'||stop.payment_payer==='sender')&&!['paid','no_charge'].includes(stop.payment_status)}
