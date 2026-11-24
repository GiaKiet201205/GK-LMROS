package vn.gk.logistics.dispatch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.*;

/** Durable background queue, optimistic snapshot/apply, and immutable driver progress. */
@Service
@DependsOn("schemaUpgrade")
public class RoutingService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final OptimizerClient optimizer;
    private final boolean enabled;
    private final WorkDayPolicy policy;
    private LocalDate lastDispatchDate;
    public RoutingService(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json, OptimizerClient optimizer,
                          @Value("${ROUTING_ENABLED:true}") boolean enabled,WorkDayPolicy policy) {
        this.jdbc=jdbc; this.tx=tx; this.json=json; this.optimizer=optimizer;
        this.enabled=enabled;this.policy=policy;
    }
    public void changed() {
        // Must be called at the start of the caller's business transaction.
        jdbc.update("UPDATE routing_state SET revision=revision+1,dirty=true,retry_after=NULL WHERE id=1");
    }
    public Map<String,Object> status() {
        Map<String,Object> out=new LinkedHashMap<>(jdbc.queryForMap("SELECT revision,dirty,last_success,last_error,last_stats::text,lease_until>now() AS running FROM routing_state WHERE id=1"));
        out.put("enabled",enabled);out.put("workday",policy.context());
        out.put("expired",jdbc.queryForObject("SELECT count(*) FROM orders WHERE (stage='awaiting_pickup' AND pickup_to<now()) OR (stage='at_warehouse' AND tw_end<now())",Integer.class));
        out.put("waiting",jdbc.queryForList("SELECT stage,count(*) AS count FROM orders WHERE stage NOT IN ('delivered','returned','failed') GROUP BY stage"));
        out.put("history",jdbc.queryForList("SELECT id,created_at,assigned,remaining_km,source,details::text FROM routing_events ORDER BY id DESC LIMIT 15"));
        return out;
    }
    private static double number(Map<String,Object> map,String key) { return ((Number)map.get(key)).doubleValue(); }
    private String stringify(Object value) {
        try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException(e); }
    }
    @Scheduled(fixedDelayString="${ROUTING_POLL_MS:1500}")
    public void tick() {
        if (!enabled) return;
        var day=policy.dispatchDate();
        if(lastDispatchDate!=null&&!lastDispatchDate.equals(day)) changed();
        lastDispatchDate=day;
        String owner=UUID.randomUUID().toString();
        try {
            if (jdbc.update("UPDATE routing_state SET lease_owner=?,lease_until=now()+interval '120 seconds' WHERE id=1 AND dirty AND (retry_after IS NULL OR retry_after<=now()) AND (lease_until IS NULL OR lease_until<now())",owner)==0) return;
            Map<String,Object> snapshot=tx.execute(s -> snapshot(owner));
            if (snapshot==null) return;
            @SuppressWarnings("unchecked") Map<String,Object> result=optimizer.solve(snapshot);
            if (result==null || !result.containsKey("routes")) throw new IllegalArgumentException("Invalid optimizer response");
            tx.executeWithoutResult(s -> apply(owner,snapshot,result));
        } catch(Exception e) {
            jdbc.update("UPDATE routing_state SET dirty=true,lease_owner=NULL,lease_until=NULL,retry_after=now()+interval '30 seconds',last_error=? WHERE id=1 AND lease_owner=?",
                    "Chưa thể tối ưu. Tuyến hiện tại được giữ nguyên; hệ thống sẽ thử lại. Kiểm tra Optimizer và cấu hình nguồn quãng đường.",owner);
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Background routing failed: {}",e.getClass().getSimpleName());
        }
    }
    private Map<String,Object> snapshot(String owner) {
        var state=jdbc.queryForMap("SELECT revision,lease_owner FROM routing_state WHERE id=1 FOR UPDATE");
        if (!owner.equals(state.get("lease_owner"))) return null;
        double now=policy.now().toEpochSecond()/60.;
        var warehouses=jdbc.queryForList("SELECT id,lat,lng FROM warehouses WHERE archived_at IS NULL");
        var workDate=policy.dispatchDate();
        var vehicles=new NamedParameterJdbcTemplate(jdbc).queryForList("SELECT v.id,v.warehouse_id,v.capacity_kg::float8,v.capacity_m3::float8,v.cost_per_km::float8,d.id AS driver_id, " +
                "extract(epoch FROM ((CAST(:day AS date)+time '08:00') AT TIME ZONE 'Asia/Ho_Chi_Minh'))/60 AS shift_start_min,extract(epoch FROM ((CAST(:day AS date)+time '20:00') AT TIME ZONE 'Asia/Ho_Chi_Minh'))/60 AS shift_end_min, " +
                "(EXISTS(SELECT 1 FROM trips accepted WHERE accepted.vehicle_id=v.id AND accepted.phase='pickup' AND accepted.status='in_progress') AND coalesce(s.working,false) AND coalesce(a.available,true) AND NOT EXISTS(SELECT 1 FROM vehicle_maintenance m WHERE m.vehicle_id=v.id AND m.state<>'completed') AND NOT EXISTS(SELECT 1 FROM trips b WHERE b.driver_id=d.id AND b.vehicle_id<>v.id AND b.status<>'completed' AND (b.planned_start::date<=:day OR b.status='in_progress')) " +
                "AND NOT EXISTS(SELECT 1 FROM trips b WHERE b.vehicle_id=v.id AND b.status='planned' AND (b.planned_start::date<:day OR (b.dispatch_mode='manual' AND b.planned_start::date<=:day)))) AS allocation_enabled " +
                "FROM vehicles v JOIN LATERAL (SELECT * FROM drivers x WHERE x.id=coalesce((SELECT t.driver_id FROM trips t WHERE t.vehicle_id=v.id AND t.status<>'completed' AND (t.planned_start::date<=:day OR t.status='in_progress') ORDER BY CASE t.status WHEN 'in_progress' THEN 0 ELSE 1 END,t.planned_start LIMIT 1),(SELECT h.id FROM drivers h WHERE h.vehicle_id=v.id ORDER BY h.id LIMIT 1))) d ON true " +
                "LEFT JOIN driver_work_days s ON s.driver_id=d.id AND s.work_date=:day LEFT JOIN vehicle_work_days a ON a.vehicle_id=v.id AND a.work_date=:day WHERE v.status<>'maintenance' AND v.archived_at IS NULL AND d.archived_at IS NULL",Map.of("day",java.sql.Date.valueOf(workDate)));
        Map<Integer,Map<String,Object>> fleet=new HashMap<>();
        for(var v:vehicles) fleet.put(((Number)v.get("id")).intValue(),v);
        List<Map<String,Object>> routes=new ArrayList<>();
        for(var trip:jdbc.queryForList("SELECT * FROM trips WHERE status='in_progress' OR (status='planned' AND dispatch_mode='auto' AND planned_start::date=?) ORDER BY id",workDate)) {
            int id=((Number)trip.get("id")).intValue(),vid=((Number)trip.get("vehicle_id")).intValue();
            var vehicle=fleet.get(vid);
            if(vehicle==null) continue;
            var all=stops(id);
            int locked=all.stream().filter(n -> !"pending".equals(n.get("state"))).mapToInt(n -> ((Number)n.get("seq")).intValue()).max().orElse(0);
            var remaining=new ArrayList<Map<String,Object>>();
            for(var n:all) if(Set.of("pending","en_route").contains(n.get("state"))) {
                n.put("frozen",((Number)n.get("seq")).intValue()<=locked);
                remaining.add(n);
            }
            if(remaining.isEmpty()) continue;
            var warehouse=warehouses.stream().filter(w -> Objects.equals(w.get("id"),trip.get("warehouse_id"))).findFirst().orElseThrow();
            Map<String,Object> origin=new LinkedHashMap<>(warehouse);
            String originSource="warehouse";
            if("in_progress".equals(trip.get("status"))) {
                var gps=jdbc.queryForList("SELECT lat,lng FROM gps_points WHERE vehicle_id=? AND time>now()-interval '5 minutes' AND time>=coalesce((SELECT max(completed_at) FROM trip_stops WHERE trip_id=? AND state IN ('completed','failed')),'-infinity'::timestamp) ORDER BY time DESC LIMIT 1",vid,id);
                if(!gps.isEmpty()) { origin.putAll(gps.get(0)); originSource="gps"; }
                else {
                    var completed=all.stream().filter(n -> Set.of("completed","failed").contains(n.get("state"))).toList();
                    if(!completed.isEmpty()) origin.putAll(completed.get(completed.size()-1));
                    originSource="last_confirmed_position";
                }
            }
            origin.put("time_min","planned".equals(trip.get("status"))?Math.max(now,number(vehicle,"shift_start_min")):now);
            Map<String,Object> route=new LinkedHashMap<>();
            route.put("id",id); route.put("vehicle_id",vid); route.put("warehouse_id",trip.get("warehouse_id"));
            route.put("phase",trip.get("phase")); route.put("status",trip.get("status"));
            route.put("start",origin); route.put("position_source",originSource); route.put("locked_seq",locked);
            route.put("nodes",remaining); route.put("frozen_count",remaining.stream().filter(n -> Boolean.TRUE.equals(n.get("frozen"))).count());
            route.put("capacity_kg",vehicle.get("capacity_kg")); route.put("capacity_m3",vehicle.get("capacity_m3"));
            route.put("cost_per_km",vehicle.get("cost_per_km"));
            var tripDate=((java.sql.Timestamp)trip.get("planned_start")).toLocalDateTime().toLocalDate();
            route.put("shift_end_min",policy.end(tripDate).getEpochSecond()/60.);
            double kg=0,m3=0;
            for(var n:all) {
                boolean onboardPickup="pickup".equals(n.get("kind")) && "completed".equals(n.get("state"));
                boolean onboardDelivery=Set.of("delivery","return").contains(n.get("kind")) && !"completed".equals(n.get("state"));
                if(onboardPickup||onboardDelivery) { kg+=number(n,"weight_kg"); m3+=number(n,"volume_m3"); }
            }
            route.put("onboard_kg",kg); route.put("onboard_m3",m3);
            route.put("allow_additions",Boolean.TRUE.equals(vehicle.get("allocation_enabled")) && tripDate.equals(workDate) && (!"in_progress".equals(trip.get("status"))||policy.inShift()));route.put("max_orders",trip.get("max_orders"));
            route.put("completed_customers",all.stream().filter(n -> !"warehouse".equals(n.get("kind")) && Set.of("completed","failed").contains(n.get("state"))).count());
            routes.add(route);
        }
        var orders=jdbc.queryForList("SELECT o.id,o.warehouse_id,CASE WHEN stage='awaiting_pickup' THEN 'pickup' ELSE 'delivery' END AS kind, " +
                "CASE WHEN stage='awaiting_pickup' THEN pickup_lat ELSE lat END AS lat,CASE WHEN stage='awaiting_pickup' THEN pickup_lng ELSE lng END AS lng, " +
                "weight_kg::float8,volume_m3::float8,service_min::float8, " +
                "extract(epoch FROM ((CASE WHEN stage='awaiting_pickup' THEN pickup_from ELSE tw_start END) AT TIME ZONE 'Asia/Ho_Chi_Minh'))/60 AS tw_start_min, " +
                "extract(epoch FROM ((CASE WHEN stage='awaiting_pickup' THEN pickup_to ELSE tw_end END) AT TIME ZONE 'Asia/Ho_Chi_Minh'))/60 AS tw_end_min " +
                "FROM orders o WHERE EXISTS(SELECT 1 FROM trips accepted WHERE accepted.warehouse_id=o.warehouse_id AND accepted.phase='pickup' AND accepted.status='in_progress') AND (stage='awaiting_pickup' AND pickup_to>=? AND pickup_from<?) AND NOT EXISTS " +
                "(SELECT 1 FROM trip_stops s JOIN trips t ON t.id=s.trip_id WHERE s.order_id=o.id AND s.state IN ('pending','en_route') AND t.status<>'completed') ORDER BY tw_end_min,o.id LIMIT 100",java.sql.Timestamp.valueOf(policy.now().toLocalDateTime().isAfter(workDate.atTime(WorkDayPolicy.START))?policy.now().toLocalDateTime():workDate.atTime(WorkDayPolicy.START)),java.sql.Timestamp.valueOf(workDate.atTime(WorkDayPolicy.END)));
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("revision",state.get("revision"));out.put("work_date",workDate.toString());out.put("warehouses",warehouses);out.put("vehicles",vehicles);
        out.put("routes",routes);out.put("orders",orders);out.put("now_min",now);
        out.put("fixed_trip_cost",jdbc.queryForObject("SELECT fixed_trip_cost_vnd FROM pricing_settings WHERE id=1",Long.class));
        return out;
    }
    public List<Map<String,Object>> stops(int tripId) { return stopsForTrips(List.of(tripId)); }
    public List<Map<String,Object>> stopsForTrips(List<Integer> tripIds) {
        if(tripIds.isEmpty()) return List.of();
        String placeholders=String.join(",",Collections.nCopies(tripIds.size(),"?"));
        return jdbc.queryForList("SELECT s.id AS stop_id,s.trip_id,s.seq,s.kind,s.state,s.order_id,s.completed_at,s.fail_reason,s.fail_note, " +
                "CASE s.kind WHEN 'warehouse' THEN w.name WHEN 'return' THEN coalesce(o.pickup_name,o.customer_name) WHEN 'pickup' THEN coalesce(o.pickup_name,o.customer_name) ELSE o.customer_name END AS customer_name, " +
                "CASE s.kind WHEN 'warehouse' THEN w.name WHEN 'return' THEN o.pickup_address WHEN 'pickup' THEN o.pickup_address ELSE o.address END AS address, " +
                "CASE s.kind WHEN 'return' THEN o.pickup_phone WHEN 'pickup' THEN o.pickup_phone ELSE o.phone END AS phone, " +
                "CASE s.kind WHEN 'warehouse' THEN w.lat WHEN 'return' THEN o.pickup_lat WHEN 'pickup' THEN o.pickup_lat ELSE o.lat END AS lat, " +
                "CASE s.kind WHEN 'warehouse' THEN w.lng WHEN 'return' THEN o.pickup_lng WHEN 'pickup' THEN o.pickup_lng ELSE o.lng END AS lng, " +
                "coalesce(o.weight_kg,0)::float8 AS weight_kg,coalesce(o.volume_m3,0)::float8 AS volume_m3,coalesce(o.parcels,0) AS parcels,o.code,o.note,o.goods_description,o.goods_type,o.stage,o.shipping_fee_vnd,o.payment_required,o.payment_payer,p.payment_status,p.payment_method,p.paid_amount,EXISTS(SELECT 1 FROM payment_decisions pd WHERE pd.stop_id=s.id) AS payment_acknowledged, " +
                "CASE WHEN s.state='completed' THEN 'delivered' WHEN s.state='failed' THEN 'failed' ELSE 'assigned' END AS status, " +
                "extract(epoch FROM ((CASE WHEN s.kind='pickup' THEN o.pickup_from WHEN s.kind='return' THEN o.return_from ELSE o.tw_start END) AT TIME ZONE 'Asia/Ho_Chi_Minh'))/60 AS tw_start_min, " +
                "extract(epoch FROM ((CASE WHEN s.kind='pickup' THEN o.pickup_to WHEN s.kind='return' THEN o.return_to ELSE o.tw_end END) AT TIME ZONE 'Asia/Ho_Chi_Minh'))/60 AS tw_end_min, " +
                "coalesce(o.service_min,5)::float8 AS service_min,to_char(s.eta,'HH24:MI') AS eta, " +
                "to_char(CASE WHEN s.kind='pickup' THEN o.pickup_from WHEN s.kind='return' THEN o.return_from ELSE o.tw_start END,'HH24:MI') AS tw_start, " +
                "to_char(CASE WHEN s.kind='pickup' THEN o.pickup_to WHEN s.kind='return' THEN o.return_to ELSE o.tw_end END,'HH24:MI') AS tw_end " +
                "FROM trip_stops s JOIN trips t ON t.id=s.trip_id JOIN warehouses w ON w.id=t.warehouse_id LEFT JOIN orders o ON o.id=s.order_id LEFT JOIN order_payments p ON p.order_id=o.id WHERE s.trip_id IN ("+placeholders+") ORDER BY s.trip_id,s.seq",tripIds.toArray());
    }
    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> journeys(Integer warehouse) {return journeys(warehouse,null);}
    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> journeys(Integer warehouse,String phase) {
        if(phase!=null&&!Set.of("pickup","delivery","return").contains(phase))throw new IllegalArgumentException("Loại chuyến không hợp lệ");
        String filter=(warehouse==null?"":" AND t.warehouse_id=?")+(phase==null?"":" AND t.phase=?");
        var journeyArgs=new ArrayList<Object>();if(warehouse!=null)journeyArgs.add(warehouse);if(phase!=null)journeyArgs.add(phase);
        var trips=jdbc.queryForList("SELECT t.id,t.code,t.status,t.phase,t.route_version,t.route_updated_at,t.remaining_km,t.max_orders,to_char(t.planned_start,'YYYY-MM-DD') AS work_date,t.warehouse_id,w.name AS warehouse, " +
            "v.id AS vehicle_id,v.code AS vehicle,v.plate,d.name AS driver,d.code AS driver_code, " +
            "g.lat AS position_lat,g.lng AS position_lng,extract(epoch FROM now()-g.time)::int AS gps_age_s " +
            "FROM trips t JOIN vehicles v ON v.id=t.vehicle_id JOIN warehouses w ON w.id=t.warehouse_id LEFT JOIN drivers d ON d.id=t.driver_id " +
            "LEFT JOIN LATERAL (SELECT lat,lng,time FROM gps_points WHERE vehicle_id=v.id AND time<=now()+interval '30 seconds' ORDER BY time DESC LIMIT 1) g ON true " +
            "WHERE t.status IN ('in_progress','planned')"+filter+" ORDER BY CASE t.status WHEN 'in_progress' THEN 0 ELSE 1 END,t.planned_start,t.id",journeyArgs.toArray());
        Map<Integer,List<Map<String,Object>>> byTrip=new HashMap<>();
        for(var stop:stopsForTrips(trips.stream().map(trip->((Number)trip.get("id")).intValue()).toList())) byTrip.computeIfAbsent(((Number)stop.get("trip_id")).intValue(),key->new ArrayList<>()).add(stop);
        Map<Integer,Map<String,Object>> byVehicle=new LinkedHashMap<>();
        for(var trip:trips) {
            int vehicleId=((Number)trip.get("vehicle_id")).intValue();
            var stops=byTrip.getOrDefault(((Number)trip.get("id")).intValue(),List.of());
            trip.put("stops",stops);
            trip.put("done",stops.stream().filter(n->Set.of("completed","failed").contains(n.get("state"))).count());
            trip.put("next_stop",stops.stream().filter(n->Set.of("pending","en_route").contains(n.get("state"))).findFirst().orElse(null));
            var vehicle=byVehicle.computeIfAbsent(vehicleId,key->{
                Map<String,Object> value=new LinkedHashMap<>();value.put("id",vehicleId);value.put("code",trip.get("vehicle"));value.put("plate",trip.get("plate"));
                value.put("trips",new ArrayList<Map<String,Object>>());return value;
            });
            @SuppressWarnings("unchecked") var list=(List<Map<String,Object>>)vehicle.get("trips");
            list.add(trip);
        }
        String orderFilter=warehouse==null?"":" AND warehouse_id=?";
        int waiting=jdbc.queryForObject("SELECT count(*) FROM orders WHERE stage IN ('awaiting_pickup','at_warehouse','return_pending')"+orderFilter,Integer.class,warehouse==null?new Object[]{}:new Object[]{warehouse});
        return Map.of("vehicles",new ArrayList<>(byVehicle.values()),"waiting",waiting,"updated_at",java.time.Instant.now().toString());
    }
    @SuppressWarnings("unchecked")
    private void apply(String owner,Map<String,Object> snapshot,Map<String,Object> result) {
        var state=jdbc.queryForMap("SELECT revision,lease_owner FROM routing_state WHERE id=1 FOR UPDATE");
        if(!owner.equals(state.get("lease_owner"))) return;
        if(!policy.dispatchDate().toString().equals(snapshot.get("work_date"))) { jdbc.update("UPDATE routing_state SET revision=revision+1,dirty=true,lease_owner=NULL,lease_until=NULL,retry_after=NULL WHERE id=1"); return; }
        if(((Number)state.get("revision")).longValue()!=((Number)snapshot.get("revision")).longValue()) {
            jdbc.update("UPDATE routing_state SET lease_owner=NULL,lease_until=NULL WHERE id=1"); return;
        }
        var originals=new HashMap<Integer,Map<String,Object>>();
        for(var r:(List<Map<String,Object>>)snapshot.get("routes")) originals.put(((Number)r.get("id")).intValue(),r);
        Set<Integer> assignedOrders=new HashSet<>();
        Set<Integer> allowedOrders=new HashSet<>();
        for(var o:(List<Map<String,Object>>)snapshot.get("orders")) allowedOrders.add(((Number)o.get("id")).intValue());
        for(var route:(List<Map<String,Object>>)result.get("routes")) {
            List<Map<String,Object>> nodes=(List<Map<String,Object>>)route.get("nodes");
            Integer id=route.get("id")==null?null:((Number)route.get("id")).intValue();
            int locked=0;
            if(id!=null) {
                var original=originals.get(id);
                if(original==null || ((Number)original.get("vehicle_id")).intValue()!=((Number)route.get("vehicle_id")).intValue()) throw new IllegalStateException("Invalid route owner");
                locked=((Number)original.get("locked_seq")).intValue();
                var oldNodes=(List<Map<String,Object>>)original.get("nodes");
                var before=oldNodes.stream().map(n -> ((Number)n.get("stop_id")).intValue()).collect(java.util.stream.Collectors.toSet());
                var after=nodes.stream().filter(n -> n.get("stop_id")!=null).map(n -> ((Number)n.get("stop_id")).intValue()).collect(java.util.stream.Collectors.toSet());
                if(!before.equals(after)) throw new IllegalStateException("Existing stops were removed");
                if(!Boolean.TRUE.equals(route.get("changed"))) {
                    for(var node:nodes) if(!Boolean.TRUE.equals(node.get("frozen")))
                        jdbc.update("UPDATE trip_stops SET eta=to_timestamp(?*60) WHERE id=? AND trip_id=? AND state='pending'",node.get("arrive_min"),node.get("stop_id"),id);
                    jdbc.update("UPDATE trips SET remaining_km=?,planned_end=to_timestamp(?*60),route_updated_at=now(),planned_km=CASE WHEN status='planned' THEN ? ELSE planned_km END WHERE id=?",route.get("distance_km"),route.get("end_min"),route.get("distance_km"),id);
                    continue;
                }
                jdbc.update("UPDATE trip_stops SET seq=-id WHERE trip_id=? AND state='pending' AND seq>?",id,locked);
            } else {
                var assignedVehicle=((List<Map<String,Object>>)snapshot.get("vehicles")).stream().filter(v -> Objects.equals(v.get("id"),route.get("vehicle_id"))).findFirst().orElseThrow();
                if(!Boolean.TRUE.equals(assignedVehicle.get("allocation_enabled"))) throw new IllegalStateException("Vehicle not available");
                id=jdbc.queryForObject("INSERT INTO trips(code,vehicle_id,driver_id,warehouse_id,status,phase,planned_start,planned_end,planned_km,remaining_km) VALUES (?,?,?,?,'planned',?,to_timestamp(?*60),to_timestamp(?*60),?,?) RETURNING id",Integer.class,
                        "GK-"+UUID.randomUUID().toString().substring(0,8),route.get("vehicle_id"),assignedVehicle.get("driver_id"),route.get("warehouse_id"),route.get("phase"),
                        ((Map<String,Object>)route.get("start")).get("time_min"),route.get("end_min"),route.get("distance_km"),route.get("distance_km"));
            }
            int seq=locked+1;
            for(var node:nodes) {
                if(Boolean.TRUE.equals(node.get("frozen"))) continue;
                if(node.get("stop_id")!=null) {
                    if(jdbc.update("UPDATE trip_stops SET seq=?,eta=to_timestamp(?*60) WHERE id=? AND trip_id=? AND state='pending'",seq++,node.get("arrive_min"),node.get("stop_id"),id)!=1) throw new IllegalStateException("Stop progress changed");
                } else {
                    Number orderId="warehouse".equals(node.get("kind"))?null:(Number)node.get("id");
                    if(orderId!=null && (!allowedOrders.contains(orderId.intValue()) || !assignedOrders.add(orderId.intValue()))) throw new IllegalStateException("Duplicate/unknown assignment");
                    jdbc.update("INSERT INTO trip_stops(trip_id,order_id,seq,kind,state,eta) VALUES (?,?,?,?,'pending',to_timestamp(?*60))",id,orderId,seq++,node.get("kind"),node.get("arrive_min"));
                    if(orderId!=null) jdbc.update("UPDATE orders SET stage=?,status='assigned' WHERE id=?", "pickup".equals(node.get("kind"))?("in_progress".equals(route.get("status"))?"picking_up":"pickup_assigned"):"delivery_assigned",orderId);
                }
            }
            jdbc.update("UPDATE trips SET remaining_km=?,planned_end=to_timestamp(?*60),route_version=route_version+1,route_updated_at=now(),planned_km=CASE WHEN status='planned' THEN ? ELSE planned_km END WHERE id=?",route.get("distance_km"),route.get("end_min"),route.get("distance_km"),id);
        }
        if (!assignedOrders.isEmpty() || ((List<Map<String,Object>>)result.get("routes")).stream().anyMatch(r -> Boolean.TRUE.equals(r.get("changed"))))
            jdbc.update("UPDATE routing_state SET revision=revision+1 WHERE id=1");
        var stats=(Map<String,Object>)result.get("stats");
        boolean pending=jdbc.queryForObject("SELECT count(*) FROM orders o WHERE EXISTS(SELECT 1 FROM trips accepted WHERE accepted.warehouse_id=o.warehouse_id AND accepted.phase='pickup' AND accepted.status='in_progress') AND (stage='awaiting_pickup' AND pickup_to>=? AND pickup_from<?) AND NOT EXISTS (SELECT 1 FROM trip_stops s JOIN trips t ON t.id=s.trip_id WHERE s.order_id=o.id AND s.state IN ('pending','en_route') AND t.status<>'completed')",Integer.class,java.sql.Timestamp.valueOf(policy.now().toLocalDateTime().isAfter(policy.dispatchDate().atTime(WorkDayPolicy.START))?policy.now().toLocalDateTime():policy.dispatchDate().atTime(WorkDayPolicy.START)),java.sql.Timestamp.valueOf(policy.dispatchDate().atTime(WorkDayPolicy.END)))>0;
        jdbc.update("INSERT INTO routing_events(revision,assigned,remaining_km,source,details) VALUES (?,?,?,?,?::jsonb)",snapshot.get("revision"),assignedOrders.size(),stats.get("remaining_km"),stats.get("source"),stringify(stats));
        jdbc.update("UPDATE routing_state SET dirty=?,lease_owner=NULL,lease_until=NULL,last_success=now(),last_error=NULL,last_stats=?::jsonb,retry_after=now()+interval '60 seconds' WHERE id=1",pending,stringify(stats));
    }
}
