package com.loris.bravos.app;

import com.loris.bravos.broker.*;
import com.loris.bravos.state.StateStore;
import com.loris.bravos.util.Json;
import java.net.URI;
import java.nio.file.*;
import java.util.UUID;

/** Read-only order lookup. Raw broker responses remain in ignored local captures. */
public final class OrderAudit {
  private OrderAudit() {}

  public static void main(String[] args) throws Exception {
    Path root = Path.of("").toAbsolutePath();
    Secrets secrets = Secrets.load(root);
    var http = new HttpTransport(URI.create("https://public-api.etoro.com"), false);
    com.fasterxml.jackson.databind.JsonNode state;
    try (var store = new StateStore(root.resolve("state/runtime"))) {
      state = Json.MAPPER.valueToTree(store.state());
    }
    Path output = root.resolve("state/capture");
    Files.createDirectories(output);
    for (var attempt : state.path("attempts")) {
      if (!attempt.path("orderId").canConvertToLong()) continue;
      long id = attempt.path("orderId").longValue();
      String action = attempt.path("intent").path("action").asText();
      boolean close = java.util.Set.of("CLOSE", "REDUCE", "EARLY_EXIT").contains(action);
      var response =
          http.request(
              "GET",
              lookupPath(action, id),
              secrets.headers(false, UUID.randomUUID().toString()),
              null);
      if (response.status() != 200) {
        System.out.println("Order " + id + ": HTTP " + response.status());
        continue;
      }
      Files.writeString(output.resolve("order-" + id + ".json"), response.body());
      var order = Json.MAPPER.readTree(response.body());
      System.out.println(
          "Order "
              + id
              + ": "
              + (close
                  ? "Close status "
                      + order.path("statusID").asInt()
                      + "; broker code "
                      + order.path("errorCode").asInt()
                  : EtoroClient.orderFailure(order))
              + "; executions="
              + order.path(close ? "positions" : "positionExecutions").size());
    }
  }

  static String lookupPath(String action, long id) {
    return java.util.Set.of("CLOSE", "REDUCE", "EARLY_EXIT").contains(action)
        ? "/api/v1/trading/info/real/close-orders/" + id
        : "/api/v2/trading/info/orders:lookup?orderId=" + id;
  }
}
