package com.loris.bravos.app;

import static com.loris.bravos.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.loris.bravos.state.TradingState;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReportFormatterTest {
  @Test
  void availabilityReasonsDistinguishBrokerRefusalFromMissingSetup() {
    var messages =
        Map.of(
            "BOTH_ACCOUNTS_OPENING_DISABLED", "both your agent and main accounts",
            "AGENT_OPENING_DISABLED", "in the agent account",
            "OWNER_OPENING_DISABLED", "in your main account",
            "INSTRUMENT_NOT_LISTED", "no listing under the Bravos ticker",
            "INSTRUMENT_PROFILE_REQUIRED", "application setup gap",
            "INSTRUMENT_LOOKUP_INCOMPLETE", "incomplete instrument lookup",
            "AGENT_INSTRUMENT_INELIGIBLE", "required unleveraged order and stop settings");
    messages.forEach(
        (code, phrase) ->
            assertTrue(
                ReportFormatter.blocks(state("CF: BLOCKED " + code), false)
                    .getFirst()
                    .replaceAll("\\s+", " ")
                    .contains(phrase)));
  }

  TradingState state(String... lines) {
    var state = new TradingState();
    state.book.cycles.put("opening", cycle());
    state.report.addAll(List.of(lines));
    return state;
  }

  @Test
  void readyEntryHasSeparateReadableFieldsWithoutClaimingARealFill() {
    var state =
        state(
            "CF: READY POLICY_PASSED",
            "CF: OPEN owner USD 184.40, internal USD 400.00, ceiling 132.97, stop 121.5");
    var paragraphs = ReportFormatter.blocks(state, false);
    assertEquals(1, paragraphs.size());
    String text = paragraphs.getFirst().replaceAll("\\s+", " ");
    assertTrue(paragraphs.getFirst().lines().allMatch(line -> line.length() <= 76));
    assertTrue(text.contains("CF [READY]"));
    assertTrue(text.contains("On live run:"));
    assertTrue(text.contains("Open position"));
    assertTrue(text.contains("Your money: $184.40"));
    assertTrue(text.contains("Agent limit: $132.97 per unit"));
    assertTrue(text.contains("Bravos stop: $121.5"));
    assertTrue(text.contains("$400.00 (agent accounting; not extra money)"));
    assertFalse(text.contains("Confirmed"));
    assertEquals(2, state.report.size());
    assertTrue(
        ReportFormatter.blocks(state, true)
            .getFirst()
            .replaceAll("\\s+", " ")
            .contains("Planned action"));
  }

  @Test
  void specificQuoteReasonReplacesGenericMessageAndUnknownFailuresStayVisible() {
    var paragraphs =
        ReportFormatter.blocks(
            state(
                "CF: WAIT_QUOTE QUOTE_NOT_EXECUTABLE",
                "CF: DETAIL No purchase is proposed because the quote is 90 seconds old; the maximum is 60 seconds. A later run will reassess.",
                "UNEXPLAINED_HOLDING_CHANGE"),
            false);
    assertEquals(2, paragraphs.size());
    assertTrue(paragraphs.get(0).contains("UNEXPLAINED_HOLDING_CHANGE"));
    assertTrue(paragraphs.get(1).replaceAll("\\s+", " ").contains("90 seconds old"));
    assertFalse(paragraphs.get(1).contains("QUOTE_NOT_EXECUTABLE"));
  }

  @Test
  void confirmedPartialFillAndProtectionActionsAreDistinguishedFromPlans() {
    String paragraph =
        ReportFormatter.blocks(
                state(
                    "CF: ADD owner USD 100.00, internal USD 200.00, ceiling 101, stop 90",
                    "CF: CONFIRMED ADD owner USD 40.00",
                    "CF: PARTIAL_FILL_KEPT owner USD 40.00, shortfall USD 60.00; no automatic top-up",
                    "CF: STOP units null, stop 90",
                    "CF: REDUCE units 0.5, stop null"),
                true)
            .getFirst()
            .replaceAll("\\s+", " ");
    assertTrue(paragraph.contains("Confirmed by broker read-back: ADD owner amount $40.00"));
    assertTrue(paragraph.contains("unfilled $60.00"));
    assertTrue(paragraph.contains("no automatic top-up"));
    assertTrue(paragraph.contains("planned update would set the exact Bravos stop of $90"));
    assertTrue(paragraph.contains("sale of 0.5 agent units"));
    assertFalse(paragraph.contains("stop null"));
  }

  @Test
  void holdsExplainWhyAndWhatHappensNextWithoutInventingEligibility() {
    for (String code :
        List.of(
            "INSTRUMENT_UNVERIFIED",
            "QUOTE_NOT_EXECUTABLE",
            "ABOVE_ORIGINAL_CEILING",
            "HOLD_UNCHANGED",
            "POSITION_EXITED_NO_REENTRY",
            "CYCLE_ENDED",
            "ADDITION_SESSION_EXPIRED",
            "INVALID_OR_CROSSED_STOP",
            "INSUFFICIENT_CASH",
            "BELOW_MINIMUM",
            "OWNER_INSTRUMENT_INELIGIBLE",
            "COST_REQUEST_STALE",
            "ACCOUNT_ACTIVITY_UNVERIFIED",
            "UNRESOLVED_ORDER_BLOCKS_NEW_SUBMISSIONS",
            "COPY_PROTECTION_UNVERIFIED",
            "COPY_EXIT_PENDING")) {
      String paragraph =
          ReportFormatter.blocks(state("CF: BLOCKED " + code), false)
              .getFirst()
              .replaceAll("\\s+", " ");
      assertFalse(paragraph.contains(code), paragraph);
      assertFalse(paragraph.contains("Ready for an entry"), paragraph);
      assertFalse(paragraph.contains("Confirmed by broker"), paragraph);
    }
  }
}
