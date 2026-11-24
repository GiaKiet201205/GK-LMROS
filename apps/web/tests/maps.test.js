import test from 'node:test'
import assert from 'node:assert/strict'
import { directionLinks, directionGroups, searchMapUrl } from '../src/shared/lib/maps.js'

test('Google Maps chunks preserve all remaining points and stay within mobile waypoint limits', () => {
  const stops = Array.from({ length: 12 }, (_, i) => ({ seq: i + 1, lat: 10 + i / 100, lng: 106 + i / 100, state: i < 2 ? 'completed' : i === 5 ? 'failed' : 'pending' }))
  const origin = { lat: 10, lng: 106 }
  const links = directionLinks(stops, origin)
  const pending = stops.filter(s => !['completed', 'failed'].includes(s.state))
  assert.deepEqual(links.flatMap(link => link.stops), pending)
  for (const [index, link] of links.entries()) {
    const url = new URL(link.url)
    assert.equal(url.searchParams.get('api'), '1')
    assert.equal(url.searchParams.get('travelmode'), 'driving')
    assert.ok((url.searchParams.get('waypoints') || '').split('|').length <= 3)
    const last = link.stops.at(-1)
    assert.equal(url.searchParams.get('destination'), `${last.lat},${last.lng}`)
    const previous = index ? links[index - 1].stops.at(-1) : origin
    assert.equal(url.searchParams.get('origin'), `${previous.lat},${previous.lng}`)
    assert.ok(link.url.length < 2048)
  }
})

test('completed routes have no navigation, and a single point can use device location', () => {
  assert.deepEqual(directionLinks([{ lat: 10, lng: 106, state: 'completed' }]), [])
  const [link] = directionLinks([{ lat: 10, lng: 106, state: 'en_route' }])
  assert.equal(new URL(link.url).searchParams.has('origin'), false)
  assert.equal(new URL(searchMapUrl({ lat: 10, lng: 106 })).searchParams.get('query'), '10,106')
})

test('five-customer stages retain their number and exact customers as progress advances', () => {
  const stops = Array.from({ length: 11 }, (_, i) => ({ stop_id: i + 1, seq: i + 1, kind: i === 10 ? 'warehouse' : 'delivery', state: 'pending', customer_name: `Khách ${i + 1}`, address: `Địa chỉ ${i + 1}`, lat: 10 + i / 100, lng: 106 + i / 100 }))
  const origin = { lat: 10, lng: 106 }
  const groups = directionGroups(stops, origin)
  assert.deepEqual(groups.map(g => g.label), ['Chặng 1', 'Chặng 2', 'Về kho'])
  assert.deepEqual(groups[0].all.map(s => s.stop_id), [1, 2, 3, 4, 5])
  assert.deepEqual(groups[0].links.flatMap(l => l.stops).map(s => s.stop_id), [1, 2, 3, 4, 5])
  stops[0].state = 'completed'
  assert.deepEqual(directionGroups(stops, origin)[0].all.map(s => s.stop_id), [1, 2, 3, 4, 5])
  assert.deepEqual(directionGroups(stops, origin)[0].pending.map(s => s.stop_id), [2, 3, 4, 5])
  stops.slice(0, 5).forEach(s => { s.state = 'completed' })
  const remaining = directionGroups(stops, origin)
  assert.deepEqual(remaining.map(g => g.label), ['Chặng 2', 'Về kho'])
  assert.deepEqual(remaining[0].pending.map(s => s.stop_id), [6, 7, 8, 9, 10])
  assert.equal(new URL(remaining[0].links[0].url).searchParams.get('origin'), '10,106')
  stops.slice(5, 10).forEach(s => { s.state = 'failed' })
  assert.deepEqual(directionGroups(stops).map(g => g.label), ['Về kho'])
  stops[10].state = 'completed'
  assert.deepEqual(directionGroups(stops), [])
})
