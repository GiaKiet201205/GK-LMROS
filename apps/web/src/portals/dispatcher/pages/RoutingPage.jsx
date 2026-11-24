import { Link } from 'react-router-dom'
import { useApi, api, nf } from '../../../shared/api/client.js'
import { PageHead, DataTable, STAGES } from '../../../shared/components/ui.jsx'
import { useState } from 'react'
import { Layers3, Plus } from 'lucide-react'
import JourneyBoard from '../components/JourneyBoard.jsx'
import { useWarehouseFilter as useFilter } from '../context/WarehouseFilterContext.js'
const parse = value => { try { return JSON.parse(value || '{}') } catch { return {} } }
export default function Optimize() {
  const { warehouse } = useFilter()
  const { data, error, reload } = useApi('/api/routing', 3000)
  const [retryError, setRetryError] = useState('')
  const stats = parse(data?.last_stats)
  return <div className="page"><PageHead title="Điều phối tự động"><Link className="btn" to="/distribution"><Layers3 aria-hidden="true" />Phân phối đơn hàng</Link><Link className="btn ghost" to="/orders/new"><Plus aria-hidden="true" />Tạo đơn lấy & giao</Link></PageHead>
    {(error || retryError) && <div className="callout red">{error?.message || retryError}</div>}
    <div className="grid4"><div className="card kpi"><small>Trạng thái</small><b className="small-kpi">{!data ? 'Đang tải' : !data.enabled ? 'Đã tạm dừng' : data.running ? 'Đang cập nhật' : data.dirty ? 'Có đơn chờ' : 'Đang theo dõi'}</b></div>
      <div className="card kpi"><small>Đơn nhận vào tuyến gần nhất</small><b>{nf(stats.assigned)}</b></div><div className="card kpi"><small>Quãng đường còn lại đã tính</small><b>{nf(stats.remaining_km, 1)} km</b></div>
      <div className="card kpi"><small>Nguồn quãng đường</small><b className="small-kpi">{stats.source === 'google' ? 'Google Maps' : stats.source === 'estimate' ? 'Ước tính' : 'Chưa tính'}</b><em>{stats.source === 'estimate' ? 'Chưa phải quãng đường đường bộ từ Google' : 'Giữ nguyên thứ tự đã hoàn tất'}</em></div></div>
    {data?.last_error && <div className="callout amber"><b>Chưa cập nhật được tuyến</b>{data.last_error}<button className="btn ghost" onClick={() => api('/api/optimize', { method: 'POST', body: {} }).then(reload).catch(e => setRetryError(e.message))}>Thử lại</button></div>}
    {(data?.expired > 0 || stats.blocked_routes?.length > 0) && <div className="callout amber">Có {data?.expired || 0} đơn chờ đã quá hạn và {stats.blocked_routes?.length || 0} chuyến có khung giờ cũ. Hệ thống giữ nguyên chúng, không chèn đơn mới vào chuyến quá hạn.</div>}
    {stats.estimated_positions > 0 && <div className="callout amber">{stats.estimated_positions} xe chưa có GPS mới. Tuyến được tính từ vị trí xác nhận gần nhất; tài xế cần bật chia sẻ vị trí.</div>}
    <JourneyBoard warehouse={warehouse} />
    <details className="card"><summary>Quy tắc lấy/giao và tình trạng hàng chờ</summary><h3>Lấy hàng → về kho → giao hàng</h3><p className="note">Chuyến lấy và chuyến giao tách riêng. Điểm đã hoàn tất và điểm đang đi tới không bị đổi chỗ. Chuyến lấy, giao và hoàn mới phải được duyệt tại Phân phối đơn hàng; đơn mới chỉ được chèn tự động vào chuyến lấy đã xuất phát nếu còn tải và đủ thời gian.</p>
      <div className="legend">{data?.waiting?.map(row => <span key={row.stage} className="tag blue">{STAGES[row.stage]?.[0] || row.stage}: {nf(row.count)}</span>)}</div>
      <p className="note">Lần cập nhật gần nhất: {data?.last_success ? new Date(data.last_success).toLocaleString('vi-VN') : 'Chưa có'}</p></details>
    <details className="card"><summary>Lịch sử cập nhật tuyến</summary><DataTable rows={data?.history} empty="Chưa có cập nhật tuyến. Tạo đơn mới để hệ thống tự điều phối." columns={[
      { key: 'created_at', label: 'Thời điểm', render: r => new Date(r.created_at).toLocaleString('vi-VN') }, { key: 'assigned', label: 'Đơn vừa phân' },
      { key: 'remaining_km', label: 'Km còn lại', render: r => nf(r.remaining_km, 1) }, { key: 'source', label: 'Nguồn', render: r => r.source === 'manual_release' ? 'Thu hồi phân công' : r.source === 'google' ? 'Google Maps' : 'Ước tính' },
      { key: 'details', label: 'Đơn chờ', render: r => parse(r.details).unassigned?.length || 0 },
    ]} /></details>
  </div>
}
