import copy
import itertools
import unittest
from unittest.mock import patch
from gk_optimizer import solver


def node(identifier, x, kind='pickup', stop_id=None):
    return {'id': identifier, 'lat': 0., 'lng': x / 100, 'warehouse_id': 1, 'kind': kind,
            'stop_id': stop_id, 'weight_kg': 10., 'volume_m3': .1,
            'tw_start_min': 0., 'tw_end_min': 10000., 'service_min': 0.}


def route():
    return {'id': 1, 'vehicle_id': 1, 'warehouse_id': 1, 'phase': 'pickup', 'status': 'in_progress',
            'capacity_kg': 100., 'capacity_m3': 8., 'cost_per_km': 6500., 'shift_end_min': 10000.,
            'start': {'lat': 0., 'lng': .01, 'time_min': 0.}, 'onboard_kg': 10., 'onboard_m3': .1,
            'frozen_count': 1, 'nodes': [node(2, 2, stop_id=20), node(3, 4, stop_id=30), node(4, 6, stop_id=40),
                                      {'lat': 0., 'lng': 0., 'kind': 'warehouse', 'stop_id': 50, 'service_min': 0.}]}


def request(r, orders, vehicles=None):
    return {'warehouses': [{'id': 1, 'lat': 0., 'lng': 0.}], 'vehicles': vehicles or [],
            'routes': [r], 'orders': orders, 'now_min': 0., 'seconds': 1., 'provider': 'estimate'}


class DynamicRoutingTest(unittest.TestCase):
    def test_delivery_batches_respect_customer_limit_and_keep_warehouse_separate(self):
        vehicles = [{'id': i, 'warehouse_id': 1, 'capacity_kg': 1000., 'capacity_m3': 100., 'cost_per_km': 6500., 'shift_end_min': 10000.} for i in (1, 2)]
        req = request(route(), [node(i, .1 * i, 'delivery') for i in range(1, 8)], vehicles)
        req.update(routes=[], max_orders=3, group_deliveries=True, seconds=2)
        result = solver.solve(req)
        self.assertEqual(result['stats']['assigned'], 6)
        self.assertEqual(len(result['stats']['unassigned']), 1)
        seen = []
        for r in result['routes']:
            customers = [n for n in r['nodes'] if n['kind'] != 'warehouse']
            self.assertEqual(len(customers), 3)
            self.assertEqual(r['nodes'][-1]['kind'], 'warehouse')
            seen += [n['id'] for n in customers]
        self.assertEqual(len(seen), len(set(seen)))

    def test_unavailable_vehicles_and_routes_cannot_accept_orders(self):
        r = route(); r['allow_additions'] = False
        free = {'id': 2, 'warehouse_id': 1, 'capacity_kg': 100., 'capacity_m3': 8., 'cost_per_km': 6500., 'shift_end_min': 10000., 'allocation_enabled': False}
        result = solver.solve(request(r, [node(5, 3)], [free]))
        self.assertEqual(result['stats']['assigned'], 0)

    def test_trip_customer_limit_includes_completed_prefix(self):
        r = route(); r['max_orders'] = 4; r['completed_customers'] = 1
        self.assertEqual(solver.solve(request(r, [node(5, 3)]))['stats']['assigned'], 0)

    def test_grouping_keeps_two_distant_neighbourhoods_on_separate_trips(self):
        vehicles = [{'id': i, 'warehouse_id': 1, 'capacity_kg': 1000., 'capacity_m3': 100., 'cost_per_km': 6500., 'shift_end_min': 10000.} for i in (1, 2)]
        orders = [node(i, (1 if i % 2 else -1) * (10 + i * .01), 'delivery') for i in range(1, 7)]
        req = request(route(), orders, vehicles)
        req.update(routes=[], max_orders=3, group_deliveries=True, seconds=2)
        result = solver.solve(req)
        self.assertEqual(result['stats']['assigned'], 6)
        for r in result['routes']:
            self.assertEqual(len({n['lng'] > 0 for n in r['nodes'] if n['kind'] != 'warehouse'}), 1)

    def test_new_order_after_completed_prefix_and_frozen_target_minimizes_remaining_distance(self):
        original = route()
        result = solver.solve(request(original, [node(5, 3)]))
        optimized = result['routes'][0]
        self.assertEqual(optimized['nodes'][0]['stop_id'], 20)
        self.assertEqual([n['id'] for n in optimized['nodes'][:-1]], [2, 5, 3, 4])
        self.assertNotIn(1, [n.get('id') for n in optimized['nodes']])  # A already completed.
        self.assertEqual(original, route())  # Input snapshot isn't mutated.
        travel = solver.Travel([], 'estimate')
        alternatives = [solver.evaluate(original, [original['nodes'][0], *tail, original['nodes'][-1]], travel)[0]
                        for tail in itertools.permutations([*original['nodes'][1:-1], node(5, 3)])]
        self.assertAlmostEqual(solver.evaluate(optimized, optimized['nodes'], travel)[0], min(alternatives))

    def test_pickup_capacity_includes_already_collected_goods(self):
        r = route(); r['onboard_kg'] = 95.
        result = solver.solve(request(r, [node(5, 3)]))
        self.assertEqual(result['stats']['assigned'], 0)
        self.assertEqual(result['stats']['unassigned'][0]['id'], 5)

    def test_separate_pickup_and_delivery_trips_and_no_depot_goods_on_a_moving_van(self):
        r = route(); r['phase'] = 'delivery'
        r['onboard_kg'] = 30.; r['onboard_m3'] = .3
        for n in r['nodes'][:-1]: n['kind'] = 'delivery'
        result = solver.solve(request(r, [node(5, 3), node(6, 3, 'delivery')]))
        self.assertEqual(result['stats']['assigned'], 0)
        self.assertEqual({u['id'] for u in result['stats']['unassigned']}, {5, 6})

    def test_idle_vehicle_gets_a_separate_pickup_trip(self):
        r = route(); r['phase'] = 'delivery'; r['onboard_kg'] = 30.; r['onboard_m3'] = .3
        for n in r['nodes'][:-1]: n['kind'] = 'delivery'
        free = {'id': 2, 'warehouse_id': 1, 'capacity_kg': 100., 'capacity_m3': 8., 'cost_per_km': 6500., 'shift_end_min': 10000.}
        result = solver.solve(request(r, [node(5, 3)], [free]))
        new = next(row for row in result['routes'] if row['id'] is None)
        self.assertEqual(new['phase'], 'pickup')
        self.assertEqual(new['nodes'][-1]['kind'], 'warehouse')
        self.assertTrue(all(n['kind'] in ('pickup', 'warehouse') for n in new['nodes']))

    def test_volume_and_time_windows_are_hard_constraints(self):
        for order in [dict(node(5, 3), volume_m3=100.), dict(node(5, 3), tw_end_min=.001)]:
            result = solver.solve(request(route(), [order]))
            self.assertEqual(result['stats']['assigned'], 0)

    def test_google_errors_do_not_silently_switch_to_estimates(self):
        with patch.dict('os.environ', {'GOOGLE_ROUTES_API_KEY': ''}):
            with self.assertRaises(ValueError): solver.Travel([node(1, 1)], 'google')
        with patch.dict('os.environ', {'GOOGLE_ROUTES_API_KEY': 'fixture', 'GOOGLE_MATRIX_MAX_POINTS': '1'}):
            with self.assertRaises(ValueError): solver.Travel([node(1, 1), node(2, 2)], 'google')

    def test_expired_legacy_routes_stay_occupied_but_do_not_block_new_idle_vehicles(self):
        old = route()
        old['nodes'][0]['tw_end_min'] = -1
        free = {'id': 2, 'warehouse_id': 1, 'capacity_kg': 100., 'capacity_m3': 8., 'cost_per_km': 6500., 'shift_end_min': 10000.}
        result = solver.solve(request(old, [node(5, 3)], [free]))
        self.assertEqual(result['stats']['blocked_routes'], [old['id']])
        self.assertEqual(result['stats']['assigned'], 1)
        self.assertEqual(result['routes'][0]['vehicle_id'], 2)

    def test_new_trip_waits_until_driver_shift_starts(self):
        free = {'id': 2, 'warehouse_id': 1, 'capacity_kg': 100., 'capacity_m3': 8., 'cost_per_km': 6500., 'shift_start_min': 100., 'shift_end_min': 10000.}
        req = request(route(), [dict(node(5, 3), tw_end_min=50.)], [free])
        req['routes'] = []
        self.assertEqual(solver.solve(req)['stats']['assigned'], 0)


if __name__ == '__main__':
    unittest.main()
