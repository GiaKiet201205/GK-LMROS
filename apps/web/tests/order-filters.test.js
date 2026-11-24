import test from 'node:test'
import assert from 'node:assert/strict'
import {ORDER_GROUPS,groupForStage,deadlineText} from '../src/shared/lib/order-filters.js'
import {STAGE_LABELS} from '../src/shared/lib/order-status.js'
test('six compact groups cover all order stages exactly once',()=>{const stages=ORDER_GROUPS.flatMap(g=>g.stages);assert.equal(ORDER_GROUPS.length,6);assert.equal(new Set(stages).size,stages.length);assert.deepEqual([...stages].sort(),Object.keys(STAGE_LABELS).sort())})
test('deep linked stages select the matching group and deadlines display Vietnam time',()=>{assert.equal(groupForStage('pickup_assigned'),'pickup');assert.equal(groupForStage('returned'),'completed');assert.equal(groupForStage('return_failed'),'issues');assert.equal(groupForStage('unknown'),'');assert.ok(deadlineText(Date.parse('2026-11-23T01:35:00+07:00')/60000).includes('01:35'));assert.equal(deadlineText(null),'—')})
