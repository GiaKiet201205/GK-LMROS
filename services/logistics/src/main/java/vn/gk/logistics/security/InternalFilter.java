package vn.gk.logistics.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class InternalFilter extends OncePerRequestFilter {
    private final byte[] token;
    public InternalFilter(@Value("${app.internal-token}") String token) {
        if (token.isBlank()) throw new IllegalArgumentException("INTERNAL_SERVICE_TOKEN must not be blank");
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        if (req.getRequestURI().startsWith("/internal/")) {
            String supplied = req.getHeader("X-Internal-Token");
            if (supplied == null || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
                res.sendError(403); return;
            }
        }
        chain.doFilter(req, res);
    }
}
