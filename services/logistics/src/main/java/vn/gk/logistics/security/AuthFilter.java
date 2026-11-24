package vn.gk.logistics.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

@Component
public class AuthFilter extends OncePerRequestFilter {
    private final AuthService auth;
    private final JdbcTemplate jdbc;
    public AuthFilter(AuthService auth, JdbcTemplate jdbc) { this.auth=auth; this.jdbc=jdbc; }
    private void reject(HttpServletResponse res,int status,String message) throws IOException {
        res.setStatus(status); res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"message\":\""+message+"\"}");
    }
    private boolean owns(ApiAccessPolicy.Grant grant,Map<String,Object> user) {
        if(grant.ownership()==ApiAccessPolicy.Ownership.NONE)return true;
        if(grant.ownership()==ApiAccessPolicy.Ownership.VEHICLE)
            return grant.variables().get("vehicleCode").equals(user.get("vehicle_code"));
        long id;
        try {id=Long.parseLong(grant.variables().get("id"));}catch(NumberFormatException e){return false;}
        if(id<=0||id>Integer.MAX_VALUE)return false;
        String sql=grant.ownership()==ApiAccessPolicy.Ownership.TRIP
            ?"SELECT count(*) FROM trips WHERE id=? AND driver_id=?"
            :"SELECT count(*) FROM trip_stops s JOIN trips t ON t.id=s.trip_id WHERE s.id=? AND t.driver_id=?";
        return jdbc.queryForObject(sql,Integer.class,id,user.get("driver_id"))>0;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        String path=req.getRequestURI();
        if(!ApiAccessPolicy.canonicalPath(path)) {reject(res,403,"Đường dẫn không hợp lệ");return;}
        if(path.startsWith("/internal/")) {chain.doFilter(req,res);return;} // InternalFilter verifies the service token.
        if(!path.startsWith("/api/")) {reject(res,404,"Không tìm thấy chức năng");return;}
        res.setHeader("Cache-Control","no-store");
        if(path.equals("/api/health")&&Set.of("GET","HEAD").contains(req.getMethod())) {chain.doFilter(req,res);return;}
        if (!Set.of("dispatcher", "driver").contains(AuthService.portal(req))) {
            reject(res,400,"Cổng truy cập không hợp lệ"); return;
        }
        if(!Set.of("GET","HEAD").contains(req.getMethod()) && !"1".equals(req.getHeader("X-GK"))) {
            reject(res,403,"Yêu cầu không hợp lệ"); return;
        }
        Map<String,Object> user;
        try { user=auth.user(req); }
        catch (org.springframework.web.client.RestClientException e) {
            reject(res,503,"Dịch vụ xác thực tạm thời không khả dụng"); return;
        }
        if(user==null) { reject(res,401,"Vui lòng đăng nhập lại"); return; }
        if (!AuthService.portal(req).equals(user.get("role"))) {
            reject(res,403,"Tài khoản không thuộc cổng hiện tại"); return;
        }
        String role=(String)user.get("role");
        if("driver".equals(role)&&(!(user.get("driver_id") instanceof Number did)||did.longValue()<=0)) {
            reject(res,403,"Hồ sơ tài xế không hợp lệ");return;
        }
        var grant=ApiAccessPolicy.grant(req.getMethod(),path,role);
        boolean allowed=false;
        try {if(grant.isPresent())allowed=owns(grant.get(),user);}
        catch(org.springframework.dao.DataAccessException e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Authorization ownership lookup failed",e);
            reject(res,503,"Tạm thời chưa thể kiểm tra quyền truy cập");return;
        }
        if(!allowed) { reject(res,403,"Bạn không có quyền truy cập dữ liệu này"); return; }
        req.setAttribute("user",user);
        chain.doFilter(req,res);
    }
}
