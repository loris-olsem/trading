package com.loris.bravos.state;

import static com.loris.bravos.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.loris.bravos.domain.Policy;
import com.loris.bravos.util.Json;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditExportTest {
  @TempDir Path root;

  @Test
  void exportRetainsSourceAndDecisionsButOmitsBrokerIdentityAndKeepsRunHistory() throws Exception {
    var s = new TradingState();
    s.generation = 42;
    var c = cycle();
    c.positionIds.add(987654321L);
    s.book.cycles.put(c.key, c);
    s.book.revisions.put("opening", c.events);
    c.completed.add("opening");
    var i =
        new Policy()
            .opening(cycle(), instrument(), quote("100"), account(), NOW, d("0"))
            .intents()
            .getFirst();
    var a = new TradingState.Attempt(i, NOW);
    a.orderId = 99887766L;
    a.result = "UNKNOWN_OUTCOME";
    s.attempts.put(i.key(), a);
    var close =
        new com.loris.bravos.domain.Model.Intent(
            "opening|close|987654321",
            "opening",
            "source-close",
            com.loris.bravos.domain.Model.Action.CLOSE,
            1890,
            987654321L,
            null,
            null,
            d("1"),
            null,
            null,
            null);
    var closeAttempt = new TradingState.Attempt(close, NOW);
    s.attempts.put(close.key(), closeAttempt);
    s.report.add("CF: BLOCKED");
    AuditExport.write(root, s);
    var file = root.resolve("state/bravos/runtime-ledger.json");
    String json = Files.readString(file);
    assertFalse(json.contains(a.reference));
    assertFalse(json.contains("987654321"));
    assertFalse(json.contains("99887766"));
    var value = Json.MAPPER.readTree(json);
    assertEquals(42, value.get("runtimeGeneration").asLong());
    assertEquals("CF", value.get("cycles").get(0).get("symbol").asText());
    assertEquals("UNKNOWN_OUTCOME", value.get("attempts").get(0).get("result").asText());
    assertTrue(value.get("sourceRevisions").has("opening"));
    Path report = root.resolve("state/bravos/runs/runtime-42.md");
    String before = Files.readString(report);
    assertTrue(before.contains("CF: BLOCKED"));
    s.report.clear();
    AuditExport.write(root, s);
    assertEquals(before, Files.readString(report));
    assertEquals(json, Files.readString(root.resolve("state/bravos/history/runtime-42.json")));
    a.result = "DIFFERENT";
    assertThrows(java.io.IOException.class, () -> AuditExport.write(root, s));
    assertEquals(json, Files.readString(file));
  }
}
