package vn.gk.logistics.dispatch;

import org.springframework.stereotype.Component;
import java.time.*;
import java.util.Map;

/** A single Vietnam-clock policy for booking dates, planning windows and departure. */
@Component
public class WorkDayPolicy {
 public static final ZoneId ZONE=ZoneId.of("Asia/Ho_Chi_Minh");
 public static final LocalTime START=LocalTime.of(8,0), END=LocalTime.of(20,0);
 private final Clock clock;
 public WorkDayPolicy(Clock clock) { this.clock=clock; }
 public ZonedDateTime now() { return ZonedDateTime.now(clock).withZoneSameInstant(ZONE); }
 public LocalDate today() { return now().toLocalDate(); }
 public LocalDate dispatchDate() { var now=now();return now.toLocalTime().isBefore(END)?now.toLocalDate():now.toLocalDate().plusDays(1); }
 public boolean inShift() { var time=now().toLocalTime();return !time.isBefore(START)&&time.isBefore(END); }
 public Instant start(LocalDate date) { return date.atTime(START).atZone(ZONE).toInstant(); }
 public Instant end(LocalDate date) { return date.atTime(END).atZone(ZONE).toInstant(); }
 public String startBlock(LocalDate date,LocalDateTime plannedStart,LocalDateTime plannedEnd) {
  if(!date.equals(today())) return date.isAfter(today())?"Chuyến ngày "+date+" chỉ được bắt đầu từ 08:00 đúng ngày đã phân.":"Chuyến đã quá ngày phân công. Nhờ điều phối thu hồi và tính lại.";
  if(!inShift()) return "Chỉ được bắt đầu chuyến trong ca 08:00–20:00.";
  if(plannedStart.toLocalTime().isBefore(START)||!plannedStart.toLocalTime().isBefore(END)||plannedEnd==null||!plannedEnd.toLocalDate().equals(date)||plannedEnd.toLocalTime().isAfter(END)) return "Chuyến nằm ngoài ca 08:00–20:00. Nhờ điều phối tính lại phương án.";
  if(!now().toLocalTime().isBefore(plannedEnd.toLocalTime())) return "Giờ kết thúc dự kiến đã qua. Nhờ điều phối tính lại tuyến trước khi xuất phát.";
  if(now().toLocalDateTime().isBefore(plannedStart)) return "Chưa đến giờ xuất phát dự kiến: "+plannedStart.toLocalTime()+".";
  if(now().toInstant().plus(Duration.between(plannedStart,plannedEnd)).isAfter(end(date))) return "Không đủ thời gian hoàn thành chuyến trước 20:00. Nhờ điều phối tính lại phương án.";
  return null;
 }
 public void validateDate(LocalDate date) {
  if(date==null||date.isBefore(dispatchDate())||date.isAfter(today().plusDays(90))) throw new IllegalArgumentException("Chọn ngày từ "+dispatchDate()+" đến "+today().plusDays(90)+". Sau 20:00 xét lịch ngày mai.");
 }
 public Map<String,Object> context() { return Map.of("date",dispatchDate(),"today",today(),"shiftStart","08:00","shiftEnd","20:00","inShift",inShift(),"planningTomorrow",dispatchDate().isAfter(today()),"serverTime",now().toOffsetDateTime()); }
}
