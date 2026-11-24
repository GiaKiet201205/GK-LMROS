package vn.gk.logistics.drivers;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.*;

@RestController
@RequestMapping("/api/driver/notifications")
public class DriverNotificationsController {
 private final JdbcTemplate jdbc;
 public DriverNotificationsController(JdbcTemplate jdbc) { this.jdbc=jdbc; }
 private int driver(HttpServletRequest request) { var user=(Map<?,?>)request.getAttribute("user"); if(!"driver".equals(user.get("role"))) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"Dùng tài khoản tài xế"); return ((Number)user.get("driver_id")).intValue(); }
 @GetMapping @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public Map<String,Object> list(HttpServletRequest request) {
  int id=driver(request);
  return Map.of("items",jdbc.queryForList("SELECT id,order_id,type,title,message,created_at,read_at FROM driver_notifications WHERE driver_id=? ORDER BY id DESC LIMIT 100",id),
   "unread",jdbc.queryForObject("SELECT count(*) FROM driver_notifications WHERE driver_id=? AND read_at IS NULL",Long.class,id),
   "latestId",jdbc.queryForObject("SELECT coalesce(max(id),0) FROM driver_notifications WHERE driver_id=?",Long.class,id));
 }
 public record Read(Long id,Long throughId) {}
 @PostMapping("/read")
 public ResponseEntity<Void> read(@RequestBody Read body,HttpServletRequest request) {
  if(body.id()!=null) jdbc.update("UPDATE driver_notifications SET read_at=coalesce(read_at,now()) WHERE driver_id=? AND id=?",driver(request),body.id());
  else if(body.throughId()!=null) jdbc.update("UPDATE driver_notifications SET read_at=coalesce(read_at,now()) WHERE driver_id=? AND id<=?",driver(request),body.throughId());
  else throw new IllegalArgumentException("Thiếu mốc thông báo cần đánh dấu đã đọc");
  return ResponseEntity.noContent().build();
 }
}
