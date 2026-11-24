import os
import unittest
from unittest.mock import patch
from fastapi.testclient import TestClient
from gk_optimizer.api import app
from gk_optimizer.settings import Settings
from gk_optimizer import solver


class OptimizerSecurityTest(unittest.TestCase):
    def test_missing_or_placeholder_token_prevents_startup(self):
        for token in ['', ' ', 'replace-with-a-random-internal-token']:
            with self.subTest(token=token), patch.dict(os.environ, {'INTERNAL_SERVICE_TOKEN': token}):
                with self.assertRaisesRegex(ValueError, 'INTERNAL_SERVICE_TOKEN'):
                    with TestClient(app):
                        pass

    def test_no_token_wrong_token_and_non_ascii_token_never_call_solver(self):
        with patch.dict(os.environ, {'INTERNAL_SERVICE_TOKEN': 'optimizer-test-secret'}), TestClient(app) as client:
            with patch('gk_optimizer.api.solver.solve') as solve:
                for headers in [{}, {'X-Internal-Token': 'wrong'}, {'X-Internal-Token': b'\xff'}]:
                    response = client.post('/solve', content='invalid json', headers=headers)
                    self.assertEqual(response.status_code, 403)
                    self.assertNotIn('optimizer-test-secret', response.text)
                self.assertEqual(client.get('/docs').status_code, 403)
                self.assertEqual(client.get('/health').status_code, 200)
                self.assertEqual(client.head('/health').status_code, 200)
                solve.assert_not_called()

    def test_environment_default_and_database_request_override(self):
        with patch.dict(os.environ, {'INTERNAL_SERVICE_TOKEN': 'optimizer-test-secret', 'OPTIMIZER_FIXED_TRIP_COST': '74000'}), TestClient(app) as client:
            headers = {'X-Internal-Token': 'optimizer-test-secret'}
            body = {'warehouses': [], 'vehicles': [], 'now_min': 0}
            with patch('gk_optimizer.api.solver.solve', return_value={'routes': []}) as solve:
                self.assertEqual(client.post('/solve', headers=headers, json=body).status_code, 200)
                self.assertEqual(solve.call_args.args[0]['fixed_trip_cost'], 74000)
                self.assertEqual(client.post('/solve', headers=headers, json={**body, 'fixed_trip_cost': 19000}).status_code, 200)
                self.assertEqual(solve.call_args.args[0]['fixed_trip_cost'], 19000)
                self.assertEqual(client.post('/solve', headers=headers, json={**body, 'fixed_trip_cost': 0}).status_code, 200)
                self.assertEqual(solve.call_args.args[0]['fixed_trip_cost'], 0)
                for value in [-1, 1000000001, 'NaN', 'Infinity']:
                    self.assertEqual(client.post('/solve', headers=headers, json={**body, 'fixed_trip_cost': value}).status_code, 422)

    def test_errors_hide_internal_provider_messages(self):
        with patch.dict(os.environ, {'INTERNAL_SERVICE_TOKEN': 'optimizer-test-secret'}), TestClient(app) as client:
            with patch('gk_optimizer.api.solver.solve', side_effect=ValueError('secret=DO_NOT_EXPOSE private-db')):
                result = client.post('/solve', headers={'X-Internal-Token': 'optimizer-test-secret'}, json={'warehouses': [], 'vehicles': [], 'now_min': 0})
                self.assertEqual(result.status_code, 503)
                self.assertNotIn('DO_NOT_EXPOSE', result.text)

    def test_invalid_environment_cost_fails_configuration(self):
        for value in ['NaN', 'Infinity', '-1', 'not-a-number', '1000000001']:
            with patch.dict(os.environ, {'INTERNAL_SERVICE_TOKEN': 'optimizer-test-secret', 'OPTIMIZER_FIXED_TRIP_COST': value}):
                with self.assertRaisesRegex(ValueError, 'OPTIMIZER_FIXED_TRIP_COST'):
                    Settings.from_env()

    def test_standalone_solver_uses_same_configured_cost(self):
        with patch.dict(os.environ, {'OPTIMIZER_FIXED_TRIP_COST': '74000'}):
            routes = [{'id': 1, 'frozen_count': 0, 'onboard_kg': 0, 'onboard_m3': 0, 'vehicle_id': 1, 'warehouse_id': 1, 'capacity_kg': 100, 'capacity_m3': 8,
                       'cost_per_km': 0, 'shift_end_min': 10000, 'start': {'lat': 0, 'lng': 0, 'time_min': 0},
                       'nodes': [{'id': 1, 'kind': 'delivery', 'warehouse_id': 1, 'lat': 0, 'lng': .001,
                                  'weight_kg': 1, 'volume_m3': .01, 'tw_start_min': 0, 'tw_end_min': 10000, 'service_min': 0},
                                 {'kind': 'warehouse', 'lat': 0, 'lng': 0, 'service_min': 0}], 'phase': 'delivery', 'status': 'planned'}]
            result = solver.solve({'warehouses': [{'id': 1, 'lat': 0, 'lng': 0}], 'vehicles': [], 'routes': routes, 'orders': [], 'now_min': 0, 'group_deliveries': True})
            self.assertEqual(result['stats']['estimated_cost'], 74000)
