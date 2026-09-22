package com.loris.bravos.broker;

import static com.loris.bravos.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.loris.bravos.app.Secrets;
import com.loris.bravos.util.Json;
import java.io.IOException;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class StreamingRatesTest {
  final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  final Secrets secrets =
      new Secrets("app-fixture", "agent-fixture", "owner-fixture", "user", "pass");

  static class Socket implements WebSocket {
    Listener listener;
    List<String> sent = new ArrayList<>();
    boolean aborted, failSend;
    long requests;

    public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
      sent.add(data.toString());
      return failSend
          ? CompletableFuture.failedFuture(new IOException("sensitive"))
          : CompletableFuture.completedFuture(this);
    }

    public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
      throw new AssertionError();
    }

    public CompletableFuture<WebSocket> sendPing(ByteBuffer data) {
      return CompletableFuture.completedFuture(this);
    }

    public CompletableFuture<WebSocket> sendPong(ByteBuffer data) {
      return CompletableFuture.completedFuture(this);
    }

    public CompletableFuture<WebSocket> sendClose(int status, String reason) {
      return CompletableFuture.completedFuture(this);
    }

    public void request(long n) {
      requests += n;
    }

    public String getSubprotocol() {
      return "";
    }

    public boolean isOutputClosed() {
      return aborted;
    }

    public boolean isInputClosed() {
      return aborted;
    }

    public void abort() {
      aborted = true;
    }

    void emit(String data) {
      listener.onText(this, data, true);
    }

    void auth() {
      emit("{\"operation\":\"Authenticate\",\"success\":true}");
    }
  }

  StreamingRates source(Socket socket, Consumer<Socket> script) {
    return new StreamingRates(
        clock,
        secrets,
        listener -> {
          socket.listener = listener;
          listener.onOpen(socket);
          assertEquals(
              1, socket.requests, "Opening a socket must request its first incoming message");
          script.accept(socket);
          return CompletableFuture.completedFuture(socket);
        },
        Duration.ofMillis(20));
  }

  String price(String topic, String type, String ask, Instant time) {
    var body = Json.MAPPER.createObjectNode().put("Ask", ask).put("Date", time.toString());
    var envelope = Json.MAPPER.createObjectNode();
    envelope
        .putArray("messages")
        .addObject()
        .put("topic", topic)
        .put("type", type)
        .put("content", body.toString());
    return envelope.toString();
  }

  String price(String ask, Instant time) {
    return price("instrument:1118", "Trading.Instrument.Rate", ask, time);
  }

  @Test
  void staleSnapshotThenFreshFragmentedTickReturnsActualPriceAndCloses() throws Exception {
    var socket = new Socket();
    var rate =
        source(
                socket,
                s -> {
                  s.emit(price("90", NOW)); // Cannot accept a price before authentication.
                  s.auth();
                  s.emit(
                      "{\"messages\":[{\"topic\":\"instrument:1118\",\"type\":\"Trading.Instrument.Rate\",\"content\":\"{\\\"Date\\\":\\\"2026-09-21T14:00:00Z\\\",\\\"PriceRateID\\\":7}\"}]}");
                  s.emit(price("100", NOW.minusSeconds(61)));
                  s.emit(price("instrument:2", "Trading.Instrument.Rate", "2", NOW));
                  s.emit(price("instrument:1118", "Other", "3", NOW));
                  s.emit(price("0", NOW));
                  s.emit(price("-1", NOW));
                  s.emit(price("101", NOW.plusNanos(1)));
                  String fresh = price("102.25", NOW.minusSeconds(60));
                  s.listener.onText(s, fresh.substring(0, 20), false);
                  s.listener.onText(s, fresh.substring(20), true);
                })
            .fetch(1118);
    assertEquals(d("102.25"), rate.ask());
    assertEquals(NOW.minusSeconds(60), rate.timestamp());
    assertTrue(socket.aborted);
    assertTrue(socket.requests > 1);
    assertEquals(2, socket.sent.size());
    var auth = Json.MAPPER.readTree(socket.sent.get(0));
    assertEquals("Authenticate", auth.path("operation").asText());
    assertEquals("agent-fixture", auth.path("data").path("userKey").asText());
    assertEquals("app-fixture", auth.path("data").path("apiKey").asText());
    var sub = Json.MAPPER.readTree(socket.sent.get(1));
    assertEquals("Subscribe", sub.path("operation").asText());
    assertEquals("instrument:1118", sub.path("data").path("topics").get(0).asText());
    assertEquals(1, sub.path("data").path("topics").size());
    assertTrue(sub.path("data").path("snapshot").asBoolean());
  }

  @Test
  void matchingAcknowledgementsAndDuplicateAuthDoNotDuplicateSubscriptions() throws Exception {
    var socket = new Socket();
    var rate =
        source(
                socket,
                s -> {
                  try {
                    String authId = Json.MAPPER.readTree(s.sent.get(0)).path("id").asText();
                    s.emit(
                        "{\"operation\":\"Authenticate\",\"id\":\""
                            + authId
                            + "\",\"success\":true}");
                    s.auth();
                    assertEquals(2, s.sent.size());
                    String subId = Json.MAPPER.readTree(s.sent.get(1)).path("id").asText();
                    s.emit(
                        "{\"operation\":\"Subscribe\",\"id\":\"" + subId + "\",\"success\":true}");
                    s.emit(price("100", NOW));
                  } catch (IOException e) {
                    throw new AssertionError(e);
                  }
                })
            .fetch(1118);
    assertEquals(d("100"), rate.ask());
  }

  @Test
  void noFreshTickTimesOutAndLateConnectionsAreClosed() {
    var socket = new Socket();
    assertEquals(
        "QUOTE_REFRESH_TIMEOUT",
        assertThrows(IOException.class, () -> source(socket, Socket::auth).fetch(1118))
            .getMessage());
    assertTrue(socket.aborted);
    var pending = new CompletableFuture<WebSocket>();
    var rates = new StreamingRates(clock, secrets, listener -> pending, Duration.ofMillis(1));
    assertEquals(
        "QUOTE_REFRESH_TIMEOUT",
        assertThrows(IOException.class, () -> rates.fetch(1118)).getMessage());
    var late = new Socket();
    pending.complete(late);
    assertTrue(late.aborted);
    assertEquals(
        "INVALID_INSTRUMENT_ID",
        assertThrows(IOException.class, () -> rates.fetch(0)).getMessage());
    assertEquals(
        "INVALID_INSTRUMENT_ID",
        assertThrows(IOException.class, () -> rates.fetch(-1)).getMessage());
  }

  @Test
  void fragmentedMessagesCannotExceedTheCombinedSizeLimit() {
    var socket = new Socket();
    var listener = new StreamingRates.Prices(1118, clock, secrets);
    listener.onText(socket, "x".repeat(40000), false);
    assertFalse(socket.aborted);
    listener.onText(socket, "x".repeat(30000), false);
    assertTrue(socket.aborted);
    assertTrue(listener.result.isCompletedExceptionally());
  }

  @Test
  void malformedRejectedOversizedClosedAndFailedStreamsDoNotSupplyQuotes() {
    List<Consumer<Socket>> failures =
        List.of(
            s -> s.emit("not json"),
            s ->
                s.emit(
                    "{\"operation\":\"Authenticate\",\"success\":false,\"errorMessage\":\"sensitive\"}"),
            s -> s.emit("{\"operation\":\"Authenticate\",\"success\":\"true\"}"),
            s -> s.emit("{\"operation\":\"Authenticate\",\"id\":\"wrong\",\"success\":true}"),
            s -> {
              s.auth();
              s.emit("{\"operation\":\"Subscribe\",\"success\":false}");
            },
            s -> {
              s.auth();
              s.emit(price("invalid", NOW));
            },
            s -> s.emit("x".repeat(65537)),
            s -> s.listener.onClose(s, 1000, "sensitive"),
            s -> s.listener.onError(s, new IOException("sensitive")));
    for (var failure : failures) {
      var socket = new Socket();
      var error = assertThrows(IOException.class, () -> source(socket, failure).fetch(1118));
      assertTrue(error.getMessage().startsWith("QUOTE_"));
      assertFalse(error.toString().contains("sensitive"));
      assertTrue(socket.aborted);
    }
    var failedSend = new Socket();
    failedSend.failSend = true;
    assertThrows(IOException.class, () -> source(failedSend, s -> {}).fetch(1118));
    assertTrue(failedSend.aborted);
    var failedConnect =
        new StreamingRates(
            clock,
            secrets,
            l -> CompletableFuture.failedFuture(new IOException("sensitive")),
            Duration.ofMillis(20));
    assertEquals(
        "QUOTE_STREAM_UNAVAILABLE",
        assertThrows(IOException.class, () -> failedConnect.fetch(1118)).getMessage());
  }

  @Test
  void interruptionIsPreservedAndConnectionClosed() {
    var socket = new Socket();
    Thread.currentThread().interrupt();
    try {
      assertEquals(
          "QUOTE_REFRESH_INTERRUPTED",
          assertThrows(IOException.class, () -> source(socket, Socket::auth).fetch(1118))
              .getMessage());
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(socket.aborted);
    } finally {
      Thread.interrupted();
    }
  }
}
