package com.loris.bravos.broker;

import com.loris.bravos.domain.Model.Intent;
import com.loris.bravos.state.TradingState.Attempt;
import com.loris.bravos.state.TradingState.Status;
import java.io.IOException;
import java.util.List;

public interface Broker {
    default void prepare(com.loris.bravos.state.TradingState.Attempt attempt) throws java.io.IOException {}
    record Receipt(Long orderId) {}
    record Observation(Status status,String reason,List<Long> positionIds) {}
    Receipt submit(Intent intent,String reference) throws IOException;
    Observation observe(Attempt attempt) throws IOException;
}
