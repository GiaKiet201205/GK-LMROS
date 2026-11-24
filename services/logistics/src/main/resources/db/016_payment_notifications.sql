CREATE OR REPLACE FUNCTION notify_order_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o record; assignment record; heading text; tone text;
BEGIN
 SELECT * INTO o FROM orders WHERE id=NEW.order_id;
 heading:=CASE NEW.type WHEN 'payment_collected' THEN 'Đã thu cước giao hàng' WHEN 'payment_payer_updated' THEN 'Đã cập nhật người trả cước' WHEN 'payment_refunded' THEN 'Đã hoàn cước giao hàng' WHEN 'picking_up' THEN 'Tài xế đã xuất phát lấy hàng'
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
 WHERE s.order_id=o.id AND s.kind=CASE WHEN NEW.type IN ('payment_collected','payment_payer_updated') AND o.stage IN ('awaiting_pickup','pickup_assigned','picking_up','picked_up') THEN 'pickup' WHEN NEW.type='payment_refunded' THEN 'return' WHEN NEW.type LIKE 'return%' OR NEW.type='waiting_return_dispatch' OR NEW.type='approaching_return' OR (NEW.type='assignment_updated' AND o.stage IN ('return_assigned','returning')) THEN 'return' WHEN NEW.type IN ('picking_up','at_warehouse','picked_up','pickup_assigned','pickup_reassigned','approaching_pickup','pickup_failed') OR (NEW.type='assignment_updated' AND o.stage IN ('pickup_assigned','picked_up')) THEN 'pickup' ELSE 'delivery' END
 ORDER BY s.id DESC LIMIT 1;
 IF assignment.driver_id IS NOT NULL AND NEW.type NOT IN ('schedule_updated','price_updated') THEN
  INSERT INTO driver_notifications(driver_id,source_key,order_id,type,title,message)
   VALUES(assignment.driver_id,'order-event:'||NEW.id,o.id,NEW.type,heading,o.code||' · '||assignment.code||' · '||assignment.plate||' · Ngày '||to_char(assignment.planned_start,'DD/MM/YYYY'));
 END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION validate_payment_collection() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r record;
BEGIN
 SELECT o.shipping_fee_vnd,o.payment_payer,s.order_id,s.kind,t.driver_id INTO r FROM trip_stops s JOIN trips t ON t.id=s.trip_id JOIN orders o ON o.id=s.order_id WHERE s.id=NEW.stop_id;
 IF r.order_id IS DISTINCT FROM NEW.order_id OR r.driver_id IS DISTINCT FROM NEW.driver_id OR r.shipping_fee_vnd IS DISTINCT FROM NEW.amount OR NEW.payer IS DISTINCT FROM r.payment_payer OR NOT((NEW.payer='sender' AND r.kind='pickup') OR (NEW.payer='recipient' AND r.kind='delivery')) THEN RAISE EXCEPTION 'Khoản thu không khớp đơn, tài xế, người trả hoặc cước';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER validate_payment_collection BEFORE INSERT ON payment_collections FOR EACH ROW EXECUTE FUNCTION validate_payment_collection();
CREATE FUNCTION protect_cash_handover() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status<>'pending' THEN RAISE EXCEPTION 'Phiếu nộp tiền đã xử lý phải giữ nguyên lịch sử';END IF;
 IF NEW.status IN ('approved','rejected') AND (NEW.reviewed_by IS NULL OR NEW.reviewed_at IS NULL) THEN RAISE EXCEPTION 'Thiếu người/thời gian xác nhận nhận tiền';END IF;
 IF NEW.status='approved' AND NEW.amount<>(SELECT coalesce(sum(c.amount),0) FROM cash_handover_items i JOIN payment_collections c ON c.id=i.collection_id WHERE i.handover_id=NEW.id AND c.driver_id=NEW.driver_id AND c.received_by='driver') THEN RAISE EXCEPTION 'Số tiền nộp không khớp các khoản đang giữ';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_cash_handover BEFORE UPDATE ON cash_handovers FOR EACH ROW EXECUTE FUNCTION protect_cash_handover();
CREATE FUNCTION protect_vehicle_cost() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status='approved' AND (NEW.status<>'cancelled' OR (to_jsonb(OLD)-'status'-'reviewed_by'-'reviewed_at'-'review_note') IS DISTINCT FROM(to_jsonb(NEW)-'status'-'reviewed_by'-'reviewed_at'-'review_note')) THEN RAISE EXCEPTION 'Phiếu chi đã duyệt chỉ được hủy để lập lại';END IF;
 IF OLD.status IN ('rejected','cancelled') THEN RAISE EXCEPTION 'Phiếu đã xử lý không được sửa lịch sử';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_vehicle_cost BEFORE UPDATE ON vehicle_cost_receipts FOR EACH ROW EXECUTE FUNCTION protect_vehicle_cost();
