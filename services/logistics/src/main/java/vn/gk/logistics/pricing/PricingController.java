package vn.gk.logistics.pricing;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
public class PricingController {
 private final ShippingPricingService pricing;private final org.springframework.jdbc.core.JdbcTemplate jdbc;private final vn.gk.logistics.dispatch.RoutingService routing;
 public PricingController(ShippingPricingService pricing,org.springframework.jdbc.core.JdbcTemplate jdbc,vn.gk.logistics.dispatch.RoutingService routing){this.pricing=pricing;this.jdbc=jdbc;this.routing=routing;}
 @PostMapping({"/api/pricing/quote","/internal/customer/pricing/quote"})
 public Map<String,Object> quote(@RequestBody Map<String,Object> body){return pricing.view(pricing.quote(body));}
 @PostMapping("/api/orders/{id}/fare") @org.springframework.transaction.annotation.Transactional
 public Map<String,Object> fare(@PathVariable int id,@RequestBody Map<String,Object> body){
  long amount;try{amount=new java.math.BigDecimal(body.get("amountVnd").toString()).longValueExact();}catch(Exception e){throw new IllegalArgumentException("Cước phải là số đồng nguyên.");}
  String note=java.util.Objects.toString(body.get("note"),"").trim();if(amount<0||amount>1000000000||note.isEmpty()||note.length()>500)throw new IllegalArgumentException("Nhập cước hợp lệ và lý do báo giá.");
  routing.changed();var order=jdbc.queryForMap("SELECT stage,delivery_cancelled_at,EXISTS(SELECT 1 FROM payment_collections p WHERE p.order_id=orders.id) AS collected FROM orders WHERE id=? FOR UPDATE",id);
  if(Boolean.TRUE.equals(order.get("collected")))throw new IllegalArgumentException("Đơn đã thu cước, không thay đổi số tiền đã thu.");
  if("delivered".equals(order.get("stage"))||order.get("delivery_cancelled_at")!=null)throw new IllegalArgumentException("Đơn đã giao xong, không thay đổi cước tại đây.");
  jdbc.update("UPDATE orders SET shipping_fee_vnd=?,shipping_price_status='confirmed',shipping_source='manual',shipping_rate_vnd=NULL,shipping_price_note=?,shipping_priced_at=now() WHERE id=?",amount,note,id);
  jdbc.update("INSERT INTO customer_tracking_events(order_id,type,reason) VALUES(?,'price_updated',?)",id,"Cước giao hàng: "+amount+" đ · "+note);
  return Map.of("updated",true);
 }

}
