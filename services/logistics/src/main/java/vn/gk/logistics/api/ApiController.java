package vn.gk.logistics.api;

import vn.gk.logistics.dispatch.RoutingService;
import vn.gk.logistics.dispatch.WorkDayPolicy;
import java.time.LocalDate;
import vn.gk.logistics.infrastructure.Events;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import org.springframework.transaction.annotation.Transactional;

/** API cho web điều phối: tổng quan, đơn hàng, kho, xe, tài xế, trực tiếp, báo cáo. */
@RestController
@RequestMapping("/api")
public class ApiController {
    private final JdbcTemplate jdbc;
    private final Events events;
    private final RoutingService routing;
    private final WorkDayPolicy policy;
    private final vn.gk.logistics.pricing.ShippingPricingService pricing;
    private final vn.gk.logistics.warehouses.WarehouseAssignmentService warehouseAssignment;

    public ApiController(JdbcTemplate jdbc, Events events, RoutingService routing,WorkDayPolicy policy,vn.gk.logistics.pricing.ShippingPricingService pricing,vn.gk.logistics.warehouses.WarehouseAssignmentService warehouseAssignment) {
        this.jdbc = jdbc;
        this.events = events;
        this.routing = routing;this.policy=policy;this.pricing=pricing;this.warehouseAssignment=warehouseAssignment;
    }

    private static String wf(Integer warehouse, String col) {
        return warehouse == null ? "" : " AND " + col + " = " + warehouse.intValue();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
        return Map.of("status", "ok", "application", "GK", "service", "gk-logistics", "db", one == 1);
    }

    @GetMapping("/dashboard")
    public Map<String,Object> dashboard(@RequestParam(required=false) Integer warehouse) {
        Map<String,Object> out=new LinkedHashMap<>();
        String filter=wf(warehouse,"warehouse_id");
        var workDate=policy.dispatchDate();
        out.put("workday",policy.context());
        out.put("totalOrders",jdbc.queryForObject("SELECT count(*) FROM orders WHERE 1=1"+filter,Long.class));
        out.put("dynamicOrders",jdbc.queryForObject("SELECT count(*) FROM orders WHERE is_dynamic"+filter,Long.class));
        out.put("stageCounts",jdbc.queryForList("SELECT stage,count(*) AS count FROM orders WHERE 1=1"+filter+" GROUP BY stage"));
        Map<String,Long> counts=new LinkedHashMap<>();
        for(var row:jdbc.queryForList("SELECT status,count(*) AS count FROM orders WHERE 1=1"+filter+" GROUP BY status")) counts.put((String)row.get("status"),((Number)row.get("count")).longValue());
        out.put("statusCounts",counts);
        out.put("vehicles",jdbc.queryForMap("SELECT count(*) AS total,count(*) FILTER(WHERE gk_vehicle_state(id,'"+workDate+"'::date)='active') AS active,count(*) FILTER(WHERE EXISTS(SELECT 1 FROM trips t WHERE t.vehicle_id=vehicles.id AND t.status='planned')) AS reserved,count(*) FILTER(WHERE gk_vehicle_state(id,'"+workDate+"'::date)='available' AND NOT EXISTS(SELECT 1 FROM trips t WHERE t.vehicle_id=vehicles.id AND t.status<>'completed')) AS available FROM vehicles WHERE archived_at IS NULL"+filter));
        String tripFilter=wf(warehouse,"t.warehouse_id"),orderFilter=wf(warehouse,"o.warehouse_id"),vehicleFilter=wf(warehouse,"v.warehouse_id");
        out.put("operations",jdbc.queryForMap("SELECT " +
            "(SELECT count(DISTINCT t.vehicle_id) FROM trips t WHERE t.status='in_progress'"+tripFilter+") AS active_vehicles," +
            "(SELECT count(*) FROM trips t WHERE t.status='planned'"+tripFilter+") AS planned_trips," +
            "(SELECT count(*) FROM trips t WHERE t.status='completed' AND EXISTS(SELECT 1 FROM trip_stops s WHERE s.trip_id=t.id AND s.kind='warehouse' AND s.completed_at::date=CURRENT_DATE)"+tripFilter+") AS completed_trips_today," +
            "(SELECT count(DISTINCT s.order_id) FROM trip_stops s JOIN orders o ON o.id=s.order_id WHERE s.kind='delivery' AND s.state='completed' AND s.completed_at::date=CURRENT_DATE"+orderFilter+") AS delivered_today," +
            "(SELECT count(*) FROM vehicles v WHERE v.archived_at IS NULL AND v.status='maintenance'"+vehicleFilter+") AS maintenance_vehicles," +
            "(SELECT count(*) FROM vehicles v WHERE gk_vehicle_state(v.id,'"+workDate+"'::date)='available' AND NOT EXISTS(SELECT 1 FROM trips held WHERE held.vehicle_id=v.id AND held.status<>'completed')"+vehicleFilter+") AS available_vehicles"));
        out.put("attention",jdbc.queryForMap("SELECT count(*) FILTER(WHERE stage IN ('awaiting_pickup','pickup_assigned','picking_up') AND pickup_to<now()) AS overdue_pickup,"+
            "count(*) FILTER(WHERE stage IN ('at_warehouse','delivery_assigned','out_for_delivery') AND tw_end<now()) AS overdue_delivery,count(*) FILTER(WHERE stage IN ('failed','return_failed','return_pending')) AS failed_orders FROM orders WHERE 1=1"+filter));
        var attention=(Map<String,Object>)out.get("attention");
        attention.put("unregistered_drivers",jdbc.queryForObject("SELECT count(*) FROM drivers d WHERE d.archived_at IS NULL AND NOT EXISTS(SELECT 1 FROM driver_work_days s WHERE s.driver_id=d.id AND s.work_date=CURRENT_DATE)"+wf(warehouse,"d.warehouse_id"),Long.class));
        out.put("routing",jdbc.queryForMap("SELECT dirty,last_success,last_error,coalesce(lease_until>now(),false) AS running FROM routing_state WHERE id=1"));
        out.put("warehouseSummary",jdbc.queryForList("SELECT w.id,w.code,w.name,count(o.id) FILTER(WHERE o.stage IN ('awaiting_pickup','pickup_assigned','picking_up')) AS pickup,count(o.id) FILTER(WHERE o.stage='at_warehouse') AS ready_delivery,count(o.id) FILTER(WHERE o.stage='out_for_delivery') AS delivering,"+
            "(SELECT count(DISTINCT t.vehicle_id) FROM trips t WHERE t.warehouse_id=w.id AND t.status='in_progress') AS active_vehicles FROM warehouses w LEFT JOIN orders o ON o.warehouse_id=w.id WHERE w.archived_at IS NULL"+wf(warehouse,"w.id")+" GROUP BY w.id ORDER BY w.id"));
        out.put("recentOrders",jdbc.queryForList("SELECT o.id,o.code,o.customer_name,o.pickup_name,o.stage,o.created_at,o.customer_booking_id IS NOT NULL AS customer_order,w.name AS warehouse FROM orders o LEFT JOIN warehouses w ON w.id=o.warehouse_id WHERE 1=1"+orderFilter+" ORDER BY o.created_at DESC,o.id DESC LIMIT 5"));
        out.put("updatedAt",java.time.Instant.now().toString());
        return out;
    }

    @GetMapping("/alerts")
    public List<Map<String, Object>> alerts() {
        return jdbc.queryForList("SELECT id, level, message, to_char(created_at,'HH24:MI') AS at FROM alerts WHERE NOT resolved ORDER BY id DESC LIMIT 20");
    }

    public List<Map<String,Object>> warehouses(){return warehouses(false);}
    @GetMapping("/warehouses")
    public List<Map<String, Object>> warehouses(@RequestParam(defaultValue="false") boolean includeArchived) {
        return jdbc.queryForList(
                "SELECT w.id, w.code, w.name, w.lat, w.lng, w.color,w.address,w.archived_at, " +
                "(SELECT count(*) FROM vehicles v WHERE v.warehouse_id = w.id AND v.archived_at IS NULL) AS vehicles, " +
                "(SELECT count(*) FROM orders o WHERE o.warehouse_id = w.id) AS orders " +
                "FROM warehouses w WHERE (w.archived_at IS NULL OR "+includeArchived+") ORDER BY w.id");
    }

    public List<Map<String,Object>> orders(String status,String q,String stage,Integer warehouse,int limit,int offset){return orders(status,q,stage,warehouse,limit,offset,null,false);}
    private static final Map<String,List<String>> ORDER_GROUPS=Map.of("pickup",List.of("awaiting_pickup","pickup_assigned","picking_up","picked_up"),"warehouse",List.of("at_warehouse"),"delivery",List.of("delivery_assigned","out_for_delivery"),"return",List.of("return_pending","return_assigned","returning"),"completed",List.of("delivered","returned"),"issues",List.of("failed","return_failed"));
    @GetMapping("/orders")
    public List<Map<String, Object>> orders(@RequestParam(required = false) String status,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(required = false) String stage,
                                            @RequestParam(required = false) Integer warehouse,
                                            @RequestParam(defaultValue = "100") int limit,
                                            @RequestParam(defaultValue = "0") int offset,@RequestParam(required=false) String group,@RequestParam(defaultValue="false") boolean overdue) {
        StringBuilder sql = new StringBuilder(
                "SELECT o.id, o.code, o.customer_name, o.address, o.weight_kg, o.status, o.stage, o.pickup_address, o.in_warehouse, o.is_demo,pay.payment_status,o.payment_payer,o.shipping_fee_vnd, o.delivery_failures, o.delivery_cancelled_at, o.is_dynamic,o.pickup_to,o.tw_end,d.deadline,extract(epoch FROM (d.deadline AT TIME ZONE 'Asia/Ho_Chi_Minh'))/60 AS deadline_min,coalesce(d.deadline<?::timestamp,false) AS overdue,CASE WHEN o.stage IN ('awaiting_pickup','pickup_assigned','picking_up') THEN 'pickup' ELSE 'delivery' END AS overdue_kind,(o.stage IN ('awaiting_pickup','at_warehouse') AND NOT EXISTS(SELECT 1 FROM trip_stops bs JOIN trips bt ON bt.id=bs.trip_id WHERE bs.order_id=o.id AND bt.status<>'completed')) AS can_reschedule, o.customer_booking_id IS NOT NULL AS customer_order, " +
                "to_char(o.tw_start,'HH24:MI') || '–' || to_char(o.tw_end,'HH24:MI') AS time_window, " +
                "w.name AS warehouse, t.code AS trip " +
                "FROM orders o JOIN order_payments pay ON pay.order_id=o.id LEFT JOIN warehouses w ON w.id = o.warehouse_id " +
                "LEFT JOIN LATERAL (SELECT trip_id FROM trip_stops WHERE order_id=o.id ORDER BY id DESC LIMIT 1) s ON true LEFT JOIN trips t ON t.id=s.trip_id CROSS JOIN LATERAL(SELECT CASE WHEN o.stage IN ('awaiting_pickup','pickup_assigned','picking_up') THEN o.pickup_to WHEN o.stage IN ('picked_up','at_warehouse','delivery_assigned','out_for_delivery','failed') THEN o.tw_end ELSE NULL END AS deadline)d WHERE 1=1");
        List<Object> args = new ArrayList<>();args.add(policy.now().toLocalDateTime());
        if(group!=null&&!group.isBlank()){var stages=ORDER_GROUPS.get(group);if(stages==null)throw new IllegalArgumentException("Nhóm trạng thái không hợp lệ");sql.append(" AND o.stage IN ("+String.join(",",Collections.nCopies(stages.size(),"?"))+")");args.addAll(stages);}
        if(overdue){sql.append(" AND d.deadline<?::timestamp");args.add(policy.now().toLocalDateTime());}
        if (stage != null && !stage.isBlank()) { sql.append(" AND o.stage = ?"); args.add(stage); }
        if (status != null && !status.isBlank()) { sql.append(" AND o.status = ?"); args.add(status); }
        if (warehouse != null) { sql.append(" AND o.warehouse_id = ?"); args.add(warehouse); }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (o.code ILIKE ? OR o.customer_name ILIKE ? OR o.address ILIKE ? OR o.pickup_name ILIKE ? OR o.pickup_address ILIKE ?)");
            String like = "%" + q.trim() + "%";
            args.add(like); args.add(like); args.add(like);args.add(like);args.add(like);
        }
        sql.append(" ORDER BY o.id LIMIT ? OFFSET ?");
        args.add(Math.min(Math.max(limit, 1), 500));
        args.add(Math.max(offset, 0));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    private static String required(Map<String,Object> body,String key) {
        String value=Objects.toString(body.get(key), "").trim();
        if(value.isEmpty()) throw new IllegalArgumentException("Thiếu thông tin: "+key);
        return value;
    }
    private static double coordinate(Map<String,Object> body,String key,double maximum) {
        if(!(body.get(key) instanceof Number n) || !Double.isFinite(n.doubleValue()) || Math.abs(n.doubleValue())>maximum)
            throw new IllegalArgumentException("Tọa độ không hợp lệ: "+key);
        return n.doubleValue();
    }
    @PostMapping("/orders") @Transactional
    public Map<String,Object> createOrder(@RequestBody Map<String,Object> body) {
        body=vn.gk.logistics.validation.OrderInputs.validate(body,policy.now().toLocalDateTime());
        String description=required(body,"goodsDescription");if(description.length()>500)throw new IllegalArgumentException("Mô tả hàng tối đa 500 ký tự");
        String goodsType=Objects.toString(body.get("goodsType"),"other");if(!List.of("documents","food","clothing","electronics","fragile","other").contains(goodsType))throw new IllegalArgumentException("Loại hàng không hợp lệ");
        var quote=pricing.forOrder(body);
        routing.changed();
        double lat=coordinate(body,"lat",90),lng=coordinate(body,"lng",180);
        double pickupLat=coordinate(body,"pickupLat",90),pickupLng=coordinate(body,"pickupLng",180);
        double kg=body.get("weightKg") instanceof Number n?n.doubleValue():0;
        double volume=body.get("volumeM3") instanceof Number n?n.doubleValue():kg*.0067;
        if(!Double.isFinite(kg)||!Double.isFinite(volume)||kg<=0||volume<=0) throw new IllegalArgumentException("Khối lượng và thể tích phải lớn hơn 0");
        var pickupFrom=java.time.LocalDateTime.parse(required(body,"pickupFrom"));
        var pickupTo=java.time.LocalDateTime.parse(required(body,"pickupTo"));
        var deliveryFrom=java.time.LocalDateTime.parse(required(body,"deliveryFrom"));
        var deliveryTo=java.time.LocalDateTime.parse(required(body,"deliveryTo"));
        if(!pickupFrom.isBefore(pickupTo)||!deliveryFrom.isBefore(deliveryTo)||!pickupFrom.isBefore(deliveryTo)) throw new IllegalArgumentException("Khung giờ lấy/giao không hợp lệ");
        var selectedWarehouse=warehouseAssignment.nearestPickup(pickupLat,pickupLng);
        Integer warehouse=selectedWarehouse.warehouseId();
        Long id=jdbc.queryForObject("SELECT nextval('orders_id_seq')",Long.class);
        String code="GK-"+java.time.LocalDate.now().toString().replace("-","")+"-"+id;
        jdbc.update("INSERT INTO orders(id,code,customer_name,phone,address,lat,lng,weight_kg,volume_m3,tw_start,tw_end,status,warehouse_id,is_dynamic,pickup_name,pickup_phone,pickup_address,pickup_lat,pickup_lng,pickup_from,pickup_to,stage,in_warehouse) VALUES (?,?,?,?,?,?,?,?,?,?,?,'new',?,true,?,?,?,?,?,?,?,'awaiting_pickup',false)",
            id,code,required(body,"customerName"),body.get("phone"),required(body,"address"),lat,lng,kg,volume,deliveryFrom,deliveryTo,warehouse,
            required(body,"pickupName"),body.get("pickupPhone"),required(body,"pickupAddress"),pickupLat,pickupLng,pickupFrom,pickupTo);
        jdbc.update("UPDATE orders SET warehouse_assignment_method='nearest_pickup_geodesic',warehouse_distance_m=?,warehouse_assigned_at=now() WHERE id=?",Math.round(selectedWarehouse.distanceMeters()),id);
        pricing.apply(id,quote,description,goodsType);
        jdbc.update("UPDATE orders SET payment_payer=? WHERE id=?",body.get("paymentPayer"),id);
        String note=Objects.toString(body.get("note"),"").trim();if(note.length()>1000)throw new IllegalArgumentException("Ghi chú tối đa 1000 ký tự");jdbc.update("UPDATE orders SET note=? WHERE id=?",note,id);
        events.publish("orders",code,"{\"code\":\""+code+"\"}");
        return Map.of("id",id,"code",code,"warehouseId",warehouse,"queued",true);
    }
    @PostMapping("/orders/{id}/requeue") @Transactional
    public ResponseEntity<Void> requeue(@PathVariable int id) {
        routing.changed();
        var order=jdbc.queryForMap("SELECT * FROM orders WHERE id=?",id);
        if(!"failed".equals(order.get("stage"))) throw new IllegalArgumentException("Đơn không ở trạng thái thất bại");
        var kinds=jdbc.queryForList("SELECT kind FROM trip_stops WHERE order_id=? AND state='failed' ORDER BY id DESC LIMIT 1",String.class,id);
        boolean pickup=!kinds.isEmpty()&&"pickup".equals(kinds.get(0));
        if(order.get("delivery_cancelled_at")!=null||((Number)order.get("delivery_failures")).intValue()>=3)throw new IllegalArgumentException("Đã giao thất bại 3 lần. Đơn chỉ được phân hoàn về người gửi.");
        if(!pickup&&!Boolean.TRUE.equals(order.get("in_warehouse"))) throw new IllegalArgumentException("Cần đưa hàng về kho trước khi giao lại");
        jdbc.update("UPDATE orders SET stage=?,status='deferred' WHERE id=?",pickup?"awaiting_pickup":"at_warehouse",id);
        return ResponseEntity.noContent().build();
    }

    /** Update expired customer appointments without altering completed stop history. */
    @PostMapping("/orders/{id}/reschedule") @Transactional
    public Map<String,Object> reschedule(@PathVariable int id,@RequestBody Map<String,Object> body) {
        routing.changed();return rescheduleLocked(id,body);
    }
    Map<String,Object> rescheduleLocked(int id,Map<String,Object> body){
        var order=jdbc.queryForMap("SELECT * FROM orders WHERE id=? FOR UPDATE",id);
        String stage=Objects.toString(order.get("stage"));
        if(!List.of("awaiting_pickup","at_warehouse").contains(stage)) throw new IllegalArgumentException("Chỉ hẹn lại đơn đang chờ lấy hoặc đang ở kho. Thu hồi chuyến trước khi sửa lịch.");
        if(jdbc.queryForObject("SELECT count(*) FROM trip_stops s JOIN trips t ON t.id=s.trip_id WHERE s.order_id=? AND t.status<>'completed'",Integer.class,id)>0) throw new IllegalArgumentException("Đơn vừa được phân chuyến. Thu hồi chuyến trước khi sửa lịch.");
        String reason=required(body,"reason");if(reason.length()>1000)throw new IllegalArgumentException("Lý do tối đa 1000 ký tự.");
        var from=java.time.LocalDateTime.parse(required(body,"deliveryFrom"));
        var to=java.time.LocalDateTime.parse(required(body,"deliveryTo"));
        validateWindow(from,to);
        if("awaiting_pickup".equals(stage)) {
            var pf=java.time.LocalDateTime.parse(required(body,"pickupFrom"));
            var pt=java.time.LocalDateTime.parse(required(body,"pickupTo"));validateWindow(pf,pt);
            if(!pf.isBefore(to))throw new IllegalArgumentException("Giờ lấy phải trước giờ kết thúc giao.");
            jdbc.update("UPDATE orders SET pickup_from=?,pickup_to=?,tw_start=?,tw_end=? WHERE id=?",pf,pt,from,to,id);
        } else jdbc.update("UPDATE orders SET tw_start=?,tw_end=? WHERE id=?",from,to,id);
        jdbc.update("INSERT INTO customer_tracking_events(order_id,type,reason) VALUES(?,'schedule_updated',?)",id,reason);
        return Map.of("updated",true);
    }
    private void validateWindow(java.time.LocalDateTime from,java.time.LocalDateTime to) {
        policy.validateDate(from.toLocalDate());
        if(!from.isBefore(to)||!from.toLocalDate().equals(to.toLocalDate())||from.toLocalTime().isBefore(WorkDayPolicy.START)||to.toLocalTime().isAfter(WorkDayPolicy.END)||!to.isAfter(policy.now().toLocalDateTime())) throw new IllegalArgumentException("Chọn khung giờ hợp lệ trong ngày, từ 08:00 đến 20:00 và chưa quá hạn.");
    }

    public List<Map<String,Object>> vehicles(LocalDate date){return vehicles(date,false);}
    @GetMapping("/vehicles")
    public List<Map<String,Object>> vehicles(@RequestParam(required=false) LocalDate date,@RequestParam(defaultValue="false") boolean includeArchived) {
        var day=date==null?policy.dispatchDate():date;
        return jdbc.queryForList("SELECT v.id,v.code,v.plate,CASE WHEN gk_vehicle_state(v.id,?)='maintenance' THEN 'maintenance' WHEN t.status='in_progress' THEN 'active' WHEN t.status='planned' THEN 'reserved' ELSE gk_vehicle_state(v.id,?) END AS status,v.status AS physical_status,v.warehouse_id,v.cost_per_km,v.archived_at,v.capacity_kg,v.capacity_m3,w.name AS warehouse,d.name AS driver,t.code AS trip,t.planned_start::date AS trip_date,m.id AS maintenance_id,m.state AS maintenance_state,m.expected_ready_at,?::date AS work_date FROM vehicles v JOIN warehouses w ON w.id=v.warehouse_id LEFT JOIN LATERAL(SELECT * FROM trips t WHERE t.vehicle_id=v.id AND t.status<>'completed' ORDER BY CASE t.status WHEN 'in_progress' THEN 0 ELSE 1 END,t.planned_start,t.id LIMIT 1) t ON true LEFT JOIN drivers d ON d.id=coalesce(t.driver_id,(SELECT h.id FROM drivers h WHERE h.vehicle_id=v.id ORDER BY h.id LIMIT 1)) LEFT JOIN vehicle_maintenance m ON m.vehicle_id=v.id AND m.state<>'completed' WHERE (v.archived_at IS NULL OR "+includeArchived+") ORDER BY v.id",day,day,day);
    }

    public List<Map<String,Object>> drivers(LocalDate date){return drivers(date,false);}
    @GetMapping("/drivers")
    public List<Map<String,Object>> drivers(@RequestParam(required=false) LocalDate date,@RequestParam(defaultValue="false") boolean includeArchived) {
        var day=date==null?policy.dispatchDate():date;
        return jdbc.queryForList("SELECT d.id,d.code,d.name,d.phone,CASE WHEN t.status='in_progress' THEN 'on_duty' WHEN t.status='planned' THEN 'reserved' ELSE gk_driver_state(d.id,?) END AS status,d.status AS physical_status,d.warehouse_id,d.vehicle_id,d.joined_on,d.archived_at,w.name AS warehouse,v.code AS vehicle,'08:00–20:00' AS shift,t.code AS trip,t.planned_start::date AS trip_date,?::date AS work_date FROM drivers d JOIN warehouses w ON w.id=d.warehouse_id LEFT JOIN LATERAL(SELECT * FROM trips t WHERE t.driver_id=d.id AND t.status<>'completed' ORDER BY CASE t.status WHEN 'in_progress' THEN 0 ELSE 1 END,t.planned_start,t.id LIMIT 1) t ON true LEFT JOIN vehicles v ON v.id=coalesce(t.vehicle_id,d.vehicle_id) WHERE (d.archived_at IS NULL OR "+includeArchived+") ORDER BY d.id",day,day);
    }

    /** Vị trí mới nhất của các xe đang chạy. age_s = số giây kể từ lần cập nhật GPS cuối. */
    @GetMapping("/live")
    public List<Map<String, Object>> live() {
        return jdbc.queryForList(
                "SELECT v.id, v.code, w.name AS warehouse, w.color, g.lat, g.lng, g.speed_kmh, " +
                "extract(epoch FROM (now() - g.time))::int AS age_s " +
                "FROM vehicles v JOIN warehouses w ON w.id = v.warehouse_id " +
                "JOIN LATERAL (SELECT * FROM gps_points p WHERE p.vehicle_id = v.id ORDER BY p.time DESC LIMIT 1) g ON true " +
                "WHERE v.status = 'active' ORDER BY v.id");
    }

    @GetMapping("/trips")
    public List<Map<String, Object>> trips(@RequestParam(required = false) String status) {
        String f = (status == null || status.isBlank()) ? "" : " WHERE t.status = '" + status.replaceAll("[^a-z_]", "") + "'";
        return jdbc.queryForList(
                "SELECT t.id, t.code, t.status, t.phase, t.route_version, t.remaining_km, v.code AS vehicle, d.name AS driver, w.name AS warehouse, w.color, " +
                "to_char(t.planned_start,'HH24:MI') AS start, t.planned_km, count(s.id) AS stops, " +
                "count(s.id) FILTER (WHERE s.state IN ('completed','failed')) AS done " +
                "FROM trips t JOIN vehicles v ON v.id = t.vehicle_id LEFT JOIN drivers d ON d.id = t.driver_id " +
                "JOIN warehouses w ON w.id = t.warehouse_id LEFT JOIN trip_stops s ON s.trip_id = t.id " +
                "LEFT JOIN orders o ON o.id = s.order_id" + f +
                " GROUP BY t.id, v.code, d.name, w.name, w.color ORDER BY t.id");
    }

    @GetMapping("/trips/{id}/stops")
    public List<Map<String, Object>> tripStops(@PathVariable int id) {
        return routing.stops(id);
    }

}
