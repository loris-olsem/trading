package com.loris.bravos.app;

import static org.junit.jupiter.api.Assertions.*;

import com.loris.bravos.broker.Transport;
import com.loris.bravos.util.Json;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class InstrumentAuditTest {
  @Test
  void watchlistDiscoveryCannotCreateListsOrAddAssets() throws Exception {
    var result =
        InstrumentAudit.watchlists(
            (method, path, headers, body) -> {
              assertEquals("POST", method);
              assertEquals("/api/v2/watchlists", path);
              assertEquals("fixture-owner", headers.get("x-user-key"));
              var request = Json.MAPPER.readTree(body);
              assertEquals(1, request.path("version").asInt());
              UUID.fromString(request.path("requestId").asText());
              assertEquals(1, request.path("components").size());
              var component = request.path("components").get(0);
              assertEquals("userWatchlists", component.path("type").asText());
              assertTrue(component.path("supportedVariations").isEmpty());
              assertTrue(component.path("params").path("ensureBuiltinWatchlists").isBoolean());
              assertTrue(component.path("params").path("addRelatedAssets").isBoolean());
              assertEquals(
                  false, component.path("params").path("ensureBuiltinWatchlists").booleanValue());
              assertEquals(false, component.path("params").path("addRelatedAssets").booleanValue());
              assertEquals(100, component.path("params").path("itemsPerPageForSingle").asInt());
              return response(200, "{\"components\":[]}");
            },
            secrets);
    assertTrue(result.path("ownerWatchlists").path("components").isEmpty());
  }

  private final Secrets secrets =
      new Secrets(
          "fixture-app", "fixture-agent", "fixture-owner", "fixture-user", "fixture-password");

  @Test
  void retrievesEligibilityForBothAccountsWithoutSendingExecutionRequests() throws Exception {
    var calls = new ArrayList<String>();
    Transport api =
        (method, path, headers, body) -> {
          calls.add(method + " " + path);
          if (calls.size() == 1) {
            assertEquals("GET /api/v2/market-data/instruments?symbols=CF,EOG", calls.getLast());
            assertNull(body);
            return response(
                200,
                "{\"results\":[{\"instrumentId\":1890,\"symbol\":\"CF\",\"displayName\":\"CF Industries\"},{\"instrumentId\":1581,\"symbol\":\"EOG\"}]}");
          }
          assertEquals("POST /api/v2/trading/info/eligibility", calls.getLast());
          var request = Json.MAPPER.readTree(body);
          assertEquals("USD", request.path("currency").asText());
          assertEquals("[1890,1581]", request.path("instrumentIds").toString());
          assertEquals(
              calls.size() == 2 ? "fixture-agent" : "fixture-owner", headers.get("x-user-key"));
          return response(200, "{\"accountMarker\":" + calls.size() + "}");
        };
    var result = InstrumentAudit.discover(api, secrets, "CF,EOG");
    assertEquals(3, calls.size());
    assertEquals(2, result.path("agentEligibility").path("accountMarker").asInt());
    assertEquals(3, result.path("ownerEligibility").path("accountMarker").asInt());
    assertEquals(
        "CF Industries",
        result.path("metadata").path("results").get(0).path("displayName").asText());
  }

  @Test
  void emptyMatchesDoNotRequestEligibility() throws Exception {
    var result =
        InstrumentAudit.discover(
            (method, path, headers, body) -> {
              assertEquals("GET", method);
              return response(200, "{\"results\":[]}");
            },
            secrets,
            "MAGS");
    assertFalse(result.has("agentEligibility"));
    assertFalse(result.has("ownerEligibility"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"cf", "CF&query=EOG", "CF/", "CF,", " CF", "CF EOG"})
  void invalidSymbolsCannotIssueRequests(String symbols) {
    assertThrows(
        IOException.class,
        () ->
            InstrumentAudit.discover(
                (m, p, h, b) -> {
                  fail("Unexpected request");
                  return null;
                },
                secrets,
                symbols));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "",
        "[]",
        "{",
        "{}",
        "{\"results\":{}}",
        "{\"results\":[{}]}",
        "{\"results\":[{\"instrumentId\":0}]}",
        "{\"results\":[{\"instrumentId\":-1}]}",
        "{\"results\":[{\"instrumentId\":1.5}]}",
        "{\"results\":[{\"instrumentId\":\"1\"}]}",
        "{\"results\":[],\"pagination\":{\"hasNext\":true}}"
      })
  void malformedOrIncompleteEvidenceIsRejected(String body) {
    assertThrows(
        IOException.class,
        () -> InstrumentAudit.discover((m, p, h, b) -> response(200, body), secrets, "CF"));
  }

  @Test
  void responseBodiesAreNotDisclosedOnHttpFailure() {
    var error =
        assertThrows(
            IOException.class,
            () ->
                InstrumentAudit.discover(
                    (m, p, h, b) -> response(403, "sensitive response"), secrets, "CF"));
    assertEquals("INSTRUMENT_AUDIT_HTTP_403", error.getMessage());
    assertNull(error.getCause());
  }

  @Test
  void searchEncodesQueryAndKeepsCandidatesSeparateFromEligibility() throws Exception {
    var result =
        InstrumentAudit.search(
            (m, p, h, b) -> {
              assertEquals("GET", m);
              assertEquals(
                  "/api/v2/market-data/instruments/search?limit=50&query=iShares+Ethereum+%26+Trust",
                  p);
              assertNull(b);
              return response(
                  200, "{\"results\":[{\"instrumentId\":12,\"displayName\":\"candidate\"}]}");
            },
            secrets,
            "iShares Ethereum & Trust");
    assertEquals(
        "candidate", result.path("metadata").path("results").get(0).path("displayName").asText());
    assertFalse(result.has("agentEligibility"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  void invalidSearchNeverIssuesRequests(String query) {
    assertThrows(
        IOException.class,
        () ->
            InstrumentAudit.search(
                (m, p, h, b) -> {
                  fail("Unexpected request");
                  return null;
                },
                secrets,
                query));
  }

  private static Transport.Response response(int status, String body) {
    return new Transport.Response(status, body, Map.of());
  }
}
