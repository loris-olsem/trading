package com.loris.bravos.app;

import com.loris.bravos.broker.*;
import com.loris.bravos.domain.*;
import com.loris.bravos.domain.Model.*;
import com.loris.bravos.source.BravosSource;
import com.loris.bravos.state.*;
import com.loris.bravos.state.TradingState.*;
import java.io.IOException;
import java.math.*;
import java.time.*;
import java.util.*;

/** Coordinates discovery, reconciliation and policy; no trading arithmetic is hidden in the CLI. */
public final class Workflow {
  public interface Market {
    Account account() throws IOException;

    Instrument instrument(String symbol, BigDecimal amount) throws IOException;

    Quote quote(Instrument instrument) throws IOException;

    int unitScale(String symbol) throws IOException;
  }

  private final StateStore store;
  private final Market market;
  private final Executor executor;
  private final Clock clock;
  private final Policy policy = new Policy();

  public Workflow(StateStore store, Market market, Broker broker, Clock clock) {
    this.store = store;
    this.market = market;
    this.executor = new Executor(store, broker, clock);
    this.clock = clock;
  }

  public static SourceBook initialBook(List<Alert> alerts, LocalDate enrollmentFloor) {
    SourceBook book = new SourceBook();
    // Start each symbol at the earliest opening actually observed. Pre-boundary updates
    // cannot create a position. Dashboard agreement is mandatory before enrollment.
    Map<String, LocalDate> first = new HashMap<>();
    for (Alert a : alerts)
      if (a.action() == Action.OPEN)
        first.merge(a.symbol(), a.date(), (a1, b) -> a1.isBefore(b) ? a1 : b);
    book.apply(
        alerts.stream()
            .filter(a -> first.containsKey(a.symbol()) && !a.date().isBefore(first.get(a.symbol())))
            .toList(),
        enrollmentFloor);
    return book;
  }

  public static boolean dashboardMatches(SourceBook book, Map<String, BigDecimal> dashboard) {
    Map<String, BigDecimal> current = new HashMap<>();
    for (Cycle c : book.cycles.values())
      if (c.sourceOpen) {
        if (c.blocker != null || current.put(c.symbol, c.weight) != null) return false;
      }
    return current.keySet().equals(dashboard.keySet())
        && current.entrySet().stream()
            .allMatch(e -> e.getValue().compareTo(dashboard.get(e.getKey())) == 0);
  }

  public void acceptScan(BravosSource.Scan scan, LocalDate enrollmentFloor, boolean initialize)
      throws IOException {
    TradingState state = store.state();
    state.report.clear();
    state.report.addAll(scan.errors());
    if (!scan.complete()) {
      state.report.add("SOURCE_SCAN_INCOMPLETE");
      store.recordRejectedSource(scan.alerts(), state.report);
      store.save();
      throw new IOException("SOURCE_DISCOVERY_INCOMPLETE_SEE_STATUS");
    }
    SourceBook candidate =
        state.checkpoint == null
            ? initialBook(scan.alerts(), enrollmentFloor)
            : com.loris.bravos.util.Json.MAPPER.readValue(
                com.loris.bravos.util.Json.MAPPER.writeValueAsBytes(state.book), SourceBook.class);
    if (state.checkpoint != null) candidate.apply(scan.alerts(), state.enrollmentFloor);
    boolean matches = dashboardMatches(candidate, scan.dashboard());
    if (!matches) state.report.add("SOURCE_DASHBOARD_MISMATCH");
    state.report.addAll(candidate.blockers);
    if (!matches || !candidate.blockers.isEmpty()) {
      store.recordRejectedSource(scan.alerts(), state.report);
      store.save();
      throw new IOException("SOURCE_DISCOVERY_INCOMPLETE_SEE_STATUS");
    }
    state.book = candidate;
    if (initialize) {
      if (state.enrollmentFloor != null) throw new IOException("ALREADY_INITIALIZED");
      state.enrollmentFloor = enrollmentFloor;
    }
    // Planning may remember source evidence but cannot establish live enrollment.
    if (state.enrollmentFloor != null) state.checkpoint = clock.instant();
    state.revisionAudit = clock.instant();
    store.save();
  }

  public List<String> evaluate(boolean live) throws IOException {
    TradingState state = store.state();
    state.report.clear();
    if (live && (state.enrollmentFloor == null || state.checkpoint == null))
      throw new IOException("INITIALIZE_REQUIRED");
    if (live && store.killed()) throw new IOException("KILL_SWITCH");
    reconcileOnly();
    if (state.attempts.values().stream()
        .anyMatch(a -> !Set.of(Status.CONFIRMED, Status.REJECTED).contains(a.status))) {
      recoverProtection(live);
      state.report.add(
          state.attempts.values().stream()
                  .anyMatch(a -> !Set.of(Status.CONFIRMED, Status.REJECTED).contains(a.status))
              ? "UNRESOLVED_ORDER_BLOCKS_NEW_SUBMISSIONS"
              : "PROTECTION_RECOVERED_REVIEW_BEFORE_NEXT_RUN");
      store.save();
      return List.copyOf(state.report);
    }
    Account account = market.account();
    if (!account.active() || account.pending() || !account.ordersComplete()) {
      state.report.add("ACCOUNT_ACTIVITY_UNVERIFIED");
      store.save();
      return List.copyOf(state.report);
    }
    Set<Long> linked = new HashSet<>();
    for (Cycle c : state.book.cycles.values()) linked.addAll(c.positionIds);
    if (account.agentPositions().stream().anyMatch(p -> !linked.contains(p.id()))) {
      state.report.add("UNEXPLAINED_AGENT_POSITION");
      store.save();
      return List.copyOf(state.report);
    }
    for (Position p : account.agentPositions()) {
      Holding known = state.holdings.get(p.id());
      Map<Long, BigDecimal> copied = new LinkedHashMap<>();
      account.ownerPositions().stream()
          .filter(o -> o.parentId() == p.id())
          .forEach(o -> copied.put(o.id(), o.units()));
      if (known == null
          || p.units().compareTo(known.agentUnits()) != 0
          || !sameAmounts(known.ownerUnits(), copied)) {
        state.report.add("UNEXPLAINED_HOLDING_CHANGE");
        store.save();
        return List.copyOf(state.report);
      }
    }
    if (account.ownerPositions().stream().anyMatch(p -> !linked.contains(p.parentId()))) {
      state.report.add("UNEXPLAINED_COPIED_POSITION");
      store.save();
      return List.copyOf(state.report);
    }
    for (Cycle c : state.book.cycles.values())
      if (c.entered && !c.terminal) {
        boolean allGone =
            c.positionIds.stream()
                .noneMatch(id -> account.agentPositions().stream().anyMatch(p -> p.id() == id));
        if (allGone && !c.positionIds.isEmpty()) {
          boolean copiesGone =
              account.ownerPositions().stream()
                  .noneMatch(p -> c.positionIds.contains(p.parentId()));
          if (copiesGone) {
            c.terminal = true;
            state.report.add(c.symbol + ": POSITION_EXITED_NO_REENTRY");
          } else {
            state.report.add(c.symbol + ": COPY_EXIT_PENDING");
            store.save();
            return List.copyOf(state.report);
          }
        }
      }
    List<Cycle> cycles =
        state.book.cycles.values().stream()
            .sorted(
                Comparator.comparing((Cycle c) -> c.openedOn)
                    .thenComparing(c -> c.key, SourceBook::compareKeys))
            .toList();
    for (Cycle c : cycles)
      if (c.blocker != null) {
        for (Alert e : c.events)
          if (!c.completed.contains(e.key()))
            state.report.add(
                c.symbol
                    + ": BLOCKED "
                    + c.blocker
                    + " event="
                    + e.key()
                    + " action="
                    + e.action()
                    + " before="
                    + e.before()
                    + " after="
                    + e.after()
                    + " retainedAgentUnits="
                    + account.agentPositions().stream()
                        .filter(p -> c.positionIds.contains(p.id()))
                        .map(Position::units)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                    + "; resolve source facts before execution");
      }
    for (Cycle c : cycles)
      if (c.entered) {
        for (int index = 0; index < c.events.size(); index++) {
          Alert add = c.events.get(index);
          if (add.action() != Action.ADD || c.completed.contains(add.key())) continue;
          boolean reducedLater =
              c.events.subList(index + 1, c.events.size()).stream()
                  .anyMatch(e -> e.action() == Action.REDUCE || e.action() == Action.CLOSE);
          if (reducedLater) {
            state.report.add(c.symbol + ": ADD_EXPIRED_BY_LATER_REDUCTION " + add.key());
            if (live) c.completed.add(add.key());
          }
        }
      }
    // First risk reductions, then protection, then additional exposure.
    for (Cycle c : cycles)
      if (c.entered && !c.terminal) {
        List<Alert> reductions =
            new ArrayList<>(
                c.events.stream()
                    .filter(e -> e.action() == Action.REDUCE || e.action() == Action.CLOSE)
                    .toList());
        reductions.addAll(state.earlyExits.stream().filter(e -> e.symbol().equals(c.key)).toList());
        for (Alert e : reductions)
          if (!c.completed.contains(e.key())) {
            var lots = market.account().agentPositions();
            List<Intent> intents = state.batches.get(c.key + "|" + e.key() + "|" + e.action());
            if (intents == null) intents = policy.reduction(c, e, lots, market.unitScale(c.symbol));
            if (!batch(c, e.key(), intents, live)) return finish();
            if (live
                && c.completed.contains(e.key())
                && (e.action() == Action.CLOSE
                    || e.action() == Action.EARLY_EXIT && e.after().signum() == 0)) {
              c.terminal = true;
              break;
            }
          }
      }
    for (Cycle c : cycles)
      if (c.entered && !c.terminal) {
        if (!batch(c, null, policy.protection(c, market.account().agentPositions()), live))
          return finish();
      }
    Account protectedAccount = market.account();
    for (Cycle c : cycles)
      if (c.entered && !c.terminal) {
        for (long id : c.positionIds) {
          List<Position> copies =
              protectedAccount.ownerPositions().stream().filter(p -> p.parentId() == id).toList();
          if (copies.isEmpty()
              || copies.stream()
                  .anyMatch(
                      p ->
                          !p.longOnly()
                              || !p.stopEnabled()
                              || p.trailing()
                              || p.stop() == null
                              || c.stop == null
                              || p.stop().compareTo(c.stop) != 0)) {
            state.report.add(c.symbol + ": COPY_PROTECTION_UNVERIFIED");
            return finish();
          }
        }
      }
    record Exposure(Cycle cycle, Alert event) {}
    List<Exposure> exposures = new ArrayList<>();
    for (Cycle c : cycles)
      if (c.sourceOpen && !c.terminal && c.enrolled) {
        List<Alert> actions =
            c.entered
                ? c.events.stream()
                    .filter(
                        e ->
                            e.action() == Action.ADD
                                && !c.completed.contains(e.key())
                                && !hasLaterReduction(c, e))
                    .toList()
                : List.of(c.events.getFirst());
        for (Alert e : actions) exposures.add(new Exposure(c, e));
      }
    exposures.sort(
        Comparator.comparing((Exposure x) -> x.event().date())
            .thenComparing(x -> x.event().key(), SourceBook::compareKeys));
    for (Exposure exposure : exposures) {
      Cycle c = exposure.cycle();
      Alert e = exposure.event();
      Account fresh = market.account();
      BigDecimal weight = c.entered ? e.after().subtract(e.before()) : c.weight;
      Instrument instrument;
      Quote quote;
      try {
        instrument =
            market.instrument(
                c.symbol, weight.multiply(fresh.ownerEquity()).divide(new BigDecimal("100")));
        quote = instrument == null ? null : market.quote(instrument);
      } catch (IOException unavailable) {
        String reason = unavailable.getMessage();
        state.report.add(
            c.symbol
                + ": BLOCKED "
                + (reason != null && reason.matches("[A-Z][A-Z0-9_]{2,100}")
                    ? reason
                    : "INSTRUMENT_DATA_UNAVAILABLE"));
        continue;
      }
      if (live && c.entered && quote != null && quote.exchangeOpen())
        c.additionSessions.putIfAbsent(
            e.key(), clock.instant().atZone(ZoneId.of("America/New_York")).toLocalDate());
      Decision decision =
          c.entered
              ? policy.addition(c, e, instrument, quote, fresh, clock.instant(), BigDecimal.ZERO)
              : policy.opening(c, instrument, quote, fresh, clock.instant(), BigDecimal.ZERO);
      state.report.add(c.symbol + ": " + decision.outcome() + " " + decision.reason());
      if (!batch(c, null, decision.intents(), live)) return finish();
    }
    return finish();
  }

  public void reconcileOnly() throws IOException {
    for (Attempt a : new ArrayList<>(store.state().attempts.values())) {
      executor.reconcile(a);
      if (a.status == Status.CONFIRMED && !a.baselineSaved) refreshHolding(a);
    }
  }

  private void recoverProtection(boolean live) throws IOException {
    Account account = market.account();
    for (Attempt attempt : new ArrayList<>(store.state().attempts.values())) {
      if (attempt.status == Status.CONFIRMED || attempt.status == Status.REJECTED) continue;
      store.state().report.add("UNRESOLVED_ATTEMPT " + attempt.reference + " " + attempt.result);
      Intent original = attempt.intent;
      if (original.action() != Action.OPEN && original.action() != Action.ADD) continue;
      Cycle cycle = store.state().book.cycles.get(original.cycleKey());
      for (long id : attempt.positionIds) {
        Position position =
            account.agentPositions().stream().filter(p -> p.id() == id).findFirst().orElse(null);
        List<Position> copies =
            account.ownerPositions().stream().filter(p -> p.parentId() == id).toList();
        if (position == null || copies.isEmpty())
          store.state().report.add("UNPROTECTED_POSITION_LINK_UNVERIFIED agent=" + id);
        for (Position copy : copies)
          if (!protectedAt(copy, original.stop()))
            store
                .state()
                .report
                .add(
                    "UNPROTECTED_OWNER_POSITION "
                        + copy.id()
                        + " expectedStop="
                        + original.stop()
                        + "; owner intervention required if propagation fails");
        if (position == null || protectedAt(position, original.stop())) continue;
        store
            .state()
            .report
            .add("UNPROTECTED_AGENT_POSITION " + id + " expectedStop=" + original.stop());
        // Repair only identified fills, never unknown executions or unrelated lots.
        if (!live
            || attempt.status != Status.PARTIAL
            || !"COPY_OR_STOP_NOT_CONFIRMED".equals(attempt.result)
            || cycle.blocker != null
            || cycle.stop == null
            || original.stop() == null
            || original.stop().signum() <= 0
            || cycle.stop.compareTo(original.stop()) != 0
            || !account.active()
            || !account.ordersComplete()
            || account.pending()
            || account.observedAt().isAfter(clock.instant())
            || Duration.between(account.observedAt(), clock.instant())
                    .compareTo(Duration.ofSeconds(60))
                > 0
            || !position.longOnly()
            || position.instrumentId() != original.instrumentId()
            || copies.isEmpty()
            || copies.stream()
                .anyMatch(p -> !p.longOnly() || p.instrumentId() != original.instrumentId()))
          continue;
        Intent repair =
            new Intent(
                "repair:" + attempt.reference + "|" + id,
                cycle.key,
                original.eventKey(),
                Action.STOP,
                original.instrumentId(),
                id,
                null,
                null,
                null,
                null,
                original.stop(),
                null);
        Attempt result = executor.execute(repair);
        store.state().report.add("PROTECTION_REPAIR " + id + " " + result.status);
      }
    }
    // Confirm the original fill only after both accounts prove its protection.
    reconcileOnly();
  }

  private static boolean protectedAt(Position position, BigDecimal stop) {
    return stop != null
        && position.longOnly()
        && position.stopEnabled()
        && !position.trailing()
        && position.stop() != null
        && position.stop().compareTo(stop) == 0;
  }

  private static boolean sameAmounts(Map<Long, BigDecimal> left, Map<Long, BigDecimal> right) {
    return left.keySet().equals(right.keySet())
        && left.entrySet().stream()
            .allMatch(e -> e.getValue().compareTo(right.get(e.getKey())) == 0);
  }

  private void refreshHolding(Attempt attempt) throws IOException {
    Account account = market.account();
    Set<Long> ids = new HashSet<>(attempt.positionIds);
    if (attempt.intent.positionId() != null) ids.add(attempt.intent.positionId());
    for (long id : ids) {
      Position p =
          account.agentPositions().stream().filter(x -> x.id() == id).findFirst().orElse(null);
      if (p == null) {
        store.state().holdings.remove(id);
        continue;
      }
      Map<Long, BigDecimal> copied = new LinkedHashMap<>();
      account.ownerPositions().stream()
          .filter(x -> x.parentId() == id)
          .forEach(x -> copied.put(x.id(), x.units()));
      store.state().holdings.put(id, new Holding(p.units(), copied));
    }
    attempt.baselineSaved = true;
    store.save();
  }

  private static boolean hasLaterReduction(Cycle c, Alert event) {
    return c.events.subList(c.events.indexOf(event) + 1, c.events.size()).stream()
        .anyMatch(e -> e.action() == Action.REDUCE || e.action() == Action.CLOSE);
  }

  private boolean batch(Cycle c, String event, List<Intent> intents, boolean live)
      throws IOException {
    if (intents.isEmpty()) return true;
    for (Intent i : intents)
      store
          .state()
          .report
          .add(
              c.symbol
                  + ": "
                  + i.action()
                  + " "
                  + (i.agentAmount() != null
                      ? "owner USD "
                          + i.ownerAmount()
                          + ", internal USD "
                          + i.agentAmount()
                          + ", ceiling "
                          + i.ceiling()
                          + ", stop "
                          + i.stop()
                      : "units " + i.units() + ", stop " + i.stop()));
    if (!live) return true;
    // Save every member before submission so restarts retain original proportional units.
    Intent first = intents.getFirst();
    boolean buy = first.action() == Action.OPEN || first.action() == Action.ADD;
    if (buy) {
      long number =
          store.state().attempts.values().stream()
                  .filter(
                      a ->
                          a.intent.cycleKey().equals(c.key)
                              && a.intent.eventKey().equals(first.eventKey())
                              && a.intent.action() == first.action())
                  .count()
              + 1;
      Intent next =
          new Intent(
              first.key() + "|attempt:" + number,
              first.cycleKey(),
              first.eventKey(),
              first.action(),
              first.instrumentId(),
              first.positionId(),
              first.ownerAmount(),
              first.agentAmount(),
              first.units(),
              first.ceiling(),
              first.stop(),
              first.settlementType());
      intents = List.of(next);
    }
    String key =
        buy ? intents.getFirst().key() : c.key + "|" + first.eventKey() + "|" + first.action();
    List<Intent> proposed = intents;
    if (buy && !store.state().attempts.containsKey(intents.getFirst().key()))
      store.state().batches.remove(key);
    List<Intent> durable =
        store.state().batches.computeIfAbsent(key, unused -> new ArrayList<>(proposed));
    store.save();
    for (Intent i : durable) {
      Attempt attempt = executor.execute(i);
      if (attempt.status != Status.CONFIRMED) {
        store.state().report.add("ORDER_PENDING_OR_REJECTED: " + attempt.result);
        if (attempt.status != Status.REJECTED) recoverProtection(false);
        return false;
      }
      refreshHolding(attempt);
      store
          .state()
          .report
          .add(
              c.symbol
                  + ": CONFIRMED "
                  + i.action()
                  + (attempt.ownerFilled == null ? "" : " owner USD " + attempt.ownerFilled));
      if (attempt.ownerShortfall != null && attempt.ownerShortfall.signum() > 0)
        store
            .state()
            .report
            .add(
                c.symbol
                    + ": PARTIAL_FILL_KEPT owner USD "
                    + attempt.ownerFilled
                    + ", shortfall USD "
                    + attempt.ownerShortfall
                    + "; no automatic top-up");
    }
    if (event != null) c.completed.add(event);
    store.save();
    return true;
  }

  private List<String> finish() throws IOException {
    store.save();
    return List.copyOf(store.state().report);
  }
}
