const ZONE = 'Asia/Ho_Chi_Minh'
export function calendarDate(now = new Date()) {
  const p = Object.fromEntries(new Intl.DateTimeFormat('en-CA', { timeZone: ZONE, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now).map(p => [p.type,p.value]))
  return `${p.year}-${p.month}-${p.day}`
}
export function addDays(date, count) { const d = new Date(`${date}T12:00:00Z`); d.setUTCDate(d.getUTCDate()+count); return d.toISOString().slice(0,10) }
export function dispatchDate(now = new Date()) {
  const hour = Number(new Intl.DateTimeFormat('en-GB', { timeZone: ZONE, hour: '2-digit', hourCycle: 'h23' }).format(now))
  const date = calendarDate(now)
  return hour >= 20 ? addDays(date,1) : date
}
export function defaultOrderWindows(now = new Date()) {
  const day = dispatchDate(now)
  const time = new Intl.DateTimeFormat('en-GB', { timeZone: ZONE, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(now)
  const start = day === calendarDate(now) && time >= '08:00' && time < '20:00' ? time : '08:00'
  const next = addDays(day,1)
  return { pickupFrom: `${day}T${start}`, pickupTo: `${day}T20:00`, deliveryFrom: `${next}T08:00`, deliveryTo: `${next}T20:00` }
}
