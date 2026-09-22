package com.loris.bravos.app;

import com.loris.bravos.broker.*;
import com.loris.bravos.state.*;
import com.loris.bravos.util.Json;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** What-if requests only: compares the saved order shape with market estimates. */
public final class OrderCompatibility {
  private OrderCompatibility() {}

  public static void main(String[] args) throws Exception {
    try {
      Path root = Path.of("").toAbsolutePath();
      var secrets = Secrets.load(root);
      var http = new HttpTransport(URI.create("https://public-api.etoro.com"), false);
      Map<Long, TradingState.Attempt> latest = new LinkedHashMap<>();
      try (var store = new StateStore(root.resolve("state/runtime"))) {
        for (var attempt : store.state().attempts.values()) {
          if (attempt.intent.agentAmount() != null)
            latest.put(attempt.intent.instrumentId(), attempt);
        }
      }
      var report = inspect(http, secrets, latest);
      Path target = root.resolve("state/capture/order-compatibility.json");
      Files.createDirectories(target.getParent());
      Json.MAPPER.writeValue(target.toFile(), report);
      for (var item : report.path("cases")) {
        if (item.has("skippedReason")) {
          System.out.println(
              item.path("instrumentId").asText()
                  + " skipped: "
                  + item.path("skippedReason").asText());
          continue;
        }
        System.out.println(
            item.path("instrumentId").asText()
                + " "
                + item.path("account").asText()
                + " "
                + item.path("type").asText()
                + " what-if HTTP "
                + item.path("httpStatus").asText());
      }
      System.out.println(
          "No orders submitted. What-if acceptance does not prove execution support.");
    } catch (Exception failure) {
      System.err.println("ORDER_COMPATIBILITY_FAILED; credentials and raw responses withheld");
      System.exit(1);
    }
  }

  static com.fasterxml.jackson.databind.JsonNode inspect(
      Transport http, Secrets secrets, Map<Long, TradingState.Attempt> latest) throws IOException {
    var report = Json.MAPPER.createObjectNode().put("observedAt", Instant.now().toString());
    var cases = report.putArray("cases");
    for (var attempt : latest.values()) {
      if (!"real".equals(attempt.intent.settlementType())) {
        cases
            .addObject()
            .put("instrumentId", attempt.intent.instrumentId())
            .put("skippedReason", "CAPPED_ORDER_REQUIRES_REAL_ASSET");
        continue;
      }
      for (boolean owner : List.of(false, true)) {
        for (String type : List.of("limitIOC", "mkt")) {
          var body = OrderPayloads.create(attempt.intent).body().deepCopy();
          if (owner) body.put("amount", attempt.intent.ownerAmount());
          body.put("orderType", type);
          if (type.equals("mkt")) body.remove("limitRate");
          var response =
              http.request(
                  "POST",
                  "/api/v2/trading/info/costs",
                  secrets.headers(owner, UUID.randomUUID().toString()),
                  body.toString());
          var item =
              cases
                  .addObject()
                  .put("instrumentId", attempt.intent.instrumentId())
                  .put("account", owner ? "owner" : "agent")
                  .put("type", type)
                  .put("httpStatus", response.status());
          item.set("request", body);
          item.set("response", Json.MAPPER.readTree(response.body()));
        }
      }
    }
    return report;
  }
}
