package vn.gk.logistics.assistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import vn.gk.logistics.dispatch.WorkDayPolicy;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class AssistantContext {
 private final JdbcTemplate jdbc;private final WorkDayPolicy policy;private final ObjectMapper json;
 public record Source(String id,String title,String path,String content,String kind) {}
 public AssistantContext(JdbcTemplate jdbc,WorkDayPolicy policy,ObjectMapper json){this.jdbc=jdbc;this.policy=policy;this.json=json;}
 private String encode(Object value){try{return json.writerWithDefaultPrettyPrinter().writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public List<Source> live(String question,Integer warehouse) {
  if(warehouse!=null&&jdbc.queryForObject("SELECT count(*) FROM warehouses WHERE id=? AND archived_at IS NULL",Integer.class,warehouse)!=1)throw new IllegalArgumentException("Kho không còn hoạt động. Hãy chọn lại kho.");
  String q=AssistantKnowledge.fold(question);var sources=new ArrayList<Source>();
  String wh=warehouse==null?"":" AND warehouse_id=?";Object[] scope=warehouse==null?new Object[]{}:new Object[]{warehouse};
  if(q.matches("(?s).*(don|qua han|tre han|tong quan|phan phoi|phan chuyen|hang cho|bao nhieu).*")) {
   var states=jdbc.queryForList("SELECT stage,count(*) AS so_don,count(*) FILTER(WHERE is_demo) AS so_don_mo_phong FROM orders WHERE true"+wh+" GROUP BY stage ORDER BY stage",scope);
   sources.add(new Source("orders-live","Đơn hàng hiện tại","/orders",encode(Map.of("phạm_vi",warehouse==null?"Tất cả kho":"Kho ID "+warehouse,"đếm_theo_trạng_thái",states,"đơn_tạo_hôm_nay",jdbc.queryForObject("SELECT count(*) FROM orders WHERE created_at::date=?"+wh,Long.class,warehouse==null?new Object[]{policy.today()}:new Object[]{policy.today(),warehouse}),"đơn_trễ_hạn",jdbc.queryForMap("SELECT count(*) FILTER(WHERE stage IN ('awaiting_pickup','pickup_assigned','picking_up') AND pickup_to<?) AS tre_lay,count(*) FILTER(WHERE stage IN ('picked_up','at_warehouse','delivery_assigned','out_for_delivery','failed') AND tw_end<?) AS tre_giao FROM orders WHERE true"+wh,warehouse==null?new Object[]{policy.now().toLocalDateTime(),policy.now().toLocalDateTime()}:new Object[]{policy.now().toLocalDateTime(),policy.now().toLocalDateTime(),warehouse}),"ghi_chú","Đây là số đơn toàn bộ lịch sử trong phạm vi, không chỉ ngày hôm nay.")),"live"));
  }
  var codes=Pattern.compile("(?i)\\bGK-[A-Z0-9]+(?:-[A-Z0-9]+){1,4}\\b").matcher(question);var seen=new HashSet<String>();
  while(codes.find()&&seen.size()<3) {
   String code=codes.group().toUpperCase(Locale.ROOT);if(!seen.add(code))continue;
   var orders=jdbc.queryForList("SELECT o.id,o.code,o.stage,o.weight_kg,o.pickup_from,o.pickup_to,o.tw_start,o.tw_end,o.in_warehouse,o.shipping_fee_vnd,o.shipping_price_status,o.is_demo,p.payment_status,w.name AS warehouse FROM orders o LEFT JOIN order_payments p ON p.order_id=o.id LEFT JOIN warehouses w ON w.id=o.warehouse_id WHERE upper(o.code)=?"+(warehouse==null?"":" AND o.warehouse_id=?"),warehouse==null?new Object[]{code}:new Object[]{code,warehouse});
   if(orders.isEmpty()){sources.add(new Source("missing-"+code,"Tra cứu "+code,"/orders","Không tìm thấy đơn "+code+" trong phạm vi kho đang chọn.","live"));continue;}
   var o=new LinkedHashMap<>(orders.get(0));Object id=o.get("id");
   o.put("phân_công",jdbc.queryForList("SELECT t.code AS trip,t.status,t.phase,d.code AS driver_code,d.name AS driver,v.code AS vehicle,v.plate,s.state,s.fail_reason FROM trip_stops s JOIN trips t ON t.id=s.trip_id LEFT JOIN drivers d ON d.id=t.driver_id JOIN vehicles v ON v.id=t.vehicle_id WHERE s.order_id=? ORDER BY s.id DESC LIMIT 6",id));
   sources.add(new Source("order-"+id,"Đơn "+code,"/orders/"+id,encode(o),"live"));
  }
  if(q.matches("(?s).*(xe|tai xe|kho|lich|ca lam|nguon luc|bao duong|phan phoi|phan chuyen).*")) {
   var fleet=new LinkedHashMap<String,Object>();fleet.put("ngày_xét_lịch",policy.dispatchDate());
   fleet.put("tổng_xe_theo_trạng_thái",jdbc.queryForList("SELECT gk_vehicle_state(id,?) AS trang_thai,count(*) AS so_xe FROM vehicles WHERE archived_at IS NULL"+wh+" GROUP BY 1",warehouse==null?new Object[]{policy.dispatchDate()}:new Object[]{policy.dispatchDate(),warehouse}));
   fleet.put("tổng_tài_xế_theo_trạng_thái",jdbc.queryForList("SELECT gk_driver_state(id,?) AS trang_thai,count(*) AS so_tai_xe FROM drivers WHERE archived_at IS NULL"+wh+" GROUP BY 1",warehouse==null?new Object[]{policy.dispatchDate()}:new Object[]{policy.dispatchDate(),warehouse}));
   fleet.put("xe",jdbc.queryForList("SELECT code,plate,capacity_kg,capacity_m3,warehouse_id,gk_vehicle_state(id,?) AS trang_thai FROM vehicles WHERE archived_at IS NULL"+wh+" ORDER BY code LIMIT 12",warehouse==null?new Object[]{policy.dispatchDate()}:new Object[]{policy.dispatchDate(),warehouse}));
   fleet.put("tài_xế",jdbc.queryForList("SELECT code,name,warehouse_id,gk_driver_state(id,?) AS trang_thai FROM drivers WHERE archived_at IS NULL"+wh+" ORDER BY code LIMIT 12",warehouse==null?new Object[]{policy.dispatchDate()}:new Object[]{policy.dispatchDate(),warehouse}));
   fleet.put("giới_hạn","Danh sách tối đa 12 xe và 12 tài xế; không suy ra tổng nguồn lực nếu chạm giới hạn.");
   sources.add(new Source("fleet-live","Xe và tài xế theo ngày xét lịch","/distribution",encode(fleet),"live"));
  }
  if(q.matches("(?s).*(cuoc|bang gia|bao gia|kg|can nang|trong luong|phi giao).*"))sources.add(new Source("pricing-live","Bảng cước hiện hành","/pricing",encode(Map.of("mức_cân",jdbc.queryForList("SELECT min_kg,max_kg,rate_vnd,enabled FROM shipping_tariffs ORDER BY min_kg"),"cấu_hình",jdbc.queryForMap("SELECT minimum_fare_vnd,fixed_trip_cost_vnd FROM pricing_settings WHERE id=1"))),"live"));
  if(q.matches("(?s).*(doanh thu|loi nhuan|chi phi|tai chinh|luong|lai|lo).*")) {
   YearMonth month=month(question,policy.today());
   if(month.atDay(1).isAfter(policy.today())){sources.add(new Source("finance-live","Sổ tài chính "+month,"/reports","Kỳ được hỏi chưa bắt đầu. Chưa có số liệu thực hiện để kết luận doanh thu, chi phí hoặc lợi nhuận.","live"));return sources;}
   LocalDate end=month.atEndOfMonth().isAfter(policy.today())?policy.today():month.atEndOfMonth();
   var totals=jdbc.queryForMap("SELECT coalesce(sum(revenue),0) AS doanh_thu,coalesce(sum(expense),0) AS chi_phi,coalesce(sum(revenue-expense),0) AS loi_nhuan,count(*) AS so_but_toan FROM finance_ledger WHERE entry_date BETWEEN ? AND ?"+wh,warehouse==null?new Object[]{month.atDay(1),end}:new Object[]{month.atDay(1),end,warehouse});
   totals.put("số_bút_toán_mô_phỏng",jdbc.queryForObject("SELECT count(*) FROM finance_entries WHERE is_demo AND entry_date BETWEEN ? AND ?"+wh,Long.class,warehouse==null?new Object[]{month.atDay(1),end}:new Object[]{month.atDay(1),end,warehouse}));
   sources.add(new Source("finance-live","Sổ tài chính "+month,"/reports",encode(Map.of("từ_ngày",month.atDay(1),"đến_ngày",end,"số_liệu_đã_ghi_sổ",totals,"giới_hạn","Không suy ra khoản chi chưa duyệt hoặc dữ liệu ngoài kỳ này. Lương theo số liệu đã phân bổ trong sổ.")),"live"));
  }
  return sources;
 }
 static YearMonth month(String question,LocalDate today) {
  String q=AssistantKnowledge.fold(question);String first=q.split("\n",2)[0];
  if(first.contains("thang truoc"))return YearMonth.from(today).minusMonths(1);
  if(first.contains("thang nay"))return YearMonth.from(today);
  var m=Pattern.compile("(?:thang\\s*)?(0?[1-9]|1[0-2])[/.-](20[0-9]{2})").matcher(q);if(m.find())return YearMonth.of(Integer.parseInt(m.group(2)),Integer.parseInt(m.group(1)));
  if(q.contains("thang truoc"))return YearMonth.from(today).minusMonths(1);return YearMonth.from(today);
 }
}
