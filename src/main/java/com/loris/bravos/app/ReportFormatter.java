package com.loris.bravos.app;

import com.loris.bravos.state.TradingState;
import java.util.*;
import java.util.regex.Pattern;

/** Presentation only. The durable diagnostic report remains the decision/audit source. */
public final class ReportFormatter {
  private static final Pattern BUY =
      Pattern.compile(
          "(OPEN|ADD) owner USD ([0-9.]+), internal USD ([0-9.]+), ceiling ([0-9.]+), stop ([0-9.]+)(, market)?");
  private static final Pattern CHANGE =
      Pattern.compile("(REDUCE|CLOSE|EARLY_EXIT|STOP) units ([0-9.]+|null), stop ([0-9.]+|null)");

  private ReportFormatter() {}

  public static List<String> blocks(TradingState state, boolean live) {
    Map<String, List<String>> grouped = new LinkedHashMap<>();
    List<String> general = new ArrayList<>();
    Set<String> symbols = new HashSet<>();
    state.book.cycles.values().forEach(c -> symbols.add(c.symbol));
    for (String line : state.report) {
      int split = line.indexOf(": ");
      if (split > 0 && symbols.contains(line.substring(0, split)))
        grouped
            .computeIfAbsent(line.substring(0, split), ignored -> new ArrayList<>())
            .add(line.substring(split + 2));
      else general.add("ACCOUNT  [REVIEW]\n" + field("Details", explain(line) + "."));
    }
    List<String> result = new ArrayList<>(general);
    grouped.forEach(
        (symbol, lines) -> {
          List<String> sentences = new ArrayList<>();
          boolean hasQuoteDetail = lines.stream().anyMatch(line -> line.startsWith("DETAIL "));
          for (String line : lines) {
            if (hasQuoteDetail && line.equals("WAIT_QUOTE QUOTE_NOT_EXECUTABLE")) continue;
            if (line.equals("READY POLICY_PASSED")) {
              sentences.add(
                  field(
                      "Checks",
                      live
                          ? "Pre-trade checks passed. See execution result below."
                          : "Passed. Checked again before execution."));
              continue;
            }
            var buy = BUY.matcher(line);
            if (buy.matches()) {
              sentences.add(
                  field(
                      live ? "Planned action" : "On live run",
                      buy.group(1).equals("OPEN") ? "Open position" : "Add to position"));
              sentences.add(field("Your money", "$" + buy.group(2)));
              boolean market = buy.group(6) != null;
              sentences.add(
                  field(market ? "Price check" : "Agent limit", "$" + buy.group(4) + " per unit"));
              if (market)
                sentences.add(
                    field(
                        "Order",
                        "Market buy. Final price can exceed the checked ceiling. An over-ceiling fill holds further purchases for review."));
              sentences.add(field("Bravos stop", "$" + buy.group(5)));
              sentences.add(
                  field(
                      "Internal funds",
                      "$" + buy.group(3) + " (agent accounting; not extra money)"));
              continue;
            }
            var change = CHANGE.matcher(line);
            if (change.matches()) {
              if (change.group(1).equals("STOP"))
                sentences.add(
                    field(
                        "Stop update",
                        (live ? "The planned update would set" : "A live run would request")
                            + " the exact Bravos stop of $"
                            + change.group(3)
                            + "."));
              else
                sentences.add(
                    field(
                        "Sale",
                        (live ? "The planned action was" : "A live run would request")
                            + " a sale of "
                            + change.group(2)
                            + " agent units and the corresponding copied exposure."));
              continue;
            }
            if (line.startsWith("NOT_FILLED ")) {
              sentences.add(
                  field(
                      "Result",
                      "No shares bought. "
                          + line.substring(11).replaceFirst("^CONFIRMED_NO_FILL ?", "")));
              sentences.add(
                  field(
                      "Next step",
                      "Other opportunities are checked in this run. A later run can retry after fresh checks; no automatic retry in this run."));
            } else if (line.startsWith("ORDER_PENDING_OR_REJECTED ")) {
              sentences.add(field("Result", "Not confirmed: " + line.substring(26)));
              sentences.add(
                  field(
                      "Next step",
                      "Further submissions are held. Keep the saved state; the next invocation reconciles unresolved orders first."));
            } else if (line.startsWith("DETAIL "))
              sentences.add(field("Reason", line.substring(7)));
            else if (line.startsWith("CONFIRMED "))
              sentences.add(
                  field(
                      "Result",
                      "Confirmed by broker read-back: "
                          + line.substring(10).replace("owner USD ", "owner amount $")
                          + "."));
            else if (line.startsWith("PARTIAL_FILL_KEPT "))
              sentences.add(
                  field(
                      "Partial fill",
                      "Only part filled; the protected filled amount is retained. "
                          + line.substring(18)
                              .replace("owner USD ", "Filled $")
                              .replace("shortfall USD ", "unfilled $")
                          + "."));
            else sentences.add(field("Details", explain(line) + "."));
          }
          result.add(
              symbol
                  + "  ["
                  + status(lines)
                  + "]\n"
                  + "-".repeat(64)
                  + "\n"
                  + String.join("\n", sentences));
        });
    return List.copyOf(result);
  }

  private static String status(List<String> lines) {
    if (lines.stream().anyMatch(l -> l.startsWith("NOT_FILLED "))) return "NOT FILLED";
    if (lines.stream().anyMatch(l -> l.startsWith("BLOCKED "))) return "BLOCKED";
    if (lines.stream().anyMatch(l -> l.startsWith("WAIT_QUOTE "))) return "WAITING FOR PRICE";
    if (lines.stream().anyMatch(l -> l.startsWith("WATCH_PRICE "))) return "WATCHING PRICE";
    if (lines.stream()
        .anyMatch(
            l ->
                !(l.equals("READY POLICY_PASSED")
                    || l.startsWith("DETAIL ")
                    || l.startsWith("CONFIRMED ")
                    || l.startsWith("PARTIAL_FILL_KEPT ")
                    || l.equals("HOLD_UNCHANGED")
                    || l.startsWith("TERMINAL ")
                    || l.startsWith("NO_POSITION ")
                    || BUY.matcher(l).matches()
                    || CHANGE.matcher(l).matches()))) return "REVIEW";
    if (lines.stream().anyMatch(l -> l.startsWith("CONFIRMED "))) return "EXECUTION RESULTS";
    if (lines.contains("READY POLICY_PASSED")) return "READY";
    if (lines.contains("HOLD_UNCHANGED")) return "UNCHANGED";
    if (lines.stream().anyMatch(l -> l.startsWith("TERMINAL ") || l.startsWith("NO_POSITION ")))
      return "NO ENTRY";
    return "PLANNED ACTION";
  }

  /** Fixed-width ASCII output also stays readable when redirected to a file. */
  private static String field(String label, String value) {
    String prefix = "  " + String.format(Locale.ROOT, "%-16s", label + ":");
    String continuation = " ".repeat(prefix.length());
    StringBuilder result = new StringBuilder(prefix);
    int column = prefix.length();
    for (String word : value.split("\\s+")) {
      if (column > prefix.length()) {
        if (column + 1 + word.length() > 76) {
          result.append('\n').append(continuation);
          column = prefix.length();
        } else {
          result.append(' ');
          column++;
        }
      }
      while (word.length() > 76 - column) {
        int count = 76 - column;
        result.append(word, 0, count).append('\n').append(continuation);
        word = word.substring(count);
        column = prefix.length();
      }
      result.append(word);
      column += word.length();
    }
    return result.toString();
  }

  private static String explain(String line) {
    String code = line.replaceFirst("^(BLOCKED|WAIT_QUOTE|WATCH_PRICE|TERMINAL|NO_POSITION) ", "");
    return switch (code) {
      case "CAPPED_ORDER_REQUIRES_REAL_ASSET" ->
          "No order will be sent: this investment is configured as a CFD, and eToro rejects price-capped IOC orders for CFDs. Buying it with a market order would remove your maximum purchase price. It remains skipped under your current rules";
      case "QUOTE_REFRESH_EXHAUSTED" ->
          "No purchase is proposed: the app requested a quote, tried eToro's live price stream, then requested another quote. None provided a usable price within the freshness rule. Your entry ceiling and Bravos stop remain unchanged";
      case "QUOTE_REFRESH_TIMEOUT" ->
          "No purchase is proposed: the usual quote was stale, and eToro's live price stream did not provide a fresh price within 20 seconds. The app attempted to refresh it during this run; your price ceiling and stop remain unchanged";
      case "QUOTE_STREAM_UNAVAILABLE" ->
          "No purchase is proposed: the usual quote was stale and the live price stream could not provide a usable quote. The app attempted to refresh it during this run";
      case "QUOTE_REFRESH_INTERRUPTED" ->
          "The live-price refresh was interrupted. No purchase is proposed for this investment";
      case "BOTH_ACCOUNTS_OPENING_DISABLED" ->
          "No purchase is possible: eToro currently refuses new purchases of this investment on both your agent and main accounts. The app will check again on the next run; this is a broker restriction, not missing setup";
      case "AGENT_OPENING_DISABLED" ->
          "No purchase is possible: eToro currently refuses new purchases of this investment in the agent account. The app will check again on the next run";
      case "OWNER_OPENING_DISABLED" ->
          "No purchase is possible: eToro currently refuses new purchases of this investment in your main account. The app will check again on the next run";
      case "INSTRUMENT_NOT_LISTED" ->
          "No purchase is proposed: eToro returned no listing under the Bravos ticker or its US alias. The app will repeat the lookup next run. It will not buy a different fund as a substitute";
      case "INSTRUMENT_PROFILE_REQUIRED" ->
          "No purchase is proposed: eToro lists a candidate, but the app still needs its exact identity and unleveraged execution setup established. This is an application setup gap, not a broker refusal";
      case "INSTRUMENT_LOOKUP_INCOMPLETE" ->
          "No purchase is proposed because eToro returned an incomplete instrument lookup. The app will retry next run";
      case "AGENT_INSTRUMENT_INELIGIBLE" ->
          "No purchase is proposed because the agent account does not permit the required unleveraged order and stop settings for this investment";
      case "INSTRUMENT_UNVERIFIED" ->
          "No purchase is proposed because the app has not verified that this exact investment can be bought without leverage on your eToro accounts. It needs a verified instrument setup and broker permission; no substitute will be bought";
      case "QUOTE_NOT_EXECUTABLE" ->
          "No purchase is proposed because the quote failed the open-market, tradability, realtime USD or 60-second freshness checks. The opening will be checked again on a later run while Bravos holds it";
      case "ABOVE_ORIGINAL_CEILING" ->
          "No purchase is proposed because the asking price exceeds the permitted entry ceiling. The opening remains on the watchlist while Bravos holds it; the maximum will not be raised";
      case "HOLD_UNCHANGED" ->
          "The existing holding has no new action to take. Its opening will not be bought again, and it will not be topped up or rebalanced just because equity or cash changed";
      case "POSITION_EXITED_NO_REENTRY", "CYCLE_ENDED" ->
          "This position cycle has ended. No re-entry into this old opening will be made";
      case "ADDITION_SESSION_EXPIRED" ->
          "This addition's eligible session has expired. No purchase is proposed for that addition";
      case "INVALID_OR_CROSSED_STOP" ->
          "No purchase is proposed because Bravos's stop is missing, invalid or incompatible with the current entry price. The app will not invent or lower the stop";
      case "INSUFFICIENT_CASH" ->
          "No purchase is proposed because available cash cannot cover the intended amount and estimated costs. The app will not resize it or redistribute another position's allocation";
      case "BELOW_MINIMUM" ->
          "No purchase is proposed because the intended amount is below the broker's minimum. The app will not increase it to qualify";
      case "OWNER_INSTRUMENT_INELIGIBLE" ->
          "No purchase is proposed because the owner's account does not permit this instrument with the required trading settings";
      case "COST_REQUEST_STALE" ->
          "No purchase is proposed because a new fee estimate could not be obtained within 60 seconds. A later run will request it again";
      case "ACCOUNT_ACTIVITY_UNVERIFIED" ->
          "The account is inactive, has pending activity or has incomplete order information. New actions are held until the account can be reconciled";
      case "UNRESOLVED_ORDER_BLOCKS_NEW_SUBMISSIONS" ->
          "An earlier order is still unresolved. No further orders will be submitted until its actual outcome is known; it will not be blindly repeated";
      case "COPY_PROTECTION_UNVERIFIED" ->
          "The owner's copied position does not yet have verified protection at the exact Bravos stop. Further purchases are held for reconciliation";
      case "COPY_EXIT_PENDING" ->
          "The agent position has gone but its copied position has not yet been confirmed closed. Further actions are held for reconciliation";
      default ->
          "Review required; the recorded diagnostic is "
              + line
              + ". Any blocked or unresolved action remains held";
    };
  }
}
