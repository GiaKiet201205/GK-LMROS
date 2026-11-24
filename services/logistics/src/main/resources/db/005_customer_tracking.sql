ALTER TABLE orders ADD COLUMN customer_ref uuid;
ALTER TABLE orders ADD COLUMN customer_booking_id uuid UNIQUE;
ALTER TABLE orders ADD CONSTRAINT customer_booking_owner CHECK ((customer_ref IS NULL)=(customer_booking_id IS NULL));
CREATE INDEX idx_orders_customer ON orders(customer_ref) WHERE customer_ref IS NOT NULL;
CREATE TABLE customer_tracking_events (
    id bigserial PRIMARY KEY, order_id int NOT NULL REFERENCES orders(id),
    type text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
    driver_name text, driver_phone text, plate text, reason text
);
CREATE INDEX idx_customer_tracking_order ON customer_tracking_events(order_id,id);

CREATE FUNCTION track_customer_order() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE event_type text;
BEGIN
    IF NEW.customer_ref IS NULL THEN RETURN NEW; END IF;
    IF OLD.customer_ref IS NULL THEN
        INSERT INTO customer_tracking_events(order_id,type,created_at) VALUES(NEW.id,'created',NEW.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh');
    ELSIF OLD.stage IS DISTINCT FROM NEW.stage THEN
        event_type:=CASE
            WHEN NEW.stage='at_warehouse' AND OLD.stage='picked_up' THEN 'at_warehouse'
            WHEN NEW.stage='out_for_delivery' THEN 'out_for_delivery'
            WHEN NEW.stage IN ('awaiting_pickup','at_warehouse') THEN 'waiting_dispatch'
            ELSE NULL END;
        IF event_type IS NOT NULL THEN
            INSERT INTO customer_tracking_events(order_id,type) VALUES(NEW.id,event_type);
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER customer_order_tracking AFTER UPDATE ON orders FOR EACH ROW EXECUTE FUNCTION track_customer_order();

CREATE FUNCTION track_customer_stop() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE event_type text; trip_record record;
BEGIN
    IF NEW.kind='warehouse' OR NOT EXISTS(SELECT 1 FROM orders WHERE id=NEW.order_id AND customer_ref IS NOT NULL) THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN
        event_type:=CASE NEW.kind WHEN 'pickup' THEN 'pickup_assigned' ELSE 'delivery_assigned' END;
    ELSIF OLD.state IS DISTINCT FROM NEW.state THEN
        event_type:=CASE NEW.state
            WHEN 'en_route' THEN CASE NEW.kind WHEN 'pickup' THEN 'approaching_pickup' ELSE 'approaching_delivery' END
            WHEN 'completed' THEN CASE NEW.kind WHEN 'pickup' THEN 'picked_up' ELSE 'delivered' END
            WHEN 'failed' THEN CASE NEW.kind WHEN 'pickup' THEN 'pickup_failed' ELSE 'delivery_failed' END
            ELSE NULL END;
    END IF;
    IF event_type IS NOT NULL THEN
        SELECT d.name,d.phone,v.plate INTO trip_record FROM trips t LEFT JOIN drivers d ON d.id=t.driver_id JOIN vehicles v ON v.id=t.vehicle_id WHERE t.id=NEW.trip_id;
        INSERT INTO customer_tracking_events(order_id,type,driver_name,driver_phone,plate,reason)
        VALUES(NEW.order_id,event_type,trip_record.name,trip_record.phone,trip_record.plate,NEW.fail_reason);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER customer_stop_tracking AFTER INSERT OR UPDATE ON trip_stops FOR EACH ROW EXECUTE FUNCTION track_customer_stop();
