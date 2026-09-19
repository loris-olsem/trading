package com.loris.bravos.state;

import com.loris.bravos.util.Json;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.UUID;

/** Stable lock inode + forced atomic generations. Never edits the legacy planning ledger. */
public final class StateStore implements AutoCloseable {
  private final Path directory;
  private final FileChannel channel;
  private final FileLock lock;
  private TradingState state;

  public StateStore(Path directory) throws IOException {
    this.directory = directory;
    Files.createDirectories(directory);
    channel =
        FileChannel.open(
            directory.resolve("process.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    FileLock acquired;
    try {
      acquired = channel.tryLock();
    } catch (OverlappingFileLockException e) {
      channel.close();
      throw new IOException("RUN_ALREADY_ACTIVE");
    }
    if (acquired == null) {
      channel.close();
      throw new IOException("RUN_ALREADY_ACTIVE");
    }
    lock = acquired;
    try {
      Path file = directory.resolve("ledger.json");
      if (Files.exists(file)) {
        var root = Json.MAPPER.readTree(file.toFile());
        requireFields(root, Json.MAPPER.valueToTree(new TradingState()));
        requireFields(
            root.get("book"), Json.MAPPER.valueToTree(new com.loris.bravos.domain.SourceBook()));
        for (var c : root.path("book").path("cycles"))
          requireFields(c, Json.MAPPER.valueToTree(new com.loris.bravos.domain.Model.Cycle()));
        for (var a : root.path("attempts"))
          requireFields(a, Json.MAPPER.valueToTree(new TradingState.Attempt()));
        state = Json.MAPPER.treeToValue(root, TradingState.class);
      } else state = new TradingState();
      validate(state);
    } catch (Exception e) {
      close();
      throw new IOException("INVALID_STATE_REQUIRES_RECOVERY");
    }
  }

  public TradingState state() {
    return state;
  }

  public boolean killed() {
    return Files.exists(directory.resolve("KILL"));
  }

  public void save() throws IOException {
    validate(state);
    Path file = directory.resolve("ledger.json");
    if (Files.exists(file)) {
      Path history = directory.resolve("history");
      Files.createDirectories(history);
      Path previous = history.resolve(state.generation + ".json");
      if (Files.exists(previous)) {
        if (Files.mismatch(file, previous) != -1) throw new IOException("HISTORY_COLLISION");
      } else Files.copy(file, previous);
    }
    state.generation++;
    Path stage = directory.resolve("stage-" + UUID.randomUUID() + ".json");
    try {
      byte[] bytes = Json.MAPPER.writeValueAsBytes(state);
      try (FileChannel out =
          FileChannel.open(stage, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) out.write(buffer);
        out.force(true);
      }
      // If atomic replacement is unsupported, fail; do not fall back to truncation.
      Files.move(stage, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      TradingState readback = Json.MAPPER.readValue(file.toFile(), TradingState.class);
      if (readback.generation != state.generation) throw new IOException("STATE_READBACK_FAILED");
    } catch (IOException e) {
      throw new IOException("STATE_COMMIT_FAILED", e);
    }
  }

  private void validate(TradingState value) throws IOException {
    if (value == null
        || value.schemaVersion != 1
        || value.generation < 0
        || value.book == null
        || value.attempts == null
        || value.earlyExits == null
        || value.report == null
        || value.batches == null
        || value.holdings == null
        || value.book.cycles == null
        || value.book.revisions == null
        || value.book.blockers == null) throw new IOException("INVALID_STATE");
    java.util.Set<Long> positions = new java.util.HashSet<>();
    for (var entry : value.book.cycles.entrySet()) {
      var c = entry.getValue();
      if (c == null
          || !entry.getKey().equals(c.key)
          || c.symbol == null
          || c.entry == null
          || c.entry.signum() <= 0
          || c.weight == null
          || c.weight.signum() < 0
          || c.openedOn == null
          || c.events == null
          || c.events.isEmpty()
          || c.completed == null
          || c.additionSessions == null
          || c.positionIds == null) throw new IOException("INVALID_CYCLE");
      for (Long id : c.positionIds)
        if (id == null || id <= 0 || !positions.add(id))
          throw new IOException("DUPLICATE_OR_INVALID_POSITION_LINK");
    }
    java.util.Set<String> references = new java.util.HashSet<>();
    for (var entry : value.attempts.entrySet()) {
      var a = entry.getValue();
      if (a == null
          || a.intent == null
          || a.status == null
          || a.createdAt == null
          || !entry.getKey().equals(a.intent.key())
          || !value.book.cycles.containsKey(a.intent.cycleKey()))
        throw new IOException("BROKEN_INTENT_REFERENCE");
      if (a.absorbedEvents == null
          || a.positionIds == null
          || a.beforeOwnerUnits == null
          || !references.add(a.reference)) throw new IOException("INVALID_ATTEMPT");
      try {
        UUID.fromString(a.reference);
      } catch (Exception e) {
        throw new IOException("INVALID_REFERENCE");
      }
    }
  }

  private static void requireFields(
      com.fasterxml.jackson.databind.JsonNode actual,
      com.fasterxml.jackson.databind.JsonNode template)
      throws IOException {
    if (actual == null || !actual.isObject()) throw new IOException("STATE_OBJECT_REQUIRED");
    var names = template.fieldNames();
    while (names.hasNext())
      if (!actual.has(names.next())) throw new IOException("STATE_FIELD_MISSING");
  }

  @Override
  public void close() throws IOException {
    try {
      lock.release();
    } finally {
      channel.close();
    }
  }
}
