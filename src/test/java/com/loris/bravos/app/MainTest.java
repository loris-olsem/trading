package com.loris.bravos.app;

import static com.loris.bravos.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.loris.bravos.broker.*;
import com.loris.bravos.source.*;
import com.loris.bravos.state.*;
import com.loris.bravos.util.Json;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
  @TempDir Path root;
  final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  ByteArrayOutputStream output = new ByteArrayOutputStream();
  Main.Connector offline =
      (r, c, l) -> {
        throw new IOException("UNEXPECTED_NETWORK");
      };

  int run(String... args) {
    output.reset();
    return Main.run(args, root, clock, new PrintStream(output), offline);
  }

  @Test
  void helpStatusAndArgumentFailuresNeedNoCredentialsOrNetwork() {
    assertEquals(0, run());
    assertTrue(output.toString().replaceAll("\\s+", " ").contains("run"));
    assertEquals(0, run("help"));
    assertEquals(0, run("status"));
    assertTrue(output.toString().replaceAll("\\s+", " ").contains("initialized=false"));
    for (String[] args :
        List.of(
            new String[] {"unknown"},
            new String[] {"plan", "unexpected"},
            new String[] {"status", "extra"},
            new String[] {"plan", "--since", "2099-01-01"},
            new String[] {"run"},
            new String[] {"early-exit"},
            new String[] {"early-exit", "missing", "1"})) assertEquals(1, run(args));
  }

  @Test
  void earlyExitIsRecordedWithoutSubmittingAndDuplicatesRejected() throws Exception {
    try (var store = new StateStore(root.resolve("state/runtime"))) {
      var c = cycle();
      c.entered = true;
      c.positionIds.add(1L);
      store.state().book.cycles.put(c.key, c);
      store.save();
    }
    assertEquals(0, run("early-exit", "opening", "0.25"));
    try (var store = new StateStore(root.resolve("state/runtime"))) {
      assertEquals(d("0.75"), store.state().earlyExits.getFirst().after());
      assertTrue(store.state().attempts.isEmpty());
    }
    assertEquals(1, run("early-exit", "opening", "1"));
    assertEquals(1, run("early-exit", "opening", "0"));
    assertEquals(1, run("early-exit", "opening", "1.1"));
  }

  @Test
  void sourceToCliPlanInitializeAndRunUseInjectedOfflineConnections() throws Exception {
    var holder = new WorkflowTest();
    var market = holder.new Market();
    List<Boolean> modes = new ArrayList<>();
    var source =
        new BravosSource(
            (m, p, h, b) -> {
              String body;
              if (p.equals("/category/portfolio-update/"))
                body =
                    "<div class=right><a href='/news-feed/cf/'>Initiating Long on CF</a> 09/01/2026</div>";
              else if (p.contains("/page/"))
                body =
                    "<div class=right><a href='/news-feed/old/'>Initiating Long on OLD</a> 01/01/2020</div>";
              else if (p.equals("/research/"))
                body =
                    "<div class=dash-newsletter><p class=h4>Tactical Portfolio</p><div class=asset-ratings><div class=tbody><div class=tr><div class=td>Company ($CF)</div><div class=td>Long</div><div class=td>5</div></div></div></div></div>";
              else
                body =
                    "<article id=post-12><h1>Initiating Long on CF ($CF)</h1><div class=entry-content>09/01/2026<br>We are initiating CF at $100 with a weight of 5.<br>Entry: $100<br>Weight Allocation: 5<br>Suggested Stop Loss (SL): $90</div></article>";
              return new Transport.Response(200, body, Map.of());
            });
    offline =
        (r, c, l) -> {
          modes.add(l);
          return new Main.Connections(market, market, source);
        };
    assertEquals(0, run("plan", "--since", "2026-08-21"), output.toString());
    assertTrue(output.toString().replaceAll("\\s+", " ").contains("CF [READY]"));
    assertTrue(output.toString().replaceAll("\\s+", " ").contains("On live run:"));
    assertTrue(market.submitted.isEmpty());
    assertEquals(0, run("initialize", "--since", "2026-08-21"), output.toString());
    assertTrue(market.submitted.isEmpty());
    assertEquals(1, run("initialize"));
    market.reject = true;
    assertEquals(2, run("run"), output.toString());
    assertTrue(output.toString().contains("[NOT FILLED]"));
    assertFalse(output.toString().contains("[READY]"));
    market.reject = false;
    assertEquals(0, run("run"), output.toString());
    assertTrue(
        output.toString().replaceAll("\\s+", " ").contains("Confirmed by broker read-back: OPEN"));
    assertTrue(output.toString().replaceAll("\\s+", " ").contains("Run completed."));
    assertFalse(output.toString().replaceAll("\\s+", " ").contains("owner USD null"));
    assertEquals(2, market.submitted.size());
    assertEquals(List.of(false, false, true, true), modes);
    output.reset();
    assertEquals(
        0,
        Main.run(
            new String[] {"plan"},
            root,
            Clock.offset(clock, java.time.Duration.ofDays(1)),
            new PrintStream(output),
            offline));
    assertTrue(
        output
            .toString()
            .replaceAll("\\s+", " ")
            .contains("existing holding has no new action to take"));
    assertFalse(output.toString().replaceAll("\\s+", " ").contains("would attempt to open"));
    assertEquals(2, market.submitted.size());
    market.pending = true;
    assertEquals(2, run("run"));
    assertTrue(
        output
            .toString()
            .replaceAll("\\s+", " ")
            .contains("New actions are held until the account can be reconciled"));
    market.pending = false;
    market.copyStopMismatch = true;
    assertEquals(2, run("run"));
    assertTrue(
        output
            .toString()
            .replaceAll("\\s+", " ")
            .contains("does not yet have verified protection"));
    market.copyStopMismatch = false;
    market.lots.add(position(9999, "1", "90", true, false));
    assertEquals(2, run("run"));
    assertTrue(output.toString().replaceAll("\\s+", " ").contains("UNEXPLAINED_AGENT_POSITION"));
    market.lots.removeLast();
    assertEquals(2, market.submitted.size());
    market.unavailable = true;
    try (var store = new StateStore(root.resolve("state/runtime"))) {
      var cycle = store.state().book.cycles.values().iterator().next();
      cycle.events.add(
          alert("later-add", com.loris.bravos.domain.Model.Action.ADD, "100", "5", "6", null));
      store.save();
    }
    assertEquals(0, run("plan"), output.toString());
    assertTrue(output.toString().replaceAll("\\s+", " ").contains("No purchase is proposed"));
    assertTrue(
        output
            .toString()
            .contains("Plan completed with blocked or unresolved items; no orders submitted."));
    assertTrue(output.toString().replaceAll("\\s+", " ").contains("no substitute will be bought"));
    assertFalse(
        output
            .toString()
            .replaceAll("\\s+", " ")
            .contains("existing holding has no new action to take"));
    assertEquals(2, market.submitted.size());
    assertEquals(2, run("run"), output.toString());
    assertTrue(
        output
            .toString()
            .replaceAll("\\s+", " ")
            .contains("Run finished with held or unresolved items."));
    assertFalse(
        output.toString().replaceAll("\\s+", " ").contains("Confirmed by broker read-back:"));
    assertEquals(2, market.submitted.size());
  }

  @Test
  void unexpectedExceptionTextIsNeverEchoed() {
    offline =
        (r, c, l) -> {
          throw new IOException("API key = secret-test");
        };
    assertEquals(1, run("plan"));
    assertFalse(output.toString().replaceAll("\\s+", " ").contains("secret-test"));
    assertTrue(output.toString().replaceAll("\\s+", " ").contains("OPERATION_FAILED"));
  }

  @Test
  void secretFilesLoadWithoutBeingPartOfDiagnosticsAndConfigurationValidates() throws Exception {
    var files =
        Map.of(
            "etoro-bravos-agent/bravos-public-key.txt",
            "app-test",
            "etoro-bravos-agent/bravos-private-key.txt",
            "agent-test",
            "etoro-main-readonly/private-key.txt",
            "owner-test",
            "bravos/username.txt",
            "user-test",
            "bravos/password.txt",
            "password-test");
    assertThrows(IOException.class, () -> Secrets.load(root));
    for (var e : files.entrySet()) {
      Path file = root.resolve("secrets").resolve(e.getKey());
      Files.createDirectories(file.getParent());
      Files.writeString(file, "\ufeff" + e.getValue() + "\n");
    }
    var secret = Secrets.load(root);
    assertEquals("user-test", secret.username());
    assertEquals("password-test", secret.password());
    assertEquals("Secrets[REDACTED]", secret.toString());
    assertEquals("owner-test", secret.headers(true, "reference").get("x-user-key"));
    assertThrows(IllegalArgumentException.class, () -> new Secrets("", "a", "o", "u", "p"));
    var configuration = new Configuration();
    var a = new Configuration.Asset();
    a.instrumentId = 1890;
    a.brokerSymbol = "CF";
    a.unleveragedEvidence = "issuer";
    a.priceScale = 2;
    a.unitScale = 6;
    configuration.assets.put("CF", a);
    Path config = root.resolve("test-config.json");
    Json.MAPPER.writeValue(config.toFile(), configuration);
    assertEquals(1890, Configuration.load(config).assets.get("CF").instrumentId);
    for (String field : List.of("priceScale", "unitScale")) {
      var incomplete = Json.MAPPER.valueToTree(configuration);
      ((com.fasterxml.jackson.databind.node.ObjectNode) incomplete.path("assets").path("CF"))
          .remove(field);
      Json.MAPPER.writeValue(config.toFile(), incomplete);
      assertEquals(
          "INVALID_ASSET_CONFIGURATION",
          assertThrows(IOException.class, () -> Configuration.load(config), field).getMessage());
      ((com.fasterxml.jackson.databind.node.ObjectNode) incomplete.path("assets").path("CF"))
          .putNull(field);
      Json.MAPPER.writeValue(config.toFile(), incomplete);
      assertThrows(IOException.class, () -> Configuration.load(config), field + " null");
    }
    a.priceScale = -1;
    assertThrows(IOException.class, configuration::validate);
    a.priceScale = 2;
    a.unitScale = 13;
    assertThrows(IOException.class, configuration::validate);
    a.unitScale = 6;
    a.settlementType = "marginTrade";
    assertThrows(IOException.class, configuration::validate);
    a.settlementType = "real";
    a.unleveragedEvidence = "";
    assertThrows(IOException.class, configuration::validate);
    a.unleveragedEvidence = "issuer";
    configuration.assets.put("DUP", a);
    assertThrows(IOException.class, configuration::validate);
  }
}
