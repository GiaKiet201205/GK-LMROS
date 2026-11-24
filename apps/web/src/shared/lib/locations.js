const number = '[-+]?\\d+(?:\\.\\d+)?'
const pair = new RegExp(`^\\s*(${number})\\s*[,;\\s]\\s*(${number})\\s*$`)
function coordinatePoint(lat, lng, source = 'coordinates') {
  lat = Number(lat); lng = Number(lng)
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) throw new Error('Tọa độ không hợp lệ. Vĩ độ từ -90 đến 90, kinh độ từ -180 đến 180.')
  return { lat, lng, label: `Vị trí ${lat.toFixed(6)}, ${lng.toFixed(6)}`, source }
}
export function parseLocationInput(input) {
  const text = input.trim()
  const direct = text.match(pair)
  if (direct) return coordinatePoint(direct[1], direct[2])
  if (!/^https?:\/\//i.test(text)) return null
  let url
  try { url = new URL(text) } catch { throw new Error('Liên kết vị trí không hợp lệ.') }
  if (!/^(?:www\.|maps\.)?google\.com(?:\.vn)?$/.test(url.hostname) && url.hostname !== 'maps.app.goo.gl' && url.hostname !== 'goo.gl') throw new Error('Hãy dán liên kết Google Maps hoặc một cặp tọa độ.')
  // A place pin is more precise than the map camera's @lat,lng viewport.
  let decoded
  try { decoded = decodeURIComponent(text) } catch { decoded = text }
  const pin = decoded.match(new RegExp(`!3d(${number})!4d(${number})`))
  if (pin) return coordinatePoint(pin[1], pin[2], 'google_link')
  for (const name of ['query', 'q', 'destination', 'll']) {
    const match = (url.searchParams.get(name) || '').match(pair)
    if (match) return coordinatePoint(match[1], match[2], 'google_link')
  }
  const camera = decoded.match(new RegExp(`@(${number}),(${number})`))
  if (camera) return { ...coordinatePoint(camera[1], camera[2], 'google_link'), approximate: true }
  throw new Error('Liên kết này chưa có tọa độ. Mở Google Maps rồi sao chép tọa độ hoặc liên kết đầy đủ của điểm đã chọn.')
}
export function confirmedLocation(point) {
  return Boolean(point?.confirmed && point.label?.trim() && Number.isFinite(point.lat) && Number.isFinite(point.lng) && Math.abs(point.lat) <= 90 && Math.abs(point.lng) <= 180)
}
