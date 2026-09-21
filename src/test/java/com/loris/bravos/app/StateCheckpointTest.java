package com.loris.bravos.app;

import static com.loris.bravos.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.loris.bravos.state.StateStore;
import com.loris.bravos.util.Json;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class StateCheckpointTest {
  @TempDir Path root;

  String git(String... args) throws Exception {
    var command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(args));
    var process =
        new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
    String output =
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
    return output;
  }

  @BeforeEach
  void repository() throws Exception {
    git("init");
    git("config", "user.name", "Checkpoint Test");
    git("config", "user.email", "checkpoint@example.invalid");
    git("config", "commit.gpgsign", "false");
    Files.copy(Path.of(".gitignore"), root.resolve(".gitignore"));
    Files.writeString(root.resolve("README.md"), "original\n");
    git("add", ".gitignore", "README.md");
    git("commit", "-m", "fixture repository");
  }

  @Test
  void checkpointsRestoreCompleteStateAndLeaveOtherStagedFilesAlone() throws Exception {
    Path runtime = root.resolve("state/runtime");
    byte[] saved;
    try (var store = new StateStore(runtime)) {
      store.state().book.cycles.put("opening", cycle());
      var intent =
          new com.loris.bravos.domain.Policy()
              .opening(cycle(), instrument(), quote("100"), account(), NOW, d("0"))
              .intents()
              .getFirst();
      var attempt = new com.loris.bravos.state.TradingState.Attempt(intent, NOW);
      attempt.status = com.loris.bravos.state.TradingState.Status.UNKNOWN;
      attempt.orderId = 123L;
      store.state().attempts.put(intent.key(), attempt);
      store.save();
      store.state().report.add("fixture report");
      store.save();
      saved = Files.readAllBytes(runtime.resolve("ledger.json"));
    }
    Files.createDirectories(root.resolve("secrets"));
    Files.writeString(root.resolve("secrets/key.txt"), "fake-secret");
    Files.writeString(runtime.resolve("KILL"), "keep halted");
    Files.writeString(runtime.resolve("stage-incomplete.json"), "not a ledger");
    Files.writeString(runtime.resolve("history/not-state.json"), "not selected");
    Files.writeString(root.resolve("README.md"), "staged owner work\n");
    git("add", "README.md");
    assertTrue(StateCheckpoint.checkpoint(root).contains("generation 2 checkpointed"));
    assertEquals("original\n", git("show", "HEAD:README.md").replace("\r\n", "\n"));
    assertEquals("README.md", git("diff", "--cached", "--name-only").trim());
    String tracked = git("ls-tree", "-r", "--name-only", "HEAD");
    assertTrue(tracked.contains("state/runtime/ledger.json"));
    assertTrue(tracked.contains("state/runtime/history/1.json"));
    for (String excluded :
        List.of("secrets/", "KILL", "process.lock", "stage-", "not-state", "checkpoint-"))
      assertFalse(tracked.contains(excluded), tracked);
    String commit = git("rev-parse", "HEAD");
    assertTrue(StateCheckpoint.checkpoint(root).contains("already checkpointed"));
    assertEquals(commit, git("rev-parse", "HEAD"));
    // Recovery is tested only in this isolated fixture repository.
    Files.delete(runtime.resolve("ledger.json"));
    git("restore", "--source=HEAD", "--worktree", "--", "state/runtime/ledger.json");
    assertEquals(
        Json.MAPPER.readTree(saved), Json.MAPPER.readTree(runtime.resolve("ledger.json").toFile()));
    try (var restored = new StateStore(runtime)) {
      assertEquals(2, restored.state().generation);
      assertEquals("CF", restored.state().book.cycles.get("opening").symbol);
      assertTrue(restored.killed());
      var attempt = restored.state().attempts.values().iterator().next();
      assertEquals(com.loris.bravos.state.TradingState.Status.UNKNOWN, attempt.status);
      assertEquals(123L, attempt.orderId);
      assertNotNull(attempt.reference);
    }
  }

  @Test
  void noStateLockConflictCorruptStateAndGitFailureDoNotResetAnything() throws Exception {
    assertTrue(StateCheckpoint.checkpoint(root).contains("No trading state"));
    Path runtime = root.resolve("state/runtime");
    try (var store = new StateStore(runtime)) {
      store.save();
      assertThrows(java.io.IOException.class, () -> StateCheckpoint.checkpoint(root));
    }
    byte[] valid = Files.readAllBytes(runtime.resolve("ledger.json"));
    Files.writeString(runtime.resolve("ledger.json"), "broken");
    assertThrows(java.io.IOException.class, () -> StateCheckpoint.checkpoint(root));
    assertEquals("broken", Files.readString(runtime.resolve("ledger.json")));
    Files.write(runtime.resolve("ledger.json"), valid);
    Files.writeString(root.resolve(".git/index.lock"), "fixture lock");
    assertThrows(java.io.IOException.class, () -> StateCheckpoint.checkpoint(root));
    assertArrayEquals(valid, Files.readAllBytes(runtime.resolve("ledger.json")));
    Files.delete(root.resolve(".git/index.lock"));
    assertTrue(StateCheckpoint.checkpoint(root).contains("checkpointed"));
    try (var files = Files.list(runtime)) {
      assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith("checkpoint-")));
    }
  }
}
