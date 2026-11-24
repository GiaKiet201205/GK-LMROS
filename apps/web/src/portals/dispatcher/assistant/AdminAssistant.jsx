import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { MessageCircle, X, Send, BookOpen, Plus, ArrowUpRight, RotateCcw, FileText, Trash2, Pencil, LoaderCircle } from 'lucide-react'
import { api } from '../../../shared/api/client.js'
import './assistant.css'

const PAGES = [['/','Tổng quan'],['/orders','Đơn hàng'],['/orders/new','Tạo đơn'],['/distribution','Phân phối đơn hàng'],['/live','Theo dõi hành trình'],['/routing','Điều phối tự động'],['/warehouses','Kho'],['/vehicles','Đội xe'],['/drivers','Tài xế & ca làm'],['/maintenance','Chi phí & bảo dưỡng'],['/reports','Tài chính & thống kê'],['/pricing','Cước giao hàng'],['/notifications','Thông báo'],['/settings','Cài đặt']]
const SUGGESTIONS = ['Hướng dẫn phân phối lấy hàng', 'Vì sao đơn chưa xếp được chuyến?', 'Doanh thu tháng này bao nhiêu?', 'Tài xế báo sửa xe xong thì làm gì?']
const EMPTY = { title:'', path:'/orders', content:'' }

export default function AdminAssistant({ warehouse }) {
 const [open,setOpen]=useState(false),[tab,setTab]=useState('chat'),[status,setStatus]=useState(null),[statusError,setStatusError]=useState('')
 const [messages,setMessages]=useState([]),[question,setQuestion]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState(''),[failed,setFailed]=useState(null)
 const dialog=useRef(null),trigger=useRef(null),end=useRef(null),input=useRef(null),abort=useRef(null)
 const location=useLocation(),navigate=useNavigate()
 useEffect(()=>{const node=dialog.current;if(open&&!node.open)node.showModal();else if(!open&&node.open)node.close()},[open])
 useEffect(()=>{if(!open)return;let alive=true;setStatusError('');api('/api/assistant/status').then(s=>{if(alive)setStatus(s)}).catch(e=>{if(alive)setStatusError(e.message)});return()=>{alive=false}},[open,tab])
 useEffect(()=>{if(open&&tab==='chat')end.current?.scrollIntoView({block:'nearest'})},[messages,busy,open,tab])
 useEffect(()=>()=>abort.current?.abort(),[])
 function close(){setOpen(false)}
 async function send(text,retry=false){
  const clean=text.trim();if(busy||!clean||clean.length>1500||!status)return
  const payload=retry?failed:{message:clean,history:messages.filter(m=>['user','assistant'].includes(m.role)).slice(-8).map(m=>({role:m.role,content:m.content.slice(0,6000)})),page:location.pathname,warehouse:warehouse?Number(warehouse):null}
  if(!payload)return
  if(!retry)setMessages(prev=>[...prev,{role:'user',content:clean}].slice(-40))
  setQuestion('');setBusy(true);setError('');setFailed(null);abort.current=new AbortController()
  try{const data=await api('/api/assistant/chat',{method:'POST',body:payload,signal:abort.current.signal});setMessages(prev=>[...prev,{role:'assistant',content:data.answer,sources:data.sources,mode:data.mode,asOf:data.asOf}].slice(-40))}
  catch(e){if(e.name!=='AbortError'){setError(e.message);setFailed(payload)}}finally{setBusy(false)}
 }
 function visit(path){close();navigate(path)}
 return <>
  <button ref={trigger} className="gk-assistant-launch" aria-label="Mở trợ lý GK" aria-haspopup="dialog" onClick={()=>setOpen(true)}><MessageCircle size={21}/><span>Trợ lý GK</span></button>
  <dialog ref={dialog} className="gk-assistant" aria-labelledby="gk-assistant-title" onCancel={close} onClose={()=>{setOpen(false);trigger.current?.focus()}} onClick={e=>{if(e.target===e.currentTarget&&e.clientX<e.currentTarget.getBoundingClientRect().left)close()}}>
   <header className="gk-assistant-head"><div className="gk-assistant-mark"><MessageCircle size={22}/></div><div><h2 id="gk-assistant-title">Trợ lý GK</h2><span className={status?.ready?'is-ready':''}>{status?.ready?'AI đã kết nối':status?'Tra cứu hướng dẫn':'Đang kết nối…'}</span></div><button className="gk-assistant-icon" aria-label="Đóng trợ lý" onClick={close}><X size={21}/></button></header>
   <div className="gk-assistant-tabs" role="tablist" aria-label="Chức năng trợ lý"><button role="tab" id="assistant-chat-tab" aria-controls="assistant-chat" aria-selected={tab==='chat'} onClick={()=>setTab('chat')}><MessageCircle size={17}/>Trò chuyện</button><button role="tab" id="assistant-knowledge-tab" aria-controls="assistant-knowledge" aria-selected={tab==='knowledge'} onClick={()=>setTab('knowledge')}><BookOpen size={17}/>Kiến thức</button></div>
   {statusError&&<div className="gk-assistant-error" role="alert">{statusError}<button onClick={()=>{setOpen(false);setTimeout(()=>setOpen(true),0)}}>Thử lại</button></div>}
   {tab==='chat'?<section className="gk-assistant-chat" id="assistant-chat" role="tabpanel" aria-labelledby="assistant-chat-tab">
    <div className="gk-assistant-messages" aria-busy={busy}>
     {!messages.length&&<div className="gk-assistant-welcome"><h3>Bạn cần hỗ trợ gì?</h3><p>Hỏi về cách dùng GK, tình trạng đơn, xe hoặc số liệu tài chính.</p><div>{SUGGESTIONS.map(s=><button key={s} disabled={busy||!status} onClick={()=>send(s)}>{s}<ArrowUpRight size={16}/></button>)}</div></div>}
     {messages.map((m,i)=><article key={i} className={`gk-assistant-message is-${m.role}`}><small>{m.role==='user'?'Bạn':m.mode==='reference'?'Tra cứu GK':'Trợ lý GK'}</small><div className="gk-assistant-text">{m.content}</div>{m.sources?.length>0&&<details className="gk-assistant-sources"><summary>{m.sources.length} nguồn tham khảo</summary>{m.sources.map(s=><div key={s.id}><button onClick={()=>visit(s.path)}><span>[{s.id}] {s.title}</span><ArrowUpRight size={15}/></button><details><summary>Xem nội dung nguồn</summary><pre>{s.excerpt}</pre></details></div>)}</details>}{m.asOf&&<time dateTime={m.asOf}>Tra cứu lúc {new Date(m.asOf).toLocaleString('vi-VN')}</time>}</article>)}
     {busy&&<p className="gk-assistant-loading" role="status"><LoaderCircle size={16}/>Đang tra cứu…</p>}
     {error&&<div className="gk-assistant-error" role="alert">{error}{failed&&<button disabled={busy} onClick={()=>send(failed.message,true)}><RotateCcw size={15}/>Thử lại</button>}</div>}
     <div ref={end}/>
    </div>
    {status&&!status.ready&&<details className="gk-assistant-connection"><summary>Chưa kết nối AI</summary><p>Hiện chỉ trích hướng dẫn và dữ liệu có sẵn. Để trò chuyện bằng AI, chọn nhà cung cấp bằng GK_ASSISTANT_PROVIDER (gemini hoặc openai), đặt API key tương ứng và GK_ASSISTANT_ENABLED=true ở backend, rồi khởi động lại dịch vụ.</p></details>}
    <form className="gk-assistant-compose" onSubmit={e=>{e.preventDefault();send(question)}}><div><textarea ref={input} aria-label="Câu hỏi cho trợ lý GK" rows={2} maxLength={1500} value={question} placeholder="Nhập câu hỏi hoặc mã đơn GK…" onChange={e=>setQuestion(e.target.value)} onKeyDown={e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.nativeEvent.isComposing){e.preventDefault();send(question)}}}/><button type="submit" aria-label="Gửi câu hỏi" disabled={busy||!question.trim()||!status}><Send size={19}/></button></div><footer><span>{warehouse?'Phạm vi: kho đang chọn':'Phạm vi: tất cả kho'}</span><button type="button" disabled={busy||!messages.length} onClick={()=>{setMessages([]);setError('');setFailed(null);input.current?.focus()}}>Xóa trò chuyện</button></footer></form>
   </section>:<Knowledge onVisit={visit}/>}
  </dialog>
 </>
}

function Knowledge({onVisit}){
 const [docs,setDocs]=useState(null),[error,setError]=useState(''),[form,setForm]=useState(null),[busy,setBusy]=useState(false),[deleting,setDeleting]=useState(null),[saved,setSaved]=useState('')
 const file=useRef(null)
 async function load(){setError('');try{setDocs(await api('/api/assistant/knowledge'))}catch(e){setError(e.message)}}
 useEffect(()=>{load()},[])
 async function save(e){e.preventDefault();setBusy(true);setError('');setSaved('');try{const id=form.id?.replace('custom:','');await api('/api/assistant/knowledge'+(id?'/'+id:''),{method:id?'PUT':'POST',body:form});setForm(null);await load();setSaved('Đã cập nhật kiến thức cho các câu hỏi tiếp theo.')}catch(e){setError(e.message)}finally{setBusy(false)}}
 async function remove(d){setBusy(true);setError('');try{await api(`/api/assistant/knowledge/${d.id.replace('custom:','')}?version=${d.version}`,{method:'DELETE'});setDeleting(null);await load()}catch(e){setError(e.message)}finally{setBusy(false)}}
 async function readFile(e){const f=e.target.files?.[0];e.target.value='';if(!f)return;if(!/\.(txt|md)$/i.test(f.name)||f.size>64000){setError('Chọn tệp .txt hoặc .md, tối đa 64 KB.');return}const content=await f.text();if(content.length>12000){setError('Nội dung tối đa 12.000 ký tự. Hãy tách tài liệu thành các chủ đề nhỏ.');return}setForm(prev=>({...prev,content,title:prev.title||f.name.replace(/\.(txt|md)$/i,'')}));setError('')}
 return <section className="gk-assistant-knowledge" id="assistant-knowledge" role="tabpanel" aria-labelledby="assistant-knowledge-tab">
  <div className="gk-assistant-knowledge-top"><div><h3>Kiến thức hệ thống</h3><p>{docs?`${docs.length} tài liệu`:'Đang tải…'}</p></div><button className="btn" disabled={busy||form!==null} onClick={()=>{setForm({...EMPTY});setSaved('');setError('')}}><Plus size={16}/>Thêm</button></div>
  {error&&<div className="gk-assistant-error" role="alert">{error}<button disabled={busy} onClick={load}>Tải lại</button></div>}{saved&&<p className="gk-assistant-saved" role="status">{saved}</p>}
  {form&&<form className="gk-assistant-document-form" onSubmit={save}><h3>{form.id?'Sửa tài liệu':'Bổ sung kiến thức'}</h3><label htmlFor="assistant-doc-title">Tiêu đề</label><input id="assistant-doc-title" required minLength={3} maxLength={120} value={form.title} disabled={busy} onChange={e=>setForm({...form,title:e.target.value})}/><label htmlFor="assistant-doc-path">Mục liên quan</label><select id="assistant-doc-path" value={form.path} disabled={busy} onChange={e=>setForm({...form,path:e.target.value})}>{PAGES.map(([path,label])=><option value={path} key={path}>{label}</option>)}</select><label htmlFor="assistant-doc-content">Nội dung</label><textarea id="assistant-doc-content" required minLength={20} maxLength={12000} rows={9} value={form.content} disabled={busy} onChange={e=>setForm({...form,content:e.target.value})}/><div className="gk-assistant-document-count"><button type="button" disabled={busy} onClick={()=>file.current?.click()}><FileText size={15}/>Đọc tệp .txt / .md</button><small>{form.content.length.toLocaleString('vi-VN')}/12.000</small></div><input ref={file} type="file" accept=".txt,.md,text/plain,text/markdown" hidden onChange={readFile}/><footer><button type="button" className="btn ghost" disabled={busy} onClick={()=>{setForm(null);setError('')}}>Đóng</button><button className="btn" disabled={busy||form.title.trim().length<3||form.content.trim().length<20}>{busy?'Đang lưu…':'Lưu kiến thức'}</button></footer></form>}
  <div className="gk-assistant-document-list">{docs?.map(d=><details key={d.id}><summary><BookOpen size={17}/><span>{d.title}<small>{d.kind==='guide'?'Hướng dẫn GK':'Tài liệu bổ sung'}</small></span></summary><div className="gk-assistant-document-content">{d.content}</div><div className="gk-assistant-document-actions"><button onClick={()=>onVisit(d.path)}>Mở chức năng<ArrowUpRight size={14}/></button>{d.kind==='custom'&&<><button disabled={busy} aria-label={`Sửa ${d.title}`} onClick={()=>{setForm({...d});setDeleting(null);setSaved('');setError('');document.querySelector('.gk-assistant-knowledge')?.scrollTo({top:0})}}><Pencil size={15}/>Sửa</button><button disabled={busy} aria-label={`Xóa ${d.title}`} onClick={()=>setDeleting(d.id)}><Trash2 size={15}/>Xóa</button></>}</div>{deleting===d.id&&<div className="gk-assistant-delete"><p>Xóa tài liệu này khỏi nguồn trả lời?</p><button disabled={busy} onClick={()=>setDeleting(null)}>Giữ lại</button><button disabled={busy} onClick={()=>remove(d)}>Xác nhận xóa</button></div>}</details>)}</div>
 </section>
}
