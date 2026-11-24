export const pointLabel = stop => stop.kind === 'warehouse' ? 'Về kho' : stop.kind === 'return' ? 'Hoàn về người gửi' : stop.kind === 'pickup' ? 'Lấy hàng' : 'Giao hàng'
const coords = p => `${Number(p.lat)},${Number(p.lng)}`
export const searchMapUrl = p => `https://www.google.com/maps/search/?${new URLSearchParams({ api: '1', query: coords(p) })}`
export function directionLinks(stops, origin) {
  const pending = stops.filter(s => !['completed', 'failed'].includes(s.state))
  const links = []
  // At most 3 intermediate waypoints, including on mobile browsers.
  for (let at = 0; at < pending.length; at += 4) {
    const chunk = pending.slice(at, at + 4)
    const params = new URLSearchParams({ api: '1', travelmode: 'driving', destination: coords(chunk.at(-1)) })
    const start = at ? pending[at - 1] : origin
    if (start) params.set('origin', coords(start))
    if (chunk.length > 1) params.set('waypoints', chunk.slice(0, -1).map(coords).join('|'))
    links.push({ url: `https://www.google.com/maps/dir/?${params}`, label: pending.length > 4 ? `Google Maps · chặng ${links.length + 1}` : 'Mở tuyến trên Google Maps', stops: chunk })
  }
  return links
}

// Partition the full ordered journey before removing handled stops. This keeps
// customer 6 in stage 2 after customers 1–5 have been handled.
export function directionGroups(stops, origin) {
  const groups = []
  let customers = 0
  for (const stop of stops) {
    if (stop.kind === 'warehouse') {
      groups.push({ key: `warehouse-${stop.stop_id ?? stop.seq}`, label: 'Về kho', all: [stop] })
    } else {
      const index = Math.floor(customers / 5)
      let group = groups.find(g => g.number === index + 1)
      if (!group) { group = { key: `stage-${index + 1}`, number: index + 1, label: `Chặng ${index + 1}`, all: [] }; groups.push(group) }
      group.all.push(stop); customers++
    }
  }
  return groups.flatMap(group => {
    const pending = group.all.filter(s => !['completed', 'failed'].includes(s.state))
    if (!pending.length) return []
    const index = stops.indexOf(pending[0])
    const previous = index > 0 ? stops[index - 1] : undefined
    const handled = previous && ['completed', 'failed'].includes(previous.state)
    const start = groups.find(g => g.all.some(s => !['completed', 'failed'].includes(s.state))) === group ? origin || (handled ? previous : undefined) : previous
    const links = directionLinks(pending, start).map((link, i) => ({ ...link, label: pending.length > 4 ? `Google Maps · phần ${i + 1}` : 'Mở Google Maps' }))
    return [{ ...group, pending, links, handled: group.all.length - pending.length }]
  })
}
