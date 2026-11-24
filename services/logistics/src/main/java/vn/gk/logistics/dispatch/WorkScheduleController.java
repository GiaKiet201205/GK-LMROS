package vn.gk.logistics.dispatch;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.util.*;

@RestController
public class WorkScheduleController {
    private final JdbcTemplate jdbc;
    private final RoutingService routing;
    private final WorkDayPolicy policy;
    public WorkScheduleController(JdbcTemplate jdbc,RoutingService routing,WorkDayPolicy policy) { this.jdbc=jdbc;this.routing=routing;this.policy=policy; }
    @GetMapping("/api/workday") public Map<String,Object> workday() { return policy.context(); }
    static void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT,message); }
    private int driver(HttpServletRequest request) {
        var user=(Map<?,?>)request.getAttribute("user");
        if(!"driver".equals(user.get("role"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Đăng ký lịch bằng tài khoản tài xế");
        return ((Number)user.get("driver_id")).intValue();
    }
    private List<Map<String,Object>> days(int id,LocalDate week) {
        if(week.getDayOfWeek()!=DayOfWeek.MONDAY) throw new IllegalArgumentException("Tuần bắt đầu vào thứ Hai");
        return jdbc.queryForList("SELECT work_date,working,to_char(shift_start,'HH24:MI') AS shift_start,to_char(shift_end,'HH24:MI') AS shift_end FROM driver_work_days WHERE driver_id=? AND work_date BETWEEN ? AND ? ORDER BY work_date",id,week,week.plusDays(6));
    }
    @GetMapping("/api/driver/schedule")
    public List<Map<String,Object>> own(@RequestParam LocalDate week,HttpServletRequest request) { return days(driver(request),week); }
    public record Day(LocalDate date,Boolean working,String start,String end) {}
    public record Week(LocalDate week,List<Day> days) {}
    @PostMapping("/api/driver/schedule") @Transactional
    public Map<String,Object> save(@RequestBody Week body,HttpServletRequest request) {
        if(body.week()==null||body.week().getDayOfWeek()!=DayOfWeek.MONDAY||body.days()==null||body.days().size()!=7) throw new IllegalArgumentException("Đăng ký đủ 7 ngày, tuần bắt đầu vào thứ Hai");
        LocalDate monday=policy.today().with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        if(body.week().isBefore(monday)||body.week().isAfter(monday.plusWeeks(12))) throw new IllegalArgumentException("Chỉ đăng ký tuần hiện tại hoặc 12 tuần tiếp theo");
        if(body.days().stream().anyMatch(d->d==null||d.working()==null))throw new IllegalArgumentException("Chọn làm/nghỉ cho đủ 7 ngày.");
        if(body.days().stream().filter(Day::working).count()<6) throw new IllegalArgumentException("Mỗi tuần cần đăng ký làm ít nhất 6 ngày");
        int id=driver(request);routing.changed();
        Set<LocalDate> dates=new HashSet<>();
        for(var day:body.days()) {
            if(day.date()==null||day.date().isBefore(body.week())||day.date().isAfter(body.week().plusDays(6))||!dates.add(day.date())) throw new IllegalArgumentException("Ngày đăng ký không hợp lệ hoặc bị trùng");
            if(day.start()==null||day.end()==null) throw new IllegalArgumentException("Điền đủ giờ bắt đầu và kết thúc");
            LocalTime start=LocalTime.parse(day.start()),end=LocalTime.parse(day.end());
            if(!start.equals(WorkDayPolicy.START)||!end.equals(WorkDayPolicy.END)) throw new IllegalArgumentException("Ca đăng ký cố định từ 08:00 đến 20:00.");
            if(jdbc.queryForObject("SELECT count(*) FROM trips WHERE driver_id=? AND status<>'completed' AND planned_start::date=? AND (NOT ? OR planned_start::time<? OR coalesce(planned_end,planned_start)::time>?)",Integer.class,id,day.date(),day.working(),start,end)>0)
                conflict("Ngày "+day.date()+" đã có chuyến nằm ngoài ca mới. Giữ lịch đã phân công hoặc xử lý chuyến trước.");
            jdbc.update("INSERT INTO driver_work_days(driver_id,work_date,working,shift_start,shift_end) VALUES(?,?,?,?,?) ON CONFLICT(driver_id,work_date) DO UPDATE SET working=excluded.working,shift_start=excluded.shift_start,shift_end=excluded.shift_end,updated_at=now()",id,day.date(),day.working(),start,end);
        }
        return Map.of("days",days(id,body.week()),"message","Đã đăng ký lịch tuần");
    }
    @GetMapping("/api/work-schedules")
    public List<Map<String,Object>> all(@RequestParam LocalDate date) {
        return jdbc.queryForList("SELECT d.id,d.code,d.name,d.warehouse_id,w.name AS warehouse,gk_driver_state(d.id,'"+date+"'::date) AS dispatch_status,s.working,to_char(s.shift_start,'HH24:MI') AS shift_start,to_char(s.shift_end,'HH24:MI') AS shift_end,EXISTS(SELECT 1 FROM trips t WHERE t.driver_id=d.id AND t.status<>'completed' AND (t.planned_start::date<=? OR t.status='in_progress')) AS busy FROM drivers d JOIN warehouses w ON w.id=d.warehouse_id LEFT JOIN driver_work_days s ON s.driver_id=d.id AND s.work_date=? WHERE d.archived_at IS NULL ORDER BY d.code",date,date);
    }
    public record Availability(LocalDate date,Boolean available,String note) {}
    @PostMapping("/api/vehicles/{id}/availability") @Transactional
    public Map<String,Object> available(@PathVariable int id,@RequestBody Availability body) {
        if(body.date()==null||body.available()==null) throw new IllegalArgumentException("Chọn ngày sử dụng xe");policy.validateDate(body.date());routing.changed();
        if(jdbc.queryForObject("SELECT count(*) FROM vehicles WHERE id=? AND archived_at IS NULL",Integer.class,id)==0) throw new IllegalArgumentException("Không tìm thấy xe");
        if(!body.available()&&jdbc.queryForObject("SELECT count(*) FROM trips WHERE vehicle_id=? AND status<>'completed' AND (planned_start::date=? OR (status='in_progress' AND ?=CURRENT_DATE))",Integer.class,id,body.date(),body.date())>0) conflict("Xe đã có chuyến; không thể đánh dấu bận cho ngày này");
        String note=Objects.toString(body.note(),"").trim();if(note.length()>300) throw new IllegalArgumentException("Ghi chú tối đa 300 ký tự");
        jdbc.update("INSERT INTO vehicle_work_days(vehicle_id,work_date,available,note) VALUES(?,?,?,?) ON CONFLICT(vehicle_id,work_date) DO UPDATE SET available=excluded.available,note=excluded.note",id,body.date(),body.available(),note);
        return Map.of("saved",true);
    }
}
