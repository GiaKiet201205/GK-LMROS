import { Check, CircleAlert } from 'lucide-react'
import { orderProgress } from '../lib/order-progress.js'
const when = date => date ? new Date(date).toLocaleString('vi-VN') : ''
export default function OrderProgress({ stage, events = [], accepted = true }) {
  return <ol className="order-progress" aria-label="Tiến độ đơn hàng">{orderProgress(stage, events, accepted).map(step => <li key={step.key} className={step.state} aria-current={['current', 'issue'].includes(step.state) ? 'step' : undefined}>
    <span className="order-progress-dot" aria-hidden="true">{step.state === 'completed' ? <Check size={12} /> : step.state === 'issue' ? <CircleAlert size={14} /> : <span />}</span>
    <div><h3>{step.label}</h3><p className="order-progress-state">{step.reached ? step.state === 'issue' ? 'Cần xử lý thêm' : step.state === 'current' && !['delivered','returned'].includes(stage) ? 'Mốc hiện tại' : 'Đã hoàn tất' : 'Chưa thực hiện'}</p>
    {step.event?.created_at && <time dateTime={step.event.created_at}>{when(step.event.created_at)}</time>}
    {step.event?.driver_name && <p>{step.event.driver_name}{step.event.plate && ` · ${step.event.plate}`}</p>}</div>
  </li>)}</ol>
}
