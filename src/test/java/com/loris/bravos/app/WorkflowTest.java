package com.loris.bravos.app;

import static com.loris.bravos.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.loris.bravos.broker.*;
import com.loris.bravos.domain.Model.*;
import com.loris.bravos.source.BravosSource;
import com.loris.bravos.state.*;
import com.loris.bravos.state.TradingState.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class WorkflowTest {
  @TempDir Path temp;
  final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void marketModeSurvivesAttemptNumberingRestartAndDoesNotBuyAgain() throws Exception {
    var market =
        new Market() {
          @Override
          public String entryOrderType() {
            return "mkt";
          }
        };
    try (var store = new StateStore(temp)) {
      var workflow = new Workflow(store, market, market, clock);
      workflow.acceptScan(
          scan(List.of(cycle().events.getFirst()), true, d("5")), LocalDate.of(2026, 9, 1), true);
      workflow.evaluate(false);
      assertTrue(market.submitted.isEmpty());
      assertTrue(ReportFormatter.blocks(store.state(), false).toString().contains("Market buy"));
      workflow.evaluate(true);
      assertEquals(1, market.submitted.size());
      assertEquals("mkt", market.submitted.getFirst().orderType());
      assertEquals(d("500.00"), market.submitted.getFirst().agentAmount());
    }
    try (var store = new StateStore(temp)) {
      assertEquals("mkt", store.state().attempts.values().iterator().next().intent.orderType());
      new Workflow(store, market, market, clock).evaluate(true);
      assertEquals(1, market.submitted.size());
    }
  }

  class Market implements Workflow.Market, Broker {
    List<Position> lots = new ArrayList<>();
    List<Intent> submitted = new ArrayList<>();
    boolean pending, copyPending, unavailable, reject, finalPartial, copyStopMismatch;

    public Account account() {
      return new Account(
          NOW,
          d("4610"),
          d("4610"),
          d("10000"),
          d("10000"),
          true,
          pending,
          true,
          true,
          true,
          List.copyOf(lots),
          lots.stream()
              .map(
                  p ->
                      new Position(
                          p.id() + 1000,
                          p.id(),
                          p.instrumentId(),
                          p.units(),
                          p.amount(),
                          p.pnl(),
                          copyStopMismatch ? d("89") : p.stop(),
                          p.stopEnabled(),
                          p.trailing(),
                          p.longOnly()))
              .toList());
    }

    public Instrument instrument(String s, BigDecimal amount) throws IOException {
      var i = com.loris.bravos.Fixtures.instrument();
      return unavailable
          ? null
          : new Instrument(
              i.id(),
              s,
              i.currency(),
              i.unleveraged(),
              i.eligible(),
              i.settlementType(),
              i.priceScale(),
              i.unitScale(),
              i.minimumAgentAmount(),
              i.estimatedOwnerCost());
    }

    public Quote quote(Instrument i) throws IOException {
      return com.loris.bravos.Fixtures.quote("100");
    }

    public int unitScale(String symbol) {
      return 4;
    }

    public Receipt submit(Intent i, String reference) {
      submitted.add(i);
      if (reject) return new Receipt((long) submitted.size());
      if (i.action() == Action.OPEN || i.action() == Action.ADD)
        lots.add(position(10 + submitted.size(), "5", i.stop().toString(), true, false));
      else if (i.action() == Action.STOP) {
        Position p = lots.stream().filter(x -> x.id() == i.positionId()).findFirst().orElseThrow();
        lots.remove(p);
        lots.add(position(p.id(), p.units().toString(), i.stop().toString(), true, false));
      } else {
        Position p = lots.stream().filter(x -> x.id() == i.positionId()).findFirst().orElseThrow();
        lots.remove(p);
        if (p.units().compareTo(i.units()) > 0)
          lots.add(
              position(
                  p.id(),
                  p.units().subtract(i.units()).toString(),
                  p.stop().toString(),
                  true,
                  false));
      }
      return new Receipt((long) submitted.size());
    }

    public Observation observe(Attempt a) {
      if (reject) return new Observation(Status.REJECTED, "CONFIRMED_NO_FILL", List.of());
      if (copyPending) return new Observation(Status.PARTIAL, "pending", List.of(11L));
      var i = a.intent;
      if (finalPartial && (i.action() == Action.OPEN || i.action() == Action.ADD))
        return new Observation(
            Status.CONFIRMED,
            "PARTIAL_FILL_KEPT_AND_PROTECTED",
            List.of(10 + a.orderId),
            d("100"),
            d("46.10"),
            i.ownerAmount().subtract(d("46.10")));
      return new Observation(
          Status.CONFIRMED,
          "proved",
          i.positionId() == null
              ? List.of(10 + a.orderId)
              : lots.stream().filter(p -> p.id() == i.positionId()).map(Position::id).toList());
    }
  }

  BravosSource.Scan scan(List<Alert> alerts, boolean complete, BigDecimal weight) {
    return new BravosSource.Scan(
        List.of(),
        alerts,
        complete ? List.of() : List.of("READ_FAILED"),
        List.of(),
        complete,
        Map.of("CF", weight));
  }

  @Test
  void unchangedHoldingIsReportedAlongsideAnotherEntryWithoutCallingThatEntryUnchanged()
      throws Exception {
    var market = new Market();
    try (var store = new StateStore(temp)) {
      var workflow = new Workflow(store, market, market, clock);
      workflow.acceptScan(
          scan(List.of(cycle().events.getFirst()), true, d("5")), LocalDate.of(2026, 9, 1), true);
      workflow.evaluate(true);
      var original = cycle().events.getFirst();
      var second =
          new Alert(
              "second",
              original.url(),
              original.date(),
              original.hash(),
              "ABC",
              Action.OPEN,
              original.price(),
              original.before(),
              original.after(),
              original.stop(),
              original.targets());
      store.state().book.cycles.put("second", new Cycle(second, true));
      var report = workflow.evaluate(false);
      assertTrue(report.contains("CF: HOLD_UNCHANGED"));
      assertTrue(report.contains("ABC: READY POLICY_PASSED"));
      assertFalse(report.contains("ABC: HOLD_UNCHANGED"));
      assertEquals(1, market.submitted.size());
    }
  }

  @Test
  void quoteWaitReportNamesTheObservedFailureAndDoesNotSubmit() throws Exception {
    List<Quote> quotes =
        Arrays.asList(
            null,
            new Quote(d("100"), NOW.minusSeconds(90), true, "USD"),
            new Quote(d("100"), NOW.plusSeconds(1), true, "USD"),
            new Quote(d("100"), NOW, false, "EUR"),
            new Quote(d("100"), NOW.minusSeconds(60), true, "USD"));
    List<String> expected =
        List.of(
            "no quote was returned",
            "90.0 seconds old",
            "dated in the future",
            "currency is not USD",
            "READY POLICY_PASSED");
    for (int index = 0; index < quotes.size(); index++) {
      Quote returned = quotes.get(index);
      var market =
          new Market() {
            public Quote quote(Instrument instrument) {
              return returned;
            }
          };
      try (var store = new StateStore(temp.resolve("quote-" + index))) {
        var workflow = new Workflow(store, market, market, clock);
        workflow.acceptScan(
            scan(List.of(cycle().events.getFirst()), true, d("5")), LocalDate.of(2026, 9, 1), true);
        String report = String.join(" ", workflow.evaluate(false));
        assertTrue(report.contains(expected.get(index)), report);
        if (index == 3)
          assertTrue(report.contains("market-hours, realtime and broker-tradability"));
        assertTrue(market.submitted.isEmpty());
      }
    }
  }

  @Test
  void copiedPriceBreachStopsPurchasesAcrossRestartWithoutCorrectiveSale() throws Exception {
    var market =
        new Market() {
          @Override
          public Observation observe(Attempt attempt) {
            return new Observation(Status.UNKNOWN, "COPIED_PRICE_CEILING_BREACHED", List.of(11L));
          }
        };
    try (var store = new StateStore(temp)) {
      var workflow = new Workflow(store, market, market, clock);
      workflow.acceptScan(
          scan(List.of(cycle().events.getFirst()), true, d("5")), LocalDate.of(2026, 9, 1), true);
      workflow.evaluate(true);
      assertEquals(1, market.submitted.size());
      assertEquals(Action.OPEN, market.submitted.getFirst().action());
      assertTrue(
          store.state().attempts.values().stream()
              .anyMatch(
                  a ->
                      a.status == Status.UNKNOWN
                          && a.result.equals("COPIED_PRICE_CEILING_BREACHED")));
    }
    try (var store = new StateStore(temp)) {
      var workflow = new Workflow(store, market, market, clock);
      assertTrue(workflow.evaluate(true).contains("UNRESOLVED_ORDER_BLOCKS_NEW_SUBMISSIONS"));
      assertEquals(1, market.submitted.size());
    }
  }

  @Test
  void multiLotTrimResumesOriginalBatchWithoutTrimmingFirstLotTwice() throws Exception {
    var market = new Market();
    try (var store = new StateStore(temp)) {
      var w = new Workflow(store, market, market, clock);
      w.acceptScan(
          scan(List.of(cycle().events.getFirst()), true, d("5")), LocalDate.of(2026, 9, 1), true);
      w.evaluate(true);
      var c = store.state().book.cycles.get("opening");
      c.events.add(alert("add", Action.ADD, "100", "5", "8", null));
      c.weight = d("8");
      w.evaluate(true);
      assertEquals(2, market.lots.size());
      c.events.add(alert("trim", Action.REDUCE, "100", "8", "4", null));
      c.weight = d("4");
      market.copyPending = true;
      assertTrue(w.evaluate(true).stream().anyMatch(s -> s.contains("ORDER_PENDING_OR_REJECTED")));
      assertEquals(3, market.submitted.size());
      assertFalse(c.completed.contains("trim"));
      assertEquals(2, store.state().batches.get("opening|trim|REDUCE").size());
    }
    market.copyPending = false;
    try (var store = new StateStore(temp)) {
      var w = new Workflow(store, market, market, clock);
      w.evaluate(true);
      assertEquals(4, market.submitted.size());
      assertTrue(store.state().book.cycles.get("opening").completed.contains("trim"));
      assertTrue(market.lots.stream().allMatch(p -> p.units().compareTo(d("2.5")) == 0));
      w.evaluate(true);
      assertEquals(4, market.submitted.size());
    }
  }

  @Test
  void terminalPartialFillSurvivesRestartWithoutTopUpAndNewAddRemainsPossible() throws Exception {
    var market = new Market();
    market.finalPartial = true;
    try (var store = new StateStore(temp)) {
      var w = new Workflow(store, market, market, clock);
      w.acceptScan(
          scan(List.of(cycle().events.getFirst()), true, d("5")), LocalDate.of(2026, 9, 1), true);
      var report = w.evaluate(true);
      assertTrue(report.stream().anyMatch(s -> s.contains("shortfall USD 184.40")));
      assertTrue(report.contains("CF: CONFIRMED OPEN owner USD 46.10"));
      var a = store.state().attempts.values().iterator().next();
      assertEquals(d("46.10"), a.ownerFilled);
      assertEquals(d("100"), a.agentFilled);
      assertEquals(d("184.40"), a.ownerShortfall);
    }
    try (var store = new StateStore(temp)) {
      var w = new Workflow(store, market, market, clock);
      w.evaluate(true);
      assertEquals(1, market.submitted.size());
      var c = store.state().book.cycles.get("opening");
      assertTrue(c.completed.contains("opening"));
      c.events.add(alert("new-add", Action.ADD, "100", "5", "7", null));
      c.weight = d("7");
      market.finalPartial = false;
      w.evaluate(true);
      assertEquals(2, market.submitted.size());
      assertEquals(Action.ADD, market.submitted.getLast().action());
      assertEquals(d("92.20"), market.submitted.getLast().ownerAmount());
    }
  }

  @Test
  void sourceExposureOrderIsGlobalAcrossCyclesAndMissedAddIsSuppressedInPlan() throws Exception {
    var market = new Market();
    try (var store = new StateStore(temp)) {
      var w = new Workflow(store, market, market, clock);
      w.acceptScan(
          scan(List.of(cycle().events.getFirst()), true, d("5")), LocalDate.of(2026, 9, 1), true);
      w.evaluate(true);
      var c = store.state().book.cycles.get("opening");
      var missed = alert("z-missed", Action.ADD, "100", "5", "8", null);
      c.events.add(missed);
      c.events.add(alert("zz-trim", Action.REDUCE, "100", "8", "4", null));
      c.weight = d("4");
      assertTrue(w.evaluate(false).stream().noneMatch(s -> s.startsWith("CF: ADD ")));
      assertFalse(c.completed.contains(missed.key()));
      w.evaluate(true);
      var add = alert("z-add", Action.ADD, "100", "4", "6", null);
      c.events.add(add);
      c.weight = d("6");
      var second =
          new Alert(
              "a-open",
              "source",
              add.date(),
              "second",
              "XLF",
              Action.OPEN,
              d("100"),
              null,
              d("3"),
              d("90"),
              List.of());
      store.state().book.cycles.put(second.key(), new Cycle(second, true));
      var report = w.evaluate(false);
      int openIndex = -1, addIndex = -1;
      for (int j = 0; j < report.size(); j++) {
        if (report.get(j).startsWith("XLF: OPEN ")) openIndex = j;
        if (report.get(j).startsWith("CF: ADD ")) addIndex = j;
      }
      assertTrue(openIndex >= 0 && addIndex > openIndex, report.toString());
    }
  }

  @Test
  void unavailableInstrumentDataDoesNotAbortOtherEligibleInstruments() throws Exception {
    for (int variant = 0; variant < 3; variant++) {
      final int failure = variant;
      var market =
          new Market() {
            public Instrument instrument(String symbol, BigDecimal amount) throws IOException {
              if (symbol.equals("CF") && failure == 0) throw new IOException("COST_ESTIMATE_STALE");
              return super.instrument(symbol, amount);
            }

            public Quote quote(Instrument instrument) throws IOException {
              if (instrument.symbol().equals("CF"))
                throw new IOException(failure == 1 ? "sensitive response must not leak" : null);
              return super.quote(instrument);
            }
          };
      try (var store = new StateStore(temp.resolve("case-" + variant))) {
        var w = new Workflow(store, market, market, clock);
        w.acceptScan(
            scan(List.of(cycle().events.getFirst()), true, d("5")), LocalDate.of(2026, 9, 1), true);
        var second =
            new Alert(
                "second",
                "source",
                LocalDate.of(2026, 9, 21),
                "hash",
                "XLF",
                Action.OPEN,
                d("100"),
                null,
                d("3"),
                d("90"),
                List.of());
        store.state().book.cycles.put(second.key(), new Cycle(second, true));
        var report = w.evaluate(false);
        assertTrue(
            report.contains(
                "CF: BLOCKED "
                    + (failure == 0 ? "COST_ESTIMATE_STALE" : "INSTRUMENT_DATA_UNAVAILABLE")));
        assertTrue(report.contains("XLF: READY POLICY_PASSED"));
        assertTrue(market.submitted.isEmpty());
        w.evaluate(true);
        assertEquals(List.of("second"), market.submitted.stream().map(Intent::cycleKey).toList());
        assertFalse(store.state().book.cycles.get("opening").entered);
      }
    }
  }

  @Test
  void planningDoesNotEnrollAndInitializationDoesNotTrade() throws Exception {
    var market = new Market();
    try (var store = new StateStore(temp)) {
      var w = new Workflow(store, market, market, clock);
      w.acceptScan(
          scan(List.of(cycle().events.getFirst()), true, d("5")), LocalDate.of(2026, 9, 1), false);
      assertNull(store.state().checkpoint);
      assertNull(store.state().enrollmentFloor);
      assertTrue(w.evaluate(false).stream().anyMatch(s -> s.contains("READY")));
      assertTrue(market.submitted.isEmpty());
      assertThrows(IOException.class, () -> w.evaluate(true));
      w.acceptScan(
          scan(List.of(cycle().events.getFirst()), true, d("5")), LocalDate.of(2026, 9, 1), true);
      assertEquals(NOW, store.state().checkpoint);
      assertTrue(market.submitted.isEmpty());
      w.evaluate(true);
      assertEquals(1, market.submitted.size());
      assertEquals(d("230.50"), market.submitted.getFirst().ownerAmount());
      w.evaluate(true);
      assertEquals(1, market.submitted.size()); // A funding-only/no-alert run cannot resize.
    }
  }

  @Test
  void incompleteScanAndDashboardMismatchNeverAdvanceCheckpoint() throws Exception {
    var market = new Market();
    try (var store = new StateStore(temp)) {
      var w = new Workflow(store, market, market, clock);
      assertThrows(
          IOException.class,
          () ->
              w.acceptScan(
                  scan(List.of(cycle().events.getFirst()), false, d("5")),
                  LocalDate.of(2026, 9, 1),
                  true));
      assertNull(store.state().checkpoint);
      assertNull(store.state().enrollmentFloor);
      assertThrows(
          IOException.class,
          () ->
              w.acceptScan(
                  scan(List.of(cycle().events.getFirst()), true, d("8")),
                  LocalDate.of(2026, 9, 1),
                  true));
      assertTrue(store.state().report.contains("SOURCE_DASHBOARD_MISMATCH"));
    }
  }

  @Test
  void lateOpeningAbsorbsHistoryThenNewTrimPrecedesStopAndAddition() throws Exception {
    var market = new Market();
    try (var store = new StateStore(temp)) {
      var w = new Workflow(store, market, market, clock);
      w.acceptScan(
          scan(
              List.of(
                  cycle().events.getFirst(),
                  alert("trim-old", Action.REDUCE, "100", "5", "4", null)),
              true,
              d("4")),
          LocalDate.of(2026, 9, 1),
          true);
      w.evaluate(true);
      assertEquals(d("184.40"), market.submitted.getFirst().ownerAmount());
      var c = store.state().book.cycles.get("opening");
      assertTrue(c.completed.contains("trim-old"));
      var trim = alert("trim-new", Action.REDUCE, "101", "4", "3", "95");
      c.events.add(trim);
      c.weight = d("3");
      c.stop = d("95");
      w.evaluate(true);
      assertEquals(
          List.of(Action.OPEN, Action.REDUCE, Action.STOP),
          market.submitted.stream().map(Intent::action).toList());
      assertEquals(d("1.2500"), market.submitted.get(1).units());
      assertTrue(c.completed.contains("trim-new"));
      c.events.add(alert("z-add", Action.ADD, "100", "3", "5", null));
      c.weight = d("5");
      w.evaluate(true);
      assertEquals(Action.ADD, market.submitted.getLast().action());
      assertEquals(d("92.20"), market.submitted.getLast().ownerAmount());
    }
  }

  @Test
  void pendingCopyKillAndUnexpectedHoldingsBlockNewExposure() throws Exception {
    var market = new Market();
    try (var store = new StateStore(temp)) {
      var w = new Workflow(store, market, market, clock);
      w.acceptScan(
          scan(List.of(cycle().events.getFirst()), true, d("5")), LocalDate.of(2026, 9, 1), true);
      market.pending = true;
      assertTrue(w.evaluate(true).contains("ACCOUNT_ACTIVITY_UNVERIFIED"));
      market.pending = false;
      market.lots.add(position(999, "1", "90", true, false));
      assertTrue(w.evaluate(true).contains("UNEXPLAINED_AGENT_POSITION"));
      market.lots.clear();
      Files.createFile(temp.resolve("KILL"));
      assertThrows(IOException.class, () -> w.evaluate(true));
      Files.delete(temp.resolve("KILL"));
      market.copyPending = true;
      w.evaluate(true);
      assertEquals(1, market.submitted.size());
      assertTrue(w.evaluate(true).contains("UNRESOLVED_ORDER_BLOCKS_NEW_SUBMISSIONS"));
      assertEquals(1, market.submitted.size());
    }
  }

  @Test
  void recordedEarlyExitIsTerminalAndCannotReenter() throws Exception {
    var market = new Market();
    try (var store = new StateStore(temp)) {
      var w = new Workflow(store, market, market, clock);
      w.acceptScan(
          scan(List.of(cycle().events.getFirst()), true, d("5")), LocalDate.of(2026, 9, 1), true);
      w.evaluate(true);
      store
          .state()
          .earlyExits
          .add(
              new Alert(
                  "exit",
                  "user",
                  LocalDate.now(clock),
                  "user",
                  "opening",
                  Action.EARLY_EXIT,
                  null,
                  d("1"),
                  d("0"),
                  null,
                  List.of()));
      w.evaluate(true);
      assertTrue(store.state().book.cycles.get("opening").terminal);
      assertTrue(market.lots.isEmpty());
      w.evaluate(true);
      assertEquals(2, market.submitted.size());
    }
  }

  @Test
  void laterReductionExpiresUnexecutedAddWithoutBuyingRemovedExposure() throws Exception {
    var market = new Market();
    try (var store = new StateStore(temp)) {
      var w = new Workflow(store, market, market, clock);
      w.acceptScan(
          scan(List.of(cycle().events.getFirst()), true, d("5")), LocalDate.of(2026, 9, 1), true);
      w.evaluate(true);
      var c = store.state().book.cycles.get("opening");
      c.events.add(alert("add-missed", Action.ADD, "100", "5", "8", null));
      c.events.add(alert("trim-later", Action.REDUCE, "100", "8", "4", null));
      c.weight = d("4");
      w.evaluate(true);
      assertTrue(c.completed.contains("add-missed"));
      assertEquals(
          List.of(Action.OPEN, Action.REDUCE),
          market.submitted.stream().map(Intent::action).toList());
      assertEquals(d("2.5000"), market.submitted.getLast().units());
      w.evaluate(true);
      assertEquals(2, market.submitted.size());
    }
  }

  @Test
  void rejectedOpeningCanRetryButUnknownOpeningCannotAndNewReferenceIsDurable() throws Exception {
    var market = new Market();
    try (var store = new StateStore(temp)) {
      var w = new Workflow(store, market, market, clock);
      w.acceptScan(
          scan(List.of(cycle().events.getFirst()), true, d("5")), LocalDate.of(2026, 9, 1), true);
      market.reject = true;
      assertTrue(w.evaluate(true).stream().anyMatch(s -> s.contains(": NOT_FILLED ")));
      assertEquals(1, market.submitted.size());
      market.reject = false;
      w.evaluate(true);
      assertEquals(2, market.submitted.size());
      assertTrue(store.state().attempts.keySet().stream().anyMatch(k -> k.endsWith("attempt:2")));
      assertEquals(
          2, store.state().attempts.values().stream().map(a -> a.reference).distinct().count());
      w.evaluate(true);
      assertEquals(2, market.submitted.size());
    }
  }

  @Test
  void provenEmptyBuyContinuesOtherCyclesButUncertainBuyStops() throws Exception {
    for (Status status :
        List.of(Status.REJECTED, Status.SUBMITTED, Status.UNKNOWN, Status.PARTIAL)) {
      var market =
          new Market() {
            @Override
            public Observation observe(Attempt a) {
              return new Observation(
                  status,
                  status == Status.REJECTED
                      ? "CONFIRMED_NO_FILL Rejected; broker code 1065: technical failure"
                      : "AWAITING_FILL",
                  List.of());
            }
          };
      market.reject = true; // no fake positions; exercise journal and submission ordering
      try (var store = new StateStore(temp.resolve(status.name()))) {
        var workflow = new Workflow(store, market, market, clock);
        workflow.acceptScan(
            scan(List.of(cycle().events.getFirst()), true, d("5")), LocalDate.of(2026, 9, 1), true);
        var second = cycle();
        second.key = "second";
        second.symbol = "ABC";
        store.state().book.cycles.put(second.key, second);
        var report = workflow.evaluate(true);
        assertEquals(status == Status.REJECTED ? 2 : 1, market.submitted.size());
        assertFalse(store.state().book.cycles.get("opening").entered);
        assertTrue(store.state().book.cycles.get("opening").completed.isEmpty());
        if (status == Status.REJECTED) {
          assertTrue(report.stream().anyMatch(s -> s.startsWith("ABC: NOT_FILLED ")));
          assertTrue(report.stream().anyMatch(s -> s.contains("broker code 1065")));
        } else assertTrue(report.stream().anyMatch(s -> s.contains("ORDER_PENDING_OR_REJECTED")));
      }
    }
  }

  @Test
  void stopExitTerminatesCycleAndUnexplainedPartialChangeIsHeld() throws Exception {
    var market = new Market();
    try (var store = new StateStore(temp)) {
      var w = new Workflow(store, market, market, clock);
      w.acceptScan(
          scan(List.of(cycle().events.getFirst()), true, d("5")), LocalDate.of(2026, 9, 1), true);
      w.evaluate(true);
      market.lots.set(0, position(11, "4", "90", true, false));
      assertTrue(w.evaluate(true).contains("UNEXPLAINED_HOLDING_CHANGE"));
      assertEquals(1, market.submitted.size());
      market.lots.clear();
      assertTrue(w.evaluate(true).stream().anyMatch(s -> s.contains("POSITION_EXITED_NO_REENTRY")));
      assertTrue(store.state().book.cycles.get("opening").terminal);
      assertEquals(1, market.submitted.size());
    }
  }

  @Test
  void newerSourceFactsAreAppliedAndIncompleteEvidenceRemainsSaved() throws Exception {
    var market = new Market();
    try (var store = new StateStore(temp)) {
      var w = new Workflow(store, market, market, clock);
      w.acceptScan(
          scan(List.of(cycle().events.getFirst()), true, d("5")), LocalDate.of(2026, 9, 1), true);
      w.acceptScan(
          scan(List.of(alert("trim", Action.REDUCE, "100", "5", "4", null)), true, d("4")),
          null,
          false);
      assertEquals(d("4"), store.state().book.cycles.get("opening").weight);
      assertThrows(
          IOException.class, () -> w.acceptScan(scan(List.of(), false, d("4")), null, false));
      var saved =
          com.loris.bravos.util.Json.MAPPER.readValue(
              temp.resolve("ledger.json").toFile(), TradingState.class);
      assertTrue(saved.report.contains("READ_FAILED"));
      assertEquals(NOW, saved.checkpoint);
    }
  }
}
