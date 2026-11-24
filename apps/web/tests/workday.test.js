import test from 'node:test'
import assert from 'node:assert/strict'
import { dispatchDate, defaultOrderWindows } from '../src/shared/lib/workday.js'

test('Vietnam planning day advances at 20:00 and stays the same after midnight',()=>{
 for(const [at,day] of [['2026-11-22T19:59:59+07:00','2026-11-22'],['2026-11-22T20:00:00+07:00','2026-11-23'],['2026-11-22T23:59:59+07:00','2026-11-23'],['2026-11-23T00:00:00+07:00','2026-11-23'],['2026-12-31T13:00:00Z','2027-01-01']])assert.equal(dispatchDate(new Date(at)),day)
})
test('new orders after 20:00 default to tomorrow at 08:00, before 08:00 to today',()=>{
 assert.equal(defaultOrderWindows(new Date('2026-11-22T20:01:00+07:00')).pickupFrom,'2026-11-23T08:00')
 const early=defaultOrderWindows(new Date('2026-11-23T07:30:00+07:00'))
 assert.equal(early.pickupFrom,'2026-11-23T08:00');assert.equal(early.pickupTo,'2026-11-23T20:00')
 const active=defaultOrderWindows(new Date('2026-11-23T09:30:00+07:00'))
 assert.equal(active.pickupFrom,'2026-11-23T09:30');assert.equal(active.deliveryFrom,'2026-11-24T08:00');assert.equal(active.deliveryTo,'2026-11-24T20:00')
})
