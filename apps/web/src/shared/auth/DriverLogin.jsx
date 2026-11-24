import { useState } from 'react'
import { ArrowRight, Eye, EyeOff, LoaderCircle, LockKeyhole, Truck, UserRound } from 'lucide-react'
import Brand from '../components/Brand.jsx'

export default function DriverLogin({ username, password, onUsername, onPassword, onSubmit, error, busy }) {
 const [visible,setVisible]=useState(false),[support,setSupport]=useState(false)
 return <main className="driver-auth-page"><div className="driver-login-shell">
  <header className="driver-login-header"><Brand compact/><span className="driver-login-badge"><Truck size={17} aria-hidden="true"/>TÀI XẾ</span></header>
  <div className="driver-login-body"><h1>Đăng nhập tài xế</h1>
   <form className="driver-login-form" onSubmit={onSubmit} aria-busy={busy}>
    <div className="field"><label htmlFor="driver-username">Mã tài xế</label><div className="driver-login-input"><UserRound size={19} aria-hidden="true"/><input id="driver-username" name="username" type="text" inputMode="text" autoComplete="username" autoCapitalize="characters" autoCorrect="off" spellCheck={false} enterKeyHint="next" placeholder="TX-001" required maxLength={100} value={username} disabled={busy} aria-invalid={!!error} aria-describedby={error?'driver-login-error':undefined} onChange={e=>onUsername(e.target.value)}/></div></div>
    <div className="field"><label htmlFor="driver-password">Mật khẩu</label><div className="driver-login-input"><LockKeyhole size={19} aria-hidden="true"/><input id="driver-password" name="password" type={visible?'text':'password'} autoComplete="current-password" enterKeyHint="go" autoCapitalize="none" autoCorrect="off" spellCheck={false} required maxLength={256} value={password} disabled={busy} aria-invalid={!!error} aria-describedby={error?'driver-login-error':undefined} onChange={e=>onPassword(e.target.value)}/><button type="button" className="driver-password-toggle" aria-label={visible?'Ẩn mật khẩu':'Hiện mật khẩu'} aria-pressed={visible} disabled={busy} onPointerDown={e=>e.preventDefault()} onClick={()=>setVisible(v=>!v)}>{visible?<EyeOff size={20}/>:<Eye size={20}/>}</button></div></div>
    {error&&<div id="driver-login-error" className="callout red" role="alert">{error}</div>}
    <button className="btn driver-login-submit" type="submit" disabled={busy}>{busy?<><LoaderCircle className="driver-login-spinner" size={19} aria-hidden="true"/>Đang đăng nhập…</>:<>Đăng nhập<ArrowRight size={19} aria-hidden="true"/></>}</button>
   </form>
   <button type="button" className="driver-login-support" aria-expanded={support} aria-controls="driver-login-support-text" onClick={()=>setSupport(v=>!v)}>Cần hỗ trợ đăng nhập?</button>
   {support&&<p id="driver-login-support-text" className="driver-login-support-text">Liên hệ điều phối viên GK để được hỗ trợ tài khoản hoặc cấp lại mật khẩu.</p>}
  </div><footer className="driver-login-footer">GK · Đồng hành cùng tài xế</footer>
 </div></main>
}
