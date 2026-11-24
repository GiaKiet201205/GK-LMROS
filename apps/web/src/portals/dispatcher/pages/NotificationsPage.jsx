import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Bell, CheckCheck, Circle, Check, RefreshCw } from 'lucide-react'
import { api, useApi } from '../../../shared/api/client.js'
import { PageHead } from '../../../shared/components/ui.jsx'
import { useNotifications, notificationTime } from '../notifications/NotificationsProvider.jsx'

export default function NotificationsPage() {
  const [unreadOnly, setUnreadOnly] = useState(false), [offset, setOffset] = useState(0), [busy, setBusy] = useState(false), [actionError, setActionError] = useState('')
  const { data, error, reload } = useApi(`/api/notifications?unreadOnly=${unreadOnly}&offset=${offset}&limit=20`, 5000), feed = useNotifications(), nav = useNavigate()
  useEffect(() => { if (data && offset > 0 && offset >= data.total) setOffset(Math.max(0, Math.floor(Math.max(0, data.total - 1) / 20) * 20)) }, [data, offset])
  async function action(item, unread = false, navigate = false) {
    setBusy(true); setActionError('')
    try {
      if (unread) await api(`/api/notifications/${item.id}/unread`, { method: 'POST', body: {} })
      else await feed.read(item?.id)
      reload(); feed.reload(); if (navigate) nav(item.href)
    } catch (e) { setActionError(e.message) } finally { setBusy(false) }
  }
  return <div className="page notifications-page"><PageHead title="Thông báo vận hành"><button className="btn ghost" onClick={reload}><RefreshCw size={16} />Làm mới</button><button className="btn" disabled={busy || !feed.data?.unread} onClick={() => action()}><CheckCheck size={17} />Đọc tất cả</button></PageHead>
    <div className="notification-page-toolbar"><div className="segmented"><button className={!unreadOnly ? 'selected' : ''} onClick={() => { setUnreadOnly(false); setOffset(0) }}>Tất cả</button><button className={unreadOnly ? 'selected' : ''} onClick={() => { setUnreadOnly(true); setOffset(0) }}>Chưa đọc {feed.data?.unread > 0 && <span>{feed.data.unread}</span>}</button></div><span className="note">{data?.total || 0} thông báo{unreadOnly ? ' chưa đọc' : ''}</span></div>
    {(error || actionError) && <div className="callout red" role="alert">{error?.message || actionError}</div>}
    <section className="card notification-history">{!data && !error && <p className="empty">Đang tải thông báo…</p>}{data?.items.length === 0 && <div className="notification-empty"><Bell size={34} /><h2>{unreadOnly ? 'Không còn thông báo chưa đọc' : 'Chưa có cập nhật mới'}</h2><p>Đơn mới, phân công và các tình huống cần xử lý sẽ được ghi nhận tại đây.</p><Link className="btn ghost" to="/">Về tổng quan</Link></div>}{data?.items.map(item => <article className={`notification-history-row${item.read_at ? '' : ' unread'}`} key={item.id}><span className={`notification-symbol ${item.level}`}><Bell size={20} /></span><button className="notification-content-button" disabled={busy} onClick={() => action(item, false, true)}><strong>{item.title}</strong><p>{item.message}</p><time>{notificationTime(item.created_at)}</time></button><button className="icon-button" disabled={busy} aria-label={`${item.read_at ? 'Đánh dấu chưa đọc' : 'Đánh dấu đã đọc'}: ${item.title}`} title={item.read_at ? 'Đánh dấu chưa đọc' : 'Đánh dấu đã đọc'} onClick={() => action(item, Boolean(item.read_at))}>{item.read_at ? <Check size={18} /> : <Circle size={15} />}</button></article>)}</section>
    {data?.total > 20 && <div className="toolbar"><button className="btn ghost" disabled={!offset} onClick={() => setOffset(v => v - 20)}>Trang trước</button><span>Trang {offset / 20 + 1}</span><button className="btn ghost" disabled={offset + 20 >= data.total} onClick={() => setOffset(v => v + 20)}>Trang sau</button></div>}
  </div>
}
