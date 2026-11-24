import { directionGroups } from '../lib/maps.js'
export default function NavigationSegments({ stops, origin, onNavigate, disabled = false, compact = false }) {
  const groups = directionGroups(stops, origin)
  if (!groups.length) return null
  return <div className="navigation-segments">
    {groups.map((group, index) => <section key={group.key} className="navigation-segment"><div className="row"><strong>{group.label}</strong><span className="tag gray">{group.handled}/{group.all.length} đã xử lý</span></div>
      {compact ? <details className="navigation-segment-customers"><summary>Xem {group.all.length} điểm trong chặng</summary><ol>{group.all.map((stop, i) => <li key={stop.stop_id ?? i} className={['completed', 'failed'].includes(stop.state) ? 'handled' : ''}><b>{stop.customer_name || 'Khách hàng'}</b><span>{stop.address}</span>{stop.state === 'failed' && <small>Không hoàn tất</small>}</li>)}</ol></details> : <ol>{group.all.map((stop, i) => <li key={stop.stop_id ?? i} className={['completed', 'failed'].includes(stop.state) ? 'handled' : ''}><b>{stop.customer_name || 'Khách hàng'}</b><span>{stop.address}</span>{stop.state === 'failed' && <small>Không hoàn tất</small>}</li>)}</ol>}

      <div className="toolbar">{group.links.map((link, part) => onNavigate ? <button key={part} type="button" className="btn ghost" disabled={disabled} onClick={() => onNavigate(link.url, index === 0 && part === 0)}>{link.label}</button> : <a key={part} className="btn ghost" href={link.url} target="_blank" rel="noreferrer">{link.label}</a>)}</div>
      
    </section>)}
  </div>
}
