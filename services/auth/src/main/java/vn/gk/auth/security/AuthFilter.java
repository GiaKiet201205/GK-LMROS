package vn.gk.auth.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.client.RestClientException;
import java.io.IOException;
import java.util.*;

@Component
public class AuthFilter extends OncePerRequestFilter {
    private final AuthService auth;
    public AuthFilter(AuthService auth) { this.auth = auth; }
    private void reject(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"message\":\"" + message + "\"}");
    }
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI();
        if (path.equals("/api/health") && req.getMethod().equals("GET")) {
            chain.doFilter(req, res); return;
        }
        res.setHeader("Cache-Control", "no-store");
        if (!Set.of("dispatcher", "driver").contains(AuthService.portal(req))) {
            reject(res, 400, "Cổng truy cập không hợp lệ"); return;
        }
        if (!Set.of("GET", "HEAD").contains(req.getMethod()) && !"1".equals(req.getHeader("X-GK"))) {
            reject(res, 403, "Yêu cầu không hợp lệ"); return;
        }
        if (path.equals("/api/auth/login") && req.getMethod().equals("POST")) {
            chain.doFilter(req, res); return;
        }
        Map<String,Object> user;
        try { user = auth.user(auth.token(req)); }
        catch (RestClientException e) {
            reject(res, 503, "Dịch vụ hồ sơ tài xế tạm thời không khả dụng"); return;
        }
        if (user == null) { reject(res, 401, "Vui lòng đăng nhập lại"); return; }
        if (!AuthService.portal(req).equals(user.get("role"))) {
            reject(res, 403, "Tài khoản không thuộc cổng hiện tại"); return;
        }
        req.setAttribute("user", user);
        chain.doFilter(req, res);
    }
}
