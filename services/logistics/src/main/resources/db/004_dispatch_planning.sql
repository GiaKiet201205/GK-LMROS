CREATE TABLE driver_work_days (
    driver_id int NOT NULL REFERENCES drivers(id), work_date date NOT NULL,
    working boolean NOT NULL, shift_start time NOT NULL, shift_end time NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(driver_id,work_date), CHECK(shift_end>shift_start)
);
CREATE TABLE vehicle_work_days (
    vehicle_id int NOT NULL REFERENCES vehicles(id), work_date date NOT NULL,
    available boolean NOT NULL DEFAULT true, note text NOT NULL DEFAULT '',
    PRIMARY KEY(vehicle_id,work_date)
);
ALTER TABLE trips ADD COLUMN dispatch_mode text NOT NULL DEFAULT 'auto' CHECK(dispatch_mode IN ('auto','manual'));
ALTER TABLE trips ADD COLUMN max_orders int NOT NULL DEFAULT 45 CHECK(max_orders BETWEEN 1 AND 100);
CREATE INDEX idx_trips_work_date ON trips((planned_start::date),vehicle_id,driver_id) WHERE status<>'completed';
CREATE TABLE distribution_plans (
    id bigserial PRIMARY KEY, work_date date NOT NULL, warehouse_id int NOT NULL REFERENCES warehouses(id),
    revision bigint NOT NULL, payload jsonb NOT NULL, status text NOT NULL DEFAULT 'draft' CHECK(status IN ('draft','confirmed')),
    created_at timestamptz NOT NULL DEFAULT now(), confirmed_at timestamptz
);
UPDATE routing_state SET revision=revision+1,dirty=true WHERE id=1;
