package vn.gk.auth.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class HealthController {
    private final JdbcTemplate jdbc;
    public HealthController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @GetMapping("/api/health")
    public Map<String,Object> health() {
        return Map.of("status", "ok", "application", "GK", "service", "gk-auth", "db", jdbc.queryForObject("SELECT 1", Integer.class) == 1);
    }
}
