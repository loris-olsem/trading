package com.loris.bravos.domain;

import com.loris.bravos.domain.Model.*;
import java.math.*;
import java.time.*;
import java.util.*;

/** Financial decisions only: no HTTP, persistence, wall clock, or credential access. */
public final class Policy {
    private static final BigDecimal HUNDRED=new BigDecimal("100");
    private static final BigDecimal TOLERANCE=new BigDecimal("1.02");
    public Decision opening(Cycle c, Instrument i, Quote q, Account a, Instant now, BigDecimal reserved) {
        if(c.terminal || !c.sourceOpen) return Decision.of(Outcome.TERMINAL,"CYCLE_ENDED");
        if(!c.enrolled || c.blocker!=null) return Decision.of(Outcome.BLOCKED,"NOT_ENROLLED_OR_SOURCE_BLOCKED");
        if(c.entered || !c.positionIds.isEmpty()) return Decision.of(Outcome.BLOCKED,"RECONCILE_EXISTING_PARTICIPATION");
        return buy(c,c.events.getFirst(),Action.OPEN,c.weight,c.entry.multiply(TOLERANCE),i,q,a,now,reserved);
    }
    public Decision addition(Cycle c, Alert event, Instrument i, Quote q, Account a, Instant now, BigDecimal reserved) {
        if(!c.entered || c.positionIds.isEmpty()) return Decision.of(Outcome.NO_POSITION,"ASSESS_ORIGINAL_OPENING_ONLY");
        if(c.terminal || !c.sourceOpen) return Decision.of(Outcome.TERMINAL,"CYCLE_ENDED");
        if(c.blocker!=null || event.action()!=Action.ADD || c.completed.contains(event.key())) return Decision.of(Outcome.BLOCKED,"SOURCE_OR_COMPLETION_CONFLICT");
        LocalDate session=now.atZone(ZoneId.of("America/New_York")).toLocalDate();
        LocalDate first=c.additionSessions.get(event.key());
        if(first!=null && !first.equals(session)) return Decision.of(Outcome.TERMINAL,"ADDITION_SESSION_EXPIRED");
        return buy(c,event,Action.ADD,event.after().subtract(event.before()),event.price(),i,q,a,now,reserved);
    }
    private Decision buy(Cycle c, Alert e, Action action, BigDecimal weight, BigDecimal ceiling,
                         Instrument i, Quote q, Account a, Instant now, BigDecimal reserved) {
        if(i==null || !c.symbol.equals(i.symbol()) || !i.eligible() || !i.unleveraged() || !"USD".equals(i.currency()) || !Set.of("real","cfd").contains(i.settlementType())) return Decision.of(Outcome.BLOCKED,"INSTRUMENT_UNVERIFIED");
        if(q==null || !q.exchangeOpen() || !"USD".equals(q.currency()) || q.timestamp().isAfter(now) || Duration.between(q.timestamp(),now).compareTo(Duration.ofSeconds(60))>0) return Decision.of(Outcome.WAIT_QUOTE,"QUOTE_NOT_EXECUTABLE");
        if(c.stop==null || c.stop.signum()<=0 || q.ask().compareTo(c.stop)<=0 || c.stop.compareTo(ceiling)>=0) return Decision.of(Outcome.BLOCKED,"INVALID_OR_CROSSED_STOP");
        BigDecimal limit=ceiling.setScale(i.priceScale(),RoundingMode.DOWN);
        if(q.ask().compareTo(limit)>0) return Decision.of(Outcome.WATCH_PRICE,"ABOVE_ORIGINAL_CEILING");
        if(!a.ordersComplete() || a.pending() || !a.active() || !a.copySizingVerified() || !a.copyStopsVerified() || a.observedAt().isAfter(now) || Duration.between(a.observedAt(),now).getSeconds()>60) return Decision.of(Outcome.BLOCKED,"ACCOUNT_OR_COPY_UNVERIFIED");
        if(a.ownerEquity().signum()<=0 || a.agentEquity().signum()<=0 || weight.signum()<=0 || weight.compareTo(HUNDRED)>0) return Decision.of(Outcome.BLOCKED,"INVALID_EQUITY_OR_WEIGHT");
        BigDecimal owner=weight.multiply(a.ownerEquity()).divide(HUNDRED).setScale(2,RoundingMode.DOWN);
        BigDecimal agent=owner.multiply(a.agentEquity()).divide(a.ownerEquity(),2,RoundingMode.DOWN);
        if(agent.compareTo(i.minimumAgentAmount())<0 || agent.signum()<=0) return Decision.of(Outcome.BLOCKED,"BELOW_MINIMUM");
        BigDecimal agentCosts=i.estimatedOwnerCost().multiply(a.agentEquity()).divide(a.ownerEquity(),2,RoundingMode.UP);
        if(owner.add(i.estimatedOwnerCost()).add(reserved).compareTo(a.ownerCash())>0 || agent.add(agentCosts).compareTo(a.agentCash())>0) return Decision.of(Outcome.BLOCKED,"INSUFFICIENT_CASH");
        return new Decision(Outcome.READY,"POLICY_PASSED",List.of(new Intent(c.key+"|"+e.key()+"|"+action,c.key,e.key(),action,i.id(),null,owner,agent,null,limit,c.stop,i.settlementType())));
    }
    public List<Intent> reduction(Cycle c, Alert event, List<Position> positions, int unitScale) {
        if(c.blocker!=null || c.completed.contains(event.key())) return List.of();
        boolean full=event.action()==Action.CLOSE || event.action()==Action.EARLY_EXIT && event.after().signum()==0;
        if(!full && event.action()!=Action.REDUCE && event.action()!=Action.EARLY_EXIT) throw new IllegalArgumentException("NOT_A_REDUCTION");
        BigDecimal fraction=full ? BigDecimal.ONE : event.before().subtract(event.after()).divide(event.before(),MathContext.DECIMAL128);
        if(fraction.signum()<=0 || fraction.compareTo(BigDecimal.ONE)>0) throw new IllegalArgumentException("INVALID_REDUCTION");
        List<Intent> result=new ArrayList<>();
        for(Position p:positions) {
            if(!c.positionIds.contains(p.id())) continue;
            BigDecimal units=full ? p.units() : p.units().multiply(fraction).setScale(unitScale,RoundingMode.DOWN);
            if(units.signum()<=0) continue;
            result.add(new Intent(c.key+"|"+event.key()+"|"+event.action()+"|"+p.id(),c.key,event.key(),event.action(),p.instrumentId(),p.id(),null,null,units,null,null,null));
        }
        return result;
    }
    public List<Intent> protection(Cycle c, List<Position> positions) {
        if(c.stop==null || c.stop.signum()<=0 || c.blocker!=null) return List.of();
        return positions.stream().filter(p->c.positionIds.contains(p.id()))
                .filter(p->!p.stopEnabled() || p.trailing() || p.stop()==null || p.stop().compareTo(c.stop)!=0)
                .map(p->new Intent(c.key+"|"+c.events.getLast().key()+"|stop:"+c.stop.toPlainString()+"|"+p.id(),c.key,c.events.getLast().key(),Action.STOP,p.instrumentId(),p.id(),null,null,null,null,c.stop,null)).toList();
    }
}
