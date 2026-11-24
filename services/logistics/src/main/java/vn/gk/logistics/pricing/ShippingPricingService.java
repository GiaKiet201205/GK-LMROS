package vn.gk.logistics.pricing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ShippingPricingService {
 private final JdbcTemplate jdbc;private final RoadDistance roads;
 public ShippingPricingService(JdbcTemplate jdbc,RoadDistance roads){this.jdbc=jdbc;this.roads=roads;}
 public static long billableKm(long metres){if(metres<0)throw new IllegalArgumentException("Quãng đường không hợp lệ");return Math.addExact(metres,500)/1000;}

 private double number(Map<String,Object> body,String key,double limit,boolean positive){if(!(body.get(key) instanceof Number n)||!Double.isFinite(n.doubleValue())||Math.abs(n.doubleValue())>limit||(positive&&n.doubleValue()<.01))throw new IllegalArgumentException("Thông tin không hợp lệ: "+key);return n.doubleValue();}
 private double[] inputs(Map<String,Object> body){return new double[]{number(body,"pickupLat",90,false),number(body,"pickupLng",180,false),number(body,"lat",90,false),number(body,"lng",180,false),number(body,"weightKg",10000,true)};}
 public Map<String,Object> view(Map<String,Object> row){var out=new LinkedHashMap<String,Object>();out.put("id",row.get("id"));out.put("distanceMeters",row.get("distance_m"));out.put("billableKm",row.get("billed_km"));out.put("rateVnd",row.get("rate_vnd"));out.put("amountVnd",row.get("amount_vnd"));out.put("source",row.get("source"));out.put("status",row.get("price_status"));out.put("weightKg",row.get("weight_kg"));return out;}
 @Transactional public Map<String,Object> quote(Map<String,Object> body){
  var p=inputs(body);var distance=roads.between(p[0],p[1],p[2],p[3]);var tiers=jdbc.queryForList("SELECT * FROM shipping_tariffs WHERE enabled AND min_kg<? AND max_kg>=? ORDER BY min_kg LIMIT 1",p[4],p[4]);
  Long rate=tiers.isEmpty()?null:((Number)tiers.get(0).get("rate_vnd")).longValue();Long amount=rate==null?null:Math.max(jdbc.queryForObject("SELECT minimum_fare_vnd FROM pricing_settings WHERE id=1",Long.class),Math.multiplyExact(billableKm(distance.metres()),rate));
  return jdbc.queryForMap("INSERT INTO shipping_quotes(id,pickup_lat,pickup_lng,delivery_lat,delivery_lng,weight_kg,distance_m,billed_km,rate_vnd,amount_vnd,source,price_status,rule_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING *",UUID.randomUUID(),p[0],p[1],p[2],p[3],p[4],distance.metres(),billableKm(distance.metres()),rate,amount,distance.source(),amount==null?"manual_quote":distance.source().equals("google")?"confirmed":"estimated","GK-TARIFF-"+jdbc.queryForObject("SELECT revision FROM pricing_settings WHERE id=1",Long.class));
 }
 public Map<String,Object> forOrder(Map<String,Object> body){
  if(body.get("shippingQuoteId")==null)return quote(body);
  UUID id;try{id=UUID.fromString(body.get("shippingQuoteId").toString());}catch(Exception e){throw new IllegalArgumentException("Mã báo giá không hợp lệ");}
  var rows=jdbc.queryForList("SELECT * FROM shipping_quotes WHERE id=?",id);if(rows.isEmpty())throw new IllegalArgumentException("Không tìm thấy báo giá. Hãy tính cước lại.");
  var row=rows.get(0);var p=inputs(body);String[] keys={"pickup_lat","pickup_lng","delivery_lat","delivery_lng","weight_kg"};
  for(int i=0;i<keys.length;i++)if(Math.abs(((Number)row.get(keys[i])).doubleValue()-p[i])>1e-8)throw new IllegalArgumentException("Địa chỉ hoặc khối lượng đã đổi. Hãy tính cước lại.");
  return row;
 }
 public void apply(long id,Map<String,Object> quote,String description,String type){
  jdbc.update("UPDATE orders SET goods_description=?,goods_type=?,shipping_quote_id=?,shipping_distance_m=?,billed_distance_km=?,shipping_rate_vnd=?,shipping_fee_vnd=?,shipping_source=?,shipping_price_status=?,shipping_priced_at=now() WHERE id=?",description,type,quote.get("id"),quote.get("distance_m"),quote.get("billed_km"),quote.get("rate_vnd"),quote.get("amount_vnd"),quote.get("source"),quote.get("price_status"),id);
 }
}
