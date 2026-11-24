-- Applied once by Logistics, including databases with an existing volume.
ALTER TABLE orders ADD COLUMN pickup_name text;
ALTER TABLE orders ADD COLUMN pickup_phone text;
ALTER TABLE orders ADD COLUMN pickup_address text;
ALTER TABLE orders ADD COLUMN pickup_lat double precision;
ALTER TABLE orders ADD COLUMN pickup_lng double precision;
ALTER TABLE orders ADD COLUMN pickup_from timestamp;
ALTER TABLE orders ADD COLUMN pickup_to timestamp;
ALTER TABLE orders ADD COLUMN stage text NOT NULL DEFAULT 'at_warehouse';
ALTER TABLE orders ADD COLUMN in_warehouse boolean NOT NULL DEFAULT true;
UPDATE orders SET stage = CASE WHEN status='delivered' THEN 'delivered'
    WHEN status='failed' THEN 'failed' WHEN status='assigned' THEN 'delivery_assigned' WHEN status='delivering' THEN 'out_for_delivery'
    ELSE 'at_warehouse' END;
UPDATE orders SET in_warehouse=false WHERE stage IN ('out_for_delivery','delivered');
UPDATE orders o SET stage='out_for_delivery',in_warehouse=false WHERE status='assigned' AND EXISTS
    (SELECT 1 FROM trip_stops s JOIN trips t ON t.id=s.trip_id WHERE s.order_id=o.id AND t.status='in_progress');
ALTER TABLE orders ADD CONSTRAINT orders_stage_check CHECK(stage IN
    ('awaiting_pickup','pickup_assigned','picked_up','at_warehouse','delivery_assigned','out_for_delivery','delivered','failed'));
ALTER TABLE trip_stops ALTER COLUMN order_id DROP NOT NULL;
ALTER TABLE trip_stops ADD COLUMN kind text NOT NULL DEFAULT 'delivery' CHECK(kind IN ('pickup','delivery','warehouse'));
ALTER TABLE trip_stops ADD COLUMN state text NOT NULL DEFAULT 'pending' CHECK(state IN ('pending','en_route','completed','failed'));
ALTER TABLE trip_stops ADD COLUMN departed_at timestamptz;
UPDATE trip_stops s SET state=CASE WHEN o.status='failed' THEN 'failed' ELSE 'completed' END
    FROM orders o WHERE o.id=s.order_id AND (s.completed_at IS NOT NULL OR o.status IN ('delivered','failed'));
ALTER TABLE trip_stops ADD CONSTRAINT stops_order_kind_check CHECK((kind='warehouse' AND order_id IS NULL) OR (kind<>'warehouse' AND order_id IS NOT NULL));
ALTER TABLE trips ADD COLUMN route_version bigint NOT NULL DEFAULT 1;
ALTER TABLE trips ADD COLUMN phase text NOT NULL DEFAULT 'delivery' CHECK(phase IN ('pickup','delivery'));
ALTER TABLE trips ADD COLUMN remaining_km numeric NOT NULL DEFAULT 0;
ALTER TABLE trips ADD COLUMN route_updated_at timestamptz NOT NULL DEFAULT now();
INSERT INTO trip_stops(trip_id,order_id,seq,kind,state)
SELECT t.id,NULL,coalesce(max(s.seq),0)+1,'warehouse','pending'
FROM trips t LEFT JOIN trip_stops s ON s.trip_id=t.id WHERE t.status<>'completed' GROUP BY t.id;
CREATE INDEX idx_stops_pending ON trip_stops(trip_id,seq) WHERE state IN ('pending','en_route');
CREATE INDEX idx_orders_stage ON orders(stage);
CREATE TABLE routing_state (
    id int PRIMARY KEY CHECK(id=1), revision bigint NOT NULL DEFAULT 0,
    dirty boolean NOT NULL DEFAULT false, lease_owner text, lease_until timestamptz,
    last_success timestamptz, last_error text, retry_after timestamptz,
    last_stats jsonb NOT NULL DEFAULT '{}'::jsonb
);
INSERT INTO routing_state(id) VALUES(1);
CREATE TABLE routing_events (
    id bigserial PRIMARY KEY, created_at timestamptz NOT NULL DEFAULT now(),
    revision bigint NOT NULL, assigned int NOT NULL, remaining_km numeric NOT NULL,
    source text NOT NULL, details jsonb NOT NULL
);
