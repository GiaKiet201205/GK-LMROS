"""Coherent demo fixtures shared by the reset command and offline validation.

No database access here. IDs are stable within a generated fixture; passwords use the services' PBKDF2 format.
All dates are relative to the Asia/Ho_Chi_Minh clock at generation time.
"""
from collections import Counter
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
from uuid import uuid5, NAMESPACE_URL
import base64
import hashlib
import json
import secrets
import random
from scripts.fixtures.city_locations import load_catalog, AddressSampler, distance_km, historical_tour
from scripts.fixtures.people import identities, phone

ZONE = ZoneInfo('Asia/Ho_Chi_Minh')
CUSTOMER_PASSWORD = 'GK@123456'


def uid(value):
    return str(uuid5(NAMESPACE_URL, 'gk-demo/' + str(value)))


def password_hash(password):
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac('sha256', password.encode(), salt, 210000, 32)
    return base64.b64encode(salt).decode() + ':' + base64.b64encode(digest).decode()


def literal(value):
    if value is None:
        return 'NULL'
    if isinstance(value, bool):
        return 'true' if value else 'false'
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, (dict, list)):
        value = json.dumps(value, ensure_ascii=False)
    if isinstance(value, datetime):
        value = value.isoformat()
    return "'" + str(value).replace("'", "''") + "'"


def insert(table, rows):
    if not rows:
        return ''
    columns = list(rows[0])
    assert all(list(row) == columns for row in rows), table
    return 'INSERT INTO ' + table + '(' + ','.join(columns) + ') VALUES\n' + ',\n'.join(
        '(' + ','.join(literal(row[k]) for k in columns) + ')' for row in rows) + ';\n'


def generate(now=None, seed=None, auto_route=True):
    now = (now or datetime.now(ZONE)).astimezone(ZONE).replace(microsecond=0)
    today = now.replace(hour=0, minute=0, second=0)
    monday = now.date() - timedelta(days=now.weekday())
    dispatch_day = now.date() if now.hour<20 else now.date()+timedelta(days=1)
    shift_start = datetime.combine(dispatch_day,datetime.min.time(),tzinfo=ZONE)+timedelta(hours=8)
    shift_end = shift_start+timedelta(hours=12)
    service_begin = max(now,shift_start)
    seed = secrets.randbits(63) if seed is None else int(seed)
    rng = random.Random(seed)
    names = identities(rng)
    catalog = load_catalog()
    data = {key: [] for key in ('warehouses', 'vehicles', 'drivers', 'driver_work_days',
                               'vehicle_work_days', 'orders', 'trips', 'trip_stops',
                               'customer_tracking_events', 'customers', 'bookings',
                               'booking_events', 'customer_notifications', 'app_users')}
    data.update(random_seed=seed, auto_route=auto_route, location_sources=[], warehouse_sources=[],
                location_catalog=dict(source=catalog['source'],retrieved_at=catalog['retrieved_at'],
                                      license=catalog['license'],license_url=catalog['license_url']))
    centers = [('BT', 'Bình Tân', 10.765, 106.603), ('TD', 'Thủ Đức', 10.850, 106.772), ('NB', 'Nhà Bè', 10.695, 106.740)]
    for i, name in enumerate(('Nguyễn Gia Kiệt', 'Trần Minh Anh', 'Lê Thanh Hà'), 1):
        data['customers'].append(dict(id=uid('customer/'+str(i)), phone=f'090900000{i}', name=name,
                                      password_hash=password_hash(CUSTOMER_PASSWORD), created_at=now-timedelta(days=10)))
    data['app_users'].append(dict(id=1, username='admin', password_hash=password_hash('201205'),
                                  display_name='Điều phối viên GK', role='dispatcher', driver_id=None, active=True))
    driver_names = ['Nguyễn Minh Quân','Trần Gia Bảo','Lê Đức Huy','Phạm Quốc An','Võ Thành Đạt',
                    'Bùi Hoàng Nam','Đặng Anh Tuấn','Huỳnh Minh Khang','Đỗ Trung Kiên','Ngô Thanh Tùng',
                    'Trần Gia Kiệt','Lê Hoàng Phúc','Nguyễn Đức Thành','Phạm Văn Long','Vũ Minh Khôi']
    for wh, (code, name, lat, lng) in enumerate(centers, 1):
        site=min(catalog['locations'],key=lambda p:distance_km(p,dict(lat=lat,lng=lng)))
        data['warehouses'].append(dict(id=wh,code=code,name='GK · '+name,lat=site['lat'],lng=site['lng'],color='#0f766e'))
        data['warehouse_sources'].append(dict(warehouse_id=wh,address=site['address'],source_url=site['source_url']))
        for pos in range(4):
            vid=(wh-1)*4+pos+1
            data['vehicles'].append(dict(id=vid,code=f'XE-{vid:03}',plate=f'51D-600.{vid:02}',warehouse_id=wh,
                                         capacity_kg=1000,capacity_m3=8,cost_per_km=6500,
                                         status='maintenance' if vid in (4,8,10) else 'available'))
        home=[(wh-1)*4+1,12 if wh==3 else (wh-1)*4+2,None,10 if wh==3 else wh*4,None]
        for pos in range(5):
            did=(wh-1)*5+pos+1
            driver_phone='0903456789' if did==12 else f'0903{did:06}'
            driver=dict(id=did,code=f'TX-{did:03}',name=driver_names[did-1],phone=driver_phone,warehouse_id=wh,
                        vehicle_id=home[pos],joined_on='2026-09-01',shift_start='08:00',shift_end='20:00',status='off')
            data['drivers'].append(driver)
            data['app_users'].append(dict(id=did+1,username=driver['code'],password_hash=password_hash(driver_phone),
                                          display_name=driver['name'],role='driver',driver_id=did,active=True))
            if pos!=3:
                for day in range(14):
                    date=monday+timedelta(days=day)
                    rest=dispatch_day.weekday() if pos==2 else (dispatch_day.weekday()+1+(did-1)%6)%7
                    data['driver_work_days'].append(dict(driver_id=did,work_date=str(date),working=date.weekday()!=rest,
                                                        shift_start='08:00',shift_end='20:00'))
        for vid in range((wh-1)*4+1,wh*4+1):
            data['vehicle_work_days'].append(dict(vehicle_id=vid,work_date=str(dispatch_day),available=vid!=11,
                                                 note='Đã giữ xe cho công việc khác trong ngày' if vid==11 else ''))

    sampler=AddressSampler(catalog['locations'],data['warehouses'],rng)
    drafts=[]
    for wh in range(1,4):
        drafts.extend((wh,'awaiting_pickup',False) for _ in range(12))
        drafts.extend((wh,'at_warehouse',True) for _ in range(50))
        drafts.extend((wh,'delivered',False) for _ in range(5))
        drafts.extend([(wh,'failed',False),(wh,'failed',True)])
    rng.shuffle(drafts)  # IDs and insertion order must not encode a geography or a route.
    notes=('Gọi điện trước khi đến.','Nhận hàng tại quầy lễ tân.','Hàng dễ vỡ, vui lòng giữ thẳng kiện.','Liên hệ người nhận khi đến cổng.','Giao tại tầng trệt.','Kiện hàng đã đóng gói sẵn.','')
    for oid,(wh,stage,inventory) in enumerate(drafts,1):
        pickup,delivery=sampler.pair(wh)
        linked=oid%3==0
        customer=data['customers'][(oid//3)%3] if linked else None
        parcels=rng.choices((1,2,3,4),(65,23,9,3))[0]
        weight=round(parcels*rng.uniform(.5,8),2)
        volume=round(parcels*rng.uniform(.008,.065),3)
        live=stage=='awaiting_pickup'
        pickup_offset=min(rng.choice((0,0,15,30)),max(0,int((shift_end-service_begin).total_seconds()/60)//3)) if live else 0
        pickup_from=service_begin+timedelta(minutes=pickup_offset) if live else today-timedelta(days=3)+timedelta(hours=6)
        pickup_to=min(service_begin+timedelta(hours=rng.choice((4,6,8))),shift_end) if live else today-timedelta(days=2)+timedelta(hours=20)
        delivery_from=max(shift_start,service_begin+timedelta(minutes=rng.choice((-30,0,0,30,60)))) if stage=='at_warehouse' else pickup_to+timedelta(hours=1) if live else today-timedelta(days=2)
        delivery_to=service_begin+timedelta(hours=rng.choice((8,12,24,30))) if stage=='at_warehouse' else now+timedelta(days=1,hours=6) if live else now+timedelta(hours=1)
        row=dict(id=oid,code=f'GK-{now:%y%m%d}-{oid:04}',customer_name=next(names),phone=phone(rng),
                 address=delivery['address'],district=delivery['district'],lat=delivery['lat'],lng=delivery['lng'],
                 weight_kg=weight,volume_m3=volume,parcels=parcels,goods_description=f'{parcels} kiện '+rng.choice(('quần áo đóng thùng carton','bánh đóng hộp kín','sách đóng gói','đồ gia dụng bọc chống sốc')),goods_type='other',tw_start=delivery_from,tw_end=delivery_to,
                 service_min=rng.choice((2,3,4,4,5,6)),status={'awaiting_pickup':'new','at_warehouse':'pending','delivered':'delivered','failed':'failed'}[stage],
                 warehouse_id=wh,is_dynamic=rng.random()<.18,note=rng.choice(notes),
                 created_at=now-timedelta(minutes=rng.randrange(10,180)) if live else today-timedelta(days=4)+timedelta(minutes=rng.randrange(60,600)),
                 pickup_name=customer['name'] if customer else next(names),pickup_phone=customer['phone'] if customer else phone(rng),
                 pickup_address=pickup['address'],pickup_lat=pickup['lat'],pickup_lng=pickup['lng'],
                 pickup_from=pickup_from,pickup_to=pickup_to,stage=stage,in_warehouse=inventory,delivery_failures=1 if stage=='failed' and inventory else 0,
                 customer_ref=customer['id'] if customer else None,customer_booking_id=uid('booking/'+str(oid)) if customer else None)
        data['orders'].append(row)
        data['location_sources'].append(dict(order_id=oid,pickup=pickup['source_url'],delivery=delivery['source_url']))

    def past_trip(wh, phase, rows, start=None, finish_before=None):
        depot=data['warehouses'][wh-1]
        rows=historical_tour(rows,phase,depot)
        tid=len(data['trips'])+1
        elapsed=0.;km=0.;at=depot;points=[]
        for row in rows:
            point=dict(lat=row['pickup_lat'],lng=row['pickup_lng']) if phase=='pickup' else row
            leg=distance_km(at,point)*1.35;km+=leg;elapsed+=leg/30*60+row['service_min']
            failed=row['stage']=='failed' and ((phase=='pickup' and not row['in_warehouse']) or phase=='delivery')
            points.append((row,elapsed,'failed' if failed else 'completed'));at=point
        leg=distance_km(at,depot)*1.35;km+=leg;elapsed+=leg/30*60+5
        if finish_before is not None:
            finish_before=min(finish_before,finish_before.replace(hour=20,minute=0,second=0))
            start=finish_before-timedelta(minutes=elapsed)
            if start.hour<8 or start.date()!=finish_before.date():
                finish_before=(finish_before-timedelta(days=1)).replace(hour=20,minute=0,second=0)
                start=finish_before-timedelta(minutes=elapsed)
        elif start.hour<8:
            start=start.replace(hour=8,minute=0,second=0)
        if (start+timedelta(minutes=elapsed))>start.replace(hour=20,minute=0,second=0):
            start=(start+timedelta(days=1)).replace(hour=8,minute=0,second=0)
        end=start+timedelta(minutes=elapsed)
        data['trips'].append(dict(id=tid,code=f'GK-T{tid:03}',vehicle_id=1+(wh-1)*4,driver_id=1+(wh-1)*5,
                                  warehouse_id=wh,status='completed',planned_start=start,planned_end=end,planned_km=round(km,3),
                                  phase=phase,remaining_km=0,dispatch_mode='manual' if phase=='delivery' else 'auto',
                                  max_orders=45,route_updated_at=end))
        for seq,(row,minute,state) in enumerate(points+[(None,elapsed,'completed')],1):
            moment=start+timedelta(minutes=minute)
            data['trip_stops'].append(dict(id=len(data['trip_stops'])+1,trip_id=tid,order_id=row['id'] if row else None,
                                          seq=seq,kind=phase if row else 'warehouse',state=state,eta=moment,arrived_at=moment,
                                          completed_at=moment,departed_at=moment-timedelta(minutes=2),
                                          fail_reason=rng.choice(('Khách hẹn lại thời gian.','Không liên hệ được người nhận.','Địa điểm đang đóng cửa.')) if state=='failed' else None))
        return end

    for wh in range(1,4):
        rows=[o for o in data['orders'] if o['warehouse_id']==wh]
        inbound=[o for o in rows if o['stage']!='awaiting_pickup']
        rng.shuffle(inbound)
        cursor=today-timedelta(days=3)+timedelta(hours=7)
        for index in range(0,len(inbound),30):
            cursor=past_trip(wh,'pickup',inbound[index:index+30],start=cursor)+timedelta(minutes=45)
        done=[o for o in rows if o['stage']=='delivered']
        past_trip(wh,'delivery',done[:3],start=today-timedelta(days=1)+timedelta(hours=9))
        failed=[o for o in rows if o['stage']=='failed' and o['in_warehouse']]
        past_trip(wh,'delivery',done[3:]+failed,finish_before=now-timedelta(minutes=30))

    # Replace trigger-created insertion events with chronological histories from the seeded stops.
    trips = {t['id']:t for t in data['trips']}
    drivers = {d['id']:d for d in data['drivers']}
    vehicles = {v['id']:v for v in data['vehicles']}
    for row in data['orders']:
        history = [('created',row['created_at'],None,None)]
        own = [s for s in data['trip_stops'] if s['order_id']==row['id']]
        for s in own:
            t=trips[s['trip_id']]
            history.append((s['kind']+'_assigned',t['planned_start'],t,None))
            if s['state']=='completed':
                history.append(('picked_up' if s['kind']=='pickup' else 'delivered',s['completed_at'],t,None))
            elif s['state']=='failed':
                history.append((s['kind']+'_failed',s['completed_at'],t,s['fail_reason']))
            elif s['state']=='en_route':
                history.append(('approaching_'+s['kind'],now-timedelta(seconds=30),t,None))
            if s['kind']=='pickup' and s['state']=='completed' and t['status']=='completed':
                history.append(('at_warehouse',t['planned_end'],t,None))
            if s['kind']=='delivery' and t['status'] in ('in_progress','completed'):
                history.append(('out_for_delivery',t['planned_start']+timedelta(seconds=1),t,None))
        snapshot = {k:row[k] for k in ('id','code','customer_ref','stage','in_warehouse','delivery_failures','pickup_name','pickup_phone',
                                     'pickup_address','customer_name','phone','address','note','goods_description','goods_type')}
        snapshot.update(booking_id=row['customer_booking_id'],warehouse=data['warehouses'][row['warehouse_id']-1]['name'])
        for source,target in [('pickup_from','pickup_from'),('pickup_to','pickup_to'),('tw_start','delivery_from'),('tw_end','delivery_to')]:
            snapshot[target]=row[source].strftime('%Y-%m-%d %H:%M')
        for phase in ('pickup','delivery'):
            matches=[s for s in own if s['kind']==phase]
            t=trips[matches[-1]['trip_id']] if matches else None
            d=drivers[t['driver_id']] if t else None
            snapshot.update({phase+'_driver':d['name'] if d else None,phase+'_driver_phone':d['phone'] if d else None,
                             phase+'_plate':vehicles[t['vehicle_id']]['plate'] if t else None,phase+'_trip_status':t['status'] if t else None})
        payload=dict(pickupName=row['pickup_name'],pickupPhone=row['pickup_phone'],pickupAddress=row['pickup_address'],
                     pickupLat=row['pickup_lat'],pickupLng=row['pickup_lng'],customerName=row['customer_name'],phone=row['phone'],
                     address=row['address'],lat=row['lat'],lng=row['lng'],weightKg=row['weight_kg'],volumeM3=row['volume_m3'],note=row['note'],goodsDescription=row['goods_description'],goodsType=row['goods_type'])
        for source,target in [('pickup_from','pickupFrom'),('pickup_to','pickupTo'),('tw_start','deliveryFrom'),('tw_end','deliveryTo')]:
            payload[target]=row[source].replace(tzinfo=None).isoformat()
        if row['customer_ref']:
            data['bookings'].append(dict(id=row['customer_booking_id'],customer_id=row['customer_ref'],request_key=uid('request/'+str(row['id'])),
                                         payload=payload,state='submitted',order_id=row['id'],order_code=row['code'],snapshot=snapshot,
                                         last_sync=now,next_attempt=now+timedelta(seconds=5),created_at=row['created_at']))
        for kind, moment, t, reason in sorted(history,key=lambda item:item[1]):
            eid=len(data['customer_tracking_events'])+1
            d=drivers[t['driver_id']] if t else None
            event=dict(id=eid,order_id=row['id'],type=kind,created_at=moment,
                       driver_name=d['name'] if d else None,driver_phone=d['phone'] if d else None,
                       plate=vehicles[t['vehicle_id']]['plate'] if t else None,reason=reason)
            data['customer_tracking_events'].append(event)
            if not row['customer_ref']:
                continue
            details=dict(event,booking_id=row['customer_booking_id']);details['created_at']=moment.isoformat()
            data['booking_events'].append(dict(source_id=eid,booking_id=row['customer_booking_id'],type=kind,occurred_at=moment,details=details))
            data['customer_notifications'].append(dict(id=eid,customer_id=row['customer_ref'],booking_id=row['customer_booking_id'],
                                                       source_event=eid,type=kind,created_at=moment,read_at=now if kind=='created' else None))
    data['now']=now
    data['dispatch_date']=dispatch_day
    return data


def sequence(table):
    return f"SELECT setval(pg_get_serial_sequence('{table}','id'),coalesce(max(id),1),count(*)>0) FROM {table};\n"


def sql_scripts(data):
    prefix="BEGIN;\nSET LOCAL timezone TO 'Asia/Ho_Chi_Minh';\n"
    operational=['warehouses','vehicles','drivers','driver_work_days','vehicle_work_days','orders','trips','trip_stops']
    logistics=prefix+"DELETE FROM dispatcher_preferences WHERE user_id<>1;\nUPDATE dispatcher_preferences SET default_warehouse_id=NULL;\n"
    logistics+="TRUNCATE order_reschedule_batches,fleet_changes,demo_history_batches,finance_requests,dispatcher_notification_reads,dispatcher_notifications,customer_tracking_events,distribution_plans,routing_events,alerts,gps_points,trip_stops,trips,orders,shipping_quotes,driver_work_days,vehicle_work_days,drivers,vehicles RESTART IDENTITY CASCADE;\nDELETE FROM warehouses;\n"
    logistics+=''.join(insert(table,data[table]) for table in operational)
    logistics+="INSERT INTO driver_service_periods(driver_id,started_on) SELECT id,joined_on FROM drivers;\n"
    logistics+="TRUNCATE customer_event_receipts,customer_tracking_events,dispatcher_notification_reads,dispatcher_notifications,driver_notifications RESTART IDENTITY;\n"
    logistics+=insert('customer_tracking_events',data['customer_tracking_events'])
    logistics+="UPDATE driver_notifications n SET created_at=e.created_at,read_at=CASE WHEN e.type LIKE '%failed' THEN NULL ELSE now() END FROM customer_tracking_events e WHERE n.source_key='order-event:'||e.id;\n"
    logistics+="TRUNCATE dispatcher_notification_reads,dispatcher_notifications RESTART IDENTITY;\n"
    notices=[('ready','info','Hàng tại kho chờ tối ưu phân phối','Mỗi kho có 50 đơn đã về kho. Thuật toán tính gom chuyến theo vị trí, chi phí và ràng buộc; điều phối duyệt trước khi xuất giao.','/distribution',None),
             ('pickup','info','Đơn lấy mới đang chờ duyệt phân phối','36 đơn lấy rải trên thành phố, chờ chọn xe và tài xế.','/distribution?phase=pickup',None),
             ('failure','warning','Có đơn cần xử lý lại','Có đơn lấy thất bại và đơn giao thất bại đã hoàn kho.','/orders?stage=failed',None),
             ('schedule','warning','Có tài xế chưa đăng ký lịch','TX-004, TX-009 và TX-014 chưa có lịch; TX-003, TX-008 và TX-013 nghỉ hôm nay.','/drivers',None)]
    logistics+=insert('dispatcher_notifications',[dict(id=i,source_key='demo:'+key,type='demo',level=level,title=title,message=message,
                       href=href,warehouse_id=wh,created_at=data['now']-timedelta(minutes=10-i))
                       for i,(key,level,title,message,href,wh) in enumerate(notices,1)])
    logistics+="UPDATE routing_state SET revision=revision+1,dirty="+literal(data['auto_route'])+",lease_owner=NULL,lease_until=NULL,last_error=NULL,retry_after=NULL,last_success=NULL,last_stats='{}'::jsonb WHERE id=1;\n"
    logistics+=''.join(sequence(t) for t in ['warehouses','vehicles','drivers','orders','trips','trip_stops','customer_tracking_events','dispatcher_notifications'])
    logistics+="INSERT INTO vehicle_maintenance(vehicle_id,reason,reported_by_driver_id) SELECT v.id,'Bảo dưỡng theo kế hoạch kiểm tra xe',(SELECT d.id FROM drivers d WHERE d.vehicle_id=v.id ORDER BY d.id LIMIT 1) FROM vehicles v WHERE v.status='maintenance';\nCOMMIT;\n"
    auth=prefix+'TRUNCATE auth_sessions,app_users RESTART IDENTITY CASCADE;\n'+insert('app_users',data['app_users'])+sequence('app_users')+'COMMIT;\n'
    customer=prefix+'TRUNCATE customer_sessions,customer_notifications,booking_events,bookings,customers RESTART IDENTITY CASCADE;\n'
    customer+=''.join(insert(t,data[t]) for t in ['customers','bookings','booking_events','customer_notifications'])
    customer+=sequence('customer_notifications')+'COMMIT;\n'
    return {'db':logistics,'auth-db':auth,'customer-db':customer}


def overview(data):
    busy = {t['vehicle_id'] for t in data['trips'] if t['status']!='completed'}
    unavailable = {v['vehicle_id'] for v in data['vehicle_work_days'] if not v['available']}
    today = str(data['dispatch_date'])
    schedule = {d['driver_id']:d['working'] for d in data['driver_work_days'] if d['work_date']==today}
    return dict(generated_at=data['now'].isoformat(),random_seed=data['random_seed'],auto_route=data['auto_route'],dispatch_date=str(data['dispatch_date']),shift='08:00–20:00',
                unique_address_points=len({o['pickup_address'] for o in data['orders']}|{o['address'] for o in data['orders']}),
                unique_pickup_addresses=len({o['pickup_address'] for o in data['orders']}),
                unique_delivery_addresses=len({o['address'] for o in data['orders']}),
                unique_streets=len({o['address'].split(',')[0].split(' ',1)[-1] for o in data['orders']}),
                location_source=data['location_catalog'],warehouses=len(data['warehouses']),vehicles=dict(Counter(v['status'] for v in data['vehicles'])),
                idle_usable_vehicles=sum(v['status']!='maintenance' and v['id'] not in busy|unavailable for v in data['vehicles']),
                drivers_today=dict(working=sum(v is True for v in schedule.values()),resting=sum(v is False for v in schedule.values()),
                                   unregistered=len(data['drivers'])-len(schedule)),
                drivers=len(data['drivers']),orders=len(data['orders']),stages=dict(Counter(o['stage'] for o in data['orders'])),
                trips=dict(Counter(t['status'] for t in data['trips'])),customers=len(data['customers']),customer_bookings=len(data['bookings']))
