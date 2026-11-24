package vn.gk.customer.accounts;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@DependsOn("customerSchema")
public class CustomerAuth {
    public static final String COOKIE="gk_customer_session";
    private final JdbcTemplate jdbc;private final SecureRandom random=new SecureRandom();
    public CustomerAuth(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public static String phone(String value) {return vn.gk.customer.validation.OrderInputs.phone(value);}
    public String token(HttpServletRequest req) { if(req.getCookies()!=null) for(var c:req.getCookies()) if(COOKIE.equals(c.getName())) return c.getValue();return null; }
    private String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    private byte[] derive(String password,byte[] salt) throws Exception {
        var spec=new PBEKeySpec(password.toCharArray(),salt,210000,256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
    private String encode(String password) {
        try { byte[] salt=new byte[16];random.nextBytes(salt);return Base64.getEncoder().encodeToString(salt)+":"+Base64.getEncoder().encodeToString(derive(password,salt)); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    private boolean matches(String password,String encoded) {
        try { var parts=encoded.split(":");return MessageDigest.isEqual(derive(password,Base64.getDecoder().decode(parts[0])),Base64.getDecoder().decode(parts[1])); }
        catch(Exception e) { return false; }
    }
    @Transactional public String register(String name,String phone,String password) {
        name=vn.gk.customer.validation.OrderInputs.name(name);phone=phone(phone);
        if(name.length()<2||name.length()>100) throw new IllegalArgumentException("Tên khách hàng từ 2 đến 100 ký tự");
        if(password==null||password.isBlank()||password.length()<8||password.length()>128) throw new IllegalArgumentException("Mật khẩu từ 8 đến 128 ký tự");
        UUID id=UUID.randomUUID();
        try { jdbc.update("INSERT INTO customers(id,name,phone,password_hash) VALUES(?,?,?,?)",id,name,phone,encode(password)); }
        catch(org.springframework.dao.DuplicateKeyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT,"Số điện thoại đã đăng ký. Hãy đăng nhập để tiếp tục."); }
        return session(id);
    }
    private String session(Object id) {
        byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("DELETE FROM customer_sessions WHERE expires_at<=now()");
        jdbc.update("INSERT INTO customer_sessions VALUES(?,?,now()+interval '12 hours')",hash(token),id);return token;
    }
    public String login(String phone,String password) {
        phone=phone(phone);
        if(password==null||password.length()>128) return null;
        var rows=jdbc.queryForList("SELECT id,password_hash FROM customers WHERE phone=?",phone);
        if(rows.isEmpty()||!matches(password,(String)rows.get(0).get("password_hash"))) return null;
        return session(rows.get(0).get("id"));
    }
    public Map<String,Object> user(String token) {
        if(token==null) return null;
        var rows=jdbc.queryForList("SELECT c.id,c.name AS display_name,c.phone,c.google_email AS email,(c.google_sub IS NOT NULL) AS google_linked FROM customer_sessions s JOIN customers c ON c.id=s.customer_id WHERE s.token_hash=? AND s.expires_at>now()",hash(token));
        if(rows.isEmpty()) return null;
        var user=new LinkedHashMap<String,Object>(rows.get(0));user.put("role","customer");user.put("username",user.get("phone"));return user;
    }
    public void logout(String token) { if(token!=null) jdbc.update("DELETE FROM customer_sessions WHERE token_hash=?",hash(token)); }
    public static final String GOOGLE_NONCE="gk_google_nonce";
    public String googleNonce() {
        byte[] bytes=new byte[32];random.nextBytes(bytes);String nonce=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("DELETE FROM google_login_challenges WHERE expires_at<=now()");
        jdbc.update("INSERT INTO google_login_challenges(nonce_hash,expires_at) VALUES(?,now()+interval '5 minutes')",hash(nonce));return nonce;
    }
    public record GoogleResult(String token,String name,String email) {}
    @Transactional public GoogleResult googleLogin(GoogleIdentity.Profile profile,String nonce,String name,String phone,String password) {
        if(nonce==null||nonce.length()!=43)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Phiên Google đã hết hạn. Chọn lại tài khoản Google.");
        if(jdbc.queryForList("SELECT nonce_hash FROM google_login_challenges WHERE nonce_hash=? AND expires_at>now() FOR UPDATE",hash(nonce)).isEmpty())throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Phiên Google đã dùng hoặc hết hạn. Chọn lại tài khoản Google.");
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?)",(long)profile.subject().hashCode());
        var linked=jdbc.queryForList("SELECT id FROM customers WHERE google_sub=?",profile.subject());Object id;
        if(!linked.isEmpty()){id=linked.get(0).get("id");jdbc.update("UPDATE customers SET google_email=? WHERE id=?",profile.email(),id); }
        else {
            if(phone==null||phone.isBlank())return new GoogleResult(null,profile.name(),profile.email());
            phone=phone(phone);var existing=jdbc.queryForList("SELECT id,password_hash,google_sub FROM customers WHERE phone=? FOR UPDATE",phone);
            if(existing.isEmpty()) {
                name=vn.gk.customer.validation.OrderInputs.name(name);
                id=UUID.randomUUID();jdbc.update("INSERT INTO customers(id,name,phone,password_hash,google_sub,google_email) VALUES(?,?,?,NULL,?,?)",id,name,phone,profile.subject(),profile.email());
            } else {
                var account=existing.get(0);
                if(password==null||password.length()>128||!matches(password,(String)account.get("password_hash")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Số điện thoại đã có tài khoản GK. Nhập mật khẩu GK của tài khoản đó để liên kết.");
                if(account.get("google_sub")!=null&&!profile.subject().equals(account.get("google_sub")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Tài khoản GK đã liên kết với một tài khoản Google khác.");
                id=account.get("id");jdbc.update("UPDATE customers SET google_sub=?,google_email=? WHERE id=?",profile.subject(),profile.email(),id);
            }
        }
        jdbc.update("DELETE FROM google_login_challenges WHERE nonce_hash=?",hash(nonce));
        return new GoogleResult(session(id),null,null);
    }

}
