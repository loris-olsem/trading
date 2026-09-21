package com.loris.bravos.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UsEquityCalendarTest {
  Instant local(String date, String time) {
    return LocalDateTime.parse(date + "T" + time).atZone(ZoneId.of("America/New_York")).toInstant();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026-01-01",
        "2026-01-19",
        "2026-02-16",
        "2026-04-03",
        "2026-05-25",
        "2026-06-19",
        "2026-07-03",
        "2026-09-07",
        "2026-11-26",
        "2026-12-25",
        "2026-09-19",
        "2026-09-20"
      })
  void holidaysAndWeekendsStayClosed(String date) {
    assertFalse(UsEquityCalendar.isOpen(local(date, "12:00")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-11-27", "2026-12-24"})
  void earlyCloseExcludesOnePmAndLater(String date) {
    assertTrue(UsEquityCalendar.isOpen(local(date, "09:30")));
    assertTrue(UsEquityCalendar.isOpen(local(date, "12:59:59.999999999")));
    assertFalse(UsEquityCalendar.isOpen(local(date, "13:00")));
    assertFalse(UsEquityCalendar.isOpen(local(date, "15:00")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026-01-02",
        "2026-07-02",
        "2026-09-21",
        "2026-10-12",
        "2026-11-11",
        "2026-12-31"
      })
  void regularSessionHasExactBoundariesAndExcludesExtendedHours(String date) {
    assertFalse(UsEquityCalendar.isOpen(local(date, "09:29:59.999999999")));
    assertTrue(UsEquityCalendar.isOpen(local(date, "09:30")));
    assertTrue(UsEquityCalendar.isOpen(local(date, "15:59:59.999999999")));
    assertFalse(UsEquityCalendar.isOpen(local(date, "16:00")));
    assertFalse(UsEquityCalendar.isOpen(local(date, "18:00")));
  }

  @Test
  void daylightSavingUsesNewYorkRatherThanFixedUtcOrLocalComputerTime() {
    assertFalse(UsEquityCalendar.isOpen(Instant.parse("2026-03-06T13:30:00Z")));
    assertTrue(UsEquityCalendar.isOpen(Instant.parse("2026-03-06T14:30:00Z")));
    assertTrue(UsEquityCalendar.isOpen(Instant.parse("2026-03-09T13:30:00Z")));
    assertTrue(UsEquityCalendar.isOpen(Instant.parse("2026-10-30T13:30:00Z")));
    assertFalse(UsEquityCalendar.isOpen(Instant.parse("2026-11-02T13:30:00Z")));
    assertTrue(UsEquityCalendar.isOpen(Instant.parse("2026-11-02T14:30:00Z")));
  }

  @Test
  void unpublishedYearsCannotBeAssumedOpen() {
    assertFalse(UsEquityCalendar.covers(local("2025-12-31", "12:00")));
    assertFalse(UsEquityCalendar.isOpen(local("2025-12-31", "12:00")));
    assertTrue(UsEquityCalendar.covers(local("2026-12-31", "23:59:59")));
    assertFalse(UsEquityCalendar.covers(local("2027-01-01", "00:00")));
    assertFalse(UsEquityCalendar.isOpen(local("2027-01-04", "12:00")));
  }
}
