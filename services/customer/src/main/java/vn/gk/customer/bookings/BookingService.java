package vn.gk.customer.bookings;

import vn.gk.customer.accounts.CustomerAuth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import java.time.*;
import java.util.*;

@Service
@DependsOn("customerSchema")
public class BookingService {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ObjectMapper json;
    private final RestTemplate rest;private final String logistics,token;
    public BookingService(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json,RestTemplateBuilder builder,@Value("${app.logistics-url}") String logistics,@Value("${app.internal-token}") String token) {
        if(token.isBlank()) throw new IllegalArgumentException("INTERNAL_SERVICE_TOKEN must not be blank");
        this.jdbc=jdbc;this.tx=tx;this.json=json;this.logistics=logistics;this.token=token;
        rest=builder.setConnectTimeout(Duration.ofSeconds(3)).setReadTimeout(Duration.ofSeconds(20)).build();
    }
    private <T> HttpEntity<T> internal(T body) { var headers=new HttpHeaders();headers.set("X-Internal-Token",token);return new HttpEntity<>(body,headers); }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("Không thể lưu thông tin đơn"); } }
    private Map<String,Object> decode(String value) { try { return json.readValue(value,new TypeReference<>(){}); } catch(Exception e) { throw new IllegalStateException(e); } }
    private static String text(Map<String,Object> input,String key,int max,boolean required) {
        String value=Objects.toString(input.get(key),"").trim();if((required&&value.isEmpty())||value.length()>max) throw new IllegalArgumentException("Kiểm tra lại thông tin "+key);return value;
    }
    public Map<String,Object> validate(Map<String,Object> input) {return vn.gk.customer.validation.OrderInputs.validate(input,LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")));}
    public Map<String,Object> create(UUID customer,UUID key,Map<String,Object> input) {
        if(key==null||input==null) throw new IllegalArgumentException("Thiếu mã yêu cầu hoặc thông tin đơn");
        var payload=validate(input);UUID fresh=UUID.randomUUID();
        UUID id=tx.execute(s->{
            jdbc.update("INSERT INTO bookings(id,customer_id,request_key,payload) VALUES(?,?,?,?::jsonb) ON CONFLICT(customer_id,request_key) DO NOTHING",fresh,customer,key,encode(payload));
            var row=jdbc.queryForMap("SELECT id,payload=?::jsonb AS same FROM bookings WHERE customer_id=? AND request_key=?",encode(payload),customer,key);
            if(!Boolean.TRUE.equals(row.get("same"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Yêu cầu này đã được gửi với thông tin khác. Kiểm tra Đơn của tôi trước khi tạo đơn mới.");
            return (UUID)row.get("id");
        });
        submit(id);syncIds(List.of(id));return booking(customer,id);
    }
    private boolean claim(UUID id) {
        return jdbc.update("UPDATE bookings SET locked_until=now()+interval '60 seconds' WHERE id=? AND state<>'rejected' AND (locked_until IS NULL OR locked_until<now())",id)>0;
    }
    @SuppressWarnings("unchecked")
    private void submit(UUID id) {
        if(!claim(id)) return;
        try {
            var row=jdbc.queryForMap("SELECT customer_id,state,payload::text FROM bookings WHERE id=?",id);
            if(!"pending".equals(row.get("state"))) return;
            var result=rest.exchange(logistics+"/internal/customer/orders",HttpMethod.POST,internal(Map.of("bookingId",id,"customerId",row.get("customer_id"),"order",decode((String)row.get("payload")))),Map.class).getBody();
            if(result==null||result.get("id")==null||result.get("code")==null) throw new IllegalStateException("Invalid order response");
            jdbc.update("UPDATE bookings SET state='submitted',order_id=?,order_code=?,error=NULL,next_attempt=now(),attempts=0 WHERE id=?",result.get("id"),result.get("code"),id);
        } catch(HttpClientErrorException e) {
            // Validation failures are terminal; transport/auth/rate-limit errors
            // stay in the durable outbox and never become phantom confirmations.
            if(e.getStatusCode().value()==400||e.getStatusCode().value()==422) {
                String message="Thông tin đơn chưa hợp lệ. Hãy tạo lại yêu cầu với địa chỉ và khung giờ đúng.";
                try { message=Objects.toString(decode(e.getResponseBodyAsString()).get("message"),message); } catch(Exception ignored) {}
                jdbc.update("UPDATE bookings SET state='rejected',error=? WHERE id=?",message,id);
            } else retry(id);
        } catch(Exception e) { retry(id); }
        finally { jdbc.update("UPDATE bookings SET locked_until=NULL WHERE id=?",id); }
    }
    private void retry(UUID id) {
        jdbc.update("UPDATE bookings SET attempts=attempts+1,error='Đã lưu yêu cầu, đang chờ kết nối điều phối',next_attempt=now()+interval '15 seconds'*least(attempts+1,20) WHERE id=?",id);
    }
    @Scheduled(fixedDelayString="${CUSTOMER_SYNC_MS:5000}")
    public void tick() {
        try {
            var due=jdbc.queryForList("SELECT id,state FROM bookings WHERE state<>'rejected' AND next_attempt<=now() AND (locked_until IS NULL OR locked_until<now()) ORDER BY next_attempt LIMIT 50");
            for(var row:due) if("pending".equals(row.get("state"))) submit((UUID)row.get("id"));
            syncIds(due.stream().map(row->(UUID)row.get("id")).toList());
        } catch(Exception e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Customer synchronization will retry ({})",e.getClass().getSimpleName()); }
    }
    @SuppressWarnings("unchecked")
    private void syncIds(List<UUID> ids) {
        if(ids.isEmpty()) return;
        var claimed=new ArrayList<UUID>();
        for(UUID id:ids) if(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE id=? AND state='submitted'",Integer.class,id)>0&&claim(id)) claimed.add(id);
        if(claimed.isEmpty()) return;
        try {
            var response=rest.exchange(logistics+"/internal/customer/sync",HttpMethod.POST,internal(claimed),List.class).getBody();
            if(response==null) throw new IllegalStateException("Invalid synchronization response");
            var received=new HashSet<UUID>();
            for(var entry:response) {
                var snapshot=new LinkedHashMap<String,Object>((Map<String,Object>)entry);UUID id=UUID.fromString((String)snapshot.get("booking_id"));
                if(!claimed.contains(id)) continue;
                var events=(List<Map<String,Object>>)snapshot.remove("events");
                tx.executeWithoutResult(s->{
                    var booking=jdbc.queryForMap("SELECT customer_id FROM bookings WHERE id=? FOR UPDATE",id);
                    if(!booking.get("customer_id").toString().equals(snapshot.get("customer_ref"))) throw new IllegalStateException("Customer owner mismatch");
                    if(events!=null) for(var event:events) {
                        int inserted=jdbc.update("INSERT INTO booking_events(source_id,booking_id,type,occurred_at,details) VALUES(?,?,?,?::timestamptz,?::jsonb) ON CONFLICT(source_id) DO NOTHING",event.get("id"),id,event.get("type"),event.get("created_at"),encode(event));
                        if(inserted>0) jdbc.update("INSERT INTO customer_notifications(customer_id,booking_id,source_event,type,created_at) VALUES(?,?,?,?,?::timestamptz)",booking.get("customer_id"),id,event.get("id"),event.get("type"),event.get("created_at"));
                    }
                    jdbc.update("UPDATE bookings SET snapshot=?::jsonb,last_sync=now(),error=NULL,next_attempt=now()+CASE WHEN ?='delivered' THEN interval '1 hour' ELSE interval '5 seconds' END WHERE id=?",encode(snapshot),snapshot.get("stage"),id);
                });
                var receipts=jdbc.queryForList("SELECT e.source_id,(n.read_at IS NOT NULL) AS is_read FROM booking_events e JOIN customer_notifications n ON n.source_event=e.source_id WHERE e.booking_id=? ORDER BY e.source_id DESC LIMIT 1000",id).stream().map(row->Map.of("eventId",row.get("source_id"),"read",row.get("is_read"))).toList();
                rest.exchange(logistics+"/internal/customer/event-receipts",HttpMethod.POST,internal(Map.of("bookingId",id,"events",receipts)),Void.class);
                received.add(id);
            }
            for(UUID id:claimed) if(!received.contains(id)) retry(id);
        } catch(Exception e) { for(UUID id:claimed) retry(id); }
        finally { for(UUID id:claimed) jdbc.update("UPDATE bookings SET locked_until=NULL WHERE id=?",id); }
    }
    private Map<String,Object> unpack(Map<String,Object> row) {
        var out=new LinkedHashMap<String,Object>(row);out.put("payload",decode((String)out.get("payload")));out.put("snapshot",decode((String)out.get("snapshot")));return out;
    }
    // Active customers refresh completed orders without polling every historical order every 5s.
    private void refreshVisible(UUID customer,List<UUID> ids) {
        if(ids.isEmpty())return;
        var args=new ArrayList<Object>();args.add(customer);args.addAll(ids);
        jdbc.update("UPDATE bookings SET next_attempt=least(next_attempt,now()) WHERE customer_id=? AND state='submitted' AND (last_sync IS NULL OR last_sync<now()-interval '15 seconds') AND id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",args.toArray());
    }
    public Map<String,Object> list(UUID customer,int offset) {
        var rows=jdbc.queryForList("SELECT id,state,order_code,payload::text,snapshot::text,error,created_at,last_sync FROM bookings WHERE customer_id=? ORDER BY created_at DESC,id LIMIT 20 OFFSET ?",customer,Math.max(0,offset));
        refreshVisible(customer,rows.stream().map(row->(UUID)row.get("id")).toList());
        return Map.of("items",rows.stream().map(this::unpack).toList(),"total",jdbc.queryForObject("SELECT count(*) FROM bookings WHERE customer_id=?",Integer.class,customer));
    }
    public Map<String,Object> booking(UUID customer,UUID id) {
        var rows=jdbc.queryForList("SELECT id,state,order_code,payload::text,snapshot::text,error,created_at,last_sync FROM bookings WHERE id=? AND customer_id=?",id,customer);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy đơn của bạn");
        refreshVisible(customer,List.of(id));
        var out=unpack(rows.get(0));out.put("events",jdbc.queryForList("SELECT details::text FROM booking_events WHERE booking_id=? ORDER BY occurred_at,source_id",id).stream().map(row->decode((String)row.get("details"))).toList());return out;
    }
    public Map<String,Object> notices(UUID customer) {
        refreshVisible(customer,jdbc.queryForList("SELECT id FROM bookings WHERE customer_id=? AND state='submitted' ORDER BY created_at DESC LIMIT 20",UUID.class,customer));
        return Map.of("items",jdbc.queryForList("SELECT n.id,n.booking_id,n.type,n.created_at,n.read_at,b.order_code FROM customer_notifications n JOIN bookings b ON b.id=n.booking_id WHERE n.customer_id=? ORDER BY n.id DESC LIMIT 100",customer),"unread",jdbc.queryForObject("SELECT count(*) FROM customer_notifications WHERE customer_id=? AND read_at IS NULL",Integer.class,customer));
    }
    public void read(UUID customer,Long id) {
        jdbc.update("UPDATE customer_notifications SET read_at=now() WHERE customer_id=? AND read_at IS NULL"+(id==null?"":" AND id=?"),id==null?new Object[]{customer}:new Object[]{customer,id});
        jdbc.update("UPDATE bookings SET next_attempt=now() WHERE customer_id=? AND state='submitted'",customer);
    }
    public Map<?,?> quote(Map<String,Object> input) {
        try{return rest.exchange(logistics+"/internal/customer/pricing/quote",HttpMethod.POST,internal(input),Map.class).getBody();}
        catch(HttpStatusCodeException e){String message="Chưa tính được cước. Vui lòng thử lại.";try{message=Objects.toString(decode(e.getResponseBodyAsString()).get("message"),message);}catch(Exception ignored){}throw new ResponseStatusException(e.getStatusCode().value()==400?HttpStatus.BAD_REQUEST:e.getStatusCode().value()==422?HttpStatus.UNPROCESSABLE_ENTITY:HttpStatus.SERVICE_UNAVAILABLE,message);}
        catch(RestClientException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Chưa tính được cước. Vui lòng thử lại.");}
    }
    public Map<?,?> locations(String q) {
        var uri=UriComponentsBuilder.fromUriString(logistics+"/internal/customer/locations").queryParam("q",q).build().encode().toUri();
        return rest.exchange(uri,HttpMethod.GET,internal(null),Map.class).getBody();
    }
}
