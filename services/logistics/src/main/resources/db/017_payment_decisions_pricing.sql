-- Payment confirmation is independent of completing a physical delivery.
CREATE TABLE payment_decisions (
 stop_id int PRIMARY KEY REFERENCES trip_stops(id),order_id int NOT NULL REFERENCES orders(id),
 driver_id int NOT NULL REFERENCES drivers(id),created_by bigint NOT NULL,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER payment_decision_immutable BEFORE UPDATE OR DELETE ON payment_decisions FOR EACH ROW EXECUTE FUNCTION immutable_finance_entry();
ALTER TABLE payment_collections DROP CONSTRAINT payment_collections_method_check;
ALTER TABLE payment_collections ADD CONSTRAINT payment_collections_method_check CHECK(method IN ('cash','transfer','external'));
ALTER TABLE pricing_settings ADD COLUMN minimum_fare_vnd bigint NOT NULL DEFAULT 20000 CHECK(minimum_fare_vnd BETWEEN 0 AND 10000000);
UPDATE shipping_tariffs SET rate_vnd=CASE id WHEN 1 THEN 5000 WHEN 2 THEN 7000 WHEN 3 THEN 10000 WHEN 4 THEN 12000 END,updated_at=now();
UPDATE pricing_settings SET revision=revision+1 WHERE id=1;
-- Demo repricing is auditable. Original collections and ledger entries remain immutable.
CREATE TABLE demo_fare_revisions (
 batch_id text NOT NULL,order_id int NOT NULL REFERENCES orders(id),old_fee bigint,new_fee bigint NOT NULL,
 old_rate bigint,new_rate bigint NOT NULL,old_quote uuid,new_quote uuid NOT NULL DEFAULT gen_random_uuid(),created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(batch_id,order_id)
);
CREATE TABLE demo_collection_adjustments (
 batch_id text NOT NULL,collection_id bigint NOT NULL REFERENCES payment_collections(id),amount bigint NOT NULL CHECK(amount<>0),
 created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(batch_id,collection_id)
);
CREATE TRIGGER demo_revision_immutable BEFORE UPDATE OR DELETE ON demo_fare_revisions FOR EACH ROW EXECUTE FUNCTION immutable_finance_entry();
CREATE TRIGGER demo_adjustment_immutable BEFORE UPDATE OR DELETE ON demo_collection_adjustments FOR EACH ROW EXECUTE FUNCTION immutable_finance_entry();
CREATE FUNCTION validate_demo_adjustment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM payment_collections c JOIN orders o ON o.id=c.order_id JOIN demo_fare_revisions r ON r.order_id=o.id AND r.batch_id=NEW.batch_id
 WHERE c.id=NEW.collection_id AND o.is_demo AND r.new_fee=c.amount+NEW.amount+coalesce((SELECT sum(a.amount) FROM demo_collection_adjustments a WHERE a.collection_id=c.id),0))
 THEN RAISE EXCEPTION 'Điều chỉnh chỉ dành cho bộ dữ liệu mẫu đã được ghi nhận';END IF;RETURN NEW;
END $$;
CREATE TRIGGER validate_demo_adjustment BEFORE INSERT ON demo_collection_adjustments FOR EACH ROW EXECUTE FUNCTION validate_demo_adjustment();
CREATE OR REPLACE FUNCTION freeze_collected_fare() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.shipping_fee_vnd IS DISTINCT FROM NEW.shipping_fee_vnd AND EXISTS(SELECT 1 FROM payment_collections WHERE order_id=NEW.id) THEN
  IF NOT (OLD.is_demo AND NEW.is_demo AND EXISTS(SELECT 1 FROM demo_fare_revisions r WHERE r.batch_id=current_setting('gk.demo_repricing',true) AND r.order_id=NEW.id AND r.old_fee IS NOT DISTINCT FROM OLD.shipping_fee_vnd AND r.new_fee=NEW.shipping_fee_vnd)) THEN
   RAISE EXCEPTION 'Đơn đã thu cước; không đổi số tiền đã thu';
  END IF;
 END IF;RETURN NEW;
END $$;
CREATE OR REPLACE VIEW order_payments AS SELECT o.id AS order_id,o.payment_payer,c.id AS collection_id,
 (c.amount+coalesce(a.amount,0))::bigint AS paid_amount,c.method AS payment_method,c.received_by,c.driver_id AS collecting_driver,c.created_at AS paid_at,
 CASE WHEN ref.id IS NOT NULL THEN 'refunded' WHEN c.id IS NULL THEN CASE WHEN NOT o.payment_required THEN 'unverified' WHEN o.shipping_fee_vnd=0 THEN 'no_charge' ELSE 'unpaid' END ELSE 'paid' END AS payment_status,
 CASE WHEN c.id IS NULL THEN 'unpaid' WHEN ref.id IS NOT NULL THEN 'refunded' WHEN c.method='external' THEN 'external' WHEN c.received_by='company' OR h.status='approved' THEN 'remitted' WHEN h.status='pending' THEN 'pending_handover' ELSE 'driver_holding' END AS remittance_status
 FROM orders o LEFT JOIN payment_collections c ON c.order_id=o.id
 LEFT JOIN (SELECT collection_id,sum(amount) AS amount FROM demo_collection_adjustments GROUP BY collection_id) a ON a.collection_id=c.id
 LEFT JOIN cash_handover_items hi ON hi.collection_id=c.id LEFT JOIN cash_handovers h ON h.id=hi.handover_id LEFT JOIN payment_refunds ref ON ref.collection_id=c.id;
-- A processed cost receipt is read-only, including direct database writes.
CREATE OR REPLACE FUNCTION protect_vehicle_cost() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status<>'pending' THEN RAISE EXCEPTION 'Phiếu đã xử lý chỉ được xem';END IF;
 IF NEW.status NOT IN ('approved','rejected') OR NEW.reviewed_at IS NULL OR NEW.reviewed_by IS NULL THEN RAISE EXCEPTION 'Cần duyệt hoặc từ chối phiếu hợp lệ';END IF;
 IF (to_jsonb(OLD)-ARRAY['status','reviewed_at','reviewed_by','review_note']) IS DISTINCT FROM (to_jsonb(NEW)-ARRAY['status','reviewed_at','reviewed_by','review_note']) THEN RAISE EXCEPTION 'Không sửa nội dung phiếu khi duyệt';END IF;
 RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION notify_order_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o record; assignment record; heading text; tone text;
BEGIN
 SELECT * INTO o FROM orders WHERE id=NEW.order_id;
 heading:=CASE NEW.type WHEN 'payment_unpaid' THEN 'Cước chưa thanh toán' WHEN 'payment_collected' THEN 'Đã thu cước giao hàng' WHEN 'payment_payer_updated' THEN 'Đã cập nhật người trả cước' WHEN 'payment_refunded' THEN 'Đã hoàn cước giao hàng' WHEN 'picking_up' THEN 'Tài xế đã xuất phát lấy hàng'
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

CREATE FUNCTION protect_completed_repair() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.state='completed' THEN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Phiếu bảo dưỡng đã xử lý chỉ được xem';END IF;
  IF NEW IS DISTINCT FROM OLD THEN RAISE EXCEPTION 'Phiếu bảo dưỡng đã xử lý chỉ được xem';END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;END IF;RETURN NEW;
END $$;
CREATE TRIGGER protect_completed_repair BEFORE UPDATE OR DELETE ON vehicle_maintenance FOR EACH ROW EXECUTE FUNCTION protect_completed_repair();
