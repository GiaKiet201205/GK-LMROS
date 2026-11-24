CREATE TABLE shipping_tariffs(id int PRIMARY KEY,min_kg numeric NOT NULL,max_kg numeric NOT NULL,rate_vnd bigint NOT NULL CHECK(rate_vnd>=0),enabled boolean NOT NULL DEFAULT true,updated_at timestamptz NOT NULL DEFAULT now(),CHECK(min_kg>=0 AND max_kg>min_kg));
INSERT INTO shipping_tariffs(id,min_kg,max_kg,rate_vnd) VALUES(1,0,5,2000),(2,5,20,3000),(3,20,50,4000),(4,50,100,5000);
CREATE TABLE pricing_settings(id int PRIMARY KEY CHECK(id=1),fixed_trip_cost_vnd bigint NOT NULL DEFAULT 35000 CHECK(fixed_trip_cost_vnd>=0),revision bigint NOT NULL DEFAULT 1);
INSERT INTO pricing_settings(id) VALUES(1);
CREATE TABLE shipping_quotes (
 id uuid PRIMARY KEY,pickup_lat double precision NOT NULL,pickup_lng double precision NOT NULL,delivery_lat double precision NOT NULL,delivery_lng double precision NOT NULL,
 weight_kg numeric NOT NULL CHECK(weight_kg>0),distance_m bigint NOT NULL CHECK(distance_m>=0),billed_km int NOT NULL CHECK(billed_km>=0),
 rate_vnd bigint,amount_vnd bigint,source text NOT NULL CHECK(source IN ('google','estimate')),
 price_status text NOT NULL CHECK(price_status IN ('confirmed','estimated','manual_quote')),rule_version text NOT NULL DEFAULT 'GK-5KG-2000-V1',created_at timestamptz NOT NULL DEFAULT now(),
 CHECK((price_status='manual_quote' AND amount_vnd IS NULL AND rate_vnd IS NULL) OR (price_status<>'manual_quote' AND amount_vnd>=0 AND rate_vnd>=0))
);
ALTER TABLE orders ADD COLUMN goods_description text,ADD COLUMN goods_type text NOT NULL DEFAULT 'other',
 ADD COLUMN shipping_quote_id uuid REFERENCES shipping_quotes(id),ADD COLUMN shipping_distance_m bigint CHECK(shipping_distance_m>=0),
 ADD COLUMN billed_distance_km int CHECK(billed_distance_km>=0),ADD COLUMN shipping_rate_vnd bigint CHECK(shipping_rate_vnd>=0),
 ADD COLUMN shipping_fee_vnd bigint CHECK(shipping_fee_vnd>=0),ADD COLUMN shipping_source text,
 ADD COLUMN shipping_price_status text NOT NULL DEFAULT 'unquoted' CHECK(shipping_price_status IN ('unquoted','confirmed','estimated','manual_quote')),
 ADD COLUMN shipping_price_note text,ADD COLUMN shipping_priced_at timestamptz;
-- Existing records retain their historical limit; new trips can never exceed 45 customers.
CREATE FUNCTION enforce_trip_order_limit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF NEW.max_orders>45 THEN RAISE EXCEPTION 'A new trip cannot exceed 45 orders';END IF;RETURN NEW;END $$;
CREATE TRIGGER trip_order_limit BEFORE INSERT OR UPDATE OF max_orders ON trips FOR EACH ROW EXECUTE FUNCTION enforce_trip_order_limit();

CREATE OR REPLACE FUNCTION notify_order_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o record; assignment record; heading text; tone text;
BEGIN
 SELECT * INTO o FROM orders WHERE id=NEW.order_id;
 heading:=CASE NEW.type WHEN 'created' THEN 'GK đã tiếp nhận đơn' WHEN 'price_updated' THEN 'Đã cập nhật cước giao hàng'
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
 IF assignment.driver_id IS NOT NULL AND NEW.type NOT IN ('schedule_updated','price_updated') THEN
  INSERT INTO driver_notifications(driver_id,source_key,order_id,type,title,message)
   VALUES(assignment.driver_id,'order-event:'||NEW.id,o.id,NEW.type,heading,o.code||' · '||assignment.code||' · '||assignment.plate||' · Ngày '||to_char(assignment.planned_start,'DD/MM/YYYY'));
 END IF;
 RETURN NEW;
END $$;
