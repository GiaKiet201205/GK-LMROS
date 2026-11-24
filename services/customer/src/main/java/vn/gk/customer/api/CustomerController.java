package vn.gk.customer.api;

import vn.gk.customer.accounts.CustomerAuth;
import vn.gk.customer.accounts.GoogleIdentity;
import vn.gk.customer.bookings.BookingService;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
public class CustomerController {
    private final GoogleIdentity google;private final CustomerAuth auth;private final BookingService bookings;private final JdbcTemplate jdbc;
    private final Map<String,long[]> attempts=new LinkedHashMap<>();
    public CustomerController(CustomerAuth auth,BookingService bookings,JdbcTemplate jdbc,GoogleIdentity google) { this.google=google;this.auth=auth;this.bookings=bookings;this.jdbc=jdbc; }
    private void rate(String key) {rate(key,10);}
    private synchronized void rate(String phone,int maximum) {
        long now=System.currentTimeMillis();attempts.entrySet().removeIf(e->e.getValue()[0]<now);
        var counter=attempts.computeIfAbsent(phone,key->new long[]{now+300000,0});
        if(++counter[1]>maximum) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Bạn đã thử nhiều lần. Hãy chờ 5 phút rồi thử lại.");
        while(attempts.size()>2000) attempts.remove(attempts.keySet().iterator().next());
    }
    private UUID customer(HttpServletRequest req) { return (UUID)((Map<?,?>)req.getAttribute("user")).get("id"); }
    private ResponseEntity<?> session(String token,HttpServletRequest req) {
        if(token==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Số điện thoại hoặc mật khẩu không đúng");
        auth.logout(auth.token(req));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,ResponseCookie.from(CustomerAuth.COOKIE,token).httpOnly(true).secure(req.isSecure()).sameSite("Strict").path("/api/customer").maxAge(43200).build().toString()).body(auth.user(token));
    }
    @GetMapping("/api/health") public Map<String,Object> health() { return Map.of("service","gk-customer","db",jdbc.queryForObject("SELECT 1",Integer.class)==1); }
    @PostMapping("/api/customer/auth/register") public ResponseEntity<?> register(@RequestBody Map<String,String> body,HttpServletRequest req) {
        String phone=CustomerAuth.phone(body.get("phone"));rate(phone);return session(auth.register(body.get("name"),phone,body.get("password")),req);
    }
    @PostMapping("/api/customer/auth/login") public ResponseEntity<?> login(@RequestBody Map<String,String> body,HttpServletRequest req) {
        String phone=CustomerAuth.phone(body.get("username"));rate(phone);return session(auth.login(phone,body.get("password")),req);
    }

    @GetMapping("/api/customer/auth/google/config") public ResponseEntity<?> googleConfig(HttpServletRequest req) {
        if(!google.enabled())return ResponseEntity.ok(Map.of("enabled",false));
        rate("google-start:"+req.getRemoteAddr(),120);String nonce=auth.googleNonce();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,ResponseCookie.from(CustomerAuth.GOOGLE_NONCE,nonce).httpOnly(true).secure(req.isSecure()).sameSite("Strict").path("/api/customer/auth").maxAge(300).build().toString()).body(Map.of("enabled",true,"clientId",google.clientId(),"nonce",nonce));
    }
    @PostMapping("/api/customer/auth/google") public ResponseEntity<?> googleLogin(@RequestBody Map<String,String> body,HttpServletRequest req) {
        rate("google-login:"+req.getRemoteAddr(),300);String nonce=null;if(req.getCookies()!=null)for(var c:req.getCookies())if(CustomerAuth.GOOGLE_NONCE.equals(c.getName()))nonce=c.getValue();
        if(nonce!=null)rate("google-nonce:"+nonce);
        if(body.get("phone")!=null&&!body.get("phone").isBlank())rate("google-phone:"+CustomerAuth.phone(body.get("phone")));
        var identity=google.verify(body.get("credential"),nonce);
        var result=auth.googleLogin(identity,nonce,body.get("name"),body.get("phone"),body.get("password"));
        if(result.token()==null)return ResponseEntity.accepted().body(Map.of("requiresProfile",true,"name",result.name(),"email",result.email()));
        var response=session(result.token(),req);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,response.getHeaders().getFirst(HttpHeaders.SET_COOKIE),ResponseCookie.from(CustomerAuth.GOOGLE_NONCE,"").httpOnly(true).secure(req.isSecure()).sameSite("Strict").path("/api/customer/auth").maxAge(0).build().toString()).body(response.getBody());
    }
    @GetMapping("/api/customer/auth/me") public Object me(HttpServletRequest req) { return req.getAttribute("user"); }
    @PostMapping("/api/customer/auth/logout") public ResponseEntity<?> logout(HttpServletRequest req) {
        auth.logout(auth.token(req));return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE,ResponseCookie.from(CustomerAuth.COOKIE,"").httpOnly(true).sameSite("Strict").path("/api/customer").maxAge(0).build().toString()).build();
    }
    public record BookingRequest(UUID requestId,Map<String,Object> order) {}
    @PostMapping("/api/customer/bookings") public Map<String,Object> create(@RequestBody BookingRequest body,HttpServletRequest req) { return bookings.create(customer(req),body.requestId(),body.order()); }
    @GetMapping("/api/customer/bookings") public Map<String,Object> list(@RequestParam(defaultValue="0") int offset,HttpServletRequest req) { return bookings.list(customer(req),offset); }
    @GetMapping("/api/customer/bookings/{id}") public Map<String,Object> detail(@PathVariable UUID id,HttpServletRequest req) { return bookings.booking(customer(req),id); }
    @GetMapping("/api/customer/notifications") public Map<String,Object> notices(HttpServletRequest req) { return bookings.notices(customer(req)); }
    @PostMapping("/api/customer/notifications/read") public ResponseEntity<?> read(@RequestBody(required=false) Map<String,Long> body,HttpServletRequest req) { bookings.read(customer(req),body==null?null:body.get("id"));return ResponseEntity.noContent().build(); }
    @PostMapping("/api/customer/pricing/quote") public Map<?,?> quote(@RequestBody Map<String,Object> body){return bookings.quote(body);}
    @GetMapping("/api/customer/locations") public Map<?,?> locations(@RequestParam String q) { return bookings.locations(q); }
}
