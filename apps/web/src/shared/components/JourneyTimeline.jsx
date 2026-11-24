import { Check, X, Navigation, Circle, Warehouse } from 'lucide-react'
import { pointLabel, searchMapUrl } from '../lib/maps.js'
const stateLabel = state => ({ completed: 'Hoàn tất', failed: 'Không hoàn tất', en_route: 'Đang đi tới', pending: 'Chưa thực hiện' })[state] || 'Chờ cập nhật'
export default function JourneyTimeline({ stops = [], compact = false, highlightedIds = [] }) {
  const nextIndex = stops.findIndex(stop => ['pending', 'en_route'].includes(stop.state))
  const from = compact ? Math.max(0, (nextIndex < 0 ? stops.length - 3 : nextIndex) - 1) : 0
  const shown = compact ? stops.slice(from, from + 4) : stops
  return <div className="journey-timeline-wrap">
    {from > 0 && <p className="journey-omitted">{from} điểm trước đó đã được xử lý</p>}
    <ol className="journey-timeline">{shown.map(stop => {
      const current = stops.indexOf(stop) === nextIndex
      const added = highlightedIds.includes(stop.stop_id)
      const Icon = stop.state === 'completed' ? Check : stop.state === 'failed' ? X : stop.state === 'en_route' ? Navigation : stop.kind === 'warehouse' ? Warehouse : Circle
      return <li key={stop.stop_id} className={`journey-point ${stop.state}${current ? ' next' : ''}${added ? ' added' : ''}`} aria-current={current ? 'step' : undefined}>
        <span className="journey-marker"><Icon size={15} /></span><div className="journey-point-content"><div className="journey-point-heading"><strong>{stop.seq}. {pointLabel(stop)}</strong><span className="journey-state">{stop.queued ? 'Chờ đồng bộ' : current && stop.state === 'pending' ? 'Tiếp theo' : stateLabel(stop.state)}</span>{added && <span className="tag blue">Đơn vừa thêm</span>}</div>
          <p>{stop.customer_name}</p><a href={searchMapUrl(stop)} target="_blank" rel="noreferrer" title="Kiểm tra địa điểm trên Google Maps">{stop.address}</a>
          {stop.kind !== 'warehouse' && <small>{stop.code}{stop.eta && ` · ETA ${stop.eta}`}{stop.tw_start && ` · ${stop.tw_start}–${stop.tw_end}`}</small>}
          {stop.fail_reason && <small className="journey-failure">Lý do: {stop.fail_reason}</small>}
        </div></li>
    })}</ol>
    {compact && from + shown.length < stops.length && <p className="journey-omitted">Còn {stops.length - from - shown.length} điểm phía sau</p>}
    {!stops.length && <p className="empty">Chưa có điểm dừng.</p>}
  </div>
}
