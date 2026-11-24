"""Add tagged, realistic test history; never present generated transactions as real business records.
Uses actual catalogue addresses, the configured tariffs and the production optimizer's hard constraints.
Default is a dry run. --apply backs up the live DB and inserts atomically without removing existing data.
"""
from __future__ import annotations
import argparse,itertools,json,math,os,random,subprocess,sys
from collections import defaultdict,Counter
from datetime import datetime,date,time,timedelta
from pathlib import Path
from zoneinfo import ZoneInfo
ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT),str(ROOT/'services/optimizer/src')]
import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from gk_optimizer.solver import solve,evaluate,Travel
from scripts.fixtures.city_locations import load_catalog,AddressSampler,distance_km
from scripts.fixtures.people import identities,phone
ZONE=ZoneInfo('Asia/Ho_Chi_Minh')
BATCH='GK-DEMO-FINANCE-2026-09-V1'
def env():
 out={}
 for line in (ROOT/'.env').read_text().splitlines():
  if '=' in line and not line.lstrip().startswith('#'):
   k,v=line.split('=',1);out[k.strip()]=v.strip().strip('\"\'')
 return out

def connection(test=None):
 if test:
  from urllib.parse import urlparse
  p=urlparse(test)
  if p.hostname not in ('127.0.0.1','localhost') or not p.path.endswith('_test'):raise ValueError('Test URL must name a dedicated local *_test database')
  return psycopg.connect(test,autocommit=True,row_factory=dict_row)
 v=env();return psycopg.connect(host='127.0.0.1',port=v.get('DB_PORT','15433'),dbname=v.get('DB_NAME','gk'),user=v.get('DB_USER','gk'),password=v.get('DB_PASSWORD',''),autocommit=True,row_factory=dict_row)
def at(day,h,m=0):return datetime.combine(day,time(h,m),ZONE)
def moment(minutes):return datetime.fromtimestamp(minutes*60,ZONE)
def ids(c,table,n):return [r['id'] for r in c.execute("SELECT nextval(pg_get_serial_sequence(%s,'id')) AS id FROM generate_series(1,%s)",(table,n))]
def copy(c,table,rows):
 if not rows:return
 keys=list(rows[0]);assert all(list(r)==keys for r in rows),table
 with c.cursor().copy('COPY '+table+'('+','.join(keys)+') FROM STDIN') as stream:
  for r in rows:stream.write_row([Jsonb(r[k]) if isinstance(r[k],(dict,list)) else r[k] for k in keys])

def build(c,start,end,seed):
 rng=random.Random(seed);names=itertools.cycle(list(identities(rng)))
 warehouses=c.execute('SELECT id,name,lat,lng FROM warehouses ORDER BY id').fetchall();fleet=c.execute('SELECT id,code,plate,warehouse_id,capacity_kg::float8,capacity_m3::float8,cost_per_km::float8 FROM vehicles ORDER BY id').fetchall();drivers=c.execute('SELECT id,name,warehouse_id FROM drivers ORDER BY id').fetchall()
 if len(warehouses)!=3 or len(fleet)!=12 or len(drivers)!=15:raise ValueError('Expected existing GK demo fleet: 3 warehouses, 12 vehicles, 15 drivers; no data modified')
 minimum=c.execute('SELECT minimum_fare_vnd FROM pricing_settings WHERE id=1').fetchone()['minimum_fare_vnd']
 tariffs=c.execute('SELECT min_kg::float8,max_kg::float8,rate_vnd FROM shipping_tariffs WHERE enabled ORDER BY min_kg').fetchall()
 blocked=c.execute("SELECT vehicle_id,created_at::date AS since FROM vehicle_maintenance WHERE state<>'completed'").fetchall();blocked={r['vehicle_id']:r['since'] for r in blocked}
 schedules={(x['driver_id'],x['work_date']):x['working'] for x in c.execute('SELECT driver_id,work_date,working FROM driver_work_days WHERE work_date BETWEEN %s AND %s',(start,end))}
 reserved=c.execute("SELECT driver_id,vehicle_id,planned_start::date AS day FROM trips WHERE planned_start::date BETWEEN %s AND %s",(start,end)).fetchall();busy_drivers={(x['driver_id'],x['day']) for x in reserved};busy_vehicles={(x['vehicle_id'],x['day']) for x in reserved}
 sampler=AddressSampler(load_catalog()['locations'],warehouses,rng);orders={};trips=[];requested=0;counter=0;day=start
 while day<=end:
  for w in warehouses:
   available=[d for d in drivers if d['warehouse_id']==w['id'] and schedules.get((d['id'],day),day.weekday()!=(d['id']+2)%7) and (d['id'],day) not in busy_drivers]
   rotation=day.toordinal()%max(1,len(available));available=available[rotation:]+available[:rotation]
   vehicles=[v for v in fleet if v['warehouse_id']==w['id'] and (v['id'],day) not in busy_vehicles and (v['id'] not in blocked or day<blocked[v['id']])][:len(available)]
   if not vehicles:continue
   n={9:70,10:85,11:95}[day.month];requested+=n;batch=[]
   for index in range(n):
    p,q=sampler.pair(w['id']);kg=round(rng.uniform(.25,5) if rng.random()<.72 else rng.uniform(5.01,35),2);volume=round(max(.005,kg*.006),3);metres=round(distance_km(p,q)*1350);billed=math.floor(metres/1000+.5);rate=next(t['rate_vnd'] for t in tariffs if t['min_kg']<kg<=t['max_kg']);counter+=1;oid=counter
    batch.append(dict(id=oid,day=day,warehouse_id=w['id'],pickup_name=next(names),customer_name=next(names),pickup_phone=phone(rng),phone=phone(rng),pickup_address=p['address'],address=q['address'],pickup_lat=p['lat'],pickup_lng=p['lng'],lat=q['lat'],lng=q['lng'],weight_kg=kg,volume_m3=volume,service_min=3,shipping_distance_m=metres,billed_distance_km=billed,shipping_rate_vnd=rate,shipping_fee_vnd=max(minimum,billed*rate),payment_payer=rng.choice(['sender','recipient','recipient']),failed=rng.random()<{9:.08,10:.05,11:.03}[day.month],goods_description=rng.choice(['Thùng quần áo đóng gói','Hộp bánh đóng kín','Sách và tài liệu','Đồ gia dụng bọc chống sốc']),goods_type='other'))
   pairs={v['id']:available[i]['id'] for i,v in enumerate(vehicles)}
   def plan(phase,candidates,begin,finish):
    points=[dict(id=o['id'],code='demo',warehouse_id=w['id'],kind=phase,lat=o['pickup_lat'] if phase=='pickup' else o['lat'],lng=o['pickup_lng'] if phase=='pickup' else o['lng'],weight_kg=o['weight_kg'],volume_m3=o['volume_m3'],service_min=3,tw_start_min=begin.timestamp()/60,tw_end_min=finish.timestamp()/60) for o in candidates]
    return solve(dict(warehouses=[w],vehicles=[dict(v,shift_start_min=begin.timestamp()/60,shift_end_min=finish.timestamp()/60) for v in vehicles],routes=[],orders=points,now_min=begin.timestamp()/60,max_orders=45,max_stops=46,group_deliveries=True,seconds=.20,provider='estimate'))['routes']
   pickup=plan('pickup',batch,at(day,8),at(day,13,30));picked={n['id'] for r in pickup for n in r['nodes'] if n['kind']=='pickup'}
   if not pickup:continue
   delivery_start=moment(max(r['end_min'] for r in pickup))+timedelta(minutes=20)
   delivery=plan('delivery',[o for o in batch if o['id'] in picked],delivery_start,at(day,20));delivered={n['id'] for r in delivery for n in r['nodes'] if n['kind']=='delivery'}
   if not delivered:continue
   travel=Travel([],'estimate')
   for r in pickup:
    r['nodes']=[n for n in r['nodes'] if n['kind']=='warehouse' or n['id'] in delivered]
    if len(r['nodes'])==1:continue
    result=evaluate(r,r['nodes'],travel);assert result
    r['distance_km']=round(result[0]/1000,3);r['end_min']=result[1]
    for n,arrival in zip(r['nodes'],result[2]):n['arrive_min']=arrival
    r['driver_id']=pairs[r['vehicle_id']];r['day']=day;trips.append(r)
   for r in delivery:r['driver_id']=pairs[r['vehicle_id']];r['day']=day;trips.append(r)
   for o in batch:
    if o['id'] in delivered:orders[o['id']]=o
  if day.day%7==0:print('Planned through',day,'orders',len(orders),flush=True)
  day+=timedelta(days=1)
 return dict(orders=list(orders.values()),trips=trips,drivers=drivers,fleet=fleet,requested=requested)

def backup():
 v=env();folder=ROOT.parent/(ROOT.name+'-backups')/'finance-history'/datetime.now(ZONE).strftime('%Y%m%d-%H%M%S');folder.mkdir(parents=True,mode=0o700);path=folder/'before.dump';e=os.environ.copy();e['PGPASSWORD']=v.get('DB_PASSWORD','');e['PGCONNECT_TIMEOUT']='5'
 with path.open('wb') as f:os.chmod(path,0o600);subprocess.run(['pg_dump','-h','127.0.0.1','-p',v.get('DB_PORT','15433'),'-U',v.get('DB_USER','gk'),'-d',v.get('DB_NAME','gk'),'-Fc'],env=e,stdout=f,check=True)
 subprocess.run(['pg_restore','--list',str(path)],stdout=subprocess.DEVNULL,check=True);return str(path)

def apply(c,plan,start,end):
 with c.transaction():
  c.execute("SET LOCAL TIME ZONE 'Asia/Ho_Chi_Minh'");c.execute('SELECT pg_advisory_xact_lock(7462003)');c.execute('SELECT revision FROM routing_state WHERE id=1 FOR UPDATE');c.execute('SELECT pg_advisory_xact_lock(7462014)')
  for version,filename in [(15,'015_payments_fixed_salary.sql'),(16,'016_payment_notifications.sql'),(17,'017_payment_decisions_pricing.sql')]:
   if not c.execute('SELECT 1 FROM schema_migrations WHERE version=%s',(version,)).fetchone():
    c.execute((ROOT/'services/logistics/src/main/resources/db'/filename).read_text());c.execute('INSERT INTO schema_migrations(version)VALUES(%s)',(version,))
  if c.execute('SELECT 1 FROM demo_history_batches WHERE id=%s',(BATCH,)).fetchone():raise ValueError('History batch already exists; refusing duplicate import')
  # Historical milestones are inserted with actual simulated timestamps, without spamming current users.
  for table,trigger in [('orders','customer_order_tracking'),('trip_stops','customer_stop_tracking'),('customer_tracking_events','order_event_notifications')]:c.execute(f'ALTER TABLE {table} DISABLE TRIGGER {trigger}')
  orderids=ids(c,'orders',len(plan['orders']));mapping={o['id']:oid for o,oid in zip(plan['orders'],orderids)};orders={o['id']:o for o in plan['orders']}
  triprows=[];stops=[];events=[];pickup_done={};delivery_done={};pickup_stop={};delivery_stop={};daily_routes=defaultdict(list)
  tripids=ids(c,'trips',len(plan['trips']));stopids=iter(ids(c,'trip_stops',sum(len(r['nodes']) for r in plan['trips'])))
  for route,tid in zip(plan['trips'],tripids):
   day=route['day'];wh=route['warehouse_id'];begin=moment(route['start']['time_min']);finish=moment(route['end_min']);assert begin>=at(day,8) and finish<=at(day,20);assert len(route['nodes'])<=46
   triprows.append(dict(id=tid,code=f'GK-DEMO-{day:%y%m%d}-{tid}',vehicle_id=route['vehicle_id'],driver_id=route['driver_id'],warehouse_id=wh,status='completed',phase=route['phase'],planned_start=begin,planned_end=finish,planned_km=route['distance_km'],remaining_km=0,dispatch_mode='manual',max_orders=45,route_updated_at=finish,is_demo=True));daily_routes[(day,route['vehicle_id'],route['driver_id'],wh)].append(route)
   previous=begin
   for seq,n in enumerate(route['nodes'],1):
    kind=n['kind'];sid=next(stopids);oid=None if kind=='warehouse' else mapping[n['id']];arrive=moment(n['arrive_min']);complete=arrive+timedelta(minutes=n.get('service_min',0));failed=kind=='delivery' and orders[n['id']]['failed'];state='failed' if failed else 'completed'
    stops.append(dict(id=sid,trip_id=tid,order_id=oid,seq=seq,kind=kind,state=state,eta=arrive,arrived_at=arrive,departed_at=previous,completed_at=complete,fail_reason='Không liên hệ được người nhận' if failed else None));previous=complete
    if kind=='pickup':pickup_stop[n['id']]=dict(id=sid,at=arrive,driver=route['driver_id'],vehicle=route['vehicle_id']);pickup_done[n['id']]=finish
    if kind=='delivery':delivery_stop[n['id']]=dict(id=sid,at=arrive,driver=route['driver_id'],vehicle=route['vehicle_id']);delivery_done[n['id']]=complete
  rows=[]
  for o in plan['orders']:
   d=o['day'];oid=mapping[o['id']];rows.append(dict(id=oid,code=f'GK-DEMO-{d:%y%m%d}-{oid}',customer_name=o['customer_name'],phone=o['phone'],address=o['address'],lat=o['lat'],lng=o['lng'],weight_kg=o['weight_kg'],volume_m3=o['volume_m3'],tw_start=pickup_done[o['id']]+timedelta(minutes=10),tw_end=at(d,20),status='failed' if o['failed'] else 'delivered',warehouse_id=o['warehouse_id'],pickup_name=o['pickup_name'],pickup_phone=o['pickup_phone'],pickup_address=o['pickup_address'],pickup_lat=o['pickup_lat'],pickup_lng=o['pickup_lng'],pickup_from=at(d,8),pickup_to=at(d,13,30),stage='failed' if o['failed'] else 'delivered',in_warehouse=o['failed'],delivery_failures=1 if o['failed'] else 0,goods_description=o['goods_description'],goods_type=o['goods_type'],service_min=3,shipping_distance_m=o['shipping_distance_m'],billed_distance_km=o['billed_distance_km'],shipping_rate_vnd=o['shipping_rate_vnd'],shipping_fee_vnd=o['shipping_fee_vnd'],shipping_source='estimate',shipping_price_status='confirmed',payment_payer=o['payment_payer'],payment_required=True,is_demo=True,created_at=at(d,7,15)))
  copy(c,'orders',rows);copy(c,'trips',triprows);copy(c,'trip_stops',stops)
  collections=[];collection_groups=defaultdict(list);collectionids=iter(ids(c,'payment_collections',len(rows)))
  for o in plan['orders']:
   oid=mapping[o['id']];p=pickup_stop[o['id']];d=delivery_stop[o['id']];phase=p if o['payment_payer']=='sender' else d
   for kind,when,actor in [('created',at(o['day'],7,15),None),('pickup_assigned',at(o['day'],7,40),p),('picked_up',p['at']+timedelta(minutes=3),p),('at_warehouse',pickup_done[o['id']],p),('delivery_assigned',pickup_done[o['id']]+timedelta(minutes=5),d),('out_for_delivery',pickup_done[o['id']]+timedelta(minutes=10),d),('delivery_failed' if o['failed'] else 'delivered',delivery_done[o['id']],d)]:events.append(dict(order_id=oid,type=kind,created_at=when,driver_name=next((x['name'] for x in plan['drivers'] if actor and x['id']==actor['driver']),None),driver_phone=None,plate=next((x['plate'] for x in plan['fleet'] if actor and x['id']==actor['vehicle']),None),reason='Không liên hệ được người nhận' if kind=='delivery_failed' else None))
   if o['shipping_fee_vnd']<=0 or o['failed'] and o['payment_payer']=='recipient':continue
   cid=next(collectionids);method='transfer' if oid%4==0 else 'cash';holder='company' if method=='transfer' and oid%8==0 else 'driver';when=phase['at']+timedelta(seconds=30)
   collections.append(dict(id=cid,order_id=oid,stop_id=phase['id'],driver_id=phase['driver'],payer=o['payment_payer'],amount=o['shipping_fee_vnd'],method=method,received_by=holder,reference=f'DEMO-GD-{oid}' if method=='transfer' else None,created_at=when,created_by=1))
   events.append(dict(order_id=oid,type='payment_collected',created_at=when,driver_name=next(x['name'] for x in plan['drivers'] if x['id']==phase['driver']),driver_phone=None,plate=next(x['plate'] for x in plan['fleet'] if x['id']==phase['vehicle']),reason=f"Đã thu cước {o['shipping_fee_vnd']} đ"))
   if holder=='driver':collection_groups[(o['day'],phase['driver'],o['warehouse_id'])].append(collections[-1])
  copy(c,'payment_collections',collections);copy(c,'customer_tracking_events',events)
  for (day,driver,wh),items in collection_groups.items():
   last=max(moment(r['end_min']) for k,rs in daily_routes.items() if k[0]==day and k[2]==driver for r in rs)+timedelta(minutes=5)
   hid=c.execute("INSERT INTO cash_handovers(driver_id,warehouse_id,amount,collection_ids,status,note,created_at,reviewed_at,reviewed_by,review_note)VALUES(%s,%s,%s,%s,'approved','Nộp cước cuối ngày',%s,%s,1,'Đã nhận đủ tiền kiểm thử')RETURNING id",(driver,wh,sum(x['amount'] for x in items),Jsonb([x['id'] for x in items]),last,last+timedelta(minutes=5))).fetchone()['id']
   copy(c,'cash_handover_items',[dict(collection_id=x['id'],handover_id=hid) for x in items])
  for (day,vehicle,driver,wh),routes in daily_routes.items():
   km=sum(r['distance_km'] for r in routes);amount=round(km*.12*23000/1000)*1000
   rid=c.execute("INSERT INTO vehicle_cost_receipts(vehicle_id,driver_id,warehouse_id,cost_date,kind,amount,description,reference,status,created_by,created_at,reviewed_by,reviewed_at,is_demo)VALUES(%s,%s,%s,%s,'fuel',%s,'Đổ nhiên liệu cho xe',%s,'approved',1,%s,1,%s,true)RETURNING id",(vehicle,driver,wh,day,max(1000,amount),f'DEMO-NL-{day:%y%m%d}-{vehicle}',at(day,19),at(day,19,30))).fetchone()['id']
   c.execute("INSERT INTO finance_entries(source_key,entry_date,direction,category,amount,warehouse_id,driver_id,vehicle_id,vehicle_receipt_id,description,reference,created_by,is_demo)VALUES(%s,%s,'expense','fuel',%s,%s,%s,%s,%s,'Đổ nhiên liệu cho xe',%s,1,true)",(f'vehicle-cost:{rid}',day,max(1000,amount),wh,driver,vehicle,rid,f'DEMO-NL-{rid}'))
  for month in (9,10):
   day=date(2026,month,25)
   for v in plan['fleet']:
    rid=c.execute("INSERT INTO vehicle_cost_receipts(vehicle_id,warehouse_id,cost_date,kind,amount,description,reference,status,created_by,created_at,reviewed_by,reviewed_at,is_demo)VALUES(%s,%s,%s,'other',%s,'Bảo dưỡng định kỳ',%s,'approved',1,%s,1,%s,true)RETURNING id",(v['id'],v['warehouse_id'],day,1200000+(v['id']%3)*150000,f'DEMO-BD-{month}-{v["id"]}',at(day,18),at(day,18,30))).fetchone()['id'];c.execute("INSERT INTO finance_entries(source_key,entry_date,direction,category,amount,warehouse_id,vehicle_id,vehicle_receipt_id,description,created_by,is_demo)VALUES(%s,%s,'expense','maintenance',%s,%s,%s,%s,'Bảo dưỡng định kỳ',1,true)",(f'vehicle-cost:{rid}',day,1200000+(v['id']%3)*150000,v['warehouse_id'],v['id'],rid))
  # Register the actual simulated work calendar, six days per week.
  workdays=[];day=start-timedelta(days=start.weekday())
  while day<=end:
   for d in plan['drivers']:workdays.append((d['id'],day,day.weekday()!=(d['id']+2)%7))
   day+=timedelta(days=1)
  c.cursor().executemany("INSERT INTO driver_work_days(driver_id,work_date,working,shift_start,shift_end)VALUES(%s,%s,%s,'08:00','20:00')ON CONFLICT(driver_id,work_date)DO NOTHING",workdays)
  # Accrue the agreed fixed salary daily; the application will see zero extra delta on startup.
  for d in plan['drivers']:
   day=start
   while day<=datetime.now(ZONE).date():
    from calendar import monthrange
    days=monthrange(day.year,day.month)[1];amount=round(15000000*day.day/days)-round(15000000*(day.day-1)/days)
    existing=c.execute("SELECT coalesce(sum(expense),0) AS amount FROM finance_ledger WHERE category='salary' AND payroll_id IS NULL AND driver_id=%s AND entry_date=%s",(d['id'],day)).fetchone()['amount'];delta=amount-int(existing)
    if delta:c.execute("INSERT INTO finance_entries(source_key,entry_date,direction,category,amount,warehouse_id,driver_id,description,created_by,is_demo)VALUES(%s,%s,%s,'salary',%s,%s,%s,'Phân bổ lương cố định 15 triệu/tháng',1,true)",(f'demo-salary:{BATCH}:{d["id"]}:{day}',day,'expense' if delta>0 else 'income',abs(delta),d['warehouse_id'],d['id']))
    day+=timedelta(days=1)
  # Closed-month salary payslips reference the fixed base already accrued above.
  for month in (9,10):
   from calendar import monthrange
   first=date(2026,month,1);last=date(2026,month,monthrange(2026,month)[1])
   if last>end:continue
   for d in plan['drivers']:
    if c.execute("SELECT 1 FROM driver_payrolls WHERE driver_id=%s AND period=%s AND status<>'cancelled'",(d['id'],first)).fetchone():continue
    days=sorted({r['day'] for r in plan['trips'] if r['driver_id']==d['id'] and r['day'].month==month})
    import uuid
    c.execute("INSERT INTO driver_payrolls(request_id,driver_id,warehouse_id,period,worked_days,actual_days,daily_rate,work_dates,description,status,created_by,created_at,approved_by,approved_at,salary_mode,monthly_salary,is_demo)VALUES(%s,%s,%s,%s,%s,%s,1,%s,%s,'approved',1,%s,1,%s,'fixed',15000000,true)",(uuid.uuid5(uuid.NAMESPACE_URL,BATCH+str(d['id'])+str(first)),d['id'],d['warehouse_id'],first,len(days),len(days),Jsonb([dict(work_date=str(day)) for day in days]),f'Lương cố định tháng {month:02}/2026',at(last,20),at(last+timedelta(days=1),9)))
  for table,trigger in [('orders','customer_order_tracking'),('trip_stops','customer_stop_tracking'),('customer_tracking_events','order_event_notifications')]:c.execute(f'ALTER TABLE {table} ENABLE TRIGGER {trigger}')
  metadata=dict(orders=len(rows),trips=len(triprows),collections=len(collections),requested=plan['requested'],distance_source='estimate',fuel_scenario_vnd_litre=23000,fuel_scenario_litres_per_100km=12,salary_monthly_vnd=15000000,seed=202609)
  c.execute('INSERT INTO demo_history_batches(id,from_date,to_date,metadata)VALUES(%s,%s,%s,%s)',(BATCH,start,end,Jsonb(metadata)))
  c.execute('UPDATE routing_state SET revision=revision+1,dirty=true WHERE id=1')
 return metadata

def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--apply',action='store_true');parser.add_argument('--test-db');parser.add_argument('--days',type=int);args=parser.parse_args();start=date(2026,9,1);end=datetime.now(ZONE).date()-timedelta(days=1)
 if args.days:end=min(end,start+timedelta(days=args.days-1))
 with connection(args.test_db) as c:
  if c.execute("SELECT to_regclass('public.demo_history_batches') AS t").fetchone()['t'] and c.execute('SELECT 1 FROM demo_history_batches WHERE id=%s',(BATCH,)).fetchone():raise SystemExit('Batch already applied; no data changed')
  revision=c.execute('SELECT revision FROM pricing_settings WHERE id=1').fetchone()['revision']
  cache=ROOT.parent/(ROOT.name+'-backups')/'finance-history'/f'plan-tariff-{revision}-{start}-{end}.json'
  if not args.test_db and cache.exists():
   plan=json.loads(cache.read_text())
   for o in plan['orders']:o['day']=date.fromisoformat(o['day'])
   for trip in plan['trips']:trip['day']=date.fromisoformat(trip['day'])
   print('Reusing verified local history plan',flush=True)
  else:
   plan=build(c,start,end,202609)
   if not args.test_db:
    cache.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    with cache.open('w') as f:os.chmod(cache,0o600);json.dump(plan,f,ensure_ascii=False,default=str)
  print(json.dumps(dict(from_date=str(start),to_date=str(end),orders=len(plan['orders']),trips=len(plan['trips']),apply=args.apply),ensure_ascii=False),flush=True)
  if not args.apply:return
  if not args.test_db:print('Verified backup:',backup(),flush=True)
  print(json.dumps(apply(c,plan,start,end),ensure_ascii=False),flush=True)
if __name__=='__main__':main()
