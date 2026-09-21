package com.loris.bravos.app;

import static com.loris.bravos.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.loris.bravos.state.TradingState;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReportFormatterTest {
  TradingState state(String... lines) {
    var state = new TradingState();
    state.book.cycles.put("opening", cycle());
    state.report.addAll(List.of(lines));
    return state;
  }

  @Test
  void readyAndAmountBecomeOneParagraphWithoutClaimingARealFill() {
    var state =
        state(
            "CF: READY POLICY_PASSED",
            "CF: OPEN owner USD 184.40, internal USD 400.00, ceiling 132.97, stop 121.5");
    var paragraphs = ReportFormatter.paragraphs(state, false);
    assertEquals(1, paragraphs.size());
    String text = paragraphs.getFirst();
    assertTrue(text.contains("would attempt to open $184.40 of your money"));
    assertTrue(text.contains("agent limit of $132.97"));
    assertTrue(text.contains("Bravos's stop at $121.5"));
    assertTrue(
        text.contains("$400.00 of the agent's internal balance, not additional owner money"));
    assertFalse(text.contains("Confirmed"));
    assertEquals(2, state.report.size());
    assertTrue(ReportFormatter.paragraphs(state, true).getFirst().contains("planned trade"));
  }

  @Test
  void specificQuoteReasonReplacesGenericMessageAndUnknownFailuresStayVisible() {
    var paragraphs =
        ReportFormatter.paragraphs(
            state(
                "CF: WAIT_QUOTE QUOTE_NOT_EXECUTABLE",
                "CF: DETAIL No purchase is proposed because the quote is 90 seconds old; the maximum is 60 seconds. A later run will reassess.",
                "UNEXPLAINED_HOLDING_CHANGE"),
            false);
    assertEquals(2, paragraphs.size());
    assertTrue(paragraphs.get(0).contains("UNEXPLAINED_HOLDING_CHANGE"));
    assertTrue(paragraphs.get(1).contains("90 seconds old"));
    assertFalse(paragraphs.get(1).contains("QUOTE_NOT_EXECUTABLE"));
  }

  @Test
  void confirmedPartialFillAndProtectionActionsAreDistinguishedFromPlans() {
    String paragraph =
        ReportFormatter.paragraphs(
                state(
                    "CF: ADD owner USD 100.00, internal USD 200.00, ceiling 101, stop 90",
                    "CF: CONFIRMED ADD owner USD 40.00",
                    "CF: PARTIAL_FILL_KEPT owner USD 40.00, shortfall USD 60.00; no automatic top-up",
                    "CF: STOP units null, stop 90",
                    "CF: REDUCE units 0.5, stop null"),
                true)
            .getFirst();
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
      String paragraph = ReportFormatter.paragraphs(state("CF: BLOCKED " + code), false).getFirst();
      assertFalse(paragraph.contains(code), paragraph);
      assertFalse(paragraph.contains("Ready for an entry"), paragraph);
      assertFalse(paragraph.contains("Confirmed by broker"), paragraph);
    }
  }
}
