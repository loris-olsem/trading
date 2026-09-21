package com.loris.bravos.domain;

import java.time.*;
import java.util.Set;

/** NYSE/Nasdaq published 2026 core sessions. Update from the sources in docs/market-hours.md. */
public final class UsEquityCalendar {
  private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
  private static final Set<LocalDate> HOLIDAYS =
      Set.of(
          LocalDate.of(2026, 1, 1),
          LocalDate.of(2026, 1, 19),
          LocalDate.of(2026, 2, 16),
          LocalDate.of(2026, 4, 3),
          LocalDate.of(2026, 5, 25),
          LocalDate.of(2026, 6, 19),
          LocalDate.of(2026, 7, 3),
          LocalDate.of(2026, 9, 7),
          LocalDate.of(2026, 11, 26),
          LocalDate.of(2026, 12, 25));
  private static final Set<LocalDate> EARLY_CLOSES =
      Set.of(LocalDate.of(2026, 11, 27), LocalDate.of(2026, 12, 24));

  private UsEquityCalendar() {}

  public static boolean covers(Instant instant) {
    return instant.atZone(NEW_YORK).getYear() == 2026;
  }

  public static boolean isOpen(Instant instant) {
    var local = instant.atZone(NEW_YORK);
    var date = local.toLocalDate();
    if (!covers(instant)
        || date.getDayOfWeek() == DayOfWeek.SATURDAY
        || date.getDayOfWeek() == DayOfWeek.SUNDAY
        || HOLIDAYS.contains(date)) return false;
    var close = EARLY_CLOSES.contains(date) ? LocalTime.of(13, 0) : LocalTime.of(16, 0);
    return !local.toLocalTime().isBefore(LocalTime.of(9, 30))
        && local.toLocalTime().isBefore(close);
  }
}
