package com.loris.bravos.source;
import com.loris.bravos.domain.Model.*;
import org.junit.jupiter.api.*;
import java.time.LocalDate;
import static com.loris.bravos.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class AlertParserTest {
    final AlertParser parser=new AlertParser();
    Alert parse(String title,String body) { return parser.parse("post:1","https://bravosresearch.com/x",LocalDate.of(2026,9,19),title,body); }
    String opening="We are initiating a position in CF ($CF) at $130.37 with a weight of 5.\n\nEntry: $130.37\nTake Profit (TP): $147.50, $153, and $160\nSuggested Stop Loss (SL): $119.5\nWeight Allocation: 5";
    @Test void openingReadsAllFields() {
        Alert a=parse("Initiating Long on CF (CF) - Breakout",opening);
        assertEquals(Action.OPEN,a.action()); assertEquals("CF",a.symbol()); assertEquals(d("130.37"),a.price());
        assertEquals(d("5"),a.after()); assertEquals(d("119.5"),a.stop()); assertEquals(3,a.targets().size()); assertEquals(64,a.hash().length());
    }
    @Test void bundledTrimAndStopSurvive() {
        Alert a=parse("Booking Partial Profits in CF (CF)","We are booking partial profits at $141.12, reducing our position from a weight of 5 to a weight of 4, while also raising our stop from $119.50 to $121.50.");
        assertEquals(Action.REDUCE,a.action()); assertEquals(d("5"),a.before()); assertEquals(d("4"),a.after()); assertEquals(d("121.50"),a.stop());
    }
    @Test void decimalCommaAndAdditionWeightVariants() {
        Alert a=parse("Increasing Exposure to Teva (TEVA)","We are increasing our allocation at $37,67, and increasing from a weight of 3 to a weight of 5, while also raising our stop from $32 to $33.");
        assertEquals(d("37.67"),a.price()); assertEquals(d("33"),a.stop());
        Alert b=parse("Increasing Exposure (IBB)","We are increasing our position at $192.3, adding a weight of 3 to bring our total allocation to a weight of 7.");
        assertEquals(d("4"),b.before()); assertEquals(d("7"),b.after());
    }
    @Test void ignoresHistoricalPricesInLaterParagraphs() {
        Alert a=parse("Increasing Exposure (EWJ)","We are increasing our allocation at $94.65, and increasing the weight allocation from 5 to 8.\n\nThis position was entered on May 08, 2026, at $92.");
        assertEquals(d("94.65"),a.price());
    }
    @Test void closesAndStandaloneStops() {
        Alert a=parse("Closing Position in CF (CF)","We are closing our position in CF at $99."); assertEquals(Action.CLOSE,a.action()); assertEquals(d("0"),a.after());
        Alert b=parse("Raising Stop on CF (CF)","We are raising our stop from $90 to $95."); assertEquals(Action.STOP,b.action()); assertEquals(d("95"),b.stop());
    }
    @Test void malformedAndUnknownInstructionsFailClosed() {
        assertThrows(IllegalArgumentException.class,()->parse("Discussion (CF)",opening));
        assertThrows(IllegalArgumentException.class,()->parse("Initiating CF",opening));
        assertThrows(IllegalArgumentException.class,()->parse("Initiating (CF) (XLF)",opening));
        assertThrows(IllegalArgumentException.class,()->parse("Initiating (CF)",opening.replace("Entry: $130.37","Entry: $130.38")));
        assertThrows(IllegalArgumentException.class,()->parse("Initiating (CF)",opening+"\nWeight Allocation: 8"));
        assertThrows(IllegalArgumentException.class,()->parse("Initiating (CF)",opening.replace("Suggested Stop Loss (SL): $119.5","")));
        assertThrows(IllegalArgumentException.class,()->parse("Increasing (CF)","We are increasing our position at $100."));
        assertThrows(IllegalArgumentException.class,()->parse("Reducing (CF)","We are reducing from 5 to 8 at $100."));
        assertThrows(IllegalArgumentException.class,()->parse("Reducing (CF)","We are reducing from 5 to 4 at $100 and raising our stop to an unspecified price."));
    }
    @Test void whitespaceHashIgnoresPageFormatting() { assertEquals(AlertParser.hash("a b c"),AlertParser.hash(" a\n b\u00a0c ")); }
}
