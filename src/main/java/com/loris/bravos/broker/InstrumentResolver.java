package com.loris.bravos.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.loris.bravos.app.Configuration;
import java.io.IOException;
import java.util.*;

/** Resolves exact broker identities; never substitutes a fuzzy search result. */
final class InstrumentResolver {
  private InstrumentResolver() {}

  static JsonNode identity(JsonNode response, String symbol, Configuration.Identity pinned)
      throws IOException {
    if (!response.path("results").isArray()
        || !response.path("pagination").path("hasNext").isBoolean())
      throw new IOException("INSTRUMENT_LOOKUP_INCOMPLETE");
    if (response.path("pagination").path("hasNext").booleanValue())
      throw new IOException("INSTRUMENT_LOOKUP_INCOMPLETE");
    List<JsonNode> matches = new ArrayList<>();
    for (var item : response.path("results")) {
      String found = item.path("symbol").asText();
      if (pinned == null
          ? found.equals(symbol) || found.equals(symbol + ".US")
          : found.equals(pinned.brokerSymbol)
              && item.path("instrumentId").asLong() == pinned.instrumentId) matches.add(item);
    }
    if (matches.isEmpty())
      throw new IOException(
          pinned == null ? "INSTRUMENT_NOT_LISTED" : "INSTRUMENT_IDENTITY_CHANGED");
    if (matches.size() != 1) throw new IOException("INSTRUMENT_IDENTITY_AMBIGUOUS");
    var result = matches.getFirst();
    if (!result.path("instrumentId").isIntegralNumber()
        || !result.path("instrumentId").canConvertToLong()
        || result.path("instrumentId").asLong() <= 0)
      throw new IOException("INVALID_INSTRUMENT_ID");
    return result;
  }

  static Configuration.Asset profile(JsonNode identity, JsonNode exchanges) throws IOException {
    return profile(identity, exchanges, false);
  }

  static Configuration.Asset profile(
      JsonNode identity, JsonNode exchanges, boolean allowLeveragedFunds) throws IOException {
    // X1 eligibility says nothing about embedded leverage (e.g. TQQQ).
    String type = identity.path("type").asText();
    if (!"Stocks".equals(type) && !("ETF".equals(type) && allowLeveragedFunds))
      throw new IOException("PRODUCT_STRUCTURE_UNVERIFIED");
    if (!identity.path("exchangeId").isIntegralNumber()
        || !exchanges.path("exchangeInfo").isArray())
      throw new IOException("INSTRUMENT_CURRENCY_UNVERIFIED");
    var names = new ArrayList<String>();
    for (var exchange : exchanges.path("exchangeInfo"))
      if (exchange.path("exchangeID").isIntegralNumber()
          && exchange.path("exchangeID").longValue() == identity.path("exchangeId").longValue())
        names.add(exchange.path("exchangeDescription").asText().toUpperCase(Locale.ROOT));
    if (names.size() != 1
        || !Set.of("NASDAQ", "NYSE", "NYSE ARCA", "NYSE AMERICAN", "AMEX")
            .contains(names.getFirst())) throw new IOException("INSTRUMENT_CURRENCY_UNVERIFIED");
    var asset = new Configuration.Asset();
    asset.instrumentId = identity.path("instrumentId").longValue();
    asset.brokerSymbol = identity.path("symbol").asText();
    asset.settlementType =
        null; // Selected from the intersection of both accounts' live permissions.
    asset.marketHours = "US_EQUITIES_2026";
    asset.priceScale = 2;
    asset.unitScale = 5;
    asset.unleveragedEvidence =
        "Live broker "
            + type
            + " classification on "
            + names.getFirst()
            + ("ETF".equals(type)
                ? "; owner permits embedded fund leverage, broker X1 still required"
                : "");
    return asset;
  }
}
