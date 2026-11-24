from collections import Counter
from datetime import datetime
import unittest
from scripts.fixtures.demo_data import generate, ZONE
from scripts.fixtures.city_locations import load_catalog, distance_km


class CityDataTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data=generate(datetime(2026,11,22,9,tzinfo=ZONE),seed=20261122)
        cls.catalog=load_catalog()

    def test_every_address_and_coordinate_is_an_exact_source_pair(self):
        originals={(p['address'],p['lat'],p['lng']) for p in self.catalog['locations']}
        for order in self.data['orders']:
            self.assertIn((order['pickup_address'],order['pickup_lat'],order['pickup_lng']),originals)
            self.assertIn((order['address'],order['lat'],order['lng']),originals)
            self.assertNotEqual(order['pickup_address'],order['address'])
        self.assertEqual(len(self.data['location_sources']),207)

    def test_city_coverage_and_realistic_people(self):
        endpoints=[(o['pickup_address'],o['pickup_lat'],o['pickup_lng']) for o in self.data['orders']]+[(o['address'],o['lat'],o['lng']) for o in self.data['orders']]
        self.assertGreaterEqual(len(set(endpoints)),280)
        self.assertGreater(max(p[1] for p in endpoints)-min(p[1] for p in endpoints),.12)
        self.assertGreater(max(p[2] for p in endpoints)-min(p[2] for p in endpoints),.18)
        streets={p[0].split(',')[0].split(' ',1)[-1] for p in endpoints}
        self.assertGreater(len(streets),120)
        for o in self.data['orders']:
            for field in ('pickup_name','customer_name'):
                self.assertGreaterEqual(len(o[field].split()),3)
                self.assertFalse(any(c.isdigit() for c in o[field]))
                self.assertNotIn('Người gửi',o[field])
        self.assertGreater(len({o['pickup_name'] for o in self.data['orders']}),100)
        self.assertTrue(any(distance_km(dict(lat=o['pickup_lat'],lng=o['pickup_lng']),o)>15 for o in self.data['orders']))

    def test_no_current_trip_is_prescribed_by_the_seed(self):
        self.assertTrue(self.data['auto_route'])
        self.assertTrue(all(t['status']=='completed' for t in self.data['trips']))
        self.assertEqual(Counter(o['stage'] for o in self.data['orders']),dict(awaiting_pickup=36,at_warehouse=150,delivered=15,failed=6))
        self.assertNotEqual([o['warehouse_id'] for o in self.data['orders']],sorted(o['warehouse_id'] for o in self.data['orders']))
        for o in self.data['orders']:
            closest=min(self.data['warehouses'],key=lambda w:distance_km(w,dict(lat=o['pickup_lat'],lng=o['pickup_lng'])))
            self.assertEqual(o['warehouse_id'],closest['id'])

    def test_night_fixture_uses_tomorrows_eight_to_twenty_shift(self):
        data=generate(datetime(2026,11,22,21,tzinfo=ZONE),seed=20261122)
        self.assertEqual(str(data['dispatch_date']),'2026-11-23')
        self.assertTrue(all(d['shift_start']=='08:00' and d['shift_end']=='20:00' for d in data['driver_work_days']))
        for order in data['orders']:
            if order['stage']=='awaiting_pickup':
                self.assertEqual(str(order['pickup_from'].date()),'2026-11-23')
                self.assertGreaterEqual(order['pickup_from'].hour,8)
                self.assertLessEqual(order['pickup_to'].hour,20)
        for trip in data['trips']:
            self.assertGreaterEqual(trip['planned_start'].hour,8)
            self.assertLessEqual(trip['planned_end'].hour,20)
            self.assertEqual(trip['planned_start'].date(),trip['planned_end'].date())

    def test_seed_is_reproducible_and_changes_the_geographic_sample(self):
        again=generate(self.data['now'],seed=20261122)
        fields=lambda d:[(o['pickup_address'],o['address'],o['pickup_name'],o['customer_name']) for o in d['orders']]
        self.assertEqual(fields(again),fields(self.data))
        different=generate(self.data['now'],seed=20261123)
        self.assertNotEqual(fields(different),fields(self.data))


if __name__=='__main__':unittest.main()
