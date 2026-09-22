package com.loris.bravos.app;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.loris.bravos.util.Json;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Non-secret identities and sources for the operating model; not proof of a broker fill. */
public final class Configuration {
  public String copySizingEvidence = "";
  public String copyStopsEvidence = "";
  public String copyPriceCeilingEvidence = "";
  public String copyPricePolicy = "REQUIRE_COPY_GUARANTEE";
  public String copySizingModel = "UNVERIFIED";
  public Map<String, Asset> assets = new LinkedHashMap<>();
  // Known identities for availability checks only; these never authorize entry.
  public Map<String, Identity> lookupOnlyAssets = new LinkedHashMap<>();

  public static final class Identity {
    public long instrumentId;
    public String brokerSymbol;
  }

  public static final class Asset {
    public long instrumentId;
    public String brokerSymbol;
    public String settlementType = "real";
    public String unleveragedEvidence;
    public String marketHours = "BROKER_FLAG";
    // Deliberate downward calculation granularity, not a claim of maximum API precision.
    public int priceScale = -1;
    public int unitScale = -1;
  }

  public static Configuration load(Path file) throws IOException {
    Configuration c =
        Json.MAPPER
            .readerFor(Configuration.class)
            .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .readValue(file.toFile());
    c.validate();
    return c;
  }

  public void validate() throws IOException {
    if (copySizingEvidence == null
        || copyStopsEvidence == null
        || copyPriceCeilingEvidence == null
        || copyPricePolicy == null
        || !Set.of(
                "REQUIRE_COPY_GUARANTEE", "AGENT_LIMIT_WITH_COPY_CHECK", "MARKET_WITH_PRICE_CHECK")
            .contains(copyPricePolicy)
        || !Set.of("UNVERIFIED", "REALIZED_EQUITY_RATIO").contains(copySizingModel)
        || assets == null
        || lookupOnlyAssets == null) throw new IOException("INVALID_CONFIGURATION");
    Set<Long> ids = new HashSet<>();
    for (var e : assets.entrySet()) {
      Asset a = e.getValue();
      if (!e.getKey().matches("[A-Z][A-Z0-9.]{0,12}")
          || a == null
          || a.instrumentId <= 0
          || !ids.add(a.instrumentId)
          || a.brokerSymbol == null
          || !a.brokerSymbol.matches("[A-Z][A-Z0-9.]{0,20}")
          || a.unleveragedEvidence == null
          || a.unleveragedEvidence.isBlank()
          || a.marketHours == null
          || !Set.of("BROKER_FLAG", "US_EQUITIES_2026").contains(a.marketHours)
          || !Set.of("real", "cfd").contains(a.settlementType)
          || a.priceScale < 0
          || a.priceScale > 8
          || a.unitScale < 0
          || a.unitScale > 12) throw new IOException("INVALID_ASSET_CONFIGURATION");
    }
    for (var e : lookupOnlyAssets.entrySet()) {
      Identity a = e.getValue();
      if (!e.getKey().matches("[A-Z][A-Z0-9.]{0,12}")
          || assets.containsKey(e.getKey())
          || a == null
          || a.instrumentId <= 0
          || !ids.add(a.instrumentId)
          || a.brokerSymbol == null
          || !a.brokerSymbol.matches("[A-Z][A-Z0-9.]{0,20}"))
        throw new IOException("INVALID_LOOKUP_CONFIGURATION");
    }
  }

  public boolean copyPricePermitted() {
    return "MARKET_WITH_PRICE_CHECK".equals(copyPricePolicy)
        || "AGENT_LIMIT_WITH_COPY_CHECK".equals(copyPricePolicy)
        || ("REQUIRE_COPY_GUARANTEE".equals(copyPricePolicy)
            && copyPriceCeilingEvidence != null
            && !copyPriceCeilingEvidence.isBlank());
  }

  public String entryOrderType() {
    return "MARKET_WITH_PRICE_CHECK".equals(copyPricePolicy) ? "mkt" : "limitIOC";
  }
}
