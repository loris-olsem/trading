package com.loris.bravos.app;

import com.loris.bravos.util.Json;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Non-secret instrument identities and evidence-backed broker capabilities. */
public final class Configuration {
  public String copySizingEvidence = "";
  public String copyStopsEvidence = "";
  public String copyPriceCeilingEvidence = "";
  public String copySizingModel = "UNVERIFIED";
  public Map<String, Asset> assets = new LinkedHashMap<>();

  public static final class Asset {
    public long instrumentId;
    public String brokerSymbol;
    public String settlementType = "real";
    public String unleveragedEvidence;
    public int priceScale = 2;
    public int unitScale = 6;
  }

  public static Configuration load(Path file) throws IOException {
    Configuration c = Json.MAPPER.readValue(file.toFile(), Configuration.class);
    c.validate();
    return c;
  }

  public void validate() throws IOException {
    if (copySizingEvidence == null
        || copyStopsEvidence == null
        || copyPriceCeilingEvidence == null
        || !Set.of("UNVERIFIED", "REALIZED_EQUITY_RATIO").contains(copySizingModel)
        || assets == null) throw new IOException("INVALID_CONFIGURATION");
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
          || !Set.of("real", "cfd").contains(a.settlementType)
          || a.priceScale < 0
          || a.priceScale > 8
          || a.unitScale < 0
          || a.unitScale > 12) throw new IOException("INVALID_ASSET_CONFIGURATION");
    }
  }
}
