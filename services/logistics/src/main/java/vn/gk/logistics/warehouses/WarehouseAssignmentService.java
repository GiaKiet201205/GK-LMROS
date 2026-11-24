package vn.gk.logistics.warehouses;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
@Service
public class WarehouseAssignmentService {
 private final JdbcTemplate jdbc;
 public WarehouseAssignmentService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public record Selection(int warehouseId,double distanceMeters){}
 public static double metres(double a,double b,double c,double d){double p=Math.toRadians(a),q=Math.toRadians(c),dl=Math.toRadians(d-b);double h=Math.pow(Math.sin((q-p)/2),2)+Math.cos(p)*Math.cos(q)*Math.pow(Math.sin(dl/2),2);return 12742000*Math.asin(Math.sqrt(Math.min(1,Math.max(0,h))));}
 public Selection nearestPickup(double lat,double lng){
  if(!Double.isFinite(lat)||!Double.isFinite(lng)||Math.abs(lat)>90||Math.abs(lng)>180)throw new IllegalArgumentException("Tọa độ lấy hàng không hợp lệ");
  return jdbc.queryForList("SELECT id,lat,lng FROM warehouses WHERE archived_at IS NULL ORDER BY id").stream().map(w->new Selection(((Number)w.get("id")).intValue(),metres(lat,lng,((Number)w.get("lat")).doubleValue(),((Number)w.get("lng")).doubleValue()))).min(Comparator.comparingDouble(Selection::distanceMeters).thenComparingInt(Selection::warehouseId)).orElseThrow(()->new IllegalArgumentException("Chưa có kho hoạt động để tiếp nhận đơn"));
 }
}
