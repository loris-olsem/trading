package com.loris.bravos.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.loris.bravos.app.*;
import com.loris.bravos.domain.Model.*;
import com.loris.bravos.state.TradingState.*;
import com.loris.bravos.util.Json;
import java.io.IOException;
import java.math.*;
import java.time.*;
import java.util.*;

/** All broker writes use the agent credential; the owner's key is exclusively read-only. */
public final class EtoroClient implements Broker, Workflow.Market {
  private final Transport transport;
  private final Secrets secrets;
  private final Configuration config;
  private final Clock clock;
  private final boolean writesAllowed;

  public EtoroClient(
      Transport transport,
      Secrets secrets,
      Configuration config,
      Clock clock,
      boolean writesAllowed) {
    this.transport = transport;
    this.secrets = secrets;
    this.config = config;
    this.clock = clock;
    this.writesAllowed = writesAllowed;
  }

  private JsonNode call(boolean owner, String method, String path, String body, String reference)
      throws IOException {
    var response = transport.request(method, path, secrets.headers(owner, reference), body);
    if (response.status() < 200 || response.status() > 299)
      throw new IOException("ETORO_HTTP_" + response.status());
    try {
      return Json.MAPPER.readTree(response.body());
    } catch (Exception e) {
      throw new IOException("ETORO_INVALID_JSON");
    }
  }

  private JsonNode get(boolean owner, String path) throws IOException {
    return call(owner, "GET", path, null, UUID.randomUUID().toString());
  }

  private JsonNode query(boolean owner, String path, JsonNode body) throws IOException {
    return call(owner, "POST", path, body.toString(), UUID.randomUUID().toString());
  }

  public Account account() throws IOException {
    Instant observed = clock.instant();
    JsonNode agent = get(false, "/api/v1/me"), owner = get(true, "/api/v1/me");
    long agentGcid = integer(agent, "gcid"), agentCid = integer(agent, "realCid");
    if (integer(owner, "gcid") == agentGcid) throw new IOException("OWNER_IS_AGENT");
    var scopes = array(owner, "scopes");
    if (scopes.isEmpty()) throw new IOException("OWNER_SCOPES_UNKNOWN");
    for (var scope : scopes)
      if (!scope.isTextual() || !scope.asText().endsWith(":read"))
        throw new IOException("OWNER_KEY_NOT_READONLY");
    JsonNode link =
        unique(
            array(get(true, "/api/v1/agent-portfolios"), "agentPortfolios"),
            "agentPortfolioGcid",
            agentGcid);
    JsonNode ownerPortfolio =
        required(get(true, "/api/v1/trading/info/real/pnl"), "clientPortfolio");
    JsonNode agentPortfolio =
        required(get(false, "/api/v1/trading/info/real/pnl"), "clientPortfolio");
    if (integer(ownerPortfolio, "accountCurrencyId") != 1
        || integer(agentPortfolio, "accountCurrencyId") != 1)
      throw new IOException("ACCOUNT_CURRENCY_UNSUPPORTED");
    JsonNode mirror =
        unique(array(ownerPortfolio, "mirrors"), "mirrorID", integer(link, "mirrorId"));
    if (integer(mirror, "parentCID") != agentCid) throw new IOException("COPY_IDENTITY_MISMATCH");
    List<Position> ap = positions(array(agentPortfolio, "positions")),
        op = positions(array(mirror, "positions"));
    BigDecimal oc = decimal(mirror, "availableAmount"), ac = decimal(agentPortfolio, "credit");
    JsonNode agentActivity =
        required(get(false, "/api/v1/trading/info/portfolio"), "clientPortfolio");
    JsonNode ownerActivity =
        required(get(true, "/api/v1/trading/info/portfolio"), "clientPortfolio");
    JsonNode copyActivity =
        unique(array(ownerActivity, "mirrors"), "mirrorID", integer(link, "mirrorId"));
    if (integer(copyActivity, "parentCID") != agentCid
        || !sameUnits(ap, array(agentActivity, "positions"))
        || !sameUnits(op, array(copyActivity, "positions")))
      throw new IOException("ACCOUNT_CHANGED_DURING_READ");
    boolean complete = true, pending = false;
    for (var node : List.of(copyActivity, agentActivity))
      for (String key :
          List.of(
              "ordersForOpen",
              "ordersForClose",
              "ordersForCloseMultiple",
              "entryOrders",
              "exitOrders")) {
        if (!node.path(key).isArray()) complete = false;
        else pending |= !node.path(key).isEmpty();
      }
    // The agent is dedicated to this strategy. Nested copying is unsupported.
    if (!array(agentPortfolio, "mirrors").isEmpty())
      throw new IOException("NESTED_AGENT_COPY_UNSUPPORTED");
    for (String key : List.of("delayedOrderForOpen", "delayedOrderForClose")) {
      if (!copyActivity.path(key).isArray()) complete = false;
      else pending |= !copyActivity.path(key).isEmpty();
    }
    for (String key : List.of("orders", "stockOrders")) {
      if (!agentActivity.path(key).isArray()) complete = false;
      else pending |= !agentActivity.path(key).isEmpty();
    }
    return new Account(
        observed,
        equity(oc, op),
        oc,
        equity(ac, ap),
        ac,
        complete,
        pending,
        !bool(mirror, "isPaused")
            && !bool(mirror, "pendingForClosure")
            && integer(mirror, "mirrorStatusID") == 0,
        config.copySizingModel.equals("REALIZED_EQUITY_RATIO")
            && !config.copySizingEvidence.isBlank()
            && !config.copyPriceCeilingEvidence.isBlank(),
        !config.copyStopsEvidence.isBlank(),
        ap,
        op);
  }

  public Instrument instrument(String symbol, BigDecimal proposedOwnerAmount) throws IOException {
    var asset = config.assets.get(symbol);
    if (asset == null) return null;
    var identity =
        unique(
            array(
                get(false, "/api/v2/market-data/instruments?symbols=" + asset.brokerSymbol),
                "results"),
            "instrumentId",
            asset.instrumentId);
    if (!text(identity, "symbol").equals(asset.brokerSymbol))
      throw new IOException("INSTRUMENT_IDENTITY_CHANGED");
    var request = Json.MAPPER.createObjectNode().put("currency", "USD");
    request.putArray("instrumentIds").add(asset.instrumentId);
    JsonNode eligibility =
        unique(
            array(query(false, "/api/v2/trading/info/eligibility", request), "eligibilities"),
            "instrumentId",
            asset.instrumentId);
    if (!bool(eligibility, "allowOpenPosition")
        || !Set.of("all", "amountOnly").contains(text(eligibility, "allowedOrderQuantityType"))
        || !text(eligibility, "tradeUnitType").equals("units")) return null;
    JsonNode leverage = null;
    for (JsonNode candidate : array(eligibility, "leverageConfigs")) {
      if (text(candidate, "settlementType").equals(asset.settlementType)
          && text(candidate, "direction").equals("long")
          && !bool(candidate, "isPotential")) {
        for (var value : array(candidate, "leverageValues"))
          if (value.decimalValue().compareTo(BigDecimal.ONE) == 0) leverage = candidate;
      }
    }
    if (leverage == null
        || !bool(leverage, "allowStopLossTakeProfit")
        || !bool(leverage, "allowEditStopLoss")) return null;
    // Query owner-side costs for the actual copied amount, not internal agent dollars.
    var costRequest =
        Json.MAPPER
            .createObjectNode()
            .put("action", "open")
            .put("transaction", "buy")
            .put("instrumentId", asset.instrumentId)
            .put("settlementType", asset.settlementType)
            .put("orderType", "mkt")
            .put("leverage", 1)
            .put("amount", proposedOwnerAmount)
            .put("orderCurrency", "usd");
    JsonNode costs = query(true, "/api/v2/trading/info/costs", costRequest);
    if (integer(costs, "instrumentId") != asset.instrumentId
        || Duration.between(instant(text(costs, "lastUpdated")), clock.instant()).abs().getSeconds()
            > 60) throw new IOException("COST_ESTIMATE_STALE");
    BigDecimal total = BigDecimal.ZERO;
    for (JsonNode cost : array(costs, "costs")) {
      if (!text(cost, "currency").equals("USD")) throw new IOException("COST_CURRENCY_UNSUPPORTED");
      total = total.add(decimal(cost, "amount").max(BigDecimal.ZERO));
    }
    return new Instrument(
        asset.instrumentId,
        symbol,
        "USD",
        true,
        true,
        asset.settlementType,
        asset.priceScale,
        text(eligibility, "unitsQuantityType").equals("whole") ? 0 : asset.unitScale,
        decimal(leverage, "minPositionAmount"),
        total);
  }

  public Quote quote(Instrument i) throws IOException {
    JsonNode rate =
        unique(
            array(get(false, "/api/v2/market-data/rates?instrumentIds=" + i.id()), "results"),
            "instrumentId",
            i.id());
    JsonNode market =
        unique(
            array(
                get(
                    false,
                    "/api/v1/market-data/search?instrumentId="
                        + i.id()
                        + "&fields=isExchangeOpen,isCurrentlyTradable"),
                "items"),
            "instrumentId",
            i.id());
    return new Quote(
        decimal(rate, "ask"),
        instant(text(rate, "date")),
        text(rate, "quoteType").equals("realtime")
            && bool(market, "isExchangeOpen")
            && bool(market, "isCurrentlyTradable"),
        "USD");
  }

  @Override
  public int unitScale(String symbol) throws IOException {
    var asset = config.assets.get(symbol);
    if (asset == null) throw new IOException("REDUCTION_PRECISION_UNVERIFIED");
    return asset.unitScale;
  }

  @Override
  public Receipt submit(Intent intent, String reference) throws IOException {
    if (!writesAllowed) throw new IOException("READ_ONLY_CLIENT");
    var request = OrderPayloads.create(intent);
    JsonNode response =
        call(false, request.method(), request.path(), request.body().toString(), reference);
    if (intent.action() == Action.STOP) return new Receipt(null);
    if (intent.action() == Action.OPEN || intent.action() == Action.ADD)
      return new Receipt(integer(response, "orderId"));
    return new Receipt(integer(required(response, "orderForClose"), "orderID"));
  }

  @Override
  public Observation observe(Attempt attempt) throws IOException {
    Intent i = attempt.intent;
    Account account = account();
    if (i.action() == Action.OPEN || i.action() == Action.ADD) {
      JsonNode order =
          get(
              false,
              "/api/v2/trading/info/orders:lookup?"
                  + (attempt.orderId == null
                      ? "referenceId=" + attempt.reference
                      : "orderId=" + attempt.orderId));
      JsonNode asset = required(order, "asset");
      if (integer(asset, "instrumentId") != i.instrumentId()
          || decimal(asset, "leverage").compareTo(BigDecimal.ONE) != 0
          || !text(asset, "side").equals("long")) throw new IOException("ORDER_IDENTITY_MISMATCH");
      int status = (int) integer(required(order, "status"), "id");
      List<Long> ids = new ArrayList<>();
      for (JsonNode p : array(order, "positionExecutions")) {
        if (decimal(required(p, "openingData"), "avgPrice").compareTo(i.ceiling()) > 0)
          return observation(Status.UNKNOWN, "PRICE_CEILING_BREACHED", ids);
        ids.add(integer(p, "positionId"));
      }
      if (ids.isEmpty() && Set.of(4, 7, 8).contains(status))
        return observation(Status.REJECTED, "CONFIRMED_NO_FILL", ids);
      if (ids.isEmpty()) return observation(Status.SUBMITTED, "AWAITING_FILL", ids);
      BigDecimal agentFilled = BigDecimal.ZERO;
      BigDecimal ownerFilled = BigDecimal.ZERO;
      for (long id : ids) {
        Position p = find(account.agentPositions(), id);
        List<Position> copies =
            account.ownerPositions().stream().filter(c -> c.parentId() == id).toList();
        if (p == null
            || !protectedAt(p, i.stop())
            || copies.isEmpty()
            || copies.stream().anyMatch(c -> !protectedAt(c, i.stop())))
          return observation(Status.PARTIAL, "COPY_OR_STOP_NOT_CONFIRMED", ids);
        if (copies.stream()
            .anyMatch(
                c ->
                    c.instrumentId() != i.instrumentId()
                        || c.openRate().compareTo(i.ceiling()) > 0))
          return observation(Status.UNKNOWN, "COPIED_PRICE_OR_ASSET_MISMATCH", ids);
        BigDecimal copied =
            copies.stream().map(Position::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expected =
            p.amount().multiply(i.ownerAmount()).divide(i.agentAmount(), MathContext.DECIMAL128);
        if (copied.subtract(expected).abs().compareTo(new BigDecimal("0.01")) > 0)
          return observation(Status.UNKNOWN, "COPIED_AMOUNT_MISMATCH", ids);
        agentFilled = agentFilled.add(p.amount());
        ownerFilled = ownerFilled.add(copied);
      }
      if (agentFilled.compareTo(i.agentAmount()) > 0
          || ownerFilled.subtract(i.ownerAmount()).compareTo(new BigDecimal("0.01")) > 0)
        return observation(Status.UNKNOWN, "FILLED_AMOUNT_EXCEEDS_REQUEST", ids);
      BigDecimal shortfall = i.ownerAmount().subtract(ownerFilled).max(BigDecimal.ZERO);
      return new Observation(
          Set.of(3, 9, 10).contains(status) ? Status.CONFIRMED : Status.PARTIAL,
          shortfall.signum() > 0 ? "PARTIAL_FILL_KEPT_AND_PROTECTED" : "FILLED_AND_PROTECTED",
          List.copyOf(ids),
          agentFilled,
          ownerFilled,
          shortfall);
    }
    Position p = find(account.agentPositions(), i.positionId());
    List<Position> copies =
        account.ownerPositions().stream().filter(c -> c.parentId() == i.positionId()).toList();
    if (i.action() == Action.STOP) {
      boolean verified =
          p != null
              && protectedAt(p, i.stop())
              && !copies.isEmpty()
              && copies.stream().allMatch(c -> protectedAt(c, i.stop()));
      return observation(
          verified ? Status.CONFIRMED : Status.SUBMITTED, "STOP_READBACK", List.of(i.positionId()));
    }
    if (attempt.orderId == null)
      return observation(Status.UNKNOWN, "CLOSE_RESPONSE_LOST_REQUIRES_RECOVERY", List.of());
    JsonNode close = get(false, "/api/v1/trading/info/real/close-orders/" + attempt.orderId);
    JsonNode execution = unique(array(close, "positions"), "positionID", i.positionId());
    if (decimal(execution, "units").compareTo(i.units()) != 0)
      return observation(Status.PARTIAL, "CLOSE_UNITS_NOT_CONFIRMED", List.of(i.positionId()));
    if (p == null && copies.isEmpty())
      return observation(Status.CONFIRMED, "FULL_CLOSE_CONFIRMED", List.of());
    // A partial close must also be proved against pre-submission agent and owner lots.
    if (p != null
        && attempt.beforeAgentUnits != null
        && p.units().compareTo(attempt.beforeAgentUnits.subtract(i.units())) == 0
        && copiedReductionConfirmed(attempt, copies))
      return observation(Status.CONFIRMED, "PARTIAL_CLOSE_CONFIRMED", List.of(p.id()));
    return observation(Status.PARTIAL, "COPY_CLOSE_PENDING", List.of(i.positionId()));
  }

  @Override
  public void prepare(Attempt attempt) throws IOException {
    Account a = account();
    if (!a.active()
        || !a.ordersComplete()
        || a.pending()
        || Duration.between(a.observedAt(), clock.instant()).getSeconds() > 60)
      throw new IOException("ACCOUNT_CHANGED_BEFORE_SUBMISSION");
    Intent i = attempt.intent;
    if (i.positionId() == null) {
      if (!a.copySizingVerified() || !a.copyStopsVerified())
        throw new IOException("COPY_CAPABILITIES_UNVERIFIED");
      String symbol =
          config.assets.entrySet().stream()
              .filter(e -> e.getValue().instrumentId == i.instrumentId())
              .map(Map.Entry::getKey)
              .findFirst()
              .orElseThrow(() -> new IOException("INSTRUMENT_UNVERIFIED"));
      Instrument asset = instrument(symbol, i.ownerAmount());
      if (asset == null || !asset.settlementType().equals(i.settlementType()))
        throw new IOException("ELIGIBILITY_CHANGED");
      Quote q = quote(asset);
      if (!q.exchangeOpen()
          || q.timestamp().isAfter(clock.instant())
          || Duration.between(q.timestamp(), clock.instant()).getSeconds() > 60
          || q.ask().compareTo(i.ceiling()) > 0
          || q.ask().compareTo(i.stop()) <= 0)
        throw new IOException("QUOTE_CHANGED_BEFORE_SUBMISSION");
      BigDecimal ownerCapital =
          a.ownerPositions().stream().map(Position::amount).reduce(a.ownerCash(), BigDecimal::add);
      BigDecimal agentCapital =
          a.agentPositions().stream().map(Position::amount).reduce(a.agentCash(), BigDecimal::add);
      if (agentCapital.signum() <= 0 || ownerCapital.signum() <= 0)
        throw new IOException("COPY_CAPITAL_INVALID");
      BigDecimal copied =
          i.agentAmount().multiply(ownerCapital).divide(agentCapital, MathContext.DECIMAL128);
      BigDecimal agentCost =
          asset
              .estimatedOwnerCost()
              .multiply(agentCapital)
              .divide(ownerCapital, MathContext.DECIMAL128);
      if (copied.subtract(i.ownerAmount()).abs().compareTo(new BigDecimal("0.01")) > 0
          || i.ownerAmount().add(asset.estimatedOwnerCost()).compareTo(a.ownerCash()) > 0
          || i.agentAmount().add(agentCost).compareTo(a.agentCash()) > 0
          || i.agentAmount().compareTo(asset.minimumAgentAmount()) < 0)
        throw new IOException("SIZING_CHANGED_BEFORE_SUBMISSION");
      return;
    }
    Position p = find(a.agentPositions(), i.positionId());
    if (p == null) throw new IOException("POSITION_CHANGED_BEFORE_SUBMISSION");
    if (p.instrumentId() != i.instrumentId()
        || !p.longOnly()
        || i.units() != null && i.units().compareTo(p.units()) > 0)
      throw new IOException("POSITION_CONTRACT_MISMATCH");
    attempt.beforeAgentUnits = p.units();
    for (Position copy : a.ownerPositions())
      if (copy.parentId() == p.id()) attempt.beforeOwnerUnits.put(copy.id(), copy.units());
    if (attempt.beforeOwnerUnits.isEmpty()) throw new IOException("COPIED_POSITION_MISSING");
  }

  private boolean copiedReductionConfirmed(Attempt a, List<Position> copies) {
    BigDecimal ratio =
        a.beforeAgentUnits
            .subtract(a.intent.units())
            .divide(a.beforeAgentUnits, MathContext.DECIMAL128);
    var asset =
        config.assets.values().stream()
            .filter(x -> x.instrumentId == a.intent.instrumentId())
            .findFirst();
    if (asset.isEmpty() || copies.size() != a.beforeOwnerUnits.size()) return false;
    BigDecimal step = BigDecimal.ONE.scaleByPowerOfTen(-asset.get().unitScale);
    return copies.stream()
        .allMatch(
            p ->
                a.beforeOwnerUnits.containsKey(p.id())
                    && p.units()
                            .subtract(a.beforeOwnerUnits.get(p.id()).multiply(ratio))
                            .abs()
                            .compareTo(step)
                        <= 0);
  }

  private static Observation observation(Status s, String reason, List<Long> ids) {
    return new Observation(s, reason, List.copyOf(ids));
  }

  private static Position find(List<Position> positions, long id) {
    return positions.stream().filter(p -> p.id() == id).findFirst().orElse(null);
  }

  private static boolean protectedAt(Position p, BigDecimal stop) {
    return p.longOnly()
        && p.stopEnabled()
        && !p.trailing()
        && p.stop() != null
        && p.stop().compareTo(stop) == 0;
  }

  private static BigDecimal equity(BigDecimal cash, List<Position> positions) {
    return positions.stream().map(p -> p.amount().add(p.pnl())).reduce(cash, BigDecimal::add);
  }

  private static boolean sameUnits(List<Position> positions, JsonNode other) throws IOException {
    if (positions.size() != other.size()) return false;
    for (Position p : positions)
      if (decimal(unique(other, "positionID", p.id()), "units").compareTo(p.units()) != 0)
        return false;
    return true;
  }

  private static List<Position> positions(JsonNode nodes) throws IOException {
    List<Position> result = new ArrayList<>();
    for (var p : nodes)
      result.add(
          new Position(
              integer(p, "positionID"),
              integer(p, "parentPositionID"),
              integer(p, "instrumentID"),
              decimal(p, "units"),
              decimal(p, "amount"),
              decimal(required(p, "unrealizedPnL"), "pnL"),
              decimal(p, "stopLossRate"),
              !bool(p, "isNoStopLoss"),
              bool(p, "isTslEnabled"),
              bool(p, "isBuy") && decimal(p, "leverage").compareTo(BigDecimal.ONE) == 0,
              decimal(p, "openRate")));
    return List.copyOf(result);
  }

  private static JsonNode required(JsonNode node, String name) throws IOException {
    if (node == null || !node.hasNonNull(name))
      throw new IOException("BROKER_REQUIRED_FIELD_MISSING_" + name.toUpperCase(Locale.ROOT));
    return node.get(name);
  }

  private static JsonNode array(JsonNode node, String name) throws IOException {
    var v = required(node, name);
    if (!v.isArray()) throw new IOException("BROKER_ARRAY_REQUIRED");
    return v;
  }

  private static long integer(JsonNode node, String name) throws IOException {
    var v = required(node, name);
    if (!v.isIntegralNumber() || !v.canConvertToLong())
      throw new IOException("BROKER_INTEGER_REQUIRED");
    return v.longValue();
  }

  private static BigDecimal decimal(JsonNode node, String name) throws IOException {
    var v = required(node, name);
    if (!v.isNumber()) throw new IOException("BROKER_DECIMAL_REQUIRED");
    return v.decimalValue();
  }

  private static String text(JsonNode node, String name) throws IOException {
    var v = required(node, name);
    if (!v.isTextual()) throw new IOException("BROKER_TEXT_REQUIRED");
    return v.textValue();
  }

  private static boolean bool(JsonNode node, String name) throws IOException {
    var v = required(node, name);
    if (!v.isBoolean()) throw new IOException("BROKER_BOOLEAN_REQUIRED");
    return v.booleanValue();
  }

  private static JsonNode unique(JsonNode array, String key, long id) throws IOException {
    JsonNode match = null;
    for (var n : array)
      if (integer(n, key) == id) {
        if (match != null) throw new IOException("BROKER_DUPLICATE_ID");
        match = n;
      }
    if (match == null) throw new IOException("BROKER_ID_NOT_FOUND");
    return match;
  }

  private static Instant instant(String value) {
    return Instant.parse(
        value.endsWith("Z") || value.matches(".*[+-]\\d\\d:\\d\\d$") ? value : value + "Z");
  }
}
