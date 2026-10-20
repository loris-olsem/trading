package com.loris.bravos.broker;

import com.loris.bravos.domain.Model.*;
import com.loris.bravos.state.*;
import com.loris.bravos.state.TradingState.*;
import java.io.IOException;
import java.time.Clock;

/** The write-ahead boundary: a failed response cannot become a second submission. */
public final class Executor {
  private final StateStore store;
  private final Broker broker;
  private final Clock clock;
  private final Sleeper sleeper;

  @FunctionalInterface
  interface Sleeper {
    void sleep(long millis) throws InterruptedException;
  }

  public Executor(StateStore store, Broker broker, Clock clock) {
    this(store, broker, clock, Thread::sleep);
  }

  Executor(StateStore store, Broker broker, Clock clock, Sleeper sleeper) {
    this.store = store;
    this.broker = broker;
    this.clock = clock;
    this.sleeper = sleeper;
  }

  private record Resolution(Intent intent, Attempt prior) {}

  /** Follow the saved retry chain without reconciling, saving or submitting anything. */
  public Attempt latest(Intent intent) {
    return resolve(intent).prior();
  }

  private Resolution resolve(Intent intent) {
    String baseKey = intent.key();
    int retry = 0;
    Attempt prior = store.state().attempts.get(intent.key());
    while (prior != null
        && prior.status == Status.REJECTED
        && ("KILL_BEFORE_SUBMISSION".equals(prior.result)
            || retry == 0
                && "FULL_CLOSE_REQUEST_REJECTED_NO_FILL".equals(prior.result)
                && prior.beforeAgentUnits != null
                && prior.intent.units() != null
                && prior.intent.units().compareTo(prior.beforeAgentUnits) == 0
                && java.util.Set.of(Action.CLOSE, Action.REDUCE, Action.EARLY_EXIT)
                    .contains(prior.intent.action()))) {
      // Retry only a proven non-submission or one rejected full-unit request.
      // A rejection of the corrected request must not cause an endless retry.
      intent =
          new Intent(
              baseKey + "|retry:" + ++retry,
              intent.cycleKey(),
              intent.eventKey(),
              intent.action(),
              intent.instrumentId(),
              intent.positionId(),
              intent.ownerAmount(),
              intent.agentAmount(),
              intent.units(),
              intent.ceiling(),
              intent.stop(),
              intent.settlementType(),
              intent.orderType(),
              intent.fullClose() || "FULL_CLOSE_REQUEST_REJECTED_NO_FILL".equals(prior.result));
      prior = store.state().attempts.get(intent.key());
    }
    return new Resolution(intent, prior);
  }

  public Attempt execute(Intent intent) throws IOException {
    Resolution resolved = resolve(intent);
    intent = resolved.intent();
    Attempt prior = resolved.prior();
    if (prior != null) {
      reconcile(prior);
      return prior;
    }
    if (store.killed()) throw new IOException("KILL_SWITCH");
    Attempt attempt = new Attempt(intent, clock.instant());
    if (intent.action() == Action.OPEN)
      attempt.absorbedEvents.addAll(
          store.state().book.cycles.get(intent.cycleKey()).events.stream()
              .map(Alert::key)
              .toList());
    broker.prepare(attempt);
    store.state().attempts.put(intent.key(), attempt);
    store.save();
    if (store.killed()) {
      attempt.status = Status.REJECTED;
      attempt.result = "KILL_BEFORE_SUBMISSION";
      store.save();
      return attempt;
    }
    Broker.Receipt receipt;
    try {
      receipt = broker.submit(intent, attempt.reference);
    } catch (IOException e) {
      attempt.status = Status.UNKNOWN;
      attempt.result = "SUBMISSION_OUTCOME_UNKNOWN";
      store.save();
      return attempt;
    }
    attempt.orderId = receipt.orderId();
    attempt.status = Status.SUBMITTED;
    store.save();
    reconcile(attempt);
    // Only read again; never repeat a submission. Portfolio/PnL caches can lag
    // the order receipt. Limit waiting to three intervals (90 seconds on eToro).
    for (int poll = 0; poll < 3 && awaitingReadback(attempt); poll++) {
      long delay = broker.readbackInterval().toMillis();
      if (delay <= 0 || store.killed()) break;
      try {
        sleeper.sleep(delay);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("READBACK_INTERRUPTED_STATE_PRESERVED", interrupted);
      }
      reconcile(attempt);
    }
    return attempt;
  }

  private static boolean awaitingReadback(Attempt attempt) {
    return attempt.result != null && attempt.result.startsWith("RECONCILIATION_UNAVAILABLE")
        || attempt.status == Status.SUBMITTED
        || attempt.status == Status.PARTIAL;
  }

  public void reconcile(Attempt attempt) throws IOException {
    if (attempt.status == Status.CONFIRMED || attempt.status == Status.REJECTED) return;
    Broker.Observation observed;
    try {
      observed = broker.observe(attempt);
    } catch (IOException e) {
      attempt.result = "RECONCILIATION_UNAVAILABLE";
      String code = e.getMessage();
      if (code != null && code.matches("[A-Z][A-Z0-9_]{0,79}")) attempt.result += " " + code;
      store.save();
      return;
    }
    attempt.status = observed.status();
    attempt.result = observed.reason();
    attempt.positionIds = observed.positionIds();
    attempt.agentFilled = observed.agentFilled();
    attempt.ownerFilled = observed.ownerFilled();
    attempt.ownerShortfall = observed.ownerShortfall();
    Cycle cycle = store.state().book.cycles.get(attempt.intent.cycleKey());
    Action action = attempt.intent.action();
    if ((action == Action.OPEN || action == Action.ADD)
        && (attempt.status == Status.CONFIRMED || attempt.status == Status.PARTIAL)) {
      if (attempt.positionIds.isEmpty()) {
        attempt.status = Status.UNKNOWN;
        attempt.result = "MISSING_FILLED_POSITION_IDS";
      } else {
        cycle.entered = true;
        for (Long id : attempt.positionIds)
          if (!cycle.positionIds.contains(id)) cycle.positionIds.add(id);
      }
    }
    if (attempt.status == Status.CONFIRMED) {
      if (action == Action.OPEN) cycle.completed.addAll(attempt.absorbedEvents);
      if (action == Action.ADD) cycle.completed.add(attempt.intent.eventKey());
      if (action == Action.CLOSE || action == Action.EARLY_EXIT) {
        // Full termination is established by the workflow's holdings read-back,
        // never merely by one lot's close acknowledgement.
        cycle.positionIds.removeIf(
            id -> id.equals(attempt.intent.positionId()) && observed.positionIds().isEmpty());
      }
    }
    store.save();
  }
}
