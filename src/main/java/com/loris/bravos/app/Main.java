package com.loris.bravos.app;

import com.loris.bravos.broker.*;
import com.loris.bravos.domain.Model.*;
import com.loris.bravos.source.*;
import com.loris.bravos.state.*;
import java.io.*;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** User-run application. With no arguments it displays usage and makes no requests. */
public final class Main {
  @FunctionalInterface
  public interface Login {
    void login() throws IOException;
  }

  public record Connections(
      Workflow.Market market, Broker broker, BravosSource source, Login login) {
    public Connections(Workflow.Market market, Broker broker, BravosSource source) {
      this(market, broker, source, () -> {});
    }
  }

  @FunctionalInterface
  public interface Connector {
    Connections connect(Path root, Clock clock, boolean live) throws IOException;
  }

  private Main() {}

  public static void main(String[] args) {
    int code = run(args, Path.of("").toAbsolutePath(), Clock.systemUTC(), System.out);
    if (code != 0) System.exit(code);
  }

  public static int run(String[] args, Path root, Clock clock, PrintStream out) {
    return run(args, root, clock, out, Main::connect);
  }

  private static Connections connect(Path root, Clock clock, boolean live) throws IOException {
    Configuration config = Configuration.load(root.resolve("config/trading.json"));
    Secrets secrets = Secrets.load(root);
    var broker =
        new EtoroClient(
            new HttpTransport(URI.create("https://public-api.etoro.com"), false),
            secrets,
            config,
            clock,
            live);
    var source =
        new BravosSource(new HttpTransport(URI.create("https://bravosresearch.com"), true));
    return new Connections(
        broker, broker, source, () -> source.login(secrets.username(), secrets.password()));
  }

  public static int run(
      String[] args, Path root, Clock clock, PrintStream out, Connector connector) {
    try {
      if (args.length == 0 || args[0].equals("help")) {
        out.println(
            "Bravos via Gradle (first dot-source: . ./env.ps1):\ngr plan [-Psince=YYYY-MM-DD] | gr initialize [-Psince=YYYY-MM-DD] | gr run | gr status\ngr earlyExit -Pcycle=ID -Pfraction=0.25 | gr brokerDiagnostics | gr capture [-Psince=YYYY-MM-DD] | gr replayCapture\ngr kill | gr resume\nRun submits eligible trades. Plan and initialize never submit. Early-exit records a request for the next run.\nConfiguration: config/trading.json. State: state/runtime. Kill stops further submissions; resume only removes the marker.");
        return 0;
      }
      String command = args[0];
      if (!Set.of("plan", "initialize", "run", "status", "early-exit").contains(command))
        throw new IOException("UNKNOWN_COMMAND");
      LocalDate since = LocalDate.now(clock.withZone(ZoneId.of("Europe/Luxembourg"))).minusDays(29);
      if (command.equals("plan") || command.equals("initialize")) {
        if (args.length == 3 && args[1].equals("--since")) since = LocalDate.parse(args[2]);
        else if (args.length != 1) throw new IOException("INVALID_ARGUMENTS");
        if (since.isAfter(LocalDate.now(clock))) throw new IOException("FUTURE_ENROLLMENT_DATE");
      } else if (!command.equals("early-exit") && args.length != 1)
        throw new IOException("INVALID_ARGUMENTS");
      try (StateStore store = new StateStore(root.resolve("state/runtime"))) {
        if (command.equals("status")) {
          out.println(
              "generation="
                  + store.state().generation
                  + " initialized="
                  + (store.state().enrollmentFloor != null)
                  + " checkpoint="
                  + store.state().checkpoint
                  + " killed="
                  + store.killed());
          store.state().report.forEach(out::println);
          for (var a : store.state().attempts.values())
            out.println(
                a.intent.key()
                    + " "
                    + a.status
                    + " "
                    + a.result
                    + (a.ownerShortfall == null
                        ? ""
                        : " owner USD " + a.ownerFilled + " shortfall USD " + a.ownerShortfall));
          return 0;
        }
        if (command.equals("early-exit")) {
          if (args.length != 3) throw new IOException("EARLY_EXIT_REQUIRES_CYCLE_AND_FRACTION");
          var cycle = store.state().book.cycles.get(args[1]);
          BigDecimal fraction = new BigDecimal(args[2]);
          if (cycle == null
              || !cycle.entered
              || cycle.terminal
              || fraction.signum() <= 0
              || fraction.compareTo(BigDecimal.ONE) > 0)
            throw new IOException("INVALID_EARLY_EXIT");
          if (store.state().earlyExits.stream()
              .anyMatch(e -> e.symbol().equals(cycle.key) && !cycle.completed.contains(e.key())))
            throw new IOException("EARLY_EXIT_ALREADY_PENDING");
          store
              .state()
              .earlyExits
              .add(
                  new Alert(
                      "user:" + UUID.randomUUID(),
                      "user-request",
                      LocalDate.now(clock),
                      "user-request",
                      cycle.key,
                      Action.EARLY_EXIT,
                      null,
                      BigDecimal.ONE,
                      BigDecimal.ONE.subtract(fraction),
                      null,
                      List.of()));
          store.save();
          AuditExport.write(root, store.state());
          out.println("Early exit recorded. The next user-run `run` will process it.");
          return 0;
        }
        if (command.equals("run") && store.state().enrollmentFloor == null)
          throw new IOException("INITIALIZE_REQUIRED");
        if (command.equals("initialize") && store.state().enrollmentFloor != null)
          throw new IOException("ALREADY_INITIALIZED");
        Connections connections = connector.connect(root, clock, command.equals("run"));
        var workflow = new Workflow(store, connections.market(), connections.broker(), clock);
        var source = connections.source();
        workflow.reconcileOnly();
        connections.login().login();
        LocalDate floor =
            store.state().checkpoint == null
                ? since.minusDays(90)
                : store.state().checkpoint.atZone(ZoneId.of("Europe/Luxembourg")).toLocalDate();
        Set<String> urls = new LinkedHashSet<>();
        boolean audit =
            store.state().revisionAudit == null
                || Duration.between(store.state().revisionAudit, clock.instant())
                        .compareTo(Duration.ofDays(7))
                    >= 0;
        for (var c : store.state().book.cycles.values())
          if (c.sourceOpen || c.blocker != null || audit) for (var e : c.events) urls.add(e.url());
        BravosSource.Scan scan = source.scan(floor, urls);
        // Acquisition backfill is separate from the enrollment window.
        for (int retry = 0;
            store.state().checkpoint == null
                && scan.complete()
                && !Workflow.dashboardMatches(
                    Workflow.initialBook(scan.alerts(), since), scan.dashboard())
                && retry < 8;
            retry++) {
          floor = floor.minusDays(90);
          scan = source.scan(floor, urls);
        }
        Instant previousAudit = store.state().revisionAudit;
        workflow.acceptScan(scan, since, command.equals("initialize"));
        if (!audit) store.state().revisionAudit = previousAudit;
        if (command.equals("initialize")) {
          store.save();
          AuditExport.write(root, store.state());
          out.println("Initialized enrollment since " + since + "; no orders submitted.");
          return 0;
        }
        workflow.evaluate(command.equals("run"));
        for (String block : ReportFormatter.blocks(store.state(), command.equals("run"))) {
          out.println(block);
          out.println();
        }
        AuditExport.write(root, store.state());
        boolean blocked =
            store.state().report.stream()
                .anyMatch(
                    line ->
                        line.contains(": BLOCKED ")
                            || line.startsWith("UNPROTECTED_")
                            || line.startsWith("UNRESOLVED_ORDER_")
                            || line.startsWith("UNEXPLAINED_")
                            || line.equals("ACCOUNT_ACTIVITY_UNVERIFIED")
                            || line.endsWith(": COPY_EXIT_PENDING")
                            || line.endsWith(": COPY_PROTECTION_UNVERIFIED")
                            || line.startsWith("ORDER_PENDING_OR_REJECTED"));
        if (command.equals("plan")) {
          out.println(
              blocked
                  ? "Plan completed with blocked or unresolved items; no orders submitted."
                  : "Plan completed; no orders submitted.");
          return 0;
        }
        out.println(
            blocked
                ? "Run finished with held or unresolved items. Only outcomes marked 'Confirmed by broker read-back' are completed; exit code 2 reports the remaining holds."
                : "Run completed. Only outcomes marked 'Confirmed by broker read-back' are completed.");
        return blocked ? 2 : 0;
      }
    } catch (Exception e) {
      // Never print HTTP/library exception bodies or credential-bearing causes.
      String code = e.getMessage();
      out.println(
          code != null && code.matches("[A-Z][A-Z0-9_]{2,100}")
              ? code
              : "OPERATION_FAILED_NO_AUTOMATIC_RETRY");
      return 1;
    }
  }
}
