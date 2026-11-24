ALTER TABLE distribution_plans DROP CONSTRAINT distribution_plans_status_check;
ALTER TABLE distribution_plans ADD CONSTRAINT distribution_plans_status_check CHECK(status IN ('draft','confirmed','released'));
ALTER TABLE trips ADD COLUMN distribution_plan_id bigint REFERENCES distribution_plans(id);
CREATE TABLE vehicle_maintenance (
 id bigserial PRIMARY KEY,vehicle_id int NOT NULL REFERENCES vehicles(id),
 state text NOT NULL DEFAULT 'open' CHECK(state IN ('awaiting_return','open','completed')),
 reason text NOT NULL CHECK(length(trim(reason)) BETWEEN 1 AND 1000),expected_ready_at timestamptz,
 reported_by_driver_id int REFERENCES drivers(id),updated_by_driver_id int REFERENCES drivers(id),updated_by_user_id bigint,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 completed_at timestamptz,completion_note text,
 CHECK(state<>'completed' OR (completed_at IS NOT NULL AND length(trim(completion_note)) BETWEEN 1 AND 1000))
);
CREATE UNIQUE INDEX idx_maintenance_open_vehicle ON vehicle_maintenance(vehicle_id) WHERE state<>'completed';
CREATE TABLE vehicle_maintenance_events (
 id bigserial PRIMARY KEY,maintenance_id bigint NOT NULL REFERENCES vehicle_maintenance(id) ON DELETE CASCADE,
 action text NOT NULL,driver_id int REFERENCES drivers(id),user_id bigint,note text,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION notify_vehicle_maintenance() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v record;kind text;heading text;source text;description text;
BEGIN
 SELECT * INTO v FROM vehicles WHERE id=NEW.vehicle_id;
 IF TG_OP='INSERT' THEN kind:='maintenance_reported';heading:=CASE NEW.state WHEN 'awaiting_return' THEN 'Xe đã báo sự cố, chờ bàn giao hàng' ELSE 'Xe đã chuyển sang bảo dưỡng' END;
 ELSIF OLD.state IS DISTINCT FROM NEW.state THEN kind:=CASE NEW.state WHEN 'completed' THEN 'maintenance_completed' ELSE 'maintenance_started' END;heading:=CASE NEW.state WHEN 'completed' THEN 'Xe đã hoàn tất bảo dưỡng' ELSE 'Xe đã về kho và bắt đầu bảo dưỡng' END;
 ELSIF OLD.expected_ready_at IS DISTINCT FROM NEW.expected_ready_at THEN kind:='maintenance_estimate';heading:='Đã cập nhật thời gian dự kiến bảo dưỡng xong';
 ELSE RETURN NEW; END IF;
 description:=v.code||' · '||v.plate||' · '||CASE WHEN NEW.state='completed' THEN NEW.completion_note ELSE NEW.reason END;
 IF NEW.state<>'completed' AND NEW.expected_ready_at IS NOT NULL THEN description:=description||' · Hẹn xong '||to_char(NEW.expected_ready_at AT TIME ZONE 'Asia/Ho_Chi_Minh','DD/MM HH24:MI'); END IF;
 source:='maintenance:'||NEW.id||':'||kind||':'||nextval('dispatcher_notifications_id_seq');
 INSERT INTO dispatcher_notifications(source_key,type,level,title,message,href,warehouse_id)
 VALUES(source,kind,CASE NEW.state WHEN 'completed' THEN 'success' ELSE 'warning' END,heading,description,'/maintenance',v.warehouse_id);
 INSERT INTO driver_notifications(driver_id,source_key,type,title,message)
 SELECT d.id,source||':driver:'||d.id,kind,heading,description FROM drivers d
 WHERE d.vehicle_id=NEW.vehicle_id OR d.id=NEW.reported_by_driver_id OR EXISTS(SELECT 1 FROM trips t WHERE t.driver_id=d.id AND t.vehicle_id=NEW.vehicle_id AND t.status<>'completed');
 INSERT INTO vehicle_maintenance_events(maintenance_id,action,driver_id,user_id,note)
 VALUES(NEW.id,kind,NEW.updated_by_driver_id,NEW.updated_by_user_id,description);
 RETURN NEW;
END $$;
CREATE TRIGGER maintenance_notifications AFTER INSERT OR UPDATE OF state,expected_ready_at ON vehicle_maintenance FOR EACH ROW EXECUTE FUNCTION notify_vehicle_maintenance();
CREATE FUNCTION start_maintenance_after_return() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status='completed' AND OLD.status IS DISTINCT FROM NEW.status AND NOT EXISTS(SELECT 1 FROM trips t WHERE t.vehicle_id=NEW.vehicle_id AND t.status='in_progress') THEN
  UPDATE vehicle_maintenance SET state='open',updated_at=now() WHERE vehicle_id=NEW.vehicle_id AND state='awaiting_return';
  IF EXISTS(SELECT 1 FROM vehicle_maintenance m WHERE m.vehicle_id=NEW.vehicle_id AND m.state<>'completed') THEN UPDATE vehicles SET status='maintenance' WHERE id=NEW.vehicle_id; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER maintenance_after_return AFTER UPDATE OF status ON trips FOR EACH ROW EXECUTE FUNCTION start_maintenance_after_return();
CREATE OR REPLACE FUNCTION gk_vehicle_state(vid int,work_day date) RETURNS text LANGUAGE sql STABLE AS $$
 SELECT CASE
  WHEN EXISTS(SELECT 1 FROM trips t WHERE t.vehicle_id=v.id AND t.status='in_progress') THEN 'active'
  WHEN v.status='maintenance' OR EXISTS(SELECT 1 FROM vehicle_maintenance m WHERE m.vehicle_id=v.id AND m.state<>'completed') THEN 'maintenance'
  WHEN EXISTS(SELECT 1 FROM trips t WHERE t.vehicle_id=v.id AND t.status='planned' AND t.planned_start::date<=work_day) THEN 'reserved'
  WHEN EXISTS(SELECT 1 FROM vehicle_work_days a WHERE a.vehicle_id=v.id AND a.work_date=work_day AND NOT a.available) THEN 'unavailable'
  ELSE 'available' END FROM vehicles v WHERE v.id=vid
$$;
INSERT INTO vehicle_maintenance(vehicle_id,state,reason,reported_by_driver_id)
 SELECT v.id,CASE WHEN EXISTS(SELECT 1 FROM trips t WHERE t.vehicle_id=v.id AND t.status='in_progress') THEN 'awaiting_return' ELSE 'open' END,
 'Bảo dưỡng theo hồ sơ xe hiện có',(SELECT d.id FROM drivers d WHERE d.vehicle_id=v.id ORDER BY d.id LIMIT 1)
 FROM vehicles v WHERE v.status='maintenance';
UPDATE routing_state SET revision=revision+1,dirty=true,retry_after=NULL WHERE id=1;

CREATE OR REPLACE FUNCTION notify_driver_trip() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  IF OLD.driver_id IS NOT NULL AND OLD.status='planned' THEN
   INSERT INTO driver_notifications(driver_id,source_key,type,title,message)
    VALUES(OLD.driver_id,'trip-released:'||OLD.id||':'||nextval('driver_notifications_id_seq'),'trip_released','Đã thu hồi chuyến chưa xuất phát',OLD.code||' đã được điều phối thu hồi.');
  END IF;
  RETURN OLD;
 END IF;
 IF OLD.status IS DISTINCT FROM NEW.status AND NEW.status='completed' AND NEW.driver_id IS NOT NULL THEN
  INSERT INTO driver_notifications(driver_id,source_key,type,title,message)
   VALUES(NEW.driver_id,'trip-completed:'||NEW.id,'trip_completed','Đã hoàn tất chuyến',NEW.code||' · Đã xác nhận về kho. Xem lịch và tình trạng xe trước khi nhận chuyến tiếp theo.') ON CONFLICT(source_key) DO NOTHING;
 END IF;
 RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION notify_order_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o record; assignment record; heading text; tone text;
BEGIN
 SELECT * INTO o FROM orders WHERE id=NEW.order_id;
 heading:=CASE NEW.type WHEN 'created' THEN 'GK đã tiếp nhận đơn'
 WHEN 'pickup_assigned' THEN 'Đã phân chuyến lấy hàng' WHEN 'delivery_assigned' THEN 'Đã phân chuyến giao hàng'
 WHEN 'pickup_reassigned' THEN 'Đã đổi phân công lấy hàng' WHEN 'delivery_reassigned' THEN 'Đã đổi phân công giao hàng'
 WHEN 'assignment_updated' THEN 'Thông tin tài xế/xe đã cập nhật' WHEN 'schedule_updated' THEN 'Đã cập nhật lịch hẹn lấy/giao'
 WHEN 'approaching_pickup' THEN 'Tài xế đang đến lấy hàng' WHEN 'picked_up' THEN 'Đã lấy hàng thành công'
 WHEN 'at_warehouse' THEN 'Hàng đã về kho' WHEN 'out_for_delivery' THEN 'Hàng đã xuất kho, đang giao'
 WHEN 'approaching_delivery' THEN 'Tài xế đang đến người nhận' WHEN 'delivered' THEN 'Đã giao hàng thành công'
 WHEN 'pickup_failed' THEN 'Chưa lấy được hàng' WHEN 'delivery_failed' THEN 'Chưa giao được hàng'
 ELSE 'Đơn đang chờ phân công lại' END;
 tone:=CASE WHEN NEW.type LIKE '%failed' THEN 'warning' WHEN NEW.type IN ('picked_up','at_warehouse','delivered') THEN 'success' ELSE 'info' END;
 IF NEW.type<>'created' THEN
  INSERT INTO dispatcher_notifications(source_key,type,level,title,message,href,warehouse_id)
   VALUES('order-event:'||NEW.id,NEW.type,tone,heading,o.code||' · '||coalesce(NEW.driver_name,o.customer_name),'/orders/'||o.id,o.warehouse_id);
 END IF;
 SELECT t.driver_id,t.code,t.planned_start,v.plate INTO assignment FROM trip_stops s JOIN trips t ON t.id=s.trip_id JOIN vehicles v ON v.id=t.vehicle_id
 WHERE s.order_id=o.id AND s.kind=CASE WHEN NEW.type IN ('at_warehouse','picked_up','pickup_assigned','pickup_reassigned','approaching_pickup','pickup_failed') OR (NEW.type='assignment_updated' AND o.stage IN ('pickup_assigned','picked_up')) THEN 'pickup' ELSE 'delivery' END
 ORDER BY s.id DESC LIMIT 1;
 IF assignment.driver_id IS NOT NULL AND NEW.type<>'schedule_updated' THEN
  INSERT INTO driver_notifications(driver_id,source_key,order_id,type,title,message)
   VALUES(assignment.driver_id,'order-event:'||NEW.id,o.id,NEW.type,heading,o.code||' · '||assignment.code||' · '||assignment.plate||' · Ngày '||to_char(assignment.planned_start,'DD/MM/YYYY'));
 END IF;
 RETURN NEW;
END $$;
