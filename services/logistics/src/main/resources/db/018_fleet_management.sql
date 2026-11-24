ALTER TABLE warehouses ADD COLUMN archived_at timestamptz,ADD COLUMN address text NOT NULL DEFAULT '';
ALTER TABLE vehicles ADD COLUMN archived_at timestamptz;
ALTER TABLE drivers ADD COLUMN archived_at timestamptz,ADD COLUMN joined_on date NOT NULL DEFAULT CURRENT_DATE;
UPDATE drivers SET joined_on='2026-09-01';
UPDATE warehouses SET address=name;
CREATE UNIQUE INDEX active_driver_phone ON drivers(phone) WHERE archived_at IS NULL AND phone IS NOT NULL;
CREATE UNIQUE INDEX active_driver_vehicle ON drivers(vehicle_id) WHERE archived_at IS NULL AND vehicle_id IS NOT NULL;
CREATE TABLE fleet_changes(id bigserial PRIMARY KEY,entity text NOT NULL,entity_id int NOT NULL,action text NOT NULL,before_data jsonb,after_data jsonb,created_by bigint NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
CREATE TRIGGER fleet_changes_immutable BEFORE UPDATE OR DELETE ON fleet_changes FOR EACH ROW EXECUTE FUNCTION immutable_finance_entry();
CREATE OR REPLACE FUNCTION gk_vehicle_state(vid int,work_day date) RETURNS text LANGUAGE sql STABLE AS $$
 SELECT CASE WHEN v.archived_at IS NOT NULL OR EXISTS(SELECT 1 FROM warehouses w WHERE w.id=v.warehouse_id AND w.archived_at IS NOT NULL) THEN 'unavailable' WHEN v.status='maintenance' THEN 'maintenance'
  WHEN EXISTS(SELECT 1 FROM trips t WHERE t.vehicle_id=v.id AND t.status='in_progress') THEN 'active'
  WHEN EXISTS(SELECT 1 FROM trips t WHERE t.vehicle_id=v.id AND t.status='planned' AND t.planned_start::date<=work_day) THEN 'reserved'
  WHEN EXISTS(SELECT 1 FROM vehicle_work_days a WHERE a.vehicle_id=v.id AND a.work_date=work_day AND NOT a.available) THEN 'unavailable'
  ELSE 'available' END FROM vehicles v WHERE v.id=vid
$$;
CREATE OR REPLACE FUNCTION gk_driver_state(did int,work_day date) RETURNS text LANGUAGE sql STABLE AS $$
 SELECT CASE WHEN d.archived_at IS NOT NULL OR d.joined_on>work_day OR EXISTS(SELECT 1 FROM warehouses w WHERE w.id=d.warehouse_id AND w.archived_at IS NOT NULL) THEN 'off' WHEN EXISTS(SELECT 1 FROM trips t WHERE t.driver_id=d.id AND t.status='in_progress') THEN 'on_duty'
  WHEN EXISTS(SELECT 1 FROM trips t WHERE t.driver_id=d.id AND t.status='planned' AND t.planned_start::date<=work_day) THEN 'reserved'
  WHEN NOT EXISTS(SELECT 1 FROM driver_work_days s WHERE s.driver_id=d.id AND s.work_date=work_day) THEN 'unregistered'
  WHEN EXISTS(SELECT 1 FROM driver_work_days s WHERE s.driver_id=d.id AND s.work_date=work_day AND s.working) THEN 'available'
  ELSE 'off' END FROM drivers d WHERE d.id=did
$$;

CREATE TABLE driver_service_periods(id bigserial PRIMARY KEY,driver_id int NOT NULL REFERENCES drivers(id),started_on date NOT NULL,ended_on date,CHECK(ended_on IS NULL OR ended_on>=started_on));
INSERT INTO driver_service_periods(driver_id,started_on) SELECT id,joined_on FROM drivers;
CREATE INDEX driver_service_dates ON driver_service_periods(driver_id,started_on);
