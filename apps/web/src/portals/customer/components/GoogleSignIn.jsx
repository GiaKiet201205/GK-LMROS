import { useEffect, useRef, useState } from 'react'
import { api } from '../../../shared/api/client.js'
import { useAuth } from '../../../shared/auth/AuthProvider.jsx'
import { validName, validPhone, cleanName, normalizePhone } from '../../../shared/lib/validation.js'

let library
function loadGoogle() {
 if(window.google?.accounts?.id)return Promise.resolve(window.google.accounts.id)
 if(library)return library
 library=new Promise((resolve,reject)=>{
  const script=document.createElement('script');script.src='https://accounts.google.com/gsi/client?hl=vi';script.async=true;script.defer=true
  const timer=setTimeout(()=>{script.remove();library=null;reject(new Error('Google chưa phản hồi. Hãy thử lại.'))},15000)
  script.onload=()=>{clearTimeout(timer);if(window.google?.accounts?.id)resolve(window.google.accounts.id);else{library=null;reject(new Error('Chưa tải được đăng nhập Google.'))}}
  script.onerror=()=>{clearTimeout(timer);script.remove();library=null;reject(new Error('Chưa kết nối được Google. Kiểm tra mạng và thử lại.'))};document.head.appendChild(script)
 });return library
}
export default function GoogleSignIn({onComplete,onProfileChange,disabled=false}) {
 const {googleLogin}=useAuth(),[config,setConfig]=useState(null),[error,setError]=useState(''),[loading,setLoading]=useState(true),[working,setWorking]=useState(false),[profile,setProfile]=useState(null)
 const [name,setName]=useState(''),[phone,setPhone]=useState(''),[password,setPassword]=useState(''),[generation,setGeneration]=useState(0)
 const button=useRef(null),active=useRef(true),handler=useRef(null),lock=useRef(false)
 useEffect(()=>{active.current=true;return()=>{active.current=false}},[])
 useEffect(()=>{let alive=true;setLoading(true);setError('');api('/api/customer/auth/google/config').then(c=>{if(alive)setConfig(c)}).catch(e=>{if(alive)setError(e.message)}).finally(()=>{if(alive)setLoading(false)});return()=>{alive=false}},[generation])
 handler.current=async credential=>{
  if(lock.current||disabled)return;lock.current=true;setWorking(true);setError('')
  try{const result=await googleLogin({credential});if(!active.current)return;if(result.requiresProfile){setProfile({...result,credential});setName(result.name||'');setPassword('');onProfileChange(true)}else onComplete()}
  catch(e){if(active.current)setError(e.message)}finally{lock.current=false;if(active.current)setWorking(false)}
 }
 useEffect(()=>{
  if(!config?.enabled||profile)return;let alive=true,observer
  loadGoogle().then(id=>{if(!alive||!button.current)return;id.initialize({client_id:config.clientId,nonce:config.nonce,callback:r=>{if(alive&&r.credential)handler.current(r.credential)},auto_select:false});
   let lastWidth=0;const render=()=>{if(!button.current)return;const width=Math.min(400,Math.floor(button.current.clientWidth));if(width<200||width===lastWidth)return;lastWidth=width;button.current.replaceChildren();id.renderButton(button.current,{theme:'outline',size:'large',shape:'rectangular',text:'continue_with',width,locale:'vi'})};render();observer=new ResizeObserver(render);observer.observe(button.current)
  }).catch(e=>{if(alive)setError(e.message)});return()=>{alive=false;observer?.disconnect()}
 },[config,profile])
 function reset(){setProfile(null);setPassword('');setPhone('');onProfileChange(false);setConfig(null);setGeneration(g=>g+1)}
 async function finish(e){e.preventDefault();if(working||disabled)return;setWorking(true);setError('');try{if(!validName(name)||!validPhone(phone))throw new Error(!validName(name)?'Nhập họ tên hợp lệ từ 2 đến 100 ký tự.':'Số điện thoại gồm 10 chữ số, bắt đầu bằng 0.');await googleLogin({credential:profile.credential,name:cleanName(name),phone:normalizePhone(phone),password});onComplete()}catch(e){setError(e.message)}finally{setWorking(false)}}
 if(!loading&&!error&&!config?.enabled)return null
 return <section className="c-google-signin" aria-label="Đăng nhập bằng Google">
  {profile?<form className="c-google-profile" onSubmit={finish}><p className="c-google-email">{profile.email}</p><label className="c-field">Họ và tên<input required minLength={2} maxLength={100} autoComplete="name" value={name} disabled={working} onChange={e=>setName(e.target.value)}/></label><label className="c-field">Số điện thoại<input required type="tel" inputMode="tel" autoComplete="tel" maxLength={30} value={phone} disabled={working} onChange={e=>setPhone(e.target.value)}/></label><label className="c-field">Mật khẩu GK nếu số điện thoại đã có tài khoản<input type="password" autoComplete="current-password" maxLength={128} value={password} disabled={working} onChange={e=>setPassword(e.target.value)}/></label>{error&&<p className="c-alert error" role="alert">{error}</p>}<button className="c-btn" disabled={working||disabled}>{working?'Đang xác nhận…':'Tiếp tục với GK'}</button><button type="button" className="c-btn outline" disabled={working} onClick={reset}>Chọn lại tài khoản Google</button></form>:<>
   {loading?<p className="c-google-loading" role="status">Đang kết nối Google…</p>:<div className="c-google-button" aria-busy={working} inert={working||disabled?'':undefined}><div ref={button}/></div>}
   {working&&<p className="c-google-loading" role="status">Đang xác nhận tài khoản…</p>}{error&&<div className="c-alert error" role="alert">{error}<button type="button" onClick={()=>{setConfig(null);setGeneration(g=>g+1)}}>Thử lại Google</button></div>}
   {config?.enabled&&<div className="c-auth-divider"><span>hoặc dùng số điện thoại</span></div>}
  </>}
 </section>
}
