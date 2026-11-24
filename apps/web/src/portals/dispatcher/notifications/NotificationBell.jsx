import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Bell, CheckCheck, ChevronRight, X } from 'lucide-react'
import { useNotifications, notificationTime } from './NotificationsProvider.jsx'

export default function NotificationBell() {
  const feed = useNotifications(), [open, setOpen] = useState(false), [busy, setBusy] = useState(false), wrapper = useRef(null), button = useRef(null), nav = useNavigate()
  useEffect(() => {
    if (!open) return
    const outside = e => { if (!wrapper.current?.contains(e.target)) setOpen(false) }
    const escape = e => { if (e.key === 'Escape') { setOpen(false); button.current?.focus() } }
    document.addEventListener('mousedown', outside); document.addEventListener('keydown', escape)
    return () => { document.removeEventListener('mousedown', outside); document.removeEventListener('keydown', escape) }
  }, [open])
  async function mark(id, href) {
    setBusy(true)
    try { await feed.read(id); if (href) { setOpen(false); nav(href) } } catch { /* Feed displays the error. */ } finally { setBusy(false) }
  }
  return <div className="notification-bell" ref={wrapper}>
    <button className={`icon-button bell-button${open ? ' selected' : ''}`} ref={button} aria-label={`Thông báo${feed.data?.unread ? `, ${feed.data.unread} chưa đọc` : ''}`} aria-expanded={open} aria-controls="dispatcher-notification-popover" onClick={() => setOpen(v => !v)}><Bell size={20} />{feed.data?.unread > 0 && <span className="notification-count">{feed.data.unread > 99 ? '99+' : feed.data.unread}</span>}</button>
    {open && <section className="notification-popover" id="dispatcher-notification-popover" role="dialog" aria-label="Thông báo điều phối"><header><div><h2>Thông báo</h2><p>{feed.data ? `${feed.data.unread} thông báo chưa đọc` : 'Đang tải cập nhật'}</p></div><button className="icon-button" aria-label="Đóng danh sách thông báo" onClick={() => { setOpen(false); button.current?.focus() }}><X size={18} /></button></header>
      {(feed.error || feed.actionError) && <div className="callout red" role="alert">{feed.error?.message || feed.actionError}<button className="text-button" onClick={feed.reload}>Thử lại</button></div>}
      {feed.data?.unread > 0 && <button className="notification-mark-all" disabled={busy} onClick={() => mark()}><CheckCheck size={16} />Đánh dấu tất cả đã đọc</button>}
      <div className="notification-popover-items">{!feed.data && !feed.error && <p className="empty">Đang tải thông báo…</p>}{feed.data?.items.length === 0 && <div className="notification-empty"><Bell size={28} /><strong>Bạn đã cập nhật hết.</strong><p>Đơn mới và các mốc vận hành sẽ xuất hiện tại đây.</p></div>}{feed.data?.items.map(item => <button className={`notification-entry${item.read_at ? '' : ' unread'}`} key={item.id} disabled={busy} onClick={() => mark(item.id, item.href)}><span className={`notification-symbol ${item.level}`}><Bell size={17} /></span><span><strong>{item.title}</strong><p>{item.message}</p><time>{notificationTime(item.created_at)}</time></span>{!item.read_at && <i aria-label="Chưa đọc" />}</button>)}</div>
      <footer><Link to="/notifications" onClick={() => setOpen(false)}>Xem tất cả thông báo<ChevronRight size={16} /></Link></footer>
    </section>}
  </div>
}
