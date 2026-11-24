import TableScroll from './TableScroll.jsx'
export const STAGES = {
  picking_up: ['Đang lấy hàng','blue'], return_pending: ['Chờ hoàn về người gửi','amber'], return_assigned: ['Chờ xuất hoàn','blue'], returning: ['Đang hoàn','amber'], returned: ['Đã hoàn người gửi','gray'], return_failed: ['Hoàn chưa thành công','red'],
  awaiting_pickup: ['Chờ lấy', 'gray'], pickup_assigned: ['Đã phân chuyến lấy', 'blue'], picked_up: ['Đã lấy · đang về kho', 'blue'],
  at_warehouse: ['Đã về kho', 'green'], delivery_assigned: ['Chờ xuất giao', 'blue'], out_for_delivery: ['Đang giao', 'blue'], delivered: ['Đã giao', 'green'], failed: ['Không hoàn tất', 'red'],
}
export const ORDER_STATUS = {
  new: ['Mới', 'blue'], pending: ['Chờ phân tuyến', 'gray'], assigned: ['Đã phân tuyến', 'blue'], delivering: ['Đang giao', 'amber'],
  delivered: ['Đã giao', 'green'], failed: ['Thất bại', 'red'], deferred: ['Chờ chuyến sau', 'gray'],
}
export const VEHICLE_STATUS = { active: ['Đang hoạt động', 'green'], reserved: ['Chờ xuất phát', 'blue'], available: ['Rảnh', 'green'], unavailable: ['Đã đánh dấu bận', 'amber'], maintenance: ['Bảo dưỡng', 'amber'] }
export const DRIVER_STATUS = { on_duty: ['Đang hoạt động', 'green'], reserved: ['Chờ xuất phát', 'blue'], available: ['Có thể nhận chuyến', 'green'], off: ['Đăng ký nghỉ', 'gray'], unregistered: ['Chưa đăng ký', 'amber'] }
export const TRIP_STATUS = { planned: ['Chờ xuất phát', 'blue'], in_progress: ['Đang chạy', 'green'], completed: ['Hoàn tất', 'blue'] }

export function Tag({ map, value }) {
  const [label, tone] = map[value] || [value, 'gray']
  return <span className={`tag ${tone}`}>{label}</span>
}

/** columns: [{key, label, render?}] */
export function DataTable({ columns, rows, error, empty = 'Chưa có dữ liệu' }) {
  if (error) return <div className="callout red" role="alert">Không tải được dữ liệu: {error.message}</div>
  if (!rows) return <div className="empty">Đang tải…</div>
  if (!rows.length) return <div className="empty">{empty}</div>
  return (
    <TableScroll>
      <table className={columns.some(c=>['act','actions'].includes(c.key))?'has-row-actions':undefined}>
        <thead><tr>{columns.map((c) => <th key={c.key} className={['act','actions'].includes(c.key)?'table-actions-cell':undefined}>{c.label}</th>)}</tr></thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={r.id ?? i}>{columns.map((c) => <td key={c.key} className={['act','actions'].includes(c.key)?'table-actions-cell':undefined}>{c.render ? c.render(r) : r[c.key]}</td>)}</tr>
          ))}
        </tbody>
      </table>
    </TableScroll>
  )
}

export function PageHead({ title, sub, children }) {
  return (
    <div className="page-head">
      <div><h1>{title}</h1>{sub && <p>{sub}</p>}</div>
      <div>{children}</div>
    </div>
  )
}
