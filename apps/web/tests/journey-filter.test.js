import test from 'node:test'
import assert from 'node:assert/strict'
import {filterJourneys} from '../src/shared/lib/journey-filter.js'
const fleet=[{id:1,code:'XE-001',plate:'51D-600.01',trips:[{id:1,phase:'pickup',status:'in_progress',driver:'Nguyễn Minh An'},{id:2,phase:'delivery',status:'planned',driver:'Trần Gia Bảo'}]},{id:2,code:'XE-002',plate:'51D-600.02',trips:[{id:3,phase:'return',status:'in_progress',driver:'Lê Minh Anh'}]}]
test('phase filters show pickup delivery and return independently',()=>{for(const phase of ['pickup','delivery','return']){const result=filterJourneys(fleet,{phase});assert.equal(result.length,1);assert.ok(result.every(v=>v.trips.every(t=>t.phase===phase)))}})
test('planned delivery remains visible when same vehicle also has active pickup',()=>{const result=filterJourneys(fleet,{phase:'delivery',state:'planned'});assert.equal(result[0].trips[0].id,2);assert.equal(filterJourneys(fleet,{phase:'delivery',state:'active'}).length,0)})
test('search and vehicle filters apply to matching trips only',()=>{assert.equal(filterJourneys(fleet,{phase:'pickup',query:'  minh an '}).length,1);assert.equal(filterJourneys(fleet,{phase:'pickup',query:'Gia Bảo'}).length,0);assert.equal(filterJourneys(fleet,{phase:'return',vehicleId:'1'}).length,0)})
test('filtering never mutates original routes',()=>{filterJourneys(fleet,{phase:'delivery'});assert.equal(fleet[0].trips.length,2)})
