package com.loris.bravos.broker;

import static com.loris.bravos.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.loris.bravos.domain.Model.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class OrderPayloadsTest {
  @Test
  void marketPayloadPreservesAmountAndStopButCannotContainLimitOrTrigger() throws Exception {
    for (String settlement : List.of("real", "cfd")) {
      var intent =
          new Intent(
              "i",
              "c",
              "e",
              Action.OPEN,
              1,
              null,
              d("368.80"),
              d("800"),
              null,
              d("516.63"),
              d("480"),
              settlement,
              "mkt");
      var json = com.loris.bravos.util.Json.MAPPER;
      var restored = json.readValue(json.writeValueAsString(intent), Intent.class);
      assertEquals(intent, restored);
      var body = OrderPayloads.create(restored).body();
      assertEquals("mkt", body.path("orderType").asText());
      assertEquals(d("800"), body.path("amount").decimalValue());
      assertEquals(d("480"), body.path("stopLossRate").decimalValue());
      assertEquals("fixed", body.path("stopLossType").asText());
      assertEquals(settlement, body.path("settlementType").asText());
      assertEquals(1, body.path("leverage").asInt());
      assertFalse(body.has("limitRate"));
      assertFalse(body.has("triggerRate"));
      assertFalse(body.has("units"));
      var legacy = json.valueToTree(intent);
      ((com.fasterxml.jackson.databind.node.ObjectNode) legacy).remove("orderType");
      assertEquals("limitIOC", json.treeToValue(legacy, Intent.class).orderType());
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Intent(
                "i",
                "c",
                "e",
                Action.OPEN,
                1,
                null,
                d("50"),
                d("100"),
                null,
                d("100"),
                d("90"),
                "real",
                "mit"));
  }

  @Test
  void v3OpeningRetainsAmountCeilingAndExactStop() {
    var request = OrderPayloads.create(opening(d("800"), d("516.63"), d("480"), "real"));
    assertEquals("/api/v3/trading/execution/orders", request.path());
    assertEquals("POST", request.method());
    var body = request.body();
    assertEquals("limitIOC", body.path("orderType").asText());
    assertEquals("real", body.path("settlementType").asText());
    assertEquals(d("800"), body.path("amount").decimalValue());
    assertEquals(d("516.63"), body.path("limitRate").decimalValue());
    assertEquals(d("480"), body.path("stopLossRate").decimalValue());
    assertEquals("fixed", body.path("stopLossType").asText());
    assertEquals(1, body.path("leverage").asInt());
    assertFalse(body.has("units"));
    assertFalse(body.has("triggerRate"));
  }

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
    assertEquals(
        "CAPPED_ORDER_REQUIRES_REAL_ASSET",
        assertThrows(
                IllegalArgumentException.class,
                () -> OrderPayloads.create(opening(d("50"), d("100"), d("90"), "cfd")))
            .getMessage());
    assertEquals("PATCH", OrderPayloads.create(position(Action.STOP, 1L, null, d("90"))).method());
  }
}
