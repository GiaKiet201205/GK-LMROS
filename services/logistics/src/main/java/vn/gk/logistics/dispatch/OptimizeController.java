package vn.gk.logistics.dispatch;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class OptimizeController {
    private final RoutingService routing;
    public OptimizeController(RoutingService routing) { this.routing=routing; }
    @GetMapping("/routing") public Map<String,Object> status() { return routing.status(); }
    @PostMapping("/optimize") @Transactional
    public Map<String,Object> enqueue() { routing.changed(); return Map.of("queued",true,"message","Đã yêu cầu cập nhật tuyến trong nền"); }
}
