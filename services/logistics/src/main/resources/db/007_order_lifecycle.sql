CREATE TABLE customer_event_receipts (
 event_id bigint PRIMARY KEY REFERENCES customer_tracking_events(id) ON DELETE CASCADE,
 saved_at timestamptz NOT NULL DEFAULT now(), read_at timestamptz
);
-- Durable in-app notifications and a shared audit trail for ALL orders.
CREATE TABLE driver_notifications (
 id bigserial PRIMARY KEY, driver_id int NOT NULL REFERENCES drivers(id) ON DELETE CASCADE,
 source_key text NOT NULL UNIQUE, order_id int REFERENCES orders(id) ON DELETE CASCADE,
 type text NOT NULL, title text NOT NULL, message text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), read_at timestamptz
);
CREATE INDEX idx_driver_notifications_owner ON driver_notifications(driver_id,id DESC);

CREATE OR REPLACE FUNCTION track_customer_order() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE event_type text;
BEGIN
 IF TG_OP='INSERT' THEN
   INSERT INTO customer_tracking_events(order_id,type,created_at) VALUES(NEW.id,'created',NEW.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh');
 ELSIF OLD.stage IS DISTINCT FROM NEW.stage THEN
   event_type:=CASE WHEN NEW.stage='at_warehouse' AND OLD.stage='picked_up' THEN 'at_warehouse'
     WHEN NEW.stage='out_for_delivery' THEN 'out_for_delivery'
     WHEN NEW.stage IN ('awaiting_pickup','at_warehouse') THEN 'waiting_dispatch' END;
   IF event_type IS NOT NULL THEN
    INSERT INTO customer_tracking_events(order_id,type) VALUES(NEW.id,event_type);
   END IF;
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER customer_order_tracking ON orders;
CREATE TRIGGER customer_order_tracking AFTER INSERT OR UPDATE ON orders FOR EACH ROW EXECUTE FUNCTION track_customer_order();

CREATE OR REPLACE FUNCTION track_customer_stop() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE event_type text; trip_record record;
BEGIN
 IF NEW.kind='warehouse' THEN RETURN NEW; END IF;
 IF TG_OP='INSERT' THEN
  event_type:=CASE NEW.kind WHEN 'pickup' THEN 'pickup_assigned' ELSE 'delivery_assigned' END;
 ELSIF OLD.state IS DISTINCT FROM NEW.state THEN
  event_type:=CASE NEW.state
   WHEN 'en_route' THEN CASE NEW.kind WHEN 'pickup' THEN 'approaching_pickup' ELSE 'approaching_delivery' END
   WHEN 'completed' THEN CASE NEW.kind WHEN 'pickup' THEN 'picked_up' ELSE 'delivered' END
   WHEN 'failed' THEN CASE NEW.kind WHEN 'pickup' THEN 'pickup_failed' ELSE 'delivery_failed' END END;
 END IF;
 IF event_type IS NOT NULL THEN
  SELECT d.name,d.phone,v.plate INTO trip_record FROM trips t LEFT JOIN drivers d ON d.id=t.driver_id JOIN vehicles v ON v.id=t.vehicle_id WHERE t.id=NEW.trip_id;
  INSERT INTO customer_tracking_events(order_id,type,driver_name,driver_phone,plate,reason)
   VALUES(NEW.order_id,event_type,trip_record.name,trip_record.phone,trip_record.plate,NEW.fail_reason);
 END IF;
 RETURN NEW;
END $$;

-- Existing staff-created orders have no history in v5. Backfill only actual recorded times.
INSERT INTO customer_tracking_events(order_id,type,created_at)
 SELECT o.id,'created',o.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh' FROM orders o
 WHERE NOT EXISTS(SELECT 1 FROM customer_tracking_events e WHERE e.order_id=o.id AND e.type='created');
INSERT INTO customer_tracking_events(order_id,type,created_at,driver_name,driver_phone,plate,reason)
 SELECT s.order_id,CASE WHEN s.state='failed' THEN s.kind||'_failed' WHEN s.kind='pickup' THEN 'picked_up' ELSE 'delivered' END,
 s.completed_at AT TIME ZONE 'Asia/Ho_Chi_Minh',d.name,d.phone,v.plate,s.fail_reason
 FROM trip_stops s JOIN trips t ON t.id=s.trip_id JOIN vehicles v ON v.id=t.vehicle_id LEFT JOIN drivers d ON d.id=t.driver_id
 WHERE s.kind<>'warehouse' AND s.completed_at IS NOT NULL AND s.state IN ('completed','failed')
 AND NOT EXISTS(SELECT 1 FROM customer_tracking_events e WHERE e.order_id=s.order_id AND e.type=CASE WHEN s.state='failed' THEN s.kind||'_failed' WHEN s.kind='pickup' THEN 'picked_up' ELSE 'delivered' END);

-- Single transactional fan-out: persistence succeeds together with the business operation.
CREATE FUNCTION notify_order_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o record; assignment record; heading text; tone text;
BEGIN
 SELECT * INTO o FROM orders WHERE id=NEW.order_id;
 heading:=CASE NEW.type WHEN 'created' THEN 'GK đã tiếp nhận đơn'
 WHEN 'pickup_assigned' THEN 'Đã phân chuyến lấy hàng' WHEN 'delivery_assigned' THEN 'Đã phân chuyến giao hàng'
 WHEN 'pickup_reassigned' THEN 'Đã đổi phân công lấy hàng' WHEN 'delivery_reassigned' THEN 'Đã đổi phân công giao hàng'
 WHEN 'assignment_updated' THEN 'Thông tin tài xế/xe đã cập nhật'
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
 SELECT t.driver_id,t.code,v.plate INTO assignment FROM trip_stops s JOIN trips t ON t.id=s.trip_id JOIN vehicles v ON v.id=t.vehicle_id
 WHERE s.order_id=o.id AND s.kind=CASE WHEN NEW.type IN ('at_warehouse','picked_up','pickup_assigned','pickup_reassigned','approaching_pickup','pickup_failed') OR (NEW.type='assignment_updated' AND o.stage IN ('pickup_assigned','picked_up')) THEN 'pickup' ELSE 'delivery' END
 ORDER BY s.id DESC LIMIT 1;
 IF assignment.driver_id IS NOT NULL THEN
  INSERT INTO driver_notifications(driver_id,source_key,order_id,type,title,message)
   VALUES(assignment.driver_id,'order-event:'||NEW.id,o.id,NEW.type,heading,o.code||' · '||assignment.code||' · '||assignment.plate);
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER order_event_notifications AFTER INSERT ON customer_tracking_events FOR EACH ROW EXECUTE FUNCTION notify_order_event();

CREATE FUNCTION track_trip_assignment() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s record; d record; plate_number text;
BEGIN
 IF OLD.driver_id IS NOT DISTINCT FROM NEW.driver_id AND OLD.vehicle_id=NEW.vehicle_id THEN RETURN NEW; END IF;
 SELECT name,phone INTO d FROM drivers WHERE id=NEW.driver_id;
 SELECT plate INTO plate_number FROM vehicles WHERE id=NEW.vehicle_id;
 IF OLD.driver_id IS NOT NULL AND OLD.driver_id IS DISTINCT FROM NEW.driver_id THEN
  INSERT INTO driver_notifications(driver_id,source_key,type,title,message)
   VALUES(OLD.driver_id,'trip-reassigned:'||NEW.id||':'||nextval('driver_notifications_id_seq'),'trip_reassigned','Chuyến đã chuyển tài xế',NEW.code||' không còn được phân cho bạn.');
 END IF;
 FOR s IN SELECT * FROM trip_stops WHERE trip_id=NEW.id AND kind<>'warehouse' AND state IN ('pending','en_route') LOOP
  INSERT INTO customer_tracking_events(order_id,type,driver_name,driver_phone,plate)
   VALUES(s.order_id,s.kind||'_reassigned',d.name,d.phone,plate_number);
 END LOOP;
 RETURN NEW;
END $$;
CREATE TRIGGER trip_assignment_tracking AFTER UPDATE OF driver_id,vehicle_id ON trips FOR EACH ROW EXECUTE FUNCTION track_trip_assignment();

CREATE FUNCTION notify_driver_trip() RETURNS trigger LANGUAGE plpgsql AS $$
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
   VALUES(NEW.driver_id,'trip-completed:'||NEW.id,'trip_completed','Đã hoàn tất chuyến',NEW.code||' · Đã xác nhận về kho. Bạn có thể nhận chuyến tiếp theo.') ON CONFLICT(source_key) DO NOTHING;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER driver_trip_completed AFTER UPDATE OF status ON trips FOR EACH ROW EXECUTE FUNCTION notify_driver_trip();
CREATE TRIGGER driver_trip_released BEFORE DELETE ON trips FOR EACH ROW EXECUTE FUNCTION notify_driver_trip();

-- Profile/plate changes update customer snapshots on the next sync AND produce an audit event.
CREATE FUNCTION track_assignment_profile() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s record;
BEGIN
 IF TG_TABLE_NAME='drivers' THEN
  IF OLD.name IS NOT DISTINCT FROM NEW.name AND OLD.phone IS NOT DISTINCT FROM NEW.phone THEN RETURN NEW; END IF;
 ELSE
  IF OLD.plate IS NOT DISTINCT FROM NEW.plate THEN RETURN NEW; END IF;
 END IF;
 FOR s IN SELECT DISTINCT o.id,d.name,d.phone,v.plate FROM orders o JOIN trip_stops st ON st.order_id=o.id
  JOIN trips t ON t.id=st.trip_id LEFT JOIN drivers d ON d.id=t.driver_id JOIN vehicles v ON v.id=t.vehicle_id
  WHERE t.status<>'completed' AND st.state IN ('pending','en_route')
   AND ((TG_TABLE_NAME='drivers' AND t.driver_id=NEW.id) OR (TG_TABLE_NAME='vehicles' AND t.vehicle_id=NEW.id)) LOOP
  INSERT INTO customer_tracking_events(order_id,type,driver_name,driver_phone,plate) VALUES(s.id,'assignment_updated',s.name,s.phone,s.plate);
 END LOOP;
 RETURN NEW;
END $$;
CREATE TRIGGER driver_profile_tracking AFTER UPDATE OF name,phone ON drivers FOR EACH ROW EXECUTE FUNCTION track_assignment_profile();
CREATE TRIGGER vehicle_profile_tracking AFTER UPDATE OF plate ON vehicles FOR EACH ROW EXECUTE FUNCTION track_assignment_profile();

INSERT INTO driver_notifications(driver_id,source_key,type,title,message)
 SELECT t.driver_id,'initial-trip:'||t.id,'trip_assigned','Bạn có chuyến được phân',t.code||' · '||v.plate
 FROM trips t JOIN vehicles v ON v.id=t.vehicle_id WHERE t.status<>'completed' AND t.driver_id IS NOT NULL;
