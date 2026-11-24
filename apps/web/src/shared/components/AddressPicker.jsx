import { useRef, useState } from 'react'
import { Search, MapPin, CheckCircle2, ExternalLink } from 'lucide-react'
import { api } from '../api/client.js'
import { parseLocationInput } from '../lib/locations.js'
import { searchMapUrl } from '../lib/maps.js'
export default function AddressPicker({ id, title, value, onChange, endpoint = '/api/locations' }) {
  const [query, setQuery] = useState(''), [results, setResults] = useState([]), [selected, setSelected] = useState(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const request = useRef(0), abort = useRef(null)
  function edit(text) {
    request.current++; abort.current?.abort(); setBusy(false)
    setQuery(text); setResults([]); setSelected(null); setError(''); onChange(null)
  }
  async function search() {
    const text = query.trim()
    if (!text) { setError('Nhập địa chỉ, liên kết Google Maps hoặc tọa độ để tìm vị trí.'); return }
    const current = ++request.current
    abort.current?.abort(); abort.current = new AbortController()
    setBusy(true); setError(''); setSelected(null); setResults([]); onChange(null)
    try {
      const point = parseLocationInput(text)
      if (point) { setSelected(point); return }
      const response = await api(`${endpoint}?q=${encodeURIComponent(text)}`, { signal: abort.current.signal })
      if (current !== request.current) return
      setResults(response.results)
      if (!response.results.length) setError('Chưa tìm thấy địa chỉ. Thêm phường/quận/thành phố, hoặc dán tọa độ của điểm trên Google Maps.')
    } catch (e) { if (current === request.current && e.name !== 'AbortError') setError(e.message) }
    finally { if (current === request.current) setBusy(false) }
  }
  const point = value || selected
  return <div className="address-picker">
    <label htmlFor={`${id}-search`} className="address-label">{title}</label>
    <div className="address-search"><input id={`${id}-search`} value={query} onChange={e => edit(e.target.value)} placeholder="Ví dụ: Chợ Bến Thành, TP.HCM" autoComplete="off" onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); search() } }} />
      <button className="btn" type="button" disabled={busy} onClick={search}><Search size={16} />{busy ? 'Đang tìm…' : 'Tìm vị trí'}</button></div>
    {error && <div className="callout amber" role="alert">{error}</div>}
    {results.length > 0 && !point && <div className="address-results" aria-label="Kết quả tìm địa chỉ">{results.map((result, index) => <button type="button" key={index} className="address-result" onClick={() => { setSelected(result); onChange(null) }}><MapPin size={18} /><span>{result.label}<small>Chọn để kiểm tra và xác nhận vị trí</small></span></button>)}</div>}
    {point && <div className={`address-confirmation${value?.confirmed ? ' confirmed' : ''}`}>
      <div className="address-confirmation-title">{value?.confirmed ? <CheckCircle2 size={20} /> : <MapPin size={20} />}<strong>{value?.confirmed ? 'Đã xác nhận vị trí' : 'Kiểm tra điểm đã chọn'}</strong></div>
      {value?.confirmed ? <p>{point.label}</p> : <div className="field"><label htmlFor={`${id}-label`}>Địa chỉ / điểm hẹn cụ thể</label><input id={`${id}-label`} minLength={5} maxLength={400} value={point.label} onChange={e => setSelected({ ...point, label: e.target.value })} /></div>}
      <div className="location-coordinates"><span>Vĩ độ <b>{point.lat.toFixed(6)}</b></span><span>Kinh độ <b>{point.lng.toFixed(6)}</b></span></div>
      <div className="toolbar"><a className="btn ghost" href={searchMapUrl(point)} target="_blank" rel="noreferrer"><ExternalLink size={15} />Kiểm tra trên Google Maps</a>
        {!value?.confirmed ? <button className="btn" type="button" disabled={point.label.trim().length<5||point.label.trim().length>400} onClick={() => onChange({ ...point, label: point.label.trim(), confirmed: true })}><CheckCircle2 size={16} />Xác nhận đúng vị trí</button> : <button type="button" className="btn ghost" onClick={() => { onChange(null); setSelected(point) }}>Chọn lại vị trí</button>}</div>
    </div>}
    {results.some(result => result.source === 'photon') && <small className="location-attribution">© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noreferrer">OpenStreetMap contributors</a></small>}
  </div>
}
