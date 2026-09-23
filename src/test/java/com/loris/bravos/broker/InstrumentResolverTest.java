package com.loris.bravos.broker;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loris.bravos.app.Configuration;
import com.loris.bravos.util.Json;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class InstrumentResolverTest {
  @Test
  void fundsRequireExplicitConsentAndStillRequireUsCurrencyContext() throws Exception {
    var fund = stock().put("type", "ETF").put("symbol", "TQQQ");
    assertThrows(IOException.class, () -> InstrumentResolver.profile(fund, exchanges(), false));
    var asset = InstrumentResolver.profile(fund, exchanges(), true);
    assertEquals("TQQQ", asset.brokerSymbol);
    assertEquals("US_EQUITIES_2026", asset.marketHours);
    assertTrue(asset.unleveragedEvidence.contains("owner permits embedded fund leverage"));
    fund.put("exchangeId", 7);
    assertThrows(IOException.class, () -> InstrumentResolver.profile(fund, exchanges(), true));
    fund.put("exchangeId", 4).put("type", "Futures");
    assertThrows(IOException.class, () -> InstrumentResolver.profile(fund, exchanges(), true));
  }

  ObjectNode node(String s) throws Exception {
    return (ObjectNode) Json.MAPPER.readTree(s);
  }

  ObjectNode stock() throws Exception {
    return node("{\"symbol\":\"MEDP\",\"instrumentId\":8882,\"type\":\"Stocks\",\"exchangeId\":4}");
  }

  ObjectNode exchanges() throws Exception {
    return node("{\"exchangeInfo\":[{\"exchangeID\":4,\"exchangeDescription\":\"Nasdaq\"}]}");
  }

  ObjectNode response() throws Exception {
    var n = node("{\"pagination\":{\"hasNext\":false}}");
    n.putArray("results").add(stock());
    return n;
  }

  @Test
  void discoversExactUsStockWithoutTickerConfiguration() throws Exception {
    var identity = InstrumentResolver.identity(response(), "MEDP", null);
    var profile = InstrumentResolver.profile(identity, exchanges());
    assertEquals(8882, profile.instrumentId);
    assertEquals("MEDP", profile.brokerSymbol);
    assertEquals("US_EQUITIES_2026", profile.marketHours);
    assertEquals(2, profile.priceScale);
    assertEquals(5, profile.unitScale);
    assertNull(profile.settlementType);
    assertTrue(profile.unleveragedEvidence.contains("NASDAQ"));
  }

  @Test
  void aliasesMustBeUniqueAndPinnedIdsCannotChange() throws Exception {
    var n = response();
    ((ObjectNode) n.path("results").get(0)).put("symbol", "MEDP.US");
    assertEquals("MEDP.US", InstrumentResolver.identity(n, "MEDP", null).path("symbol").asText());
    n.withArray("results").add(stock());
    assertEquals(
        "INSTRUMENT_IDENTITY_AMBIGUOUS",
        assertThrows(IOException.class, () -> InstrumentResolver.identity(n, "MEDP", null))
            .getMessage());
    var pin = new Configuration.Identity();
    pin.instrumentId = 8882;
    pin.brokerSymbol = "MEDP.US";
    assertEquals("MEDP.US", InstrumentResolver.identity(n, "MEDP", pin).path("symbol").asText());
    pin.instrumentId = 8883;
    assertThrows(IOException.class, () -> InstrumentResolver.identity(n, "MEDP", pin));
    assertThrows(IOException.class, () -> InstrumentResolver.identity(n, "OTHER", null));
  }

  @Test
  void incompleteOrInvalidIdentitiesAreNeverPromoted() throws Exception {
    for (String invalid :
        new String[] {"0", "-1", "1.5", "\"8882\"", "9223372036854775808", "null"}) {
      var n = response();
      ((ObjectNode) n.path("results").get(0)).set("instrumentId", Json.MAPPER.readTree(invalid));
      assertThrows(IOException.class, () -> InstrumentResolver.identity(n, "MEDP", null));
    }
    for (String invalid :
        new String[] {
          "{}",
          "{\"results\":[]}",
          "{\"results\":[],\"pagination\":{\"hasNext\":true}}",
          "{\"results\":[],\"pagination\":{\"hasNext\":\"false\"}}"
        }) {
      var n = node(invalid);
      assertThrows(IOException.class, () -> InstrumentResolver.identity(n, "MEDP", null));
    }
  }

  @Test
  void fundLeverageAndUnknownCurrencyCannotBeInferredFromX1() throws Exception {
    for (String type : new String[] {"ETF", "Crypto", "", "Stocks"}) {
      var n = stock().put("type", type);
      if (type.equals("Stocks")) n.put("exchangeId", 7);
      assertThrows(IOException.class, () -> InstrumentResolver.profile(n, exchanges()));
    }
    var n = stock();
    n.remove("exchangeId");
    assertThrows(IOException.class, () -> InstrumentResolver.profile(n, exchanges()));
    assertThrows(IOException.class, () -> InstrumentResolver.profile(stock(), node("{}")));
    var exchange = exchanges();
    exchange.withArray("exchangeInfo").add(exchange.path("exchangeInfo").get(0).deepCopy());
    assertThrows(IOException.class, () -> InstrumentResolver.profile(stock(), exchange));
  }
}
