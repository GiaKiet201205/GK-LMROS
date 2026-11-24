package vn.gk.logistics.dispatch;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkDayPolicyTest {
 private WorkDayPolicy at(String time) { return new WorkDayPolicy(Clock.fixed(OffsetDateTime.parse(time).toInstant(),ZoneOffset.UTC)); }
 @Test void cutoffUsesVietnamDateAndResetsAtMidnight() {
  assertEquals(LocalDate.parse("2026-11-22"),at("2026-11-22T19:59:59+07:00").dispatchDate());
  assertEquals(LocalDate.parse("2026-11-23"),at("2026-11-22T20:00:00+07:00").dispatchDate());
  assertEquals(LocalDate.parse("2026-11-23"),at("2026-11-22T23:59:59+07:00").dispatchDate());
  assertEquals(LocalDate.parse("2026-11-23"),at("2026-11-23T00:00:00+07:00").dispatchDate());
  assertEquals(LocalDate.parse("2027-01-01"),at("2026-12-31T13:00:00Z").dispatchDate());
 }
 @Test void departureIsAllowedOnlyInsideEightToTwenty() {
  assertFalse(at("2026-11-22T07:59:59+07:00").inShift());
  assertTrue(at("2026-11-22T08:00:00+07:00").inShift());
  assertTrue(at("2026-11-22T19:59:59+07:00").inShift());
  assertFalse(at("2026-11-22T20:00:00+07:00").inShift());
 }
 @Test void aFutureReservationDoesNotAllowAnEarlyDeparture() {
  var date=LocalDate.parse("2026-11-23");
  assertNotNull(at("2026-11-22T21:00:00+07:00").startBlock(date,date.atTime(8,0),date.atTime(10,0)));
  assertNotNull(at("2026-11-23T07:59:00+07:00").startBlock(date,date.atTime(8,0),date.atTime(10,0)));
  assertNull(at("2026-11-23T08:00:00+07:00").startBlock(date,date.atTime(8,0),date.atTime(10,0)));
 }
 @Test void delayedDeparturesMustStillFinishInsideTheShift() {
  var date=LocalDate.parse("2026-11-22");
  assertNull(at("2026-11-22T19:00:00+07:00").startBlock(date,date.atTime(18,30),date.atTime(19,30)));
  assertNotNull(at("2026-11-22T19:01:00+07:00").startBlock(date,date.atTime(18,30),date.atTime(19,30)));
  assertNotNull(at("2026-11-22T20:00:00+07:00").startBlock(date,date.atTime(18,30),date.atTime(19,30)));
 }
 @Test void afterCutoffBookingTodayIsRejectedButTomorrowIsAccepted() {
  var policy=at("2026-11-22T20:00:00+07:00");
  assertThrows(IllegalArgumentException.class,()->policy.validateDate(LocalDate.parse("2026-11-22")));
  assertDoesNotThrow(()->policy.validateDate(LocalDate.parse("2026-11-23")));
 }
}
