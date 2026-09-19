package com.loris.bravos.broker;

import static com.loris.bravos.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.loris.bravos.domain.Model.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class OrderPayloadsTest {
  Intent opening(BigDecimal amount, BigDecimal ceiling, BigDecimal stop, String settlement) {
    return new Intent(
        "i", "c", "e", Action.OPEN, 1, null, d("50"), amount, null, ceiling, stop, settlement);
  }

  Intent position(Action a, Long id, BigDecimal units, BigDecimal stop) {
    return new Intent("i", "c", "e", a, 1, id, null, null, units, null, stop, null);
  }

  @Test
  void invalidMoneyProtectionAndPositionIdentifiersCannotReachHttp() {
    for (BigDecimal value : Arrays.asList(null, d("0"), d("-1"))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> OrderPayloads.create(opening(value, d("100"), d("90"), "real")));
      assertThrows(
          IllegalArgumentException.class,
          () -> OrderPayloads.create(opening(d("50"), d("100"), value, "real")));
      assertThrows(
          IllegalArgumentException.class,
          () -> OrderPayloads.create(position(Action.REDUCE, 1L, value, null)));
      assertThrows(
          IllegalArgumentException.class,
          () -> OrderPayloads.create(position(Action.STOP, 1L, null, value)));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> OrderPayloads.create(opening(d("50"), null, d("90"), "real")));
    assertThrows(
        IllegalArgumentException.class,
        () -> OrderPayloads.create(opening(d("50"), d("90"), d("90"), "real")));
    assertThrows(
        IllegalArgumentException.class,
        () -> OrderPayloads.create(opening(d("50"), d("89"), d("90"), "real")));
    assertThrows(
        IllegalArgumentException.class,
        () -> OrderPayloads.create(opening(d("50"), d("100"), d("90"), "marginTrade")));
    for (Long id : Arrays.asList(null, 0L, -1L))
      assertThrows(
          IllegalArgumentException.class,
          () -> OrderPayloads.create(position(Action.CLOSE, id, d("1"), null)));
    var cfd = OrderPayloads.create(opening(d("50"), d("100"), d("90"), "cfd"));
    assertEquals("cfd", cfd.body().get("settlementType").asText());
    assertEquals("PATCH", OrderPayloads.create(position(Action.STOP, 1L, null, d("90"))).method());
  }
}
