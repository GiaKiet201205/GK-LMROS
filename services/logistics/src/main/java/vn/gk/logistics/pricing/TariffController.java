package vn.gk.logistics.pricing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import vn.gk.logistics.dispatch.RoutingService;
import java.util.*;
@RestController
public class TariffController {
 private final JdbcTemplate jdbc;private final RoutingService routing;
 public TariffController(JdbcTemplate jdbc,RoutingService routing){this.jdbc=jdbc;this.routing=routing;}
 @GetMapping({"/api/pricing/tariffs","/internal/customer/pricing/tariffs"}) public Map<String,Object> list(){return Map.of("tiers",jdbc.queryForList("SELECT * FROM shipping_tariffs ORDER BY min_kg"),"settings",jdbc.queryForMap("SELECT * FROM pricing_settings WHERE id=1"));}
 public record Tier(int id,Long rateVnd,Boolean enabled){}
 public record Update(List<Tier> tiers,Long fixedTripCostVnd,Long minimumFareVnd){}
 @PostMapping("/api/pricing/tariffs") @Transactional public Map<String,Object> update(@RequestBody Update body){
  if(body.tiers()==null||body.tiers().size()!=4||body.fixedTripCostVnd()==null||body.fixedTripCostVnd()<0||body.fixedTripCostVnd()>10000000)throw new IllegalArgumentException("Kiểm tra lại bốn mức giá và chi phí chuyến.");
  if(body.minimumFareVnd()!=null&&(body.minimumFareVnd()<0||body.minimumFareVnd()>10000000))throw new IllegalArgumentException("Cước tối thiểu không hợp lệ");routing.changed();Set<Integer> ids=new HashSet<>();
  for(var tier:body.tiers()){if(tier==null||tier.rateVnd()==null||tier.enabled()==null||!ids.add(tier.id())||tier.id()<1||tier.id()>4||tier.rateVnd()<0||tier.rateVnd()>10000000)throw new IllegalArgumentException("Mức giá không hợp lệ");jdbc.update("UPDATE shipping_tariffs SET rate_vnd=?,enabled=?,updated_at=now() WHERE id=?",tier.rateVnd(),tier.enabled(),tier.id());}
  jdbc.update("UPDATE pricing_settings SET fixed_trip_cost_vnd=?,minimum_fare_vnd=coalesce(?,minimum_fare_vnd),revision=revision+1 WHERE id=1",body.fixedTripCostVnd(),body.minimumFareVnd());return list();
 }
}
