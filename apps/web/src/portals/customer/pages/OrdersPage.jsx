import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Package, Plus } from 'lucide-react'
import { useApi } from '../../../shared/api/client.js'
import LoadState from '../components/LoadState.jsx'
import { bookingStatus, when } from '../lib/status.js'

export default function CustomerOrders() {
  const [offset, setOffset] = useState(0), { data, error, reload } = useApi(`/api/customer/bookings?offset=${offset}`, 5000)
  return <div className="c-container c-page"><div className="c-page-heading c-heading-row"><div><p className="c-eyebrow">MỌI HÀNH TRÌNH, MỘT NƠI</p><h1>Đơn của tôi</h1><p>Tiến độ được cập nhật từ điều phối và tài xế GK.</p></div><Link to="/send" className="c-btn"><Plus size={18} />Tạo đơn mới</Link></div>
    <LoadState data={data} error={error} reload={reload} />
    {data?.items?.length === 0 && <div className="c-panel c-empty"><Package size={40} /><h2>Bắt đầu từ kiện hàng đầu tiên.</h2><p>Bạn chưa có đơn nào. Tạo đơn lấy–giao, rồi theo dõi toàn bộ hành trình ngay tại đây.</p><Link to="/send" className="c-btn">Tạo đơn đầu tiên</Link></div>}
    <div className="c-orders-grid">{data?.items?.map(booking => <Link key={booking.id} className="c-order-card" to={`/orders/${booking.id}`}><div className="c-order-card-top"><b>{booking.order_code || 'Yêu cầu đang đồng bộ'}</b><span className={`c-status ${booking.snapshot?.stage === 'delivered' ? 'done' : booking.state === 'rejected' || booking.snapshot?.stage === 'failed' ? 'attention' : ''}`}>{bookingStatus(booking)}</span></div><div className="c-summary-route"><div><i /><small>LẤY TẠI · {booking.payload.pickupName}</small><b>{booking.payload.pickupAddress}</b></div><div><i /><small>GIAO ĐẾN · {booking.payload.customerName}</small><b>{booking.payload.address}</b></div></div><div className="c-order-card-bottom"><span>{when(booking.created_at)}</span><strong>Xem hành trình</strong></div></Link>)}</div>
    {data?.total > 20 && <div className="c-pagination"><button className="c-btn outline" disabled={!offset} onClick={() => setOffset(v => v - 20)}>Trang trước</button><span>Trang {offset / 20 + 1} · {data.total} đơn</span><button className="c-btn outline" disabled={offset + 20 >= data.total} onClick={() => setOffset(v => v + 20)}>Trang sau</button></div>}
  </div>
}
