import { useContext } from 'react'
import { Link } from 'react-router-dom'
import { ArrowRight, Bell, Package, Plus, Truck } from 'lucide-react'
import { useAuth } from '../../../shared/auth/AuthProvider.jsx'
import { useApi } from '../../../shared/api/client.js'
import Notices from '../context/NotificationsContext.js'
import { bookingStatus } from '../lib/status.js'
export default function CustomerQuickHome(){
 const {user}=useAuth(),notices=useContext(Notices),{data,error,reload}=useApi(user?'/api/customer/bookings?offset=0':null,10000)
 return <div className="c-container c-compact-home"><section className="c-compact-welcome"><div><p className="c-eyebrow">GK GIAO HÀNG</p><h1>{user?`Chào ${user.display_name.trim().split(' ').at(-1)}`:'Gửi hàng cùng GK'}</h1></div><div className="c-quick-actions"><Link to="/send" className="c-quick-action primary"><Plus size={26}/><b>Tạo đơn</b><ArrowRight size={18}/></Link><Link to="/orders" className="c-quick-action"><Package size={25}/><b>Đơn của tôi</b><ArrowRight size={18}/></Link></div></section>
 {user?<><div className="c-quick-stats"><Link to="/orders"><Package size={20}/><span>Đơn đã tạo<b>{data?data.total:'–'}</b></span></Link><Link to="/notifications"><Bell size={20}/><span>Thông báo chưa đọc<b>{notices.data?notices.data.unread:'–'}</b></span></Link></div><section className="c-compact-recent"><header><h2>Đơn gần đây</h2><Link to="/orders">Xem tất cả<ArrowRight size={16}/></Link></header>{error&&<div className="c-alert error" role="alert">{error.message}<button className="c-btn outline" onClick={reload}>Thử lại</button></div>}{!data&&!error&&<p className="c-loading">Đang tải đơn…</p>}{data?.items.length===0&&<div className="c-panel c-quick-empty"><Truck size={28}/><h3>Chưa có đơn hàng</h3><Link className="c-btn" to="/send">Tạo đơn đầu tiên</Link></div>}<div className="c-compact-orders">{data?.items.slice(0,6).map(order=><Link key={order.id} to={`/orders/${order.id}`} className="c-panel c-compact-order"><div><b>{order.order_code||'Đang gửi điều phối'}</b><span className="c-status">{bookingStatus(order)}</span></div><p><span>Lấy</span>{order.payload.pickupAddress}</p><p><span>Giao</span>{order.payload.address}</p><footer>Xem hành trình<ArrowRight size={17}/></footer></Link>)}</div></section></>:<section className="c-panel c-guest-actions"><h2>Tài khoản GK</h2><Link className="c-btn" to="/login">Đăng nhập</Link><Link className="c-btn outline" to="/login?mode=register">Tạo tài khoản</Link></section>}
 </div>
}
