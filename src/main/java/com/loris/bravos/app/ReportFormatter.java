package com.loris.bravos.app;

import com.loris.bravos.state.TradingState;
import java.util.*;
import java.util.regex.Pattern;

/** Presentation only. The durable diagnostic report remains the decision/audit source. */
public final class ReportFormatter {
  private static final Pattern BUY =
      Pattern.compile(
          "(OPEN|ADD) owner USD ([0-9.]+), internal USD ([0-9.]+), ceiling ([0-9.]+), stop ([0-9.]+)");
  private static final Pattern CHANGE =
      Pattern.compile("(REDUCE|CLOSE|EARLY_EXIT|STOP) units ([0-9.]+|null), stop ([0-9.]+|null)");

  private ReportFormatter() {}

  public static List<String> paragraphs(TradingState state, boolean live) {
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
      else general.add("Account review: " + explain(line) + ".");
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
                  live
                      ? "The entry passed its pre-trade checks."
                      : "Ready for an entry if the checks still pass at execution.");
              continue;
            }
            var buy = BUY.matcher(line);
            if (buy.matches()) {
              sentences.add(
                  (live ? "The planned trade was to " : "A live run would attempt to ")
                      + (buy.group(1).equals("OPEN") ? "open" : "add")
                      + " $"
                      + buy.group(2)
                      + " of your money, with an agent limit of $"
                      + buy.group(4)
                      + " per unit and Bravos's stop at $"
                      + buy.group(5)
                      + ". This uses $"
                      + buy.group(3)
                      + " of the agent's internal balance, not additional owner money.");
              continue;
            }
            var change = CHANGE.matcher(line);
            if (change.matches()) {
              if (change.group(1).equals("STOP"))
                sentences.add(
                    (live ? "The planned update would set" : "A live run would request")
                        + " the exact Bravos stop of $"
                        + change.group(3)
                        + ".");
              else
                sentences.add(
                    (live ? "The planned action was" : "A live run would request")
                        + " a sale of "
                        + change.group(2)
                        + " agent units and the corresponding copied exposure.");
              continue;
            }
            if (line.startsWith("DETAIL ")) sentences.add(line.substring(7));
            else if (line.startsWith("CONFIRMED "))
              sentences.add(
                  "Confirmed by broker read-back: "
                      + line.substring(10).replace("owner USD ", "owner amount $")
                      + ".");
            else if (line.startsWith("PARTIAL_FILL_KEPT "))
              sentences.add(
                  "Only part filled; the protected filled amount is retained. "
                      + line.substring(18)
                          .replace("owner USD ", "Filled $")
                          .replace("shortfall USD ", "unfilled $")
                      + ".");
            else sentences.add(explain(line) + ".");
          }
          result.add(symbol + ": " + String.join(" ", sentences));
        });
    return List.copyOf(result);
  }

  private static String explain(String line) {
    String code = line.replaceFirst("^(BLOCKED|WAIT_QUOTE|WATCH_PRICE|TERMINAL|NO_POSITION) ", "");
    return switch (code) {
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
