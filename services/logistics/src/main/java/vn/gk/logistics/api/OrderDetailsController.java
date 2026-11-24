package vn.gk.logistics.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

@RestController
public class OrderDetailsController {
 private final JdbcTemplate jdbc;
 public OrderDetailsController(JdbcTemplate jdbc) { this.jdbc=jdbc; }
 @GetMapping("/api/orders/{id}")
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public Map<String,Object> detail(@PathVariable int id) {
  var rows=jdbc.queryForList("SELECT o.*,pay.payment_status,pay.paid_amount,pay.payment_method,pay.remittance_status,w.name AS warehouse,w.code AS warehouse_code FROM orders o JOIN order_payments pay ON pay.order_id=o.id LEFT JOIN warehouses w ON w.id=o.warehouse_id WHERE o.id=?",id);
  if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy đơn hàng");
  var result=new LinkedHashMap<String,Object>(rows.get(0));result.remove("geom");
  result.put("assignments",jdbc.queryForList("SELECT s.id AS stop_id,s.kind,s.seq,s.state,s.eta,s.arrived_at,s.completed_at,s.fail_reason,s.fail_note,t.id AS trip_id,t.code AS trip_code,t.status AS trip_status,t.phase,t.planned_start,t.planned_end,d.id AS driver_id,d.code AS driver_code,d.name AS driver_name,d.phone AS driver_phone,v.id AS vehicle_id,v.code AS vehicle_code,v.plate FROM trip_stops s JOIN trips t ON t.id=s.trip_id LEFT JOIN drivers d ON d.id=t.driver_id JOIN vehicles v ON v.id=t.vehicle_id WHERE s.order_id=? ORDER BY s.id DESC",id));
  result.put("events",jdbc.queryForList("SELECT * FROM customer_tracking_events WHERE order_id=? ORDER BY created_at,id",id));
  result.put("notifications",jdbc.queryForMap("SELECT EXISTS(SELECT 1 FROM dispatcher_notifications n WHERE n.source_key='order-event:'||e.id OR (e.type='created' AND n.source_key='order:'||e.order_id)) AS admin,(SELECT count(*) FROM driver_notifications n WHERE n.source_key='order-event:'||e.id) AS driver_count,(SELECT count(*) FROM driver_notifications n WHERE n.source_key='order-event:'||e.id AND n.read_at IS NOT NULL) AS driver_read,EXISTS(SELECT 1 FROM customer_event_receipts r WHERE r.event_id=e.id) AS customer_saved,EXISTS(SELECT 1 FROM customer_event_receipts r WHERE r.event_id=e.id AND r.read_at IS NOT NULL) AS customer_read FROM (SELECT id,type,order_id FROM customer_tracking_events WHERE order_id=? ORDER BY created_at DESC,id DESC LIMIT 1) e",id));
  return result;
 }
}
