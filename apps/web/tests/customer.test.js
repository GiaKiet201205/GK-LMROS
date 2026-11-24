import test from 'node:test'
import assert from 'node:assert/strict'
import { assignedDriver, bookingStatus } from '../src/portals/customer/lib/status.js'

test('unsubmitted bookings never claim delivery or vehicle confirmation', () => {
  assert.equal(bookingStatus({ state: 'pending', snapshot: { stage: 'delivered' } }), 'Đã lưu · đang gửi điều phối')
  assert.equal(bookingStatus({ state: 'rejected', snapshot: {} }), 'Yêu cầu chưa hợp lệ')
})

test('previous pickup driver is hidden while goods wait at depot for a new delivery assignment', () => {
  const snapshot = { stage: 'at_warehouse', pickup_driver: 'Driver A', pickup_plate: '51D-12345', delivery_driver: 'Driver B', delivery_plate: '51D-67890' }
  assert.equal(assignedDriver(snapshot), null)
  assert.equal(assignedDriver({ ...snapshot, stage: 'awaiting_pickup' }), null)
  assert.equal(assignedDriver({ ...snapshot, stage: 'pickup_assigned' }).plate, '51D-12345')
  assert.equal(assignedDriver({ ...snapshot, stage: 'delivery_assigned' }).plate, '51D-67890')
})

test('approaching recipient is shown only for a real departure event on a delivery in progress', () => {
  const booking = { state: 'submitted', snapshot: { stage: 'out_for_delivery' }, events: [{ type: 'out_for_delivery' }] }
  assert.equal(bookingStatus(booking), 'Đang giao hàng')
  booking.events.push({ type: 'approaching_delivery' })
  assert.equal(bookingStatus(booking), 'Tài xế đang đến người nhận')
  booking.snapshot.stage = 'delivered'
  assert.equal(bookingStatus(booking), 'Đã giao thành công')
})
