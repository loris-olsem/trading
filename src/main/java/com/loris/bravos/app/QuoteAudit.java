package com.loris.bravos.app;

import com.loris.bravos.broker.StreamingRates;
import java.time.*;

/** Read-only price diagnostic; prints only prices and sanitized errors. */
public final class QuoteAudit {
  public static void main(String[] args) throws Exception {
    var clock = Clock.systemUTC();
    var credentials = Secrets.load(java.nio.file.Path.of(""));
    var rates = new StreamingRates(clock, credentials);
    var config = Configuration.load(java.nio.file.Path.of("config/trading.json"));
    for (String symbol : config.assets.keySet()) {
      try {
        var rate = rates.fetch(config.assets.get(symbol).instrumentId);
        System.out.println(
            symbol
                + ": streaming ask "
                + rate.ask()
                + ", age "
                + Duration.between(rate.timestamp(), clock.instant()).toMillis()
                + " ms");
      } catch (java.io.IOException e) {
        System.out.println(symbol + ": " + e.getMessage());
      }
    }
  }
}
