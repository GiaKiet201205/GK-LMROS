package vn.gk.logistics.customers;

import vn.gk.logistics.api.ApiController;
import vn.gk.logistics.api.LocationController;
import vn.gk.logistics.security.InternalFilter;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Only accessible through InternalFilter. Never exposes other customers' stops. */
@RestController
@RequestMapping("/internal/customer")
public class CustomerInternalController {
    private final JdbcTemplate jdbc; private final ApiController orders; private final LocationController locations;
    public CustomerInternalController(JdbcTemplate jdbc,ApiController orders,LocationController locations) { this.jdbc=jdbc;this.orders=orders;this.locations=locations; }
    public record Booking(UUID bookingId,UUID customerId,Map<String,Object> order) {}
    @PostMapping("/orders") @Transactional
    public Map<String,Object> create(@RequestBody Booking body) {
        if(body.bookingId()==null||body.customerId()==null||body.order()==null) throw new IllegalArgumentException("Thiếu thông tin yêu cầu đặt đơn");
        jdbc.queryForMap("SELECT revision FROM routing_state WHERE id=1 FOR UPDATE");
        var found=jdbc.queryForList("SELECT id,code,customer_ref FROM orders WHERE customer_booking_id=?",body.bookingId());
        if(!found.isEmpty()) {
            if(!body.customerId().equals(found.get(0).get("customer_ref"))) throw new IllegalArgumentException("Yêu cầu đặt đơn không khớp khách hàng");
            return Map.of("id",found.get(0).get("id"),"code",found.get(0).get("code"));
        }
        var result=orders.createOrder(body.order());
        jdbc.update("UPDATE orders SET customer_ref=?,customer_booking_id=?,note=? WHERE id=?",body.customerId(),body.bookingId(),Objects.toString(body.order().get("note"),""),result.get("id"));
        return result;
    }
    public record Receipt(long eventId,boolean read) {}
    public record Receipts(UUID bookingId,List<Receipt> events) {}
    @PostMapping("/event-receipts") @Transactional
    public void receipts(@RequestBody Receipts body) {
        if(body.bookingId()==null||body.events()==null||body.events().size()>1000) throw new IllegalArgumentException("Thông tin xác nhận không hợp lệ");
        for(var event:body.events()) jdbc.update("INSERT INTO customer_event_receipts(event_id,read_at) SELECT e.id,CASE WHEN ? THEN now() END FROM customer_tracking_events e JOIN orders o ON o.id=e.order_id WHERE e.id=? AND o.customer_booking_id=? ON CONFLICT(event_id) DO UPDATE SET read_at=coalesce(customer_event_receipts.read_at,excluded.read_at)",event.read(),event.eventId(),body.bookingId());
    }
    @GetMapping("/locations")
    public Map<String,Object> search(@RequestParam String q) { return locations.search(q); }
    @PostMapping("/sync")
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Map<String,Object>> sync(@RequestBody List<UUID> ids) {
        if(ids.isEmpty()) return List.of();if(ids.size()>100) throw new IllegalArgumentException("Tối đa 100 đơn/lần đồng bộ");
        String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        var rows=jdbc.queryForList("SELECT o.id,o.code,o.customer_ref,o.customer_booking_id AS booking_id,o.stage,o.is_demo,o.payment_payer,pay.payment_status,pay.paid_amount,pay.payment_method,pay.paid_at,o.in_warehouse,extract(epoch FROM (o.pickup_from AT TIME ZONE 'Asia/Ho_Chi_Minh'))/60 AS pickup_from_min,extract(epoch FROM (o.pickup_to AT TIME ZONE 'Asia/Ho_Chi_Minh'))/60 AS pickup_to_min,extract(epoch FROM (o.tw_start AT TIME ZONE 'Asia/Ho_Chi_Minh'))/60 AS delivery_from_min,extract(epoch FROM (o.tw_end AT TIME ZONE 'Asia/Ho_Chi_Minh'))/60 AS delivery_to_min,o.delivery_failures,o.delivery_cancelled_at,o.pickup_name,o.pickup_phone,o.pickup_address,o.customer_name,o.phone,o.address,o.note,o.goods_description,o.goods_type,o.shipping_distance_m,o.billed_distance_km,o.shipping_rate_vnd,o.shipping_fee_vnd,o.shipping_source,o.shipping_price_status,o.weight_kg,o.volume_m3,o.warehouse_assignment_method,o.warehouse_distance_m,w.name AS warehouse, " +
            "to_char(o.pickup_from,'YYYY-MM-DD HH24:MI') AS pickup_from,to_char(o.pickup_to,'YYYY-MM-DD HH24:MI') AS pickup_to,to_char(o.tw_start,'YYYY-MM-DD HH24:MI') AS delivery_from,to_char(o.tw_end,'YYYY-MM-DD HH24:MI') AS delivery_to, " +
            "p.driver_name AS pickup_driver,p.driver_phone AS pickup_driver_phone,p.plate AS pickup_plate,p.trip_status AS pickup_trip_status, " +
            "r.driver_name AS return_driver,r.driver_phone AS return_driver_phone,r.plate AS return_plate,r.trip_status AS return_trip_status,d.driver_name AS delivery_driver,d.driver_phone AS delivery_driver_phone,d.plate AS delivery_plate,d.trip_status AS delivery_trip_status " +
            "FROM orders o JOIN warehouses w ON w.id=o.warehouse_id JOIN order_payments pay ON pay.order_id=o.id " +
            "LEFT JOIN LATERAL (SELECT dr.name AS driver_name,dr.phone AS driver_phone,v.plate,t.status AS trip_status FROM trip_stops s JOIN trips t ON t.id=s.trip_id JOIN vehicles v ON v.id=t.vehicle_id LEFT JOIN drivers dr ON dr.id=t.driver_id WHERE s.order_id=o.id AND s.kind='pickup' ORDER BY s.id DESC LIMIT 1) p ON true " +
            "LEFT JOIN LATERAL (SELECT dr.name AS driver_name,dr.phone AS driver_phone,v.plate,t.status AS trip_status FROM trip_stops s JOIN trips t ON t.id=s.trip_id JOIN vehicles v ON v.id=t.vehicle_id LEFT JOIN drivers dr ON dr.id=t.driver_id WHERE s.order_id=o.id AND s.kind='delivery' ORDER BY s.id DESC LIMIT 1) d ON true " +
            "LEFT JOIN LATERAL (SELECT dr.name AS driver_name,dr.phone AS driver_phone,v.plate,t.status AS trip_status FROM trip_stops s JOIN trips t ON t.id=s.trip_id JOIN vehicles v ON v.id=t.vehicle_id LEFT JOIN drivers dr ON dr.id=t.driver_id WHERE s.order_id=o.id AND s.kind='return' ORDER BY s.id DESC LIMIT 1) r ON true " +
            "WHERE o.customer_booking_id IN ("+placeholders+")",ids.toArray());
        if(rows.isEmpty()) return rows;
        var events=jdbc.queryForList("SELECT e.*,o.customer_booking_id AS booking_id FROM customer_tracking_events e JOIN orders o ON o.id=e.order_id WHERE o.customer_booking_id IN ("+placeholders+") ORDER BY e.id",ids.toArray());
        for(var row:rows) row.put("events",events.stream().filter(e->Objects.equals(e.get("booking_id"),row.get("booking_id"))).toList());
        return rows;
    }
}
