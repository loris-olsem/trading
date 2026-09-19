package com.loris.bravos.domain;
import com.loris.bravos.domain.Model.*;
import org.junit.jupiter.api.*;
import java.time.LocalDate;
import java.util.*;
import static com.loris.bravos.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceBookTest {
    final LocalDate floor=LocalDate.of(2026,9,1);
    @Test void deduplicationAndBundledUpdates() {
        SourceBook b=new SourceBook(); Alert open=cycle().events.getFirst();
        b.apply(List.of(open,open),floor); assertEquals(1,b.cycles.size()); assertEquals(1,b.revisions.size());
        b.apply(List.of(alert("zadd",Action.ADD,"110","5","8","95")),floor);
        assertEquals(d("8"),b.cycles.get("opening").weight); assertEquals(d("95"),b.cycles.get("opening").stop);
        assertEquals(d("100"),b.cycles.get("opening").entry);
    }
    @Test void closedThenReopenedTickerHasDistinctCycles() {
        SourceBook b=new SourceBook(); b.apply(List.of(cycle().events.getFirst()),floor);
        b.apply(List.of(alert("close",Action.CLOSE,"99",null,"0",null)),floor);
        b.apply(List.of(alert("reopen",Action.OPEN,"110",null,"3","100")),floor);
        assertEquals(2,b.cycles.size()); assertFalse(b.cycles.get("opening").sourceOpen); assertTrue(b.cycles.get("reopen").sourceOpen);
    }
    @Test void materialRevisionsAreRetainedAndBlocked() {
        SourceBook b=new SourceBook(); Alert o=cycle().events.getFirst(); b.apply(List.of(o),floor);
        Alert changed=new Alert(o.key(),o.url(),o.date(),"changed",o.symbol(),o.action(),d("101"),null,o.after(),o.stop(),o.targets());
        b.apply(List.of(changed),floor); assertEquals(2,b.revisions.get(o.key()).size());
        assertNotNull(b.cycles.get(o.key()).blocker); assertEquals(d("100"),b.cycles.get(o.key()).entry);
    }
    @Test void cosmeticRevisionRetainsOneInstruction() {
        SourceBook b=new SourceBook(); Alert o=cycle().events.getFirst(); b.apply(List.of(o),floor);
        b.apply(List.of(new Alert(o.key(),o.url(),o.date(),"different",o.symbol(),o.action(),o.price(),o.before(),o.after(),o.stop(),o.targets())),floor);
        assertNull(b.cycles.get(o.key()).blocker); assertEquals(1,b.cycles.get(o.key()).events.size());
    }
    @Test void orphanAndWeightMismatchCannotBecomeTrades() {
        SourceBook b=new SourceBook(); b.apply(List.of(alert("orphan",Action.ADD,"110","5","8",null)),floor); assertFalse(b.blockers.isEmpty());
        b.apply(List.of(cycle().events.getFirst()),floor); b.apply(List.of(alert("wrong",Action.REDUCE,"100","8","3",null)),floor);
        assertEquals("WEIGHT_CHAIN_MISMATCH",b.cycles.get("opening").blocker);
    }
    @Test void researchDoesNotEnrollAndDuplicateOpenBlocks() {
        SourceBook b=new SourceBook(); b.apply(List.of(cycle().events.getFirst()),null); assertFalse(b.cycles.get("opening").enrolled);
        b.apply(List.of(alert("other",Action.OPEN,"100",null,"5","90")),floor); assertEquals("OVERLAPPING_OPENING",b.cycles.get("opening").blocker);
    }
}
