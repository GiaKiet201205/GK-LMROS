package vn.gk.logistics.drivers;

import org.springframework.jdbc.core.JdbcTemplate;
import vn.gk.logistics.dispatch.WorkDayPolicy;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

/** Driver directory owned by Logistics. Auth only receives profile data through this API. */
@RestController
@RequestMapping("/internal/drivers")
public class InternalDriverController {
    private final JdbcTemplate jdbc;
    private final WorkDayPolicy policy;
    public InternalDriverController(JdbcTemplate jdbc,WorkDayPolicy policy) { this.jdbc = jdbc;this.policy=policy; }

    @GetMapping
    public List<Map<String,Object>> drivers() {
        return jdbc.queryForList("SELECT d.id,d.code,d.name,d.phone,(d.archived_at IS NULL AND w.archived_at IS NULL) AS active FROM drivers d JOIN warehouses w ON w.id=d.warehouse_id ORDER BY d.id");
    }

    @GetMapping("/{id}")
    public Map<String,Object> driver(@PathVariable int id) {
        var rows = jdbc.queryForList("SELECT d.id,d.code,d.name,v.code AS vehicle_code FROM drivers d LEFT JOIN vehicles v ON v.id=coalesce((SELECT t.vehicle_id FROM trips t WHERE t.driver_id=d.id AND t.status<>'completed' AND (t.planned_start::date<=? OR t.status='in_progress') ORDER BY CASE t.status WHEN 'in_progress' THEN 0 ELSE 1 END,t.planned_start LIMIT 1),d.vehicle_id) WHERE d.id=? AND d.archived_at IS NULL",policy.dispatchDate(),id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.get(0);
    }
}
