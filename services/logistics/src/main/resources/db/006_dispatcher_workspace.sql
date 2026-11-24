CREATE TABLE dispatcher_preferences (
    user_id bigint PRIMARY KEY,
    theme text NOT NULL DEFAULT 'system' CHECK(theme IN ('light','dark','system')),
    density text NOT NULL DEFAULT 'comfortable' CHECK(density IN ('comfortable','compact')),
    notification_toasts boolean NOT NULL DEFAULT true,
    default_warehouse_id int REFERENCES warehouses(id) ON DELETE SET NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE dispatcher_notifications (
    id bigserial PRIMARY KEY, source_key text NOT NULL UNIQUE,
    type text NOT NULL, level text NOT NULL CHECK(level IN ('info','success','warning','error')),
    title text NOT NULL, message text NOT NULL, href text NOT NULL,
    warehouse_id int REFERENCES warehouses(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE dispatcher_notification_reads (
    user_id bigint NOT NULL, notification_id bigint NOT NULL REFERENCES dispatcher_notifications(id) ON DELETE CASCADE,
    read_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(user_id,notification_id)
);
CREATE INDEX idx_dispatcher_notifications_recent ON dispatcher_notifications(created_at DESC,id DESC);
INSERT INTO dispatcher_notifications(source_key,type,level,title,message,href,created_at)
SELECT 'alert:'||id,'alert',CASE level WHEN 'danger' THEN 'error' WHEN 'info' THEN 'info' ELSE 'warning' END,
    'Cảnh báo vận hành',message,'/routing',created_at AT TIME ZONE 'Asia/Ho_Chi_Minh'
FROM alerts WHERE NOT resolved;

CREATE FUNCTION notify_dispatcher_order() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO dispatcher_notifications(source_key,type,level,title,message,href,warehouse_id)
    VALUES('order:'||NEW.id,'order_created','info','Có đơn lấy & giao mới',NEW.code||' · '||coalesce(NEW.pickup_name,NEW.customer_name)||' · '||coalesce(NEW.pickup_address,NEW.address),'/orders?q='||NEW.code,NEW.warehouse_id);
    RETURN NEW;
END $$;
CREATE TRIGGER dispatcher_order_created AFTER INSERT ON orders FOR EACH ROW EXECUTE FUNCTION notify_dispatcher_order();

CREATE FUNCTION notify_dispatcher_stop_failure() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE order_record record;
BEGIN
    IF NEW.state='failed' AND OLD.state IS DISTINCT FROM NEW.state AND NEW.order_id IS NOT NULL THEN
        SELECT code,warehouse_id INTO order_record FROM orders WHERE id=NEW.order_id;
        INSERT INTO dispatcher_notifications(source_key,type,level,title,message,href,warehouse_id)
        VALUES('stop-failed:'||NEW.id,'stop_failed','error',CASE NEW.kind WHEN 'pickup' THEN 'Có đơn chưa lấy được' ELSE 'Có đơn chưa giao được' END,
            order_record.code||' · '||coalesce(NEW.fail_reason,'Cần kiểm tra với tài xế'),'/orders?q='||order_record.code,order_record.warehouse_id)
        ON CONFLICT(source_key) DO NOTHING;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER dispatcher_stop_failure AFTER UPDATE ON trip_stops FOR EACH ROW EXECUTE FUNCTION notify_dispatcher_stop_failure();

CREATE FUNCTION notify_dispatcher_trip_completed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE vehicle_code text;
BEGIN
    IF NEW.status='completed' AND OLD.status IS DISTINCT FROM NEW.status THEN
        SELECT code INTO vehicle_code FROM vehicles WHERE id=NEW.vehicle_id;
        INSERT INTO dispatcher_notifications(source_key,type,level,title,message,href,warehouse_id)
        VALUES('trip-completed:'||NEW.id,'trip_completed','success',CASE NEW.phase WHEN 'pickup' THEN 'Xe đã về kho sau chuyến lấy' ELSE 'Đã hoàn tất chuyến giao' END,
            vehicle_code||' · '||NEW.code||CASE NEW.phase WHEN 'pickup' THEN ' · Kiểm tra hàng đã về kho để phân giao.' ELSE ' · Xe đã kết thúc hành trình.' END,
            CASE NEW.phase WHEN 'pickup' THEN '/distribution' ELSE '/live' END,NEW.warehouse_id)
        ON CONFLICT(source_key) DO NOTHING;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER dispatcher_trip_completed AFTER UPDATE ON trips FOR EACH ROW EXECUTE FUNCTION notify_dispatcher_trip_completed();

CREATE FUNCTION notify_dispatcher_routing() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE wh int;
BEGIN
    IF NEW.assigned>0 OR NEW.source='manual_release' THEN
        IF NEW.details ? 'plan_id' THEN SELECT warehouse_id INTO wh FROM distribution_plans WHERE id=(NEW.details->>'plan_id')::bigint; END IF;
        INSERT INTO dispatcher_notifications(source_key,type,level,title,message,href,warehouse_id)
        VALUES('routing-event:'||NEW.id,'assignment','info',CASE WHEN NEW.source='manual_release' THEN 'Đã thu hồi chuyến chưa xuất phát' ELSE 'Phân công chuyến vừa được cập nhật' END,
            CASE WHEN NEW.source='manual_release' THEN coalesce(NEW.details->>'trip','Chuyến')||' · Đơn đã trở về hàng chờ.' ELSE NEW.assigned||' đơn đã được phân công. Xem hành trình để theo dõi.' END,
            CASE WHEN NEW.source='manual_release' THEN '/distribution' ELSE '/live' END,wh);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER dispatcher_routing_event AFTER INSERT ON routing_events FOR EACH ROW EXECUTE FUNCTION notify_dispatcher_routing();

CREATE FUNCTION notify_dispatcher_routing_error() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.last_error IS NOT NULL AND OLD.last_error IS DISTINCT FROM NEW.last_error THEN
        INSERT INTO dispatcher_notifications(source_key,type,level,title,message,href)
        VALUES('routing-error:'||NEW.revision||':'||nextval('dispatcher_notifications_id_seq'),'routing_error','warning','Tuyến đang chờ được tối ưu lại',NEW.last_error,'/routing');
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER dispatcher_routing_error AFTER UPDATE ON routing_state FOR EACH ROW EXECUTE FUNCTION notify_dispatcher_routing_error();

CREATE FUNCTION notify_dispatcher_alert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT NEW.resolved THEN
        INSERT INTO dispatcher_notifications(source_key,type,level,title,message,href)
        VALUES('alert:'||NEW.id,'alert',CASE NEW.level WHEN 'danger' THEN 'error' WHEN 'info' THEN 'info' ELSE 'warning' END,'Cảnh báo vận hành',NEW.message,'/routing')
        ON CONFLICT(source_key) DO NOTHING;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER dispatcher_alert AFTER INSERT ON alerts FOR EACH ROW EXECUTE FUNCTION notify_dispatcher_alert();
