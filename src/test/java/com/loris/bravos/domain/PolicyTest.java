package com.loris.bravos.domain;
import com.loris.bravos.domain.Model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.time.*;
import java.util.*;
import static com.loris.bravos.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class PolicyTest {
    final Policy policy=new Policy();
    Decision opening(Cycle c,Quote q,Account a) { return policy.opening(c,instrument(),q,a,NOW,d("0")); }
    @Test void pricePassUsesRealEquityAndCurrentWeightExactlyOnce() {
        Cycle c=cycle(); c.weight=d("4"); c.stop=d("95");
        Intent i=opening(c,quote("99"),account()).intents().getFirst();
        assertEquals(d("184.40"),i.ownerAmount()); assertEquals(d("400.00"),i.agentAmount());
        assertEquals(d("102.00"),i.ceiling()); assertEquals(d("95"),i.stop());
    }
    @ParameterizedTest @CsvSource({"102,READY","102.01,WATCH_PRICE","90,BLOCKED","89,BLOCKED","90.01,READY"})
    void priceAndStopBoundaries(String ask,Outcome expected) { assertEquals(expected,opening(cycle(),quote(ask),account()).outcome()); }
    @Test void ceilingNeverRoundsUp() {
        Cycle c=cycle(); c.entry=d("100.009");
        assertEquals(Outcome.WATCH_PRICE,opening(c,quote("102.01"),account()).outcome());
    }
    @Test void stopAboveCeilingHasNoValidBand() { Cycle c=cycle(); c.stop=d("103"); assertEquals(Outcome.BLOCKED,opening(c,quote("104"),account()).outcome()); }
    @ParameterizedTest @ValueSource(ints={-1,61,500})
    void rejectsFutureAndStaleQuotes(int age) { assertEquals(Outcome.WAIT_QUOTE,opening(cycle(),new Quote(d("100"),NOW.minusSeconds(age),true,"USD"),account()).outcome()); }
    @Test void sixtySecondsIsAcceptedButClosedOrWrongCurrencyIsNot() {
        assertEquals(Outcome.READY,opening(cycle(),new Quote(d("100"),NOW.minusSeconds(60),true,"USD"),account()).outcome());
        assertEquals(Outcome.WAIT_QUOTE,opening(cycle(),new Quote(d("100"),NOW,false,"USD"),account()).outcome());
        assertEquals(Outcome.WAIT_QUOTE,opening(cycle(),new Quote(d("100"),NOW,true,"EUR"),account()).outcome());
        assertEquals(Outcome.WAIT_QUOTE,opening(cycle(),null,account()).outcome());
    }
    @Test void terminalUnenrolledAmbiguousAndExistingCyclesCannotOpen() {
        Cycle c=cycle(); c.terminal=true; assertEquals(Outcome.TERMINAL,opening(c,quote("100"),account()).outcome());
        c.terminal=false; c.sourceOpen=false; assertEquals(Outcome.TERMINAL,opening(c,quote("100"),account()).outcome());
        c.sourceOpen=true; c.enrolled=false; assertEquals(Outcome.BLOCKED,opening(c,quote("100"),account()).outcome());
        c.enrolled=true; c.blocker="revision"; assertEquals(Outcome.BLOCKED,opening(c,quote("100"),account()).outcome());
        c.blocker=null; c.entered=true; assertEquals(Outcome.BLOCKED,opening(c,quote("100"),account()).outcome());
        c.entered=false; c.positionIds.add(10L); assertEquals(Outcome.BLOCKED,opening(c,quote("100"),account()).outcome());
    }
    @Test void unknownCapabilitiesAndAccountFailuresBlock() {
        var bad=List.of(account(d("4610"),d("4610"),false,false,true,true,true,NOW),account(d("4610"),d("4610"),true,true,true,true,true,NOW),account(d("4610"),d("4610"),true,false,false,true,true,NOW),account(d("4610"),d("4610"),true,false,true,false,true,NOW),account(d("4610"),d("4610"),true,false,true,true,false,NOW),account(d("4610"),d("4610"),true,false,true,true,true,NOW.minusSeconds(61)),account(d("4610"),d("4610"),true,false,true,true,true,NOW.plusSeconds(1)),account(d("0"),d("4610"),true,false,true,true,true,NOW));
        for(Account a:bad) assertEquals(Outcome.BLOCKED,opening(cycle(),quote("100"),a).outcome());
    }
    @Test void reserveCostsAndMinimumCannotChangeWeight() {
        assertEquals(Outcome.BLOCKED,opening(cycle(),quote("100"),account(d("4610"),d("230.50"),true,false,true,true,true,NOW)).outcome());
        assertEquals(Outcome.BLOCKED,policy.opening(cycle(),instrument(),quote("100"),account(),NOW,d("4380")).outcome());
        Cycle c=cycle(); c.weight=d("0.001"); assertEquals("BELOW_MINIMUM",opening(c,quote("100"),account()).reason());
        c.weight=d("0"); assertEquals("INVALID_EQUITY_OR_WEIGHT",opening(c,quote("100"),account()).reason());
    }
    @Test void exactInstrumentAndLeverageAreRequired() {
        assertEquals(Outcome.BLOCKED,policy.opening(cycle(),null,quote("100"),account(),NOW,d("0")).outcome());
        for(Instrument i:List.of(new Instrument(1,"X","USD",false,true,"real",2,4,d("1"),d("0")),new Instrument(1,"X","USD",true,false,"real",2,4,d("1"),d("0")),new Instrument(1,"X","EUR",true,true,"real",2,4,d("1"),d("0")),new Instrument(1,"X","USD",true,true,"marginTrade",2,4,d("1"),d("0"))))
            assertEquals(Outcome.BLOCKED,policy.opening(cycle(),i,quote("100"),account(),NOW,d("0")).outcome());
    }
    @Test void heldAdditionUsesOnlyDeltaAndZeroTolerance() {
        Cycle c=cycle(); Alert add=alert("add",Action.ADD,"110","5","8",null);
        assertEquals(Outcome.NO_POSITION,policy.addition(c,add,instrument(),quote("100"),account(),NOW,d("0")).outcome());
        c.entered=true; c.positionIds.add(1L);
        Decision ready=policy.addition(c,add,instrument(),quote("110"),account(),NOW,d("0"));
        assertEquals(d("138.30"),ready.intents().getFirst().ownerAmount());
        assertEquals(Outcome.WATCH_PRICE,policy.addition(c,add,instrument(),quote("110.01"),account(),NOW,d("0")).outcome());
        c.additionSessions.put("add",LocalDate.of(2026,9,18));
        assertEquals(Outcome.TERMINAL,policy.addition(c,add,instrument(),quote("100"),account(),NOW,d("0")).outcome());
        c.additionSessions.clear(); c.completed.add("add");
        assertEquals(Outcome.BLOCKED,policy.addition(c,add,instrument(),quote("100"),account(),NOW,d("0")).outcome());
    }
    @Test void trimsUseLinkedUnitsAndFullCloseDoesNotRoundAwayDust() {
        Cycle c=cycle(); c.positionIds.add(1L);
        var positions=List.of(position(1,"7.123456","90",true,false),position(2,"100","90",true,false));
        Intent trim=policy.reduction(c,alert("trim",Action.REDUCE,"120","5","4",null),positions,4).getFirst();
        assertEquals(d("1.4246"),trim.units()); assertEquals(1L,trim.positionId());
        assertEquals(d("7.123456"),policy.reduction(c,alert("close",Action.CLOSE,"120",null,"0",null),positions,4).getFirst().units());
        assertThrows(IllegalArgumentException.class,()->policy.reduction(c,c.events.getFirst(),positions,4));
    }
    @Test void stopUpdateUsesExactPriceAndFixesTrailingAndDisabledProtection() {
        Cycle c=cycle(); c.positionIds.addAll(List.of(1L,2L,3L,4L));
        var list=List.of(position(1,"1","89",true,false),position(2,"1","90",false,false),position(3,"1","90",true,true),position(4,"1","90",true,false),position(5,"1",null,false,false));
        var updates=policy.protection(c,list); assertEquals(3,updates.size());
        assertTrue(updates.stream().allMatch(i->i.stop().equals(d("90"))));
        c.stop=null; assertTrue(policy.protection(c,list).isEmpty());
    }
}
