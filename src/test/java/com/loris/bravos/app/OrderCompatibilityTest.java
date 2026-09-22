package com.loris.bravos.app;

import static com.loris.bravos.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.loris.bravos.broker.Transport;
import com.loris.bravos.state.TradingState.Attempt;
import com.loris.bravos.util.Json;
import java.util.*;
import org.junit.jupiter.api.Test;

class OrderCompatibilityTest {
  @Test
  void knownUnsupportedCfdDoesNotBreakOrSendWhatIfRequests() throws Exception {
    var attempt =
        new Attempt(
            new com.loris.bravos.domain.Model.Intent(
                "cfd",
                "opening",
                "opening",
                com.loris.bravos.domain.Model.Action.OPEN,
                3421,
                null,
                d("230.50"),
                d("500"),
                null,
                d("98.36"),
                d("89"),
                "cfd"),
            NOW);
    Transport transport =
        (method, path, headers, body) -> {
          fail("Unsupported CFD must not reach the broker");
          return null;
        };
    var report =
        OrderCompatibility.inspect(
            transport,
            new Secrets("test-app", "test-agent", "test-owner", "test-user", "test-password"),
            Map.of(3421L, attempt));
    assertEquals(1, report.path("cases").size());
    assertEquals(
        "CAPPED_ORDER_REQUIRES_REAL_ASSET",
        report.path("cases").get(0).path("skippedReason").asText());
  }

  @Test
  void comparesExactOrderAndMarketEstimateWithoutAnyExecutionEndpoint() throws Exception {
    var attempt =
        new Attempt(
            new com.loris.bravos.domain.Model.Intent(
                "test",
                "opening",
                "opening",
                com.loris.bravos.domain.Model.Action.OPEN,
                1118,
                null,
                d("368.80"),
                d("800"),
                null,
                d("516.63"),
                d("480"),
                "real"),
            NOW);
    var requests = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
    var users = new ArrayList<String>();
    Transport transport =
        (method, path, headers, body) -> {
          assertEquals("POST", method);
          assertEquals("/api/v2/trading/info/costs", path);
          requests.add(Json.MAPPER.readTree(body));
          users.add(headers.get("x-user-key"));
          return new Transport.Response(200, "{\"costs\":[]}", Map.of());
        };
    var report =
        OrderCompatibility.inspect(
            transport,
            new Secrets("test-app", "test-agent", "test-owner", "test-user", "test-password"),
            Map.of(attempt.intent.instrumentId(), attempt));
    assertEquals(4, requests.size());
    assertEquals(List.of("test-agent", "test-agent", "test-owner", "test-owner"), users);
    for (int n = 0; n < 4; n++) {
      var request = requests.get(n);
      assertEquals(n % 2 == 0 ? "limitIOC" : "mkt", request.path("orderType").asText());
      assertEquals(n % 2 == 0, request.has("limitRate"));
      assertEquals(
          0,
          request
              .path("amount")
              .decimalValue()
              .compareTo(n < 2 ? attempt.intent.agentAmount() : attempt.intent.ownerAmount()));
      assertEquals(0, request.path("stopLossRate").decimalValue().compareTo(attempt.intent.stop()));
      assertEquals(1, request.path("leverage").asInt());
    }
    assertEquals(4, report.path("cases").size());
    assertEquals(200, report.path("cases").get(0).path("httpStatus").asInt());
  }
}
