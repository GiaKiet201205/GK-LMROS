package vn.gk.logistics.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Map;

@Component
public class AuthFilter extends OncePerRequestFilter {
    private final AuthService auth;
    private final JdbcTemplate jdbc;
    public AuthFilter(AuthService auth, JdbcTemplate jdbc) { this.auth=auth; this.jdbc=jdbc; }
    private void reject(HttpServletResponse res,int status,String message) throws IOException {
        res.setStatus(status); res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"message\":\""+message+"\"}");
    }
    @Override
    protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        String path=req.getRequestURI();
        if(!path.startsWith("/api/")) { chain.doFilter(req,res); return; }
        res.setHeader("Cache-Control","no-store");
        if (!java.util.Set.of("dispatcher", "driver").contains(AuthService.portal(req))) {
            reject(res,400,"Cổng truy cập không hợp lệ"); return;
        }
        if(!req.getMethod().equals("GET") && !req.getMethod().equals("HEAD") && !"1".equals(req.getHeader("X-GK"))) {
            reject(res,403,"Yêu cầu không hợp lệ"); return;
        }
        if(path.equals("/api/health") || (path.equals("/api/auth/login") && req.getMethod().equals("POST"))) { chain.doFilter(req,res); return; }
        Map<String,Object> user;
        try { user=auth.user(req); }
        catch (org.springframework.web.client.RestClientException e) {
            reject(res,503,"Dịch vụ xác thực tạm thời không khả dụng"); return;
        }
        if(user==null) { reject(res,401,"Vui lòng đăng nhập lại"); return; }
        if (!AuthService.portal(req).equals(user.get("role"))) {
            reject(res,403,"Tài khoản không thuộc cổng hiện tại"); return;
        }
        req.setAttribute("user",user);
        if("dispatcher".equals(user.get("role")) || path.equals("/api/auth/me") || path.equals("/api/auth/logout")) { chain.doFilter(req,res); return; }
        boolean allowed=false;
        if(req.getMethod().equals("GET")) allowed=path.equals("/api/driver/vehicle-costs")||path.equals("/api/driver/cash")||path.equals("/api/finance/mine")||path.equals("/api/driver/"+user.get("vehicle_code")+"/today")||path.equals("/api/driver/today")||path.equals("/api/driver/schedule")||path.equals("/api/driver/notifications")||path.equals("/api/workday")||path.equals("/api/driver/maintenance");
        if(req.getMethod().equals("POST")) {
            if(path.equals("/api/driver/vehicle-costs")||path.equals("/api/driver/cash/handovers"))allowed=true;
            else if(path.equals("/api/driver/maintenance")||path.matches("/api/driver/maintenance/[0-9]+/(estimate|complete)")) allowed=true;
            else if(path.equals("/api/driver/gps")||path.equals("/api/driver/schedule")||path.equals("/api/driver/notifications/read")) allowed=true; // Controller validates ownership before writing.
            else if(path.matches("/api/driver/trips/[0-9]+/(start|finish)"))
                allowed=jdbc.queryForObject("SELECT count(*) FROM trips WHERE id=? AND driver_id=?",Integer.class,Long.parseLong(path.split("/")[4]),user.get("driver_id"))>0;
            else if(path.matches("/api/driver/stops/[0-9]+/(depart|complete|fail|collect|payer|unpaid)"))
                allowed=jdbc.queryForObject("SELECT count(*) FROM trip_stops s JOIN trips t ON t.id=s.trip_id WHERE s.id=? AND t.driver_id=?",Integer.class,Long.parseLong(path.split("/")[4]),user.get("driver_id"))>0;
        }
        if(!allowed) { reject(res,403,"Bạn không có quyền truy cập dữ liệu này"); return; }
        chain.doFilter(req,res);
    }
}
