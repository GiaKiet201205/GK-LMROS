-- Separate reviewed pickup, delivery and seller-return trips. Existing data is retained.
ALTER TABLE orders ADD COLUMN delivery_failures int NOT NULL DEFAULT 0 CHECK(delivery_failures BETWEEN 0 AND 3);
ALTER TABLE orders ADD COLUMN delivery_cancelled_at timestamptz;
ALTER TABLE orders ADD COLUMN return_from timestamp;
ALTER TABLE orders ADD COLUMN return_to timestamp;
ALTER TABLE orders DROP CONSTRAINT orders_stage_check;
ALTER TABLE orders ADD CONSTRAINT orders_stage_check CHECK(stage IN ('awaiting_pickup','pickup_assigned','picking_up','picked_up','at_warehouse','delivery_assigned','out_for_delivery','delivered','failed','return_pending','return_assigned','returning','return_failed','returned'));
ALTER TABLE trips DROP CONSTRAINT trips_phase_check;
ALTER TABLE trips ADD CONSTRAINT trips_phase_check CHECK(phase IN ('pickup','delivery','return'));
ALTER TABLE trip_stops DROP CONSTRAINT trip_stops_kind_check;
ALTER TABLE trip_stops ADD CONSTRAINT trip_stops_kind_check CHECK(kind IN ('pickup','delivery','return','warehouse'));
UPDATE orders o SET delivery_failures=least(3,(SELECT count(*) FROM trip_stops s WHERE s.order_id=o.id AND s.kind='delivery' AND s.state='failed'));

CREATE FUNCTION guard_delivery_retry() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.delivery_cancelled_at IS NOT NULL AND NEW.stage IN ('awaiting_pickup','pickup_assigned','picking_up','picked_up','at_warehouse','delivery_assigned','out_for_delivery','delivered') THEN
  RAISE EXCEPTION 'Đơn đã hủy giao, chỉ được xử lý hoàn hàng';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_delivery_retry BEFORE UPDATE ON orders FOR EACH ROW EXECUTE FUNCTION guard_delivery_retry();

CREATE FUNCTION count_delivery_failure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.kind='delivery' AND NEW.state='failed' AND OLD.state IS DISTINCT FROM NEW.state THEN
  UPDATE orders SET delivery_failures=least(3,delivery_failures+1) WHERE id=NEW.order_id;
  UPDATE orders SET stage='return_pending',status='failed',delivery_cancelled_at=coalesce(delivery_cancelled_at,now())
   WHERE id=NEW.order_id AND delivery_failures>=3;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER zz_count_delivery_failure AFTER UPDATE OF state ON trip_stops FOR EACH ROW EXECUTE FUNCTION count_delivery_failure();
CREATE OR REPLACE FUNCTION track_customer_order() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE event_type text;
BEGIN
 IF TG_OP='INSERT' THEN
   INSERT INTO customer_tracking_events(order_id,type,created_at) VALUES(NEW.id,'created',NEW.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh');
 ELSIF OLD.stage IS DISTINCT FROM NEW.stage THEN
   event_type:=CASE WHEN NEW.stage='at_warehouse' AND OLD.stage='picked_up' THEN 'at_warehouse'
     WHEN NEW.stage='out_for_delivery' THEN 'out_for_delivery'
     WHEN NEW.stage='picking_up' THEN 'picking_up'
     WHEN NEW.stage='return_pending' AND OLD.stage='return_failed' THEN 'return_at_warehouse'
     WHEN NEW.stage='return_pending' AND OLD.delivery_cancelled_at IS NULL AND NEW.delivery_cancelled_at IS NOT NULL THEN 'delivery_cancelled'
     WHEN NEW.stage='return_pending' THEN 'waiting_return_dispatch'
     WHEN NEW.stage='returning' THEN 'returning'
     WHEN NEW.stage IN ('awaiting_pickup','at_warehouse') THEN 'waiting_dispatch' END;
   IF event_type IS NOT NULL THEN
    INSERT INTO customer_tracking_events(order_id,type) VALUES(NEW.id,event_type);
   END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION track_customer_stop() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE event_type text; trip_record record;
BEGIN
 IF NEW.kind='warehouse' THEN RETURN NEW; END IF;
 IF TG_OP='INSERT' THEN
  event_type:=NEW.kind||'_assigned';
 ELSIF OLD.state IS DISTINCT FROM NEW.state THEN
  event_type:=CASE NEW.state
   WHEN 'en_route' THEN CASE NEW.kind WHEN 'pickup' THEN 'approaching_pickup' WHEN 'return' THEN 'approaching_return' ELSE 'approaching_delivery' END
   WHEN 'completed' THEN CASE NEW.kind WHEN 'pickup' THEN 'picked_up' WHEN 'return' THEN 'returned' ELSE 'delivered' END
   WHEN 'failed' THEN NEW.kind||'_failed' END;
 END IF;
 IF event_type IS NOT NULL THEN
  SELECT d.name,d.phone,v.plate INTO trip_record FROM trips t LEFT JOIN drivers d ON d.id=t.driver_id JOIN vehicles v ON v.id=t.vehicle_id WHERE t.id=NEW.trip_id;
  INSERT INTO customer_tracking_events(order_id,type,driver_name,driver_phone,plate,reason)
   VALUES(NEW.order_id,event_type,trip_record.name,trip_record.phone,trip_record.plate,NEW.fail_reason);
 END IF;
 RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION notify_order_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o record; assignment record; heading text; tone text;
BEGIN
 SELECT * INTO o FROM orders WHERE id=NEW.order_id;
 heading:=CASE NEW.type WHEN 'picking_up' THEN 'Tài xế đã xuất phát lấy hàng'
 WHEN 'delivery_cancelled' THEN 'Đã hủy giao sau 3 lần thất bại · chờ hoàn hàng'
 WHEN 'waiting_return_dispatch' THEN 'Đang chờ phân chuyến hoàn lại'
 WHEN 'return_assigned' THEN 'Đã phân chuyến hoàn về người gửi'
 WHEN 'return_reassigned' THEN 'Đã đổi phân công hoàn hàng'
 WHEN 'returning' THEN 'Hàng đang được hoàn về người gửi'
 WHEN 'approaching_return' THEN 'Tài xế đang đến trả hàng'
 WHEN 'returned' THEN 'Đã hoàn hàng cho người gửi'
 WHEN 'return_failed' THEN 'Chưa hoàn được hàng · cần xử lý'
 WHEN 'return_at_warehouse' THEN 'Hàng hoàn đã về kho · chờ phân lại'
 WHEN 'failed_at_warehouse' THEN 'Hàng giao chưa thành công đã về kho'
 WHEN 'created' THEN 'GK đã tiếp nhận đơn' WHEN 'price_updated' THEN 'Đã cập nhật cước giao hàng'
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
 WHERE s.order_id=o.id AND s.kind=CASE WHEN NEW.type LIKE 'return%' OR NEW.type='waiting_return_dispatch' OR NEW.type='approaching_return' OR (NEW.type='assignment_updated' AND o.stage IN ('return_assigned','returning')) THEN 'return' WHEN NEW.type IN ('picking_up','at_warehouse','picked_up','pickup_assigned','pickup_reassigned','approaching_pickup','pickup_failed') OR (NEW.type='assignment_updated' AND o.stage IN ('pickup_assigned','picked_up')) THEN 'pickup' ELSE 'delivery' END
 ORDER BY s.id DESC LIMIT 1;
 IF assignment.driver_id IS NOT NULL AND NEW.type NOT IN ('schedule_updated','price_updated') THEN
  INSERT INTO driver_notifications(driver_id,source_key,order_id,type,title,message)
   VALUES(assignment.driver_id,'order-event:'||NEW.id,o.id,NEW.type,heading,o.code||' · '||assignment.code||' · '||assignment.plate||' · Ngày '||to_char(assignment.planned_start,'DD/MM/YYYY'));
 END IF;
 RETURN NEW;
END $$;
UPDATE orders SET stage='return_pending',status='failed',delivery_cancelled_at=now() WHERE stage='failed' AND delivery_failures>=3;

-- Protect direct writes as well as API confirmation. Completed historical trips are retained.
CREATE FUNCTION guard_new_customer_stop() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE t record; o record; n int; kg numeric; m3 numeric; v record;
BEGIN
 IF NEW.kind='warehouse' THEN RETURN NEW; END IF;
 SELECT * INTO t FROM trips WHERE id=NEW.trip_id FOR UPDATE;
 IF t.status='completed' THEN RETURN NEW; END IF;
 SELECT * INTO o FROM orders WHERE id=NEW.order_id FOR UPDATE;
 IF NEW.kind<>t.phase THEN RAISE EXCEPTION 'Không trộn lấy, giao và hoàn trong một chuyến';END IF;
 IF NEW.kind IN ('delivery','return') AND t.status<>'planned' THEN RAISE EXCEPTION 'Hàng tại kho chỉ được thêm trước xuất phát';END IF;
 IF NEW.kind='pickup' AND (o.in_warehouse OR o.delivery_cancelled_at IS NOT NULL OR o.stage NOT IN ('awaiting_pickup','pickup_assigned','picking_up')) THEN RAISE EXCEPTION 'Đơn không còn chờ lấy';END IF;
 IF NEW.kind='delivery' AND (NOT o.in_warehouse OR o.delivery_cancelled_at IS NOT NULL OR o.delivery_failures>=3 OR o.stage NOT IN ('at_warehouse','delivery_assigned')) THEN RAISE EXCEPTION 'Đơn không đủ điều kiện xuất giao';END IF;
 IF NEW.kind='return' AND (NOT o.in_warehouse OR o.delivery_cancelled_at IS NULL OR o.stage NOT IN ('return_pending','return_assigned')) THEN RAISE EXCEPTION 'Đơn không đủ điều kiện hoàn';END IF;
 IF EXISTS(SELECT 1 FROM trip_stops s JOIN trips other ON other.id=s.trip_id WHERE s.order_id=NEW.order_id AND other.status<>'completed' AND s.state IN ('pending','en_route')) THEN RAISE EXCEPTION 'Đơn đang có chuyến xử lý';END IF;
 SELECT count(*),coalesce(sum(a.weight_kg) FILTER(WHERE s.state<>'failed'),0),coalesce(sum(a.volume_m3) FILTER(WHERE s.state<>'failed'),0)
 INTO n,kg,m3 FROM trip_stops s JOIN orders a ON a.id=s.order_id WHERE s.trip_id=NEW.trip_id AND s.kind<>'warehouse';
 SELECT * INTO v FROM vehicles WHERE id=t.vehicle_id;
 IF n>=least(45,t.max_orders) OR kg+o.weight_kg>v.capacity_kg OR m3+o.volume_m3>v.capacity_m3 THEN RAISE EXCEPTION 'Vượt 45 đơn, tải trọng hoặc thể tích xe';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_new_customer_stop BEFORE INSERT ON trip_stops FOR EACH ROW EXECUTE FUNCTION guard_new_customer_stop();
