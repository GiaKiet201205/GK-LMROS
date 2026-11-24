-- Existing warehouse ownership stays intact for goods already handled/in transit.
ALTER TABLE orders ADD COLUMN warehouse_assignment_method text NOT NULL DEFAULT 'legacy' CHECK(warehouse_assignment_method IN ('legacy','nearest_pickup_geodesic')),
 ADD COLUMN warehouse_distance_m bigint CHECK(warehouse_distance_m>=0),ADD COLUMN warehouse_assigned_at timestamptz;
ALTER TABLE distribution_plans ADD COLUMN planning_objective text NOT NULL DEFAULT 'legacy_cost' CHECK(planning_objective IN ('legacy_cost','min_trips_then_cost'));
UPDATE routing_state SET revision=revision+1,dirty=true WHERE id=1;

CREATE OR REPLACE FUNCTION notify_order_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o record; assignment record; heading text; tone text;
BEGIN
 SELECT * INTO o FROM orders WHERE id=NEW.order_id;
 heading:=CASE NEW.type WHEN 'warehouse_assigned' THEN 'Đã xác định kho tiếp quản đơn' WHEN 'payment_unpaid' THEN 'Cước chưa thanh toán' WHEN 'payment_collected' THEN 'Đã thu cước giao hàng' WHEN 'payment_payer_updated' THEN 'Đã cập nhật người trả cước' WHEN 'payment_refunded' THEN 'Đã hoàn cước giao hàng' WHEN 'picking_up' THEN 'Tài xế đã xuất phát lấy hàng'
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
 WHERE s.order_id=o.id AND s.kind=CASE WHEN NEW.type IN ('payment_unpaid','payment_collected','payment_payer_updated') AND o.stage IN ('awaiting_pickup','pickup_assigned','picking_up','picked_up') THEN 'pickup' WHEN NEW.type='payment_refunded' THEN 'return' WHEN NEW.type LIKE 'return%' OR NEW.type='waiting_return_dispatch' OR NEW.type='approaching_return' OR (NEW.type='assignment_updated' AND o.stage IN ('return_assigned','returning')) THEN 'return' WHEN NEW.type IN ('picking_up','at_warehouse','picked_up','pickup_assigned','pickup_reassigned','approaching_pickup','pickup_failed') OR (NEW.type='assignment_updated' AND o.stage IN ('pickup_assigned','picked_up')) THEN 'pickup' ELSE 'delivery' END
 ORDER BY s.id DESC LIMIT 1;
 IF assignment.driver_id IS NOT NULL AND NEW.type NOT IN ('schedule_updated','price_updated') THEN
  INSERT INTO driver_notifications(driver_id,source_key,order_id,type,title,message)
   VALUES(assignment.driver_id,'order-event:'||NEW.id,o.id,NEW.type,heading,o.code||' · '||assignment.code||' · '||assignment.plate||' · Ngày '||to_char(assignment.planned_start,'DD/MM/YYYY'));
 END IF;
 RETURN NEW;
END $$;
-- Reclassify only goods still at the sender with no stop/assignment history.
-- Never move stock at a warehouse, active trips or historical completed orders.
CREATE TEMP TABLE gk_waiting_warehouse_assignment ON COMMIT DROP AS
 SELECT o.id,o.warehouse_id AS old_warehouse,n.id AS new_warehouse,n.name,n.distance_m
 FROM orders o JOIN LATERAL(
  SELECT w.id,w.name,12742000*asin(sqrt(least(1::double precision,greatest(0::double precision,
   power(sin(radians(w.lat-o.pickup_lat)/2),2)+cos(radians(o.pickup_lat))*cos(radians(w.lat))*power(sin(radians(w.lng-o.pickup_lng)/2),2))))) AS distance_m
  FROM warehouses w WHERE w.archived_at IS NULL ORDER BY distance_m,w.id LIMIT 1
 ) n ON true
 WHERE o.stage='awaiting_pickup' AND NOT o.in_warehouse AND o.pickup_lat BETWEEN -90 AND 90 AND o.pickup_lng BETWEEN -180 AND 180
 AND NOT EXISTS(SELECT 1 FROM trip_stops st WHERE st.order_id=o.id);
UPDATE orders o SET warehouse_id=a.new_warehouse,warehouse_assignment_method='nearest_pickup_geodesic',warehouse_distance_m=round(a.distance_m)::bigint,warehouse_assigned_at=now()
 FROM gk_waiting_warehouse_assignment a WHERE a.id=o.id;
INSERT INTO customer_tracking_events(order_id,type,reason)
 SELECT id,'warehouse_assigned','Kho tiếp quản: '||name FROM gk_waiting_warehouse_assignment WHERE old_warehouse IS DISTINCT FROM new_warehouse;
