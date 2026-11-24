-- Keep historical completions; new repair submissions require dispatcher review.
ALTER TABLE vehicle_maintenance DROP CONSTRAINT vehicle_maintenance_state_check;
ALTER TABLE vehicle_maintenance ADD CONSTRAINT vehicle_maintenance_state_check CHECK(state IN ('awaiting_return','open','pending_approval','completed'));
ALTER TABLE vehicle_maintenance
 ADD COLUMN repair_note text,
 ADD COLUMN repair_shop text,
 ADD COLUMN repair_cost numeric(14,2) CHECK(repair_cost>=0),
 ADD COLUMN receipt_reference text,
 ADD COLUMN submitted_at timestamptz,
 ADD COLUMN submitted_by_driver_id int REFERENCES drivers(id),
 ADD COLUMN reviewed_at timestamptz,
 ADD COLUMN reviewed_by_user_id bigint,
 ADD COLUMN reviewed_by_name text,
 ADD COLUMN review_note text,
 ADD CONSTRAINT maintenance_submission_required CHECK(state<>'pending_approval' OR (repair_note IS NOT NULL AND length(trim(repair_note)) BETWEEN 1 AND 1000 AND submitted_at IS NOT NULL AND submitted_by_driver_id IS NOT NULL)),
 ADD CONSTRAINT maintenance_approval_required CHECK(state<>'completed' OR submitted_at IS NULL OR (reviewed_at IS NOT NULL AND reviewed_by_user_id IS NOT NULL));
ALTER TABLE vehicle_maintenance_events ADD COLUMN details jsonb NOT NULL DEFAULT '{}'::jsonb;
CREATE OR REPLACE FUNCTION notify_vehicle_maintenance() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v record;kind text;heading text;source text;description text;
BEGIN
 SELECT * INTO v FROM vehicles WHERE id=NEW.vehicle_id;
 IF TG_OP='INSERT' THEN kind:='maintenance_reported';heading:=CASE NEW.state WHEN 'awaiting_return' THEN 'Xe đã báo sự cố, chờ bàn giao hàng' ELSE 'Tài xế đã lập phiếu bảo dưỡng' END;
 ELSIF OLD.state IS DISTINCT FROM NEW.state THEN
  kind:=CASE NEW.state WHEN 'pending_approval' THEN 'maintenance_submitted' WHEN 'completed' THEN 'maintenance_approved' ELSE CASE OLD.state WHEN 'pending_approval' THEN 'maintenance_rejected' ELSE 'maintenance_started' END END;
  heading:=CASE kind WHEN 'maintenance_submitted' THEN 'Phiếu sửa xe đang chờ duyệt' WHEN 'maintenance_approved' THEN 'Đã duyệt phiếu sửa xe' WHEN 'maintenance_rejected' THEN 'Phiếu sửa xe cần bổ sung' ELSE 'Xe đã về kho và bắt đầu bảo dưỡng' END;
 ELSIF OLD.expected_ready_at IS DISTINCT FROM NEW.expected_ready_at THEN kind:='maintenance_estimate';heading:='Đã cập nhật lịch bảo dưỡng';
 ELSE RETURN NEW; END IF;
 description:='BD-'||lpad(NEW.id::text,6,'0')||' · '||v.code||' · '||v.plate||' · '||CASE WHEN kind='maintenance_rejected' THEN NEW.review_note WHEN NEW.state IN ('pending_approval','completed') THEN NEW.repair_note ELSE NEW.reason END;
 source:='maintenance:'||NEW.id||':'||kind||':'||nextval('dispatcher_notifications_id_seq');
 INSERT INTO dispatcher_notifications(source_key,type,level,title,message,href,warehouse_id)
 VALUES(source,kind,CASE NEW.state WHEN 'completed' THEN 'success' ELSE 'warning' END,heading,description,'/maintenance',v.warehouse_id);
 INSERT INTO driver_notifications(driver_id,source_key,type,title,message)
 SELECT d.id,source||':driver:'||d.id,kind,heading,description FROM drivers d
 WHERE d.vehicle_id=NEW.vehicle_id OR d.id IN (NEW.reported_by_driver_id,NEW.submitted_by_driver_id) OR EXISTS(SELECT 1 FROM trips t WHERE t.driver_id=d.id AND t.vehicle_id=NEW.vehicle_id AND t.status<>'completed');
 INSERT INTO vehicle_maintenance_events(maintenance_id,action,driver_id,user_id,note,details)
 VALUES(NEW.id,kind,NEW.updated_by_driver_id,NEW.updated_by_user_id,description,jsonb_build_object('state',NEW.state,'repair_note',NEW.repair_note,'repair_shop',NEW.repair_shop,'repair_cost',NEW.repair_cost,'receipt_reference',NEW.receipt_reference,'submitted_at',NEW.submitted_at,'reviewed_at',NEW.reviewed_at,'reviewed_by_name',NEW.reviewed_by_name,'review_note',NEW.review_note));
 RETURN NEW;
END $$;
