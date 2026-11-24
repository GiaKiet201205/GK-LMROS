#!/usr/bin/env python3
"""Preview, back up, and replace the three GK application databases with demo data."""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.fixtures.demo_data import generate, overview, sql_scripts

COMPOSE = ['docker', 'compose', '--profile', 'tools']
DATABASES = ('db', 'auth-db', 'customer-db')
INFRASTRUCTURE = {*DATABASES, 'redis', 'kafka'}


class ResetError(RuntimeError):
    pass


class Docker:
    def command(self, args, *, input=None, stdout=subprocess.PIPE):
        result = subprocess.run(COMPOSE+list(args), cwd=ROOT, input=input, stdout=stdout, stderr=subprocess.PIPE)
        if result.returncode:
            # Never print SQL, passwords, Compose environment or database contents in errors.
            raise ResetError(f"Docker command failed ({args[0]}, exit {result.returncode}). Check Docker access and container logs.")
        return result.stdout

    def sql(self, service, sql):
        return self.command(['exec','-T',service,'sh','-c',
            'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -At'], input=sql.encode()).decode().strip()

    def running(self):
        output = self.command(['ps','--status','running','--format','json']).decode().strip()
        if not output:
            return []
        rows = json.loads(output) if output.startswith('[') else [json.loads(line) for line in output.splitlines()]
        return [row['Service'] for row in rows]

    def stop(self, services):
        if services:
            self.command(['stop', *services])

    def start(self, services):
        if services:
            self.command(['start', '--wait', '--wait-timeout', '150', *services])

    def backup(self, service, path):
        with path.open('xb') as handle:
            os.chmod(path, 0o600)
            self.command(['exec','-T',service,'sh','-c',
                'exec pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB"'], stdout=handle)
            handle.flush()
            os.fsync(handle.fileno())
        # Read the complete archive inside the same PostgreSQL version that created it.
        self.command(['exec','-T',service,'pg_restore','--list'], input=path.read_bytes())
        self.command(['exec','-T',service,'pg_restore','--file=/dev/null'], input=path.read_bytes())
        if path.stat().st_size < 100:
            raise ResetError('Empty backup archive: '+service)

    def restore(self, service, path):
        self.command(['exec','-T',service,'sh','-c',
            'exec pg_restore --clean --if-exists --no-owner --single-transaction -U "$POSTGRES_USER" -d "$POSTGRES_DB"'], input=path.read_bytes())

    def clear_gps(self, services):
        if 'redis' in services:
            self.command(['exec','-T','redis','redis-cli','DEL','vehicles:last'])


def preflight(docker):
    running = docker.running()
    if not set(DATABASES).issubset(running):
        raise ResetError('Start all three databases and services once so their schemas have been migrated, then retry.')
    checks = {
        'db': 'SELECT count(*)=18 FROM schema_migrations WHERE version IN (3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20);',
        'auth-db': "SELECT to_regclass('app_users') IS NOT NULL AND to_regclass('auth_sessions') IS NOT NULL;",
        'customer-db': 'SELECT count(*)>0 FROM customer_schema_migrations WHERE version=1;',
    }
    for service, sql in checks.items():
        if docker.sql(service, sql) != 't':
            raise ResetError('Missing schema migration in '+service+'. Start the current application version first.')
    return running


def validate(docker, data):
    expectations = {'db': [('orders',len(data['orders'])),('vehicles',12),('drivers',15),
                           ('customer_tracking_events',len(data['customer_tracking_events']))],
                    'auth-db': [('app_users',16),('auth_sessions',0)],
                    'customer-db': [('customers',3),('bookings',len(data['bookings'])),
                                    ('booking_events',len(data['booking_events'])),('customer_sessions',0)]}
    for service, pairs in expectations.items():
        for table, count in pairs:
            if docker.sql(service, f'SELECT count(*) FROM {table};') != str(count):
                raise ResetError('Post-reset validation failed: '+service+'/'+table)
    expected_dirty='true' if data['auto_route'] else 'false'
    if docker.sql('db', f'SELECT count(*) FROM routing_state WHERE id=1 AND dirty={expected_dirty} AND lease_owner IS NULL;') != '1':
        raise ResetError('Routing fixture state is inconsistent')


def reset(docker, data, directory):
    """All writers stop before backup; no mutations until all three archives validate.

    Each database transaction is atomic. If a later database fails, restore ALL three
    snapshots with writers stopped. Never restart writers after an incomplete restore.
    """
    running = preflight(docker)
    writers = sorted(set(running)-INFRASTRUCTURE)
    directory.mkdir(parents=True, mode=0o700, exist_ok=False)
    directory.chmod(0o700)
    scripts = sql_scripts(data)
    mutated = False
    safe_to_restart = True
    try:
        docker.stop(writers)
        for service in DATABASES:
            print('Backing up '+service+'…', flush=True)
            docker.backup(service, directory/(service+'.dump'))
        manifest=directory/'manifest.json'
        manifest.write_text(json.dumps(dict(overview(data),order_location_sources=data['location_sources'],warehouse_location_sources=data['warehouse_sources']),ensure_ascii=False,indent=2)+'\n')
        manifest.chmod(0o600)
        for service in DATABASES:
            mutated = True
            print('Loading demo data into '+service+'…', flush=True)
            docker.sql(service, scripts[service])
        validate(docker, data)
        docker.clear_gps(running)
    except BaseException:
        if mutated:
            print('Reset failed. Restoring all three databases from verified backups…', file=sys.stderr, flush=True)
            failures=[]
            for service in DATABASES:
                try:
                    docker.restore(service, directory/(service+'.dump'))
                except BaseException:
                    failures.append(service)
            if failures:
                safe_to_restart=False
                raise ResetError('Restore incomplete for '+', '.join(failures)+'. Services remain stopped. Backups: '+str(directory))
        raise
    finally:
        if safe_to_restart:
            try:
                docker.start(writers)
            except Exception as error:
                raise ResetError('Data operation finished but some services did not restart. Use docker compose up -d. Backups: '+str(directory)) from error
    return directory


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reset',action='store_true',help='Replace ALL operational, staff and customer data after verified backups. Without this flag, preview only.')
    parser.add_argument('--seed',type=int,help='Random seed for reproducing the same addresses, people and order mix.')
    parser.add_argument('--no-auto-route',action='store_true',help='Keep the pickup queue paused after loading, for inspection only.')
    args=parser.parse_args()
    data=generate(seed=args.seed,auto_route=not args.no_auto_route)
    print(json.dumps(overview(data),ensure_ascii=False,indent=2))
    if not args.reset:
        print('\nPreview only. Apply with: sudo python3 scripts/ops/reset_demo_data.py --reset')
        return
    directory=ROOT.parent/(ROOT.name+'-backups')/'demo-reset'/datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    try:
        reset(Docker(),data,directory)
    except (ResetError, OSError, KeyboardInterrupt) as error:
        print(str(error) or 'Interrupted',file=sys.stderr)
        raise SystemExit(1)
    print('\nDemo data ready. Private backups: '+str(directory))
    if data['auto_route']:
        print('Pickup and delivery queues are ready for review in Distribution. The background worker only inserts new pickups into approved active pickup trips.')
    print('Admin: admin / 201205. Driver: TX-001…TX-015 / corresponding phone number.')
    print('Customers: 0909000001…0909000003 / GK@123456. Sign in again in each portal.')


if __name__=='__main__':
    main()
