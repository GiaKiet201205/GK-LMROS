import { useEffect, useState } from 'react'
import { dispatchDate,calendarDate,addDays } from '../../../shared/lib/workday.js'
import { api, useApi } from '../../../shared/api/client.js'
const dateString = date => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
const currentMonday = () => { const d = new Date(`${dispatchDate()}T12:00:00`); d.setDate(d.getDate() - (d.getDay() + 6) % 7); return dateString(d) }
const lastMonday=()=>{const date=new Date(`${calendarDate()}T12:00:00`);date.setDate(date.getDate()-(date.getDay()+6)%7);return addDays(dateString(date),84)}
function weekDays(week) { return Array.from({ length: 7 }, (_, index) => { const d = new Date(`${week}T12:00:00`); d.setDate(d.getDate() + index); return { date: dateString(d), working: index < 6, start: '08:00', end: '20:00' } }) }
export default function WeeklySchedule() {
  const [week, setWeek] = useState(currentMonday), [days, setDays] = useState([]), [busy, setBusy] = useState(false), [message, setMessage] = useState(null)
  const { data, error, reload } = useApi(`/api/driver/schedule?week=${week}`)
  useEffect(() => {
    if (!data) return
    setDays(weekDays(week).map(day => { const saved = data.find(d => d.work_date === day.date); return saved ? { ...day, working: saved.working, start: '08:00', end: '20:00' } : day }))
  }, [data, week])
  function move(step) { const d = new Date(`${week}T12:00:00`); d.setDate(d.getDate() + step * 7); setDays([]); setMessage(null); setWeek(dateString(d)) }
  function edit(index, values) { setDays(rows => rows.map((row, i) => i === index ? { ...row, ...values } : row)); setMessage(null) }
  const count = days.filter(day => day.working).length
  async function save(e) {
    e.preventDefault(); setBusy(true); setMessage(null)
    try { await api('/api/driver/schedule', { method: 'POST', body: { week, days } }); setMessage({ ok: true, text: 'Đã lưu lịch tuần.' }); reload() }
    catch (e) { setMessage({ ok: false, text: e.message }) } finally { setBusy(false) }
  }
  return <section className="pcard weekly-schedule"><h2>Đăng ký lịch làm việc</h2>
    <div className="toolbar"><button type="button" className="btn ghost" disabled={week <= currentMonday() || busy} onClick={() => move(-1)}>Tuần trước</button><strong>Tuần từ {new Date(`${week}T12:00:00`).toLocaleDateString('vi-VN')}</strong><button type="button" className="btn ghost" disabled={busy||week>=lastMonday()} onClick={() => move(1)}>Tuần sau</button></div>
    {error && <div className="callout red">{error.message}</div>}{data?.length === 0 && <p className="callout amber">Tuần này chưa đăng ký. Bấm lưu để xác nhận lịch.</p>}
    <form onSubmit={save}>{days.map((day, index) => <div className="schedule-day" key={day.date}><label><input type="checkbox" checked={day.working} disabled={busy} onChange={e => edit(index, { working: e.target.checked })} /><span><b>{index === 6 ? 'Chủ nhật' : `Thứ ${index + 2}`}</b><small>{new Date(`${day.date}T12:00:00`).toLocaleDateString('vi-VN')}</small></span></label><div className="schedule-hours"><span>08:00 – 20:00</span></div></div>)}
      <p className={count < 6 ? 'schedule-warning' : 'muted'}>Đã chọn {count}/7 ngày làm việc{count < 6 ? ' · cần ít nhất 6 ngày' : ''}.</p><button className="btn" disabled={busy || days.length !== 7 || count < 6}>{busy ? 'Đang lưu…' : 'Lưu lịch tuần'}</button></form>
    {message && <p className={`callout ${message.ok ? 'green' : 'red'}`} role="status">{message.text}</p>}
  </section>
}
