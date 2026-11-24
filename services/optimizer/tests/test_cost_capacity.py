import copy
import time
import unittest
from gk_optimizer import solver
from test_dynamic import node, route, request

class CostCapacityTest(unittest.TestCase):
 def vehicle(self,identifier=1,cost=6500):return dict(id=identifier,warehouse_id=1,capacity_kg=1000,capacity_m3=100,cost_per_km=cost,shift_end_min=10000)
 def solve(self,orders,**extra):
  req=request(route(),orders,[self.vehicle()]);req.update(routes=[],group_deliveries=True,max_orders=45,seconds=3);req.update(extra);return solver.solve(req)
 def test_twenty_orders_fit_one_trip_for_each_phase_even_if_splitting_is_cheaper(self):
  for phase in ('pickup','delivery','return'):
   orders=[dict(node(i,(-1 if i%2 else 1)*i*.1,phase),weight_kg=20) for i in range(1,21)]
   result=self.solve(orders,vehicles=[self.vehicle(1),self.vehicle(2)],fixed_trip_cost=0)
   self.assertEqual(result['stats']['assigned'],20)
   self.assertEqual(len(result['routes']),1)
   self.assertEqual(result['stats']['planning_objective'],'min_trips_then_cost')
 def test_whole_batch_chooses_large_enough_vehicle_over_cheaper_small_van(self):
  small=self.vehicle(1,cost=100);small['capacity_kg']=500
  big=self.vehicle(2,cost=10000)
  result=self.solve([dict(node(i,i*.01,'delivery'),weight_kg=40) for i in range(1,21)],vehicles=[small,big])
  self.assertEqual(result['stats']['assigned'],20);self.assertEqual(len(result['routes']),1)
  self.assertEqual(result['routes'][0]['vehicle_id'],2)
 def test_overloaded_batch_splits_without_losing_orders(self):
  for extra in [dict(weight_kg=60),dict(volume_m3=6)]:
   result=self.solve([dict(node(i,i*.01,'pickup'),**extra) for i in range(1,21)],vehicles=[self.vehicle(1),self.vehicle(2)])
   self.assertEqual(result['stats']['assigned'],20);self.assertEqual(len(result['routes']),2)
   for trip in result['routes']:
    self.assertLessEqual(trip['load_kg'],1000);self.assertLessEqual(trip['load_m3'],100)
 def test_customer_limit_splits_46_orders_into_two_trips(self):
  result=self.solve([node(i,i*.001,'delivery') for i in range(1,47)],vehicles=[self.vehicle(1),self.vehicle(2)])
  self.assertEqual(result['stats']['assigned'],46);self.assertEqual(len(result['routes']),2)
  self.assertTrue(all(len(r['nodes'])<=46 for r in result['routes']))
 def test_expired_customer_window_reports_deadline_not_distance_zero(self):
  order=dict(node(1,.1,'pickup'),tw_start_min=0,tw_end_min=60)
  result=self.solve([order],now_min=120)
  self.assertEqual(result['stats']['assigned'],0)
  self.assertEqual(result['stats']['unassigned'][0]['reason'],'order_deadline_passed')
  self.assertEqual(result['stats']['unassigned'][0]['deadline_min'],60)
  order.update(tw_start_min=130,tw_end_min=300)
  result=self.solve([order],now_min=120)
  self.assertEqual(result['stats']['assigned'],1)
  self.assertGreater(result['stats']['remaining_km'],0)
 def test_failure_diagnostics_preserve_capacity_and_shift_constraints(self):
  for extra,expected in [(dict(weight_kg=1001),'weight_capacity'),(dict(volume_m3=101),'volume_capacity')]:
   result=self.solve([dict(node(1,.1,'delivery'),**extra)])
   self.assertEqual(result['stats']['unassigned'][0]['reason'],expected)
  vehicle=self.vehicle();vehicle['shift_end_min']=2
  result=self.solve([dict(node(1,.1,'delivery'),service_min=10)],vehicles=[vehicle])
  self.assertEqual(result['stats']['assigned'],0)
  self.assertIn(result['stats']['unassigned'][0]['reason'],['shift_end_exceeded','existing_route_infeasible'])
 def test_1000kg_vehicle_never_loads_1100kg_even_under_45_orders(self):
  result=self.solve([dict(node(1,.1,'delivery'),weight_kg=600),dict(node(2,.2,'delivery'),weight_kg=500)])
  self.assertEqual(result['stats']['assigned'],1);self.assertLessEqual(result['routes'][0]['load_kg'],1000)
  exact=self.solve([dict(node(1,.1,'delivery'),weight_kg=500),dict(node(2,.2,'delivery'),weight_kg=500)])
  self.assertEqual(exact['stats']['assigned'],2);self.assertEqual(exact['routes'][0]['load_kg'],1000)
 def test_45_limit_even_when_direct_solver_request_asks_for_100(self):
  result=self.solve([node(i,i*.001,'delivery') for i in range(1,47)],max_orders=100)
  self.assertEqual(result['stats']['assigned'],45);self.assertEqual(len(result['stats']['unassigned']),1);self.assertEqual(len(result['routes'][0]['nodes']),46)
 def test_fleet_relocation_reduces_total_cost_without_losing_orders(self):
  a=route();a.update(id=None,status='planned',phase='delivery',frozen_count=0,capacity_kg=1000,onboard_kg=0,start={'lat':0,'lng':0,'time_min':0})
  a['nodes']=[node(1,.1,'delivery'),dict(a['nodes'][-1])];b=copy.deepcopy(a);b.update(vehicle_id=2,cost_per_km=1000);b['nodes'][0]=node(2,.11,'delivery')
  travel=solver.Travel([], 'estimate');fleet=[a,b]
  def cost():return sum(solver.evaluate(r,r['nodes'],travel)[0]/1000*r['cost_per_km']+35000 for r in fleet if len(r['nodes'])>1)
  before=cost();solver.improve_fleet(fleet,travel,dict(max_orders=45,fixed_trip_cost=35000),time.monotonic()+2)
  self.assertLess(cost(),before);self.assertEqual(sorted(n['id'] for r in fleet for n in r['nodes'] if n['kind']=='delivery'),[1,2])
 def test_unpriced_orders_never_create_fake_revenue(self):
  result=self.solve([node(1,.1,'delivery')]);self.assertEqual(result['stats']['unpriced_orders'],1);self.assertIsNone(result['routes'][0]['delivery_contribution'])
  priced=self.solve([dict(node(1,.1,'delivery'),shipping_fee_vnd=40000,shipping_price_status='estimated')]);self.assertEqual(priced['stats']['estimated_revenue'],40000);self.assertEqual(priced['stats']['confirmed_revenue'],0);self.assertEqual(priced['routes'][0]['estimated_fares'],1)

 def test_pickups_and_returns_use_same_capacity_and_do_not_mix_phases(self):
  for kind in ('pickup','return'):
   result=self.solve([dict(node(1,.1,kind),weight_kg=600),dict(node(2,.2,kind),weight_kg=500)])
   self.assertEqual(result['stats']['assigned'],1)
   self.assertLessEqual(result['routes'][0]['load_kg'],1000)
   self.assertEqual(result['routes'][0]['phase'],kind)
  result=self.solve([node(1,.1,'pickup'),node(2,.2,'return')],vehicles=[self.vehicle(1),self.vehicle(2)])
  self.assertEqual(result['stats']['assigned'],2)
  for r in result['routes']:
   self.assertTrue(all(n['kind'] in (r['phase'],'warehouse') for n in r['nodes']))
 def test_return_goods_cannot_be_added_to_a_moving_vehicle(self):
  r=route();r.update(phase='return',onboard_kg=20,onboard_m3=.2)
  for n in r['nodes'][:-1]:n['kind']='return'
  req=request(r,[node(3,.3,'return')],[])
  result=solver.solve(req)
  self.assertEqual(result['stats']['assigned'],0)
  self.assertEqual(result['stats']['unassigned'][0]['id'],3)
 def test_pickup_and_return_never_count_original_delivery_fare_as_new_revenue(self):
  for kind in ('pickup','return'):
   result=self.solve([dict(node(1,.1,kind),shipping_fee_vnd=40000,shipping_price_status='confirmed')])
   self.assertEqual(result['stats']['estimated_revenue'],0)
   self.assertEqual(result['stats']['confirmed_revenue'],0)
   self.assertEqual(result['routes'][0]['load_kg'],10)
