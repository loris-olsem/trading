package com.loris.bravos.broker;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loris.bravos.domain.Model.*;
import com.loris.bravos.util.Json;

public final class OrderPayloads {
  private OrderPayloads() {}

  public record Request(String method, String path, ObjectNode body) {}

  public static Request create(Intent i) {
    ObjectNode body = Json.MAPPER.createObjectNode();
    if (i.action() == Action.OPEN || i.action() == Action.ADD) {
      if (i.agentAmount() == null
          || i.agentAmount().signum() <= 0
          || i.stop() == null
          || i.stop().signum() <= 0
          || i.ceiling() == null
          || i.stop().compareTo(i.ceiling()) >= 0)
        throw new IllegalArgumentException("INVALID_OPEN_INTENT");
      if (!"real".equals(i.settlementType()) && !"cfd".equals(i.settlementType()))
        throw new IllegalArgumentException("UNSUPPORTED_SETTLEMENT");
      if (!"real".equals(i.settlementType()))
        throw new IllegalArgumentException("CAPPED_ORDER_REQUIRES_REAL_ASSET");
      body.put("action", "open")
          .put("transaction", "buy")
          .put("instrumentId", i.instrumentId())
          .put("orderType", "limitIOC")
          .put("limitRate", i.ceiling())
          .put("leverage", 1)
          .put("amount", i.agentAmount())
          .put("orderCurrency", "usd")
          .put("stopLossRate", i.stop())
          .put("stopLossType", "fixed")
          .put("settlementType", i.settlementType());
      // v3 queues asynchronously; acceptance is not a fill. Reconcile through
      // the shared v2 lookup using the durable request reference/order ID.
      return new Request("POST", "/api/v3/trading/execution/orders", body);
    }
    if (i.positionId() == null || i.positionId() <= 0)
      throw new IllegalArgumentException("MISSING_POSITION");
    if (i.action() == Action.STOP) {
      if (i.stop() == null || i.stop().signum() <= 0)
        throw new IllegalArgumentException("MISSING_STOP");
      body.put("stopLossRate", i.stop()).put("stopLossType", "fixed");
      return new Request("PATCH", "/api/v2/trading/positions/" + i.positionId(), body);
    }
    if (i.units() == null || i.units().signum() <= 0)
      throw new IllegalArgumentException("INVALID_CLOSE_UNITS");
    body.put("InstrumentId", i.instrumentId()).put("UnitsToDeduct", i.units());
    return new Request(
        "POST", "/api/v1/trading/execution/market-close-orders/positions/" + i.positionId(), body);
  }
}
