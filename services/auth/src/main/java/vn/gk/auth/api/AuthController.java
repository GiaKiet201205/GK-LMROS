package vn.gk.auth.api;

import vn.gk.auth.security.AuthService;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth=auth; }
    private String cookie(String token, long maxAge, HttpServletRequest req) {
        return ResponseCookie.from(AuthService.cookieName(req),token).httpOnly(true).secure(req.isSecure()).sameSite("Strict").path("/api").maxAge(maxAge).build().toString();
    }
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String,String> body, HttpServletRequest req) {
        String username=Objects.toString(body.get("username"), "").trim(), password=Objects.toString(body.get("password"), "");
        if(username.isBlank()||password.isBlank()||username.length()>100 || password.length()>256) return ResponseEntity.badRequest().body(Map.of("message","Thông tin đăng nhập không hợp lệ"));
        String token=auth.login(username,password);
        if(token==null) return ResponseEntity.status(401).body(Map.of("message","Tên đăng nhập hoặc mật khẩu không đúng"));
        if (!AuthService.portal(req).equals(auth.user(token).get("role"))) {
            auth.logout(token);
            return ResponseEntity.status(403).body(Map.of("message", "Tài khoản không được phép đăng nhập vào cổng này"));
        }
        auth.logout(auth.token(req));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,cookie(token,43200,req)).body(auth.user(token));
    }
    @GetMapping("/me")
    public Object me(HttpServletRequest req) { return req.getAttribute("user"); }
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest req) {
        auth.logout(auth.token(req));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE,cookie("",0,req)).build();
    }
}
