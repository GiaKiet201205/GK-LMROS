export default function Brand({ compact = false }) {
  return <div className={`brand${compact ? ' compact' : ''}`}>
    <img src="/icon.svg" alt="" width="42" height="42" />
    <div><strong>GK</strong>{!compact && <small>Vận tải thông minh</small>}</div>
  </div>
}
