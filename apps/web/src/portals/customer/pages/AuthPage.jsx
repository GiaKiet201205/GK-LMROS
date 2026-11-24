import { validName,validPhone,normalizePhone,cleanName } from '../../../shared/lib/validation.js'
import { useState } from 'react'
import { Navigate, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import GoogleSignIn from '../components/GoogleSignIn.jsx'
import { useAuth } from '../../../shared/auth/AuthProvider.jsx'

export default function CustomerLogin() {
  const { user, loading, login, register } = useAuth(), [params, setParams] = useSearchParams(), nav = useNavigate(), location = useLocation()
  const registering = params.get('mode') === 'register'
  const [googleProfile,setGoogleProfile]=useState(false)
  const [name, setName] = useState(''), [phone, setPhone] = useState(''), [password, setPassword] = useState(''), [repeat, setRepeat] = useState(''), [busy, setBusy] = useState(false), [error, setError] = useState('')
  if (loading) return <div className="c-loading">Đang kiểm tra đăng nhập…</div>
  const from = location.state?.from
  const destination = from?.startsWith('/') && !from.startsWith('//') && !from.includes('login') ? from : '/orders'
  if (user) return <Navigate to={destination} replace />
  async function submit(e) {
    e.preventDefault(); setBusy(true); setError('')
    try {
      if(!validPhone(phone))throw new Error('Số điện thoại phải gồm 10 chữ số, bắt đầu bằng 0.');
      if(!password.trim())throw new Error('Nhập mật khẩu.');
      if(registering&&(!validName(name)||password.length<8||password.length>128))throw new Error(!validName(name)?'Họ tên từ 2 đến 100 ký tự và phải có chữ.':'Mật khẩu từ 8 đến 128 ký tự.');
      if (registering) { if (password !== repeat) throw new Error('Mật khẩu nhập lại chưa khớp.'); await register({ name:cleanName(name), phone:normalizePhone(phone), password }) } else await login(normalizePhone(phone), password)
      nav(destination, { replace: true })
    } catch (e) { setError(e.message) } finally { setBusy(false) }
  }
  return <div className="c-container c-auth"><section className="c-auth-intro"><p className="c-eyebrow">KHÔNG GIAN KHÁCH HÀNG GK</p><h1>Gửi một kiện hàng.<br /><em>An tâm cả hành trình.</em></h1></section>
    <section className="c-panel c-auth-form"><div className="c-auth-tabs"><button disabled={googleProfile} className={!registering ? 'selected' : ''} onClick={() => { setParams({}, { state: location.state }); setError('') }}>Đăng nhập</button><button disabled={googleProfile} className={registering ? 'selected' : ''} onClick={() => { setParams({ mode: 'register' }, { state: location.state }); setError('') }}>Tạo tài khoản</button></div><h2>{googleProfile?'Thông tin liên hệ':registering ? 'Rất vui được đồng hành.' : 'Chào bạn trở lại.'}</h2><GoogleSignIn disabled={busy} onProfileChange={setGoogleProfile} onComplete={()=>nav(destination,{replace:true})}/>{!googleProfile&&<form onSubmit={submit}>
      {registering && <label className="c-field">Họ và tên<input autoComplete="name" required minLength={2} maxLength={100} value={name} onChange={e => setName(e.target.value)} /></label>}
      <label className="c-field">Số điện thoại<input type="tel" autoComplete="username" required inputMode="tel" maxLength={30} placeholder="0903 000 001" value={phone} onChange={e => setPhone(e.target.value)} /></label>
      <label className="c-field">Mật khẩu<input type="password" autoComplete={registering ? 'new-password' : 'current-password'} required minLength={registering ? 8 : undefined} maxLength={128} value={password} onChange={e => setPassword(e.target.value)} /></label>
      {registering && <><label className="c-field">Nhập lại mật khẩu<input type="password" autoComplete="new-password" required minLength={8} maxLength={128} value={repeat} onChange={e => setRepeat(e.target.value)} /></label></>}
      {error && <p className="c-alert error" role="alert">{error}</p>}<button className="c-btn" disabled={busy}>{busy ? 'Đang xử lý…' : registering ? 'Tạo tài khoản GK' : 'Đăng nhập'}</button></form>}
    </section></div>
}
