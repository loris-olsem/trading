package com.loris.bravos.app;

import com.loris.bravos.broker.*;
import com.loris.bravos.source.*;
import com.loris.bravos.util.Json;
import java.net.URI;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.List;

/** Read-only source diagnostic. Raw member content stays in ignored local state. */
public final class Capture {
  private Capture() {}

  public static void main(String[] args) throws Exception {
    Path root = Path.of("").toAbsolutePath();
    if (args.length > 0 && args[0].equals("--replay")) {
      var scan =
          Json.MAPPER.readValue(
              root.resolve("state/capture/scan.json").toFile(), BravosSource.Scan.class);
      int success = 0;
      var alerts = new java.util.ArrayList<com.loris.bravos.domain.Model.Alert>();
      for (var a : scan.articles()) {
        try {
          alerts.add(new AlertParser().parse(a.key(), a.url(), a.date(), a.title(), a.body()));
          success++;
        } catch (IllegalArgumentException e) {
          System.out.println(a.key() + " " + e.getMessage());
        }
      }
      var book = Workflow.initialBook(alerts, LocalDate.of(2026, 8, 21));
      var dashboard =
          new BravosSource(
                  (m, p, h, b) -> {
                    throw new java.io.IOException();
                  })
              .dashboard(
                  org.jsoup.Jsoup.parse(
                      Files.readString(root.resolve("state/capture/research.html"))));
      System.out.println(
          "Parsed "
              + success
              + " / "
              + scan.articles().size()
              + "; dashboard matches="
              + Workflow.dashboardMatches(book, dashboard)
              + "; blockers="
              + book.blockers);
      for (var c : book.cycles.values())
        if (c.sourceOpen)
          System.out.println(
              c.symbol + " weight=" + c.weight + " stop=" + c.stop + " blocker=" + c.blocker);
      return;
    }
    Secrets secrets = Secrets.load(root);
    if (args.length > 0 && args[0].equals("--broker")) {
      var client =
          new EtoroClient(
              new HttpTransport(URI.create("https://public-api.etoro.com"), false),
              secrets,
              Configuration.load(root.resolve("config/trading.json")),
              java.time.Clock.systemUTC(),
              false);
      var account = client.account();
      Files.createDirectories(root.resolve("state/capture"));
      Json.MAPPER.writeValue(root.resolve("state/capture/account.json").toFile(), account);
      var http = new HttpTransport(URI.create("https://public-api.etoro.com"), false);
      for (boolean owner : List.of(false, true)) {
        var response =
            http.request(
                "GET",
                "/api/v1/trading/info/portfolio",
                secrets.headers(owner, java.util.UUID.randomUUID().toString()),
                null);
        if (response.status() == 200) {
          Files.writeString(
              root.resolve("state/capture/" + (owner ? "owner" : "agent") + "-portfolio.json"),
              response.body());
          var portfolio = Json.MAPPER.readTree(response.body()).path("clientPortfolio");
          var scope = owner ? portfolio.path("mirrors").get(0) : portfolio;
          for (String field :
              List.of(
                  "orders",
                  "stockOrders",
                  "ordersForOpen",
                  "ordersForClose",
                  "ordersForCloseMultiple",
                  "entryOrders",
                  "exitOrders",
                  "delayedOrderForOpen",
                  "delayedOrderForClose"))
            System.out.println(
                (owner ? "owner" : "agent")
                    + " "
                    + field
                    + "="
                    + (scope != null && scope.path(field).isArray()
                        ? scope.path(field).size()
                        : "unknown"));
        } else System.out.println("Portfolio diagnostic HTTP " + response.status());
      }
      System.out.println(
          "Owner equity="
              + account.ownerEquity()
              + "; internal equity="
              + account.agentEquity()
              + "; orders complete="
              + account.ordersComplete()
              + "; active="
              + account.active());
      return;
    }
    var transport = new HttpTransport(URI.create("https://bravosresearch.com"), true);
    var source = new BravosSource(transport);
    source.login(secrets.username(), secrets.password());
    Path output = root.resolve("state/capture");
    Files.createDirectories(output);
    for (String page : List.of("research", "category/portfolio-update")) {
      var response = transport.request("GET", "/" + page + "/", java.util.Map.of(), null);
      if (response.status() != 200) throw new IllegalStateException("SOURCE_READ_FAILED");
      Files.writeString(output.resolve(page.replace('/', '-') + ".html"), response.body());
    }
    if (args.length > 0) {
      var scan = source.scan(LocalDate.parse(args[0]), List.of());
      Json.MAPPER.writeValue(output.resolve("scan.json").toFile(), scan);
      System.out.println(
          "articles="
              + scan.articles().size()
              + " parsed="
              + scan.alerts().size()
              + " complete="
              + scan.complete()
              + " errors="
              + scan.errors().size());
    }
    System.out.println("Read-only capture saved under state/capture.");
  }
}
