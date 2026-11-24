-- Posted actuals are immutable. Estimates never enter this ledger.
CREATE TABLE driver_payrolls (
 id bigserial PRIMARY KEY,request_id uuid NOT NULL UNIQUE,driver_id int NOT NULL REFERENCES drivers(id),warehouse_id int NOT NULL REFERENCES warehouses(id),
 period date NOT NULL CHECK(extract(day FROM period)=1),worked_days int NOT NULL CHECK(worked_days BETWEEN 0 AND 31),
 actual_days int NOT NULL CHECK(actual_days BETWEEN 0 AND 31),daily_rate bigint NOT NULL CHECK(daily_rate>0 AND daily_rate<=100000000),
 allowance bigint NOT NULL DEFAULT 0 CHECK(allowance BETWEEN 0 AND 1000000000),bonus bigint NOT NULL DEFAULT 0 CHECK(bonus BETWEEN 0 AND 1000000000),
 deduction bigint NOT NULL DEFAULT 0 CHECK(deduction BETWEEN 0 AND 1000000000),
 net_amount bigint GENERATED ALWAYS AS (worked_days::bigint*daily_rate+allowance+bonus-deduction) STORED,
 adjustment_reason text,work_dates jsonb NOT NULL,description text NOT NULL,
 status text NOT NULL DEFAULT 'draft' CHECK(status IN ('draft','approved','cancelled')),
 created_by bigint NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),approved_by bigint,approved_at timestamptz,cancel_reason text,cancelled_by bigint,cancelled_at timestamptz,
 CHECK(deduction<=worked_days::bigint*daily_rate+allowance+bonus),
 CHECK(worked_days=actual_days OR coalesce(length(trim(adjustment_reason)),0)>=3),
 CHECK(status<>'approved' OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
 CHECK(status<>'cancelled' OR (cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL AND coalesce(length(trim(cancel_reason)),0)>=3))
);
CREATE UNIQUE INDEX payroll_one_active_period ON driver_payrolls(driver_id,period) WHERE status<>'cancelled';
CREATE TABLE finance_entries (
 id bigserial PRIMARY KEY,source_key text NOT NULL UNIQUE,entry_date date NOT NULL,
 direction text NOT NULL CHECK(direction IN ('income','expense')),
 category text NOT NULL CHECK(category IN ('delivery','salary','fuel','maintenance','toll','parking','insurance','rent','other')),
 amount numeric(16,2) NOT NULL CHECK(amount>0 AND amount=round(amount)),
 warehouse_id int NOT NULL REFERENCES warehouses(id),driver_id int REFERENCES drivers(id),vehicle_id int REFERENCES vehicles(id),
 order_id int REFERENCES orders(id),maintenance_id bigint REFERENCES vehicle_maintenance(id),payroll_id bigint REFERENCES driver_payrolls(id),
 description text NOT NULL CHECK(length(trim(description)) BETWEEN 3 AND 500),reference text,
 quantity numeric(12,3) CHECK(quantity>0),unit_price bigint CHECK(unit_price>0),
 reverses_id bigint UNIQUE REFERENCES finance_entries(id),created_by bigint,created_at timestamptz NOT NULL DEFAULT now(),
 CHECK(category<>'fuel' OR reverses_id IS NOT NULL OR (vehicle_id IS NOT NULL AND quantity IS NOT NULL AND unit_price IS NOT NULL AND amount=round(quantity*unit_price)))
);
CREATE INDEX finance_period_warehouse ON finance_entries(entry_date,warehouse_id);
CREATE INDEX finance_driver_period ON finance_entries(driver_id,entry_date);
CREATE VIEW finance_ledger AS SELECT f.*,
 CASE WHEN category='delivery' THEN CASE direction WHEN 'income' THEN amount ELSE -amount END ELSE 0 END AS revenue,
 CASE WHEN category<>'delivery' THEN CASE direction WHEN 'expense' THEN amount ELSE -amount END ELSE 0 END AS expense
 FROM finance_entries f;
CREATE FUNCTION immutable_finance_entry() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Bút toán đã ghi sổ không được sửa/xóa; lập bút toán đảo có lý do';END $$;
CREATE TRIGGER finance_immutable BEFORE UPDATE OR DELETE ON finance_entries FOR EACH ROW EXECUTE FUNCTION immutable_finance_entry();

CREATE FUNCTION post_delivery_revenue(oid int) RETURNS void LANGUAGE plpgsql AS $$
DECLARE o record;s record;
BEGIN
 SELECT * INTO o FROM orders WHERE id=oid;
 IF o.stage<>'delivered' OR o.shipping_price_status<>'confirmed' OR o.shipping_fee_vnd IS NULL OR o.shipping_fee_vnd<=0 OR o.warehouse_id IS NULL THEN RETURN;END IF;
 SELECT st.completed_at,t.driver_id,t.vehicle_id INTO s FROM trip_stops st JOIN trips t ON t.id=st.trip_id
 WHERE st.order_id=oid AND st.kind='delivery' AND st.state='completed' AND st.completed_at IS NOT NULL ORDER BY st.completed_at DESC,st.id DESC LIMIT 1;
 IF s.completed_at IS NULL THEN RETURN;END IF;
 INSERT INTO finance_entries(source_key,entry_date,direction,category,amount,warehouse_id,driver_id,vehicle_id,order_id,description)
 VALUES('delivery:'||oid,s.completed_at::date,'income','delivery',o.shipping_fee_vnd,o.warehouse_id,s.driver_id,s.vehicle_id,oid,'Cước giao thành công · '||o.code) ON CONFLICT(source_key) DO NOTHING;
END $$;
CREATE FUNCTION finance_delivery_stop() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF NEW.kind='delivery' AND NEW.state='completed' THEN PERFORM post_delivery_revenue(NEW.order_id);END IF;RETURN NEW;END $$;
CREATE TRIGGER finance_delivery_stop AFTER INSERT OR UPDATE OF state ON trip_stops FOR EACH ROW EXECUTE FUNCTION finance_delivery_stop();
CREATE FUNCTION finance_order_price() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN PERFORM post_delivery_revenue(NEW.id);RETURN NEW;END $$;
CREATE TRIGGER finance_order_price AFTER UPDATE OF shipping_fee_vnd,shipping_price_status ON orders FOR EACH ROW EXECUTE FUNCTION finance_order_price();

CREATE FUNCTION finance_maintenance_cost() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE wh int;
BEGIN
 IF NEW.state='completed' AND NEW.repair_cost>0 THEN
  SELECT warehouse_id INTO wh FROM vehicles WHERE id=NEW.vehicle_id;
  INSERT INTO finance_entries(source_key,entry_date,direction,category,amount,warehouse_id,vehicle_id,driver_id,maintenance_id,description,reference,created_by)
  VALUES('maintenance:'||NEW.id,(coalesce(NEW.reviewed_at,NEW.completed_at) AT TIME ZONE 'Asia/Ho_Chi_Minh')::date,'expense','maintenance',NEW.repair_cost,wh,NEW.vehicle_id,NEW.reported_by_driver_id,NEW.id,'Bảo dưỡng BD-'||NEW.id,NEW.receipt_reference,NEW.reviewed_by_user_id) ON CONFLICT(source_key) DO NOTHING;
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER finance_maintenance_cost AFTER INSERT OR UPDATE OF state ON vehicle_maintenance FOR EACH ROW EXECUTE FUNCTION finance_maintenance_cost();
-- Only actual dated, confirmed historical data is imported; no synthetic income or expenses.
DO $$ DECLARE r record;BEGIN FOR r IN SELECT id FROM orders WHERE stage='delivered' AND shipping_price_status='confirmed' LOOP PERFORM post_delivery_revenue(r.id);END LOOP;END $$;
INSERT INTO finance_entries(source_key,entry_date,direction,category,amount,warehouse_id,vehicle_id,driver_id,maintenance_id,description,reference,created_by)
 SELECT 'maintenance:'||m.id,(coalesce(m.reviewed_at,m.completed_at) AT TIME ZONE 'Asia/Ho_Chi_Minh')::date,'expense','maintenance',m.repair_cost,v.warehouse_id,m.vehicle_id,m.reported_by_driver_id,m.id,'Bảo dưỡng BD-'||m.id,m.receipt_reference,m.reviewed_by_user_id
 FROM vehicle_maintenance m JOIN vehicles v ON v.id=m.vehicle_id WHERE m.state='completed' AND m.repair_cost>0 AND coalesce(m.reviewed_at,m.completed_at) IS NOT NULL;
CREATE TABLE finance_requests(id uuid PRIMARY KEY,kind text NOT NULL,body jsonb NOT NULL,result jsonb NOT NULL,created_by bigint NOT NULL,created_at timestamptz NOT NULL DEFAULT now());

CREATE FUNCTION protect_approved_payroll() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status='cancelled' THEN RAISE EXCEPTION 'Bảng lương đã hủy được giữ nguyên làm lịch sử';END IF;
 IF OLD.status='approved' AND (NEW.status<>'cancelled' OR (to_jsonb(OLD)-'net_amount'-'status'-'cancel_reason'-'cancelled_by'-'cancelled_at') IS DISTINCT FROM (to_jsonb(NEW)-'net_amount'-'status'-'cancel_reason'-'cancelled_by'-'cancelled_at')) THEN RAISE EXCEPTION 'Không sửa bảng lương đã duyệt; hủy phiếu có lý do và lập lại';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_approved_payroll BEFORE UPDATE ON driver_payrolls FOR EACH ROW EXECUTE FUNCTION protect_approved_payroll();
CREATE INDEX finance_order_lookup ON finance_entries(order_id) WHERE order_id IS NOT NULL;
CREATE INDEX finance_maintenance_lookup ON finance_entries(maintenance_id) WHERE maintenance_id IS NOT NULL;
CREATE INDEX finance_fuel_vehicle_day ON finance_entries(vehicle_id,entry_date) WHERE category='fuel';
CREATE INDEX finance_attendance_lookup ON trips(driver_id,planned_start) WHERE status IN ('completed','in_progress');
