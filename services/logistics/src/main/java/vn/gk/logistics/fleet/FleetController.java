package vn.gk.logistics.fleet;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import vn.gk.logistics.dispatch.RoutingService;
import vn.gk.logistics.dispatch.WorkDayPolicy;
import vn.gk.logistics.validation.OrderInputs;
import java.util.*;

@RestController
@RequestMapping("/api/fleet")
public class FleetController {
 private final JdbcTemplate jdbc;private final RoutingService routing;private final WorkDayPolicy policy;private final ObjectMapper json;
 public FleetController(JdbcTemplate jdbc,RoutingService routing,WorkDayPolicy policy,ObjectMapper json){this.jdbc=jdbc;this.routing=routing;this.policy=policy;this.json=json;}
 private long admin(HttpServletRequest req){var u=(Map<?,?>)req.getAttribute("user");if(u==null||!"dispatcher".equals(u.get("role")))throw new ResponseStatusException(HttpStatus.FORBIDDEN);return ((Number)u.get("id")).longValue();}
 private String table(String kind){if(!Set.of("warehouses","vehicles","drivers").contains(kind))throw new ResponseStatusException(HttpStatus.NOT_FOUND);return kind;}
 private void conflict(String text){throw new ResponseStatusException(HttpStatus.CONFLICT,text);}
 private Map<String,Object> row(String t,int id){var r=jdbc.queryForList("SELECT * FROM "+t+" WHERE id=? FOR UPDATE",id);if(r.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy hồ sơ");return r.get(0);}
 private String text(Map<String,Object>b,String key,int min,int max){String v=Objects.toString(b.get(key),"").strip();if(v.length()<min||v.length()>max||v.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Kiểm tra trường "+key+" ("+min+"–"+max+" ký tự)");return v;}
 private double number(Map<String,Object>b,String k,double lo,double hi){if(!(b.get(k) instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<lo||n.doubleValue()>hi)throw new IllegalArgumentException("Giá trị không hợp lệ: "+k);return n.doubleValue();}
 private int id(Map<String,Object>b,String k){double n=number(b,k,1,Integer.MAX_VALUE);if(n!=Math.rint(n))throw new IllegalArgumentException("Mã hồ sơ không hợp lệ");return (int)n;}
 private boolean changed(Map<String,Object>old,String k,Object value){return !Objects.toString(old.get(k),"").equals(Objects.toString(value,""));}
 private void activeWarehouse(int id){if(jdbc.queryForObject("SELECT count(*) FROM warehouses WHERE id=? AND archived_at IS NULL",Integer.class,id)==0)conflict("Chọn kho đang sử dụng");}
 private void free(String t,int id){String key=t.equals("drivers")?"driver_id":t.equals("vehicles")?"vehicle_id":"warehouse_id";if(jdbc.queryForObject("SELECT count(*) FROM trips WHERE "+key+"=? AND status<>'completed'",Integer.class,id)>0)conflict("Còn chuyến chờ xuất phát hoặc đang chạy; xử lý chuyến trước khi thay đổi phân công");if(t.equals("vehicles")&&jdbc.queryForObject("SELECT count(*) FROM vehicle_maintenance WHERE vehicle_id=? AND state<>'completed'",Integer.class,id)>0)conflict("Cần xử lý xong phiếu bảo dưỡng trước");}
 private String encode(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
 private Map<String,Object> log(String t,int id,String action,Object before,long uid){var after=row(t,id);jdbc.update("INSERT INTO fleet_changes(entity,entity_id,action,before_data,after_data,created_by) VALUES(?,?,?,?::jsonb,?::jsonb,?)",t,id,action,encode(before),encode(after),uid);return after;}
 @PostMapping("/{kind}") @Transactional public Map<String,Object> create(@PathVariable String kind,@RequestBody Map<String,Object>b,HttpServletRequest req){return save(kind,null,b,req);}
 @PutMapping("/{kind}/{id}") @Transactional public Map<String,Object> update(@PathVariable String kind,@PathVariable int id,@RequestBody Map<String,Object>b,HttpServletRequest req){return save(kind,id,b,req);}
 private Map<String,Object> save(String kind,Integer key,Map<String,Object>b,HttpServletRequest req){long uid=admin(req);String t=table(kind);routing.changed();var old=key==null?null:row(t,key);if(old!=null&&old.get("archived_at")!=null)conflict("Khôi phục hồ sơ trước khi sửa");String code=text(b,"code",2,30).toUpperCase(Locale.ROOT);if(!code.matches("[A-Z][A-Z0-9_-]{1,29}"))throw new IllegalArgumentException("Mã gồm chữ Latin, số, dấu gạch ngang hoặc gạch dưới");if(old!=null&&!code.equals(old.get("code")))conflict("Mã hồ sơ được giữ cố định; có thể sửa thông tin còn lại");if(jdbc.queryForObject("SELECT count(*) FROM "+t+" WHERE code=? AND id<>?",Integer.class,code,key==null?0:key)>0)conflict("Mã đã được sử dụng");
  int result;
  if(t.equals("warehouses")){
   String name=OrderInputs.name(text(b,"name",2,100)),address=text(b,"address",5,400),color=Objects.toString(b.get("color"),"#0f766e");if(!Set.of("#0f766e","#2563eb","#7c3aed").contains(color))throw new IllegalArgumentException("Chọn màu kho hợp lệ");double lat=number(b,"lat",-90,90),lng=number(b,"lng",-180,180);
   if(old!=null&&(Double.compare(((Number)old.get("lat")).doubleValue(),lat)!=0||Double.compare(((Number)old.get("lng")).doubleValue(),lng)!=0)){free(t,key);if(jdbc.queryForObject("SELECT count(*) FROM orders WHERE warehouse_id=? AND stage NOT IN ('delivered','returned')",Integer.class,key)>0)conflict("Kho còn hàng hoặc đơn chưa hoàn tất; không đổi vị trí kho");}
   if(key==null)result=jdbc.queryForObject("INSERT INTO warehouses(code,name,address,lat,lng,color) VALUES(?,?,?,?,?,?) RETURNING id",Integer.class,code,name,address,lat,lng,color);else{jdbc.update("UPDATE warehouses SET name=?,address=?,lat=?,lng=?,color=? WHERE id=?",name,address,lat,lng,color,key);result=key;}
  }else{
   int warehouse=id(b,"warehouseId");activeWarehouse(warehouse);
   if(t.equals("vehicles")){
    String plate=text(b,"plate",5,20).toUpperCase(Locale.ROOT).replaceAll("\\s+","");if(!plate.matches("[0-9]{2}[A-Z]{1,2}[0-9]?[-.]?[0-9]{3,5}(?:[.][0-9]{2})?"))throw new IllegalArgumentException("Biển số không hợp lệ, ví dụ 51D-600.01");double kg=number(b,"capacityKg",1,100000),m3=number(b,"capacityM3",.001,200),cost=number(b,"costPerKm",0,1000000);if(cost!=Math.rint(cost))throw new IllegalArgumentException("Chi phí/km phải là số đồng nguyên");
    if(jdbc.queryForObject("SELECT count(*) FROM vehicles WHERE regexp_replace(upper(plate),'[^A-Z0-9]','','g')=? AND id<>?",Integer.class,plate.replaceAll("[^A-Z0-9]",""),key==null?0:key)>0)conflict("Biển số đã được sử dụng");
    if(old!=null&&(changed(old,"warehouse_id",warehouse)||Double.compare(((Number)old.get("capacity_kg")).doubleValue(),kg)!=0||Double.compare(((Number)old.get("capacity_m3")).doubleValue(),m3)!=0||changed(old,"plate",plate)||Double.compare(((Number)old.get("cost_per_km")).doubleValue(),cost)!=0)){free(t,key);if(changed(old,"warehouse_id",warehouse)&&jdbc.queryForObject("SELECT count(*) FROM drivers WHERE vehicle_id=? AND archived_at IS NULL",Integer.class,key)>0)conflict("Gỡ tài xế được gắn với xe trước khi chuyển kho");}
    if(key==null)result=jdbc.queryForObject("INSERT INTO vehicles(code,plate,warehouse_id,capacity_kg,capacity_m3,cost_per_km) VALUES(?,?,?,?,?,?) RETURNING id",Integer.class,code,plate,warehouse,kg,m3,cost);else{jdbc.update("UPDATE vehicles SET plate=?,warehouse_id=?,capacity_kg=?,capacity_m3=?,cost_per_km=? WHERE id=?",plate,warehouse,kg,m3,cost,key);result=key;}
   }else{
    String name=OrderInputs.name(text(b,"name",2,100)),phone=OrderInputs.phone(text(b,"phone",1,30));Integer vehicle=b.get("vehicleId")==null?null:id(b,"vehicleId");
    if(jdbc.queryForObject("SELECT count(*) FROM drivers WHERE phone=? AND archived_at IS NULL AND id<>?",Integer.class,phone,key==null?0:key)>0)conflict("Số điện thoại đã được đăng ký cho tài xế khác");
    if(old!=null&&(changed(old,"warehouse_id",warehouse)||changed(old,"vehicle_id",vehicle)))free(t,key);
    if(vehicle!=null){if(jdbc.queryForObject("SELECT count(*) FROM vehicles WHERE id=? AND warehouse_id=? AND archived_at IS NULL",Integer.class,vehicle,warehouse)==0)conflict("Xe và tài xế phải cùng kho đang sử dụng");if(jdbc.queryForObject("SELECT count(*) FROM drivers WHERE vehicle_id=? AND archived_at IS NULL AND id<>?",Integer.class,vehicle,key==null?0:key)>0)conflict("Xe đã gắn với tài xế khác");if(old==null||changed(old,"vehicle_id",vehicle))free("vehicles",vehicle);}
    if(key==null){result=jdbc.queryForObject("INSERT INTO drivers(code,name,phone,warehouse_id,vehicle_id,joined_on) VALUES(?,?,?,?,?,?) RETURNING id",Integer.class,code,name,phone,warehouse,vehicle,policy.today());jdbc.update("INSERT INTO driver_salary_rates(driver_id,effective_month,monthly_amount,created_by) VALUES(?,?,15000000,?)",result,policy.today().withDayOfMonth(1),uid);jdbc.update("INSERT INTO driver_service_periods(driver_id,started_on) VALUES(?,?)",result,policy.today());}else{jdbc.update("UPDATE drivers SET name=?,phone=?,warehouse_id=?,vehicle_id=? WHERE id=?",name,phone,warehouse,vehicle,key);result=key;}
   }
  }
  return log(t,result,key==null?"created":"updated",old,uid);
 }
 @DeleteMapping("/{kind}/{id}") @Transactional public Map<String,Object> archive(@PathVariable String kind,@PathVariable int id,HttpServletRequest req){long uid=admin(req);String t=table(kind);routing.changed();var old=row(t,id);if(old.get("archived_at")!=null)return old;free(t,id);
  if(t.equals("warehouses")){if(jdbc.queryForObject("SELECT (SELECT count(*) FROM vehicles WHERE warehouse_id=? AND archived_at IS NULL)+(SELECT count(*) FROM drivers WHERE warehouse_id=? AND archived_at IS NULL)+(SELECT count(*) FROM orders WHERE warehouse_id=? AND stage NOT IN ('delivered','returned'))",Integer.class,id,id,id)>0)conflict("Chuyển xe, tài xế và xử lý hết đơn trước khi xóa kho");}
  if(t.equals("vehicles")&&jdbc.queryForObject("SELECT count(*) FROM drivers WHERE vehicle_id=? AND archived_at IS NULL",Integer.class,id)>0)conflict("Gỡ tài xế được gắn với xe trước khi xóa");
  jdbc.update("UPDATE "+t+" SET archived_at=?"+(t.equals("drivers")?",vehicle_id=NULL":"")+" WHERE id=?",policy.now().toOffsetDateTime(),id);if(t.equals("drivers"))jdbc.update("UPDATE driver_service_periods SET ended_on=? WHERE driver_id=? AND ended_on IS NULL",policy.today(),id);return log(t,id,"archived",old,uid);
 }
 @PostMapping("/{kind}/{id}/restore") @Transactional public Map<String,Object> restore(@PathVariable String kind,@PathVariable int id,HttpServletRequest req){long uid=admin(req);String t=table(kind);routing.changed();var old=row(t,id);if(old.get("archived_at")==null)return old;if(!t.equals("warehouses"))activeWarehouse(((Number)old.get("warehouse_id")).intValue());if(t.equals("drivers")&&jdbc.queryForObject("SELECT count(*) FROM drivers WHERE phone=? AND archived_at IS NULL",Integer.class,old.get("phone"))>0)conflict("Số điện thoại đang thuộc tài xế khác");jdbc.update("UPDATE "+t+" SET archived_at=NULL WHERE id=?",id);if(t.equals("drivers"))jdbc.update("INSERT INTO driver_service_periods(driver_id,started_on) VALUES(?,?)",id,policy.today());return log(t,id,"restored",old,uid);}
}
