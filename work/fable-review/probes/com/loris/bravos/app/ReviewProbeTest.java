package com.loris.bravos.app;

import static com.loris.bravos.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.loris.bravos.domain.Model.*;
import com.loris.bravos.source.BravosSource;
import com.loris.bravos.state.StateStore;
import com.loris.bravos.state.TradingState.Attempt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Review-only characterizations of defects, NOT assertions of desired behavior. */
class ReviewProbeTest {
  @TempDir Path temp;
  final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  final WorkflowTest fixture = new WorkflowTest();

  void initialize(Workflow workflow) throws IOException {
    workflow.acceptScan(fixture.scan(List.of(cycle().events.getFirst()), true, d("5")),
        LocalDate.of(2026, 9, 1), true);
  }

  Alert xlf(String key, Action action, String before, String after) {
    return new Alert(key, "https://example.invalid/" + key, LocalDate.of(2026, 9, 2),
        key, "XLF", action, d("100"), n(before), n(after), d("90"), List.of());
  }

  @Test void incompleteScanPoisonsBookEvenAfterCorrectedRescanAndRestart() throws Exception {
    var market = fixture.new Market();
    var opening = xlf("xlf-open", Action.OPEN, null, "5");
    var update = xlf("xlf-update", Action.ADD, "5", "6");
    try (var store = new StateStore(temp)) {
      var workflow = new Workflow(store, market, market, clock);
      initialize(workflow);
      var broken = new BravosSource.Scan(List.of(), List.of(update), List.of("PARSE_FAILED"),
          List.of(), false, Map.of("CF", d("5"), "XLF", d("6")));
      assertThrows(IOException.class, () -> workflow.acceptScan(broken, null, false));
    }
    try (var store = new StateStore(temp)) {
      var workflow = new Workflow(store, market, market, clock);
      var corrected = new BravosSource.Scan(List.of(), List.of(opening, update), List.of(),
          List.of(), true, Map.of("CF", d("5"), "XLF", d("6")));
      assertThrows(IOException.class, () -> workflow.acceptScan(corrected, null, false));
      assertTrue(store.state().book.blockers.contains("ORPHAN_UPDATE:xlf-update"));
      assertEquals(d("5"), store.state().book.cycles.get("xlf-open").weight);
    }
  }

  @Test void knownFilledUnprotectedLotNeverReachesProtectionPass() throws Exception {
    var market = fixture.new Market();
    try (var store = new StateStore(temp)) {
      var workflow = new Workflow(store, market, market, clock);
      initialize(workflow);
      market.copyPending = true;
      workflow.evaluate(true);
      market.lots.set(0, position(11, "5", "90", false, false));
      assertEquals(List.of(11L), store.state().book.cycles.get("opening").positionIds);
      assertTrue(workflow.evaluate(true).contains("UNRESOLVED_ORDER_BLOCKS_NEW_SUBMISSIONS"));
      assertTrue(workflow.evaluate(true).contains("UNRESOLVED_ORDER_BLOCKS_NEW_SUBMISSIONS"));
      assertEquals(List.of(Action.OPEN), market.submitted.stream().map(Intent::action).toList());
      assertFalse(market.lots.getFirst().stopEnabled());
    }
  }

  @Test void mismatchedCloseIsAcceptedThenSilentlySkipped() throws Exception {
    var market = fixture.new Market();
    try (var store = new StateStore(temp)) {
      var workflow = new Workflow(store, market, market, clock);
      initialize(workflow);
      workflow.evaluate(true);
      var close = alert("close", Action.CLOSE, "100", "4", "0", null);
      workflow.acceptScan(new BravosSource.Scan(List.of(), List.of(close), List.of(),
          List.of(), true, Map.of()), null, false);
      assertEquals("WEIGHT_CHAIN_MISMATCH", store.state().book.cycles.get("opening").blocker);
      assertTrue(workflow.evaluate(true).isEmpty());
      assertEquals(1, market.submitted.size());
      assertFalse(market.lots.isEmpty());
    }
  }

  @Test void closeCancelledByKillBeforeSubmissionCannotRetryEvenAfterRestart() throws Exception {
    var market = fixture.new Market() {
      @Override public void prepare(Attempt attempt) throws IOException {
        if (reject) Files.writeString(temp.resolve("KILL"), "review fixture");
      }
    };
    try (var store = new StateStore(temp)) {
      var workflow = new Workflow(store, market, market, clock);
      initialize(workflow);
      workflow.evaluate(true);
      workflow.acceptScan(new BravosSource.Scan(List.of(),
          List.of(alert("close", Action.CLOSE, "100", "5", "0", null)), List.of(),
          List.of(), true, Map.of()), null, false);
      market.reject = true;
      workflow.evaluate(true);
      assertEquals(1, market.submitted.size());
      assertTrue(store.state().attempts.values().stream()
          .anyMatch(a -> "KILL_BEFORE_SUBMISSION".equals(a.result)));
    }
    market.reject = false;
    Files.delete(temp.resolve("KILL"));
    try (var store = new StateStore(temp)) {
      var workflow = new Workflow(store, market, market, clock);
      assertTrue(workflow.evaluate(true).stream().anyMatch(s -> s.contains("ORDER_PENDING_OR_REJECTED")));
      assertEquals(1, market.submitted.size());
      assertFalse(market.lots.isEmpty());
      assertFalse(store.state().book.cycles.get("opening").completed.contains("close"));
    }
  }
}
