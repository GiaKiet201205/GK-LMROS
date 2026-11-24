package vn.gk.customer.security;

import vn.gk.customer.accounts.CustomerAuth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
public class CustomerFilter extends OncePerRequestFilter {
    private final CustomerAuth auth;
    public CustomerFilter(CustomerAuth auth) { this.auth=auth; }
    private void reject(HttpServletResponse res,int status,String message) throws IOException {
        res.setStatus(status);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"message\":\""+message+"\"}");
    }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        String path=req.getRequestURI();res.setHeader("Cache-Control","no-store");
        if(path.equals("/api/health")&&req.getMethod().equals("GET")) { chain.doFilter(req,res);return; }
        if(!path.startsWith("/api/customer/")) { reject(res,404,"Không tìm thấy chức năng");return; }
        if(!"customer".equals(req.getHeader("X-GK-Portal"))) { reject(res,403,"Sử dụng cổng khách hàng GK");return; }
        if(!req.getMethod().equals("GET")&&!"1".equals(req.getHeader("X-GK"))) { reject(res,403,"Yêu cầu không hợp lệ");return; }
        if(req.getMethod().equals("GET")&&path.equals("/api/customer/auth/google/config")){chain.doFilter(req,res);return;}
        if(req.getMethod().equals("POST")&&(path.equals("/api/customer/auth/login")||path.equals("/api/customer/auth/register")||path.equals("/api/customer/auth/google"))) { chain.doFilter(req,res);return; }
        var user=auth.user(auth.token(req));if(user==null) { reject(res,401,"Vui lòng đăng nhập để tiếp tục");return; }
        req.setAttribute("user",user);chain.doFilter(req,res);
    }
}
