package com.loris.bravos.state;

import com.loris.bravos.domain.SourceBook;
import com.loris.bravos.domain.Model.*;
import java.time.*;
import java.util.*;

public final class TradingState {
    public int schemaVersion=1;
    public long generation;
    public LocalDate enrollmentFloor;
    public Instant checkpoint;
    public Instant revisionAudit;
    public SourceBook book=new SourceBook();
    public Map<String,Attempt> attempts=new LinkedHashMap<>();
    public Map<String,List<Intent>> batches=new LinkedHashMap<>();
    public List<Alert> earlyExits=new ArrayList<>();
    public List<String> report=new ArrayList<>();
    public enum Status { SUBMITTING, SUBMITTED, PARTIAL, CONFIRMED, REJECTED, UNKNOWN }
    public static final class Attempt {
        public Intent intent;
        public String reference;
        public Long orderId;
        public Status status;
        public Instant createdAt;
        public String result;
        public List<Long> positionIds=new ArrayList<>();
        public List<String> absorbedEvents=new ArrayList<>();
        public java.math.BigDecimal beforeAgentUnits;
        public Map<Long,java.math.BigDecimal> beforeOwnerUnits=new LinkedHashMap<>();
        public Attempt() {}
        public Attempt(Intent intent,Instant now) {
            this.intent=intent; reference=UUID.randomUUID().toString(); status=Status.SUBMITTING; createdAt=now;
        }
    }
}
