package com.loris.bravos.broker;

import com.loris.bravos.app.Secrets;
import com.loris.bravos.util.Json;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;

/** Bounded market-data subscription on eToro's documented socket; no private topics. */
public final class StreamingRates {
  public record Rate(BigDecimal ask, Instant timestamp) {}

  @FunctionalInterface
  interface Connector {
    CompletableFuture<WebSocket> connect(WebSocket.Listener listener);
  }

  private final Clock clock;
  private final Connector connector;
  private final Duration timeout;
  private final Secrets secrets;

  public StreamingRates(Clock clock, Secrets secrets) {
    this(
        clock,
        secrets,
        listener ->
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("wss://ws.etoro.com/ws"), listener),
        Duration.ofSeconds(20));
  }

  StreamingRates(Clock clock, Secrets secrets, Connector connector, Duration timeout) {
    this.clock = clock;
    this.secrets = secrets;
    this.connector = connector;
    this.timeout = timeout;
  }

  public Rate fetch(long instrumentId) throws IOException {
    if (instrumentId <= 0) throw new IOException("INVALID_INSTRUMENT_ID");
    var listener = new Prices(instrumentId, clock, secrets);
    CompletableFuture<WebSocket> connection = connector.connect(listener);
    connection.whenComplete(
        (socket, failure) -> {
          if (failure != null)
            listener.result.completeExceptionally(new IOException("QUOTE_STREAM_UNAVAILABLE"));
          else if (listener.result.isDone()) socket.abort();
        });
    try {
      return listener.result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("QUOTE_REFRESH_INTERRUPTED");
    } catch (TimeoutException e) {
      throw new IOException("QUOTE_REFRESH_TIMEOUT");
    } catch (ExecutionException e) {
      throw new IOException(
          e.getCause() instanceof IOException
              ? e.getCause().getMessage()
              : "QUOTE_STREAM_UNAVAILABLE");
    } finally {
      listener.result.cancel(false);
      // Also covers a connection finishing after timeout without leaking a subscription.
      connection.thenAccept(WebSocket::abort);
    }
  }

  static final class Prices implements WebSocket.Listener {
    final CompletableFuture<Rate> result = new CompletableFuture<>();
    private final String topic;
    private final Clock clock;
    private final String requestId = UUID.randomUUID().toString();
    private final String authId = UUID.randomUUID().toString();
    private final StringBuilder fragments = new StringBuilder();
    private final Secrets secrets;
    private boolean authenticated;

    Prices(long id, Clock clock, Secrets secrets) {
      this.topic = "instrument:" + id;
      this.clock = clock;
      this.secrets = secrets;
    }

    @Override
    public void onOpen(WebSocket socket) {
      var credentials = secrets.headers(false, authId);
      var request =
          Json.MAPPER.createObjectNode().put("id", authId).put("operation", "Authenticate");
      request
          .putObject("data")
          .put("userKey", credentials.get("x-user-key"))
          .put("apiKey", credentials.get("x-api-key"));
      send(socket, request.toString());
      socket.request(1);
    }

    private void subscribe(WebSocket socket) {
      var request =
          Json.MAPPER.createObjectNode().put("id", requestId).put("operation", "Subscribe");
      var data = request.putObject("data").put("snapshot", true);
      data.putArray("topics").add(topic);
      send(socket, request.toString());
    }

    private void send(WebSocket socket, String message) {
      socket
          .sendText(message, true)
          .whenComplete(
              (sent, failure) -> {
                if (failure != null)
                  result.completeExceptionally(new IOException("QUOTE_STREAM_UNAVAILABLE"));
              });
    }

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
      if (fragments.length() + data.length() > 65_536) {
        result.completeExceptionally(new IOException("QUOTE_STREAM_OVERSIZED"));
        socket.abort();
        return null;
      }
      fragments.append(data);
      if (last) {
        try {
          var envelope = Json.MAPPER.readTree(fragments.toString());
          String operation = envelope.path("operation").asText();
          if (operation.equals("Authenticate") || operation.equals("Subscribe")) {
            String expected = operation.equals("Authenticate") ? authId : requestId;
            if (envelope.has("id") && !expected.equals(envelope.path("id").asText()))
              throw new IOException("QUOTE_ACK_MISMATCH");
            if (!envelope.path("success").isBoolean() || !envelope.path("success").booleanValue())
              throw new IOException("QUOTE_SUBSCRIPTION_REJECTED");
            if (operation.equals("Authenticate") && !authenticated) {
              authenticated = true;
              subscribe(socket);
            }
          }
          var messages = envelope.path("messages");
          if (authenticated && messages.isArray())
            for (var message : messages) {
              if (!topic.equals(message.path("topic").asText())
                  || !"Trading.Instrument.Rate".equals(message.path("type").asText())) continue;
              var rate = Json.MAPPER.readTree(message.path("content").asText());
              if (!rate.has("Ask")) continue;
              BigDecimal ask = new BigDecimal(rate.path("Ask").asText());
              Instant date = Instant.parse(rate.path("Date").asText());
              Instant now = clock.instant();
              if (ask.signum() > 0
                  && !date.isAfter(now)
                  && Duration.between(date, now).compareTo(Duration.ofSeconds(60)) <= 0)
                result.complete(new Rate(ask, date));
            }
        } catch (Exception invalid) {
          result.completeExceptionally(
              new IOException(
                  invalid instanceof java.time.format.DateTimeParseException
                      ? "QUOTE_STREAM_DATE_INVALID"
                      : invalid instanceof NumberFormatException
                          ? "QUOTE_STREAM_PRICE_INVALID"
                          : "QUOTE_STREAM_INVALID"));
        }
        fragments.setLength(0);
      }
      socket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
      result.completeExceptionally(new IOException("QUOTE_STREAM_CLOSED"));
      return null;
    }

    @Override
    public void onError(WebSocket socket, Throwable failure) {
      result.completeExceptionally(new IOException("QUOTE_STREAM_UNAVAILABLE"));
    }
  }
}
