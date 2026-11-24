-- Cước giao hàng: doanh thu, thu tiền và nộp tiền là các nghiệp vụ riêng.
ALTER TABLE orders ADD COLUMN payment_payer text NOT NULL DEFAULT 'recipient' CHECK(payment_payer IN ('sender','recipient'));
ALTER TABLE orders ADD COLUMN payment_required boolean NOT NULL DEFAULT true;
UPDATE orders SET payment_required=false WHERE stage IN ('delivered','returned');
ALTER TABLE orders ADD COLUMN is_demo boolean NOT NULL DEFAULT false;
ALTER TABLE trips ADD COLUMN is_demo boolean NOT NULL DEFAULT false;
ALTER TABLE finance_entries ADD COLUMN is_demo boolean NOT NULL DEFAULT false;
CREATE TABLE driver_salary_rates (
 id bigserial PRIMARY KEY,driver_id int NOT NULL REFERENCES drivers(id),effective_month date NOT NULL CHECK(extract(day FROM effective_month)=1),
 monthly_amount bigint NOT NULL CHECK(monthly_amount BETWEEN 0 AND 1000000000),created_by bigint,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(driver_id,effective_month)
);
INSERT INTO driver_salary_rates(driver_id,effective_month,monthly_amount) SELECT id,'2026-09-01',15000000 FROM drivers;
ALTER TABLE driver_payrolls ADD COLUMN salary_mode text NOT NULL DEFAULT 'daily_legacy' CHECK(salary_mode IN ('daily_legacy','fixed'));
ALTER TABLE driver_payrolls ADD COLUMN monthly_salary bigint CHECK(monthly_salary BETWEEN 0 AND 1000000000);
ALTER TABLE driver_payrolls ADD COLUMN is_demo boolean NOT NULL DEFAULT false;
ALTER TABLE driver_payrolls ALTER COLUMN net_amount DROP EXPRESSION;
ALTER TABLE driver_payrolls DROP COLUMN net_amount;
ALTER TABLE driver_payrolls ADD COLUMN net_amount bigint GENERATED ALWAYS AS ((CASE WHEN salary_mode='fixed' THEN monthly_salary ELSE worked_days::bigint*daily_rate END)+allowance+bonus-deduction) STORED;
ALTER TABLE driver_payrolls DROP CONSTRAINT driver_payrolls_check;
ALTER TABLE driver_payrolls ADD CONSTRAINT payroll_nonnegative CHECK(deduction<=CASE WHEN salary_mode='fixed' THEN monthly_salary ELSE worked_days::bigint*daily_rate END+allowance+bonus);
ALTER TABLE driver_payrolls ADD CONSTRAINT fixed_salary_required CHECK(salary_mode<>'fixed' OR monthly_salary IS NOT NULL);

CREATE TABLE payment_collections (
 id bigserial PRIMARY KEY,order_id int NOT NULL UNIQUE REFERENCES orders(id),stop_id int NOT NULL REFERENCES trip_stops(id),driver_id int NOT NULL REFERENCES drivers(id),
 payer text NOT NULL CHECK(payer IN ('sender','recipient')),amount bigint NOT NULL CHECK(amount>0),method text NOT NULL CHECK(method IN ('cash','transfer')),
 received_by text NOT NULL CHECK(received_by IN ('driver','company')),reference text,created_at timestamptz NOT NULL DEFAULT now(),created_by bigint NOT NULL,
 CHECK(method<>'cash' OR received_by='driver'),CHECK(method<>'transfer' OR coalesce(length(trim(reference)),0)>=3)
);
CREATE TABLE cash_handovers (
 id bigserial PRIMARY KEY,driver_id int NOT NULL REFERENCES drivers(id),warehouse_id int NOT NULL REFERENCES warehouses(id),amount bigint NOT NULL CHECK(amount>0),
 collection_ids jsonb NOT NULL,status text NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','approved','rejected')),
 note text,created_at timestamptz NOT NULL DEFAULT now(),reviewed_at timestamptz,reviewed_by bigint,review_note text
);
CREATE TABLE cash_handover_items(collection_id bigint PRIMARY KEY REFERENCES payment_collections(id),handover_id bigint NOT NULL REFERENCES cash_handovers(id));
CREATE TABLE payment_refunds (
 id bigserial PRIMARY KEY,collection_id bigint NOT NULL UNIQUE REFERENCES payment_collections(id),amount bigint NOT NULL CHECK(amount>0),
 paid_by text NOT NULL CHECK(paid_by IN ('driver','company')),method text NOT NULL CHECK(method IN ('cash','transfer')),reference text,reason text NOT NULL,
 created_by bigint NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),CHECK(method<>'transfer' OR coalesce(length(trim(reference)),0)>=3)
);
CREATE INDEX collections_driver ON payment_collections(driver_id,created_at);
CREATE INDEX handover_status ON cash_handovers(status,driver_id);
CREATE VIEW order_payments AS SELECT o.id AS order_id,o.payment_payer,c.id AS collection_id,c.amount AS paid_amount,c.method AS payment_method,c.received_by,c.driver_id AS collecting_driver,c.created_at AS paid_at,
 CASE WHEN ref.id IS NOT NULL THEN 'refunded' WHEN c.id IS NULL THEN CASE WHEN NOT o.payment_required THEN 'unverified' WHEN o.shipping_fee_vnd=0 THEN 'no_charge' ELSE 'unpaid' END ELSE 'paid' END AS payment_status,
 CASE WHEN c.id IS NULL THEN 'unpaid' WHEN ref.id IS NOT NULL THEN 'refunded' WHEN c.received_by='company' OR h.status='approved' THEN 'remitted' WHEN h.status='pending' THEN 'pending_handover' ELSE 'driver_holding' END AS remittance_status
 FROM orders o LEFT JOIN payment_collections c ON c.order_id=o.id LEFT JOIN cash_handover_items hi ON hi.collection_id=c.id LEFT JOIN cash_handovers h ON h.id=hi.handover_id LEFT JOIN payment_refunds ref ON ref.collection_id=c.id;
CREATE TRIGGER collection_immutable BEFORE UPDATE OR DELETE ON payment_collections FOR EACH ROW EXECUTE FUNCTION immutable_finance_entry();
CREATE TRIGGER refund_immutable BEFORE UPDATE OR DELETE ON payment_refunds FOR EACH ROW EXECUTE FUNCTION immutable_finance_entry();
CREATE OR REPLACE FUNCTION post_delivery_revenue(oid int) RETURNS void LANGUAGE plpgsql AS $$
DECLARE o record;s record;
BEGIN
 SELECT * INTO o FROM orders WHERE id=oid;
 IF o.stage<>'delivered' OR o.shipping_fee_vnd IS NULL OR o.shipping_fee_vnd<=0 OR o.warehouse_id IS NULL THEN RETURN;END IF;
 SELECT st.completed_at,t.driver_id,t.vehicle_id INTO s FROM trip_stops st JOIN trips t ON t.id=st.trip_id WHERE st.order_id=oid AND st.kind='delivery' AND st.state='completed' AND st.completed_at IS NOT NULL ORDER BY st.completed_at DESC,st.id DESC LIMIT 1;
 IF s.completed_at IS NULL THEN RETURN;END IF;
 INSERT INTO finance_entries(source_key,entry_date,direction,category,amount,warehouse_id,driver_id,vehicle_id,order_id,description,is_demo)
 VALUES('delivery:'||oid,s.completed_at::date,'income','delivery',o.shipping_fee_vnd,o.warehouse_id,s.driver_id,s.vehicle_id,oid,'Cước giao thành công · '||o.code,o.is_demo) ON CONFLICT(source_key) DO NOTHING;
END $$;
-- Existing known fares are imported by actual successful-delivery date, never by fabricated dates.
DO $$ DECLARE r record;BEGIN FOR r IN SELECT id FROM orders WHERE stage='delivered' AND shipping_fee_vnd>0 LOOP PERFORM post_delivery_revenue(r.id);END LOOP;END $$;
CREATE FUNCTION freeze_collected_fare() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF OLD.shipping_fee_vnd IS DISTINCT FROM NEW.shipping_fee_vnd AND EXISTS(SELECT 1 FROM payment_collections WHERE order_id=NEW.id) THEN RAISE EXCEPTION 'Đơn đã thu cước; không đổi số tiền đã thu';END IF;RETURN NEW;END $$;
CREATE TRIGGER freeze_collected_fare BEFORE UPDATE OF shipping_fee_vnd ON orders FOR EACH ROW EXECUTE FUNCTION freeze_collected_fare();
CREATE TABLE vehicle_cost_receipts (
 id bigserial PRIMARY KEY,vehicle_id int NOT NULL REFERENCES vehicles(id),driver_id int REFERENCES drivers(id),warehouse_id int NOT NULL REFERENCES warehouses(id),
 cost_date date NOT NULL,kind text NOT NULL CHECK(kind IN ('fuel','other')),amount bigint NOT NULL CHECK(amount BETWEEN 1 AND 1000000000),
 description text NOT NULL,reference text,status text NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','approved','rejected','cancelled')),
 created_by bigint NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),reviewed_by bigint,reviewed_at timestamptz,review_note text,is_demo boolean NOT NULL DEFAULT false
);
ALTER TABLE finance_entries ADD COLUMN vehicle_receipt_id bigint REFERENCES vehicle_cost_receipts(id);
ALTER TABLE finance_entries DROP CONSTRAINT finance_entries_check;
ALTER TABLE finance_entries ADD CONSTRAINT finance_entries_check CHECK(category<>'fuel' OR vehicle_receipt_id IS NOT NULL OR reverses_id IS NOT NULL OR (vehicle_id IS NOT NULL AND quantity IS NOT NULL AND unit_price IS NOT NULL AND amount=round(quantity*unit_price)));
CREATE TABLE vehicle_cost_events(id bigserial PRIMARY KEY,receipt_id bigint NOT NULL REFERENCES vehicle_cost_receipts(id),action text NOT NULL,user_id bigint NOT NULL,note text,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE demo_history_batches(id text PRIMARY KEY,from_date date NOT NULL,to_date date NOT NULL,metadata jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
