import { useAuth } from '../../../shared/auth/AuthProvider.jsx'
import { useEffect, useRef, useState } from 'react'
import { Link, Outlet, useLocation } from 'react-router-dom'
import { LayoutDashboard, Package, Warehouse, Truck, Users, Radio, Route, PlusCircle, Settings, Bell, Calendar, Wrench, Banknote, Menu, X, LogOut } from 'lucide-react'
import { useApi } from '../../../shared/api/client.js'
import Brand from '../../../shared/components/Brand.jsx'
import PreferencesProvider, { usePreferences } from '../preferences/PreferencesProvider.jsx'
import NotificationsProvider, { WorkspaceToast } from '../notifications/NotificationsProvider.jsx'
import NotificationBell from '../notifications/NotificationBell.jsx'
import '../styles/workspace.css'
import AdminAssistant from '../assistant/AdminAssistant.jsx'

import { WarehouseFilterContext as Ctx } from '../context/WarehouseFilterContext.js'

const NAV_GROUPS = [
  ['Vận hành', [['/', 'Tổng quan', LayoutDashboard], ['/orders', 'Đơn hàng', Package], ['/distribution', 'Phân phối đơn hàng', Truck], ['/live', 'Theo dõi hành trình', Radio], ['/routing', 'Điều phối tự động', Route]]],
  ['Nguồn lực', [['/warehouses', 'Kho', Warehouse], ['/vehicles', 'Đội xe', Truck], ['/drivers', 'Tài xế & ca làm', Users], ['/maintenance', 'Chi phí & bảo dưỡng', Wrench]]],
  ['Tài chính', [['/reports', 'Tài chính & thống kê', Banknote], ['/pricing', 'Cước giao hàng', Banknote]]],
  ['Hệ thống', [['/notifications', 'Thông báo', Bell], ['/settings', 'Cài đặt', Settings]]],
]
function activePath(path,to){return to==='/'?path==='/':to==='/orders'?path==='/orders'||/^\/orders\/\d+(?:\/|$)/.test(path):path===to||path.startsWith(to+'/')}


export default function Layout() {
  const { user } = useAuth()
  return <PreferencesProvider key={user.id}><NotificationsProvider><DispatcherShell /></NotificationsProvider></PreferencesProvider>
}

function DispatcherShell() {
  const { user, logout } = useAuth()
  const workspace = usePreferences(),location=useLocation(),sideRef=useRef(null),menuRef=useRef(null)
  const [drawer,setDrawer]=useState(false),[mobile,setMobile]=useState(()=>window.matchMedia('(max-width:1099px)').matches)
  const [logoutError, setLogoutError] = useState('')
  const [warehouse, setWarehouse] = useState('')
  const { data: whs } = useApi('/api/warehouses')
  const { data: vehicles } = useApi('/api/vehicles')
  const today = new Date().toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric' })
  useEffect(() => { if (workspace.ready) setWarehouse(workspace.preferences.defaultWarehouse ? String(workspace.preferences.defaultWarehouse) : '') }, [workspace.ready, workspace.preferences.defaultWarehouse])
  useEffect(()=>{if(whs&&warehouse&&!whs.some(w=>String(w.id)===warehouse))setWarehouse('')},[whs,warehouse])
  useEffect(()=>{const media=window.matchMedia('(max-width:1099px)');const update=()=>{setMobile(media.matches);if(!media.matches)setDrawer(false)};media.addEventListener('change',update);return()=>media.removeEventListener('change',update)},[])
  useEffect(()=>{setDrawer(false);window.scrollTo({top:0,behavior:'instant'})},[location.pathname])
  useEffect(() => {
    if (!mobile || !drawer) return
    const previous = menuRef.current || document.activeElement
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    // Wait until the drawer visibility transition allows keyboard focus.
    const focusTimer = window.setTimeout(() => sideRef.current?.querySelector('.side-close')?.focus(), 220)
    return () => {
      window.clearTimeout(focusTimer)
      document.body.style.overflow = overflow
      if (previous?.isConnected && previous.getClientRects().length) previous.focus()
    }
  }, [mobile, drawer])
  function drawerKeys(e){if(e.key==='Escape')setDrawer(false);if(!mobile||!drawer||e.key!=='Tab')return;const controls=[...sideRef.current.querySelectorAll('a[href],button:not(:disabled)')].filter(el=>el.getClientRects().length);if(e.shiftKey&&document.activeElement===controls[0]){e.preventDefault();controls.at(-1)?.focus()}else if(!e.shiftKey&&document.activeElement===controls.at(-1)){e.preventDefault();controls[0]?.focus()}}
  const signout=()=>logout().catch(e=>setLogoutError(e.message))
  return (
    <Ctx.Provider value={{ warehouse }}>
      <div className="shell dispatcher-shell" data-theme={workspace.theme} data-density={workspace.preferences.density}>
        <a className="dispatcher-skip" href="#dispatcher-main">Đến nội dung chính</a>
        {mobile&&drawer&&<button className="side-backdrop" aria-label="Đóng nền điều hướng" onClick={()=>setDrawer(false)} tabIndex={-1}/>}
        <aside ref={sideRef} id="dispatcher-navigation" className={`side${drawer?' is-open':''}`} inert={mobile&&!drawer?'':undefined} aria-hidden={mobile&&!drawer?true:undefined} role={mobile&&drawer?'dialog':undefined} aria-modal={mobile&&drawer?true:undefined} aria-label="Điều hướng điều phối" onKeyDown={drawerKeys}>
          <div className="side-header"><Link className="side-brand-link" to="/" aria-label="GK · Tổng quan" onClick={()=>setDrawer(false)}><Brand /></Link><button className="side-close" aria-label="Đóng điều hướng" onClick={()=>setDrawer(false)}><X size={20}/></button></div>
          <Link to="/orders/new" className={`side-create${location.pathname==='/orders/new'?' current':''}`} aria-current={location.pathname==='/orders/new'?'page':undefined} onClick={()=>setDrawer(false)}><PlusCircle size={18}/>Tạo đơn mới</Link>
          <nav className="nav" aria-label="Các mục điều phối">
            {NAV_GROUPS.map(([title,items])=><div className="side-group" key={title}><p className="side-group-title">{title}</p>{items.map(([to,label,Icon])=><Link key={to} to={to} className={activePath(location.pathname,to)?'active':undefined} aria-current={activePath(location.pathname,to)?'page':undefined} onClick={()=>setDrawer(false)}><Icon size={18}/><span>{label}</span></Link>)}</div>)}
          </nav>
          <div className="side-foot"><div className="side-counts"><span><Truck size={14}/>{vehicles?vehicles.length:'–'} xe</span><span><Warehouse size={14}/>{whs?whs.length:'–'} kho</span></div><div className="side-mobile-account"><span>{user.display_name}</span><button aria-label="Đăng xuất tài khoản" onClick={signout}><LogOut size={17}/></button></div></div>
        </aside>
        <div className="main" id="dispatcher-main" tabIndex={-1} inert={mobile&&drawer?'':undefined}>
          <header className="top">
            <button ref={menuRef} className="side-menu" aria-label="Mở điều hướng" aria-controls="dispatcher-navigation" aria-expanded={drawer} onClick={()=>setDrawer(true)}><Menu size={21}/></button>
            <span className="chip top-date"><Calendar size={16} />{today}</span>
            <span className="chip">
              <Warehouse size={16} />
              <select value={warehouse} onChange={(e) => setWarehouse(e.target.value)} aria-label="Lọc theo kho">
                <option value="">Tất cả kho</option>
                {(whs || []).map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
              </select>
            </span>
            <span className="grow" />
            <NotificationBell />
            <div className="user"><i aria-hidden="true">{user.display_name?.trim().split(/\s+/).slice(-1)[0]?.charAt(0)}</i><div><b>{user.display_name}</b><small>Điều phối viên GK</small></div></div>
          <button className="btn ghost dispatcher-logout" onClick={signout}>Đăng xuất</button>
            {logoutError && <span role="alert">{logoutError}</span>}
          </header>
          <Outlet />
          <WorkspaceToast />
          <AdminAssistant warehouse={warehouse} />
        </div>
      </div>
    </Ctx.Provider>
  )
}
