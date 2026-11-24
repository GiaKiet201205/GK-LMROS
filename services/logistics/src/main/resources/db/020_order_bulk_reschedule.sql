CREATE TABLE order_reschedule_batches(id uuid PRIMARY KEY,created_by bigint NOT NULL,request jsonb NOT NULL,result jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE order_reschedule_items(batch_id uuid NOT NULL REFERENCES order_reschedule_batches(id),order_id int NOT NULL REFERENCES orders(id),previous_schedule jsonb NOT NULL,new_schedule jsonb NOT NULL,PRIMARY KEY(batch_id,order_id));
CREATE TRIGGER reschedule_batch_immutable BEFORE UPDATE OR DELETE ON order_reschedule_batches FOR EACH ROW EXECUTE FUNCTION immutable_finance_entry();
CREATE TRIGGER reschedule_item_immutable BEFORE UPDATE OR DELETE ON order_reschedule_items FOR EACH ROW EXECUTE FUNCTION immutable_finance_entry();
CREATE INDEX orders_pickup_deadline ON orders(warehouse_id,pickup_to) WHERE stage IN ('awaiting_pickup','pickup_assigned','picking_up');
CREATE INDEX orders_delivery_deadline ON orders(warehouse_id,tw_end) WHERE stage IN ('picked_up','at_warehouse','delivery_assigned','out_for_delivery','failed');
