package vn.gk.logistics.dispatch;

import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Release expired, untouched reservations; never alter a trip that has started. */
@Service
@DependsOn("schemaUpgrade")
public class ReservationReconciler {
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final DistributionController distribution;private final WorkDayPolicy policy;private final boolean enabled;
 public ReservationReconciler(JdbcTemplate jdbc,TransactionTemplate tx,DistributionController distribution,WorkDayPolicy policy,@Value("${RESERVATION_RECONCILE_ENABLED:true}") boolean enabled) {this.jdbc=jdbc;this.tx=tx;this.distribution=distribution;this.policy=policy;this.enabled=enabled;}
 @Scheduled(fixedDelayString="${RESERVATION_RECONCILE_MS:15000}")
 public void tick() {
  if(!enabled)return;
  var candidates=jdbc.queryForList("SELECT id FROM trips t WHERE t.status='planned' AND (t.planned_start::date<? OR t.planned_end<?) AND NOT EXISTS(SELECT 1 FROM trip_stops s WHERE s.trip_id=t.id AND s.state<>'pending') ORDER BY t.id LIMIT 100",Integer.class,policy.dispatchDate(),policy.now().toLocalDateTime());
  for(var id:candidates)try {
   tx.executeWithoutResult(status->{
    // Same revision lock as driver progress and distribution confirmation.
    jdbc.queryForMap("SELECT revision FROM routing_state WHERE id=1 FOR UPDATE");
    var rows=jdbc.queryForList("SELECT * FROM trips WHERE id=? AND status='planned' AND (planned_start::date<? OR planned_end<?) FOR UPDATE",id,policy.dispatchDate(),policy.now().toLocalDateTime());
    if(rows.isEmpty())return;var trip=rows.get(0);distribution.release(id);
    var message=trip.get("code")+" chưa xuất phát và đã quá thời gian. Đơn được trả về hàng chờ; cần tính phương án mới theo giờ hẹn còn hiệu lực.";
    jdbc.update("INSERT INTO dispatcher_notifications(source_key,type,level,title,message,href,warehouse_id) VALUES(?,'reservation_expired','warning','Đã thu hồi chuyến chờ quá hạn',?,'/distribution',?) ON CONFLICT(source_key) DO NOTHING","reservation-expired:"+id,message,trip.get("warehouse_id"));
    if(trip.get("driver_id")!=null)jdbc.update("INSERT INTO driver_notifications(driver_id,source_key,type,title,message) VALUES(?,?,'reservation_expired','Chuyến chờ đã quá thời gian',?) ON CONFLICT(source_key) DO NOTHING",trip.get("driver_id"),"reservation-expired:"+id,message);
   });
  }catch(ResponseStatusException changed){org.slf4j.LoggerFactory.getLogger(getClass()).debug("Reservation {} changed before expiry release",id);}catch(Exception error){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Could not reconcile reservation {} ({})",id,error.getClass().getSimpleName());}
 }
}
