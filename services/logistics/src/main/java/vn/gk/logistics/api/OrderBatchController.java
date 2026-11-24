package vn.gk.logistics.api;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import vn.gk.logistics.dispatch.RoutingService;
import java.util.*;
@RestController
public class OrderBatchController {
 private final JdbcTemplate jdbc;private final ObjectMapper json;private final ApiController orders;private final RoutingService routing;
 public OrderBatchController(JdbcTemplate jdbc,ObjectMapper json,ApiController orders,RoutingService routing){this.jdbc=jdbc;this.json=json;this.orders=orders;this.routing=routing;}
 private String encode(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalArgumentException("Không đọc được yêu cầu hẹn lại");}}
 private Map<String,Object> decode(String v){try{return json.readValue(v,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException(e);}}
 private String schedule(int id){return jdbc.queryForObject("SELECT jsonb_build_object('pickup_from',pickup_from,'pickup_to',pickup_to,'delivery_from',tw_start,'delivery_to',tw_end)::text FROM orders WHERE id=?",String.class,id);}
 @PostMapping("/api/orders/reschedule-batch") @Transactional public Map<String,Object> reschedule(@RequestBody Map<String,Object>b,HttpServletRequest req){
  var u=(Map<?,?>)req.getAttribute("user");if(u==null||!"dispatcher".equals(u.get("role")))throw new ResponseStatusException(HttpStatus.FORBIDDEN);long uid=((Number)u.get("id")).longValue();
  UUID request;try{request=UUID.fromString(Objects.toString(b.get("requestId"),""));}catch(Exception e){throw new IllegalArgumentException("Thiếu mã yêu cầu hợp lệ");}
  if(!(b.get("ids") instanceof List<?> ids)||ids.isEmpty()||ids.size()>200)throw new IllegalArgumentException("Chọn từ 1 đến 200 đơn để hẹn lại");
  var keys=new TreeSet<Integer>();for(var raw:ids){try{if(!(raw instanceof Number))throw new IllegalArgumentException();int id=new java.math.BigDecimal(raw.toString()).intValueExact();if(id<=0||!keys.add(id))throw new IllegalArgumentException();}catch(Exception e){throw new IllegalArgumentException("Mã đơn không hợp lệ hoặc bị trùng");}}
  String reason=Objects.toString(b.get("reason"),"").strip();if(reason.length()<3||reason.length()>1000)throw new IllegalArgumentException("Lý do hẹn lại từ 3 đến 1.000 ký tự");
  jdbc.queryForMap("SELECT revision FROM routing_state WHERE id=1 FOR UPDATE");var saved=jdbc.queryForList("SELECT created_by,request::text,result::text FROM order_reschedule_batches WHERE id=?",request);
  if(!saved.isEmpty()){var old=saved.get(0);if(((Number)old.get("created_by")).longValue()!=uid)throw new ResponseStatusException(HttpStatus.FORBIDDEN);if(!decode((String)old.get("request")).equals(decode(encode(b))))throw new ResponseStatusException(HttpStatus.CONFLICT,"Mã yêu cầu đã dùng với nội dung khác");return decode((String)old.get("result"));}
  routing.changed();String marks=String.join(",",Collections.nCopies(keys.size(),"?"));var rows=jdbc.queryForList("SELECT id,code,stage FROM orders WHERE id IN ("+marks+") ORDER BY id FOR UPDATE",keys.toArray());
  if(rows.size()!=keys.size())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Một số đơn không còn tồn tại. Hãy tải lại danh sách");
  for(var row:rows){if(!Set.of("awaiting_pickup","at_warehouse").contains(row.get("stage"))||jdbc.queryForObject("SELECT count(*) FROM trip_stops st JOIN trips t ON t.id=st.trip_id WHERE st.order_id=? AND t.status<>'completed'",Integer.class,row.get("id"))>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Đơn "+row.get("code")+" đã có chuyến hoặc không còn ở bước chờ. Thu hồi chuyến và tải lại trước khi hẹn");}
  var result=Map.<String,Object>of("updated",keys.size(),"ids",new ArrayList<>(keys),"batchId",request.toString());
  jdbc.update("INSERT INTO order_reschedule_batches(id,created_by,request,result) VALUES(?,?,?::jsonb,?::jsonb)",request,uid,encode(b),encode(result));
  for(var id:keys){String previous=schedule(id);orders.rescheduleLocked(id,b);jdbc.update("INSERT INTO order_reschedule_items(batch_id,order_id,previous_schedule,new_schedule) VALUES(?,?,?::jsonb,?::jsonb)",request,id,previous,schedule(id));}
  return result;
 }
}
