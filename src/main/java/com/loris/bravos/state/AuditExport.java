package com.loris.bravos.state;

import com.loris.bravos.util.Json;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Git-reviewable source/decision projection. Broker IDs and credentials never leave runtime state.
 */
public final class AuditExport {
  private AuditExport() {}

  public static void write(Path root, TradingState state) throws IOException {
    Path directory = root.resolve("state/bravos");
    Files.createDirectories(directory.resolve("runs"));
    Map<String, Object> projection = new LinkedHashMap<>();
    projection.put("runtimeGeneration", state.generation);
    projection.put("checkpoint", state.checkpoint);
    projection.put("enrollmentFloor", state.enrollmentFloor);
    projection.put("sourceRevisions", state.book.revisions);
    List<Map<String, Object>> cycles = new ArrayList<>();
    for (var c : state.book.cycles.values()) {
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("key", c.key);
      fields.put("symbol", c.symbol);
      fields.put("sourceOpen", c.sourceOpen);
      fields.put("enrolled", c.enrolled);
      fields.put("entered", c.entered);
      fields.put("terminal", c.terminal);
      fields.put("completed", new TreeSet<>(c.completed));
      fields.put("blocker", c.blocker);
      cycles.add(fields);
    }
    projection.put("cycles", cycles);
    projection.put(
        "attempts",
        state.attempts.values().stream()
            .map(
                a ->
                    Map.of(
                        "cycle",
                        a.intent.cycleKey(),
                        "event",
                        a.intent.eventKey(),
                        "action",
                        a.intent.action(),
                        "status",
                        a.status,
                        "result",
                        a.result == null ? "" : a.result))
            .toList());
    byte[] bytes =
        Json.MAPPER
            .writer()
            .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .writeValueAsBytes(projection);
    Files.createDirectories(directory.resolve("history"));
    Path history = directory.resolve("history/runtime-" + state.generation + ".json");
    if (Files.exists(history)) {
      if (!Arrays.equals(bytes, Files.readAllBytes(history)))
        throw new IOException("AUDIT_HISTORY_COLLISION");
    } else Files.write(history, bytes, StandardOpenOption.CREATE_NEW);
    Path staged = directory.resolve("runtime-ledger.stage");
    Files.write(staged, bytes);
    Files.move(
        staged,
        directory.resolve("runtime-ledger.json"),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
    Path report = directory.resolve("runs/runtime-" + state.generation + ".md");
    if (!Files.exists(report))
      Files.writeString(
          report,
          "# Runtime generation "
              + state.generation
              + "\n\n"
              + String.join("\n", state.report.stream().map(s -> "- " + s).toList())
              + "\n");
  }
}
