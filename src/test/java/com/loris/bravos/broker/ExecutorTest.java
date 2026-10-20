package com.loris.bravos.broker;

import static com.loris.bravos.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.loris.bravos.domain.*;
import com.loris.bravos.domain.Model.*;
import com.loris.bravos.state.*;
import com.loris.bravos.state.TradingState.*;
import com.loris.bravos.util.Json;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ExecutorTest {
  @TempDir Path temp;
  final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  Intent intent() {
    return new Policy()
        .opening(cycle(), instrument(), quote("100"), account(), NOW, d("0"))
        .intents()
        .getFirst();
  }

  class Fake implements Broker {
    int submits, observes;
    boolean lost, readFailure, killPrepare;
    Observation observation = new Observation(Status.CONFIRMED, "PROVED", List.of(10L));

    public void prepare(Attempt a) throws IOException {
      if (killPrepare) Files.createFile(temp.resolve("KILL"));
    }

    public Receipt submit(Intent i, String reference) throws IOException {
      var durable =
          Json.MAPPER
              .readValue(temp.resolve("ledger.json").toFile(), TradingState.class)
              .attempts
              .get(i.key());
      assertEquals(reference, durable.reference);
      assertEquals(Status.SUBMITTING, durable.status);
      submits++;
      if (lost) throw new IOException("lost response");
      return new Receipt(123L);
    }

    public Observation observe(Attempt a) throws IOException {
      observes++;
      if (readFailure) throw new IOException();
      return observation;
    }
  }

  StateStore store() throws IOException {
    var s = new StateStore(temp);
    s.state().book.cycles.put("opening", cycle());
    return s;
  }

  @Test
  void delayedCopyConfirmationWaitsWithoutResubmission() throws Exception {
    var broker =
        new Fake() {
          public Duration readbackInterval() {
            return Duration.ofSeconds(30);
          }
        };
    broker.readFailure = true;
    var sleeps = new ArrayList<Long>();
    try (var store = store()) {
      var executor =
          new Executor(
              store,
              broker,
              clock,
              millis -> {
                sleeps.add(millis);
                broker.readFailure = false;
                broker.observation =
                    sleeps.size() < 3
                        ? new Broker.Observation(
                            Status.PARTIAL, "COPY_OR_STOP_NOT_CONFIRMED", List.of(10L))
                        : new Broker.Observation(Status.CONFIRMED, "PROVED", List.of(10L));
              });
      assertEquals(Status.CONFIRMED, executor.execute(intent()).status);
      assertEquals(List.of(30000L, 30000L, 30000L), sleeps);
      assertEquals(1, broker.submits);
      assertEquals(4, broker.observes);
      executor.execute(intent());
      assertEquals(1, broker.submits);
    }
  }

  @Test
  void pendingReadbackStopsAtBoundAndRemainsDurable() throws Exception {
    var broker =
        new Fake() {
          public Duration readbackInterval() {
            return Duration.ofSeconds(30);
          }
        };
    broker.observation = new Broker.Observation(Status.SUBMITTED, "AWAITING_FILL", List.of());
    try (var store = store()) {
      assertEquals(
          Status.SUBMITTED, new Executor(store, broker, clock, ms -> {}).execute(intent()).status);
      assertEquals(4, broker.observes);
    }
    try (var store = new StateStore(temp)) {
      assertEquals(Status.SUBMITTED, store.state().attempts.get(intent().key()).status);
      new Executor(
              store, broker, clock, ms -> fail("No new submission or wait for an existing attempt"))
          .execute(intent());
      assertEquals(1, broker.submits);
    }
  }

  @Test
  void adverseEvidenceStopsPollingImmediately() throws Exception {
    var broker =
        new Fake() {
          public Duration readbackInterval() {
            return Duration.ofSeconds(30);
          }
        };
    broker.observation =
        new Broker.Observation(Status.UNKNOWN, "COPIED_PRICE_CEILING_BREACHED", List.of());
    try (var store = store()) {
      assertEquals(
          Status.UNKNOWN,
          new Executor(store, broker, clock, ms -> fail("Must report breach immediately"))
              .execute(intent())
              .status);
      assertEquals(1, broker.observes);
    }
  }

  @Test
  void interruptionPreservesSubmittedAttemptAndInterruptFlag() throws Exception {
    var broker =
        new Fake() {
          public Duration readbackInterval() {
            return Duration.ofSeconds(30);
          }
        };
    broker.observation = new Broker.Observation(Status.SUBMITTED, "AWAITING_FILL", List.of());
    try (var store = store()) {
      var executor =
          new Executor(
              store,
              broker,
              clock,
              ms -> {
                throw new InterruptedException();
              });
      assertThrows(IOException.class, () -> executor.execute(intent()));
      assertTrue(Thread.interrupted());
      assertEquals(Status.SUBMITTED, store.state().attempts.get(intent().key()).status);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void killDuringReadbackPreventsFurtherWaiting() throws Exception {
    var broker =
        new Fake() {
          public Duration readbackInterval() {
            return Duration.ofSeconds(30);
          }
        };
    broker.observation = new Broker.Observation(Status.SUBMITTED, "AWAITING_FILL", List.of());
    try (var store = store()) {
      var executor =
          new Executor(
              store,
              broker,
              clock,
              ms -> {
                try {
                  Files.createFile(temp.resolve("KILL"));
                } catch (IOException e) {
                  throw new AssertionError(e);
                }
              });
      assertEquals(Status.SUBMITTED, executor.execute(intent()).status);
      assertEquals(2, broker.observes);
      assertEquals(1, broker.submits);
    }
  }

  @Test
  void writeAheadConfirmationAndRerunNeverDuplicate() throws Exception {
    var broker = new Fake();
    try (var store = store()) {
      var executor = new Executor(store, broker, clock);
      var a = executor.execute(intent());
      assertEquals(Status.CONFIRMED, a.status);
      assertEquals(123L, a.orderId);
      assertTrue(store.state().book.cycles.get("opening").entered);
      assertEquals(List.of(10L), store.state().book.cycles.get("opening").positionIds);
      assertTrue(store.state().book.cycles.get("opening").completed.contains("opening"));
      executor.execute(intent());
      assertEquals(1, broker.submits);
      assertEquals(1, broker.observes);
    }
    try (var store = new StateStore(temp)) {
      new Executor(store, broker, clock).execute(intent());
      assertEquals(1, broker.submits);
    }
  }

  @Test
  void lostResponseRecoveryUsesSameAttemptAndAbsorbsOnlyOriginalBacklog() throws Exception {
    var broker = new Fake();
    broker.lost = true;
    try (var store = store()) {
      var c = store.state().book.cycles.get("opening");
      c.events.add(alert("old-trim", Action.REDUCE, "110", "5", "4", null));
      var executor = new Executor(store, broker, clock);
      var a = executor.execute(intent());
      String reference = a.reference;
      assertEquals(Status.UNKNOWN, a.status);
      c.events.add(alert("new-add", Action.ADD, "100", "4", "5", null));
      executor.execute(intent());
      assertEquals(1, broker.submits);
      assertEquals(reference, a.reference);
      assertTrue(c.completed.contains("old-trim"));
      assertFalse(c.completed.contains("new-add"));
    }
  }

  @Test
  void partialReadFailuresAndMissingPositionProofStayUnresolved() throws Exception {
    var broker = new Fake();
    broker.observation = new Broker.Observation(Status.PARTIAL, "COPY_PENDING", List.of(10L));
    try (var store = store()) {
      var executor = new Executor(store, broker, clock);
      var a = executor.execute(intent());
      assertEquals(Status.PARTIAL, a.status);
      assertEquals(1, broker.observes);
      assertFalse(store.state().book.cycles.get("opening").completed.contains("opening"));
      broker.readFailure = true;
      executor.reconcile(a);
      assertEquals("RECONCILIATION_UNAVAILABLE", a.result);
      broker.readFailure = false;
      broker.observation = new Broker.Observation(Status.CONFIRMED, "bad proof", List.of());
      executor.reconcile(a);
      assertEquals(Status.UNKNOWN, a.status);
      assertEquals("MISSING_FILLED_POSITION_IDS", a.result);
    }
  }

  @Test
  void killBeforeAndAfterPreparationNeverSubmits() throws Exception {
    var broker = new Fake();
    try (var store = store()) {
      Files.createFile(temp.resolve("KILL"));
      var executor = new Executor(store, broker, clock);
      assertThrows(IOException.class, () -> executor.execute(intent()));
      assertTrue(store.state().attempts.isEmpty());
      Files.delete(temp.resolve("KILL"));
      broker.killPrepare = true;
      assertEquals(Status.REJECTED, executor.execute(intent()).status);
      assertEquals(0, broker.submits);
    }
  }

  @Test
  void closingOneLotDoesNotCompleteTheEntireSourceEvent() throws Exception {
    var broker = new Fake();
    broker.observation = new Broker.Observation(Status.CONFIRMED, "closed", List.of());
    try (var store = store()) {
      var c = store.state().book.cycles.get("opening");
      c.positionIds.addAll(List.of(1L, 2L));
      c.entered = true;
      var close = alert("close", Action.CLOSE, "100", null, "0", null);
      var intents =
          new Policy()
              .reduction(
                  c,
                  close,
                  List.of(position(1, "1", "90", true, false), position(2, "1", "90", true, false)),
                  4);
      new Executor(store, broker, clock).execute(intents.getFirst());
      assertEquals(List.of(2L), c.positionIds);
      assertFalse(c.completed.contains("close"));
      assertFalse(c.terminal);
    }
  }

  @Test
  void cancelledBeforeSubmitIsDurablyRejectedAndCanRetryOnlyOnce() throws Exception {
    var broker = new Fake();
    broker.killPrepare = true;
    try (var store = store()) {
      new Executor(store, broker, clock).execute(intent());
    }
    Files.delete(temp.resolve("KILL"));
    broker.killPrepare = false;
    try (var store = new StateStore(temp)) {
      var cancelled = store.state().attempts.get(intent().key());
      assertEquals(Status.REJECTED, cancelled.status);
      assertEquals("KILL_BEFORE_SUBMISSION", cancelled.result);
      var executor = new Executor(store, broker, clock);
      var retried = executor.execute(intent());
      assertNotEquals(cancelled.reference, retried.reference);
      assertEquals(Status.CONFIRMED, retried.status);
      executor.execute(intent());
      assertEquals(1, broker.submits);
    }
  }

  @Test
  void lostResponseStatusIsSavedBeforeReturning() throws Exception {
    var broker = new Fake();
    broker.lost = true;
    try (var store = store()) {
      new Executor(store, broker, clock).execute(intent());
    }
    try (var store = new StateStore(temp)) {
      assertEquals(Status.UNKNOWN, store.state().attempts.get(intent().key()).status);
      assertEquals("SUBMISSION_OUTCOME_UNKNOWN", store.state().attempts.get(intent().key()).result);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = Action.class,
      names = {"CLOSE", "REDUCE", "EARLY_EXIT"})
  void provenRejectedWholeLotCloseGetsOneDurableCorrectedAttempt(Action action) throws Exception {
    var broker = new Fake();
    var close =
        new Intent(
            "close",
            "opening",
            "close",
            action,
            1890,
            1L,
            null,
            null,
            d("0.838976"),
            null,
            null,
            null);
    String originalReference;
    try (var store = store()) {
      var original = new Attempt(close, NOW);
      original.orderId = 456L;
      original.status = Status.REJECTED;
      original.result = "FULL_CLOSE_REQUEST_REJECTED_NO_FILL";
      original.beforeAgentUnits = close.units();
      originalReference = original.reference;
      store.state().attempts.put(close.key(), original);
      store.save();
    }
    try (var store = new StateStore(temp)) {
      var executor = new Executor(store, broker, clock);
      var corrected = executor.execute(close);
      assertEquals(Status.CONFIRMED, corrected.status);
      assertEquals("close|retry:1", corrected.intent.key());
      assertTrue(corrected.intent.fullClose());
      assertNotEquals(originalReference, corrected.reference);
      long generation = store.state().generation;
      int observes = broker.observes;
      assertSame(corrected, executor.latest(close));
      assertEquals(generation, store.state().generation);
      assertEquals(observes, broker.observes);
      assertEquals(1, broker.submits);
      executor.execute(close);
      assertEquals(1, broker.submits);
    }
    try (var store = new StateStore(temp)) {
      new Executor(store, broker, clock).execute(close);
      assertEquals(1, broker.submits);
      var corrected = store.state().attempts.get("close|retry:1");
      corrected.status = Status.REJECTED;
      corrected.result = "FULL_CLOSE_REQUEST_REJECTED_NO_FILL";
      corrected.beforeAgentUnits = close.units();
      new Executor(store, broker, clock).execute(close);
      assertEquals(1, broker.submits, "A rejected correction must not loop");
    }
  }

  @Test
  void fullEarlyExitKeepsFullCloseModeAfterKillCancelledSubmission() throws Exception {
    var broker = new Fake();
    var full =
        new Intent(
            "exit",
            "opening",
            "exit",
            Action.EARLY_EXIT,
            1890,
            1L,
            null,
            null,
            d("1"),
            null,
            null,
            null,
            "limitIOC",
            true);
    try (var store = store()) {
      var cancelled = new Attempt(full, NOW);
      cancelled.status = Status.REJECTED;
      cancelled.result = "KILL_BEFORE_SUBMISSION";
      store.state().attempts.put(full.key(), cancelled);
      var retried = new Executor(store, broker, clock).execute(full);
      assertTrue(retried.intent.fullClose());
      assertEquals(Status.CONFIRMED, retried.status);
      assertEquals(1, broker.submits);
    }
  }

  @Test
  void partialOrUnprovedCloseRejectionCannotAuthorizeCorrection() throws Exception {
    var broker = new Fake();
    try (var store = store()) {
      for (String proof : List.of("missing-baseline", "different-units", "unknown-result")) {
        var intent =
            new Intent(
                proof,
                "opening",
                "close",
                Action.REDUCE,
                1890,
                1L,
                null,
                null,
                d("1"),
                null,
                null,
                null);
        var a = new Attempt(intent, NOW);
        a.status = Status.REJECTED;
        a.result =
            "unknown-result".equals(proof)
                ? "CLOSE_UNPROVED"
                : "FULL_CLOSE_REQUEST_REJECTED_NO_FILL";
        a.beforeAgentUnits = "missing-baseline".equals(proof) ? null : d("2");
        store.state().attempts.put(intent.key(), a);
        new Executor(store, broker, clock).execute(intent);
      }
      assertEquals(0, broker.submits);
    }
  }

  @Test
  void reconciliationKeepsOnlySafeDiagnosticCodes() throws Exception {
    for (String message : List.of("BROKER_ID_NOT_FOUND", "sensitive response or credential")) {
      var broker =
          new Fake() {
            public Observation observe(Attempt a) throws IOException {
              throw new IOException(message);
            }
          };
      try (var store = store()) {
        var a = new Attempt(intent(), NOW);
        new Executor(store, broker, clock).reconcile(a);
        assertEquals(
            message.startsWith("BROKER_")
                ? "RECONCILIATION_UNAVAILABLE BROKER_ID_NOT_FOUND"
                : "RECONCILIATION_UNAVAILABLE",
            a.result);
      }
    }
  }

  @Test
  void receiptIsSavedBeforeReadbackCanCrash() throws Exception {
    var broker =
        new Fake() {
          @Override
          public Observation observe(Attempt attempt) {
            throw new IllegalStateException("synthetic process failure");
          }
        };
    try (var store = store()) {
      assertThrows(
          IllegalStateException.class, () -> new Executor(store, broker, clock).execute(intent()));
    }
    try (var store = new StateStore(temp)) {
      var saved = store.state().attempts.get(intent().key());
      assertEquals(Status.SUBMITTED, saved.status);
      assertEquals(123L, saved.orderId);
    }
  }
}
