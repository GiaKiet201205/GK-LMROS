import { Link } from 'react-router-dom'
import { Package, Truck, Warehouse, CheckCircle2, Plus, Clock3, AlertTriangle, Users, Activity, Radio, Layers3, Bell } from 'lucide-react'
import { useApi, nf } from '../../../shared/api/client.js'
import { useWarehouseFilter } from '../context/WarehouseFilterContext.js'
import { PageHead, STAGES, Tag } from '../../../shared/components/ui.jsx'
import { useNotifications, notificationTime } from '../notifications/NotificationsProvider.jsx'
import JourneyBoard from '../components/JourneyBoard.jsx'

export default function Dashboard() {
  const { warehouse } = useWarehouseFilter(), feed = useNotifications()
  const { data, error, reload } = useApi(`/api/dashboard${warehouse ? `?warehouse=${warehouse}` : ''}`, 5000)
  const counts = Object.fromEntries((data?.stageCounts || []).map(row => [row.stage, Number(row.count)]))
  const operations = data?.operations || {}, attention = data?.attention || {}, routing = data?.routing || {}
  const pickup = (counts.awaiting_pickup || 0) + (counts.pickup_assigned || 0)
  const atDepot = (counts.at_warehouse || 0) + (counts.delivery_assigned || 0)
  const stages = [
    { label: 'Chờ lấy', count: pickup, icon: Package, to: '/orders?stage=awaiting_pickup' },
    { label: 'Đang về kho', count: counts.picked_up || 0, icon: Truck, to: '/orders?stage=picked_up' },
    { label: 'Tại kho', count: atDepot, icon: Warehouse, to: '/distribution' },
    { label: 'Đang giao', count: counts.out_for_delivery || 0, icon: Radio, to: '/orders?stage=out_for_delivery' },
    { label: 'Đã giao', count: counts.delivered || 0, icon: CheckCircle2, to: '/orders?stage=delivered' },
  ]
  const tasks = [
    { count: counts.at_warehouse || 0, title: 'Đơn tại kho cần phân giao', detail: 'Chọn xe, tài xế và duyệt chuyến tiếp theo.', to: '/distribution', icon: Warehouse, tone: 'teal' },
    { count: attention.failed_orders || 0, title: 'Đơn lấy/giao chưa hoàn tất', detail: 'Kiểm tra lý do và phương án phục vụ lại.', to: '/orders?stage=failed', icon: AlertTriangle, tone: 'red' },
    { count: Number(attention.overdue_pickup || 0) + Number(attention.overdue_delivery || 0), title: 'Đơn đã quá khung giờ hẹn', detail: 'Đối chiếu các đơn và chuyến đang mở.', to: '/routing', icon: Clock3, tone: 'amber' },
    { count: attention.unregistered_drivers || 0, title: 'Tài xế chưa đăng ký lịch hôm nay', detail: 'Kiểm tra lịch để chủ động nguồn lực.', to: '/drivers', icon: Users, tone: 'slate' },
  ].filter(task => task.count > 0)
  const recent = (feed.data?.items || []).filter(item => !warehouse || !item.warehouse_id || String(item.warehouse_id) === warehouse).slice(0, 4)
  return <div className="page dashboard-page"><PageHead title="Tổng quan vận hành"><Link className="btn ghost" to="/distribution"><Layers3 size={17} />Phân phối đơn hàng</Link><Link className="btn" to="/orders/new"><Plus size={18} />Tạo đơn lấy & giao</Link></PageHead>
    {error && <div className="callout red" role="alert">{error.message}<button className="text-button" onClick={reload}>Thử lại</button></div>}
    {!data && !error && <div className="empty">Đang tải tổng quan…</div>}
    {data && <><div className="dashboard-status-strip"><span><i className={routing.last_error ? 'warning' : ''} />{routing.last_error ? 'Cần kiểm tra tối ưu tuyến' : routing.running ? 'Đang tính phương án mới' : 'Theo dõi tiến độ đội xe'}</span><p><Clock3 size={14} />Cập nhật {data.updatedAt ? new Date(data.updatedAt).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }) : 'vừa xong'} · tự làm mới</p></div>
    <section className="dashboard-kpis" aria-label="Chỉ số vận hành">{[
      { label: 'Đơn chờ lấy', value: pickup, detail: `${nf(counts.awaiting_pickup || 0)} chưa phân · ${nf(counts.pickup_assigned || 0)} đã phân xe`, icon: Package, to: '/orders?stage=awaiting_pickup', tone: 'teal' },
      { label: 'Tại kho chờ phân giao', value: counts.at_warehouse || 0, detail: `${nf(counts.delivery_assigned || 0)} đơn khác đã có chuyến giao`, icon: Warehouse, to: '/distribution', tone: 'amber' },
      { label: 'Xe đang hoạt động', value: operations.active_vehicles || 0, detail: `${nf(operations.available_vehicles || 0)} xe rảnh · ${nf(operations.planned_trips || 0)} chuyến chờ${data.workday?.date ? ` · lịch ${data.workday.date}` : ''}`, icon: Truck, to: '/live', tone: 'teal' },
      { label: 'Đã giao hôm nay', value: operations.delivered_today || 0, detail: `${nf(counts.out_for_delivery || 0)} đơn đang trên đường giao`, icon: CheckCircle2, to: '/orders?stage=delivered', tone: 'slate' },
    ].map(item => <Link className={`card dashboard-kpi ${item.tone}`} key={item.label} to={item.to}><div><span>{item.label}</span><i><item.icon size={22} /></i></div><strong>{nf(item.value)}</strong><small>{item.detail}</small></Link>)}</section>
    <div className="dashboard-primary-grid"><section className="card dashboard-flow"><div className="dashboard-section-head"><div><span className="section-eyebrow">DÒNG HÀNG</span><h2>Từ điểm lấy đến người nhận</h2></div><Link to="/orders">Xem đơn hàng</Link></div><div className="dashboard-pipeline">{stages.map(stage => <Link to={stage.to} key={stage.label}><span><stage.icon size={19} /></span><b>{nf(stage.count)}</b><small>{stage.label}</small></Link>)}</div><div className="dashboard-flow-footer"><span><CheckCircle2 size={16} />{nf(data.totalOrders)} đơn đang được lưu trong hệ thống</span><span>{nf(operations.completed_trips_today || 0)} chuyến hoàn tất hôm nay</span></div></section>
      <section className="card dashboard-tasks"><div className="dashboard-section-head"><div><span className="section-eyebrow">ƯU TIÊN VẬN HÀNH</span><h2>Cần bạn xử lý</h2></div><span className="tag gray">{tasks.length} nhóm việc</span></div><div className="dashboard-task-list">{tasks.length ? tasks.map(task => <Link key={task.title} to={task.to}><span className={`task-icon ${task.tone}`}><task.icon size={18} /></span><div><strong>{task.title}</strong><p>{task.detail}</p></div><b>{nf(task.count)}</b></Link>) : <div className="dashboard-clear"><CheckCircle2 size={30} /><strong>Các hàng chờ đang ổn định</strong><p>Tiếp tục theo dõi hành trình hoặc tạo đơn mới khi cần.</p></div>}</div></section></div>
    <JourneyBoard compact warehouse={warehouse} />
    <div className="dashboard-secondary-grid"><section className="card dashboard-warehouses"><div className="dashboard-section-head"><div><span className="section-eyebrow">PHÂN BỔ NGUỒN VIỆC</span><h2>Tình trạng theo kho</h2></div><Link to="/warehouses">Danh sách kho</Link></div><div className="dashboard-warehouse-list">{(data.warehouseSummary || []).map(item => { const total = Number(item.pickup) + Number(item.ready_delivery) + Number(item.delivering); return <Link key={item.id} to={`/distribution?warehouse=${item.id}`} className="dashboard-warehouse-row"><div className="warehouse-row-title"><span className="warehouse-code">{item.code}</span><div><strong>{item.name}</strong><p>{nf(item.active_vehicles)} xe đang hoạt động</p></div><span>{nf(total)} đơn đang phục vụ</span></div><div className="warehouse-workload" aria-label={`${item.pickup} chờ lấy, ${item.ready_delivery} chờ giao, ${item.delivering} đang giao`}>{[['pickup', 'pickup'], ['ready_delivery', 'ready'], ['delivering', 'delivery']].map(([key, name]) => <span key={key} className={name} style={{ width: `${total ? Number(item[key]) / total * 100 : 0}%` }} />)}</div><div className="warehouse-row-counts"><span>Chờ lấy <b>{nf(item.pickup)}</b></span><span>Tại kho <b>{nf(item.ready_delivery)}</b></span><span>Đang giao <b>{nf(item.delivering)}</b></span></div></Link> })}{data.warehouseSummary?.length === 0 && <p className="empty">Chưa có kho trong phạm vi đã chọn.</p>}</div></section>
      <section className="card dashboard-activity"><div className="dashboard-section-head"><div><span className="section-eyebrow">NHỊP VẬN HÀNH</span><h2>Cập nhật gần đây</h2></div><Link to="/notifications">Xem tất cả</Link></div>{feed.error && <p className="callout amber">Chưa tải được hoạt động. <button className="text-button" onClick={feed.reload}>Thử lại</button></p>}<div className="dashboard-activity-list">{recent.map(item => <Link to={item.href} key={item.id}><span className={`activity-dot ${item.level}`} /><div><strong>{item.title}</strong><p>{item.message}</p><time>{notificationTime(item.created_at)}</time></div></Link>)}{!recent.length && !feed.error && <div className="dashboard-clear"><Bell size={28} /><strong>Chưa có hoạt động mới</strong><p>Đơn mới, phân công và các mốc hoàn tất sẽ được cập nhật tại đây.</p></div>}</div><Link className="dashboard-action-link" to="/notifications">Mở trung tâm thông báo</Link></section></div>
    <section className="card dashboard-recent-orders"><div className="dashboard-section-head"><div><span className="section-eyebrow">ĐƠN HÀNG VỪA TẠO</span><h2>Tiếp nhận gần đây</h2></div><Link to="/orders">Tất cả đơn</Link></div><div className="dashboard-recent-list">{(data.recentOrders || []).map(order => <Link key={order.id} to={`/orders?q=${encodeURIComponent(order.code)}`}><span className="recent-order-icon"><Package size={18} /></span><div><strong>{order.code}</strong><small>{order.customer_order ? 'Khách đặt trên web' : 'Điều phối tạo đơn'} · {order.customer_name}</small></div><span className="recent-order-warehouse">{order.warehouse}</span><Tag map={STAGES} value={order.stage} /></Link>)}{data.recentOrders?.length === 0 && <p className="empty">Chưa có đơn. Tạo đơn đầu tiên để bắt đầu điều phối.</p>}</div></section>
    </>}
  </div>
}
