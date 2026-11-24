import test from 'node:test'
import assert from 'node:assert/strict'
import { parseLocationInput, confirmedLocation } from '../src/shared/lib/locations.js'

test('address searches are distinct from coordinates and never invent default coordinates', () => {
  assert.equal(parseLocationInput('Chợ Bến Thành, Hồ Chí Minh'), null)
  assert.equal(confirmedLocation(null), false)
  const point = parseLocationInput(' 10.78, 106.7 ')
  assert.equal(point.lat, 10.78)
  assert.equal(point.lng, 106.7)
  assert.equal(confirmedLocation(point), false)
  assert.equal(confirmedLocation({ ...point, confirmed: true }), true)
  assert.equal(confirmedLocation({ ...point, confirmed: true, label: '' }), false)
})

test('Google place pin wins over viewport camera and coordinate order is lat then lng', () => {
  const point = parseLocationInput('https://www.google.com/maps/place/Test/@10.5,106.4,16z/data=!3d10.781!4d106.702')
  assert.equal(point.lat, 10.781)
  assert.equal(point.lng, 106.702)
  assert.equal(point.approximate, undefined)
  assert.equal(parseLocationInput('https://www.google.com/maps/@10.5,106.4,16z').approximate, true)
  assert.equal(parseLocationInput('https://www.google.com/maps/search/?api=1&query=10.7%2C106.8').lng, 106.8)
})

test('invalid, shortened or foreign links cannot silently become a confirmed point', () => {
  for (const value of ['91,106', '10,181', 'https://maps.app.goo.gl/short', 'https://google.com.evil.test/maps?q=10,106']) {
    assert.throws(() => parseLocationInput(value))
  }
  assert.equal(confirmedLocation({ label: 'x', confirmed: true, lat: NaN, lng: 106 }), false)
})
