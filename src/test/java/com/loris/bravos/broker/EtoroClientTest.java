package com.loris.bravos.broker;

import static com.loris.bravos.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.*;
import com.loris.bravos.app.*;
import com.loris.bravos.domain.*;
import com.loris.bravos.domain.Model.*;
import com.loris.bravos.state.TradingState.*;
import com.loris.bravos.util.Json;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class EtoroClientTest {
  final EtoroClient.RateSource unavailableStream =
      id -> {
        throw new IOException("QUOTE_STREAM_UNAVAILABLE");
      };

  EtoroClient offlineClient(
      Transport transport, Secrets keys, Configuration configuration, Clock time, boolean writes) {
    return new EtoroClient(transport, keys, configuration, time, writes, unavailableStream);
  }

  EtoroClient offlineClient(
      Transport transport,
      Secrets keys,
      Configuration configuration,
      Clock time,
      boolean writes,
      EtoroClient.RateSource rates) {
    return new EtoroClient(transport, keys, configuration, time, writes, rates);
  }

  final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  final Secrets secrets =
      new Secrets("app-test", "agent-test", "owner-test", "user-test", "password-test");

  ObjectNode node(String json) throws IOException {
    return (ObjectNode) Json.MAPPER.readTree(json);
  }

  Configuration config() {
    var c = new Configuration();
    c.copySizingEvidence = "test contract";
    c.copyStopsEvidence = "test contract";
    c.copyPriceCeilingEvidence = "test contract";
    c.copySizingModel = "REALIZED_EQUITY_RATIO";
    var a = new Configuration.Asset();
    a.instrumentId = 1890;
    a.brokerSymbol = "CF";
    a.unleveragedEvidence = "test issuer";
    a.priceScale = 2;
    a.unitScale = 4;
    c.assets.put("CF", a);
    return c;
  }

  class Api implements Transport {
    ObjectNode agent, owner, mirror, order, eligibility, costs, close;
    ObjectNode ownerEligibility;
    ObjectNode metadata;
    int status = 200, writes;
    boolean wrongOwnerScopes;
    boolean exchangeOpen = true, tradable = true;
    String rateDate = "2026-09-21T14:00:00Z", quoteType = "realtime";
    int activityDrift;
    String submittedBody;
    Set<String> freshReads = new HashSet<>();

    Api() throws IOException {
      agent =
          node(
              "{\"credit\":10000,\"accountCurrencyId\":1,\"positions\":[],\"mirrors\":[],\"orders\":[],\"stockOrders\":[]}");
      mirror =
          node(
              "{\"mirrorID\":22,\"parentCID\":11,\"availableAmount\":4610,\"positions\":[],\"isPaused\":false,\"pendingForClosure\":false,\"mirrorStatusID\":0,\"delayedOrderForOpen\":[],\"delayedOrderForClose\":[]}");
      for (var n : List.of(agent, mirror))
        for (String field :
            List.of(
                "ordersForOpen",
                "ordersForClose",
                "ordersForCloseMultiple",
                "entryOrders",
                "exitOrders")) n.putArray(field);
      owner = node("{\"accountCurrencyId\":1,\"mirrors\":[]}");
      ((ArrayNode) owner.get("mirrors")).add(mirror);
      order =
          node(
              "{\"orderId\":123,\"asset\":{\"instrumentId\":1890,\"currency\":\"USD\",\"settlementType\":\"real\",\"leverage\":1,\"side\":\"long\"},\"status\":{\"id\":3},\"positionExecutions\":[{\"positionId\":900,\"openingData\":{\"avgPrice\":100}}]}");
      eligibility =
          node(
              "{\"instrumentId\":1890,\"allowOpenPosition\":true,\"allowedOrderQuantityType\":\"all\",\"tradeUnitType\":\"units\",\"unitsQuantityType\":\"fractional\",\"leverageConfigs\":[{\"settlementType\":\"real\",\"direction\":\"long\",\"leverageValues\":[1],\"isPotential\":false,\"minPositionAmount\":10,\"allowStopLossTakeProfit\":true,\"allowEditStopLoss\":true}]}");
      costs =
          node(
              "{\"instrumentId\":1890,\"lastUpdated\":\"2026-09-21T14:00:00Z\",\"costs\":[{\"costType\":\"transactionFee\",\"currency\":\"USD\",\"value\":1}]}");
      close = node("{\"orderID\":123,\"positions\":[{\"positionID\":900,\"units\":1}]}");
    }

    void position(boolean ownerCopy, String units, String stop) throws IOException {
      var p =
          node(
              "{\"positionID\":900,\"parentPositionID\":0,\"instrumentID\":1890,\"units\":1,\"amount\":100,\"unrealizedPnL\":{\"pnL\":5},\"stopLossRate\":90,\"isNoStopLoss\":false,\"isTslEnabled\":false,\"isBuy\":true,\"leverage\":1}");
      p.put("units", d(units)).put("stopLossRate", d(stop)).put("openRate", 100);
      if (ownerCopy)
        p.put("positionID", 901).put("parentPositionID", 900).put("amount", d("46.10"));
      ((ArrayNode) (ownerCopy ? mirror : agent).get("positions")).add(p);
    }

    public Response request(String method, String path, Map<String, String> headers, String body)
        throws IOException {
      assertEquals("app-test", headers.get("x-api-key"));
      UUID.fromString(headers.get("x-request-id"));
      boolean ownerRead = headers.get("x-user-key").equals("owner-test");
      if ("no-cache".equals(headers.get("Cache-Control"))) freshReads.add(path);
      if (status != 200) return new Response(status, "sensitive body must not leak", Map.of());
      ObjectNode response;
      if (path.equals("/api/v1/me"))
        response =
            ownerRead
                ? node(
                    "{\"gcid\":2,\"realCid\":12,\"scopes\":[\"real:"
                        + (wrongOwnerScopes ? "write" : "read")
                        + "\"]}")
                : node("{\"gcid\":1,\"realCid\":11}");
      else if (path.equals("/api/v1/agent-portfolios")) {
        assertTrue(ownerRead);
        response = node("{\"agentPortfolios\":[{\"agentPortfolioGcid\":1,\"mirrorId\":22}]}");
      } else if (path.endsWith("/real/pnl") || path.endsWith("/info/portfolio")) {
        response = Json.MAPPER.createObjectNode();
        response.set("clientPortfolio", ownerRead ? owner : agent);
        if (!ownerRead && path.endsWith("/info/portfolio") && activityDrift != 0) {
          response = response.deepCopy();
          if (activityDrift == 1)
            ((ObjectNode) response.get("clientPortfolio")).putArray("positions");
          else
            ((ObjectNode) response.get("clientPortfolio").get("positions").get(0)).put("units", 99);
        }
      } else if (path.startsWith("/api/v2/market-data/instruments?"))
        response =
            metadata != null
                ? metadata
                : node(
                    "{\"results\":[{\"instrumentId\":1890,\"symbol\":\"CF\"}],\"pagination\":{\"hasNext\":false}}");
      else if (path.endsWith("/eligibility")) {
        assertEquals("POST", method);
        response = Json.MAPPER.createObjectNode();
        response
            .putArray("eligibilities")
            .add(ownerRead && ownerEligibility != null ? ownerEligibility : eligibility);
      } else if (path.endsWith("/costs")) {
        assertTrue(ownerRead);
        response = costs;
      } else if (path.contains("/rates?"))
        response =
            node(
                "{\"results\":[{\"instrumentId\":1890,\"ask\":100.12345678,\"date\":\""
                    + rateDate
                    + "\",\"quoteType\":\""
                    + quoteType
                    + "\"}]}");
      else if (path.contains("/search?"))
        response =
            node(
                "{\"items\":[{\"instrumentId\":1890,\"isExchangeOpen\":"
                    + exchangeOpen
                    + ",\"isCurrentlyTradable\":"
                    + tradable
                    + "}]}");
      else if (path.contains("orders:lookup?")) response = order;
      else if (path.contains("/real/close-orders/")) response = close;
      else {
        assertFalse(ownerRead);
        writes++;
        submittedBody = body;
        response =
            path.contains("/positions/") && method.equals("PATCH")
                ? node("{\"operationId\":42}")
                : path.contains("market-close")
                    ? node("{\"orderForClose\":{\"orderID\":123}}")
                    : node("{\"orderId\":123}");
      }
      return new Response(200, response.toString(), Map.of());
    }
  }

  EtoroClient client(Api api, boolean writes) {
    return offlineClient(api, secrets, config(), clock, writes);
  }

  Intent opening() {
    return new Policy()
        .opening(cycle(), instrument(), quote("100"), account(), NOW, d("0"))
        .intents()
        .getFirst();
  }

  @Test
  void acceptedCalendarModeUsesFreshQuoteAndTradabilityWithoutClosedExchangeFlag()
      throws Exception {
    var api = new Api();
    api.exchangeOpen = false;
    var config = config();
    var broker = offlineClient(api, secrets, config, clock, false);
    var instrument = broker.instrument("CF", d("230.50"));
    assertFalse(broker.quote(instrument).exchangeOpen());
    config.assets.get("CF").marketHours = "US_EQUITIES_2026";
    var quote = broker.quote(instrument);
    assertTrue(quote.exchangeOpen());
    assertEquals(
        Outcome.READY,
        new Policy().opening(cycle(), instrument, quote, broker.account(), NOW, d("0")).outcome());
    broker.prepare(new Attempt(opening(), NOW));
    api.tradable = false;
    assertFalse(broker.quote(instrument).exchangeOpen());
    assertThrows(IOException.class, () -> broker.prepare(new Attempt(opening(), NOW)));
    api.tradable = true;
    for (String instant : List.of("2026-09-21T20:00:00Z", "2026-12-25T15:00:00Z")) {
      var closed =
          offlineClient(
              api, secrets, config, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), false);
      assertFalse(closed.quote(instrument).exchangeOpen());
    }
    var expired =
        offlineClient(
            api,
            secrets,
            config,
            Clock.fixed(Instant.parse("2027-01-04T15:00:00Z"), ZoneOffset.UTC),
            false);
    assertEquals(
        "MARKET_CALENDAR_EXPIRED",
        assertThrows(IOException.class, () -> expired.quote(instrument)).getMessage());
    config.assets.clear();
    assertThrows(IOException.class, () -> broker.quote(instrument));
    assertEquals(0, api.writes);
  }

  @Test
  void preflightDiagnosticCapturesCostTimestampsWithoutCredentialsOrWrites() throws Exception {
    var api = new Api();
    var report = InstrumentAudit.preflight(api, secrets, config(), clock, unavailableStream);
    assertEquals("READ_PREFLIGHT_PASSED", report.path("preflight").get(0).path("result").asText());
    assertEquals(1, report.path("costResponses").size());
    assertEquals(
        "2026-09-21T14:00:00Z", report.path("costResponses").get(0).path("receivedAt").asText());
    assertFalse(report.toString().contains("owner-test"));
    assertFalse(report.toString().contains("agent-test"));
    api.costs.put("lastUpdated", NOW.minusSeconds(70).toString());
    report = InstrumentAudit.preflight(api, secrets, config(), clock, unavailableStream);
    assertEquals("READ_PREFLIGHT_PASSED", report.path("preflight").get(0).path("result").asText());
    assertEquals(0, api.writes);
  }

  @Test
  void deployedConfigurationCanPrepareAnEligibleOrderUsingOnlySyntheticAccounts() throws Exception {
    var api = new Api();
    var deployed = Configuration.load(java.nio.file.Path.of("config/trading.json"));
    var broker = offlineClient(api, secrets, deployed, clock, false);
    var account = broker.account();
    var instrument = broker.instrument("CF", d("230.50"));
    var decision =
        new Policy().opening(cycle(), instrument, broker.quote(instrument), account, NOW, d("0"));
    assertEquals(Outcome.READY, decision.outcome());
    var intent = decision.intents().getFirst();
    assertEquals(d("230.50"), intent.ownerAmount());
    assertEquals(d("500.00"), intent.agentAmount());
    assertEquals(d("90"), intent.stop());
    broker.prepare(new Attempt(intent, NOW));
    assertEquals(0, api.writes);
    assertTrue(api.freshReads.contains("/api/v1/me"));
    assertTrue(api.freshReads.contains("/api/v2/trading/info/eligibility"));
    assertTrue(api.freshReads.contains("/api/v2/trading/info/costs"));
  }

  @Test
  void nonAtomicPortfolioReadsAndUnrelatedLotEvidenceCannotConfirm() throws Exception {
    var api = new Api();
    var c = client(api, false);
    api.position(false, "1", "90");
    api.position(true, "0.461", "90");
    for (int drift : List.of(1, 2)) {
      api.activityDrift = drift;
      assertEquals(
          "ACCOUNT_CHANGED_DURING_READ", assertThrows(IOException.class, c::account).getMessage());
    }
    api.activityDrift = 0;
    var attempt = new Attempt(opening(), NOW);
    ((ObjectNode) api.agent.get("positions").get(0)).put("positionID", 899);
    assertEquals("COPY_OR_STOP_NOT_CONFIRMED", c.observe(attempt).reason());
    ((ObjectNode) api.agent.get("positions").get(0)).put("positionID", 900);
    ((ObjectNode) api.mirror.get("positions").get(0)).put("parentPositionID", 899);
    assertEquals("COPY_OR_STOP_NOT_CONFIRMED", c.observe(attempt).reason());
    ((ObjectNode) api.mirror.get("positions").get(0)).put("parentPositionID", 900);
    ((ObjectNode) api.order.get("positionExecutions").get(0).get("openingData"))
        .put("avgPrice", 102);
    ((ObjectNode) api.mirror.get("positions").get(0)).put("openRate", 102);
    assertEquals(Status.CONFIRMED, c.observe(attempt).status());
  }

  @Test
  void observedCostResponseUsesValueAndMissingValueCannotBecomeZero() throws Exception {
    var api = new Api();
    api.costs =
        node(
            "{\"instrumentId\":1890,\"symbol\":\"CF\",\"lastUpdated\":\"2026-09-21T14:00:00Z\",\"costs\":[{\"costType\":\"transactionFee\",\"currency\":\"USD\",\"value\":1.0},{\"costType\":\"marketSpread\",\"currency\":\"USD\",\"value\":1.17},{\"costType\":\"overnightFee\",\"currency\":\"USD\",\"value\":0.0}]}");
    assertEquals(
        0,
        d("2.17").compareTo(client(api, false).instrument("CF", d("184.40")).estimatedOwnerCost()));
    ((ObjectNode) api.costs.get("costs").get(0)).remove("value");
    assertThrows(IOException.class, () -> client(api, false).instrument("CF", d("184.40")));
    ObjectNode fee = (ObjectNode) api.costs.get("costs").get(0);
    fee.put("amount", 1);
    assertEquals(
        0,
        d("2.17").compareTo(client(api, false).instrument("CF", d("184.40")).estimatedOwnerCost()));
    fee.put("value", 1);
    assertEquals(
        0,
        d("2.17").compareTo(client(api, false).instrument("CF", d("184.40")).estimatedOwnerCost()));
    fee.put("value", 2);
    assertEquals(
        "CONFLICTING_COST_VALUE",
        assertThrows(IOException.class, () -> client(api, false).instrument("CF", d("184.40")))
            .getMessage());
    assertEquals(0, api.writes);
  }

  @Test
  void verifiedIdentityEquityAndUnknownCollectionsStayDistinct() throws Exception {
    var api = new Api();
    var client = client(api, false);
    Account a = client.account();
    assertEquals(d("4610"), a.ownerEquity());
    assertEquals(d("10000"), a.agentEquity());
    assertTrue(a.ordersComplete());
    assertFalse(a.pending());
    assertTrue(a.active());
    api.position(true, "1", "90");
    assertEquals(0, d("4661.10").compareTo(client.account().ownerEquity()));
    api.mirror.remove("ordersForCloseMultiple");
    assertFalse(client.account().ordersComplete());
    api.agent.withArray("ordersForOpen").addObject().put("orderID", 1);
    assertTrue(client.account().pending());
    api.mirror.put("isPaused", true);
    assertFalse(client.account().active());
    api.wrongOwnerScopes = true;
    assertThrows(IOException.class, client::account);
  }

  @Test
  void missingWrongTypeIdentityAndHttpErrorsFailClosed() throws Exception {
    var api = new Api();
    var client = client(api, false);
    api.mirror.put("parentCID", 999);
    assertThrows(IOException.class, client::account);
    api.mirror.put("parentCID", 11);
    api.mirror.put("availableAmount", "4610");
    assertThrows(IOException.class, client::account);
    api.mirror.put("availableAmount", 4610);
    api.agent.put("accountCurrencyId", 2);
    assertThrows(IOException.class, client::account);
    api.agent.put("accountCurrencyId", 1);
    api.status = 429;
    assertEquals("ETORO_HTTP_429", assertThrows(IOException.class, client::account).getMessage());
  }

  @Test
  void eligibilityCostsAndFreshQuoteAreTypedAndExact() throws Exception {
    var api = new Api();
    var client = client(api, false);
    var i = client.instrument("CF", d("230.50"));
    assertEquals(1890, i.id());
    assertEquals(d("1"), i.estimatedOwnerCost());
    assertEquals(d("10"), i.minimumAgentAmount());
    var q = client.quote(i);
    assertEquals(d("100.12345678"), q.ask());
    assertEquals(NOW, q.timestamp());
    assertTrue(q.exchangeOpen());
    assertEquals(
        "INSTRUMENT_NOT_LISTED",
        assertThrows(IOException.class, () -> client.instrument("UNKNOWN", d("1"))).getMessage());
    api.eligibility.put("allowOpenPosition", false);
    assertEquals(
        "BOTH_ACCOUNTS_OPENING_DISABLED",
        assertThrows(IOException.class, () -> client.instrument("CF", d("1"))).getMessage());
    api.eligibility.put("allowOpenPosition", true);
    api.eligibility.put("unitsQuantityType", "whole");
    assertEquals(0, client.instrument("CF", d("1")).unitScale());
    api.costs.put("lastUpdated", "2020-01-01T00:00:00Z");
    assertEquals(d("1"), client.instrument("CF", d("1")).estimatedOwnerCost());
    api.costs.put("lastUpdated", NOW.plusNanos(1).toString());
    assertEquals(
        "COST_TIMESTAMP_FUTURE",
        assertThrows(IOException.class, () -> client.instrument("CF", d("1"))).getMessage());
    api.costs.put("instrumentId", 1118);
    assertEquals(
        "COST_INSTRUMENT_MISMATCH",
        assertThrows(IOException.class, () -> client.instrument("CF", d("1"))).getMessage());
  }

  @Test
  void costsAreRequestedForEachAmountAndRequestFreshnessHasExactBoundaries() throws Exception {
    for (Duration elapsed :
        List.of(
            Duration.ofNanos(-1),
            Duration.ZERO,
            Duration.ofSeconds(60),
            Duration.ofSeconds(60).plusNanos(1))) {
      var api = new Api();
      api.costs.put("lastUpdated", NOW.minusSeconds(7200).toString());
      var moment = new java.util.concurrent.atomic.AtomicReference<>(NOW);
      Clock requestClock =
          new Clock() {
            public ZoneId getZone() {
              return ZoneOffset.UTC;
            }

            public Clock withZone(ZoneId zone) {
              return Clock.fixed(instant(), zone);
            }

            public Instant instant() {
              return moment.get();
            }
          };
      List<String> amounts = new ArrayList<>();
      Transport transport =
          (method, path, headers, body) -> {
            if (path.endsWith("/costs")) {
              amounts.add(Json.MAPPER.readTree(body).path("amount").asText());
              moment.set(NOW.plus(elapsed));
            }
            return api.request(method, path, headers, body);
          };
      var broker = offlineClient(transport, secrets, config(), requestClock, false);
      for (String amount : List.of("100", "200")) {
        moment.set(NOW);
        if (elapsed.isNegative() || elapsed.compareTo(Duration.ofSeconds(60)) > 0)
          assertEquals(
              "COST_REQUEST_STALE",
              assertThrows(IOException.class, () -> broker.instrument("CF", d(amount)))
                  .getMessage());
        else assertEquals(d("1"), broker.instrument("CF", d(amount)).estimatedOwnerCost());
      }
      assertEquals(List.of("100", "200"), amounts);
      assertEquals(0, api.writes);
    }
  }

  @Test
  void ownerEligibilityMustIndependentlyAllowTheExactProtectedUnleveragedTrade() throws Exception {
    for (String restriction :
        List.of(
            "opening",
            "settlement",
            "leverage",
            "stop",
            "edit",
            "potential",
            "direction",
            "quantity",
            "units")) {
      var api = new Api();
      api.ownerEligibility = api.eligibility.deepCopy();
      var leverage = (ObjectNode) api.ownerEligibility.path("leverageConfigs").get(0);
      switch (restriction) {
        case "opening" -> api.ownerEligibility.put("allowOpenPosition", false);
        case "settlement" -> leverage.put("settlementType", "cfd");
        case "leverage" -> leverage.putArray("leverageValues").add(2);
        case "stop" -> leverage.put("allowStopLossTakeProfit", false);
        case "edit" -> leverage.put("allowEditStopLoss", false);
        case "potential" -> leverage.put("isPotential", true);
        case "direction" -> leverage.put("direction", "short");
        case "quantity" -> api.ownerEligibility.put("allowedOrderQuantityType", "unitsOnly");
        case "units" -> api.ownerEligibility.put("tradeUnitType", "contracts");
      }
      assertEquals(
          restriction.equals("opening") ? "OWNER_OPENING_DISABLED" : "OWNER_INSTRUMENT_INELIGIBLE",
          assertThrows(
                  IOException.class,
                  () -> client(api, false).instrument("CF", d("184.40")),
                  restriction)
              .getMessage());
      assertEquals(0, api.writes);
    }
  }

  @Test
  void submitOnlyThroughAgentAndReadOnlyClientCannotWrite() throws Exception {
    var api = new Api();
    var i = opening();
    assertThrows(
        IOException.class, () -> client(api, false).submit(i, UUID.randomUUID().toString()));
    assertEquals(0, api.writes);
    var client = client(api, true);
    assertEquals(123L, client.submit(i, UUID.randomUUID().toString()).orderId());
    var body = Json.MAPPER.readTree(api.submittedBody);
    assertEquals("limitIOC", body.get("orderType").asText());
    assertEquals(1, body.get("leverage").asInt());
    assertEquals(d("102"), body.get("limitRate").decimalValue());
    assertEquals("fixed", body.get("stopLossType").asText());
    var stop =
        new Intent(
            "stop", "opening", "s", Action.STOP, 1890, 900L, null, null, null, null, d("95"), null);
    assertNull(client.submit(stop, UUID.randomUUID().toString()).orderId());
    var close =
        new Intent(
            "close",
            "opening",
            "c",
            Action.CLOSE,
            1890,
            900L,
            null,
            null,
            d("1"),
            null,
            null,
            null);
    assertEquals(123L, client.submit(close, UUID.randomUUID().toString()).orderId());
    assertEquals(3, api.writes);
  }

  @Test
  void acceptanceIsNotFillAndCopiesMustCarryExactStop() throws Exception {
    var api = new Api();
    var client = client(api, false);
    var a = new Attempt(opening(), NOW);
    assertEquals(Status.PARTIAL, client.observe(a).status());
    api.position(false, "1", "90");
    api.position(true, "0.461", "89");
    assertEquals(Status.PARTIAL, client.observe(a).status());
    ((ObjectNode) api.mirror.get("positions").get(0)).put("stopLossRate", 90);
    assertEquals(Status.CONFIRMED, client.observe(a).status());
    ((ObjectNode) api.order.get("status")).put("id", 5);
    assertEquals(Status.PARTIAL, client.observe(a).status());
    api.order.putArray("positionExecutions");
    ((ObjectNode) api.order.get("status")).put("id", 7);
    assertEquals(Status.REJECTED, client.observe(a).status());
    ((ObjectNode) api.order.get("status")).put("id", 1);
    assertEquals(Status.SUBMITTED, client.observe(a).status());
  }

  @Test
  void emptyTerminalOrderPreservesSpecificBrokerReasonAndNeverHidesExecutions() throws Exception {
    var api = new Api();
    var client = client(api, false);
    var attempt = new Attempt(opening(), NOW);
    var status = (ObjectNode) api.order.get("status");
    status.put("id", 4).put("errorCode", 1065).put("errorMessage", "Technical\nfailure\u001b");
    assertNotEquals(Status.REJECTED, client.observe(attempt).status());
    api.order.putArray("positionExecutions");
    for (int id : List.of(4, 7, 8)) {
      status.put("id", id);
      var result = client.observe(attempt);
      assertEquals(Status.REJECTED, result.status());
      assertEquals(
          "CONFIRMED_NO_FILL Status " + id + "; broker code 1065: Technical failure",
          result.reason());
      assertTrue(result.positionIds().isEmpty());
    }
    status.remove("errorMessage");
    status.remove("errorCode");
    assertEquals("Status 8; broker supplied no explanation", EtoroClient.orderFailure(api.order));
    status.put("name", "Rejected");
    assertEquals("Rejected; broker supplied no explanation", EtoroClient.orderFailure(api.order));
    status.put("name", "Rejected\u001b");
    assertEquals("Status 8; broker supplied no explanation", EtoroClient.orderFailure(api.order));
    status.put("errorMessage", "x".repeat(501));
    assertEquals("Status 8: " + "x".repeat(500), EtoroClient.orderFailure(api.order));
    status.put("id", 1);
    assertEquals(Status.SUBMITTED, client.observe(attempt).status());
  }

  @Test
  void terminalPartialFillKeepsProtectedUnitsAndReportsActualDollarShortfall() throws Exception {
    var api = new Api();
    var c = client(api, false);
    api.position(false, "1", "90");
    api.position(true, "0.461", "90");
    var attempt = new Attempt(opening(), NOW);
    for (int status : List.of(9, 10)) {
      ((ObjectNode) api.order.get("status")).put("id", status);
      var observed = c.observe(attempt);
      assertEquals(Status.CONFIRMED, observed.status());
      assertEquals("PARTIAL_FILL_KEPT_AND_PROTECTED", observed.reason());
      assertEquals(0, d("100").compareTo(observed.agentFilled()));
      assertEquals(0, d("46.10").compareTo(observed.ownerFilled()));
      assertEquals(
          0,
          attempt.intent.ownerAmount().subtract(d("46.10")).compareTo(observed.ownerShortfall()));
    }
    ((ObjectNode) api.agent.get("positions").get(0)).put("amount", attempt.intent.agentAmount());
    ((ObjectNode) api.mirror.get("positions").get(0)).put("amount", attempt.intent.ownerAmount());
    assertEquals("FILLED_AND_PROTECTED", c.observe(attempt).reason());
    assertEquals(0, c.observe(attempt).ownerShortfall().signum());
    ((ObjectNode) api.agent.get("positions").get(0))
        .put("amount", attempt.intent.agentAmount().multiply(d("2")));
    ((ObjectNode) api.mirror.get("positions").get(0))
        .put("amount", attempt.intent.ownerAmount().multiply(d("2")));
    assertEquals("FILLED_AMOUNT_EXCEEDS_REQUEST", c.observe(attempt).reason());
    assertEquals(0, api.writes);
  }

  @Test
  void partialAndFullCloseUseExecutionPlusBothAccountReadbacks() throws Exception {
    var api = new Api();
    var client = client(api, false);
    api.position(false, "5", "90");
    api.position(true, "2.305", "90");
    var i =
        new Intent(
            "trim",
            "opening",
            "trim",
            Action.REDUCE,
            1890,
            900L,
            null,
            null,
            d("1"),
            null,
            null,
            null);
    var a = new Attempt(i, NOW);
    client.prepare(a);
    assertEquals(d("5"), a.beforeAgentUnits);
    assertEquals(d("2.305"), a.beforeOwnerUnits.get(901L));
    assertEquals(Status.UNKNOWN, client.observe(a).status());
    a.orderId = 123L;
    assertEquals(Status.PARTIAL, client.observe(a).status());
    ((ObjectNode) api.agent.get("positions").get(0)).put("units", 4);
    ((ObjectNode) api.mirror.get("positions").get(0)).put("units", d("1.844"));
    assertEquals(Status.CONFIRMED, client.observe(a).status());
    api.agent.putArray("positions");
    api.mirror.putArray("positions");
    assertEquals(Status.CONFIRMED, client.observe(a).status());
    assertThrows(IOException.class, () -> client.prepare(a));
  }

  @Test
  void stopReadbackRequiresFixedEnabledProtectionOnBothSides() throws Exception {
    var api = new Api();
    var client = client(api, false);
    var a =
        new Attempt(
            new Intent(
                "s",
                "opening",
                "s",
                Action.STOP,
                1890,
                900L,
                null,
                null,
                null,
                null,
                d("90"),
                null),
            NOW);
    assertEquals(Status.SUBMITTED, client.observe(a).status());
    api.position(false, "1", "90");
    api.position(true, "0.461", "90");
    assertEquals(Status.CONFIRMED, client.observe(a).status());
    ((ObjectNode) api.mirror.get("positions").get(0)).put("isTslEnabled", true);
    assertEquals(Status.SUBMITTED, client.observe(a).status());
  }

  @Test
  void limitDeviationIsRecheckedAtSubmissionBoundary() throws Exception {
    var api = new Api();
    var c = client(api, false);
    assertEquals(Duration.ofSeconds(30), c.readbackInterval());
    var original = opening();
    var boundary = d("100.12345678").multiply(d("1.10"));
    for (var limit : List.of(boundary, boundary.add(d("0.00000001")))) {
      var i =
          new Intent(
              original.key(),
              original.cycleKey(),
              original.eventKey(),
              Action.OPEN,
              original.instrumentId(),
              null,
              original.ownerAmount(),
              original.agentAmount(),
              null,
              limit,
              original.stop(),
              original.settlementType());
      if (limit.compareTo(boundary) == 0) c.prepare(new Attempt(i, NOW));
      else
        assertEquals(
            "QUOTE_CHANGED_BEFORE_SUBMISSION",
            assertThrows(IOException.class, () -> c.prepare(new Attempt(i, NOW))).getMessage());
    }
    assertEquals(0, api.writes);
  }

  @Test
  void freshPreflightRejectsChangedQuotesCapitalAndCapabilities() throws Exception {
    var api = new Api();
    var c = client(api, false);
    var attempt = new Attempt(opening(), NOW);
    c.prepare(attempt);
    assertEquals(0, api.writes);
    assertEquals(4, c.unitScale("CF"));
    assertThrows(IOException.class, () -> c.unitScale("UNKNOWN"));
    api.mirror.put("availableAmount", 5000);
    assertThrows(IOException.class, () -> c.prepare(attempt));
    api.mirror.put("availableAmount", 4610);
    api.mirror.put("isPaused", true);
    assertThrows(IOException.class, () -> c.prepare(attempt));
    api.mirror.put("isPaused", false);
    var config = config();
    config.copyPriceCeilingEvidence = "";
    assertThrows(
        IOException.class,
        () -> offlineClient(api, secrets, config, clock, false).prepare(attempt));
    var changed =
        new Intent(
            "i",
            "c",
            "e",
            Action.OPEN,
            1890,
            null,
            d("230.50"),
            d("500"),
            null,
            d("99"),
            d("90"),
            "real");
    assertThrows(IOException.class, () -> c.prepare(new Attempt(changed, NOW)));
    api.eligibility.put("allowOpenPosition", false);
    assertThrows(IOException.class, () -> c.prepare(attempt));
  }

  @Test
  void copiedOverpaymentWrongAssetAndWrongAmountNeverConfirm() throws Exception {
    var api = new Api();
    var c = client(api, false);
    api.position(false, "1", "90");
    api.position(true, "0.461", "90");
    var a = new Attempt(opening(), NOW);
    ObjectNode copy = (ObjectNode) api.mirror.get("positions").get(0);
    copy.put("openRate", 103);
    assertEquals("COPIED_PRICE_CEILING_BREACHED", c.observe(a).reason());
    copy.put("openRate", 100);
    copy.put("instrumentID", 9999);
    assertEquals("COPIED_ASSET_MISMATCH", c.observe(a).reason());
    copy.put("instrumentID", 1890);
    copy.put("amount", 50);
    assertEquals("COPIED_AMOUNT_MISMATCH", c.observe(a).reason());
    copy.put("amount", d("46.10"));
    ((ObjectNode) api.order.get("positionExecutions").get(0).get("openingData"))
        .put("avgPrice", 103);
    assertEquals("PRICE_CEILING_BREACHED", c.observe(a).reason());
    ((ObjectNode) api.order.get("asset")).put("leverage", 2);
    assertThrows(IOException.class, () -> c.observe(a));
  }

  @Test
  void acceptedCopyPriceRiskDoesNotBypassOtherChecksOrHideOverpayment() throws Exception {
    var api = new Api();
    var config = config();
    config.copyPriceCeilingEvidence = "";
    var broker = offlineClient(api, secrets, config, clock, false);
    assertFalse(broker.account().copyEntryPermitted());
    config.copyPricePolicy = "AGENT_LIMIT_WITH_COPY_CHECK";
    assertTrue(broker.account().copyEntryPermitted());
    broker.prepare(new Attempt(opening(), NOW));
    config.copySizingEvidence = "";
    assertFalse(broker.account().copyEntryPermitted());
    config.copySizingEvidence = "fixture verified ratio";
    config.copyStopsEvidence = "";
    assertThrows(IOException.class, () -> broker.prepare(new Attempt(opening(), NOW)));
    config.copyStopsEvidence = "fixture stop contract";
    api.position(false, "1", "90");
    api.position(true, "0.461", "90");
    ((ObjectNode) api.mirror.path("positions").get(0)).put("openRate", 103);
    var result = broker.observe(new Attempt(opening(), NOW));
    assertEquals(Status.UNKNOWN, result.status());
    assertEquals("COPIED_PRICE_CEILING_BREACHED", result.reason());
    assertEquals(0, api.writes);
    assertEquals("", config.copyPriceCeilingEvidence);
  }

  @Test
  void closeReadbackRejectsUnrelatedCopiesRoundingAndMissingPrecision() throws Exception {
    var api = new Api();
    var c = client(api, false);
    api.position(false, "5", "90");
    api.position(true, "2.305", "90");
    var a =
        new Attempt(
            new Intent(
                "trim",
                "opening",
                "trim",
                Action.REDUCE,
                1890,
                900L,
                null,
                null,
                d("1"),
                null,
                null,
                null),
            NOW);
    c.prepare(a);
    a.orderId = 123L;
    ((ObjectNode) api.agent.get("positions").get(0)).put("units", 4);
    ((ObjectNode) api.mirror.get("positions").get(0)).put("units", d("1.8438"));
    assertEquals(Status.PARTIAL, c.observe(a).status());
    ((ObjectNode) api.mirror.get("positions").get(0)).put("units", d("1.8439"));
    assertEquals(Status.CONFIRMED, c.observe(a).status());
    ((ObjectNode) api.mirror.get("positions").get(0)).put("parentPositionID", 999);
    assertEquals(Status.PARTIAL, c.observe(a).status());
    var noConfig = new Configuration();
    assertEquals(
        Status.PARTIAL, offlineClient(api, secrets, noConfig, clock, false).observe(a).status());
    ((ObjectNode) api.close.get("positions").get(0)).put("units", d("0.5"));
    assertEquals("CLOSE_UNITS_NOT_CONFIRMED", c.observe(a).reason());
  }

  @Test
  void longRefreshCannotSubmitUsingAnExpiredAccountSnapshot() throws Exception {
    var time = new java.util.concurrent.atomic.AtomicReference<>(NOW);
    Clock advancing =
        new Clock() {
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId zone) {
            return this;
          }

          public Instant instant() {
            return time.get();
          }
        };
    var api = new Api();
    api.rateDate = NOW.minusSeconds(61).toString();
    var broker =
        offlineClient(
            api,
            secrets,
            config(),
            advancing,
            false,
            id -> {
              time.set(NOW.plusSeconds(60).plusNanos(1));
              return new StreamingRates.Rate(d("100"), time.get());
            });
    assertEquals(
        "ACCOUNT_CHANGED_BEFORE_SUBMISSION",
        assertThrows(IOException.class, () -> broker.prepare(new Attempt(opening(), NOW)))
            .getMessage());
    assertEquals(0, api.writes);
  }

  @Test
  void staleQuotesRefreshDuringTheSameRunAndStillEnforcePriceAndMarketChecks() throws Exception {
    var api = new Api();
    api.rateDate = NOW.minusSeconds(61).toString();
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var broker =
        offlineClient(
            api,
            secrets,
            config(),
            clock,
            false,
            id -> {
              assertEquals(1890, id);
              calls.incrementAndGet();
              return new StreamingRates.Rate(d("103"), NOW);
            });
    var quote = broker.quote(instrument());
    assertEquals(d("103"), quote.ask());
    assertEquals(NOW, quote.timestamp());
    assertTrue(quote.exchangeOpen());
    assertEquals(1, calls.get());
    assertEquals(
        Outcome.WATCH_PRICE,
        new Policy().opening(cycle(), instrument(), quote, account(), NOW, d("0")).outcome());
    assertThrows(IOException.class, () -> broker.prepare(new Attempt(opening(), NOW)));
    assertEquals(2, calls.get());
    var suspended =
        offlineClient(
            api,
            secrets,
            config(),
            clock,
            false,
            id -> {
              api.tradable = false;
              return new StreamingRates.Rate(d("100"), NOW);
            });
    assertFalse(suspended.quote(instrument()).exchangeOpen());
    assertEquals(0, api.writes);
  }

  @Test
  void freshFutureClosedDelayedQuotesDoNotStartAStream() throws Exception {
    for (String scenario :
        List.of("fresh", "boundary", "future", "closed", "suspended", "delayed")) {
      var api = new Api();
      api.rateDate = NOW.minusSeconds(61).toString();
      switch (scenario) {
        case "fresh" -> api.rateDate = NOW.toString();
        case "boundary" -> api.rateDate = NOW.minusSeconds(60).toString();
        case "future" -> api.rateDate = NOW.plusNanos(1).toString();
        case "closed" -> api.exchangeOpen = false;
        case "suspended" -> api.tradable = false;
        case "delayed" -> api.quoteType = "delayed";
      }
      var broker =
          offlineClient(
              api,
              secrets,
              config(),
              clock,
              false,
              id -> {
                fail("Unexpected refresh for " + scenario);
                return null;
              });
      broker.quote(instrument());
    }
  }

  @Test
  void streamFailureRetriesSnapshotWithoutRelabelingOldOrDelayedPrices() throws Exception {
    for (String scenario :
        List.of("fresh", "boundary", "old", "future", "delayed", "interrupted")) {
      var api = new Api();
      api.rateDate = NOW.minusSeconds(90).toString();
      var broker =
          offlineClient(
              api,
              secrets,
              config(),
              clock,
              false,
              id -> {
                api.rateDate =
                    switch (scenario) {
                      case "boundary" -> NOW.minusSeconds(60).toString();
                      case "old" -> NOW.minusSeconds(60).minusNanos(1).toString();
                      case "future" -> NOW.plusNanos(1).toString();
                      default -> NOW.toString();
                    };
                if (scenario.equals("delayed")) api.quoteType = "delayed";
                if (scenario.equals("interrupted")) Thread.currentThread().interrupt();
                throw new IOException("QUOTE_REFRESH_TIMEOUT");
              });
      try {
        if (List.of("fresh", "boundary").contains(scenario)) {
          var quote = broker.quote(instrument());
          assertTrue(quote.exchangeOpen());
          assertEquals(Instant.parse(api.rateDate), quote.timestamp());
          assertEquals(d("100.12345678"), quote.ask());
        } else
          assertEquals(
              scenario.equals("interrupted") ? "QUOTE_REFRESH_TIMEOUT" : "QUOTE_REFRESH_EXHAUSTED",
              assertThrows(IOException.class, () -> broker.quote(instrument())).getMessage());
      } finally {
        Thread.interrupted();
      }
      assertEquals(0, api.writes);
    }
  }

  @Test
  void deployedAdiAliasSurvivesQuoteAndPreSubmissionRechecksWithoutTrading() throws Exception {
    var api = new Api();
    Transport transport =
        (method, path, headers, body) -> {
          if (path.startsWith("/api/v2/market-data/instruments?"))
            assertEquals("/api/v2/market-data/instruments?symbols=ADI.US", path);
          var response = api.request(method, path, headers, body);
          return new Transport.Response(
              response.status(),
              response.body().replace("1890", "4264").replace("\"CF\"", "\"ADI.US\""),
              response.headers());
        };
    var deployed = Configuration.load(java.nio.file.Path.of("config/trading.json"));
    var broker = offlineClient(transport, secrets, deployed, clock, false);
    var asset = broker.instrument("ADI", d("230.50"));
    assertEquals(4264, asset.id());
    assertEquals("ADI", asset.symbol());
    assertEquals("real", asset.settlementType());
    assertEquals(5, broker.unitScale("ADI"));
    assertTrue(broker.quote(asset).exchangeOpen());
    var intent =
        new Intent(
            "cycle",
            "event",
            "opening",
            Action.OPEN,
            4264,
            null,
            d("230.50"),
            d("500"),
            null,
            d("102"),
            d("90"),
            "real");
    broker.prepare(new Attempt(intent, NOW));
    assertEquals(0, api.writes);
  }

  @Test
  void lookupOnlyIdentityChecksBothAccountsButNeverAuthorizesAnEntry() throws Exception {
    var api = new Api();
    var config = config();
    config.assets.clear();
    var identity = new Configuration.Identity();
    identity.instrumentId = 1890;
    identity.brokerSymbol = "CF";
    config.lookupOnlyAssets.put("SOURCE", identity);
    var broker = offlineClient(api, secrets, config, clock, false);
    api.ownerEligibility = api.eligibility.deepCopy();
    for (boolean agent : List.of(false, true)) {
      for (boolean owner : List.of(false, true)) {
        api.eligibility.put("allowOpenPosition", agent);
        api.ownerEligibility.put("allowOpenPosition", owner);
        String expected =
            !agent && !owner
                ? "BOTH_ACCOUNTS_OPENING_DISABLED"
                : !agent
                    ? "AGENT_OPENING_DISABLED"
                    : !owner ? "OWNER_OPENING_DISABLED" : "INSTRUMENT_PROFILE_REQUIRED";
        assertEquals(
            expected,
            assertThrows(IOException.class, () -> broker.instrument("SOURCE", d("100")))
                .getMessage());
      }
    }
    api.metadata =
        node(
            "{\"results\":[{\"instrumentId\":1890,\"symbol\":\"WRONG\"}],\"pagination\":{\"hasNext\":false}}");
    assertEquals(
        "INSTRUMENT_IDENTITY_CHANGED",
        assertThrows(IOException.class, () -> broker.instrument("SOURCE", d("100"))).getMessage());
    ((ObjectNode) api.metadata.get("results").get(0)).put("instrumentId", 2000);
    assertThrows(IOException.class, () -> broker.instrument("SOURCE", d("100")));
    assertEquals(0, api.writes);
  }

  @Test
  void unknownTickerDiscoveryNeverPromotesCandidatesAndRequiresCompleteResults() throws Exception {
    var api = new Api();
    var c = client(api, false);
    for (String found : List.of("NEW", "NEW.US", "OTHER")) {
      api.metadata =
          node(
              "{\"results\":[{\"instrumentId\":17,\"symbol\":\""
                  + found
                  + "\"}],\"pagination\":{\"hasNext\":false}}");
      assertEquals(
          found.equals("OTHER") ? "INSTRUMENT_NOT_LISTED" : "INSTRUMENT_PROFILE_REQUIRED",
          assertThrows(IOException.class, () -> c.instrument("NEW", d("1"))).getMessage());
    }
    api.metadata.putArray("results");
    assertEquals(
        "INSTRUMENT_NOT_LISTED",
        assertThrows(IOException.class, () -> c.instrument("NEW", d("1"))).getMessage());
    ((ObjectNode) api.metadata.get("pagination")).put("hasNext", true);
    assertEquals(
        "INSTRUMENT_LOOKUP_INCOMPLETE",
        assertThrows(IOException.class, () -> c.instrument("NEW", d("1"))).getMessage());
    api.metadata.remove("pagination");
    assertThrows(IOException.class, () -> c.instrument("NEW", d("1")));
    assertEquals(
        "INVALID_INSTRUMENT_SYMBOL",
        assertThrows(IOException.class, () -> c.instrument("NEW&query=bad", d("1"))).getMessage());
    assertEquals(0, api.writes);
  }

  @Test
  void onlyUnconfiguredLookup404MeansNoListing() throws Exception {
    var api = new Api();
    var c = client(api, false);
    api.status = 404;
    assertEquals(
        "INSTRUMENT_NOT_LISTED",
        assertThrows(IOException.class, () -> c.instrument("MAGS", d("1"))).getMessage());
    assertEquals(
        "ETORO_HTTP_404",
        assertThrows(IOException.class, () -> c.instrument("CF", d("1"))).getMessage());
    var config = config();
    var id = new Configuration.Identity();
    id.instrumentId = 12152;
    id.brokerSymbol = "ETHA.US";
    config.lookupOnlyAssets.put("ETHA", id);
    var known = offlineClient(api, secrets, config, clock, false);
    assertEquals(
        "ETORO_HTTP_404",
        assertThrows(IOException.class, () -> known.instrument("ETHA", d("1"))).getMessage());
    api.status = 500;
    assertEquals(
        "ETORO_HTTP_500",
        assertThrows(IOException.class, () -> c.instrument("MAGS", d("1"))).getMessage());
    assertEquals(0, api.writes);
  }

  @Test
  void unsupportedEligibilityAndCostCurrencyCannotBecomeAnOrder() throws Exception {
    var api = new Api();
    var c = client(api, false);
    ObjectNode leverage = (ObjectNode) api.eligibility.get("leverageConfigs").get(0);
    leverage.put("isPotential", true);
    assertThrows(IOException.class, () -> c.instrument("CF", d("1")));
    leverage.put("isPotential", false);
    leverage.put("allowStopLossTakeProfit", false);
    assertThrows(IOException.class, () -> c.instrument("CF", d("1")));
    leverage.put("allowStopLossTakeProfit", true);
    leverage.put("allowEditStopLoss", false);
    assertThrows(IOException.class, () -> c.instrument("CF", d("1")));
    leverage.put("allowEditStopLoss", true);
    leverage.putArray("leverageValues").add(2);
    assertThrows(IOException.class, () -> c.instrument("CF", d("1")));
    leverage.putArray("leverageValues").add(1);
    api.eligibility.put("allowedOrderQuantityType", "unitsOnly");
    assertThrows(IOException.class, () -> c.instrument("CF", d("1")));
    api.eligibility.put("allowedOrderQuantityType", "all");
    api.eligibility.put("tradeUnitType", "contracts");
    assertThrows(IOException.class, () -> c.instrument("CF", d("1")));
    api.eligibility.put("tradeUnitType", "units");
    ((ObjectNode) api.costs.get("costs").get(0)).put("currency", "EUR");
    assertThrows(IOException.class, () -> c.instrument("CF", d("1")));
  }
}
