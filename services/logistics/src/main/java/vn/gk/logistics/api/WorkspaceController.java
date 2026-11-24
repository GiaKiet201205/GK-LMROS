package vn.gk.logistics.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api")
public class WorkspaceController {
    private final JdbcTemplate jdbc;
    public WorkspaceController(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private long user(HttpServletRequest request) { return ((Number)((Map<?,?>)request.getAttribute("user")).get("id")).longValue(); }
    public record Preferences(String theme,String density,Boolean notificationToasts,Integer defaultWarehouse) {}
    @GetMapping("/preferences")
    public Preferences preferences(HttpServletRequest request) {
        var rows=jdbc.queryForList("SELECT * FROM dispatcher_preferences WHERE user_id=?",user(request));
        if(rows.isEmpty()) return new Preferences("system","comfortable",true,null);
        var row=rows.get(0);return new Preferences((String)row.get("theme"),(String)row.get("density"),(Boolean)row.get("notification_toasts"),(Integer)row.get("default_warehouse_id"));
    }
    @PutMapping("/preferences") @Transactional
    public Preferences save(@RequestBody Preferences body,HttpServletRequest request) {
        if(body.theme()==null||!Set.of("light","dark","system").contains(body.theme())||body.density()==null||!Set.of("comfortable","compact").contains(body.density())||body.notificationToasts()==null)
            throw new IllegalArgumentException("Lựa chọn giao diện không hợp lệ");
        if(body.defaultWarehouse()!=null&&jdbc.queryForObject("SELECT count(*) FROM warehouses WHERE id=?",Integer.class,body.defaultWarehouse())==0) throw new IllegalArgumentException("Không tìm thấy kho mặc định");
        jdbc.update("INSERT INTO dispatcher_preferences(user_id,theme,density,notification_toasts,default_warehouse_id) VALUES(?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET theme=excluded.theme,density=excluded.density,notification_toasts=excluded.notification_toasts,default_warehouse_id=excluded.default_warehouse_id,updated_at=now()",user(request),body.theme(),body.density(),body.notificationToasts(),body.defaultWarehouse());
        return preferences(request);
    }
    @GetMapping("/notifications") @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> notifications(@RequestParam(defaultValue="false") boolean unreadOnly,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="20") int limit,HttpServletRequest request) {
        long uid=user(request);String filter=unreadOnly?" WHERE r.notification_id IS NULL":"";
        var rows=jdbc.queryForList("SELECT n.*,r.read_at FROM dispatcher_notifications n LEFT JOIN dispatcher_notification_reads r ON r.notification_id=n.id AND r.user_id=?"+filter+" ORDER BY n.created_at DESC,n.id DESC LIMIT ? OFFSET ?",uid,Math.min(100,Math.max(1,limit)),Math.max(0,offset));
        long unread=jdbc.queryForObject("SELECT count(*) FROM dispatcher_notifications n WHERE NOT EXISTS(SELECT 1 FROM dispatcher_notification_reads r WHERE r.notification_id=n.id AND r.user_id=?)",Long.class,uid);
        long total=unreadOnly?unread:jdbc.queryForObject("SELECT count(*) FROM dispatcher_notifications",Long.class);
        long latestId=jdbc.queryForObject("SELECT coalesce(max(id),0) FROM dispatcher_notifications",Long.class);
        return Map.of("items",rows,"unread",unread,"total",total,"latestId",latestId);
    }
    public record ReadRequest(Long id,Long throughId) {}
    @PostMapping("/notifications/read") @Transactional
    public ResponseEntity<Void> read(@RequestBody(required=false) ReadRequest body,HttpServletRequest request) {
        Long id=body==null?null:body.id(),through=body==null?null:body.throughId();
        if(id!=null&&jdbc.queryForObject("SELECT count(*) FROM dispatcher_notifications WHERE id=?",Integer.class,id)==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy thông báo");
        var args=new ArrayList<Object>();args.add(user(request));String filter="";
        if(id!=null) { filter=" WHERE id=?";args.add(id); } else if(through!=null) { filter=" WHERE id<=?";args.add(through); }
        jdbc.update("INSERT INTO dispatcher_notification_reads(user_id,notification_id) SELECT ?,id FROM dispatcher_notifications"+filter+" ON CONFLICT DO NOTHING",args.toArray());
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/notifications/{id}/unread")
    public ResponseEntity<Void> unread(@PathVariable long id,HttpServletRequest request) {
        jdbc.update("DELETE FROM dispatcher_notification_reads WHERE user_id=? AND notification_id=?",user(request),id);return ResponseEntity.noContent().build();
    }
}
