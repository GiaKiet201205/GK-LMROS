package vn.gk.auth.security;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class AuthService {
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();
    @Value("${ADMIN_PASSWORD:201205}") private String adminPassword;
    private final RestTemplate rest;
    private final String logisticsUrl;
    private final String internalToken;
    public AuthService(JdbcTemplate jdbc, RestTemplate rest,
                       @Value("${app.logistics-url}") String logisticsUrl,
                       @Value("${app.internal-token}") String internalToken) {
        if (internalToken.isBlank()) throw new IllegalArgumentException("INTERNAL_SERVICE_TOKEN must not be blank");
        this.jdbc = jdbc; this.rest = rest; this.logisticsUrl = logisticsUrl; this.internalToken = internalToken;
    }
    private HttpEntity<Void> internalRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", internalToken);
        return new HttpEntity<>(headers);
    }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> drivers() {
        return (List<Map<String,Object>>) (List<?>) rest.exchange(logisticsUrl + "/internal/drivers", HttpMethod.GET, internalRequest(), List.class).getBody();
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> driver(Object id) {
        return rest.exchange(logisticsUrl + "/internal/drivers/" + id, HttpMethod.GET, internalRequest(), Map.class).getBody();
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS app_users (id serial PRIMARY KEY, username text UNIQUE NOT NULL, password_hash text NOT NULL, display_name text NOT NULL, role text NOT NULL CHECK(role IN ('dispatcher','driver')), driver_id int UNIQUE, active boolean NOT NULL DEFAULT true)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS auth_sessions (token_hash text PRIMARY KEY, user_id int NOT NULL REFERENCES app_users(id) ON DELETE CASCADE, expires_at timestamptz NOT NULL)");
        if (jdbc.queryForObject("SELECT count(*) FROM app_users WHERE username='admin'", Integer.class) == 0)
            jdbc.update("INSERT INTO app_users(username,password_hash,display_name,role) VALUES ('admin',?, 'Điều phối viên','dispatcher') ON CONFLICT DO NOTHING", encode(adminPassword));
        synchronizeDrivers();
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=15000,initialDelay=5000)
    public void refreshDrivers(){try{synchronizeDrivers();}catch(org.springframework.web.client.RestClientException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Driver directory synchronization will retry ({})",e.getClass().getSimpleName());}}
    public synchronized void synchronizeDrivers(){
        var directory=drivers();if(directory==null)throw new IllegalStateException("Missing driver directory");
        for(var d:directory){
            boolean active=!Boolean.FALSE.equals(d.get("active"));
            var users=jdbc.queryForList("SELECT id FROM app_users WHERE driver_id=? AND role='driver'",d.get("id"));
            if(!users.isEmpty()){
                jdbc.update("UPDATE app_users SET display_name=?,active=? WHERE id=?",d.get("name"),active,users.get(0).get("id"));
                if(!active)jdbc.update("DELETE FROM auth_sessions WHERE user_id=?",users.get(0).get("id"));
                continue;
            }
            String phone=Objects.toString(d.get("phone"),"").replaceAll("[^0-9]","");
            if(!active||!phone.matches("0[0-9]{9}"))continue;
            jdbc.update("INSERT INTO app_users(username,password_hash,display_name,role,driver_id) VALUES(?,?,?,'driver',?) ON CONFLICT DO NOTHING",d.get("code"),encode(phone),d.get("name"),d.get("id"));
        }
    }

    private byte[] derive(String password, byte[] salt) throws Exception {
        var spec = new PBEKeySpec(password.toCharArray(), salt, 210000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
    private String encode(String password) {
        try {
            byte[] salt = new byte[16]; random.nextBytes(salt);
            return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(derive(password,salt));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private boolean matches(String password, String encoded) {
        try {
            var parts = encoded.split(":");
            return MessageDigest.isEqual(derive(password,Base64.getDecoder().decode(parts[0])), Base64.getDecoder().decode(parts[1]));
        } catch (Exception e) { return false; }
    }
    private String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public String login(String username, String password) {
        if(!"admin".equals(username))synchronizeDrivers();
        var users = jdbc.queryForList("SELECT id,password_hash FROM app_users WHERE username=? AND active",username);
        if (users.isEmpty() || !matches(password,(String) users.get(0).get("password_hash"))) return null;
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("DELETE FROM auth_sessions WHERE expires_at <= now()");
        jdbc.update("INSERT INTO auth_sessions VALUES (?,?,now()+interval '12 hours')",hash(token),users.get(0).get("id"));
        return token;
    }
    public static String portal(HttpServletRequest req) {
        String portal = req.getHeader("X-GK-Portal");
        return portal == null ? "dispatcher" : portal;
    }
    public static String cookieName(HttpServletRequest req) { return "gk_" + portal(req) + "_session"; }
    public String token(HttpServletRequest req) {
        if (req.getCookies()!=null) for (var c:req.getCookies()) if (c.getName().equals(cookieName(req))) return c.getValue();
        return null;
    }
    public Map<String,Object> user(String token) {
        if (token==null) return null;
        var rows=jdbc.queryForList("SELECT u.id,u.username,u.display_name,u.role,u.driver_id FROM auth_sessions s JOIN app_users u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at>now() AND u.active",hash(token));
        if (rows.isEmpty()) return null;
        Map<String,Object> user = new LinkedHashMap<>(rows.get(0));
        user.put("vehicle_code", null);
        if (user.get("driver_id") != null) {try{user.put("vehicle_code", driver(user.get("driver_id")).get("vehicle_code"));}catch(org.springframework.web.client.HttpClientErrorException.NotFound e){logout(token);return null;}}
        return user;
    }
    public void logout(String token) { if(token!=null) jdbc.update("DELETE FROM auth_sessions WHERE token_hash=?",hash(token)); }
}
