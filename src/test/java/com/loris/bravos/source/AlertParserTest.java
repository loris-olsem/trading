package com.loris.bravos.source;

import static com.loris.bravos.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.loris.bravos.domain.Model.*;
import java.time.LocalDate;
import org.junit.jupiter.api.*;

class AlertParserTest {
  final AlertParser parser = new AlertParser();

  Alert parse(String title, String body) {
    return parser.parse(
        "post:1", "https://bravosresearch.com/x", LocalDate.of(2026, 9, 19), title, body);
  }

  String opening =
      "We are initiating a position in CF ($CF) at $130.37 with a weight of 5.\n\nEntry: $130.37\nTake Profit (TP): $147.50, $153, and $160\nSuggested Stop Loss (SL): $119.5\nWeight Allocation: 5";

  @Test
  void conflictingInlineFactsAndConditionalQuantitiesAreHeld() {
    assertThrows(
        IllegalArgumentException.class,
        () -> parse("Initiating (CF)", opening.replace("weight of 5", "weight of 6")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            parse(
                "Initiating (CF)",
                opening.replace("with a weight", "with a stop at $120 and a weight")));
    assertEquals(
        d("119.5"),
        parse(
                "Initiating (CF)",
                opening.replace("with a weight", "with a stop at $119.50 and a weight"))
            .stop());
    assertThrows(
        IllegalArgumentException.class,
        () -> parse("Initiating (CF)", opening + "\nWe will sell half at $150."));
    assertThrows(
        IllegalArgumentException.class,
        () -> parse("Initiating (CF)", opening + "\nTake Profit (TP): $160"));
    assertThrows(
        IllegalArgumentException.class,
        () -> parse("Closing (CF)", "We are increasing our position at $100."));
    assertThrows(
        IllegalArgumentException.class,
        () -> parse("Raising Stop (CF)", "We are raising the stop to $95."));
  }

  @Test
  void openingReadsAllFields() {
    Alert a = parse("Initiating Long on CF (CF) - Breakout", opening);
    assertEquals(Action.OPEN, a.action());
    assertEquals("CF", a.symbol());
    assertEquals(d("130.37"), a.price());
    assertEquals(d("5"), a.after());
    assertEquals(d("119.5"), a.stop());
    assertEquals(3, a.targets().size());
    assertEquals(64, a.hash().length());
  }

  @Test
  void bundledTrimAndStopSurvive() {
    Alert a =
        parse(
            "Booking Partial Profits in CF (CF)",
            "We are booking partial profits at $141.12, reducing our position from a weight of 5 to a weight of 4, while also raising our stop from $119.50 to $121.50.");
    assertEquals(Action.REDUCE, a.action());
    assertEquals(d("5"), a.before());
    assertEquals(d("4"), a.after());
    assertEquals(d("121.50"), a.stop());
  }

  @Test
  void decimalCommaAndAdditionWeightVariants() {
    Alert a =
        parse(
            "Increasing Exposure to Teva (TEVA)",
            "We are increasing our allocation at $37,67, and increasing from a weight of 3 to a weight of 5, while also raising our stop from $32 to $33.");
    assertEquals(d("37.67"), a.price());
    assertEquals(d("33"), a.stop());
    Alert b =
        parse(
            "Increasing Exposure (IBB)",
            "We are increasing our position at $192.3, adding a weight of 3 to bring our total allocation to a weight of 7.");
    assertEquals(d("4"), b.before());
    assertEquals(d("7"), b.after());
  }

  @Test
  void ignoresHistoricalPricesInLaterParagraphs() {
    Alert a =
        parse(
            "Increasing Exposure (EWJ)",
            "We are increasing our allocation at $94.65, and increasing the weight allocation from 5 to 8.\n\nThis position was entered on May 08, 2026, at $92.");
    assertEquals(d("94.65"), a.price());
  }

  @Test
  void closesAndStandaloneStops() {
    Alert a = parse("Closing Position in CF (CF)", "We are closing our position in CF at $99.");
    assertEquals(Action.CLOSE, a.action());
    assertEquals(d("0"), a.after());
    Alert b = parse("Raising Stop on CF (CF)", "We are raising our stop from $90 to $95.");
    assertEquals(Action.STOP, b.action());
    assertEquals(d("95"), b.stop());
  }

  @Test
  void malformedAndUnknownInstructionsFailClosed() {
    assertThrows(IllegalArgumentException.class, () -> parse("Discussion (CF)", opening));
    assertThrows(IllegalArgumentException.class, () -> parse("Initiating CF", opening));
    assertThrows(IllegalArgumentException.class, () -> parse("Initiating (CF) (XLF)", opening));
    assertThrows(
        IllegalArgumentException.class,
        () -> parse("Initiating (CF)", opening.replace("Entry: $130.37", "Entry: $130.38")));
    assertThrows(
        IllegalArgumentException.class,
        () -> parse("Initiating (CF)", opening + "\nWeight Allocation: 8"));
    assertThrows(
        IllegalArgumentException.class,
        () -> parse("Initiating (CF)", opening.replace("Suggested Stop Loss (SL): $119.5", "")));
    assertThrows(
        IllegalArgumentException.class,
        () -> parse("Increasing (CF)", "We are increasing our position at $100."));
    assertThrows(
        IllegalArgumentException.class,
        () -> parse("Reducing (CF)", "We are reducing from 5 to 8 at $100."));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            parse(
                "Reducing (CF)",
                "We are reducing from 5 to 4 at $100 and raising our stop to an unspecified price."));
  }

  @Test
  void whitespaceHashIgnoresPageFormatting() {
    assertEquals(AlertParser.hash("a b c"), AlertParser.hash(" a\n b\u00a0c "));
  }

  @Test
  void actualTextVariantsAndDuplicateStopRestatementAgree() {
    Alert a =
        parse(
            "Increasing (TEVA)",
            "We are increasing at $37,67 from 3 to 5 while raising our stop from $32 to $33.\nWe are also raising our stop to $33.");
    assertEquals(d("33"), a.stop());
    Alert b =
        parse(
            "Initiating (ASML)",
            opening
                .replace("We are", "We’re")
                .replace("130.37", "1,564.48")
                .replace("119.5", "1,420"));
    assertEquals(d("1564.48"), b.price());
    assertEquals(d("1420"), b.stop());
    Alert c =
        parse(
            "Initiating (CF)",
            opening.replace(
                "Take Profit (TP): $147.50, $153, and $160",
                "Our 3 take-profit targets are $147.5, $153, and $160."));
    assertEquals(3, c.targets().size());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            parse(
                "Reducing (CF)",
                "We are reducing from 5 to 4 at $100 while raising our stop to $90.\nWe are raising our stop to $95."));
  }

  @Test
  void invalidMoneyWeightsStopsAndEmptyInstructionsAreHeld() {
    for (String body :
        java.util.List.of(
            "",
            opening.replace("We are", "They are"),
            opening.replace("130.37", "0"),
            opening.replace("Weight Allocation: 5", "Weight Allocation: 0"),
            opening.replace("Weight Allocation: 5", "Weight Allocation: 101"),
            opening.replace("119.5", "0"),
            opening.replace("119.5", "130.37")))
      assertThrows(IllegalArgumentException.class, () -> parse("Initiating (CF)", body));
    for (String weights : java.util.List.of("0 to 1", "5 to 0", "5 to 5", "5 to 6"))
      assertThrows(
          IllegalArgumentException.class,
          () -> parse("Reducing (CF)", "We are reducing from " + weights + " at $100."));
    assertThrows(
        IllegalArgumentException.class,
        () -> parse("Increasing (CF)", "We are increasing from 5 to 5 at $100."));
    assertThrows(
        IllegalArgumentException.class,
        () -> parse("Increasing (CF)", "We are increasing from 5 to 4 at $100."));
    assertThrows(
        IllegalArgumentException.class,
        () -> parse("Increasing (CF)", "We are increasing from 3 to 5 without a price."));
    assertThrows(
        IllegalArgumentException.class,
        () -> parse("Moving stop (CF)", "We are reconsidering our position."));
    assertThrows(
        IllegalArgumentException.class,
        () -> parse("Reducing (CF)", "We are reducing from 5 to 4 and from 3 to 2 at $100."));
    assertThrows(
        IllegalArgumentException.class, () -> parse("Reducing (CF)", "We are reducing at $100."));
    assertEquals(
        d("100"),
        parse(
                "Initiating (CF)",
                opening
                    .replace("Weight Allocation: 5", "Weight Allocation: 100")
                    .replace("weight of 5", "weight of 100"))
            .after());
  }
}
