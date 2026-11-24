package vn.gk.logistics.dispatch;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import vn.gk.logistics.api.ApiController;
import vn.gk.logistics.drivers.DriverController;
import vn.gk.logistics.drivers.InternalDriverController;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in only: all test business rows are cleared in a dedicated local *_test database. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"ROUTING_ENABLED=false","RESERVATION_RECONCILE_ENABLED=false","FINANCE_ACCRUAL_ENABLED=false","app.internal-token=workday-test-token","debug=false","logging.level.root=WARN"})
@EnabledIfEnvironmentVariable(named="GK_WORKDAY_TEST_DB",matches="jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/[a-z_]+_test")
class WorkdayIntegrationTest {
 @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
  var url=System.getenv("GK_WORKDAY_TEST_DB");
  if(url==null||!url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/[a-z_]+_test")) throw new IllegalStateException("Requires a dedicated local *_test database");
  registry.add("spring.datasource.url",()->url);registry.add("spring.datasource.username",()->System.getenv().getOrDefault("GK_WORKDAY_TEST_USER","gk"));registry.add("spring.datasource.password",()->System.getenv().getOrDefault("GK_WORKDAY_TEST_PASSWORD","test"));
  registry.add("app.optimizer-url",()->"http://127.0.0.1:18083");
 }
 static class TestClock extends Clock {
  private Instant instant=Instant.parse("2026-11-22T02:00:00Z");
  void at(String time) { instant=OffsetDateTime.parse(time).toInstant(); }
  public ZoneId getZone() { return ZoneOffset.UTC; }
  public Clock withZone(ZoneId zone) { return Clock.fixed(instant,zone); }
  public Instant instant() { return instant; }
 }
 @TestConfiguration static class ClockConfig { @Bean @Primary TestClock controlledClock() { return new TestClock(); } }
 @org.springframework.boot.test.mock.mockito.MockBean vn.gk.logistics.infrastructure.Events outboundEvents;
 @Autowired TestClock clock;
 @Autowired JdbcTemplate jdbc;
 @Autowired DistributionController distribution;
 @Autowired DriverController driver;
 @Autowired InternalDriverController directory;
 @Autowired ApiController api;
 @Autowired WorkScheduleController schedules;
 @Autowired WorkDayPolicy policy;
 @Autowired vn.gk.logistics.pricing.ShippingPricingService pricing;
 @Autowired vn.gk.logistics.maintenance.MaintenanceController maintenance;
 @Autowired org.springframework.transaction.support.TransactionTemplate tx;
 @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
 @Autowired org.springframework.boot.web.client.RestTemplateBuilder rest;
 static final LocalDate DAY=LocalDate.of(2026,11,22);
 @BeforeEach void fixture() {
  clock.at("2026-11-22T09:00:00+07:00");
  jdbc.execute("TRUNCATE order_reschedule_batches,warehouses,vehicles,drivers,orders,trips,trip_stops,driver_work_days,vehicle_work_days,distribution_plans,customer_tracking_events,driver_notifications,customer_event_receipts,dispatcher_notifications,dispatcher_notification_reads,gps_points,routing_events,alerts RESTART IDENTITY CASCADE");
  jdbc.update("UPDATE routing_state SET revision=0,dirty=false,lease_owner=NULL,lease_until=NULL,last_error=NULL,retry_after=NULL WHERE id=1");
  jdbc.update("INSERT INTO warehouses(id,code,name,lat,lng) VALUES(1,'QA','GK QA',10.765,106.603)");
  jdbc.update("INSERT INTO vehicles(id,code,plate,warehouse_id) VALUES(1,'XE-QA-1','QA-001',1),(2,'XE-QA-2','QA-002',1)");
  jdbc.update("INSERT INTO drivers(id,code,name,phone,warehouse_id,vehicle_id,joined_on) VALUES(1,'TX-QA-1','Nguyễn Minh An','0903000001',1,1,'2026-09-01'),(2,'TX-QA-2','Trần Gia Bảo','0903000002',1,NULL,'2026-09-01')");
  for(int id:List.of(1,2))for(var day:List.of(DAY,DAY.plusDays(1)))jdbc.update("INSERT INTO driver_work_days(driver_id,work_date,working,shift_start,shift_end) VALUES(?,?,true,'08:00','20:00')",id,day);
  for(int i=1;i<=3;i++)jdbc.update("INSERT INTO orders(id,code,customer_name,address,lat,lng,weight_kg,volume_m3,tw_start,tw_end,warehouse_id,status,stage,in_warehouse,service_min) VALUES(?,?,'Lê Minh Anh','Địa chỉ QA',?,?,5,.02,?,? ,1,'pending','at_warehouse',true,2)",i,"QA-"+i,10.765+i*.001,106.603+i*.001,DAY.atTime(8,0),DAY.plusDays(1).atTime(20,0));
  jdbc.update("UPDATE orders SET payment_required=false");
  for(String table:List.of("warehouses","vehicles","drivers","orders"))jdbc.execute("SELECT setval(pg_get_serial_sequence('"+table+"','id'),(SELECT max(id) FROM "+table+"))");
 }
 MockHttpServletRequest request(int id) { var r=new MockHttpServletRequest();r.setAttribute("user",Map.of("id",id,"driver_id",id,"role","driver"));return r; }
 MockHttpServletRequest admin() {var r=new MockHttpServletRequest();r.setAttribute("user",Map.of("id",100,"role","dispatcher","display_name","Điều phối GK QA"));return r;}
 long plan(LocalDate date,int vehicle,int person) { return ((Number)distribution.preview(new DistributionController.Request(date,1,2,List.of(new DistributionController.Assignment(vehicle,person)))).get("id")).longValue(); }
 int trip(int person) { return jdbc.queryForObject("SELECT id FROM trips WHERE driver_id=? AND status='planned'",Integer.class,person); }
 String vehicle(int id,LocalDate date) { return jdbc.queryForObject("SELECT gk_vehicle_state(?,?)",String.class,id,date); }
 String person(int id,LocalDate date) { return jdbc.queryForObject("SELECT gk_driver_state(?,?)",String.class,id,date); }
 @Test void approvalReservesResourcesRemovesQueueAndStartActivates() {
  var id=plan(DAY,1,1);var result=distribution.confirm(id);assertEquals(2,result.get("orders"));
  distribution.confirm(id);assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM trips",Integer.class));
  int trip=trip(1);assertEquals("planned",jdbc.queryForObject("SELECT status FROM trips WHERE id=?",String.class,trip));
  assertEquals("reserved",vehicle(1,DAY));assertEquals("reserved",person(1,DAY));
  assertEquals(1,((Number)((Map<?,?>)distribution.resources(DAY,1).get("counts")).get("at_warehouse")).intValue());
  assertEquals("reserved",api.vehicles(null).get(0).get("status"));assertEquals("reserved",api.drivers(null).get(0).get("status"));
  var overview=api.dashboard(null);var operations=(Map<?,?>)overview.get("operations");assertEquals(1,((Number)operations.get("available_vehicles")).intValue());assertEquals(0,((Number)operations.get("active_vehicles")).intValue());assertEquals(1,((Number)((Map<?,?>)overview.get("vehicles")).get("reserved")).intValue());
  assertTrue(jdbc.queryForObject("SELECT count(*) FROM driver_notifications WHERE driver_id=1",Integer.class)>0);
  var current=(Map<?,?>)driver.ownToday(request(1)).getBody().get("currentTrip");assertEquals(true,current.get("can_start"));
  driver.start(trip);driver.start(trip);
  assertEquals("active",vehicle(1,DAY));assertEquals("on_duty",person(1,DAY));
  assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM orders WHERE stage='out_for_delivery' AND NOT in_warehouse",Integer.class));
  for(var stop:jdbc.queryForList("SELECT id FROM trip_stops WHERE trip_id=? ORDER BY seq",Integer.class,trip)) driver.complete(stop);
  assertEquals("available",vehicle(1,DAY));assertEquals("available",person(1,DAY));
  assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM orders WHERE stage='delivered'",Integer.class));
 }
 @Test void releaseRestoresQueueAndAvailability() {
  distribution.confirm(plan(DAY,1,1));int id=trip(1);distribution.release(id);
  assertEquals("available",vehicle(1,DAY));assertEquals("available",person(1,DAY));
  assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM orders WHERE stage='at_warehouse'",Integer.class));
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM driver_notifications WHERE driver_id=1 AND type='trip_released'",Integer.class));
 }
 @Test void lateBookingsUseTomorrowAndCannotDepartUntilEight() {
  clock.at("2026-11-22T20:00:00+07:00");
  jdbc.update("UPDATE driver_work_days SET working=false WHERE driver_id=1 AND work_date=?",DAY.plusDays(1));
  jdbc.update("UPDATE driver_work_days SET working=false WHERE driver_id=2 AND work_date=?",DAY);
  assertThrows(IllegalArgumentException.class,()->plan(DAY,1,1));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->plan(DAY.plusDays(1),1,1));
  distribution.confirm(plan(DAY.plusDays(1),2,2));int id=trip(2);
  assertEquals("reserved",api.vehicles(null).get(1).get("status"));
  assertEquals("XE-QA-2",directory.driver(2).get("vehicle_code"));
  assertEquals(false,((Map<?,?>)driver.ownToday(request(2)).getBody().get("currentTrip")).get("can_start"));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->driver.start(id));
  clock.at("2026-11-23T00:00:00+07:00");assertEquals(DAY.plusDays(1),policy.dispatchDate());
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->driver.start(id));
  clock.at("2026-11-23T08:00:00+07:00");driver.start(id);assertEquals("active",vehicle(2,DAY.plusDays(1)));
 }
 @Test void staleApprovalAcrossCutoffIsRejectedAtomically() {
  clock.at("2026-11-22T19:00:00+07:00");long id=plan(DAY,1,1);
  clock.at("2026-11-22T20:00:00+07:00");
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->distribution.confirm(id));
  assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM trips",Integer.class));
  assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM orders WHERE stage='at_warehouse'",Integer.class));
 }
 @Test void pickupsRequireReviewAndUseTomorrowsRegisteredDriverAfterTwenty() {
  clock.at("2026-11-22T20:00:00+07:00");
  jdbc.update("UPDATE driver_work_days SET working=false WHERE driver_id=1 AND work_date=?",DAY.plusDays(1));
  jdbc.update("UPDATE driver_work_days SET working=false WHERE driver_id=2 AND work_date=?",DAY);
  jdbc.update("UPDATE drivers SET vehicle_id=2 WHERE id=2");
  jdbc.update("UPDATE orders SET stage='awaiting_pickup',status='new',in_warehouse=false,pickup_lat=lat,pickup_lng=lng,pickup_from=?,pickup_to=? WHERE id=1",DAY.plusDays(1).atTime(8,0),DAY.plusDays(1).atTime(18,0));
  var worker=new RoutingService(jdbc,tx,json,rest,"http://127.0.0.1:18083",true,policy);
  jdbc.update("UPDATE routing_state SET dirty=true,retry_after=NULL WHERE id=1");worker.tick();
  assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM trips",Integer.class));
  assertEquals("awaiting_pickup",stage(1));
  var proposal=distribution.preview(new DistributionController.Request(DAY.plusDays(1),1,45,List.of(new DistributionController.Assignment(2,2)),"pickup"));
  distribution.confirm(((Number)proposal.get("id")).longValue());
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM trips WHERE driver_id=2 AND planned_start::date=? AND status='planned'",Integer.class,DAY.plusDays(1)));
  clock.at("2026-11-23T00:01:00+07:00");assertThrows(org.springframework.web.server.ResponseStatusException.class,()->driver.start(trip(2)));
  jdbc.update("UPDATE orders SET stage='awaiting_pickup',status='new',in_warehouse=false,pickup_lat=lat,pickup_lng=lng,pickup_from=?,pickup_to=? WHERE id=2",DAY.plusDays(1).atTime(8,0),DAY.plusDays(1).atTime(18,0));
  jdbc.update("UPDATE routing_state SET dirty=true,retry_after=NULL WHERE id=1");worker.tick();
  assertEquals("awaiting_pickup",stage(2));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM trips",Integer.class));

 }
 @Test void weeklyRegistrationRejectsOtherHoursWithoutPartialWrites() {
  var monday=DAY.minusDays(6);var days=new ArrayList<WorkScheduleController.Day>();
  for(int i=0;i<7;i++) days.add(new WorkScheduleController.Day(monday.plusDays(i),i<6,"07:00","20:00"));
  assertThrows(IllegalArgumentException.class,()->schedules.save(new WorkScheduleController.Week(monday,days),request(1)));
  assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM driver_work_days",Integer.class));
  assertEquals(0L,jdbc.queryForObject("SELECT revision FROM routing_state WHERE id=1",Long.class));
 }
 @Test void futureAcceptedTripIsNotShownAsGloballyFree() {
  distribution.confirm(plan(DAY.plusDays(1),2,2));
  assertEquals("reserved",api.drivers(DAY).get(1).get("status"));
  assertEquals("reserved",api.vehicles(DAY).get(1).get("status"));
 }
 @Test void reportingMaintenanceReleasesWaitingOrdersAndCompletionRestoresVehicle() {
  long plan=plan(DAY,1,1);distribution.confirm(plan);
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->maintenance.report(new vn.gk.logistics.maintenance.MaintenanceController.Report(1,"Kiểm tra phanh",null,false),request(1)));
  var job=maintenance.report(new vn.gk.logistics.maintenance.MaintenanceController.Report(1,"Kiểm tra phanh",null,true),request(1));long id=((Number)job.get("id")).longValue();
  assertEquals("maintenance",vehicle(1,DAY));assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM orders WHERE stage='at_warehouse'",Integer.class));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->distribution.confirm(plan));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->distribution.preview(new DistributionController.Request(DAY,1,2,List.of(new DistributionController.Assignment(1,1)))));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->maintenance.complete(id,new vn.gk.logistics.maintenance.MaintenanceController.Completion("Đã sửa"),request(2)));
  maintenance.estimate(id,new vn.gk.logistics.maintenance.MaintenanceController.Estimate("2026-11-22T18:00"),request(1));
  maintenance.complete(id,new vn.gk.logistics.maintenance.MaintenanceController.Completion("Đã thay má phanh và chạy thử","Gara QA",new java.math.BigDecimal("300000"),"HD-QA-TEST"),request(1));
  maintenance.complete(id,new vn.gk.logistics.maintenance.MaintenanceController.Completion("Đã thay má phanh và chạy thử","Gara QA",new java.math.BigDecimal("300000"),"HD-QA-TEST"),request(1));
  assertEquals("maintenance",vehicle(1,DAY));assertEquals("pending_approval",jdbc.queryForObject("SELECT state FROM vehicle_maintenance WHERE id=?",String.class,id));
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dispatcher_notifications WHERE type='maintenance_submitted'",Integer.class));
  maintenance.approve(id,new vn.gk.logistics.maintenance.MaintenanceController.Review("Đã kiểm tra phiếu"),admin());
  maintenance.approve(id,null,admin());
  assertEquals("available",vehicle(1,DAY));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dispatcher_notifications WHERE type='maintenance_approved'",Integer.class));
 }
 @Test void activeVehicleMaintenanceWaitsForWarehouseAndDoesNotLoseCargo() {
  distribution.confirm(plan(DAY,1,1));int trip=trip(1);driver.start(trip);
  var job=maintenance.report(new vn.gk.logistics.maintenance.MaintenanceController.Report(1,"Xe có tiếng động bất thường",null,true),request(1));long id=((Number)job.get("id")).longValue();
  assertEquals("awaiting_return",job.get("state"));assertEquals("active",vehicle(1,DAY));
  assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM orders WHERE stage='out_for_delivery' AND NOT in_warehouse",Integer.class));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->maintenance.complete(id,new vn.gk.logistics.maintenance.MaintenanceController.Completion("Chưa bàn giao"),request(1)));
  for(var stop:jdbc.queryForList("SELECT id FROM trip_stops WHERE trip_id=? ORDER BY seq",Integer.class,trip)) driver.complete(stop);
  assertEquals("maintenance",vehicle(1,DAY));assertEquals("open",jdbc.queryForObject("SELECT state FROM vehicle_maintenance WHERE id=?",String.class,id));
  maintenance.complete(id,new vn.gk.logistics.maintenance.MaintenanceController.Completion("Đã kiểm tra và sửa xong","Gara QA",new java.math.BigDecimal("300000"),"HD-QA-TEST"),request(1));assertEquals("maintenance",vehicle(1,DAY));maintenance.approve(id,null,admin());assertEquals("available",vehicle(1,DAY));
 }
 @Test void unstartedTripCannotBeFinishedAndUnassignedDriverHasNormalEmptyState() {
  distribution.confirm(plan(DAY,1,1));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->driver.finish(trip(1)));
  assertNull(driver.ownToday(request(2)).getBody().get("vehicle"));assertNull(driver.ownToday(request(2)).getBody().get("currentTrip"));
 }
 @Test void expiredUntouchedTripIsReleasedButActiveTripIsKept() {
  distribution.confirm(plan(DAY,1,1));int id=trip(1);clock.at("2026-11-22T20:01:00+07:00");
  new ReservationReconciler(jdbc,tx,distribution,policy,true).tick();assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM trips",Integer.class));assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM orders WHERE stage='at_warehouse'",Integer.class));
  clock.at("2026-11-23T09:00:00+07:00");distribution.confirm(plan(DAY.plusDays(1),1,1));id=trip(1);driver.start(id);clock.at("2026-11-23T21:00:00+07:00");
  new ReservationReconciler(jdbc,tx,distribution,policy,true).tick();assertEquals("in_progress",jdbc.queryForObject("SELECT status FROM trips WHERE id=?",String.class,id));
 }
 @Test void databaseAndJavaAgreeOnCutoffAndMidnight() {
  for(var time:List.of("2026-11-22T19:59:59+07:00","2026-11-22T20:00:00+07:00","2026-11-22T23:59:59+07:00","2026-11-23T00:00:00+07:00")) {
   clock.at(time);assertEquals(policy.dispatchDate(),jdbc.queryForObject("SELECT gk_dispatch_date(?::timestamptz)",java.sql.Date.class,time).toLocalDate());
  }
 }
 @Test void reschedulingWaitingOrdersPreservesHistoryAndNotifies() {
  var original=jdbc.queryForObject("SELECT pickup_from FROM orders WHERE id=1",java.sql.Timestamp.class);
  var body=new HashMap<String,Object>(Map.of("deliveryFrom","2026-11-23T08:00","deliveryTo","2026-11-23T20:00","reason","Khách hẹn ngày mai"));
  api.reschedule(1,body);
  assertEquals(original,jdbc.queryForObject("SELECT pickup_from FROM orders WHERE id=1",java.sql.Timestamp.class));
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM customer_tracking_events WHERE order_id=1 AND type='schedule_updated'",Integer.class));
  body.put("deliveryTo","2026-11-23T21:00");assertThrows(IllegalArgumentException.class,()->api.reschedule(1,body));
  distribution.confirm(plan(DAY,2,2));
  int assigned=jdbc.queryForObject("SELECT order_id FROM trip_stops WHERE trip_id=? AND kind='delivery' LIMIT 1",Integer.class,trip(2));
  assertThrows(IllegalArgumentException.class,()->api.reschedule(assigned,body));
 }

 @Test void repairReviewRejectAndResubmitKeepsVehicleHeldAndAudit() {
  var job=maintenance.report(new vn.gk.logistics.maintenance.MaintenanceController.Report(1,"Phanh bị mòn",null,false),request(1));long id=((Number)job.get("id")).longValue();
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->maintenance.approve(id,null,admin()));
  var receipt=new vn.gk.logistics.maintenance.MaintenanceController.Completion("Thay má phanh", "Gara Minh An",new java.math.BigDecimal("450000"),"HD-001");
  maintenance.complete(id,receipt,request(1));
  assertEquals("maintenance",vehicle(1,DAY));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->maintenance.approve(id,null,request(1)));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->distribution.preview(new DistributionController.Request(DAY,1,2,List.of(new DistributionController.Assignment(1,1)))));
  assertThrows(IllegalArgumentException.class,()->maintenance.reject(id,new vn.gk.logistics.maintenance.MaintenanceController.Review(" "),admin()));
  maintenance.reject(id,new vn.gk.logistics.maintenance.MaintenanceController.Review("Bổ sung kết quả kiểm tra phanh"),admin());
  assertEquals("open",jdbc.queryForObject("SELECT state FROM vehicle_maintenance WHERE id=?",String.class,id));assertEquals("maintenance",vehicle(1,DAY));
  maintenance.complete(id,new vn.gk.logistics.maintenance.MaintenanceController.Completion("Thay má phanh, chạy thử đạt",receipt.repairShop(),receipt.repairCost(),receipt.receiptReference()),request(1));
  assertNull(jdbc.queryForObject("SELECT review_note FROM vehicle_maintenance WHERE id=?",String.class,id));
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM vehicle_maintenance_events WHERE maintenance_id=? AND action='maintenance_rejected' AND details->>'review_note'='Bổ sung kết quả kiểm tra phanh'",Integer.class,id));
  maintenance.approve(id,new vn.gk.logistics.maintenance.MaintenanceController.Review("Hợp lệ"),admin());
  assertEquals("available",vehicle(1,DAY));assertEquals("HD-001",jdbc.queryForObject("SELECT receipt_reference FROM vehicle_maintenance WHERE id=?",String.class,id));
  assertEquals(100L,jdbc.queryForObject("SELECT reviewed_by_user_id FROM vehicle_maintenance WHERE id=?",Long.class,id));
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM driver_notifications WHERE driver_id=1 AND type='maintenance_approved'",Integer.class));
 }
 @Test void repairRequiresInvoiceAndWholeCostAndDatabaseEnforcesTheSameRule() {
  long id=((Number)maintenance.report(new vn.gk.logistics.maintenance.MaintenanceController.Report(1,"Kiểm tra hệ thống phanh",null,false),request(1)).get("id")).longValue();
  for(var invalid:List.of(new vn.gk.logistics.maintenance.MaintenanceController.Completion("Đã sửa","Gara QA",null,"HD-TEST"),new vn.gk.logistics.maintenance.MaintenanceController.Completion("Đã sửa","Gara QA",new java.math.BigDecimal("300000")," "),new vn.gk.logistics.maintenance.MaintenanceController.Completion("Đã sửa","Gara QA",new java.math.BigDecimal("1.5"),"HD-TEST"),new vn.gk.logistics.maintenance.MaintenanceController.Completion("Đã sửa","Gara QA",new java.math.BigDecimal("1000000001"),"HD-TEST")))
   assertThrows(IllegalArgumentException.class,()->maintenance.complete(id,invalid,request(1)));
  assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE vehicle_maintenance SET state='pending_approval',repair_note='Đã sửa',submitted_at=now(),submitted_by_driver_id=1 WHERE id=?",id));
  assertEquals("open",jdbc.queryForObject("SELECT state FROM vehicle_maintenance WHERE id=?",String.class,id));
  assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM finance_entries WHERE maintenance_id=?",Integer.class,id));
  maintenance.complete(id,new vn.gk.logistics.maintenance.MaintenanceController.Completion("Thay má phanh","Gara QA",new java.math.BigDecimal("300000")," HD-TEST "),request(1));
  assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM finance_entries WHERE maintenance_id=?",Integer.class,id));
  maintenance.approve(id,null,admin());maintenance.approve(id,null,admin());
  var entry=jdbc.queryForMap("SELECT id,amount,reference FROM finance_entries WHERE maintenance_id=?",id);
  assertEquals(300000,((Number)entry.get("amount")).longValue());assertEquals("HD-TEST",entry.get("reference"));
  var jobs=(List<Map<String,Object>>)maintenance.list(admin()).get("jobs");assertEquals(entry.get("id"),jobs.get(0).get("finance_entry_id"));
  assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE vehicle_maintenance SET repair_cost=1 WHERE id=?",id));
 }
 @Test void warrantyRepairWithZeroCostDoesNotInventAnExpense() {
  long id=((Number)maintenance.report(new vn.gk.logistics.maintenance.MaintenanceController.Report(1,"Bảo hành phanh",null,false),request(1)).get("id")).longValue();
  maintenance.complete(id,new vn.gk.logistics.maintenance.MaintenanceController.Completion("Sửa bảo hành miễn phí","Gara QA",java.math.BigDecimal.ZERO,"BH-TEST"),request(1));maintenance.approve(id,null,admin());
  assertEquals("available",vehicle(1,DAY));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM finance_entries WHERE maintenance_id=?",Integer.class,id));
 }
 @Test void onlyDriverCanWriteRepairTicketAndInvalidCostIsRejected() {
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->maintenance.report(new vn.gk.logistics.maintenance.MaintenanceController.Report(1,"Bảo dưỡng",null,false),admin()));
  var job=maintenance.report(new vn.gk.logistics.maintenance.MaintenanceController.Report(1,"Bảo dưỡng",null,false),request(1));long id=((Number)job.get("id")).longValue();
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->maintenance.complete(id,new vn.gk.logistics.maintenance.MaintenanceController.Completion("Admin viết thay"),admin()));
  assertThrows(IllegalArgumentException.class,()->maintenance.complete(id,new vn.gk.logistics.maintenance.MaintenanceController.Completion("Đã sửa",null,new java.math.BigDecimal("-1"),null),request(1)));
  assertEquals("open",jdbc.queryForObject("SELECT state FROM vehicle_maintenance WHERE id=?",String.class,id));
  assertEquals(1,((java.util.List<?>)maintenance.list(request(1)).get("jobs")).size());
 }

 @Test void shippingQuotesUseAcceptedTiersBindWeightAndCannotTrustClientFee() {
  var body=new HashMap<String,Object>(Map.of("pickupLat",10.765,"pickupLng",106.603,"lat",10.8,"lng",106.7,"weightKg",3));
  var quote=pricing.quote(body);assertEquals(5000L,((Number)quote.get("rate_vnd")).longValue());assertEquals("estimated",quote.get("price_status"));
  assertEquals(vn.gk.logistics.pricing.ShippingPricingService.billableKm(((Number)quote.get("distance_m")).longValue())*5000,((Number)quote.get("amount_vnd")).longValue());
  body.put("shippingQuoteId",quote.get("id").toString());body.put("amountVnd",1);assertEquals(quote.get("amount_vnd"),pricing.forOrder(body).get("amount_vnd"));
  body.put("weightKg",6);assertThrows(IllegalArgumentException.class,()->pricing.forOrder(body));body.remove("shippingQuoteId");assertEquals(7000L,((Number)pricing.quote(body).get("rate_vnd")).longValue());
  for(var entry:Map.of(20,7000,21,10000,50,10000,51,12000,100,12000).entrySet()){body.put("weightKg",entry.getKey());assertEquals(entry.getValue().longValue(),((Number)pricing.quote(body).get("rate_vnd")).longValue());}
  body.put("weightKg",101);assertEquals("manual_quote",pricing.quote(body).get("price_status"));assertNull(pricing.quote(body).get("amount_vnd"));
 }
 @Test void confirmedDistributionIndependentlyRejectsOverweightOrdersAndLimitAbove45() {
  assertThrows(IllegalArgumentException.class,()->distribution.preview(new DistributionController.Request(DAY,1,46,List.of(new DistributionController.Assignment(1,1)))));
  var plan=plan(DAY,1,1);jdbc.update("UPDATE orders SET weight_kg=1001 WHERE id IN (1,2,3)");
  assertThrows(IllegalArgumentException.class,()->distribution.confirm(plan));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM trips",Integer.class));
 }


 @Autowired vn.gk.logistics.customers.CustomerInternalController customers;
 long phasePlan(String phase) {return ((Number)distribution.preview(new DistributionController.Request(DAY,1,45,List.of(new DistributionController.Assignment(1,1)),phase)).get("id")).longValue();}
 String stage(int id) {return jdbc.queryForObject("SELECT stage FROM orders WHERE id=?",String.class,id);}
 boolean stocked(int id) {return jdbc.queryForObject("SELECT in_warehouse FROM orders WHERE id=?",Boolean.class,id);}
 int stop(int trip,String kind) {return jdbc.queryForObject("SELECT id FROM trip_stops WHERE trip_id=? AND kind=? ORDER BY seq LIMIT 1",Integer.class,trip,kind);}
 void sender() {jdbc.update("UPDATE orders SET pickup_name='Nguyễn Thảo Vy',pickup_phone='0903000003',pickup_address='38 An Dương Vương, TP. Hồ Chí Minh',pickup_lat=10.766,pickup_lng=106.604,pickup_from=?,pickup_to=?",DAY.atTime(8,0),DAY.atTime(20,0));}
 @Test void reviewedPickupRequiresDepotHandoffAndPublishesCustomerProgress() {
  sender();jdbc.update("UPDATE orders SET stage='awaiting_pickup',in_warehouse=false");
  UUID booking=UUID.randomUUID();jdbc.update("UPDATE orders SET customer_ref=?,customer_booking_id=? WHERE id=1",UUID.randomUUID(),booking);
  assertThrows(IllegalArgumentException.class,()->phasePlan("delivery"));
  distribution.confirm(phasePlan("pickup"));int t=trip(1);
  assertEquals("reserved",vehicle(1,DAY));assertEquals("reserved",person(1,DAY));
  assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM orders WHERE stage='awaiting_pickup'",Integer.class));
  driver.start(t);assertEquals("picking_up",stage(1));
  var ids=jdbc.queryForList("SELECT id FROM trip_stops WHERE trip_id=? AND kind='pickup' ORDER BY seq",Integer.class,t);
  int last=ids.remove(ids.size()-1);
  for(int id:ids){driver.depart(id);driver.complete(id);}
  int failedOrder=jdbc.queryForObject("SELECT order_id FROM trip_stops WHERE id=?",Integer.class,last);
  driver.fail(last,Map.of("reason","Người gửi hẹn lại"));driver.fail(last,Map.of("reason","Người gửi hẹn lại"));
  assertFalse(stocked(failedOrder));assertEquals("failed",stage(failedOrder));
  assertEquals(0,jdbc.queryForObject("SELECT sum(delivery_failures) FROM orders",Integer.class));
  assertThrows(IllegalArgumentException.class,()->distribution.preview(new DistributionController.Request(DAY,1,45,List.of(new DistributionController.Assignment(2,2)),"delivery")));
  driver.complete(stop(t,"warehouse"));
  assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM orders WHERE stage='at_warehouse' AND in_warehouse",Integer.class));
  assertEquals("available",vehicle(1,DAY));api.requeue(failedOrder);assertEquals("awaiting_pickup",stage(failedOrder));
  assertTrue(jdbc.queryForObject("SELECT count(*) FROM customer_tracking_events WHERE type='picking_up'",Integer.class)>0);
  assertTrue(jdbc.queryForObject("SELECT count(*) FROM dispatcher_notifications WHERE type='pickup_failed'",Integer.class)>0);
  assertTrue(jdbc.queryForObject("SELECT count(*) FROM driver_notifications WHERE type='picked_up'",Integer.class)>0);
  var snapshot=customers.sync(List.of(booking)).get(0);assertNotNull(snapshot.get("pickup_driver"));assertNotNull(snapshot.get("events"));
 }
 @Test void threeRealDeliveryFailuresCancelDeliveryAndReturnToOriginalSender() {
  sender();jdbc.update("DELETE FROM customer_tracking_events WHERE order_id<>1");jdbc.update("DELETE FROM orders WHERE id<>1");UUID booking=UUID.randomUUID();jdbc.update("UPDATE orders SET customer_ref=?,customer_booking_id=? WHERE id=1",UUID.randomUUID(),booking);
  for(int attempt=1;attempt<=3;attempt++) {
   distribution.confirm(phasePlan("delivery"));int t=trip(1);driver.start(t);int s=stop(t,"delivery");
   assertThrows(IllegalArgumentException.class,()->driver.fail(s,Map.of("reason","")));
   driver.fail(s,Map.of("reason","Không liên hệ được người nhận"));driver.fail(s,Map.of("reason","Không liên hệ được người nhận"));
   assertEquals(attempt,jdbc.queryForObject("SELECT delivery_failures FROM orders WHERE id=1",Integer.class));
   assertThrows(IllegalArgumentException.class,()->api.requeue(1));
   if(attempt==3){assertEquals("return_pending",stage(1));assertThrows(IllegalArgumentException.class,()->distribution.preview(new DistributionController.Request(DAY,1,45,List.of(new DistributionController.Assignment(2,2)),"return")));}
   driver.complete(stop(t,"warehouse"));assertTrue(stocked(1));
   if(attempt<3){api.requeue(1);assertEquals("at_warehouse",stage(1));}
  }
  assertThrows(IllegalArgumentException.class,()->api.requeue(1));assertThrows(IllegalArgumentException.class,()->phasePlan("delivery"));
  distribution.confirm(phasePlan("return"));int t=trip(1);assertEquals("return_assigned",stage(1));
  assertEquals("Nguyễn Thảo Vy",routing.stops(t).get(0).get("customer_name"));assertEquals(10.766,((Number)routing.stops(t).get(0).get("lat")).doubleValue());
  distribution.release(t);assertEquals("return_pending",stage(1));assertTrue(stocked(1));
  distribution.confirm(phasePlan("return"));t=trip(1);driver.start(t);assertEquals("returning",stage(1));
  var snapshot=customers.sync(List.of(booking)).get(0);assertEquals("Nguyễn Minh An",snapshot.get("return_driver"));assertEquals("QA-001",snapshot.get("return_plate"));
  driver.depart(stop(t,"return"));driver.complete(stop(t,"return"));driver.complete(stop(t,"return"));assertEquals("returned",stage(1));
  assertEquals("active",vehicle(1,DAY));driver.complete(stop(t,"warehouse"));assertEquals("available",vehicle(1,DAY));
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM customer_tracking_events WHERE type='delivery_cancelled'",Integer.class));
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM customer_tracking_events WHERE type='returned'",Integer.class));
  assertTrue(jdbc.queryForObject("SELECT count(*) FROM driver_notifications WHERE type='return_assigned'",Integer.class)>0);
  assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE orders SET stage='at_warehouse' WHERE id=1"));
 }
 @Autowired RoutingService routing;
 @Test void pickupAndReturnRespectLoadWorkScheduleAndInvalidPhase() {
  sender();jdbc.update("UPDATE orders SET stage='awaiting_pickup',in_warehouse=false,weight_kg=600");
  long p=phasePlan("pickup");distribution.confirm(p);int t=trip(1);
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM trip_stops WHERE trip_id=? AND kind='pickup'",Integer.class,t));
  distribution.release(t);jdbc.update("UPDATE driver_work_days SET working=false WHERE driver_id=1 AND work_date=?",DAY);
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->phasePlan("pickup"));
  assertThrows(IllegalArgumentException.class,()->phasePlan("invalid"));
 }
 @Test void failedReturnStaysOnVehicleUntilDepotThenRequiresNewReview() {
  sender();jdbc.update("DELETE FROM customer_tracking_events WHERE order_id<>1");jdbc.update("DELETE FROM orders WHERE id<>1");jdbc.update("UPDATE orders SET stage='return_pending',delivery_cancelled_at=now(),delivery_failures=3");
  distribution.confirm(phasePlan("return"));int t=trip(1);driver.start(t);driver.fail(stop(t,"return"),Map.of("reason","Người gửi chưa thể nhận lại"));
  assertEquals("return_failed",stage(1));assertFalse(stocked(1));assertThrows(IllegalArgumentException.class,()->distribution.preview(new DistributionController.Request(DAY,1,45,List.of(new DistributionController.Assignment(2,2)),"return")));
  driver.complete(stop(t,"warehouse"));assertEquals("return_pending",stage(1));assertTrue(stocked(1));
  distribution.confirm(phasePlan("return"));assertEquals("return_assigned",stage(1));assertEquals(3,jdbc.queryForObject("SELECT delivery_failures FROM orders WHERE id=1",Integer.class));
 }

 @Test void newPickupIsInsertedOnlyIntoAnApprovedActiveRouteAndPreservesCurrentStop() {
  clock.at("2026-11-23T14:00:00+07:00");sender();
  jdbc.update("UPDATE orders SET pickup_from=?,pickup_to=?",DAY.plusDays(1).atTime(14,0),DAY.plusDays(1).atTime(20,0));
  jdbc.update("UPDATE orders SET stage='awaiting_pickup',in_warehouse=false WHERE id=1");
  var proposal=distribution.preview(new DistributionController.Request(DAY.plusDays(1),1,45,List.of(new DistributionController.Assignment(1,1)),"pickup"));
  distribution.confirm(((Number)proposal.get("id")).longValue());int t=trip(1);driver.start(t);int first=stop(t,"pickup");driver.depart(first);
  jdbc.update("UPDATE orders SET stage='awaiting_pickup',in_warehouse=false WHERE id=2");
  var worker=new RoutingService(jdbc,tx,json,rest,"http://127.0.0.1:18083",true,policy);jdbc.update("UPDATE routing_state SET dirty=true,retry_after=NULL WHERE id=1");worker.tick();
  assertNull(jdbc.queryForObject("SELECT last_error FROM routing_state WHERE id=1",String.class));
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM trips",Integer.class));
  assertEquals(first,routing.stops(t).get(0).get("stop_id"));assertEquals("en_route",routing.stops(t).get(0).get("state"));
  assertEquals("picking_up",stage(2));assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM trip_stops WHERE trip_id=? AND kind='pickup'",Integer.class,t));
 }

 @Autowired vn.gk.logistics.finance.FinanceController finance;
 Map<String,Object> cost(String type){return new HashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"category",type,"date",DAY.toString(),"warehouse",1,"description","Chứng từ chi phí kiểm thử","reference","QA-001","amount",100000));}
 long money(String column){return jdbc.queryForObject("SELECT coalesce(sum("+column+"),0) FROM finance_ledger",Long.class);}
 void oneDelivery(){jdbc.update("UPDATE orders SET stage='awaiting_pickup',in_warehouse=false WHERE id<>1");distribution.confirm(plan(DAY,1,1));driver.start(trip(1));}
 @Test void financeBooksSuccessfulDeliveryOnceWithoutPickupReturnRevenue() {
  jdbc.update("UPDATE orders SET shipping_fee_vnd=40000,shipping_price_status='confirmed' WHERE id=1");oneDelivery();assertEquals(0,money("revenue"));
  int t=jdbc.queryForObject("SELECT id FROM trips WHERE status='in_progress'",Integer.class);int sid=stop(t,"delivery");driver.complete(sid);driver.complete(sid);
  assertEquals(40000,money("revenue"));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM finance_entries",Integer.class));
  var correction=new HashMap<String,Object>(Map.of("requestId",UUID.randomUUID().toString(),"amount",45000,"reason","Đối chiếu phiếu cước thực tế"));finance.revenue(1,correction,admin());finance.revenue(1,correction,admin());assertEquals(45000,money("revenue"));
  correction.put("requestId",UUID.randomUUID().toString());correction.put("amount",35000);finance.revenue(1,correction,admin());assertEquals(35000,money("revenue"));
  assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM finance_entries"));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->finance.revenue(1,correction,request(1)));
 }
 @Test void financeKnownFareIsAutomaticallyRecognizedAtRealDeliveryCompletion() {
  jdbc.update("UPDATE orders SET shipping_fee_vnd=40000,shipping_price_status='estimated' WHERE id=1");oneDelivery();int t=jdbc.queryForObject("SELECT id FROM trips WHERE status='in_progress'",Integer.class);driver.complete(stop(t,"delivery"));assertEquals(40000,money("revenue"));
  LocalDate completed=jdbc.queryForObject("SELECT completed_at::date FROM trip_stops WHERE id=?",java.sql.Date.class,stop(t,"delivery")).toLocalDate();assertEquals(0,finance.unrecognized(completed,completed,1).size());finance.revenue(1,new HashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"amount",40000,"reason","Đã đối chiếu cước")),admin());assertEquals(40000,money("revenue"));assertTrue(finance.unrecognized(completed,completed,1).isEmpty());
  jdbc.update("UPDATE orders SET stage='delivered',shipping_price_status='confirmed',shipping_fee_vnd=12345 WHERE id=2");assertEquals(40000,money("revenue"));
 }
 @Test void financeFuelUsesLitresTimesPriceAndIdempotentReversalPreservesAudit() {
  var body=cost("fuel");body.put("vehicleId",1);body.put("litres","12.345");body.put("unitPrice",20000);body.put("amount",1);
  var result=finance.expense(body,admin());assertEquals(246900,((Number)result.get("amount")).longValue());finance.expense(body,admin());assertEquals(246900,money("expense"));
  body.put("litres","12.346");assertThrows(org.springframework.web.server.ResponseStatusException.class,()->finance.expense(body,admin()));
  long id=((Number)result.get("id")).longValue();var reverse=new HashMap<String,Object>(Map.of("requestId",UUID.randomUUID().toString(),"reason","Hóa đơn ghi nhầm xe"));finance.reverse(id,reverse,admin());finance.reverse(id,reverse,admin());assertEquals(0,money("expense"));assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM finance_entries",Integer.class));
 }
 @Test void financeExpenseValidationRejectsCrossWarehouseNegativeFractionFutureAndProtectedCategories() {
  var body=cost("fuel");body.put("litres",10);body.put("unitPrice",20000);assertThrows(IllegalArgumentException.class,()->finance.expense(body,admin()));
  body.put("vehicleId",1);body.put("litres","1.2345");assertThrows(IllegalArgumentException.class,()->finance.expense(body,admin()));
  for(String kind:List.of("salary","maintenance","delivery","invalid")){var b=cost(kind);assertThrows(IllegalArgumentException.class,()->finance.expense(b,admin()));}
  var b=cost("other");b.put("date",DAY.plusDays(1).toString());assertThrows(IllegalArgumentException.class,()->finance.expense(b,admin()));b.put("date",DAY.toString());b.put("amount",1.5);assertThrows(IllegalArgumentException.class,()->finance.expense(b,admin()));
  b.put("amount",1000);b.put("warehouse",2);assertThrows(IllegalArgumentException.class,()->finance.expense(b,admin()));jdbc.update("INSERT INTO warehouses(id,code,name,lat,lng) VALUES(2,'QA-2','GK QA 2',10.8,106.7)");b.put("vehicleId",1);assertThrows(IllegalArgumentException.class,()->finance.expense(b,admin()));assertEquals(0,money("expense"));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->finance.expense(cost("other"),request(1)));
 }
 @Test void financeMaintenanceBooksAfterApprovalAndSupportsCorrectionWithoutChangingProcessedReceipt() {
  var job=maintenance.report(new vn.gk.logistics.maintenance.MaintenanceController.Report(1,"Thay lốp xe",null,false),request(1));long id=((Number)job.get("id")).longValue();
  maintenance.complete(id,new vn.gk.logistics.maintenance.MaintenanceController.Completion("Đã thay lốp","Gara QA",new java.math.BigDecimal("450000"),"HD-QA"),request(1));assertEquals(0,money("expense"));maintenance.approve(id,null,admin());maintenance.approve(id,null,admin());assertEquals(450000,money("expense"));
  var adjustment=new HashMap<String,Object>(Map.of("requestId",UUID.randomUUID().toString(),"amount",400000,"reason","Gara giảm giá theo hóa đơn cuối"));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->finance.maintenanceCost(id,adjustment,admin()));assertEquals(450000,money("expense"));
 }
 void octoberWork(){jdbc.update("INSERT INTO trips(code,vehicle_id,driver_id,warehouse_id,status,phase,planned_start,planned_end) VALUES('OCT-1',1,1,1,'completed','pickup','2026-10-02 08:00','2026-10-02 12:00'),('OCT-2',1,1,1,'completed','delivery','2026-10-02 13:00','2026-10-02 17:00'),('OCT-3',1,1,1,'completed','return','2026-10-03 08:00','2026-10-03 12:00')");}
 Map<String,Object> payrollBody(){return new HashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"driverId",1,"month","2026-10","dailyRate",100000,"allowance",20000,"bonus",10000,"deduction",5000,"reason",""));}
 @Test void financeFixedPayrollTracksAttendanceWithoutUsingItAsPayMultiplier() {
  octoberWork();assertEquals(2,finance.attendance(1,"2026-10").get("days"));var b=payrollBody();var p=finance.payroll(b,admin());finance.payroll(b,admin());assertEquals(15025000,((Number)p.get("net_amount")).longValue());assertEquals(0,money("expense"));var duplicate=new HashMap<>(b);duplicate.put("requestId",UUID.randomUUID().toString());assertThrows(org.springframework.web.server.ResponseStatusException.class,()->finance.payroll(duplicate,admin()));long id=((Number)p.get("id")).longValue();finance.approve(id,admin());finance.approve(id,admin());assertEquals(15025000,money("expense"));
  assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE driver_payrolls SET daily_rate=daily_rate+1 WHERE id=?",id));assertEquals(1,finance.mine(request(1)).size());assertTrue(finance.mine(request(2)).isEmpty());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM driver_notifications WHERE type='payroll_approved'",Integer.class));
  var cancel=new HashMap<String,Object>(Map.of("requestId",UUID.randomUUID().toString(),"reason","Điều chỉnh phụ cấp tháng"));finance.cancelPayroll(id,cancel,admin());finance.cancelPayroll(id,cancel,admin());assertEquals(0,money("expense"));assertTrue(finance.mine(request(1)).isEmpty());
  b.put("requestId",UUID.randomUUID().toString());b.put("workedDays",3);assertThrows(IllegalArgumentException.class,()->finance.payroll(b,admin()));b.put("reason","Bổ sung một ngày làm tại kho");finance.payroll(b,admin());
 }
 @Test void financePayrollRejectsEarlyApprovalAndStaleAttendance() {
  var b=payrollBody();b.put("month","2026-11");var p=finance.payroll(b,admin());assertThrows(IllegalArgumentException.class,()->finance.approve(((Number)p.get("id")).longValue(),admin()));
  b=payrollBody();var old=finance.payroll(b,admin());octoberWork();assertThrows(org.springframework.web.server.ResponseStatusException.class,()->finance.approve(((Number)old.get("id")).longValue(),admin()));
 }
 @Test void financeReportsGroupAllPeriodsAndFilterWarehouse() {
  var fuel=cost("fuel");fuel.put("vehicleId",1);fuel.put("litres",10);fuel.put("unitPrice",20000);finance.expense(fuel,admin());finance.expense(cost("toll"),admin());
  for(String group:List.of("day","week","month","quarter","year")) {
   var result=finance.report(LocalDate.of(2026,1,1),LocalDate.of(2026,12,31),group,null);var totals=(Map<?,?>)result.get("totals");assertEquals(300000,((Number)totals.get("expense")).longValue());assertEquals(-300000,((Number)totals.get("profit")).longValue());assertNotNull(result.get("quality"));
  }
  var totals=(Map<?,?>)finance.report(DAY,DAY,"day",2).get("totals");assertEquals(0,((Number)totals.get("expense")).longValue());assertThrows(IllegalArgumentException.class,()->finance.report(DAY,DAY,"month');DROP TABLE orders;--",null));
 }
 @Test void journeyPhaseFilterReturnsOnlyRequestedTripsAndRejectsInvalidPhase() {
  sender();jdbc.update("UPDATE orders SET stage='awaiting_pickup',in_warehouse=false WHERE id=1");distribution.confirm(phasePlan("pickup"));distribution.confirm(plan(DAY,2,2));
  var pickups=(List<?>)routing.journeys(1,"pickup").get("vehicles");assertEquals(1,pickups.size());var deliveries=(List<?>)routing.journeys(1,"delivery").get("vehicles");assertEquals(1,deliveries.size());assertTrue(((List<?>)routing.journeys(1,"return").get("vehicles")).isEmpty());assertThrows(IllegalArgumentException.class,()->routing.journeys(null,"invalid"));
 }

 @Test void confirmedFeeOnFailedDeliveryAndPickupDoesNotCreateRevenue() {
  jdbc.update("UPDATE orders SET shipping_fee_vnd=40000,shipping_price_status='confirmed'");oneDelivery();int t=jdbc.queryForObject("SELECT id FROM trips WHERE status='in_progress'",Integer.class);driver.fail(stop(t,"delivery"),Map.of("reason","Người nhận hẹn lại"));driver.complete(stop(t,"warehouse"));assertEquals(0,money("revenue"));
  sender();distribution.confirm(phasePlan("pickup"));int pickup=trip(1);driver.start(pickup);driver.complete(stop(pickup,"pickup"));assertEquals(0,money("revenue"));
 }

 @Autowired vn.gk.logistics.payments.PaymentController payments;
 @Autowired vn.gk.logistics.payments.VehicleCostController vehicleCosts;
 Map<String,Object> collectBody(String method,String holder){return new HashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"method",method,"receivedBy",holder,"reference",method.equals("transfer")?"GD-QA-001":""));}
 void priced(){jdbc.update("UPDATE orders SET shipping_fee_vnd=40000,shipping_price_status='estimated',payment_required=true WHERE id=1");}
 @Test void recipientCollectionDeliveryAndHandoverAreSeparateIdempotentOperations() {
  priced();oneDelivery();int t=jdbc.queryForObject("SELECT id FROM trips WHERE status='in_progress'",Integer.class),s=stop(t,"delivery");
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->driver.complete(s));
  var body=collectBody("cash","driver");body.put("amount",1);payments.collect(s,body,request(1));payments.collect(s,body,request(1));
  assertEquals(40000,jdbc.queryForObject("SELECT amount FROM payment_collections WHERE order_id=1",Long.class));assertEquals(0,money("revenue"));
  driver.complete(s);payments.collect(s,body,request(1));assertEquals(40000,money("revenue"));
  long cid=jdbc.queryForObject("SELECT id FROM payment_collections WHERE order_id=1",Long.class);var handover=new HashMap<String,Object>(Map.of("requestId",UUID.randomUUID().toString(),"collectionIds",List.of(cid),"note","Nộp tiền cuối chuyến"));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->payments.handover(handover,request(1)));driver.complete(stop(t,"warehouse"));
  long hid=((Number)payments.handover(handover,request(1)).get("id")).longValue();payments.handover(handover,request(1));assertEquals(40000,((Number)((Map<?,?>)payments.own(request(1)).get("balances")).get("holding")).longValue());
  var review=new HashMap<String,Object>(Map.of("requestId",UUID.randomUUID().toString(),"reason","Đã đếm đủ tiền"));payments.review(hid,"approve",review,admin());payments.review(hid,"approve",review,admin());assertEquals(0,((Number)((Map<?,?>)payments.own(request(1)).get("balances")).get("holding")).longValue());assertEquals(40000,money("revenue"));
 }
 @Test void senderCanPayAtPickupThenRecipientIsNotChargedAgain() {
  sender();priced();jdbc.update("UPDATE orders SET stage='awaiting_pickup',in_warehouse=false,payment_payer='sender' WHERE id=1");distribution.confirm(phasePlan("pickup"));int t=trip(1);driver.start(t);int s=stop(t,"pickup");assertThrows(org.springframework.web.server.ResponseStatusException.class,()->driver.complete(s));
  var bad=collectBody("transfer","company");bad.put("reference","");assertThrows(IllegalArgumentException.class,()->payments.collect(s,bad,request(1)));payments.collect(s,collectBody("transfer","company"),request(1));
  for(int stop:jdbc.queryForList("SELECT id FROM trip_stops WHERE trip_id=? ORDER BY seq",Integer.class,t))driver.complete(stop);
  assertEquals(0,money("revenue"));assertEquals(0,((Number)((Map<?,?>)payments.own(request(1)).get("balances")).get("holding")).longValue());
  jdbc.update("UPDATE orders SET stage='awaiting_pickup',in_warehouse=false WHERE id<>1");distribution.confirm(plan(DAY,1,1));t=trip(1);driver.start(t);int delivery=stop(t,"delivery");driver.complete(delivery);assertEquals(40000,money("revenue"));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM payment_collections",Integer.class));
  assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE orders SET shipping_fee_vnd=50000 WHERE id=1"));
 }
 @Test void unpaidSenderCanChooseRecipientAndDriverCannotCollectOtherDriversStops() {
  sender();priced();jdbc.update("UPDATE orders SET stage='awaiting_pickup',in_warehouse=false,payment_payer='sender' WHERE id=1");distribution.confirm(phasePlan("pickup"));int t=trip(1);driver.start(t);int s=stop(t,"pickup");
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->payments.collect(s,collectBody("cash","driver"),request(2)));
  payments.payer(s,new HashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"payer","recipient")),request(1));driver.complete(s);assertEquals("recipient",jdbc.queryForObject("SELECT payment_payer FROM orders WHERE id=1",String.class));
 }
 @Test void returnedPrepaidOrderRefundDoesNotInventRevenueOrExpense() {
  sender();priced();jdbc.update("UPDATE orders SET stage='awaiting_pickup',in_warehouse=false,payment_payer='sender' WHERE id=1");distribution.confirm(phasePlan("pickup"));int t=trip(1);driver.start(t);payments.collect(stop(t,"pickup"),collectBody("cash","driver"),request(1));
  var b=new HashMap<String,Object>(Map.of("requestId",UUID.randomUUID().toString(),"method","cash","reference","","reason","Hoàn cước sau khi hoàn hàng"));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->payments.refund(1,b,admin()));
  jdbc.update("UPDATE orders SET stage='returned',delivery_failures=3,delivery_cancelled_at=now() WHERE id=1");payments.refund(1,b,admin());payments.refund(1,b,admin());assertEquals(0,money("revenue"));assertEquals(0,money("expense"));assertEquals(0,((Number)((Map<?,?>)payments.own(request(1)).get("balances")).get("holding")).longValue());
 }
 @Test void vehicleFuelReceiptNeedsApprovalAndCanBeCancelledWithoutDoubleExpense() {
  var b=new HashMap<String,Object>(Map.of("requestId",UUID.randomUUID().toString(),"vehicleId",1,"date",DAY.toString(),"kind","fuel","amount",450000,"description","Đổ dầu cho xe QA","reference","HD-NL-QA"));long id=((Number)vehicleCosts.create(b,request(1)).get("id")).longValue();vehicleCosts.create(b,request(1));assertEquals(0,money("expense"));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->vehicleCosts.review(id,"approve",Map.of(),request(1)));vehicleCosts.review(id,"approve",Map.of(),admin());vehicleCosts.review(id,"approve",Map.of(),admin());assertEquals(450000,money("expense"));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->vehicleCosts.review(id,"cancel",Map.of("reason","Sai số tiền hóa đơn"),admin()));assertEquals(450000,money("expense"));
 }
 @Test void fixedSalaryAccruesByCalendarNotTripsAndPayrollDoesNotDoubleCountBase() {
  var accrual=new vn.gk.logistics.finance.FixedSalaryService(jdbc,policy,true);accrual.accrue();long before=money("expense");accrual.accrue();assertEquals(before,money("expense"));assertEquals(15000000,jdbc.queryForObject("SELECT sum(expense) FROM finance_ledger WHERE driver_id=1 AND entry_date BETWEEN '2026-10-01' AND '2026-10-31'",Long.class));
  octoberWork();var p=finance.payroll(payrollBody(),admin());long id=((Number)p.get("id")).longValue();finance.approve(id,admin());assertEquals(before+25000,money("expense"));finance.cancelPayroll(id,new HashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"reason","Điều chỉnh phụ cấp")),admin());assertEquals(before,money("expense"));
 }

 @Test void changedFixedSalaryReconcilesOnceAndInvalidatesOldDraft() {
  var draft=finance.payroll(payrollBody(),admin());long id=((Number)draft.get("id")).longValue();
  var update=new HashMap<String,Object>(Map.of("requestId",UUID.randomUUID().toString(),"driverId",1,"month","2026-10","amount",16000000,"reason","Điều chỉnh lương theo hợp đồng"));
  finance.setSalary(update,admin());finance.setSalary(update,admin());assertThrows(org.springframework.web.server.ResponseStatusException.class,()->finance.approve(id,admin()));
  var accrual=new vn.gk.logistics.finance.FixedSalaryService(jdbc,policy,true);accrual.accrue();assertEquals(16000000,jdbc.queryForObject("SELECT sum(expense) FROM finance_ledger WHERE driver_id=1 AND entry_date BETWEEN '2026-10-01' AND '2026-10-31'",Long.class));long before=money("expense");accrual.accrue();assertEquals(before,money("expense"));
 }
 @Test void explicitUnpaidDecisionAllowsDeliveryButKeepsOutstandingPayment() {
  priced();oneDelivery();int t=jdbc.queryForObject("SELECT id FROM trips WHERE status='in_progress'",Integer.class),sid=stop(t,"delivery");
  var b=new HashMap<String,Object>(Map.of("requestId",UUID.randomUUID().toString()));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->payments.unpaid(sid,b,request(2)));
  payments.unpaid(sid,b,request(1));payments.unpaid(sid,b,request(1));driver.complete(sid);
  assertEquals("unpaid",jdbc.queryForObject("SELECT payment_status FROM order_payments WHERE order_id=1",String.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM payment_collections",Integer.class));assertEquals(40000,money("revenue"));
 }
 @Test void simplePaidConfirmationDoesNotRequireAnInAppCashHandover() {
  priced();oneDelivery();int t=jdbc.queryForObject("SELECT id FROM trips WHERE status='in_progress'",Integer.class),sid=stop(t,"delivery");
  var b=collectBody("external","driver");payments.collect(sid,b,request(1));payments.collect(sid,b,request(1));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->payments.unpaid(sid,new HashMap<>(Map.of("requestId",UUID.randomUUID().toString())),request(1)));
  driver.complete(sid);assertEquals("external",jdbc.queryForObject("SELECT remittance_status FROM order_payments WHERE order_id=1",String.class));
  assertEquals(0,((Number)((Map<?,?>)payments.own(request(1)).get("balances")).get("holding")).longValue());assertEquals(40000,money("revenue"));
 }
 @Test void shortDistanceUsesMinimumFareRatherThanFreeDelivery() {
  var q=pricing.quote(new HashMap<String,Object>(Map.of("pickupLat",10.765,"pickupLng",106.603,"lat",10.765,"lng",106.603,"weightKg",3)));
  assertEquals(0,((Number)q.get("billed_km")).intValue());assertEquals(20000,((Number)q.get("amount_vnd")).longValue());
 }

 @Autowired vn.gk.logistics.fleet.FleetController fleet;
 Map<String,Object> warehouseBody(){return new HashMap<>(Map.of("code","KHO-QA-2","name","GK Tân Bình","address","20 Cộng Hòa, Tân Bình","lat",10.8,"lng",106.65,"color","#0f766e"));}
 Map<String,Object> vehicleBody(int w){return new HashMap<>(Map.of("code","XE-QA-3","plate","51D-600.13","warehouseId",w,"capacityKg",1000,"capacityM3",8,"costPerKm",6500));}
 Map<String,Object> driverBody(int w){return new HashMap<>(Map.of("code","TX-QA-3","name","Trần Minh Khang","phone","0903000003","warehouseId",w));}
 @Test void fleetCanExpandEditArchiveAndRestoreWithoutDeletingHistory(){
  int w=((Number)fleet.create("warehouses",warehouseBody(),admin()).get("id")).intValue();
  var vb=vehicleBody(w);int v=((Number)fleet.create("vehicles",vb,admin()).get("id")).intValue();var db=driverBody(w);db.put("vehicleId",v);int d=((Number)fleet.create("drivers",db,admin()).get("id")).intValue();
  db.put("name","Trần Minh Khôi");fleet.update("drivers",d,db,admin());assertEquals("Trần Minh Khôi",jdbc.queryForObject("SELECT name FROM drivers WHERE id=?",String.class,d));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->fleet.archive("vehicles",v,admin()));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->fleet.archive("warehouses",w,admin()));
  fleet.archive("drivers",d,admin());assertEquals("off",jdbc.queryForObject("SELECT gk_driver_state(?,?)",String.class,d,DAY));assertFalse(api.drivers(null).stream().anyMatch(x->((Number)x.get("id")).intValue()==d));assertTrue(api.drivers(null,true).stream().anyMatch(x->((Number)x.get("id")).intValue()==d));
  fleet.archive("vehicles",v,admin());fleet.archive("warehouses",w,admin());assertEquals("unavailable",jdbc.queryForObject("SELECT gk_vehicle_state(?,?)",String.class,v,DAY));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->fleet.restore("vehicles",v,admin()));
  fleet.restore("warehouses",w,admin());fleet.restore("vehicles",v,admin());fleet.restore("drivers",d,admin());assertNull(jdbc.queryForObject("SELECT archived_at FROM drivers WHERE id=?",java.sql.Timestamp.class,d));assertTrue(jdbc.queryForObject("SELECT count(*) FROM fleet_changes",Integer.class)>=10);
 }
 @Test void fleetRejectsDuplicatesCrossWarehouseInvalidInputsAndDriverPermissions(){
  var b=vehicleBody(1);int v=((Number)fleet.create("vehicles",b,admin()).get("id")).intValue();var duplicate=vehicleBody(1);duplicate.put("code","XE-QA-4");assertThrows(org.springframework.web.server.ResponseStatusException.class,()->fleet.create("vehicles",duplicate,admin()));
  b.put("capacityKg",-1);assertThrows(IllegalArgumentException.class,()->fleet.update("vehicles",v,b,admin()));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->fleet.create("warehouses",warehouseBody(),request(1)));
  int w=((Number)fleet.create("warehouses",warehouseBody(),admin()).get("id")).intValue();var d=driverBody(w);d.put("vehicleId",v);assertThrows(org.springframework.web.server.ResponseStatusException.class,()->fleet.create("drivers",d,admin()));d.put("vehicleId",null);d.put("phone","123");assertThrows(IllegalArgumentException.class,()->fleet.create("drivers",d,admin()));
  d.put("phone","0903000001");assertThrows(org.springframework.web.server.ResponseStatusException.class,()->fleet.create("drivers",d,admin()));
  var invalid=warehouseBody();invalid.put("code","KHO-QA-3");invalid.put("lat",Double.NaN);assertThrows(IllegalArgumentException.class,()->fleet.create("warehouses",invalid,admin()));
 }
 @Test void fleetCannotArchiveOrChangeResourcesAlreadyReservedForATrip(){
  distribution.confirm(plan(DAY,1,1));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->fleet.archive("drivers",1,admin()));
  var b=new HashMap<String,Object>(Map.of("code","XE-QA-1","plate","51D-600.01","warehouseId",1,"capacityKg",900,"capacityM3",8,"costPerKm",6500));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->fleet.update("vehicles",1,b,admin()));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->fleet.archive("vehicles",1,admin()));
  var w=new HashMap<>(warehouseBody());w.put("code","QA");assertThrows(org.springframework.web.server.ResponseStatusException.class,()->fleet.update("warehouses",1,w,admin()));
 }
 @Test void newDriverSalaryStartsOnJoiningAndDoesNotBackfillArchivedGap(){
  int d=((Number)fleet.create("drivers",driverBody(1),admin()).get("id")).intValue();var service=new vn.gk.logistics.finance.FixedSalaryService(jdbc,policy,true);service.accrue();assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM finance_entries WHERE driver_id=? AND entry_date<?",Integer.class,d,DAY));
  fleet.archive("drivers",d,admin());clock.at("2026-11-24T09:00:00+07:00");fleet.restore("drivers",d,admin());service.accrue();assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM finance_entries WHERE driver_id=? AND entry_date='2026-11-23'",Integer.class,d));assertEquals(1000000,jdbc.queryForObject("SELECT sum(expense) FROM finance_ledger WHERE driver_id=? AND category='salary'",Long.class,d));
 }

 @Test void expiredPickupPreviewExplainsDeadlineAndReschedulingRestoresARoute(){
  sender();jdbc.update("UPDATE orders SET stage='awaiting_pickup',in_warehouse=false,pickup_from=?,pickup_to=? WHERE id=1",DAY.minusDays(1).atTime(17,35),DAY.atTime(1,35));
  var request=new DistributionController.Request(DAY,1,45,List.of(new DistributionController.Assignment(1,1)),"pickup");
  var before=distribution.preview(request);var result=(Map<?,?>)before.get("result");var stats=(Map<?,?>)result.get("stats");var failure=(Map<?,?>)((List<?>)stats.get("unassigned")).get(0);
  assertEquals("order_deadline_passed",failure.get("reason"));assertEquals("QA-1",failure.get("code"));assertNotNull(failure.get("window_end_min"));assertTrue(((List<?>)result.get("routes")).isEmpty());assertEquals("awaiting_pickup",stage(1));
  api.reschedule(1,Map.of("pickupFrom",DAY.atTime(9,30).toString(),"pickupTo",DAY.atTime(12,0).toString(),"deliveryFrom",DAY.plusDays(1).atTime(8,0).toString(),"deliveryTo",DAY.plusDays(1).atTime(20,0).toString(),"reason","Khách xác nhận lại giờ lấy hàng"));
  var after=(Map<?,?>)distribution.preview(request).get("result");assertEquals(1,((Number)((Map<?,?>)after.get("stats")).get("assigned")).intValue());assertFalse(((List<?>)after.get("routes")).isEmpty());
 }

 @Autowired vn.gk.logistics.warehouses.WarehouseAssignmentService warehouseAssignment;
 Map<String,Object> newOrder(){return new HashMap<>(Map.ofEntries(Map.entry("pickupName","Nguyễn Minh An"),Map.entry("pickupPhone","0903000021"),Map.entry("pickupAddress","38 An Dương Vương, TP. Hồ Chí Minh"),Map.entry("pickupLat",10.765),Map.entry("pickupLng",106.603),Map.entry("customerName","Trần Minh Anh"),Map.entry("phone","0903000022"),Map.entry("address","20 Nguyễn Huệ, TP. Hồ Chí Minh"),Map.entry("lat",10.9),Map.entry("lng",106.8),Map.entry("weightKg",3),Map.entry("volumeM3",.02),Map.entry("goodsDescription","Thùng quần áo"),Map.entry("goodsType","clothing"),Map.entry("pickupFrom",DAY.atTime(10,0).toString()),Map.entry("pickupTo",DAY.atTime(20,0).toString()),Map.entry("deliveryFrom",DAY.plusDays(1).atTime(8,0).toString()),Map.entry("deliveryTo",DAY.plusDays(1).atTime(20,0).toString())));}
 @Test void adminAndCustomerOrdersBelongToNearestActivePickupWarehouse(){
  jdbc.update("INSERT INTO warehouses(id,code,name,lat,lng) VALUES(2,'GK-SECOND','GK Kho giao',10.9,106.8)");
  var order=newOrder();order.put("warehouseId",2);long adminOrder=((Number)api.createOrder(order).get("id")).longValue();
  var customerOrder=customers.create(new vn.gk.logistics.customers.CustomerInternalController.Booking(UUID.randomUUID(),UUID.randomUUID(),order));
  for(long id:List.of(adminOrder,((Number)customerOrder.get("id")).longValue())){
   assertEquals(1,jdbc.queryForObject("SELECT warehouse_id FROM orders WHERE id=?",Integer.class,id));assertEquals("nearest_pickup_geodesic",jdbc.queryForObject("SELECT warehouse_assignment_method FROM orders WHERE id=?",String.class,id));assertEquals(0,jdbc.queryForObject("SELECT warehouse_distance_m FROM orders WHERE id=?",Long.class,id));
  }
  jdbc.update("UPDATE warehouses SET archived_at=now() WHERE id=1");assertEquals(2,warehouseAssignment.nearestPickup(10.765,106.603).warehouseId());
 }
 @Test void nearestWarehouseUsesGeographicDistanceAndDeterministicTies(){
  jdbc.update("UPDATE warehouses SET lat=80,lng=10 WHERE id=1");jdbc.update("INSERT INTO warehouses(id,code,name,lat,lng) VALUES(2,'POLAR-2','Kho hai',76,0),(3,'POLAR-3','Kho ba',80,10)");
  assertEquals(1,warehouseAssignment.nearestPickup(80,0).warehouseId());
  assertThrows(IllegalArgumentException.class,()->warehouseAssignment.nearestPickup(Double.NaN,0));
 }
 @Test void warehousePolicyMigrationOnlyMovesUnassignedGoodsStillAtSender() throws Exception{
  sender();jdbc.update("INSERT INTO warehouses(id,code,name,lat,lng) VALUES(2,'FAR','Kho xa',10.9,106.8)");jdbc.update("UPDATE orders SET warehouse_id=2");jdbc.update("UPDATE orders SET stage='awaiting_pickup',in_warehouse=false WHERE id IN (1,3)");
  int trip=jdbc.queryForObject("INSERT INTO trips(code,vehicle_id,driver_id,warehouse_id,status,phase,planned_start,planned_end) VALUES('HISTORY',1,1,1,'completed','pickup','2026-11-21 08:00','2026-11-21 12:00') RETURNING id",Integer.class);
  jdbc.update("INSERT INTO trip_stops(trip_id,order_id,seq,kind,state) VALUES(?,3,1,'pickup','failed')",trip);
  try(var resource=getClass().getResourceAsStream("/db/019_batch_pickup_policy.sql")){String sql=new String(resource.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);sql=sql.substring(sql.indexOf("CREATE TEMP TABLE"));String finalSql=sql;tx.executeWithoutResult(status->jdbc.execute(finalSql));}
  assertEquals(1,jdbc.queryForObject("SELECT warehouse_id FROM orders WHERE id=1",Integer.class));assertEquals(2,jdbc.queryForObject("SELECT warehouse_id FROM orders WHERE id=2",Integer.class));assertEquals(2,jdbc.queryForObject("SELECT warehouse_id FROM orders WHERE id=3",Integer.class));
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM customer_tracking_events WHERE order_id=1 AND type='warehouse_assigned'",Integer.class));
 }

 @Test void queueIncludesNineOlderAndThreeSameDayExpiredOrdersInBothCountsAndPreview(){
  sender();jdbc.update("UPDATE orders SET stage='awaiting_pickup',in_warehouse=false,pickup_from=?,pickup_to=?",DAY.minusDays(1).atTime(17,35),DAY.atTime(1,35));
  for(int id=4;id<=12;id++)jdbc.update("INSERT INTO orders(id,code,customer_name,address,lat,lng,weight_kg,volume_m3,tw_start,tw_end,warehouse_id,stage,in_warehouse,pickup_name,pickup_address,pickup_lat,pickup_lng,pickup_from,pickup_to,payment_required) SELECT ?,? ,customer_name,address,lat,lng,weight_kg,volume_m3,tw_start,tw_end,warehouse_id,'awaiting_pickup',false,pickup_name,pickup_address,pickup_lat,pickup_lng,?,?,false FROM orders WHERE id=1",id,"QUEUE-"+id,DAY.minusDays(1).atTime(8,0),DAY.minusDays(1).atTime(20,0));
  var counts=(Map<?,?>)distribution.resources(DAY,1,"pickup").get("counts");assertEquals(12,((Number)counts.get("at_warehouse")).intValue());assertEquals(12,((Number)counts.get("expired")).intValue());assertEquals(0,((Number)counts.get("ready")).intValue());
  var preview=distribution.preview(new DistributionController.Request(DAY,1,45,List.of(new DistributionController.Assignment(1,1)),"pickup"));var stats=(Map<?,?>)((Map<?,?>)preview.get("result")).get("stats");var excluded=(List<?>)stats.get("unassigned");
  assertEquals(12,((Number)stats.get("requested")).intValue());assertEquals(0,((Number)stats.get("considered")).intValue());assertEquals(12,excluded.size());assertTrue(excluded.stream().allMatch(x->"order_deadline_passed".equals(((Map<?,?>)x).get("reason"))));assertEquals("not_requested",stats.get("source"));assertEquals(counts,preview.get("queue_summary"));
 }
 @Test void queueReconcilesReadyExpiredAndFutureOrdersWithoutSilentlyDroppingAny(){
  sender();jdbc.update("UPDATE orders SET stage='awaiting_pickup',in_warehouse=false");jdbc.update("UPDATE orders SET pickup_from=?,pickup_to=? WHERE id=1",DAY.minusDays(1).atTime(8,0),DAY.minusDays(1).atTime(20,0));jdbc.update("UPDATE orders SET pickup_from=?,pickup_to=? WHERE id=3",DAY.plusDays(1).atTime(8,0),DAY.plusDays(1).atTime(20,0));
  var preview=distribution.preview(new DistributionController.Request(DAY,1,45,List.of(new DistributionController.Assignment(1,1)),"pickup"));var counts=(Map<?,?>)preview.get("queue_summary");var stats=(Map<?,?>)((Map<?,?>)preview.get("result")).get("stats");
  assertEquals(3,counts.get("at_warehouse"));assertEquals(1,counts.get("ready"));assertEquals(1,counts.get("expired"));assertEquals(1,counts.get("future"));assertEquals(1,((Number)stats.get("assigned")).intValue());assertEquals(2,((List<?>)stats.get("unassigned")).size());
  var reasons=((List<?>)stats.get("unassigned")).stream().map(x->((Map<?,?>)x).get("reason")).collect(java.util.stream.Collectors.toSet());assertEquals(Set.of("order_deadline_passed","scheduled_later"),reasons);
 }
 @Test void queueReportsBatchLimitInsteadOfHidingThe501stReadyOrder(){
  sender();jdbc.update("UPDATE orders SET stage='awaiting_pickup',in_warehouse=false,weight_kg=1,volume_m3=.001,service_min=0");
  jdbc.update("INSERT INTO orders(id,code,customer_name,address,lat,lng,weight_kg,volume_m3,tw_start,tw_end,warehouse_id,stage,in_warehouse,pickup_name,pickup_address,pickup_lat,pickup_lng,pickup_from,pickup_to,payment_required,service_min) SELECT i,'QUEUE-'||i,o.customer_name,o.address,o.lat,o.lng,1,.001,o.tw_start,o.tw_end,1,'awaiting_pickup',false,o.pickup_name,o.pickup_address,o.pickup_lat,o.pickup_lng,o.pickup_from,o.pickup_to,false,0 FROM orders o CROSS JOIN generate_series(4,501)i WHERE o.id=1");
  var preview=distribution.preview(new DistributionController.Request(DAY,1,45,List.of(new DistributionController.Assignment(1,1)),"pickup"));var counts=(Map<?,?>)preview.get("queue_summary");var stats=(Map<?,?>)((Map<?,?>)preview.get("result")).get("stats");
  assertEquals(501,counts.get("at_warehouse"));assertEquals(500,counts.get("considered"));assertEquals(1,counts.get("deferred"));assertEquals(501,((Number)stats.get("assigned")).intValue()+((List<?>)stats.get("unassigned")).size());assertEquals(1,((List<?>)stats.get("unassigned")).stream().filter(x->"batch_limit".equals(((Map<?,?>)x).get("reason"))).count());
 }

 @Autowired vn.gk.logistics.api.OrderBatchController orderBatches;
 Map<String,Object> batchSchedule(List<Integer> ids){return new HashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"ids",ids,"pickupFrom",DAY.atTime(10,0).toString(),"pickupTo",DAY.atTime(12,0).toString(),"deliveryFrom",DAY.plusDays(1).atTime(8,0).toString(),"deliveryTo",DAY.plusDays(1).atTime(20,0).toString(),"reason","Khách xác nhận hẹn lại"));}
 @Test void orderFiltersUsePickupOrDeliveryDeadlineAndExcludeCompletedOrders(){
  sender();jdbc.update("UPDATE orders SET stage='awaiting_pickup',in_warehouse=false,pickup_to=? WHERE id=1",DAY.atTime(8,30));jdbc.update("UPDATE orders SET tw_end=? WHERE id=2",DAY.atTime(8,45));jdbc.update("UPDATE orders SET stage='delivered',tw_start=?,tw_end=? WHERE id=3",DAY.minusDays(1).atTime(8,0),DAY.atTime(8,0));
  var late=api.orders(null,null,null,1,201,0,null,true);assertEquals(Set.of(1,2),late.stream().map(r->r.get("id")).collect(java.util.stream.Collectors.toSet()));assertTrue(late.stream().allMatch(r->Boolean.TRUE.equals(r.get("overdue"))));
  assertEquals(1,api.orders(null,null,null,1,201,0,"pickup",true).size());assertEquals(1,api.orders(null,null,null,1,201,0,"warehouse",true).size());assertTrue(api.orders(null,null,null,1,201,0,"completed",true).isEmpty());
  assertEquals("pickup",late.get(0).get("overdue_kind"));assertEquals("delivery",late.get(1).get("overdue_kind"));assertEquals(3,api.orders(null,"Nguyễn Thảo Vy",null,1,201,0,null,false).size());assertThrows(IllegalArgumentException.class,()->api.orders(null,null,null,null,200,0,"unknown",false));
 }
 @Test void bulkRescheduleUpdatesWaitingPickupAndWarehouseOrdersOnceWithAudit(){
  sender();jdbc.update("UPDATE orders SET stage='awaiting_pickup',in_warehouse=false,pickup_to=? WHERE id=1",DAY.atTime(8,30));jdbc.update("UPDATE orders SET tw_end=? WHERE id=2",DAY.atTime(8,45));
  UUID booking=UUID.randomUUID();jdbc.update("UPDATE orders SET customer_ref=?,customer_booking_id=? WHERE id=1",UUID.randomUUID(),booking);
  var previousPickup=jdbc.queryForObject("SELECT pickup_from FROM orders WHERE id=2",java.sql.Timestamp.class);var body=batchSchedule(List.of(1,2));var result=orderBatches.reschedule(body,admin());assertEquals(2,result.get("updated"));long revision=jdbc.queryForObject("SELECT revision FROM routing_state WHERE id=1",Long.class);
  var customerSnapshot=customers.sync(List.of(booking)).get(0);assertEquals(DAY.atTime(10,0).atZone(WorkDayPolicy.ZONE).toEpochSecond()/60.,((Number)customerSnapshot.get("pickup_from_min")).doubleValue());assertEquals(DAY.plusDays(1).atTime(20,0).atZone(WorkDayPolicy.ZONE).toEpochSecond()/60.,((Number)customerSnapshot.get("delivery_to_min")).doubleValue());
  orderBatches.reschedule(body,admin());assertEquals(revision,jdbc.queryForObject("SELECT revision FROM routing_state WHERE id=1",Long.class));assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM customer_tracking_events WHERE type='schedule_updated'",Integer.class));assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM order_reschedule_items",Integer.class));assertEquals(previousPickup,jdbc.queryForObject("SELECT pickup_from FROM orders WHERE id=2",java.sql.Timestamp.class));assertTrue(api.orders(null,null,null,1,201,0,null,true).isEmpty());
  var changed=new HashMap<>(body);changed.put("reason","Nội dung khác");assertThrows(org.springframework.web.server.ResponseStatusException.class,()->orderBatches.reschedule(changed,admin()));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE order_reschedule_items SET new_schedule='{}'::jsonb"));
 }
 @Test void bulkRescheduleRollsBackAllOrdersWhenOneAppointmentIsInvalid(){
  sender();jdbc.update("UPDATE orders SET stage='awaiting_pickup',in_warehouse=false WHERE id=2");var before=jdbc.queryForObject("SELECT tw_end FROM orders WHERE id=1",java.sql.Timestamp.class);var body=batchSchedule(List.of(1,2));body.remove("pickupFrom");
  assertThrows(IllegalArgumentException.class,()->orderBatches.reschedule(body,admin()));assertEquals(before,jdbc.queryForObject("SELECT tw_end FROM orders WHERE id=1",java.sql.Timestamp.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM order_reschedule_batches",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM customer_tracking_events WHERE type='schedule_updated'",Integer.class));
 }
 @Test void bulkRescheduleRejectsReservedTripsDuplicateIdsAndDriverMutation(){
  sender();distribution.confirm(plan(DAY,1,1));var body=batchSchedule(List.of(1,2));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->orderBatches.reschedule(body,admin()));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM order_reschedule_batches",Integer.class));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->orderBatches.reschedule(body,request(1)));assertThrows(IllegalArgumentException.class,()->orderBatches.reschedule(batchSchedule(List.of(2,2)),admin()));
 }

}
