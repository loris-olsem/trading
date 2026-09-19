package com.loris.bravos;

import com.loris.bravos.domain.Model.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class Fixtures {
  public static final Instant NOW = Instant.parse("2026-09-21T14:00:00Z");

  public static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  public static Alert alert(
      String key, Action action, String price, String before, String after, String stop) {
    return new Alert(
        key,
        "https://bravosresearch.com/news-feed/" + key,
        LocalDate.of(2026, 9, 1),
        key,
        "CF",
        action,
        n(price),
        n(before),
        n(after),
        n(stop),
        List.of(d("150")));
  }

  public static BigDecimal n(String s) {
    return s == null ? null : d(s);
  }

  public static Cycle cycle() {
    return new Cycle(alert("opening", Action.OPEN, "100", null, "5", "90"), true);
  }

  public static Instrument instrument() {
    return new Instrument(1890, "CF", "USD", true, true, "real", 2, 4, d("10"), d("1"));
  }

  public static Quote quote(String price) {
    return new Quote(d(price), NOW, true, "USD");
  }

  public static Account account() {
    return account(d("4610"), d("4610"), true, false, true, true, true, NOW);
  }

  public static Account account(
      BigDecimal equity,
      BigDecimal cash,
      boolean complete,
      boolean pending,
      boolean active,
      boolean sizing,
      boolean stops,
      Instant observed) {
    return new Account(
        observed,
        equity,
        cash,
        d("10000"),
        d("10000"),
        complete,
        pending,
        active,
        sizing,
        stops,
        List.of(),
        List.of());
  }

  public static Position position(
      long id, String units, String stop, boolean enabled, boolean trailing) {
    return new Position(id, 0, 1890, d(units), d("100"), d("0"), n(stop), enabled, trailing, true);
  }
}
