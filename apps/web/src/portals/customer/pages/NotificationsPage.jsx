import { useContext, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Bell, CheckCircle2, Package } from 'lucide-react'
import { api } from '../../../shared/api/client.js'
import Notices from '../context/NotificationsContext.js'
import LoadState from '../components/LoadState.jsx'
import { EVENT_LABELS, when } from '../lib/status.js'

export default function CustomerNotifications() {
  const notices = useContext(Notices), nav = useNavigate(), [error, setError] = useState(''), [busy, setBusy] = useState(false)
  async function read(id, booking) { setBusy(true); try { await api('/api/customer/notifications/read', { method: 'POST', body: id ? { id } : {} }); notices.reload(); if (booking) nav(`/orders/${booking}`) } catch (e) { setError(e.message) } finally { setBusy(false) } }
  return <div className="c-container c-page c-notifications-page"><div className="c-page-heading c-heading-row"><div><p className="c-eyebrow">NHỮNG ĐIỀU BẠN CẦN BIẾT</p><h1>Thông báo</h1><p>Cập nhật phân xe và các mốc mới trong hành trình đơn hàng.</p></div>{notices.data?.unread > 0 && <button className="c-btn outline" disabled={busy} onClick={() => read()}>Đánh dấu tất cả đã đọc</button>}</div><LoadState {...notices} />{error && <p className="c-alert error">{error}</p>}
    <section className="c-panel c-notifications-list">{notices.data?.items.length === 0 && <div className="c-empty"><Bell size={35} /><h2>Chưa có thông báo mới.</h2><p>Khi đơn được tiếp nhận hoặc tài xế cập nhật tiến độ, thông báo sẽ xuất hiện ở đây.</p></div>}{notices.data?.items.map(item => <button key={item.id} className={`c-notification${item.read_at ? '' : ' unread'}`} disabled={busy} onClick={() => read(item.id, item.booking_id)}><span className="c-notification-icon">{item.type === 'delivered' ? <CheckCircle2 size={22} /> : <Package size={22} />}</span><span><b>{EVENT_LABELS[item.type] || 'Đơn hàng vừa được cập nhật'}</b><small>{item.order_code} · {when(item.created_at)}</small></span>{!item.read_at && <i aria-label="Chưa đọc" />}</button>)}</section><p className="c-field-hint">Hiển thị 100 thông báo gần nhất. Thông báo được lưu trong tài khoản để bạn xem lại trên mọi thiết bị.</p>
  </div>
}
