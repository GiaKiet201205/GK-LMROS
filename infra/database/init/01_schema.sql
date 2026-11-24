-- GK: schema. Chạy tự động khi container db khởi tạo lần đầu.
DO $$ BEGIN
  EXECUTE format('ALTER DATABASE %I SET timezone TO %L', current_database(), 'Asia/Ho_Chi_Minh');
END $$;
SET timezone TO 'Asia/Ho_Chi_Minh';
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE warehouses (
  id serial PRIMARY KEY,
  code text UNIQUE NOT NULL,
  name text NOT NULL,
  lat double precision NOT NULL,
  lng double precision NOT NULL,
  color text NOT NULL DEFAULT '#0f766e',
  geom geography(Point,4326) GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(lng,lat),4326)::geography) STORED
);

CREATE TABLE vehicles (
  id serial PRIMARY KEY,
  code text UNIQUE NOT NULL,                 -- XE-012
  plate text UNIQUE NOT NULL,                -- 51D-482.16
  warehouse_id int NOT NULL REFERENCES warehouses(id),
  capacity_kg numeric NOT NULL DEFAULT 1000,
  capacity_m3 numeric NOT NULL DEFAULT 8,
  cost_per_km numeric NOT NULL DEFAULT 6500,
  status text NOT NULL DEFAULT 'available'   -- available | active | maintenance
    CHECK (status IN ('available','active','maintenance'))
);

CREATE TABLE drivers (
  id serial PRIMARY KEY,
  code text UNIQUE NOT NULL,                 -- TX-012
  name text NOT NULL,
  phone text,
  warehouse_id int NOT NULL REFERENCES warehouses(id),
  vehicle_id int REFERENCES vehicles(id),
  shift_start time NOT NULL DEFAULT '08:00',
  shift_end time NOT NULL DEFAULT '20:00',
  status text NOT NULL DEFAULT 'off'         -- on_duty | off
);

CREATE TABLE orders (
  id serial PRIMARY KEY,
  code text UNIQUE NOT NULL,                 -- DH-261103-0148
  customer_name text NOT NULL,
  phone text,
  address text NOT NULL,
  district text,
  lat double precision NOT NULL,
  lng double precision NOT NULL,
  weight_kg numeric NOT NULL,
  volume_m3 numeric NOT NULL,
  parcels int NOT NULL DEFAULT 1,
  tw_start timestamp NOT NULL,
  tw_end timestamp NOT NULL,
  service_min int NOT NULL DEFAULT 6,
  status text NOT NULL DEFAULT 'new'
    CHECK (status IN ('new','pending','assigned','delivering','delivered','failed','deferred')),
  warehouse_id int REFERENCES warehouses(id),
  is_dynamic boolean NOT NULL DEFAULT false,
  note text,
  created_at timestamp NOT NULL DEFAULT now(),
  geom geography(Point,4326) GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(lng,lat),4326)::geography) STORED
);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_wh ON orders(warehouse_id);
CREATE INDEX idx_orders_geom ON orders USING gist(geom);

CREATE TABLE trips (
  id serial PRIMARY KEY,
  code text UNIQUE NOT NULL,                 -- T-012A
  vehicle_id int NOT NULL REFERENCES vehicles(id),
  driver_id int REFERENCES drivers(id),
  warehouse_id int NOT NULL REFERENCES warehouses(id),
  status text NOT NULL DEFAULT 'planned'     -- planned | in_progress | completed
    CHECK (status IN ('planned','in_progress','completed')),
  planned_start timestamp NOT NULL,
  planned_end timestamp,
  planned_km numeric DEFAULT 0
);

CREATE TABLE trip_stops (
  id serial PRIMARY KEY,
  trip_id int NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
  order_id int NOT NULL REFERENCES orders(id),
  seq int NOT NULL,
  eta timestamp,
  arrived_at timestamp,
  completed_at timestamp,
  fail_reason text,
  fail_note text,
  UNIQUE (trip_id, seq)
);
CREATE INDEX idx_stops_order ON trip_stops(order_id);

CREATE TABLE gps_points (
  time timestamptz NOT NULL DEFAULT now(),
  vehicle_id int NOT NULL,
  lat double precision NOT NULL,
  lng double precision NOT NULL,
  speed_kmh real
);
CREATE INDEX idx_gps_vehicle_time ON gps_points(vehicle_id, time DESC);
-- TimescaleDB là tùy chọn: có extension thì chuyển thành hypertable, không thì giữ bảng thường.
DO $$ BEGIN
  CREATE EXTENSION IF NOT EXISTS timescaledb;
  PERFORM create_hypertable('gps_points','time', if_not_exists => TRUE);
EXCEPTION WHEN OTHERS THEN
  RAISE NOTICE 'TimescaleDB không khả dụng, dùng bảng thường cho gps_points';
END $$;

CREATE TABLE alerts (
  id serial PRIMARY KEY,
  level text NOT NULL DEFAULT 'warning',     -- info | warning | danger
  message text NOT NULL,
  created_at timestamp NOT NULL DEFAULT now(),
  resolved boolean NOT NULL DEFAULT false
);

