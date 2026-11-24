import DriverLogin from './DriverLogin.jsx'
import { useState } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { ShieldCheck, Route, Truck } from 'lucide-react'
import { useAuth } from './AuthProvider.jsx'
import { IS_DRIVER } from '../config/portal.js'
import Brand from '../components/Brand.jsx'

const HOME = '/'

export default function StaffLogin() {
  const driver = IS_DRIVER
  const { user, login, loading } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const nav = useNavigate(), location = useLocation()
  if (loading) return <div className="auth-page">Đang kiểm tra đăng nhập…</div>
  if (user) return <Navigate to={HOME} replace />
  const submit = async e => {
    e.preventDefault(); setBusy(true); setError('')
    try {
      await login(username.trim(), password)
      const from = location.state?.from
      const safeFrom = from?.startsWith('/') && !from.startsWith('//') && !from.includes('login')
      nav(safeFrom ? from : HOME, { replace: true })
    } catch (e) { setError(e.message) } finally { setBusy(false) }
  }
  if (driver) return <DriverLogin username={username} password={password} onUsername={setUsername} onPassword={setPassword} onSubmit={submit} error={error} busy={busy} />
  return <div className="auth-page auth-layout">
    <section className="auth-story">
      <Brand />
      <div className="auth-story-copy"><span className="eyebrow">GK · KẾT NỐI MỌI HÀNH TRÌNH</span>
        <h2>{driver ? 'Mỗi điểm đến,\nmột hành trình tốt hơn.' : 'Điều phối chủ động.\nHành trình hiệu quả.'}</h2>
        <p>{driver ? 'Tuyến đường rõ ràng, cập nhật dễ dàng. Luôn kết nối cùng đội ngũ điều phối.' : 'Kết nối đơn hàng, đội xe và tuyến đường trong một không gian làm việc thống nhất.'}</p>
      </div>
      <div className="route-art" aria-hidden="true"><svg viewBox="0 0 480 180"><path className="route-guide" d="M25 135H125Q155 135 155 105V75Q155 45 185 45H280Q310 45 310 75V105Q310 135 340 135H455" /><circle cx="25" cy="135" r="9"/><circle cx="235" cy="45" r="9"/><circle cx="455" cy="135" r="9"/></svg><span><Truck size={22} /></span></div>
      <div className="auth-features"><span><Route size={18} />Tối ưu từng tuyến</span><span><ShieldCheck size={18} />Kết nối tin cậy</span></div>
      <small className="auth-footer">GK · Nền tảng quản lý vận tải</small>
    </section>
    <section className="auth-form-panel"><form className="card auth-card" onSubmit={submit}>
    <span className="eyebrow">{driver ? 'KHÔNG GIAN TÀI XẾ' : 'TRUNG TÂM ĐIỀU PHỐI'}</span><h1>Đăng nhập {driver ? 'tài xế' : 'quản lý'}</h1>
    <div className="field"><label htmlFor="username">Tên đăng nhập</label><input id="username" autoComplete="username" required maxLength={100} value={username} onChange={e => setUsername(e.target.value)} /></div>
    <div className="field"><label htmlFor="password">Mật khẩu</label><input id="password" type="password" autoComplete="current-password" required maxLength={256} value={password} onChange={e => setPassword(e.target.value)} /></div>
    {error && <div className="callout red" role="alert">{error}</div>}
    <button className="btn" disabled={busy}>{busy ? 'Đang đăng nhập…' : 'Đăng nhập'}</button>
    <p className="auth-help">Cần hỗ trợ truy cập? Liên hệ quản trị viên GK.</p>
  </form><small className="auth-panel-footer">GK · Đồng hành trên mọi tuyến đường</small></section></div>
}
