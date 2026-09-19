package com.loris.bravos.broker;

import com.loris.bravos.domain.Model.*;
import com.loris.bravos.state.*;
import com.loris.bravos.state.TradingState.*;
import java.io.IOException;
import java.time.Clock;

/** The write-ahead boundary: a failed response cannot become a second submission. */
public final class Executor {
    private final StateStore store;
    private final Broker broker;
    private final Clock clock;
    public Executor(StateStore store,Broker broker,Clock clock) { this.store=store; this.broker=broker; this.clock=clock; }
    public Attempt execute(Intent intent) throws IOException {
        Attempt prior=store.state().attempts.get(intent.key());
        if(prior!=null) { reconcile(prior); return prior; }
        if(store.killed()) throw new IOException("KILL_SWITCH");
        Attempt attempt=new Attempt(intent,clock.instant());
        if(intent.action()==Action.OPEN) attempt.absorbedEvents.addAll(store.state().book.cycles.get(intent.cycleKey()).events.stream().map(Alert::key).toList());
        broker.prepare(attempt);
        store.state().attempts.put(intent.key(),attempt);
        store.save();
        if(store.killed()) { attempt.status=Status.REJECTED; attempt.result="KILL_BEFORE_SUBMISSION"; store.save(); return attempt; }
        Broker.Receipt receipt;
        try { receipt=broker.submit(intent,attempt.reference); }
        catch(IOException e) { attempt.status=Status.UNKNOWN; attempt.result="SUBMISSION_OUTCOME_UNKNOWN"; store.save(); return attempt; }
        attempt.orderId=receipt.orderId(); attempt.status=Status.SUBMITTED;
        store.save();
        reconcile(attempt);
        return attempt;
    }
    public void reconcile(Attempt attempt) throws IOException {
        if(attempt.status==Status.CONFIRMED || attempt.status==Status.REJECTED) return;
        Broker.Observation observed;
        try { observed=broker.observe(attempt); }
        catch(IOException e) { attempt.result="RECONCILIATION_UNAVAILABLE"; store.save(); return; }
        attempt.status=observed.status(); attempt.result=observed.reason(); attempt.positionIds=observed.positionIds();
        Cycle cycle=store.state().book.cycles.get(attempt.intent.cycleKey());
        Action action=attempt.intent.action();
        if((action==Action.OPEN || action==Action.ADD) && (attempt.status==Status.CONFIRMED || attempt.status==Status.PARTIAL)) {
            if(attempt.positionIds.isEmpty()) { attempt.status=Status.UNKNOWN; attempt.result="MISSING_FILLED_POSITION_IDS"; }
            else { cycle.entered=true; for(Long id:attempt.positionIds) if(!cycle.positionIds.contains(id)) cycle.positionIds.add(id); }
        }
        if(attempt.status==Status.CONFIRMED) {
            if(action==Action.OPEN) cycle.completed.addAll(attempt.absorbedEvents);
            if(action==Action.ADD) cycle.completed.add(attempt.intent.eventKey());
            if(action==Action.CLOSE || action==Action.EARLY_EXIT) {
                // Full termination is established by the workflow's holdings read-back,
                // never merely by one lot's close acknowledgement.
                cycle.positionIds.removeIf(id->id.equals(attempt.intent.positionId()) && observed.positionIds().isEmpty());
            }
        }
        store.save();
    }
}
