import test from 'node:test'
import assert from 'node:assert/strict'
import { orderProgress, ORDER_MILESTONES } from '../src/shared/lib/order-progress.js'

test('every order renders the same seven milestones in the same order', () => {
 for(const stage of ['awaiting_pickup','pickup_assigned','picked_up','at_warehouse','delivery_assigned','out_for_delivery','delivered','failed']) {
  const steps=orderProgress(stage,[])
  assert.deepEqual(steps.map(s=>s.key),ORDER_MILESTONES.map(([key])=>key))
  assert.equal(steps.length,7)
 }
})
test('future milestones remain visible and pending at warehouse', () => {
 const steps=orderProgress('at_warehouse',[{type:'created'},{type:'at_warehouse'}])
 assert.equal(steps[3].state,'current')
 assert.ok(steps.slice(4).every(s=>s.state==='pending'&&!s.event))
})
test('failed attempts never claim delivery success and retry resets future progress', () => {
 const events=[{type:'pickup_assigned'},{type:'delivery_assigned'},{type:'delivery_failed'}]
 const failed=orderProgress('failed',events)
 assert.equal(failed[5].state,'issue');assert.equal(failed[6].state,'pending')
 const retry=orderProgress('at_warehouse',events)
 assert.ok(retry.slice(4).every(s=>s.state==='pending'&&!s.event))
})
test('new assignment appears on fixed milestone while original audit is unchanged', () => {
 const events=[{type:'pickup_assigned',driver_name:'Old'},{type:'pickup_reassigned',driver_name:'New'}]
 assert.equal(orderProgress('pickup_assigned',events)[1].event.driver_name,'New')
 assert.equal(events[0].driver_name,'Old')
})
test('pending/rejected submissions never imply GK has accepted them', () => {
 assert.ok(orderProgress('delivered',[{type:'created'}],false).every(s=>s.state==='pending'))
})
test('completion uses actual timestamps without manufacturing missing history', () => {
 const steps=orderProgress('delivered',[{type:'delivered',created_at:'2026-11-22T10:00:00+07:00'}])
 assert.ok(steps.every(s=>s.reached));assert.equal(steps[1].event,undefined)
 assert.equal(steps[6].event.created_at,'2026-11-22T10:00:00+07:00')
})

test('return progress has stable steps and never claims delivery success',()=>{
 const stages=['return_pending','return_assigned','returning','return_failed','returned']
 let keys
 for(const stage of stages){
  const steps=orderProgress(stage,[{type:'delivery_failed'},{type:'delivery_cancelled'}])
  if(!keys)keys=steps.map(s=>s.key)
  assert.deepEqual(steps.map(s=>s.key),keys)
  assert.ok(!steps.some(s=>s.key==='delivered'))
 }
 assert.equal(orderProgress('return_failed')[5].state,'issue')
 assert.equal(orderProgress('returned')[6].state,'current')
 assert.ok(orderProgress('returning',[],false).every(s=>s.state==='pending'))
})
