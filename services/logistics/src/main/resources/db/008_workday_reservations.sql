-- Physical states stay on vehicles/drivers; dispatch states derive from durable trips.
ALTER TABLE drivers ALTER COLUMN shift_start SET DEFAULT '08:00';
ALTER TABLE drivers ALTER COLUMN shift_end SET DEFAULT '20:00';
UPDATE drivers SET shift_start='08:00',shift_end='20:00';
UPDATE driver_work_days SET shift_start='08:00',shift_end='20:00';
ALTER TABLE driver_work_days ADD CONSTRAINT driver_work_hours CHECK(shift_start='08:00'::time AND shift_end='20:00'::time);

CREATE FUNCTION gk_dispatch_date(at_time timestamptz DEFAULT now()) RETURNS date LANGUAGE sql IMMUTABLE AS $$
 SELECT (at_time AT TIME ZONE 'Asia/Ho_Chi_Minh')::date + CASE WHEN (at_time AT TIME ZONE 'Asia/Ho_Chi_Minh')::time >= '20:00'::time THEN 1 ELSE 0 END
$$;
CREATE FUNCTION gk_vehicle_state(vid int,work_day date) RETURNS text LANGUAGE sql STABLE AS $$
 SELECT CASE WHEN v.status='maintenance' THEN 'maintenance'
  WHEN EXISTS(SELECT 1 FROM trips t WHERE t.vehicle_id=v.id AND t.status='in_progress') THEN 'active'
  WHEN EXISTS(SELECT 1 FROM trips t WHERE t.vehicle_id=v.id AND t.status='planned' AND t.planned_start::date<=work_day) THEN 'reserved'
  WHEN EXISTS(SELECT 1 FROM vehicle_work_days a WHERE a.vehicle_id=v.id AND a.work_date=work_day AND NOT a.available) THEN 'unavailable'
  ELSE 'available' END FROM vehicles v WHERE v.id=vid
$$;
CREATE FUNCTION gk_driver_state(did int,work_day date) RETURNS text LANGUAGE sql STABLE AS $$
 SELECT CASE WHEN EXISTS(SELECT 1 FROM trips t WHERE t.driver_id=d.id AND t.status='in_progress') THEN 'on_duty'
  WHEN EXISTS(SELECT 1 FROM trips t WHERE t.driver_id=d.id AND t.status='planned' AND t.planned_start::date<=work_day) THEN 'reserved'
  WHEN NOT EXISTS(SELECT 1 FROM driver_work_days s WHERE s.driver_id=d.id AND s.work_date=work_day) THEN 'unregistered'
  WHEN EXISTS(SELECT 1 FROM driver_work_days s WHERE s.driver_id=d.id AND s.work_date=work_day AND s.working) THEN 'available'
  ELSE 'off' END FROM drivers d WHERE d.id=did
$$;
CREATE INDEX idx_trips_pending_driver ON trips(driver_id,planned_start) WHERE status<>'completed';
CREATE INDEX idx_trips_pending_vehicle ON trips(vehicle_id,planned_start) WHERE status<>'completed';
CREATE VIEW vehicle_dispatch_status AS SELECT v.*,gk_dispatch_date() AS work_date,gk_vehicle_state(v.id,gk_dispatch_date()) AS dispatch_status FROM vehicles v;
CREATE VIEW driver_dispatch_status AS SELECT d.*,gk_dispatch_date() AS work_date,gk_driver_state(d.id,gk_dispatch_date()) AS dispatch_status FROM drivers d;
UPDATE routing_state SET revision=revision+1,dirty=true,retry_after=NULL WHERE id=1;
