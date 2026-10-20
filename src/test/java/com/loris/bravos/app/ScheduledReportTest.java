package com.loris.bravos.app;

import static com.loris.bravos.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.loris.bravos.state.TradingState;
import com.loris.bravos.util.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class ScheduledReportTest {
  @TempDir Path temp;

  @Test
  void summaryDistinguishesUncertainRecoveredRejectedAndNewOrdersWithoutRunningTheScheduler()
      throws Exception {
    var script = Files.readString(Path.of("scripts/scheduled-run.ps1"));
    // Execute only the report parser/summary. Toolchains, orders, state, Discord and
    // Windows notifications are outside this fragment and cannot be invoked here.
    var summary =
        script.substring(
            script.indexOf("# --- Summarise"), script.indexOf("# --- Market-calendar"));
    var harness = temp.resolve("summary.ps1");
    Files.writeString(
        harness,
        """
        param([string]$FixtureRoot)
        $ErrorActionPreference = 'Stop'
        $Root = $FixtureRoot
        $log = Join-Path $Root 'report.log'
        $nyLabel = '15:30 New York'
        $operation = 'run'
        $gradleExit = 2
        $DryRun = $false
        """
            + summary
            + "\n@{title=$title;body=$body;result=$result;color=$color;ending=$ending} | ConvertTo-Json -Compress");
    record Case(List<String> lines, String title, String result) {}
    var cases =
        List.of(
            new Case(
                List.of("CF: ORDER_UNCONFIRMED RECONCILIATION_UNAVAILABLE; order 123"),
                "Bravos: order outcome unconfirmed",
                "UNCONFIRMED"),
            new Case(
                List.of("CF: ORDER_UNCONFIRMED SUBMISSION_OUTCOME_UNKNOWN; order null"),
                "Bravos: order outcome unconfirmed",
                "UNCONFIRMED"),
            new Case(
                List.of("CF: ALREADY_CONFIRMED REDUCE; order 123"),
                "Bravos: earlier order(s) confirmed",
                "RECOVERED"),
            new Case(
                List.of("CF: ALREADY_CONFIRMED REDUCE owner USD 12.34; order 123"),
                "Bravos: earlier order(s) confirmed",
                "RECOVERED"),
            new Case(
                List.of("CF: ORDER_REJECTED FULL_CLOSE_REQUEST_REJECTED_NO_FILL; order 123"),
                "Bravos: needs a look",
                "ATTENTION"),
            new Case(
                List.of("CF: ORDER_REJECTED KILL_BEFORE_SUBMISSION; order null"),
                "Bravos: needs a look",
                "ATTENTION"),
            new Case(List.of("CF: CONFIRMED REDUCE"), "Bravos: 1 trade(s) confirmed", "TRADED"),
            new Case(
                List.of("CF: CONFIRMED REDUCE", "CF: ORDER_UNCONFIRMED AWAITING_FILL; order 456"),
                "Bravos: order outcome unconfirmed",
                "UNCONFIRMED"),
            new Case(
                List.of("CF: NOT_FILLED CONFIRMED_NO_FILL Rejected; order 123"),
                "Bravos: needs a look",
                "ATTENTION"),
            new Case(List.of("CF: HOLD_UNCHANGED"), "Bravos: nothing to trade", "NOTHING"));
    for (var fixture : cases) {
      var state = new TradingState();
      state.book.cycles.put("opening", cycle());
      state.report.addAll(fixture.lines());
      var log =
          String.join("\n\n", ReportFormatter.blocks(state, true))
              + "\n\nRun finished with held or unresolved items.\n";
      Files.writeString(temp.resolve("report.log"), log);
      var process =
          new ProcessBuilder(
                  "powershell.exe",
                  "-NoProfile",
                  "-NonInteractive",
                  "-ExecutionPolicy",
                  "Bypass",
                  "-File",
                  harness.toString(),
                  temp.toString())
              .redirectErrorStream(true)
              .start();
      try {
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Offline summary timed out");
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        var result = Json.MAPPER.readTree(output);
        assertTrue(result.path("title").asText().startsWith(fixture.title()), output);
        assertEquals(fixture.result(), result.path("result").asText(), output);
        assertFalse(result.path("title").asText().contains("no trades"), output);
        if (fixture.result().equals("UNCONFIRMED")) {
          assertEquals(16753920, result.path("color").asInt());
          assertTrue(result.path("body").asText().contains("May have executed"));
          assertEquals(
              "Run finished; execution or account checks need review.",
              result.path("ending").asText());
        } else if (fixture.result().equals("ATTENTION")) {
          assertFalse(result.path("body").asText().contains("May have executed"));
          assertEquals(
              fixture.lines().getFirst().contains("NOT_FILLED")
                  ? "Run finished; some orders were not filled."
                  : "Run finished; execution or account checks need review.",
              result.path("ending").asText());
        } else {
          assertEquals(
              "Run finished; some items are held (blocked/waiting items above are expected).",
              result.path("ending").asText());
        }
        if (fixture.lines().getFirst().contains("12.34"))
          assertTrue(result.path("body").asText().contains("12.34"));
      } finally {
        if (process.isAlive()) process.destroyForcibly();
      }
    }
  }
}
