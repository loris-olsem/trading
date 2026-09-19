package com.loris.bravos.app;

import com.loris.bravos.broker.*;
import com.loris.bravos.domain.*;
import com.loris.bravos.domain.Model.*;
import com.loris.bravos.source.BravosSource;
import com.loris.bravos.state.*;
import com.loris.bravos.state.TradingState.*;
import java.io.IOException;
import java.math.*;
import java.time.*;
import java.util.*;

/** Coordinates discovery, reconciliation and policy; no trading arithmetic is hidden in the CLI. */
public final class Workflow {
    public interface Market {
        Account account() throws IOException;
        Instrument instrument(String symbol,BigDecimal amount) throws IOException;
        Quote quote(Instrument instrument) throws IOException;
    }
    private final StateStore store;
    private final Market market;
    private final Executor executor;
    private final Clock clock;
    private final Policy policy=new Policy();
    public Workflow(StateStore store,Market market,Broker broker,Clock clock) {
        this.store=store; this.market=market; this.executor=new Executor(store,broker,clock); this.clock=clock;
    }
    public static SourceBook initialBook(List<Alert> alerts,LocalDate enrollmentFloor) {
        SourceBook book=new SourceBook();
        // Start each symbol at the earliest opening actually observed. Pre-boundary updates
        // cannot create a position. Dashboard agreement is mandatory before enrollment.
        Map<String,LocalDate> first=new HashMap<>();
        for(Alert a:alerts) if(a.action()==Action.OPEN) first.merge(a.symbol(),a.date(),(a1,b)->a1.isBefore(b)?a1:b);
        book.apply(alerts.stream().filter(a->first.containsKey(a.symbol()) && !a.date().isBefore(first.get(a.symbol()))).toList(),enrollmentFloor);
        return book;
    }
    public static boolean dashboardMatches(SourceBook book,Map<String,BigDecimal> dashboard) {
        Map<String,BigDecimal> current=new HashMap<>();
        for(Cycle c:book.cycles.values()) if(c.sourceOpen) {
            if(c.blocker!=null || current.put(c.symbol,c.weight)!=null) return false;
        }
        return current.keySet().equals(dashboard.keySet()) && current.entrySet().stream().allMatch(e->e.getValue().compareTo(dashboard.get(e.getKey()))==0);
    }
    public void acceptScan(BravosSource.Scan scan,LocalDate enrollmentFloor,boolean initialize) throws IOException {
        TradingState state=store.state();
        state.report.clear(); state.report.addAll(scan.errors());
        if(state.checkpoint==null) state.book=initialBook(scan.alerts(),enrollmentFloor);
        else state.book.apply(scan.alerts(),state.enrollmentFloor);
        boolean matches=dashboardMatches(state.book,scan.dashboard());
        if(!matches) state.report.add("SOURCE_DASHBOARD_MISMATCH");
        state.report.addAll(state.book.blockers);
        if(!scan.complete() || !matches || !state.book.blockers.isEmpty()) {
            store.save(); throw new IOException("SOURCE_DISCOVERY_INCOMPLETE_SEE_STATUS");
        }
        if(initialize) {
            if(state.enrollmentFloor!=null) throw new IOException("ALREADY_INITIALIZED");
            state.enrollmentFloor=enrollmentFloor;
        }
        // Planning may remember source evidence but cannot establish live enrollment.
        if(state.enrollmentFloor!=null) state.checkpoint=clock.instant();
        state.revisionAudit=clock.instant();
        store.save();
    }
    public List<String> evaluate(boolean live) throws IOException {
        TradingState state=store.state(); state.report.clear();
        if(live && (state.enrollmentFloor==null || state.checkpoint==null)) throw new IOException("INITIALIZE_REQUIRED");
        if(live && store.killed()) throw new IOException("KILL_SWITCH");
        for(Attempt a:new ArrayList<>(state.attempts.values())) executor.reconcile(a);
        if(state.attempts.values().stream().anyMatch(a->!Set.of(Status.CONFIRMED,Status.REJECTED).contains(a.status))) {
            state.report.add("UNRESOLVED_ORDER_BLOCKS_NEW_SUBMISSIONS"); store.save(); return List.copyOf(state.report);
        }
        Account account=market.account();
        if(!account.active() || account.pending() || !account.ordersComplete()) {
            state.report.add("ACCOUNT_ACTIVITY_UNVERIFIED"); store.save(); return List.copyOf(state.report);
        }
        Set<Long> linked=new HashSet<>();
        for(Cycle c:state.book.cycles.values()) linked.addAll(c.positionIds);
        if(account.agentPositions().stream().anyMatch(p->!linked.contains(p.id()))) {
            state.report.add("UNEXPLAINED_AGENT_POSITION"); store.save(); return List.copyOf(state.report);
        }
        for(Cycle c:state.book.cycles.values()) if(c.entered && !c.terminal) {
            boolean allGone=c.positionIds.stream().noneMatch(id->account.agentPositions().stream().anyMatch(p->p.id()==id));
            if(allGone && !c.positionIds.isEmpty()) {
                boolean copiesGone=account.ownerPositions().stream().noneMatch(p->c.positionIds.contains(p.parentId()));
                if(copiesGone) { c.terminal=true; state.report.add(c.symbol+": POSITION_EXITED_NO_REENTRY"); }
                else { state.report.add(c.symbol+": COPY_EXIT_PENDING"); store.save(); return List.copyOf(state.report); }
            }
        }
        List<Cycle> cycles=state.book.cycles.values().stream().sorted(Comparator.comparing((Cycle c)->c.openedOn).thenComparing(c->c.key)).toList();
        // First risk reductions, then protection, then additional exposure.
        for(Cycle c:cycles) if(c.entered && !c.terminal) {
            List<Alert> reductions=new ArrayList<>(c.events.stream().filter(e->e.action()==Action.REDUCE || e.action()==Action.CLOSE).toList());
            reductions.addAll(state.earlyExits.stream().filter(e->e.symbol().equals(c.key)).toList());
            for(Alert e:reductions) if(!c.completed.contains(e.key())) {
                var lots=market.account().agentPositions();
                List<Intent> intents=state.batches.get(c.key+"|"+e.key()+"|"+e.action());
                if(intents==null) intents=policy.reduction(c,e,lots,unitScale(c.symbol));
                if(!batch(c,e.key(),intents,live)) return finish();
                if(live && c.completed.contains(e.key()) && (e.action()==Action.CLOSE || e.action()==Action.EARLY_EXIT && e.after().signum()==0)) { c.terminal=true; break; }
            }
        }
        for(Cycle c:cycles) if(c.entered && !c.terminal) {
            if(!batch(c,null,policy.protection(c,market.account().agentPositions()),live)) return finish();
        }
        for(Cycle c:cycles) if(c.sourceOpen && !c.terminal && c.enrolled) {
            Account fresh=market.account();
            List<Alert> actions=c.entered?c.events.stream().filter(e->e.action()==Action.ADD && !c.completed.contains(e.key())).toList():List.of(c.events.getFirst());
            for(Alert e:actions) {
                BigDecimal weight=c.entered?e.after().subtract(e.before()):c.weight;
                Instrument instrument=market.instrument(c.symbol,weight.multiply(fresh.ownerEquity()).divide(new BigDecimal("100")));
                Quote quote=instrument==null?null:market.quote(instrument);
                if(live && c.entered && quote!=null && quote.exchangeOpen()) c.additionSessions.putIfAbsent(e.key(),clock.instant().atZone(ZoneId.of("America/New_York")).toLocalDate());
                Decision decision=c.entered?policy.addition(c,e,instrument,quote,fresh,clock.instant(),BigDecimal.ZERO):policy.opening(c,instrument,quote,fresh,clock.instant(),BigDecimal.ZERO);
                state.report.add(c.symbol+": "+decision.outcome()+" "+decision.reason());
                if(!batch(c,null,decision.intents(),live)) return finish();
                fresh=market.account();
            }
        }
        return finish();
    }
    private int unitScale(String symbol) throws IOException {
        Instrument i=market.instrument(symbol,BigDecimal.ONE);
        if(i==null) throw new IOException("REDUCTION_PRECISION_UNVERIFIED");
        return i.unitScale();
    }
    private boolean batch(Cycle c,String event,List<Intent> intents,boolean live) throws IOException {
        if(intents.isEmpty()) return true;
        for(Intent i:intents) store.state().report.add(c.symbol+": "+i.action()+" "+(i.agentAmount()!=null?"owner USD "+i.ownerAmount()+", internal USD "+i.agentAmount()+", ceiling "+i.ceiling()+", stop "+i.stop():"units "+i.units()+", stop "+i.stop()));
        if(!live) return true;
        // Save every member before submission so restarts retain original proportional units.
        Intent first=intents.getFirst();
        String key=c.key+"|"+first.eventKey()+"|"+first.action();
        List<Intent> durable=store.state().batches.computeIfAbsent(key,unused->new ArrayList<>(intents));
        store.save();
        for(Intent i:durable) {
            Attempt attempt=executor.execute(i);
            if(attempt.status!=Status.CONFIRMED) { store.state().report.add("ORDER_PENDING_OR_REJECTED: "+attempt.result); return false; }
        }
        if(event!=null) c.completed.add(event);
        store.save(); return true;
    }
    private List<String> finish() throws IOException { store.save(); return List.copyOf(store.state().report); }
}
