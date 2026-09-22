package com.loris.bravos.app;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ConfigurationTest {
  @Test
  void lookupIdentitiesCannotOverlapExecutionProfilesOrContainInvalidIds() throws Exception {
    var c = new Configuration();
    var id = new Configuration.Identity();
    id.instrumentId = 1367;
    id.brokerSymbol = "IBIT";
    c.lookupOnlyAssets.put("IBIT", id);
    c.validate();
    for (String bad : new String[] {null, "", "A&bad=1"}) {
      id.brokerSymbol = bad;
      assertThrows(IOException.class, c::validate);
    }
    id.brokerSymbol = "IBIT";
    id.instrumentId = 0;
    assertThrows(IOException.class, c::validate);
    id.instrumentId = -1;
    assertThrows(IOException.class, c::validate);
    id.instrumentId = 1367;
    c.lookupOnlyAssets.put("OTHER", id);
    assertThrows(IOException.class, c::validate);
    c.lookupOnlyAssets.remove("OTHER");
    c.lookupOnlyAssets.put("bad&", id);
    assertThrows(IOException.class, c::validate);
    c.lookupOnlyAssets.remove("bad&");
    var asset = new Configuration.Asset();
    asset.instrumentId = 42;
    asset.brokerSymbol = "IBIT";
    asset.unleveragedEvidence = "fixture";
    asset.priceScale = 2;
    asset.unitScale = 5;
    c.assets.put("IBIT", asset);
    assertThrows(IOException.class, c::validate);
    c.assets.clear();
    c.lookupOnlyAssets.put("IBIT", null);
    assertThrows(IOException.class, c::validate);
    c.lookupOnlyAssets = null;
    assertThrows(IOException.class, c::validate);
  }

  @Test
  void explicitPrecisionSupportsWholeUnitsAndRejectsOutsideDocumentedConfigRange()
      throws Exception {
    var config = new Configuration();
    var asset = new Configuration.Asset();
    asset.instrumentId = 1;
    asset.brokerSymbol = "CF";
    asset.unleveragedEvidence = "fixture issuer";
    config.assets.put("CF", asset);
    assertThrows(IOException.class, config::validate);
    asset.priceScale = 0;
    asset.unitScale = 0;
    config.validate();
    asset.priceScale = 8;
    asset.unitScale = 12;
    config.validate();
    asset.priceScale = 9;
    assertThrows(IOException.class, config::validate);
    asset.priceScale = 8;
    asset.unitScale = 13;
    assertThrows(IOException.class, config::validate);
    asset.unitScale = 12;
    asset.marketHours = "US_EQUITIES_2026";
    config.validate();
    asset.marketHours = "unknown";
    assertThrows(IOException.class, config::validate);
    asset.marketHours = null;
    assertThrows(IOException.class, config::validate);
    asset.marketHours = "BROKER_FLAG";
    asset.instrumentId = 0;
    assertThrows(IOException.class, config::validate);
  }

  @Test
  void copiedPriceModeDistinguishesConsentFromBrokerEvidence() throws Exception {
    var config = new Configuration();
    assertFalse(config.copyPricePermitted());
    config.copyPriceCeilingEvidence = " ";
    assertFalse(config.copyPricePermitted());
    config.copyPriceCeilingEvidence = "verified fixture contract";
    assertTrue(config.copyPricePermitted());
    config.copyPriceCeilingEvidence = "";
    config.copyPricePolicy = "AGENT_LIMIT_WITH_COPY_CHECK";
    config.validate();
    assertTrue(config.copyPricePermitted());
    config.copyPricePolicy = "typo";
    assertThrows(IOException.class, config::validate);
    assertFalse(config.copyPricePermitted());
    config.copyPricePolicy = null;
    assertThrows(IOException.class, config::validate);
    assertFalse(config.copyPricePermitted());
  }
}
