package com.loris.bravos.app;

import static com.loris.bravos.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.loris.bravos.domain.Model.*;
import com.loris.bravos.source.BravosSource;
import com.loris.bravos.state.StateStore;
import com.loris.bravos.state.TradingState.*;
import com.loris.bravos.util.Json;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryTest {
  @TempDir Path temp;
  final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  final WorkflowTest fixture = new WorkflowTest();

  void initialize(Workflow workflow) throws IOException {
    workflow.acceptScan(
        fixture.scan(List.of(cycle().events.getFirst()), true, d("5")),
        LocalDate.of(2026, 9, 1),
        true);
  }

  Alert xlf(String key, Action action, String before, String after) {
    return new Alert(
        key,
        "https://example.invalid/" + key,
        LocalDate.of(2026, 9, 2),
        key,
        "XLF",
        action,
        d("100"),
        n(before),
        n(after),
        d("90"),
        List.of());
  }

  @Test
  void incompleteScanDoesNotPoisonBookAndCorrectedScanPreservesParticipation() throws Exception {
    var market = fixture.new Market();
    var opening = xlf("bravos:post:999", Action.OPEN, null, "5");
    var update = xlf("bravos:post:1000", Action.ADD, "5", "6");
    String reference;
    try (var store = new StateStore(temp)) {
      var workflow = new Workflow(store, market, market, clock);
      initialize(workflow);
      workflow.evaluate(true);
      reference = store.state().attempts.values().iterator().next().reference;
      var broken =
          new BravosSource.Scan(
              List.of(),
              List.of(update),
              List.of("PARSE_FAILED"),
              List.of(),
              false,
              Map.of("CF", d("5"), "XLF", d("6")));
      assertThrows(IOException.class, () -> workflow.acceptScan(broken, null, false));
      assertFalse(store.state().book.revisions.containsKey(update.key()));
      assertEquals(NOW, store.state().checkpoint);
      try (var evidence = Files.list(temp.resolve("source-review"))) {
        var saved = Json.MAPPER.readTree(evidence.findFirst().orElseThrow().toFile());
        assertEquals(update.key(), saved.path("observations").get(0).path("key").asText());
        assertTrue(saved.path("reasons").toString().contains("PARSE_FAILED"));
      }
    }
    try (var store = new StateStore(temp)) {
      var workflow = new Workflow(store, market, market, clock);
      var corrected =
          new BravosSource.Scan(
              List.of(),
              List.of(update, opening),
              List.of(),
              List.of(),
              true,
              Map.of("CF", d("5"), "XLF", d("6")));
      workflow.acceptScan(corrected, null, false);
      assertTrue(store.state().book.blockers.isEmpty());
      assertEquals(d("6"), store.state().book.cycles.get(opening.key()).weight);
      assertEquals(reference, store.state().attempts.values().iterator().next().reference);
      var held = store.state().book.cycles.get("opening");
      assertTrue(held.entered);
      assertEquals(List.of(11L), held.positionIds);
      assertTrue(held.completed.contains("opening"));
      assertNotNull(store.state().holdings.get(11L));
      var durable =
          Json.MAPPER.readValue(
              temp.resolve("ledger.json").toFile(), com.loris.bravos.state.TradingState.class);
      assertEquals(d("6"), durable.book.cycles.get(opening.key()).weight);
      assertFalse(durable.report.contains("PARSE_FAILED"));
    }
  }

  @Test
  void existingLegacyOrphanCanHealButRejectedCandidateDoesNotChangeAcceptedBook() throws Exception {
    var market = fixture.new Market();
    var opening = xlf("bravos:post:999", Action.OPEN, null, "5");
    var update = xlf("bravos:post:1000", Action.ADD, "5", "6");
    try (var store = new StateStore(temp)) {
      var workflow = new Workflow(store, market, market, clock);
      initialize(workflow);
      store.state().book.apply(List.of(update), store.state().enrollmentFloor);
      store.save();
    }
    try (var store = new StateStore(temp)) {
      var workflow = new Workflow(store, market, market, clock);
      workflow.acceptScan(
          new BravosSource.Scan(
              List.of(),
              List.of(opening, update),
              List.of(),
              List.of(),
              true,
              Map.of("CF", d("5"), "XLF", d("6"))),
          null,
          false);
      assertTrue(store.state().book.blockers.isEmpty());
      assertEquals(2, store.state().book.cycles.get(opening.key()).events.size());
      var invalid =
          new BravosSource.Scan(
              List.of(),
              List.of(xlf("overlap", Action.OPEN, null, "7")),
              List.of(),
              List.of(),
              true,
              Map.of("CF", d("5"), "XLF", d("7")));
      assertThrows(IOException.class, () -> workflow.acceptScan(invalid, null, false));
      assertFalse(store.state().book.revisions.containsKey("overlap"));
      assertEquals(d("6"), store.state().book.cycles.get(opening.key()).weight);
      var durable =
          Json.MAPPER.readValue(
              temp.resolve("ledger.json").toFile(), com.loris.bravos.state.TradingState.class);
      assertTrue(durable.report.stream().anyMatch(s -> s.contains("OVERLAPPING_OPENING")));
      try (var evidence = Files.list(temp.resolve("source-review"))) {
        var saved = Json.MAPPER.readTree(evidence.findFirst().orElseThrow().toFile());
        assertEquals("overlap", saved.path("observations").get(0).path("key").asText());
      }
    }
  }

  class RepairMarket extends WorkflowTest.Market {
    boolean unknown;
    String scenario = "normal";

    RepairMarket() {
      fixture.super();
    }

    @Override
    public Observation observe(Attempt attempt) {
      if ((attempt.intent.action() == Action.OPEN || attempt.intent.action() == Action.ADD)
          && (!lots.stream()
                  .filter(p -> p.id() == 10 + attempt.orderId)
                  .findFirst()
                  .orElseThrow()
                  .stopEnabled()
              || scenario.equals("copyOnly")))
        return new Observation(
            unknown ? Status.UNKNOWN : Status.PARTIAL,
            scenario.equals("wrongReason") ? "COPY_AMOUNT_MISMATCH" : "COPY_OR_STOP_NOT_CONFIRMED",
            List.of(10 + attempt.orderId));
      return super.observe(attempt);
    }

    @Override
    public Account account() {
      Account a = super.account();
      var agents = a.agentPositions();
      var copies = a.ownerPositions();
      if (!agents.isEmpty()) {
        if (scenario.equals("missingAgent")) agents = List.of();
        else if (scenario.equals("shortAgent") || scenario.equals("wrongAgent"))
          agents =
              List.of(
                  alter(
                      agents.getFirst(),
                      scenario.equals("shortAgent"),
                      scenario.equals("wrongAgent"),
                      false));
        if (scenario.equals("missingCopy")) copies = List.of();
        else if (scenario.equals("shortCopy")
            || scenario.equals("wrongCopy")
            || scenario.equals("copyOnly"))
          copies =
              List.of(
                  alter(
                      copies.getFirst(),
                      scenario.equals("shortCopy"),
                      scenario.equals("wrongCopy"),
                      true));
      }
      return new Account(
          scenario.equals("boundary")
              ? NOW.minusSeconds(60)
              : scenario.equals("stale")
                  ? NOW.minusSeconds(61)
                  : scenario.equals("future") ? NOW.plusSeconds(1) : NOW,
          a.ownerEquity(),
          a.ownerCash(),
          a.agentEquity(),
          a.agentCash(),
          !scenario.equals("incomplete"),
          scenario.equals("pending"),
          !scenario.equals("inactive"),
          true,
          true,
          agents,
          copies);
    }

    Position alter(Position p, boolean shortPosition, boolean wrongAsset, boolean noStop) {
      return new Position(
          p.id(),
          p.parentId(),
          wrongAsset ? 123 : p.instrumentId(),
          p.units(),
          p.amount(),
          p.pnl(),
          p.stop(),
          noStop ? false : p.stopEnabled(),
          p.trailing(),
          !shortPosition);
    }

    @Override
    public Receipt submit(Intent intent, String reference) {
      var receipt = super.submit(intent, reference);
      if (intent.action() == Action.OPEN || intent.action() == Action.ADD) {
        var added = lots.getLast();
        lots.set(lots.size() - 1, position(added.id(), "5", "90", false, false));
      }
      return receipt;
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missingAgent",
        "missingCopy",
        "shortAgent",
        "wrongAgent",
        "shortCopy",
        "wrongCopy",
        "pending",
        "inactive",
        "incomplete",
        "stale",
        "future",
        "copyOnly",
        "changedStop",
        "blockedCycle",
        "wrongReason"
      })
  void recoveryNeverWritesWithoutMatchingFreshEvidence(String scenario) throws Exception {
    var market = new RepairMarket();
    try (var store = new StateStore(temp)) {
      var workflow = new Workflow(store, market, market, clock);
      initialize(workflow);
      workflow.evaluate(true);
      market.scenario = scenario;
      if (scenario.equals("copyOnly")) market.lots.set(0, position(11, "5", "90", true, false));
      if (scenario.equals("changedStop")) store.state().book.cycles.get("opening").stop = d("95");
      if (scenario.equals("blockedCycle"))
        store.state().book.cycles.get("opening").blocker = "AMBIGUOUS";
      var report = workflow.evaluate(true);
      assertTrue(report.contains("UNRESOLVED_ORDER_BLOCKS_NEW_SUBMISSIONS"));
      if (scenario.equals("copyOnly"))
        assertTrue(report.stream().anyMatch(s -> s.startsWith("UNPROTECTED_OWNER_POSITION 1011")));
      if (scenario.equals("missingAgent") || scenario.equals("missingCopy"))
        assertTrue(
            report.stream()
                .anyMatch(s -> s.startsWith("UNPROTECTED_POSITION_LINK_UNVERIFIED agent=11")));
      assertEquals(1, market.submitted.size());
    }
  }

  @Test
  void knownUnprotectedFillIsRepairedWithoutAnyAdditionalPurchase() throws Exception {
    var market = new RepairMarket();
    try (var store = new StateStore(temp)) {
      var workflow = new Workflow(store, market, market, clock);
      initialize(workflow);
      workflow.evaluate(true);
      var plan = workflow.evaluate(false);
      assertTrue(plan.stream().anyMatch(s -> s.startsWith("UNPROTECTED_AGENT_POSITION 11")));
      assertEquals(1, market.submitted.size());
      var repaired = workflow.evaluate(true);
      assertTrue(repaired.stream().anyMatch(s -> s.equals("PROTECTION_REPAIR 11 CONFIRMED")));
      assertEquals(
          List.of(Action.OPEN, Action.STOP),
          market.submitted.stream().map(Intent::action).toList());
      assertTrue(
          store.state().attempts.values().stream().allMatch(a -> a.status == Status.CONFIRMED));
      assertTrue(market.lots.getFirst().stopEnabled());
    }
    try (var store = new StateStore(temp)) {
      new Workflow(store, market, market, clock).evaluate(true);
      assertEquals(2, market.submitted.size());
    }
  }

  @Test
  void additionProtectionRecoversAtFreshnessBoundaryWithoutRepeatingTheAdd() throws Exception {
    var market = new RepairMarket();
    try (var store = new StateStore(temp)) {
      var workflow = new Workflow(store, market, market, clock);
      initialize(workflow);
      workflow.evaluate(true);
      workflow.evaluate(true);
      var cycle = store.state().book.cycles.get("opening");
      cycle.events.add(alert("add", Action.ADD, "100", "5", "6", null));
      cycle.weight = d("6");
      workflow.evaluate(true);
      market.scenario = "boundary";
      workflow.evaluate(true);
      assertEquals(
          List.of(Action.OPEN, Action.STOP, Action.ADD, Action.STOP),
          market.submitted.stream().map(Intent::action).toList());
      assertTrue(cycle.completed.contains("add"));
      workflow.evaluate(true);
      assertEquals(4, market.submitted.size());
    }
  }

  @Test
  void unknownFillCannotAuthorizeProtectionWrite() throws Exception {
    var market = new RepairMarket();
    market.unknown = true;
    try (var store = new StateStore(temp)) {
      var workflow = new Workflow(store, market, market, clock);
      initialize(workflow);
      workflow.evaluate(true);
      assertTrue(
          workflow.evaluate(true).stream()
              .anyMatch(s -> s.startsWith("UNPROTECTED_AGENT_POSITION")));
      assertEquals(1, market.submitted.size());
    }
  }

  @Test
  void mismatchedCloseReportsRetainedExposureInsteadOfSilentlySkipping() throws Exception {
    var market = fixture.new Market();
    try (var store = new StateStore(temp)) {
      var workflow = new Workflow(store, market, market, clock);
      initialize(workflow);
      workflow.evaluate(true);
      var close = alert("close", Action.CLOSE, "100", "4", "0", null);
      workflow.acceptScan(
          new BravosSource.Scan(List.of(), List.of(close), List.of(), List.of(), true, Map.of()),
          null,
          false);
      var report = workflow.evaluate(true);
      assertTrue(
          report.stream()
              .anyMatch(
                  s ->
                      s.contains("BLOCKED WEIGHT_CHAIN_MISMATCH")
                          && s.contains("action=CLOSE")
                          && s.contains("retainedAgentUnits=5")));
      assertEquals(1, market.submitted.size());
      assertFalse(market.lots.isEmpty());
    }
  }

  @Test
  void closeCancelledByKillCanResumeWithFreshReferenceAfterRestart() throws Exception {
    var market =
        fixture.new Market() {
          @Override
          public void prepare(Attempt attempt) throws IOException {
            if (reject) Files.writeString(temp.resolve("KILL"), "fixture");
          }
        };
    String rejectedReference;
    try (var store = new StateStore(temp)) {
      var workflow = new Workflow(store, market, market, clock);
      initialize(workflow);
      workflow.evaluate(true);
      workflow.acceptScan(
          new BravosSource.Scan(
              List.of(),
              List.of(alert("close", Action.CLOSE, "100", "5", "0", null)),
              List.of(),
              List.of(),
              true,
              Map.of()),
          null,
          false);
      market.reject = true;
      workflow.evaluate(true);
      assertEquals(1, market.submitted.size());
      rejectedReference =
          store.state().attempts.values().stream()
              .filter(a -> "KILL_BEFORE_SUBMISSION".equals(a.result))
              .findFirst()
              .orElseThrow()
              .reference;
    }
    market.reject = false;
    Files.delete(temp.resolve("KILL"));
    try (var store = new StateStore(temp)) {
      var workflow = new Workflow(store, market, market, clock);
      workflow.evaluate(true);
      assertEquals(2, market.submitted.size());
      assertTrue(market.lots.isEmpty());
      assertTrue(store.state().book.cycles.get("opening").completed.contains("close"));
      var retry =
          store.state().attempts.values().stream()
              .filter(a -> a.intent.key().endsWith("|retry:1"))
              .findFirst()
              .orElseThrow();
      assertNotEquals(rejectedReference, retry.reference);
      workflow.evaluate(true);
      assertEquals(2, market.submitted.size());
    }
  }
}
