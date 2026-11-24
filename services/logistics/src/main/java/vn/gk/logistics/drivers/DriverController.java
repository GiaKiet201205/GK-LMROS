package vn.gk.logistics.drivers;

import vn.gk.logistics.dispatch.RoutingService;
import vn.gk.logistics.dispatch.WorkDayPolicy;
import java.time.*;
import vn.gk.logistics.infrastructure.Events;

import org.springframework.http.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@RestController
@RequestMapping("/api/driver")
public class DriverController {
    private final JdbcTemplate jdbc;
    private final Events events;
    private final RoutingService routing;
    private final WorkDayPolicy policy;
    public DriverController(JdbcTemplate jdbc, Events events, RoutingService routing,WorkDayPolicy policy) {
        this.jdbc=jdbc;this.events=events;this.routing=routing;this.policy=policy;
    }
    @GetMapping("/today")
    public ResponseEntity<Map<String,Object>> ownToday(HttpServletRequest request) {
        var user=(Map<?,?>)request.getAttribute("user");
        if(!"driver".equals(user.get("role"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Dùng tài khoản tài xế");
        var vehicles=jdbc.queryForList("SELECT v.code FROM drivers d JOIN vehicles v ON v.id=coalesce((SELECT t.vehicle_id FROM trips t WHERE t.driver_id=d.id AND t.status<>'completed' AND (t.planned_start::date<=? OR t.status='in_progress') ORDER BY CASE t.status WHEN 'in_progress' THEN 0 ELSE 1 END,t.planned_start LIMIT 1),d.vehicle_id) WHERE d.id=?",String.class,policy.dispatchDate(),user.get("driver_id"));
        if(vehicles.isEmpty()) {
            var profiles=jdbc.queryForList("SELECT d.id,d.code,d.name,d.phone,w.name AS warehouse,gk_driver_state(d.id,?) AS status,s.working AS registered_working,to_char(s.shift_start,'HH24:MI') AS shift_start,to_char(s.shift_end,'HH24:MI') AS shift_end FROM drivers d JOIN warehouses w ON w.id=d.warehouse_id LEFT JOIN driver_work_days s ON s.driver_id=d.id AND s.work_date=? WHERE d.id=?",policy.dispatchDate(),policy.dispatchDate(),user.get("driver_id"));
            var out=new LinkedHashMap<String,Object>();out.put("vehicle",null);out.put("driver",profiles.isEmpty()?null:profiles.get(0));out.put("currentTrip",null);out.put("position",null);out.put("trips",List.of());out.put("workday",policy.context());return ResponseEntity.ok(out);
        }
        return today(vehicles.get(0),request);
    }
    @GetMapping("/{vehicleCode}/today")
    public ResponseEntity<Map<String,Object>> today(@PathVariable String vehicleCode,HttpServletRequest request) {
        var user=(Map<?,?>)request.getAttribute("user");boolean driver="driver".equals(user.get("role"));
        var vehicles=jdbc.queryForList("SELECT v.id,v.code,v.plate,v.capacity_kg,v.capacity_m3,v.status AS physical_status,(SELECT m.state FROM vehicle_maintenance m WHERE m.vehicle_id=v.id AND m.state<>'completed') AS maintenance_state,w.name AS warehouse,w.lat,w.lng FROM vehicles v JOIN warehouses w ON w.id=v.warehouse_id WHERE v.code=?",vehicleCode);
        if(vehicles.isEmpty()) return ResponseEntity.notFound().build();int vid=((Number)vehicles.get(0).get("id")).intValue();
        var args=new ArrayList<Object>(List.of(vid,policy.dispatchDate(),policy.today()));if(driver)args.add(user.get("driver_id"));
        var trips=jdbc.queryForList("SELECT t.id,t.driver_id,t.code,t.status,t.phase,t.route_version,t.route_updated_at,t.remaining_km,t.planned_km,t.planned_start,t.planned_end,to_char(t.planned_start,'YYYY-MM-DD') AS work_date,to_char(t.planned_start,'HH24:MI') AS start,to_char(t.planned_end,'HH24:MI') AS end_time,count(s.id) AS stops,count(s.id) FILTER(WHERE s.state IN ('completed','failed')) AS done FROM trips t LEFT JOIN trip_stops s ON s.trip_id=t.id WHERE t.vehicle_id=? AND (t.status='in_progress' OR (t.status='planned' AND t.planned_start::date<=?) OR (t.status='completed' AND t.planned_start::date=?))"+(driver?" AND t.driver_id=?":"")+" GROUP BY t.id ORDER BY CASE t.status WHEN 'in_progress' THEN 0 WHEN 'planned' THEN 1 ELSE 2 END,t.planned_start,t.id",args.toArray());
        for(var t:trips) t.put("end",t.remove("end_time"));
        Map<String,Object> current=null;
        for(var t:trips) if(!"completed".equals(t.get("status"))) {current=new LinkedHashMap<>(t);current.put("stopList",routing.stops(((Number)t.get("id")).intValue()));break;}
        var scheduleDate=current==null?policy.dispatchDate():LocalDate.parse((String)current.get("work_date"));
        var driverId=driver?user.get("driver_id"):current==null?null:current.get("driver_id");
        var profiles=jdbc.queryForList("SELECT d.id,d.code,d.name,d.phone,gk_driver_state(d.id,?) AS status,s.working AS registered_working,to_char(s.shift_start,'HH24:MI') AS shift_start,to_char(s.shift_end,'HH24:MI') AS shift_end FROM drivers d LEFT JOIN driver_work_days s ON s.driver_id=d.id AND s.work_date=? WHERE "+(driverId==null?"d.vehicle_id":"d.id")+"=? ORDER BY d.id",scheduleDate,scheduleDate,driverId==null?vid:driverId);
        if(current!=null && "planned".equals(current.get("status"))) {
            String reason=policy.startBlock(scheduleDate,((java.sql.Timestamp)current.get("planned_start")).toLocalDateTime(),current.get("planned_end")==null?null:((java.sql.Timestamp)current.get("planned_end")).toLocalDateTime());
            if(reason==null&&(profiles.isEmpty()||!Boolean.TRUE.equals(profiles.get(0).get("registered_working")))) reason="Chưa đăng ký làm vào ngày được phân chuyến.";
            current.put("can_start",reason==null);current.put("start_block_reason",reason);
        }
        var gps=jdbc.queryForList("SELECT lat,lng,time FROM gps_points WHERE vehicle_id=? AND time>now()-interval '5 minutes' ORDER BY time DESC LIMIT 1",vid);
        Map<String,Object> out=new LinkedHashMap<>();out.put("vehicle",vehicles.get(0));out.put("driver",profiles.isEmpty()?null:profiles.get(0));out.put("trips",trips);out.put("currentTrip",current);out.put("position",gps.isEmpty()?null:gps.get(0));out.put("workday",policy.context());
        return ResponseEntity.ok(out);
    }
    private void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT,message); }
    @PostMapping("/trips/{id}/start") @Transactional
    public ResponseEntity<Void> start(@PathVariable int id) {
        routing.changed();
        var trip=jdbc.queryForMap("SELECT * FROM trips WHERE id=? FOR UPDATE",id);
        if("in_progress".equals(trip.get("status"))) return ResponseEntity.noContent().build();
        if(!"planned".equals(trip.get("status"))) conflict("Chuyến đã kết thúc");
        var plannedStart=((java.sql.Timestamp)trip.get("planned_start")).toLocalDateTime();
        var plannedEnd=trip.get("planned_end")==null?null:((java.sql.Timestamp)trip.get("planned_end")).toLocalDateTime();
        String reason=policy.startBlock(plannedStart.toLocalDate(),plannedStart,plannedEnd);if(reason!=null) conflict(reason);
        if(jdbc.queryForObject("SELECT count(*) FROM driver_work_days WHERE driver_id=? AND work_date=? AND working AND shift_start='08:00' AND shift_end='20:00'",Integer.class,trip.get("driver_id"),policy.today())==0) conflict("Cần đăng ký làm ca 08:00–20:00 vào ngày xuất phát.");
        if(jdbc.queryForObject("SELECT count(*) FROM vehicles v LEFT JOIN vehicle_work_days a ON a.vehicle_id=v.id AND a.work_date=? WHERE v.id=? AND v.status<>'maintenance' AND NOT EXISTS(SELECT 1 FROM vehicle_maintenance m WHERE m.vehicle_id=v.id AND m.state<>'completed') AND coalesce(a.available,true)",Integer.class,policy.today(),trip.get("vehicle_id"))==0) conflict("Xe đang bận hoặc bảo trì");
        if(jdbc.queryForObject("SELECT count(*) FROM trips WHERE vehicle_id=? AND status='in_progress' AND id<>?",Integer.class,trip.get("vehicle_id"),id)>0) conflict("Xe cần hoàn tất chuyến hiện tại và về kho trước");
        if(jdbc.queryForObject("SELECT count(*) FROM trips WHERE driver_id=? AND status='in_progress' AND id<>?",Integer.class,trip.get("driver_id"),id)>0) conflict("Tài xế cần hoàn tất chuyến hiện tại trước");
        if("pickup".equals(trip.get("phase")))jdbc.update("UPDATE orders SET stage='picking_up' WHERE stage='pickup_assigned' AND id IN(SELECT order_id FROM trip_stops WHERE trip_id=? AND kind='pickup')",id);
        if("return".equals(trip.get("phase"))) {
            if(jdbc.queryForObject("SELECT count(*) FROM trip_stops s JOIN orders o ON o.id=s.order_id WHERE s.trip_id=? AND s.kind='return' AND (NOT o.in_warehouse OR o.stage<>'return_assigned')",Integer.class,id)>0)conflict("Hàng hoàn phải ở kho và đã được phân hoàn");
            jdbc.update("UPDATE orders SET stage='returning',status='delivering',in_warehouse=false WHERE id IN(SELECT order_id FROM trip_stops WHERE trip_id=? AND kind='return')",id);
        }
        if("delivery".equals(trip.get("phase"))) {
            if(jdbc.queryForObject("SELECT count(*) FROM trip_stops s JOIN orders o ON o.id=s.order_id WHERE s.trip_id=? AND s.kind='delivery' AND s.state='pending' AND NOT o.in_warehouse",Integer.class,id)>0) conflict("Hàng phải được xác nhận về kho trước khi xuất giao");
            jdbc.update("UPDATE orders SET stage='out_for_delivery',status='delivering',in_warehouse=false WHERE id IN (SELECT order_id FROM trip_stops WHERE trip_id=? AND kind='delivery' AND state='pending')",id);
        }
        var actualStart=policy.now().toLocalDateTime();
        jdbc.update("UPDATE trips SET status='in_progress',planned_start=?,planned_end=? WHERE id=?",actualStart,actualStart.plus(Duration.between(plannedStart,plannedEnd)),id);
        jdbc.update("UPDATE vehicles SET status='active' WHERE id=?",trip.get("vehicle_id"));
        jdbc.update("UPDATE drivers SET status='on_duty' WHERE id=?",trip.get("driver_id"));
        return ResponseEntity.noContent().build();
    }
    private Map<String,Object> next(int stopId) {
        var stop=jdbc.queryForMap("SELECT s.*,t.status AS trip_status,t.vehicle_id FROM trip_stops s JOIN trips t ON t.id=s.trip_id WHERE s.id=?",stopId);
        if(Set.of("completed","failed").contains(stop.get("state"))) return stop;
        if(!"in_progress".equals(stop.get("trip_status"))) conflict("Hãy bắt đầu chuyến trước khi cập nhật điểm dừng");
        var first=jdbc.queryForList("SELECT id FROM trip_stops WHERE trip_id=? AND state IN ('pending','en_route') ORDER BY seq LIMIT 1",Integer.class,stop.get("trip_id"));
        if(first.isEmpty() || first.get(0)!=stopId) conflict("Hãy xử lý điểm đang đi trước; tuyến có thể đã được cập nhật");
        return stop;
    }
    @PostMapping("/stops/{id}/depart") @Transactional
    public ResponseEntity<Void> depart(@PathVariable int id) {
        routing.changed();var stop=next(id);
        if("pending".equals(stop.get("state"))) jdbc.update("UPDATE trip_stops SET state='en_route',departed_at=now() WHERE id=?",id);
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/stops/{id}/complete") @Transactional
    public ResponseEntity<Void> complete(@PathVariable int id) {
        routing.changed();var stop=next(id);
        if("completed".equals(stop.get("state"))) return ResponseEntity.noContent().build();
        if("failed".equals(stop.get("state"))) conflict("Điểm đã được ghi nhận thất bại");
        if(stop.get("order_id")!=null) {
            var payment=jdbc.queryForMap("SELECT o.payment_required,o.payment_payer,o.shipping_fee_vnd,p.payment_status FROM orders o JOIN order_payments p ON p.order_id=o.id WHERE o.id=?",stop.get("order_id"));
            if(Boolean.TRUE.equals(payment.get("payment_required"))&&("delivery".equals(stop.get("kind"))||"pickup".equals(stop.get("kind"))&&"sender".equals(payment.get("payment_payer")))&&!Set.of("paid","no_charge").contains(payment.get("payment_status"))&&jdbc.queryForObject("SELECT count(*) FROM payment_decisions WHERE stop_id=?",Integer.class,id)==0)conflict("Hãy chọn đã thanh toán hoặc chưa thanh toán trước khi hoàn tất.");
        }
        switch((String)stop.get("kind")) {
            case "pickup" -> jdbc.update("UPDATE orders SET stage='picked_up',status='assigned',in_warehouse=false WHERE id=?",stop.get("order_id"));
            case "delivery" -> jdbc.update("UPDATE orders SET stage='delivered',status='delivered',in_warehouse=false WHERE id=?",stop.get("order_id"));
            case "return" -> jdbc.update("UPDATE orders SET stage='returned',status='failed',in_warehouse=false WHERE id=?",stop.get("order_id"));
            case "warehouse" -> {
                jdbc.update("UPDATE orders SET stage='at_warehouse',status='pending',in_warehouse=true WHERE stage='picked_up' AND id IN (SELECT order_id FROM trip_stops WHERE trip_id=? AND kind='pickup' AND state='completed')",stop.get("trip_id"));
                jdbc.update("UPDATE orders SET in_warehouse=true WHERE id IN (SELECT order_id FROM trip_stops WHERE trip_id=? AND kind='delivery' AND state='failed')",stop.get("trip_id"));
                jdbc.update("UPDATE orders SET stage='return_pending',in_warehouse=true WHERE stage='return_failed' AND id IN(SELECT order_id FROM trip_stops WHERE trip_id=? AND kind='return' AND state='failed')",stop.get("trip_id"));
                jdbc.update("INSERT INTO customer_tracking_events(order_id,type) SELECT o.id,CASE WHEN o.delivery_cancelled_at IS NULL THEN 'failed_at_warehouse' ELSE 'return_at_warehouse' END FROM orders o JOIN trip_stops s ON s.order_id=o.id WHERE s.trip_id=? AND s.kind='delivery' AND s.state='failed'",stop.get("trip_id"));
                jdbc.update("UPDATE trips SET status='completed',remaining_km=0 WHERE id=?",stop.get("trip_id"));
                jdbc.update("UPDATE vehicles SET status=CASE WHEN EXISTS(SELECT 1 FROM vehicle_maintenance m WHERE m.vehicle_id=vehicles.id AND m.state<>'completed') THEN 'maintenance' ELSE 'available' END WHERE id=?",stop.get("vehicle_id"));
                jdbc.update("UPDATE drivers SET status='off' WHERE id IN (SELECT driver_id FROM trips WHERE id=?) AND NOT EXISTS(SELECT 1 FROM trips t WHERE t.driver_id=drivers.id AND t.status='in_progress')",stop.get("trip_id"));
            }
        }
        jdbc.update("UPDATE trip_stops SET state='completed',arrived_at=coalesce(arrived_at,now()),completed_at=now() WHERE id=?",id);
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/stops/{id}/fail") @Transactional
    public ResponseEntity<Void> fail(@PathVariable int id,@RequestBody Map<String,Object> body) {
        String reason=Objects.toString(body.get("reason"),"").strip(),note=Objects.toString(body.get("note"),"").strip();
        if(reason.isBlank()||reason.length()>200||note.length()>1000)throw new IllegalArgumentException("Nhập lý do từ 1 đến 200 ký tự; ghi chú tối đa 1000 ký tự.");
        routing.changed();var stop=next(id);
        if("failed".equals(stop.get("state"))) return ResponseEntity.noContent().build();
        if("completed".equals(stop.get("state")) || "warehouse".equals(stop.get("kind"))) conflict("Không thể báo thất bại cho điểm này");
        jdbc.update("UPDATE orders SET stage=?,status='failed' WHERE id=?","return".equals(stop.get("kind"))?"return_failed":"failed",stop.get("order_id"));
        jdbc.update("UPDATE trip_stops SET state='failed',completed_at=now(),fail_reason=?,fail_note=? WHERE id=?",reason,note,id);
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/trips/{id}/finish") @Transactional
    public ResponseEntity<Void> finish(@PathVariable int id) {
        routing.changed();
        var trip=jdbc.queryForMap("SELECT status FROM trips WHERE id=? FOR UPDATE",id);
        if("completed".equals(trip.get("status"))) return ResponseEntity.noContent().build();
        if(!"in_progress".equals(trip.get("status"))) conflict("Chuyến chưa xuất phát.");
        if(jdbc.queryForObject("SELECT count(*) FROM trip_stops WHERE trip_id=? AND kind='warehouse' AND state='completed'",Integer.class,id)==0) conflict("Cần xác nhận điểm về kho trước khi kết thúc chuyến.");
        if(jdbc.queryForObject("SELECT count(*) FROM trip_stops WHERE trip_id=? AND state IN ('pending','en_route')",Integer.class,id)>0) conflict("Cần hoàn tất các điểm và xác nhận về kho");
        jdbc.update("UPDATE trips SET status='completed' WHERE id=?",id);
        return ResponseEntity.noContent().build();
    }
    /** Nhận một hoặc nhiều điểm GPS (app gửi gộp khi vừa có lại kết nối). */
    @PostMapping("/gps")
    @Transactional
    public ResponseEntity<Map<String, Object>> gps(@RequestBody List<Map<String, Object>> points, HttpServletRequest request) {
        var user = (Map<?, ?>) request.getAttribute("user");
        if(points==null||points.isEmpty()||points.size()>1000) throw new IllegalArgumentException("Tối đa 1000 điểm GPS mỗi lần gửi.");
        int n = 0;
        for (Map<String, Object> p : points) {
            if(p==null||!(p.get("vehicleCode") instanceof String codeValue)||codeValue.isBlank()||!(p.get("lat") instanceof Number)||!(p.get("lng") instanceof Number))throw new IllegalArgumentException("Thiếu mã xe hoặc tọa độ GPS.");
            if(p.get("speed")!=null&&(!(p.get("speed") instanceof Number speedValue)||!Double.isFinite(speedValue.doubleValue())||speedValue.doubleValue()<0||speedValue.doubleValue()>250))throw new IllegalArgumentException("Tốc độ GPS không hợp lệ.");
            if(p.get("ts")!=null&&(!(p.get("ts") instanceof Number timestamp)||!Double.isFinite(timestamp.doubleValue())||timestamp.doubleValue()<=0||timestamp.doubleValue()!=timestamp.longValue()))throw new IllegalArgumentException("Thời gian GPS không hợp lệ.");
            List<Integer> ids = jdbc.queryForList("SELECT id FROM vehicles WHERE code = ?", Integer.class, p.get("vehicleCode"));
            if(ids.isEmpty()) {if("driver".equals(user.get("role")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Xe không thuộc chuyến được giao.");continue;}
            double lat = ((Number) p.get("lat")).doubleValue(), lng = ((Number) p.get("lng")).doubleValue();
            if (!Double.isFinite(lat) || !Double.isFinite(lng) || Math.abs(lat)>90 || Math.abs(lng)>180) throw new IllegalArgumentException("Tọa độ GPS không hợp lệ");
            double speed = p.get("speed") instanceof Number s ? s.doubleValue() : 0;
            long ts = p.get("ts") instanceof Number t ? t.longValue() : System.currentTimeMillis();
            if (ts>System.currentTimeMillis()+60000) throw new IllegalArgumentException("Thời gian GPS không hợp lệ");
            if("driver".equals(user.get("role")) && jdbc.queryForObject("SELECT count(*) FROM trips t WHERE t.driver_id=? AND t.vehicle_id=? AND t.status IN ('in_progress','completed') AND (t.planned_start AT TIME ZONE 'Asia/Ho_Chi_Minh')<=to_timestamp(?/1000.0) AND (t.status='in_progress' OR EXISTS(SELECT 1 FROM trip_stops s WHERE s.trip_id=t.id AND s.kind='warehouse' AND s.completed_at AT TIME ZONE 'Asia/Ho_Chi_Minh'>=to_timestamp(?/1000.0)))",Integer.class,user.get("driver_id"),ids.get(0),ts,ts)==0) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Điểm GPS không thuộc chuyến bạn đã xuất phát tại thời điểm ghi nhận.");
            jdbc.update("INSERT INTO gps_points(time, vehicle_id, lat, lng, speed_kmh) VALUES (to_timestamp(?/1000.0), ?, ?, ?, ?)",
                    ts, ids.get(0), lat, lng, speed);
            String code = String.valueOf(p.get("vehicleCode"));
            String json = String.format(Locale.ROOT, "{\"vehicle\":\"%s\",\"lat\":%f,\"lng\":%f,\"speed\":%f,\"ts\":%d}", code, lat, lng, speed, ts);
            events.lastPosition(code, json);
            events.publish("gps", code, json);
            n++;
        }
        return ResponseEntity.ok(Map.of("saved", n));
    }
}
