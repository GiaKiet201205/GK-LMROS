-- Historical completed repairs remain unchanged. New submissions and approvals
-- must include the amount and a repair-shop receipt/invoice reference.
CREATE FUNCTION require_maintenance_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.state='pending_approval' OR (NEW.state='completed' AND (TG_OP='INSERT' OR OLD.state IS DISTINCT FROM NEW.state)) THEN
  IF NEW.repair_cost IS NULL OR NEW.repair_cost<0 OR NEW.repair_cost>1000000000 OR NEW.repair_cost<>round(NEW.repair_cost)
   OR NEW.receipt_reference IS NULL OR length(trim(NEW.receipt_reference)) NOT BETWEEN 1 AND 120 THEN
   RAISE EXCEPTION 'Phiếu sửa xong phải có chi phí nguyên đồng từ 0 đến 1 tỷ và mã hóa đơn/phiếu sửa chữa';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER require_maintenance_receipt BEFORE INSERT OR UPDATE ON vehicle_maintenance FOR EACH ROW EXECUTE FUNCTION require_maintenance_receipt();
