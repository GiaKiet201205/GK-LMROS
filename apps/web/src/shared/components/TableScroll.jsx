import { useEffect, useRef, useState } from 'react'
import { ChevronLeft, ChevronRight, MoveHorizontal } from 'lucide-react'

/** Native touch/trackpad scrolling, plus mouse dragging without breaking links. */
export default function TableScroll({ children }) {
 const viewport = useRef(null), drag = useRef(null), suppressClick = useRef(false)
 const [edges,setEdges]=useState({overflow:false,left:false,right:false})
 function sync() {
  const el=viewport.current;if(!el)return
  const max=el.scrollWidth-el.clientWidth
  const next={overflow:max>2,left:el.scrollLeft>2,right:el.scrollLeft<max-2}
  setEdges(old=>old.overflow===next.overflow&&old.left===next.left&&old.right===next.right?old:next)
 }
 useEffect(()=>{
  const el=viewport.current,observer=new ResizeObserver(sync)
  observer.observe(el);if(el.firstElementChild)observer.observe(el.firstElementChild)
  sync();return ()=>observer.disconnect()
 },[])
 function down(e) {
  suppressClick.current=false
  if(e.pointerType!=='mouse'||e.button!==0||!edges.overflow||e.target.closest('button,input,select,textarea,[contenteditable="true"]'))return
  drag.current={id:e.pointerId,x:e.clientX,y:e.clientY,scroll:viewport.current.scrollLeft,active:false}
 }
 function move(e) {
  const d=drag.current;if(!d||d.id!==e.pointerId)return
  const delta=e.clientX-d.x
  if(!d.active) {
   if(Math.abs(delta)<6||Math.abs(delta)<Math.abs(e.clientY-d.y))return
   d.active=true;viewport.current.setPointerCapture(e.pointerId);viewport.current.classList.add('is-dragging')
   window.getSelection()?.removeAllRanges()
  }
  e.preventDefault();viewport.current.scrollLeft=d.scroll-delta
 }
 function end(e) {
  const d=drag.current;if(!d||d.id!==e.pointerId)return
  suppressClick.current=d.active;drag.current=null;viewport.current.classList.remove('is-dragging')
  if(viewport.current.hasPointerCapture(e.pointerId))viewport.current.releasePointerCapture(e.pointerId)
 }
 function scroll(direction) {const el=viewport.current;el.scrollBy({left:direction*Math.max(200,el.clientWidth*.7),behavior:window.matchMedia('(prefers-reduced-motion: reduce)').matches?'instant':'smooth'})}
 return <div className={`table-scroll${edges.overflow?' has-overflow':''}`}>
  {edges.overflow && <div className="table-scroll-tools"><span><MoveHorizontal size={15} aria-hidden="true" /><span>Cuộn ngang</span></span><div><button type="button" className="table-scroll-arrow" aria-label="Cuộn bảng sang trái" disabled={!edges.left} onClick={()=>scroll(-1)}><ChevronLeft size={17} /></button><button type="button" className="table-scroll-arrow" aria-label="Cuộn bảng sang phải" disabled={!edges.right} onClick={()=>scroll(1)}><ChevronRight size={17} /></button></div></div>}
  <div ref={viewport} className={`table-wrap${edges.overflow?' is-draggable':''}`} tabIndex={edges.overflow?0:undefined} role="region" aria-label="Bảng dữ liệu, có thể cuộn ngang" onScroll={sync} onPointerDown={down} onPointerMove={move} onPointerUp={end} onPointerCancel={end} onLostPointerCapture={end} onPointerLeave={e=>{if(!drag.current?.active)end(e)}} onDragStart={e=>{if(drag.current)e.preventDefault()}} onClickCapture={e=>{if(suppressClick.current){e.preventDefault();e.stopPropagation();suppressClick.current=false}}}>
   {children}
  </div>
 </div>
}
