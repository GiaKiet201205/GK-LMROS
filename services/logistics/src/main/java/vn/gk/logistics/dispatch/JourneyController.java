package vn.gk.logistics.dispatch;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/journeys")
public class JourneyController {
    private final RoutingService routing;
    public JourneyController(RoutingService routing) { this.routing=routing; }
    @GetMapping
    public Map<String,Object> list(@RequestParam(required=false) Integer warehouse,@RequestParam(required=false) String phase) {
        return routing.journeys(warehouse,phase);
    }
}
