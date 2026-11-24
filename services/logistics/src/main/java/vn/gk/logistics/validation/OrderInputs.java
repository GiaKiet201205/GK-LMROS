package vn.gk.logistics.validation;
import java.time.*;
import java.text.Normalizer;
import java.util.*;

/** Canonical input contract shared by staff and customer order entry. */
public final class OrderInputs {
 private OrderInputs(){}
 public static final ZoneId ZONE=ZoneId.of("Asia/Ho_Chi_Minh");
 private static final Map<String,String> LABELS=Map.ofEntries(Map.entry("pickupName","Tên người gửi"),Map.entry("customerName","Tên người nhận"),Map.entry("pickupPhone","Điện thoại người gửi"),Map.entry("phone","Điện thoại người nhận"),Map.entry("pickupAddress","Địa chỉ lấy"),Map.entry("address","Địa chỉ giao"),Map.entry("goodsDescription","Mô tả hàng"),Map.entry("pickupFrom","Giờ bắt đầu lấy"),Map.entry("pickupTo","Hạn lấy hàng"),Map.entry("deliveryFrom","Giờ bắt đầu giao"),Map.entry("deliveryTo","Hạn giao hàng"));
 public static class Invalid extends IllegalArgumentException {
  public final Map<String,String> fields;
  public Invalid(Map<String,String> fields){super(fields.values().iterator().next());this.fields=Map.copyOf(fields);}
 }
 public static String phone(String raw){String v=Objects.toString(raw,"").replaceAll("(?U)[\\s.()\\-]","");if(v.startsWith("+84"))v="0"+v.substring(3);if(!v.matches("0[0-9]{9}"))throw new IllegalArgumentException("Số điện thoại phải gồm 10 chữ số, bắt đầu bằng 0.");return v;}
 public static String name(String raw){String v=Normalizer.normalize(Objects.toString(raw,"").strip(),Normalizer.Form.NFC).replaceAll("(?U)\\s+"," ").strip();if(v.length()<2||v.length()>100||!v.matches("(?s).*\\p{L}.*")||v.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Tên phải có chữ, từ 2 đến 100 ký tự.");return v;}
 private static String text(Map<String,Object> in,String key,int min,int max,Map<String,String> errors){Object raw=in.get(key);String v=raw instanceof String?(String)raw:"";v=Normalizer.normalize(v.strip(),Normalizer.Form.NFC);if(v.length()<min||v.length()>max||v.indexOf('\u0000')>=0)errors.put(key,LABELS.getOrDefault(key,key)+" phải từ "+min+" đến "+max+" ký tự.");return v;}
 private static double number(Map<String,Object> in,String key,double min,double max,Map<String,String> errors){if(!(in.get(key) instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<min||n.doubleValue()>max){errors.put(key,key.equals("weightKg")?"Khối lượng từ 0,01 đến 10.000 kg.":key.equals("volumeM3")?"Thể tích từ 0,001 đến 100 m³.":"Tọa độ không hợp lệ.");return 0;}return n.doubleValue();}
 public static Map<String,Object> validate(Map<String,Object> in,LocalDateTime now){
  if(in==null)throw new IllegalArgumentException("Thiếu thông tin đơn hàng.");
  var errors=new LinkedHashMap<String,String>();var out=new LinkedHashMap<String,Object>();
  for(String key:List.of("pickupName","customerName")){try{out.put(key,name(text(in,key,2,100,errors)));}catch(IllegalArgumentException e){errors.put(key,LABELS.get(key)+": từ 2 đến 100 ký tự và phải có chữ.");}}
  for(String key:List.of("pickupPhone","phone")){try{out.put(key,phone(text(in,key,1,30,errors)));}catch(IllegalArgumentException e){errors.put(key,LABELS.get(key)+" phải gồm 10 chữ số, bắt đầu bằng 0.");}}
  for(String key:List.of("pickupAddress","address"))out.put(key,text(in,key,5,400,errors));
  out.put("goodsDescription",text(in,"goodsDescription",2,500,errors));out.put("note",text(in,"note",0,1000,errors));
  String payer=Objects.toString(in.get("paymentPayer"),"recipient");if(!Set.of("sender","recipient").contains(payer))errors.put("paymentPayer","Chọn người gửi hoặc người nhận thanh toán.");out.put("paymentPayer",payer);
  String type=Objects.toString(in.get("goodsType"),"other");if(!Set.of("documents","food","clothing","electronics","fragile","other").contains(type))errors.put("goodsType","Chọn loại hàng hợp lệ.");out.put("goodsType",type);
  for(String key:List.of("lat","pickupLat"))out.put(key,number(in,key,-90,90,errors));
  for(String key:List.of("lng","pickupLng"))out.put(key,number(in,key,-180,180,errors));
  out.put("weightKg",number(in,"weightKg",.01,10000,errors));out.put("volumeM3",number(in,"volumeM3",.001,100,errors));
  LocalDate earliest=now.toLocalTime().isBefore(LocalTime.of(20,0))?now.toLocalDate():now.toLocalDate().plusDays(1);
  var dates=new HashMap<String,LocalDateTime>();
  for(String key:List.of("pickupFrom","pickupTo","deliveryFrom","deliveryTo"))try{var value=LocalDateTime.parse(text(in,key,1,40,errors));dates.put(key,value);out.put(key,value.toString());if(value.toLocalDate().isBefore(earliest)||value.toLocalDate().isAfter(now.toLocalDate().plusDays(90)))errors.put(key,"Chọn ngày từ "+earliest+" đến "+now.toLocalDate().plusDays(90)+".");if(value.toLocalTime().isBefore(LocalTime.of(8,0))||value.toLocalTime().isAfter(LocalTime.of(20,0)))errors.put(key,"Chọn giờ trong ca 08:00–20:00.");}catch(Exception e){errors.put(key,LABELS.get(key)+" không hợp lệ.");}
  for(String prefix:List.of("pickup","delivery")){var from=dates.get(prefix+"From");var to=dates.get(prefix+"To");if(from!=null&&to!=null){if(!from.isBefore(to)||!from.toLocalDate().equals(to.toLocalDate()))errors.put(prefix+"To","Giờ kết thúc phải sau giờ bắt đầu trong cùng ngày.");else if(!to.isAfter(now))errors.put(prefix+"To","Khung giờ đã hết hạn.");}}
  if(dates.get("pickupFrom")!=null&&dates.get("deliveryTo")!=null&&!dates.get("pickupFrom").isBefore(dates.get("deliveryTo")))errors.put("deliveryTo","Hạn giao phải sau thời điểm có thể lấy hàng.");
  if(in.get("shippingQuoteId")!=null)try{out.put("shippingQuoteId",UUID.fromString(in.get("shippingQuoteId").toString()).toString());}catch(Exception e){errors.put("shippingQuoteId","Hãy tính lại cước.");}
  if(!errors.isEmpty())throw new Invalid(errors);return out;
 }
}
