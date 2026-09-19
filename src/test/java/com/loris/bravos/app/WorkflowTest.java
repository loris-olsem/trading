package com.loris.bravos.app;

import com.loris.bravos.broker.*;
import com.loris.bravos.domain.Model.*;
import com.loris.bravos.source.BravosSource;
import com.loris.bravos.state.*;
import com.loris.bravos.state.TradingState.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static com.loris.bravos.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowTest {
    @TempDir Path temp;
    final Clock clock=Clock.fixed(NOW,ZoneOffset.UTC);
    class Market implements Workflow.Market,Broker {
        List<Position> lots=new ArrayList<>(); List<Intent> submitted=new ArrayList<>();
        boolean pending,copyPending,unavailable;
        public Account account() { return new Account(NOW,d("4610"),d("4610"),d("10000"),d("10000"),true,pending,true,true,true,List.copyOf(lots),List.of()); }
        public Instrument instrument(String s,BigDecimal amount) { return unavailable?null:com.loris.bravos.Fixtures.instrument(); }
        public Quote quote(Instrument i) { return com.loris.bravos.Fixtures.quote("100"); }
        public Receipt submit(Intent i,String reference) {
            submitted.add(i);
            if(i.action()==Action.OPEN || i.action()==Action.ADD) lots.add(position(10+submitted.size(),"5",i.stop().toString(),true,false));
            else if(i.action()==Action.STOP) { Position p=lots.stream().filter(x->x.id()==i.positionId()).findFirst().orElseThrow(); lots.remove(p); lots.add(position(p.id(),p.units().toString(),i.stop().toString(),true,false)); }
            else { Position p=lots.stream().filter(x->x.id()==i.positionId()).findFirst().orElseThrow(); lots.remove(p); if(p.units().compareTo(i.units())>0) lots.add(position(p.id(),p.units().subtract(i.units()).toString(),p.stop().toString(),true,false)); }
            return new Receipt((long)submitted.size());
        }
        public Observation observe(Attempt a) {
            if(copyPending) return new Observation(Status.PARTIAL,"pending",List.of(11L));
            var i=a.intent;
            return new Observation(Status.CONFIRMED,"proved",i.positionId()==null?List.of(10+a.orderId):lots.stream().filter(p->p.id()==i.positionId()).map(Position::id).toList());
        }
    }
    BravosSource.Scan scan(List<Alert> alerts,boolean complete,BigDecimal weight) { return new BravosSource.Scan(List.of(),alerts,complete?List.of():List.of("READ_FAILED"),List.of(),complete,Map.of("CF",weight)); }
    @Test void planningDoesNotEnrollAndInitializationDoesNotTrade() throws Exception {
        var market=new Market();
        try(var store=new StateStore(temp)) {
            var w=new Workflow(store,market,market,clock);
            w.acceptScan(scan(List.of(cycle().events.getFirst()),true,d("5")),LocalDate.of(2026,9,1),false);
            assertNull(store.state().checkpoint); assertNull(store.state().enrollmentFloor);
            assertTrue(w.evaluate(false).stream().anyMatch(s->s.contains("READY"))); assertTrue(market.submitted.isEmpty());
            assertThrows(IOException.class,()->w.evaluate(true));
            w.acceptScan(scan(List.of(cycle().events.getFirst()),true,d("5")),LocalDate.of(2026,9,1),true);
            assertEquals(NOW,store.state().checkpoint); assertTrue(market.submitted.isEmpty());
            w.evaluate(true); assertEquals(1,market.submitted.size()); assertEquals(d("230.50"),market.submitted.getFirst().ownerAmount());
            w.evaluate(true); assertEquals(1,market.submitted.size()); // A funding-only/no-alert run cannot resize.
        }
    }
    @Test void incompleteScanAndDashboardMismatchNeverAdvanceCheckpoint() throws Exception {
        var market=new Market();
        try(var store=new StateStore(temp)) {
            var w=new Workflow(store,market,market,clock);
            assertThrows(IOException.class,()->w.acceptScan(scan(List.of(cycle().events.getFirst()),false,d("5")),LocalDate.of(2026,9,1),true));
            assertNull(store.state().checkpoint); assertNull(store.state().enrollmentFloor);
            assertThrows(IOException.class,()->w.acceptScan(scan(List.of(cycle().events.getFirst()),true,d("8")),LocalDate.of(2026,9,1),true));
            assertTrue(store.state().report.contains("SOURCE_DASHBOARD_MISMATCH"));
        }
    }
    @Test void lateOpeningAbsorbsHistoryThenNewTrimPrecedesStopAndAddition() throws Exception {
        var market=new Market();
        try(var store=new StateStore(temp)) {
            var w=new Workflow(store,market,market,clock);
            w.acceptScan(scan(List.of(cycle().events.getFirst(),alert("trim-old",Action.REDUCE,"100","5","4",null)),true,d("4")),LocalDate.of(2026,9,1),true);
            w.evaluate(true); assertEquals(d("184.40"),market.submitted.getFirst().ownerAmount());
            var c=store.state().book.cycles.get("opening"); assertTrue(c.completed.contains("trim-old"));
            var trim=alert("trim-new",Action.REDUCE,"101","4","3","95");
            c.events.add(trim); c.weight=d("3"); c.stop=d("95");
            w.evaluate(true);
            assertEquals(List.of(Action.OPEN,Action.REDUCE,Action.STOP),market.submitted.stream().map(Intent::action).toList());
            assertEquals(d("1.2500"),market.submitted.get(1).units()); assertTrue(c.completed.contains("trim-new"));
            c.events.add(alert("z-add",Action.ADD,"100","3","5",null)); c.weight=d("5");
            w.evaluate(true); assertEquals(Action.ADD,market.submitted.getLast().action()); assertEquals(d("92.20"),market.submitted.getLast().ownerAmount());
        }
    }
    @Test void pendingCopyKillAndUnexpectedHoldingsBlockNewExposure() throws Exception {
        var market=new Market();
        try(var store=new StateStore(temp)) {
            var w=new Workflow(store,market,market,clock); w.acceptScan(scan(List.of(cycle().events.getFirst()),true,d("5")),LocalDate.of(2026,9,1),true);
            market.pending=true; assertTrue(w.evaluate(true).contains("ACCOUNT_ACTIVITY_UNVERIFIED")); market.pending=false;
            market.lots.add(position(999,"1","90",true,false)); assertTrue(w.evaluate(true).contains("UNEXPLAINED_AGENT_POSITION")); market.lots.clear();
            Files.createFile(temp.resolve("KILL")); assertThrows(IOException.class,()->w.evaluate(true)); Files.delete(temp.resolve("KILL"));
            market.copyPending=true; w.evaluate(true); assertEquals(1,market.submitted.size());
            assertTrue(w.evaluate(true).contains("UNRESOLVED_ORDER_BLOCKS_NEW_SUBMISSIONS")); assertEquals(1,market.submitted.size());
        }
    }
    @Test void recordedEarlyExitIsTerminalAndCannotReenter() throws Exception {
        var market=new Market();
        try(var store=new StateStore(temp)) {
            var w=new Workflow(store,market,market,clock); w.acceptScan(scan(List.of(cycle().events.getFirst()),true,d("5")),LocalDate.of(2026,9,1),true); w.evaluate(true);
            store.state().earlyExits.add(new Alert("exit","user",LocalDate.now(clock),"user","opening",Action.EARLY_EXIT,null,d("1"),d("0"),null,List.of()));
            w.evaluate(true); assertTrue(store.state().book.cycles.get("opening").terminal); assertTrue(market.lots.isEmpty());
            w.evaluate(true); assertEquals(2,market.submitted.size());
        }
    }
}
