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
}
