CREATE TABLE customers (
    id uuid PRIMARY KEY, phone text UNIQUE NOT NULL, name text NOT NULL,
    password_hash text NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE customer_sessions (
    token_hash text PRIMARY KEY, customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL
);
CREATE INDEX idx_customer_sessions_expiry ON customer_sessions(expires_at);
CREATE TABLE bookings (
    id uuid PRIMARY KEY, customer_id uuid NOT NULL REFERENCES customers(id), request_key uuid NOT NULL,
    payload jsonb NOT NULL, state text NOT NULL DEFAULT 'pending' CHECK(state IN ('pending','submitted','rejected')),
    order_id int, order_code text, snapshot jsonb NOT NULL DEFAULT '{}', error text,
    attempts int NOT NULL DEFAULT 0, next_attempt timestamptz NOT NULL DEFAULT now(),
    locked_until timestamptz, last_sync timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(customer_id,request_key)
);
CREATE INDEX idx_bookings_customer ON bookings(customer_id,created_at DESC);
CREATE INDEX idx_bookings_due ON bookings(next_attempt);
CREATE TABLE booking_events (
    source_id bigint PRIMARY KEY, booking_id uuid NOT NULL REFERENCES bookings(id),
    type text NOT NULL, occurred_at timestamptz NOT NULL, details jsonb NOT NULL
);
CREATE INDEX idx_booking_events_booking ON booking_events(booking_id,source_id);
CREATE TABLE customer_notifications (
    id bigserial PRIMARY KEY, customer_id uuid NOT NULL REFERENCES customers(id), booking_id uuid NOT NULL REFERENCES bookings(id),
    source_event bigint UNIQUE NOT NULL REFERENCES booking_events(source_id), type text NOT NULL,
    created_at timestamptz NOT NULL, read_at timestamptz
);
CREATE INDEX idx_customer_notifications ON customer_notifications(customer_id,id DESC);
