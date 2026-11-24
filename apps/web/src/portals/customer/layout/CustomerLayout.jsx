import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { Bell, Home, Package, Plus, User, X } from 'lucide-react'
import { useAuth } from '../../../shared/auth/AuthProvider.jsx'
import { useApi } from '../../../shared/api/client.js'
import CustomerBrand from '../components/CustomerBrand.jsx'
import CustomerFooter from '../components/CustomerFooter.jsx'
import Notices from '../context/NotificationsContext.js'
import { EVENT_LABELS } from '../lib/status.js'

export default function CustomerLayout() {
  const { user, logout } = useAuth(), location = useLocation(), nav = useNavigate()
  const [error, setError] = useState(''), [toast, setToast] = useState(null)
  const notices = useApi(user ? '/api/customer/notifications' : null, 5000), last = useRef(null)
  useEffect(() => { setError('');const target=location.hash?document.getElementById(location.hash.slice(1)):null;if(target)target.scrollIntoView({block:'start',behavior:'instant'});else window.scrollTo({top:0,behavior:'instant'}) }, [location.pathname,location.hash])
  useEffect(() => { last.current = null; setToast(null) }, [user?.id])
  useEffect(() => {
    if (!notices.data) return
    const newest = notices.data?.items?.[0]
    if (newest && last.current != null && newest.id !== last.current && !newest.read_at) setToast(newest)
    last.current = newest?.id ?? 0
  }, [notices.data])
  async function signout() { try { await logout(); nav('/') } catch (e) { setError(e.message) } }
  return <Notices.Provider value={notices}><div className="gk-customer"><a className="c-skip" href="#customer-main">Đến nội dung chính</a>
    <header className="c-header"><div className="c-header-inner"><CustomerBrand /><Link className="c-compact-account" to={user?'/account':'/login'} aria-label={user?'Tài khoản của tôi':'Đăng nhập'}><User size={21}/>{!user&&<span>Đăng nhập</span>}</Link>
      <nav className="c-nav" aria-label="Điều hướng khách hàng"><NavLink to="/" end>Trang chủ</NavLink><NavLink to="/orders">Đơn của tôi</NavLink><NavLink to="/send">Gửi hàng</NavLink>{user ? <><NavLink to="/notifications" className="c-bell"><Bell size={18} />Thông báo{notices.data && <span className={`c-unread${notices.data.unread ? "" : " is-empty"}`} aria-label={`${notices.data.unread} thông báo chưa đọc`}>{notices.data.unread > 99 ? '99+' : notices.data.unread}</span>}</NavLink><NavLink to="/account" className="c-account"><User size={18} /><span>{user.display_name.split(' ').at(-1)}</span></NavLink><button className="c-text-button" onClick={signout}>Đăng xuất</button></> : <><Link to="/login" className="c-login-link">Đăng nhập</Link><Link to="/login?mode=register" className="c-btn small">Tạo tài khoản</Link></>}</nav>
    </div></header>
    {error && <p className="c-alert error" role="alert">{error}</p>}
    {toast && <div className="c-toast" role="status"><Bell size={20} /><Link to={`/orders/${toast.booking_id}`} onClick={() => setToast(null)}><b>{EVENT_LABELS[toast.type] || 'Đơn hàng vừa cập nhật'}</b><small>{toast.order_code}</small></Link><button aria-label="Đóng thông báo" onClick={() => setToast(null)}><X size={18} /></button></div>}
    <main id="customer-main"><Outlet /></main>
    <CustomerFooter/>
    <nav className="c-mobile-nav" aria-label="Lối tắt"><span className="c-rail-mark" aria-hidden="true">GK</span><NavLink to="/" end><Home size={20}/>Trang chủ</NavLink><NavLink to="/orders"><Package size={19} />Đơn của tôi</NavLink><NavLink to="/send"><Plus size={21} />Tạo đơn</NavLink><NavLink to="/notifications"><Bell size={19} />Thông báo{notices.data?.unread > 0 && <span className="c-mobile-count" aria-label={`${notices.data.unread} thông báo chưa đọc`}>{notices.data.unread > 99 ? "99+" : notices.data.unread}</span>}</NavLink><NavLink to="/account"><User size={19} />Tài khoản</NavLink></nav>
  </div></Notices.Provider>
}
