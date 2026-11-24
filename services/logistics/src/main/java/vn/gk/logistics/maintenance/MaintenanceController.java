package vn.gk.logistics.maintenance;

import vn.gk.logistics.dispatch.DistributionController;
import vn.gk.logistics.dispatch.RoutingService;
import vn.gk.logistics.dispatch.WorkDayPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.util.*;

@RestController
public class MaintenanceController {
 private final JdbcTemplate jdbc;private final RoutingService routing;private final DistributionController distribution;private final WorkDayPolicy policy;
 public MaintenanceController(JdbcTemplate jdbc,RoutingService routing,DistributionController distribution,WorkDayPolicy policy) {this.jdbc=jdbc;this.routing=routing;this.distribution=distribution;this.policy=policy;}
 private Map<?,?> user(HttpServletRequest req) { return (Map<?,?>)req.getAttribute("user"); }
 private Integer driver(HttpServletRequest req) {var u=user(req);return "driver".equals(u.get("role"))?((Number)u.get("driver_id")).intValue():null;}
 private int requireDriver(HttpServletRequest req) {var did=driver(req);if(did==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Phiếu sửa xe do tài xế lập và gửi duyệt.");return did;}
 private void requireAdmin(HttpServletRequest req) {if(!"dispatcher".equals(user(req).get("role")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Chỉ điều phối viên được duyệt phiếu sửa xe.");}
 private String optional(String value,int max) {if(value==null||value.isBlank())return null;var text=value.trim();if(text.length()>max)throw new IllegalArgumentException("Thông tin quá dài (tối đa "+max+" ký tự).");return text;}
 private void access(int vid,HttpServletRequest req) {
  var did=driver(req);if(did==null) return;
  if(jdbc.queryForObject("SELECT count(*) FROM vehicles v WHERE v.id=? AND (EXISTS(SELECT 1 FROM drivers d WHERE d.id=? AND d.vehicle_id=v.id) OR EXISTS(SELECT 1 FROM trips t WHERE t.driver_id=? AND t.vehicle_id=v.id AND t.status<>'completed') OR EXISTS(SELECT 1 FROM vehicle_maintenance m WHERE m.vehicle_id=v.id AND m.reported_by_driver_id=? AND m.state<>'completed'))",Integer.class,vid,did,did,did)==0)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Bạn chỉ được cập nhật bảo dưỡng xe được giao cho mình.");
 }
 private String required(String text) {var value=Objects.toString(text,"").trim();if(value.isEmpty()||value.length()>1000)throw new IllegalArgumentException("Điền nội dung từ 1 đến 1000 ký tự.");return value;}
 private java.sql.Timestamp expected(String value) {if(value==null||value.isBlank())return null;var instant=LocalDateTime.parse(value).atZone(WorkDayPolicy.ZONE).toInstant();if(!instant.isAfter(policy.now().toInstant())||instant.isAfter(policy.now().plusDays(90).toInstant()))throw new IllegalArgumentException("Chọn thời gian dự kiến trong 90 ngày tới.");return java.sql.Timestamp.from(instant);}
 private String vehiclesSql() {return "SELECT v.id,v.code,v.plate,v.warehouse_id,w.name AS warehouse,v.status AS physical_status,gk_vehicle_state(v.id,gk_dispatch_date()) AS status,(SELECT count(*) FROM trips t WHERE t.vehicle_id=v.id AND t.status='in_progress') AS active_trips,(SELECT count(*) FROM trips t WHERE t.vehicle_id=v.id AND t.status='planned') AS planned_trips,(SELECT count(*) FROM trip_stops s JOIN trips t ON t.id=s.trip_id WHERE t.vehicle_id=v.id AND t.status='planned' AND s.kind<>'warehouse') AS planned_orders FROM vehicles v JOIN warehouses w ON w.id=v.warehouse_id";}
 @GetMapping({"/api/maintenance","/api/driver/maintenance"})
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public Map<String,Object> list(HttpServletRequest req) {
  var did=driver(req);var args=new ArrayList<Object>();var filter="";
  if(did!=null){filter=" WHERE EXISTS(SELECT 1 FROM drivers d WHERE d.id=? AND d.vehicle_id=v.id) OR EXISTS(SELECT 1 FROM trips t WHERE t.driver_id=? AND t.vehicle_id=v.id AND t.status<>'completed') OR EXISTS(SELECT 1 FROM vehicle_maintenance m WHERE m.vehicle_id=v.id AND m.reported_by_driver_id=? AND m.state<>'completed')";args.addAll(List.of(did,did,did));}
  var vehicles=jdbc.queryForList(vehiclesSql()+filter+" ORDER BY v.id",args.toArray());
  var jobs=jdbc.queryForList("SELECT m.*,(SELECT f.id FROM finance_entries f WHERE f.source_key='maintenance:'||m.id) AS finance_entry_id,'BD-'||lpad(m.id::text,6,'0') AS code,v.code AS vehicle,v.plate,w.name AS warehouse,d.name AS reported_by_name,sd.name AS submitted_by_name,(m.expected_ready_at<now() AND m.state IN ('open','awaiting_return')) AS overdue FROM vehicle_maintenance m JOIN vehicles v ON v.id=m.vehicle_id JOIN warehouses w ON w.id=v.warehouse_id LEFT JOIN drivers d ON d.id=m.reported_by_driver_id LEFT JOIN drivers sd ON sd.id=m.submitted_by_driver_id"+(did==null?"":" WHERE m.reported_by_driver_id=? OR m.submitted_by_driver_id=? OR EXISTS(SELECT 1 FROM drivers d WHERE d.id=? AND d.vehicle_id=m.vehicle_id)")+" ORDER BY CASE m.state WHEN 'pending_approval' THEN 0 WHEN 'open' THEN 1 WHEN 'awaiting_return' THEN 2 ELSE 3 END,m.id DESC LIMIT 100",did==null?new Object[]{}:new Object[]{did,did,did});
  return Map.of("vehicles",vehicles,"jobs",jobs,"workday",policy.context());
 }
 public record Report(int vehicleId,String reason,String expectedReadyAt,Boolean releasePlanned) {}
 @PostMapping({"/api/maintenance","/api/driver/maintenance"}) @Transactional
 public Map<String,Object> report(@RequestBody Report body,HttpServletRequest req) {
  requireDriver(req);if(body.vehicleId()<=0)throw new IllegalArgumentException("Chọn xe bảo dưỡng.");routing.changed();access(body.vehicleId(),req);String reason=required(body.reason());var eta=expected(body.expectedReadyAt());
  jdbc.queryForMap("SELECT id FROM vehicles WHERE id=? FOR UPDATE",body.vehicleId());
  var existing=jdbc.queryForList("SELECT * FROM vehicle_maintenance WHERE vehicle_id=? AND state<>'completed'",body.vehicleId());if(!existing.isEmpty())return existing.get(0);
  var planned=jdbc.queryForList("SELECT id FROM trips WHERE vehicle_id=? AND status='planned' ORDER BY id",Integer.class,body.vehicleId());
  if(!planned.isEmpty()&&!Boolean.TRUE.equals(body.releasePlanned()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Xe có "+planned.size()+" chuyến chưa xuất phát. Xác nhận trả các đơn về hàng chờ trước khi báo bảo dưỡng.");
  for(var id:planned) distribution.release(id);
  boolean active=jdbc.queryForObject("SELECT count(*) FROM trips WHERE vehicle_id=? AND status='in_progress'",Integer.class,body.vehicleId())>0;
  var did=driver(req);var uid=((Number)user(req).get("id")).longValue();
  Long id=jdbc.queryForObject("INSERT INTO vehicle_maintenance(vehicle_id,state,reason,expected_ready_at,reported_by_driver_id,updated_by_driver_id,updated_by_user_id) VALUES(?,?,?,?,?,?,?) RETURNING id",Long.class,body.vehicleId(),active?"awaiting_return":"open",reason,eta,did,did,uid);
  if(!active)jdbc.update("UPDATE vehicles SET status='maintenance' WHERE id=?",body.vehicleId());
  var result=new LinkedHashMap<String,Object>(jdbc.queryForMap("SELECT * FROM vehicle_maintenance WHERE id=?",id));result.put("released_trips",planned.size());return result;
 }
 public record Estimate(String expectedReadyAt) {}
 @PostMapping({"/api/maintenance/{id}/estimate","/api/driver/maintenance/{id}/estimate"}) @Transactional
 public Map<String,Object> estimate(@PathVariable long id,@RequestBody Estimate body,HttpServletRequest req) {
  requireDriver(req);if(body.expectedReadyAt()==null||body.expectedReadyAt().isBlank())throw new IllegalArgumentException("Nhập thời gian dự kiến sửa xong.");routing.changed();var job=jdbc.queryForMap("SELECT * FROM vehicle_maintenance WHERE id=? FOR UPDATE",id);access(((Number)job.get("vehicle_id")).intValue(),req);
  if(!List.of("open","awaiting_return").contains(job.get("state")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Chỉ đổi lịch khi phiếu đang bảo dưỡng.");
  jdbc.update("UPDATE vehicle_maintenance SET expected_ready_at=?,updated_by_driver_id=?,updated_by_user_id=?,updated_at=now() WHERE id=?",expected(body.expectedReadyAt()),driver(req),user(req).get("id"),id);return jdbc.queryForMap("SELECT * FROM vehicle_maintenance WHERE id=?",id);
 }
 private void validateReceipt(java.math.BigDecimal cost,String reference) {
  if(cost==null||cost.signum()<0||cost.compareTo(new java.math.BigDecimal("1000000000"))>0||cost.stripTrailingZeros().scale()>0)throw new IllegalArgumentException("Nhập chi phí sửa chữa từ 0 đến 1 tỷ đồng, không có phần lẻ.");
  if(reference==null||reference.isBlank()||reference.trim().length()>120)throw new IllegalArgumentException("Nhập mã hóa đơn hoặc số phiếu sửa chữa, tối đa 120 ký tự.");
 }
 public record Completion(String note,String repairShop,java.math.BigDecimal repairCost,String receiptReference) {
  public Completion(String note) {this(note,null,null,null);}
 }
 @PostMapping({"/api/maintenance/{id}/complete","/api/driver/maintenance/{id}/complete"}) @Transactional
 public Map<String,Object> complete(@PathVariable long id,@RequestBody Completion body,HttpServletRequest req) {
  int did=requireDriver(req);routing.changed();var job=jdbc.queryForMap("SELECT * FROM vehicle_maintenance WHERE id=? FOR UPDATE",id);int vid=((Number)job.get("vehicle_id")).intValue();
  access(vid,req);
  if(List.of("completed","pending_approval").contains(job.get("state")))return job;
  String note=required(body.note());String shop=optional(body.repairShop(),200),receipt=optional(body.receiptReference(),120);
  if(!"open".equals(job.get("state"))||jdbc.queryForObject("SELECT count(*) FROM trips WHERE vehicle_id=? AND status='in_progress'",Integer.class,vid)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Cần bàn giao hàng và xác nhận về kho trước khi gửi phiếu sửa xong.");
  validateReceipt(body.repairCost(),receipt);
  jdbc.update("UPDATE vehicle_maintenance SET state='pending_approval',repair_note=?,repair_shop=?,repair_cost=?,receipt_reference=?,submitted_at=now(),submitted_by_driver_id=?,reviewed_at=NULL,reviewed_by_user_id=NULL,reviewed_by_name=NULL,review_note=NULL,updated_by_driver_id=?,updated_by_user_id=?,updated_at=now() WHERE id=?",note,shop,body.repairCost(),receipt,did,did,user(req).get("id"),id);
  return jdbc.queryForMap("SELECT * FROM vehicle_maintenance WHERE id=?",id);
 }
 public record Review(String note) {}
 @PostMapping("/api/maintenance/{id}/approve") @Transactional
 public Map<String,Object> approve(@PathVariable long id,@RequestBody(required=false) Review body,HttpServletRequest req) {
  requireAdmin(req);routing.changed();var job=jdbc.queryForMap("SELECT * FROM vehicle_maintenance WHERE id=? FOR UPDATE",id);
  if("completed".equals(job.get("state")))return job;
  if(!"pending_approval".equals(job.get("state")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Phiếu chưa được tài xế gửi duyệt.");
  int vid=((Number)job.get("vehicle_id")).intValue();
  if(jdbc.queryForObject("SELECT count(*) FROM trips WHERE vehicle_id=? AND status='in_progress'",Integer.class,vid)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Xe còn chuyến chưa hoàn tất.");
  try {validateReceipt((java.math.BigDecimal)job.get("repair_cost"),(String)job.get("receipt_reference"));}
  catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"Phiếu thiếu chi phí hoặc mã hóa đơn hợp lệ. Yêu cầu tài xế bổ sung trước khi duyệt.");}
  jdbc.update("UPDATE vehicle_maintenance SET state='completed',completed_at=now(),completion_note=repair_note,reviewed_at=now(),reviewed_by_user_id=?,reviewed_by_name=?,review_note=?,updated_by_driver_id=NULL,updated_by_user_id=?,updated_at=now() WHERE id=?",user(req).get("id"),user(req).get("display_name"),optional(body==null?null:body.note(),1000),user(req).get("id"),id);
  jdbc.update("UPDATE vehicles SET status='available' WHERE id=?",vid);return jdbc.queryForMap("SELECT * FROM vehicle_maintenance WHERE id=?",id);
 }
 @PostMapping("/api/maintenance/{id}/reject") @Transactional
 public Map<String,Object> reject(@PathVariable long id,@RequestBody Review body,HttpServletRequest req) {
  requireAdmin(req);routing.changed();var job=jdbc.queryForMap("SELECT * FROM vehicle_maintenance WHERE id=? FOR UPDATE",id);
  if(!"pending_approval".equals(job.get("state")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Phiếu không còn chờ duyệt. Hãy tải lại.");
  jdbc.update("UPDATE vehicle_maintenance SET state='open',reviewed_at=now(),reviewed_by_user_id=?,reviewed_by_name=?,review_note=?,updated_by_driver_id=NULL,updated_by_user_id=?,updated_at=now() WHERE id=?",user(req).get("id"),user(req).get("display_name"),required(body.note()),user(req).get("id"),id);
  return jdbc.queryForMap("SELECT * FROM vehicle_maintenance WHERE id=?",id);
 }
}
