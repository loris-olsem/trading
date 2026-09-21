package com.loris.bravos.app;

import com.loris.bravos.state.StateStore;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Local Git recovery checkpoints. Never reads credentials or contacts a remote/broker. */
public final class StateCheckpoint {
  private StateCheckpoint() {}

  public static void main(String[] args) {
    try {
      System.out.println(checkpoint(Path.of("").toAbsolutePath()));
    } catch (Exception failure) {
      System.err.println(
          "State checkpoint failed. The on-disk trading state is retained; this does not undo any trades. Resolve the local Git/lock problem and retry gr checkpointState.");
      System.exit(1);
    }
  }

  public static String checkpoint(Path root) throws IOException, InterruptedException {
    Path runtime = root.resolve("state/runtime");
    if (!Files.exists(runtime.resolve("ledger.json")))
      return "No trading state exists to checkpoint.";
    if (Files.isSymbolicLink(runtime) || Files.isSymbolicLink(runtime.resolve("ledger.json")))
      throw new IOException("STATE_CHECKPOINT_SYMLINK");
    try (var store = new StateStore(runtime)) {
      if (!Files.isSameFile(root, Path.of(git(root, "rev-parse", "--show-toplevel").trim())))
        throw new IOException("STATE_CHECKPOINT_REPOSITORY_MISMATCH");
      List<String> paths = new ArrayList<>(List.of("state/runtime/ledger.json"));
      Path history = runtime.resolve("history");
      if (Files.isSymbolicLink(history)) throw new IOException("STATE_CHECKPOINT_SYMLINK");
      if (Files.isDirectory(history)) {
        try (var files = Files.list(history)) {
          for (Path file : files.sorted().toList()) {
            if (!file.getFileName().toString().matches("[0-9]+\\.json")) continue;
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file))
              throw new IOException("STATE_CHECKPOINT_SYMLINK");
            paths.add("state/runtime/history/" + file.getFileName());
          }
        }
      }
      Path pathspec = Files.createTempFile(runtime, "checkpoint-paths-", ".tmp");
      try {
        Files.writeString(pathspec, String.join("\0", paths) + "\0");
        git(
            root,
            "add",
            "--pathspec-from-file=" + pathspec.toAbsolutePath(),
            "--pathspec-file-nul");
        Set<String> selected = new HashSet<>(paths);
        String staged = git(root, "diff", "--cached", "--name-only", "-z");
        if (Arrays.stream(staged.split("\0")).noneMatch(selected::contains))
          return "Trading state is already checkpointed in local Git.";
        git(
            root,
            "commit",
            "--only",
            "-m",
            "Checkpoint trading state generation " + store.state().generation,
            "--pathspec-from-file=" + pathspec.toAbsolutePath(),
            "--pathspec-file-nul");
        return "Trading state generation "
            + store.state().generation
            + " checkpointed in local Git; nothing pushed.";
      } finally {
        Files.deleteIfExists(pathspec);
      }
    }
  }

  private static String git(Path root, String... args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of("git", "--literal-pathspecs"));
    command.addAll(List.of(args));
    Path output = Files.createTempFile(root.resolve("state/runtime"), "checkpoint-git-", ".tmp");
    Process process = null;
    try {
      process =
          new ProcessBuilder(command)
              .directory(root.toFile())
              .redirectOutput(output.toFile())
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!process.waitFor(30, TimeUnit.SECONDS))
        throw new IOException("STATE_CHECKPOINT_GIT_TIMEOUT");
      if (process.exitValue() != 0 || Files.size(output) > 4_000_000)
        throw new IOException("STATE_CHECKPOINT_GIT_FAILED");
      return Files.readString(output);
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
      Files.deleteIfExists(output);
    }
  }
}
