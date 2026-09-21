package com.loris.bravos.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.loris.bravos.broker.*;
import com.loris.bravos.util.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Read-only instrument discovery. It never configures or authorizes an order. */
public final class InstrumentAudit {
  private InstrumentAudit() {}

  public static void main(String[] args) {
    try {
      Path root = Path.of("").toAbsolutePath();
      var secrets = Secrets.load(root);
      var transport = new HttpTransport(URI.create("https://public-api.etoro.com"), false);
      var report =
          args.length == 1 && args[0].equals("--watchlists")
              ? watchlists(transport, secrets)
              : args.length == 2 && args[0].equals("--query")
                  ? search(transport, secrets, args[1])
                  : discover(
                      transport,
                      secrets,
                      args.length == 0 ? "CF,BRK.B,ARGT,MAGS,IBIT,EOG,ETHA.US,SMH" : args[0]);
      Path output =
          root.resolve(
              report.has("ownerWatchlists")
                  ? "state/capture/watchlist-metadata.json"
                  : report.has("query")
                      ? "state/capture/instrument-search.json"
                      : "state/capture/instruments.json");
      Files.createDirectories(output.getParent());
      Json.MAPPER.writeValue(output.toFile(), report);
      System.out.println("Read-only instrument evidence saved to " + root.relativize(output));
      for (var item : report.path("metadata").path("results"))
        System.out.println(
            item.path("symbol").asText()
                + " id="
                + item.path("instrumentId").asText()
                + " name="
                + item.path("displayName").asText());
    } catch (Exception exception) {
      System.err.println("INSTRUMENT_AUDIT_FAILED; credentials and response bodies withheld");
      System.exit(1);
    }
  }

  /** Read existing lists only; never create built-in lists or add any instruments. */
  public static JsonNode watchlists(Transport transport, Secrets secrets) throws IOException {
    var request =
        Json.MAPPER
            .createObjectNode()
            .put("version", 1)
            .put("requestId", UUID.randomUUID().toString());
    var component = request.putArray("components").addObject().put("type", "userWatchlists");
    component.putArray("supportedVariations");
    component
        .putObject("params")
        .put("itemsPerPageForSingle", 100)
        .put("ensureBuiltinWatchlists", false)
        .put("addRelatedAssets", false);
    var report = Json.MAPPER.createObjectNode().put("observedAt", Instant.now().toString());
    report.set(
        "ownerWatchlists",
        read(transport, secrets, true, "POST", "/api/v2/watchlists", request.toString()));
    return report;
  }

  public static JsonNode discover(Transport transport, Secrets secrets, String symbols)
      throws IOException {
    if (symbols == null || !symbols.matches("[A-Z][A-Z0-9.]*(,[A-Z][A-Z0-9.]*){0,39}"))
      throw new IOException("INVALID_SYMBOLS");
    var result = Json.MAPPER.createObjectNode().put("observedAt", Instant.now().toString());
    JsonNode metadata =
        read(
            transport,
            secrets,
            false,
            "GET",
            "/api/v2/market-data/instruments?symbols=" + symbols,
            null);
    if (!metadata.path("results").isArray()) throw new IOException("INVALID_INSTRUMENT_RESPONSE");
    if (metadata.path("pagination").path("hasNext").asBoolean())
      throw new IOException("INCOMPLETE_INSTRUMENT_RESPONSE");
    result.set("metadata", metadata);
    var request = Json.MAPPER.createObjectNode().put("currency", "USD");
    var ids = request.putArray("instrumentIds");
    for (var item : metadata.path("results")) {
      if (!item.path("instrumentId").isIntegralNumber()
          || !item.path("instrumentId").canConvertToLong()
          || item.path("instrumentId").asLong() <= 0)
        throw new IOException("INVALID_INSTRUMENT_ID");
      ids.add(item.path("instrumentId").asLong());
    }
    if (!ids.isEmpty()) {
      for (boolean owner : List.of(false, true))
        result.set(
            owner ? "ownerEligibility" : "agentEligibility",
            read(
                transport,
                secrets,
                owner,
                "POST",
                "/api/v2/trading/info/eligibility",
                request.toString()));
    }
    return result;
  }

  /** Search results are candidates for identity review, never approved symbol substitutions. */
  public static JsonNode search(Transport transport, Secrets secrets, String query)
      throws IOException {
    if (query == null || query.isBlank() || query.length() > 100)
      throw new IOException("INVALID_QUERY");
    var metadata =
        read(
            transport,
            secrets,
            false,
            "GET",
            "/api/v2/market-data/instruments/search?limit=50&query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8),
            null);
    if (!metadata.path("results").isArray()) throw new IOException("INVALID_INSTRUMENT_RESPONSE");
    var report =
        Json.MAPPER
            .createObjectNode()
            .put("observedAt", Instant.now().toString())
            .put("query", query);
    report.set("metadata", metadata);
    return report;
  }

  private static JsonNode read(
      Transport transport, Secrets secrets, boolean owner, String method, String path, String body)
      throws IOException {
    var response =
        transport.request(method, path, secrets.headers(owner, UUID.randomUUID().toString()), body);
    if (response.status() != 200)
      throw new IOException("INSTRUMENT_AUDIT_HTTP_" + response.status());
    try {
      var parsed = Json.MAPPER.readTree(response.body());
      if (parsed == null || !parsed.isObject()) throw new IOException();
      return parsed;
    } catch (Exception exception) {
      throw new IOException("INSTRUMENT_AUDIT_INVALID_JSON");
    }
  }
}
