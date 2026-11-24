package vn.gk.logistics.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
import java.util.Map;

/** Session introspection through the Auth API; no access to the Auth database. */
@Service
public class AuthService {
    private final RestTemplate rest;
    private final String authUrl;
    public AuthService(org.springframework.boot.web.client.RestTemplateBuilder builder, @Value("${app.auth-url}") String authUrl) {
        this.rest = builder.setConnectTimeout(java.time.Duration.ofSeconds(2)).setReadTimeout(java.time.Duration.ofSeconds(15)).build(); this.authUrl = authUrl;
    }
    public static String portal(HttpServletRequest req) {
        String portal = req.getHeader("X-GK-Portal");
        return portal == null ? "dispatcher" : portal;
    }
    public static String cookieName(HttpServletRequest req) { return "gk_" + portal(req) + "_session"; }
    public String token(HttpServletRequest req) {
        if (req.getCookies() != null) for (var c : req.getCookies())
            if (c.getName().equals(cookieName(req))) return c.getValue();
        return null;
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> user(HttpServletRequest req) {
        String token = token(req);
        if (token == null) return null;
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-GK-Portal", portal(req));
        headers.set(HttpHeaders.COOKIE, cookieName(req) + "=" + token);
        try {
            return rest.exchange(authUrl + "/api/auth/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) return null;
            throw e;
        }
    }
}
