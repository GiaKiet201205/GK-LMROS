from collections import defaultdict
from datetime import datetime
import base64
import hashlib
from pathlib import Path
import tempfile
import unittest

from scripts.fixtures.demo_data import generate, sql_scripts, ZONE
from scripts.ops.reset_demo_data import reset, ResetError, DATABASES


class DemoDataTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data=generate(datetime(2026,11,22,9,0,tzinfo=ZONE),seed=20261122)

    def test_calendar_and_reserves(self):
        weeks=defaultdict(list)
        for row in self.data['driver_work_days']:
            weeks[(row['driver_id'],row['work_date'][:10] >= '2026-11-23')].append(row['working'])
        self.assertEqual(len(weeks),24)
        self.assertTrue(all(len(days)==7 and sum(days)==6 for days in weeks.values()))
        self.assertEqual({d['id'] for d in self.data['drivers']} - {d['driver_id'] for d in self.data['driver_work_days']},{4,9,14})
        reserve=[d for d in self.data['drivers'] if d['id'] in (5,10,15)]
        self.assertTrue(all(d['vehicle_id'] is None for d in reserve))
        busy={t['vehicle_id'] for t in self.data['trips'] if t['status']!='completed'}
        self.assertEqual(busy,set())
        self.assertTrue(self.data['auto_route'])
        self.assertTrue(all(v['status']!='active' for v in self.data['vehicles']))
        self.assertTrue({1,3,5,7,9,11}.isdisjoint(busy))

    def test_realistic_states_and_customer_ownership(self):
        data=self.data
        orders={o['id']:o for o in data['orders']}
        self.assertEqual(len(orders),207)
        for wh in (1,2,3):
            self.assertEqual(sum(o['stage']=='at_warehouse' and o['warehouse_id']==wh for o in orders.values()),50)
        bookings={b['id']:b for b in data['bookings']}
        self.assertEqual(len({b['customer_id'] for b in bookings.values()}),3)
        for row in orders.values():
            if row['stage'] in ('delivered','picked_up','out_for_delivery'):
                self.assertFalse(row['in_warehouse'])
            if row['customer_booking_id']:
                b=bookings[row['customer_booking_id']]
                self.assertEqual((b['order_id'],b['customer_id']),(row['id'],row['customer_ref']))
                self.assertEqual(b['snapshot']['stage'],row['stage'])
        events_by_id={e['id']:e for e in data['customer_tracking_events']}
        for cache,notice in zip(data['booking_events'],data['customer_notifications']):
            event=events_by_id[cache['source_id']]
            self.assertEqual(event['id'],cache['source_id'])
            self.assertEqual(event['id'],notice['source_event'])
            self.assertEqual(orders[event['order_id']]['customer_booking_id'],cache['booking_id'])
        trips={t['id']:t for t in data['trips']}
        counts=defaultdict(int)
        for stop in data['trip_stops']:
            if stop['kind']!='warehouse':
                counts[stop['trip_id']]+=1
                self.assertIn(stop['order_id'],orders)
            if stop['completed_at']:
                self.assertLessEqual(stop['completed_at'],data['now'])
        self.assertTrue(all(count<=45 for count in counts.values()))
        # No overlapping trips for a driver or vehicle, including completed histories.
        for key in ('driver_id','vehicle_id'):
            groups=defaultdict(list)
            for t in trips.values():
                groups[t[key]].append(t)
            for group in groups.values():
                group.sort(key=lambda t:t['planned_start'])
                for a,b in zip(group,group[1:]):
                    self.assertLessEqual(a['planned_end'],b['planned_start'])

    def test_password_format_matches_java_and_bootstrap_preserved(self):
        for user in self.data['app_users']:
            salt,digest=user['password_hash'].split(':')
            password='201205' if user['role']=='dispatcher' else self.data['drivers'][user['driver_id']-1]['phone']
            self.assertEqual(hashlib.pbkdf2_hmac('sha256',password.encode(),base64.b64decode(salt),210000,32),base64.b64decode(digest))
        scripts=sql_scripts(self.data)
        self.assertEqual(set(scripts),set(DATABASES))
        self.assertTrue(all(s.startswith('BEGIN;') and s.endswith('COMMIT;\n') for s in scripts.values()))
        self.assertTrue(all('TRUNCATE schema_migrations' not in s for s in scripts.values()))


class FakeDocker:
    def __init__(self, fail_backup=None, fail_write=None, fail_restore=None):
        self.calls=[];self.fail_backup=fail_backup;self.fail_write=fail_write;self.fail_restore=fail_restore
    def running(self): return [*DATABASES,'logistics','auth','customer','redis','frontend']
    def sql(self,service,sql):
        if sql.startswith('BEGIN;'):
            self.calls.append(('write',service))
            if service==self.fail_write: raise ResetError('Fixture write failure')
            return ''
        if 'schema_migrations' in sql or 'to_regclass' in sql: return 't'
        if 'lease_owner IS NULL' in sql:return '1'
        table=sql.split('FROM ')[1].split(';')[0]
        sizes={'orders':207,'vehicles':12,'drivers':15,'customer_tracking_events':len(DemoDataTests.data['customer_tracking_events']),
               'app_users':16,'auth_sessions':0,'customers':3,'bookings':len(DemoDataTests.data['bookings']),
               'booking_events':len(DemoDataTests.data['booking_events']),'customer_sessions':0}
        return str(sizes[table])
    def stop(self,services):self.calls.append(('stop',tuple(services)))
    def start(self,services):self.calls.append(('start',tuple(services)))
    def backup(self,service,path):
        self.calls.append(('backup',service))
        if service==self.fail_backup:raise ResetError('Fixture backup failure')
        path.write_bytes(b'fixture')
    def restore(self,service,path):
        self.calls.append(('restore',service))
        if service==self.fail_restore:raise ResetError('Fixture restore failure')
    def clear_gps(self,services):self.calls.append(('gps',))


class ResetRecoveryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not hasattr(DemoDataTests,'data'):DemoDataTests.setUpClass()
    def run_reset(self,docker):
        with tempfile.TemporaryDirectory() as tmp:
            return reset(docker,DemoDataTests.data,Path(tmp)/'backup')
    def test_backup_failure_never_mutates(self):
        docker=FakeDocker(fail_backup='customer-db')
        with self.assertRaises(ResetError):self.run_reset(docker)
        self.assertFalse(any(c[0] in ('write','restore') for c in docker.calls))
        self.assertEqual(docker.calls[-1][0],'start')
    def test_partial_write_restores_every_database(self):
        docker=FakeDocker(fail_write='auth-db')
        with self.assertRaises(ResetError):self.run_reset(docker)
        self.assertEqual([c[1] for c in docker.calls if c[0]=='restore'],list(DATABASES))
        self.assertEqual(docker.calls[-1][0],'start')
    def test_failed_restore_keeps_writers_stopped(self):
        docker=FakeDocker(fail_write='auth-db',fail_restore='db')
        with self.assertRaisesRegex(ResetError,'remain stopped'):self.run_reset(docker)
        self.assertFalse(any(c[0]=='start' for c in docker.calls))
    def test_success_backs_up_all_before_writes(self):
        docker=FakeDocker();self.run_reset(docker)
        firstwrite=next(i for i,c in enumerate(docker.calls) if c[0]=='write')
        self.assertEqual([c[1] for c in docker.calls[:firstwrite] if c[0]=='backup'],list(DATABASES))
        self.assertEqual(docker.calls[-1][0],'start')


if __name__=='__main__':unittest.main()
