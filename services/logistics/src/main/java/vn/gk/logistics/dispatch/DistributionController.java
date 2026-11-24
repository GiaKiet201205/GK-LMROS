package vn.gk.logistics.dispatch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.util.*;

/** Reviewable delivery batches. No operational assignments until confirmation. */
@RestController
@RequestMapping("/api/distribution")
public class DistributionController {
    private final JdbcTemplate jdbc; private final TransactionTemplate tx; private final ObjectMapper json;
    private final WorkDayPolicy policy; private final RoutingService routing; private final OptimizerClient optimizer;
    public DistributionController(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json,RoutingService routing,OptimizerClient optimizer,WorkDayPolicy policy) {
        this.jdbc=jdbc;this.tx=tx;this.json=json;this.routing=routing;this.optimizer=optimizer;this.policy=policy;
    }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("Không thể lưu phương án"); } }
    private Map<String,Object> decode(String value) { try { return json.readValue(value,new TypeReference<>(){}); } catch(Exception e) { throw new IllegalStateException(e); } }
    @GetMapping("/resources")
    public Map<String,Object> resources(@RequestParam(required=false) LocalDate date,@RequestParam int warehouse,@RequestParam(defaultValue="delivery") String phase) {
        phase=phase(phase);if(date==null) date=policy.dispatchDate();
        policy.validateDate(date);
        return resourceInfo(date,warehouse,phase,waitingQueue(date,warehouse,phase));
    }
    private record QueueData(List<Map<String,Object>> ready,List<Map<String,Object>> excluded,Map<String,Object> counts){}
    private QueueData waitingQueue(LocalDate date,int warehouse,String phase){
        String fields=phase.equals("pickup")?"o.pickup_name AS customer_name,o.pickup_address AS address,o.pickup_lat AS lat,o.pickup_lng AS lng,o.pickup_from AS window_start,o.pickup_to AS window_end":phase.equals("return")?"o.pickup_name AS customer_name,o.pickup_address AS address,o.pickup_lat AS lat,o.pickup_lng AS lng,?::date+time '08:00' AS window_start,?::date+time '20:00' AS window_end":"o.customer_name,o.address,o.lat,o.lng,o.tw_start AS window_start,o.tw_end AS window_end";
        String eligible=phase.equals("pickup")?"o.stage='awaiting_pickup' AND NOT o.in_warehouse":phase.equals("return")?"o.stage='return_pending' AND o.in_warehouse AND o.delivery_cancelled_at IS NOT NULL":"o.stage='at_warehouse' AND o.in_warehouse AND o.delivery_cancelled_at IS NULL AND o.delivery_failures<3";
        var args=new ArrayList<Object>();if(phase.equals("return")){args.add(date);args.add(date);}args.add(warehouse);
        var orders=jdbc.queryForList("SELECT q.*,extract(epoch FROM (window_start AT TIME ZONE 'Asia/Ho_Chi_Minh'))/60 AS tw_start_min,extract(epoch FROM (window_end AT TIME ZONE 'Asia/Ho_Chi_Minh'))/60 AS tw_end_min FROM (SELECT o.id,o.code,o.warehouse_id,'"+phase+"' AS kind,"+fields+",o.weight_kg::float8,o.volume_m3::float8,o.shipping_fee_vnd,o.shipping_price_status,o.service_min::float8 FROM orders o WHERE o.warehouse_id=? AND "+eligible+" AND NOT EXISTS(SELECT 1 FROM trip_stops st JOIN trips t ON t.id=st.trip_id WHERE st.order_id=o.id AND t.status<>'completed' AND st.state IN ('pending','en_route'))) q ORDER BY window_end NULLS LAST,id",args.toArray());
        double cutoff=Math.max(policy.now().toEpochSecond()/60.,policy.start(date).getEpochSecond()/60.);
        double nextDay=date.plusDays(1).atStartOfDay(WorkDayPolicy.ZONE).toEpochSecond()/60.;
        var ready=new ArrayList<Map<String,Object>>();var excluded=new ArrayList<Map<String,Object>>();int expired=0,future=0,invalid=0;
        for(var order:orders){String reason=null;
            if(!(order.get("tw_start_min") instanceof Number)||!(order.get("tw_end_min") instanceof Number)) {reason="missing_appointment";invalid++;}
            else if(((Number)order.get("tw_end_min")).doubleValue()<cutoff){reason="order_deadline_passed";expired++;}
            else if(((Number)order.get("tw_start_min")).doubleValue()>=nextDay){reason="scheduled_later";future++;}
            else if(!validPoint(order.get("lat"),90)||!validPoint(order.get("lng"),180)){reason="missing_location";invalid++;}
            if(reason==null)ready.add(order);else{var n=new LinkedHashMap<String,Object>();for(String k:List.of("id","code","customer_name"))n.put(k,order.get(k));n.put("reason",reason);n.put("window_start_min",order.get("tw_start_min"));n.put("window_end_min",order.get("tw_end_min"));if(reason.equals("order_deadline_passed"))n.put("deadline_min",order.get("tw_end_min"));excluded.add(n);}
        }
        int available=ready.size(),deferred=Math.max(0,available-500);
        if(deferred>0){for(var order:ready.subList(500,ready.size())){var n=new LinkedHashMap<String,Object>();for(String k:List.of("id","code","customer_name"))n.put(k,order.get(k));n.put("reason","batch_limit");excluded.add(n);}ready=new ArrayList<>(ready.subList(0,500));}
        return new QueueData(ready,excluded,Map.of("at_warehouse",orders.size(),"expired",expired,"future",future,"invalid",invalid,"ready",available,"considered",ready.size(),"deferred",deferred));
    }
    private boolean validPoint(Object value,int bound){return value instanceof Number n&&Double.isFinite(n.doubleValue())&&Math.abs(n.doubleValue())<=bound;}
    private Map<String,Object> resourceInfo(LocalDate date,int warehouse,String phase,QueueData queue){
        var vehicles=jdbc.queryForList("SELECT v.id,v.code,v.plate,v.capacity_kg,v.capacity_m3,v.cost_per_km,v.status AS physical_status,gk_vehicle_state(v.id,'"+date+"'::date) AS status,coalesce(a.available,true) AS available,coalesce(a.note,'') AS note,EXISTS(SELECT 1 FROM trips t WHERE t.vehicle_id=v.id AND t.status<>'completed' AND (t.planned_start::date<=? OR t.status='in_progress')) AS busy FROM vehicles v LEFT JOIN vehicle_work_days a ON a.vehicle_id=v.id AND a.work_date=? WHERE v.warehouse_id=? AND v.archived_at IS NULL ORDER BY v.code",date,date,warehouse);
        var drivers=jdbc.queryForList("SELECT d.id,d.code,d.name,gk_driver_state(d.id,'"+date+"'::date) AS dispatch_status,s.working,to_char(s.shift_start,'HH24:MI') AS shift_start,to_char(s.shift_end,'HH24:MI') AS shift_end,EXISTS(SELECT 1 FROM trips t WHERE t.driver_id=d.id AND t.status<>'completed' AND (t.planned_start::date<=? OR t.status='in_progress')) AS busy FROM drivers d LEFT JOIN driver_work_days s ON s.driver_id=d.id AND s.work_date=? WHERE d.warehouse_id=? AND d.archived_at IS NULL ORDER BY d.code",date,date,warehouse);
        var counts=queue.counts();
        var trips=jdbc.queryForList("SELECT t.id,t.code,t.status,t.planned_start::date AS work_date,v.code AS vehicle,d.name AS driver,(SELECT count(*) FROM trip_stops s WHERE s.trip_id=t.id AND s.kind<>'warehouse') AS orders FROM trips t JOIN vehicles v ON v.id=t.vehicle_id LEFT JOIN drivers d ON d.id=t.driver_id WHERE t.warehouse_id=? AND t.status<>'completed' AND (t.planned_start::date<=? OR t.status='in_progress') ORDER BY t.planned_start,t.id",warehouse,date);
        return Map.of("vehicles",vehicles,"drivers",drivers,"counts",counts,"trips",trips,"work_date",date,"workday",policy.context(),"planning_objective","min_trips_then_cost");
    }
    @PostMapping("/trips/{id}/release")
    public Map<String,Object> release(@PathVariable int id) {
        return tx.execute(status->{
            routing.changed();
            var rows=jdbc.queryForList("SELECT * FROM trips WHERE id=? FOR UPDATE",id);
            if(rows.isEmpty()) throw new IllegalArgumentException("Không tìm thấy chuyến");var trip=rows.get(0);
            if(!"planned".equals(trip.get("status"))||jdbc.queryForObject("SELECT count(*) FROM trip_stops WHERE trip_id=? AND state<>'pending'",Integer.class,id)>0) WorkScheduleController.conflict("Chỉ thu hồi chuyến chưa xuất phát, chưa xử lý điểm nào");
            jdbc.update("UPDATE orders SET stage=CASE WHEN stage='pickup_assigned' THEN 'awaiting_pickup' WHEN stage='return_assigned' THEN 'return_pending' ELSE 'at_warehouse' END,status='pending' WHERE stage IN ('pickup_assigned','delivery_assigned','return_assigned') AND id IN (SELECT order_id FROM trip_stops WHERE trip_id=?)",id);
            if(trip.get("distribution_plan_id")!=null) jdbc.update("UPDATE distribution_plans SET status='released' WHERE id=?",trip.get("distribution_plan_id"));
            jdbc.update("DELETE FROM trips WHERE id=?",id);
            jdbc.update("INSERT INTO routing_events(revision,assigned,remaining_km,source,details) SELECT revision,0,0,'manual_release',?::jsonb FROM routing_state WHERE id=1",encode(Map.of("trip",trip.get("code"),"action","release_unstarted_trip")));
            return Map.of("released",true);
        });
    }
    public record Assignment(int vehicleId,int driverId) {}
    public record Request(LocalDate date,int warehouse,int maxOrders,List<Assignment> assignments,String phase) {
        public Request(LocalDate date,int warehouse,int maxOrders,List<Assignment> assignments) {this(date,warehouse,maxOrders,assignments,"delivery");}
    }
    public Map<String,Object> resources(LocalDate date,int warehouse) {return resources(date,warehouse,"delivery");}
    private String phase(String value) { if(value==null) return "delivery";if(!Set.of("pickup","delivery","return").contains(value))throw new IllegalArgumentException("Loại chuyến không hợp lệ");return value; }
    @SuppressWarnings("unchecked")
    private Map<String,Object> snapshot(Request body) {
        String phase=phase(body.phase());
        if(body.date()==null) throw new IllegalArgumentException("Chọn ngày thực hiện");policy.validateDate(body.date());
        if(body.maxOrders()<1||body.maxOrders()>45) throw new IllegalArgumentException("Số đơn mỗi chuyến từ 1 đến 45");
        if(body.assignments()==null||body.assignments().isEmpty()||body.assignments().stream().anyMatch(a->a==null||a.vehicleId()<=0||a.driverId()<=0)) throw new IllegalArgumentException("Chọn ít nhất một cặp xe và tài xế");
        var state=jdbc.queryForMap("SELECT revision FROM routing_state WHERE id=1 FOR UPDATE");
        var queue=waitingQueue(body.date(),body.warehouse(),phase);
        var info=resourceInfo(body.date(),body.warehouse(),phase,queue);
        var vehicles=(List<Map<String,Object>>)info.get("vehicles");var drivers=(List<Map<String,Object>>)info.get("drivers");
        var fleet=new ArrayList<Map<String,Object>>();Set<Integer> vids=new HashSet<>(),dids=new HashSet<>();
        for(var pair:body.assignments()) {
            if(!vids.add(pair.vehicleId())||!dids.add(pair.driverId())) throw new IllegalArgumentException("Một xe hoặc tài xế chỉ được chọn một lần");
            var v=vehicles.stream().filter(n->((Number)n.get("id")).intValue()==pair.vehicleId()).findFirst().orElseThrow(()->new IllegalArgumentException("Xe không thuộc kho đã chọn"));
            var d=drivers.stream().filter(n->((Number)n.get("id")).intValue()==pair.driverId()).findFirst().orElseThrow(()->new IllegalArgumentException("Tài xế không thuộc kho đã chọn"));
            if(!Boolean.TRUE.equals(v.get("available"))||Boolean.TRUE.equals(v.get("busy"))||"maintenance".equals(v.get("status"))) WorkScheduleController.conflict("Xe "+v.get("code")+" đang bận, bảo trì hoặc đã có chuyến");
            if(!Boolean.TRUE.equals(d.get("working"))||Boolean.TRUE.equals(d.get("busy"))) WorkScheduleController.conflict("Tài xế "+d.get("code")+" chưa đăng ký làm, nghỉ hoặc đã có chuyến");
            double start=body.date().atTime(WorkDayPolicy.START).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toEpochSecond()/60.;
            double end=body.date().atTime(WorkDayPolicy.END).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toEpochSecond()/60.;
            if(end<=policy.now().toEpochSecond()/60.) WorkScheduleController.conflict("Ca làm đã kết thúc");
            Map<String,Object> item=new LinkedHashMap<>(v);item.put("warehouse_id",body.warehouse());item.put("driver_id",pair.driverId());item.put("driver_name",d.get("name"));item.put("shift_start_min",start);item.put("shift_end_min",end);fleet.add(item);
        }
        var warehouses=jdbc.queryForList("SELECT id,lat,lng FROM warehouses WHERE id=? AND archived_at IS NULL",body.warehouse());
        if(warehouses.isEmpty()) throw new IllegalArgumentException("Không tìm thấy kho");
        var orders=queue.ready();boolean limited=((Number)queue.counts().get("deferred")).intValue()>0;
        if(((Number)queue.counts().get("at_warehouse")).intValue()==0)throw new IllegalArgumentException("Không có đơn đang chờ trong kho và loại phân phối đã chọn.");
        Map<String,Object> req=new LinkedHashMap<>();req.put("warehouses",warehouses);req.put("vehicles",fleet);req.put("orders",orders);req.put("routes",List.of());
        req.put("now_min",Math.max(policy.now().toEpochSecond()/60.,body.date().atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toEpochSecond()/60.));
        req.put("max_orders",body.maxOrders());req.put("max_stops",body.maxOrders()+1);req.put("seconds",10);req.put("group_deliveries",true);req.put("fixed_trip_cost",jdbc.queryForObject("SELECT fixed_trip_cost_vnd FROM pricing_settings WHERE id=1",Long.class));
        return Map.of("revision",state.get("revision"),"request",req,"limited",limited,"excluded",queue.excluded(),"queue_summary",queue.counts());
    }
    @PostMapping("/preview")
    @SuppressWarnings("unchecked")
    public Map<String,Object> preview(@RequestBody Request body) {
        var snapshot=tx.execute(s->snapshot(body));
        Map<String,Object> result;
        try {
            if(((List<?>)((Map<?,?>)snapshot.get("request")).get("orders")).isEmpty()){
                var stats=new LinkedHashMap<String,Object>();stats.put("planning_objective","min_trips_then_cost");stats.put("algorithm","queue_precheck");stats.put("source","not_requested");stats.put("assigned",0);stats.put("remaining_km",0);stats.put("estimated_cost",0);stats.put("requested",0);stats.put("unassigned",new ArrayList<>());result=new LinkedHashMap<>(Map.of("routes",List.of(),"stats",stats));
            }else result=optimizer.solve(snapshot.get("request"));
        }
        catch(org.springframework.web.client.RestClientException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Chưa tính được phương án. Kiểm tra Optimizer hoặc cấu hình quãng đường."); }
        if(result==null||!(result.get("routes") instanceof List<?>)) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Phương án trả về không hợp lệ");
        if(!(result.get("stats") instanceof Map<?,?> optimizerStats)||!"min_trips_then_cost".equals(optimizerStats.get("planning_objective")))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Optimizer chưa được cập nhật chính sách gom chuyến. Hãy cập nhật đồng bộ dịch vụ tối ưu.");
        // Attach customer-facing context, including expired appointment windows.
        var input=(Map<String,Object>)snapshot.get("request");var inputOrders=(List<Map<String,Object>>)input.get("orders");
        var stats=(Map<String,Object>)result.get("stats");
        if(stats!=null&&stats.get("unassigned") instanceof List<?> failures){
            var explained=new ArrayList<Map<String,Object>>();
            for(var raw:failures){var failure=new LinkedHashMap<String,Object>((Map<String,Object>)raw);var order=inputOrders.stream().filter(o->Objects.equals(o.get("id"),failure.get("id"))).findFirst().orElse(null);
                if(order!=null){failure.put("code",order.get("code"));failure.put("customer_name",order.get("customer_name"));failure.put("window_start_min",order.get("tw_start_min"));failure.put("window_end_min",order.get("tw_end_min"));
                    if(((Number)order.get("tw_end_min")).doubleValue()<((Number)input.get("now_min")).doubleValue()){failure.put("reason","order_deadline_passed");failure.put("deadline_min",order.get("tw_end_min"));}}
                explained.add(failure);
            }
            explained.addAll((List<Map<String,Object>>)snapshot.get("excluded"));stats.put("unassigned",explained);
            stats.put("requested",((Map<?,?>)snapshot.get("queue_summary")).get("at_warehouse"));
            stats.put("considered",inputOrders.size());stats.put("excluded_before_routing",((List<?>)snapshot.get("excluded")).size());
        }
        var payload=new LinkedHashMap<String,Object>(snapshot);payload.put("result",result);payload.put("selection",body);
        Long id=jdbc.queryForObject("INSERT INTO distribution_plans(work_date,warehouse_id,revision,payload,planning_objective) VALUES(?,?,?,?::jsonb,'min_trips_then_cost') RETURNING id",Long.class,body.date(),body.warehouse(),snapshot.get("revision"),encode(payload));
        return Map.of("id",id,"date",body.date(),"result",result,"vehicles",((Map<?,?>)snapshot.get("request")).get("vehicles"),"limited",snapshot.get("limited"),"queue_summary",snapshot.get("queue_summary"));
    }
    @PostMapping("/{id}/confirm")
    @SuppressWarnings("unchecked")
    public Map<String,Object> confirm(@PathVariable long id) {
        return tx.execute(status->{
            var state=jdbc.queryForMap("SELECT revision FROM routing_state WHERE id=1 FOR UPDATE");
            var rows=jdbc.queryForList("SELECT *,created_at<now()-interval '30 minutes' AS expired,payload::text AS body FROM distribution_plans WHERE id=? FOR UPDATE",id);
            if(rows.isEmpty()) throw new IllegalArgumentException("Không tìm thấy phương án");var plan=rows.get(0);
            if("released".equals(plan.get("status"))) WorkScheduleController.conflict("Phương án đã có chuyến được thu hồi. Hãy tính phương án mới cho các đơn còn chờ.");
            if("confirmed".equals(plan.get("status"))) {
                var counts=jdbc.queryForMap("SELECT count(DISTINCT t.id) AS trips,count(s.order_id) AS orders FROM trips t LEFT JOIN trip_stops s ON s.trip_id=t.id AND s.kind<>'warehouse' WHERE t.distribution_plan_id=?",id);
                if(((Number)counts.get("trips")).intValue()==0) WorkScheduleController.conflict("Phương án cũ đã thay đổi. Tải lại danh sách chuyến trước khi tiếp tục.");
                return Map.of("confirmed",true,"alreadyConfirmed",true,"trips",counts.get("trips"),"orders",counts.get("orders"));
            }
            if(!"min_trips_then_cost".equals(plan.get("planning_objective")))WorkScheduleController.conflict("Phương án dùng quy tắc cũ. Hãy tính lại để gom chuyến theo quy tắc mới.");
            if(Boolean.TRUE.equals(plan.get("expired"))||((Number)state.get("revision")).longValue()!=((Number)plan.get("revision")).longValue()) WorkScheduleController.conflict("Dữ liệu hoặc lịch đã thay đổi. Hãy tính lại phương án trước khi duyệt.");
            var payload=decode((String)plan.get("body"));var selection=(Map<String,Object>)payload.get("selection");
            LocalDate date=LocalDate.parse((String)selection.get("date"));
            var pairs=((List<Map<String,Object>>)selection.get("assignments")).stream().map(a->new Assignment(((Number)a.get("vehicleId")).intValue(),((Number)a.get("driverId")).intValue())).toList();
            if(date.isBefore(policy.dispatchDate())) WorkScheduleController.conflict("Đã chuyển ngày phân công sau 20:00. Chọn "+policy.dispatchDate()+" và tính lại phương án.");
            String phase=phase((String)selection.get("phase"));
            var current=snapshot(new Request(date,((Number)selection.get("warehouse")).intValue(),((Number)selection.get("maxOrders")).intValue(),pairs,phase));
            var req=(Map<String,Object>)payload.get("request");var result=(Map<String,Object>)payload.get("result");
            var routes=(List<Map<String,Object>>)result.get("routes");if(routes.isEmpty()) throw new IllegalArgumentException("Không có chuyến phù hợp để phân công");
            var allowed=new HashSet<Integer>();for(var o:(List<Map<String,Object>>)((Map<String,Object>)current.get("request")).get("orders")) allowed.add(((Number)o.get("id")).intValue());
            Set<Integer> assigned=new HashSet<>(),usedVehicles=new HashSet<>(),usedDrivers=new HashSet<>();
            routing.changed();
            for(var route:routes) {
                int vid=((Number)route.get("vehicle_id")).intValue();
                var vehicle=((List<Map<String,Object>>)req.get("vehicles")).stream().filter(v->((Number)v.get("id")).intValue()==vid).findFirst().orElseThrow();
                int did=((Number)vehicle.get("driver_id")).intValue();
                if(!usedVehicles.add(vid)||!usedDrivers.add(did)||!phase.equals(route.get("phase"))) throw new IllegalArgumentException("Phân xe không hợp lệ");
                var nodes=(List<Map<String,Object>>)route.get("nodes");
                if(nodes.isEmpty()||nodes.size()>((Number)selection.get("maxOrders")).intValue()+1||!"warehouse".equals(nodes.get(nodes.size()-1).get("kind"))) throw new IllegalArgumentException("Chuyến vượt số đơn hoặc thiếu điểm về kho");
                if(nodes.size()<2||nodes.size()>46||nodes.stream().filter(n->"warehouse".equals(n.get("kind"))).count()!=1||nodes.subList(0,nodes.size()-1).stream().anyMatch(n->!phase.equals(n.get("kind"))))throw new IllegalArgumentException("Chuyến chỉ gồm tối đa 45 điểm khách hàng và một điểm về kho.");
                double kg=0,m3=0;
                for(var node:nodes.subList(0,nodes.size()-1)) {var order=jdbc.queryForMap("SELECT weight_kg,volume_m3 FROM orders WHERE id=? FOR UPDATE",node.get("id"));kg+=((Number)order.get("weight_kg")).doubleValue();m3+=((Number)order.get("volume_m3")).doubleValue();}
                var currentVehicle=jdbc.queryForMap("SELECT capacity_kg,capacity_m3 FROM vehicles WHERE id=? FOR UPDATE",vid);
                if(kg>((Number)currentVehicle.get("capacity_kg")).doubleValue()+1e-6||m3>((Number)currentVehicle.get("capacity_m3")).doubleValue()+1e-6)throw new IllegalArgumentException("Chuyến vượt tải trọng hoặc thể tích xe. Hãy tính lại phương án.");
                double start=((Number)((Map<?,?>)route.get("start")).get("time_min")).doubleValue(),end=((Number)route.get("end_min")).doubleValue();
                if(start<policy.now().toEpochSecond()/60.-2) WorkScheduleController.conflict("Giờ xuất phát đã qua. Hãy tính lại phương án.");
                int tid=jdbc.queryForObject("INSERT INTO trips(code,vehicle_id,driver_id,warehouse_id,status,phase,dispatch_mode,max_orders,planned_start,planned_end,planned_km,remaining_km) VALUES(?,?,?,?,'planned',?,'manual',?,to_timestamp(?*60),to_timestamp(?*60),?,?) RETURNING id",Integer.class,"GK-"+(phase.equals("pickup")?"LAY":phase.equals("return")?"HOAN":"GIAO")+"-"+UUID.randomUUID().toString().substring(0,8),vid,did,selection.get("warehouse"),phase,selection.get("maxOrders"),start,end,route.get("distance_km"),route.get("distance_km"));
                jdbc.update("UPDATE trips SET distribution_plan_id=? WHERE id=?",id,tid);
                int seq=0;
                for(var node:nodes) {
                    boolean warehouse="warehouse".equals(node.get("kind"));Integer oid=warehouse?null:((Number)node.get("id")).intValue();
                    if(oid!=null&&(!allowed.contains(oid)||!assigned.add(oid))) WorkScheduleController.conflict("Đơn đã đổi trạng thái hoặc bị phân trùng. Hãy tính lại.");
                    if(oid!=null&&phase.equals("return"))jdbc.update("UPDATE orders SET return_from=?::date+time '08:00',return_to=?::date+time '20:00' WHERE id=?",date,date,oid);
                    if(oid!=null&&jdbc.update("UPDATE orders SET stage=?,status='assigned' WHERE id=? AND stage=? AND in_warehouse=? AND (delivery_cancelled_at IS NULL OR ?='return')",phase+"_assigned",oid,phase.equals("pickup")?"awaiting_pickup":phase.equals("return")?"return_pending":"at_warehouse",!phase.equals("pickup"),phase)!=1) WorkScheduleController.conflict("Đơn không còn ở kho");
                    jdbc.update("INSERT INTO trip_stops(trip_id,order_id,seq,kind,state,eta) VALUES(?,?,?,?,'pending',to_timestamp(?*60))",tid,oid,++seq,warehouse?"warehouse":phase,node.get("arrive_min"));
                }
            }
            jdbc.update("UPDATE distribution_plans SET status='confirmed',confirmed_at=now() WHERE id=?",id);
            var stats=(Map<String,Object>)result.get("stats");
            var audit=new LinkedHashMap<String,Object>(stats);audit.put("plan_id",id);audit.put("action","confirm_distribution");
            jdbc.update("INSERT INTO routing_events(revision,assigned,remaining_km,source,details) SELECT revision,?,?,?,?::jsonb FROM routing_state WHERE id=1",assigned.size(),stats.get("remaining_km"),stats.get("source"),encode(audit));
            return Map.of("confirmed",true,"trips",routes.size(),"orders",assigned.size());
        });
    }
}
