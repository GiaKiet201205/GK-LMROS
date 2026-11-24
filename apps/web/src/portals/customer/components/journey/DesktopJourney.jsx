import { useEffect, useRef, useState } from 'react'
import { Pause, Play } from 'lucide-react'
import useMediaQuery from '../../../../shared/lib/useMediaQuery.js'
import './journey.css'

function StaticJourney() {
 return <svg className="c-journey-static" viewBox="0 0 620 460" aria-hidden="true"><ellipse cx="316" cy="360" rx="240" ry="40" fill="#d8e3d9"/><path d="M66 284 306 158 554 282 313 412Z" fill="#dce7dc"/><path d="M66 284v17l247 128 241-131v-16L313 412Z" fill="#bed0c1"/><path d="m120 281 190-97 188 98-185 98Z" fill="none" stroke="#bd7253" strokeWidth="5" strokeDasharray="7 10" strokeLinecap="round"/><path d="m238 201 97-50 96 50-97 51Z" fill="#376957"/><path d="M238 201v101l96 51V252Z" fill="#174b3d"/><path d="m334 252 97-51v101l-97 51Z" fill="#24664f"/><path d="m240 198 95-50 101 49-100 52Z" fill="#f1eee1"/><path d="m350 273 56-29v60l-56 29Z" fill="#a4b7a7"/><path d="m251 226 58 30v32l-58-30Z" fill="#fbfbf0"/><text x="262" y="254" transform="rotate(27 262 254)" fontFamily="system-ui,sans-serif" fontSize="24" fontWeight="750" fill="#174b3d">GK</text><path d="m138 282 40-21 39 21-40 22Z" fill="#e8bb88"/><path d="M138 282v43l39 22v-43Z" fill="#bf8f60"/><path d="m177 304 40-22v43l-40 22Z" fill="#d4a671"/><path d="m157 271 39 22 0 10-39-21Z" fill="#f2dcba"/><path d="m403 327 47-24 45 24-46 24Z" fill="#ead1ab"/><path d="M403 327v46l46 25v-47Z" fill="#b58a61"/><path d="m449 351 46-24v45l-46 26Z" fill="#d3ad7e"/><circle cx="119" cy="277" r="9" fill="#b96343"/><circle cx="499" cy="281" r="9" fill="#b96343"/></svg>
}

export default function DesktopJourney() {
 const host=useRef(null),app=useRef(null),visible=useRef(false),pausedRef=useRef(false)
 const [ready,setReady]=useState(false),[paused,setPaused]=useState(false)
 const reduced=useMediaQuery('(prefers-reduced-motion: reduce)')
 useEffect(()=>{
  let cancelled=false,started=false,idle=null,timer=null
  setReady(false)
  const connection=navigator.connection
  if(reduced||connection?.saveData||['slow-2g','2g'].includes(connection?.effectiveType))return
  const eligible=window.matchMedia('(min-width:1200px) and (hover:hover) and (pointer:fine)').matches
  if(!eligible||window.matchMedia('(display-mode: standalone)').matches)return
  const active=()=>app.current?.setActive(visible.current&&!pausedRef.current&&!document.hidden)
  async function load(){
   if(cancelled)return
   try{
    const {createJourneyScene}=await import('./journey-scene.js')
    if(cancelled||!host.current)return
    const scene=createJourneyScene(host.current,()=>{app.current?.dispose();app.current=null;if(!cancelled)setReady(false)})
    if(!scene)return
    app.current=scene;setReady(true);active()
   }catch{if(!cancelled)setReady(false)}
  }
  const observer=new IntersectionObserver(entries=>{
   visible.current=entries[0].isIntersecting
   if(visible.current&&!started){started=true;if('requestIdleCallback' in window)idle=window.requestIdleCallback(load,{timeout:1200});else timer=window.setTimeout(load,150)}
   active()
  },{threshold:.1})
  observer.observe(host.current)
  document.addEventListener('visibilitychange',active)
  return()=>{cancelled=true;observer.disconnect();document.removeEventListener('visibilitychange',active);if(idle!==null)window.cancelIdleCallback(idle);window.clearTimeout(timer);app.current?.dispose();app.current=null}
 },[reduced])
 useEffect(()=>{pausedRef.current=paused;app.current?.setActive(visible.current&&!paused&&!document.hidden)},[paused])
 return <figure className={`c-journey${ready?' is-rendered':''}`} aria-labelledby="c-journey-caption" data-renderer={ready?'webgl':'static'}>
  <div className="c-journey-masthead"><span className="c-journey-wordmark">GK</span><span>HÀNH TRÌNH<br/>KIỆN HÀNG</span></div>
  <div className="c-journey-viewport" ref={host}><StaticJourney/></div>
  {ready&&<button className="c-journey-motion" type="button" aria-label={paused?'Tiếp tục chuyển động':'Tạm dừng chuyển động'} aria-pressed={paused} onClick={()=>setPaused(value=>!value)}>{paused?<Play size={13}/>:<Pause size={13}/>}<span>{paused?'Tiếp tục':'Tạm dừng'}</span></button>}
  <figcaption id="c-journey-caption">Kết nối người gửi, kho và người nhận.</figcaption>
 </figure>
}
