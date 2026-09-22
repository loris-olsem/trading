package com.loris.bravos.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

public final class Model {
  private Model() {}

  public enum Action {
    OPEN,
    ADD,
    REDUCE,
    CLOSE,
    STOP,
    EARLY_EXIT
  }

  public enum Outcome {
    READY,
    WATCH_PRICE,
    WAIT_QUOTE,
    BLOCKED,
    NO_POSITION,
    TERMINAL
  }

  public record Alert(
      String key,
      String url,
      LocalDate date,
      String hash,
      String symbol,
      Action action,
      BigDecimal price,
      BigDecimal before,
      BigDecimal after,
      BigDecimal stop,
      List<BigDecimal> targets) {}

  public record Quote(BigDecimal ask, Instant timestamp, boolean exchangeOpen, String currency) {}

  public record Instrument(
      long id,
      String symbol,
      String currency,
      boolean unleveraged,
      boolean eligible,
      String settlementType,
      int priceScale,
      int unitScale,
      BigDecimal minimumAgentAmount,
      BigDecimal estimatedOwnerCost) {}

  public record Position(
      long id,
      long parentId,
      long instrumentId,
      BigDecimal units,
      BigDecimal amount,
      BigDecimal pnl,
      BigDecimal stop,
      boolean stopEnabled,
      boolean trailing,
      boolean longOnly,
      BigDecimal openRate) {
    public Position(
        long id,
        long parentId,
        long instrumentId,
        BigDecimal units,
        BigDecimal amount,
        BigDecimal pnl,
        BigDecimal stop,
        boolean stopEnabled,
        boolean trailing,
        boolean longOnly) {
      this(
          id,
          parentId,
          instrumentId,
          units,
          amount,
          pnl,
          stop,
          stopEnabled,
          trailing,
          longOnly,
          null);
    }
  }

  public record Account(
      Instant observedAt,
      BigDecimal ownerEquity,
      BigDecimal ownerCash,
      BigDecimal agentEquity,
      BigDecimal agentCash,
      boolean ordersComplete,
      boolean pending,
      boolean active,
      boolean copyEntryPermitted,
      boolean copyStopModelConfigured,
      List<Position> agentPositions,
      List<Position> ownerPositions) {}

  public static final class Cycle {
    public String key;
    public String symbol;
    public LocalDate openedOn;
    public BigDecimal entry;
    public BigDecimal weight;
    public BigDecimal stop;
    public boolean sourceOpen = true;
    public boolean enrolled;
    public boolean entered;
    public boolean terminal;
    public String blocker;
    public List<Alert> events = new ArrayList<>();
    public List<Long> positionIds = new ArrayList<>();
    public Set<String> completed = new HashSet<>();
    public Map<String, LocalDate> additionSessions = new HashMap<>();

    public Cycle() {}

    public Cycle(Alert a, boolean enrolled) {
      key = a.key();
      symbol = a.symbol();
      openedOn = a.date();
      entry = a.price();
      weight = a.after();
      stop = a.stop();
      this.enrolled = enrolled;
      events.add(a);
    }
  }

  public record Intent(
      String key,
      String cycleKey,
      String eventKey,
      Action action,
      long instrumentId,
      Long positionId,
      BigDecimal ownerAmount,
      BigDecimal agentAmount,
      BigDecimal units,
      BigDecimal ceiling,
      BigDecimal stop,
      String settlementType,
      String orderType) {
    public Intent {
      // Missing in historical journals: those orders were always IOC limits.
      if (orderType == null) orderType = "limitIOC";
      if (!Set.of("limitIOC", "mkt").contains(orderType))
        throw new IllegalArgumentException("INVALID_ORDER_TYPE");
    }

    public Intent(
        String key,
        String cycleKey,
        String eventKey,
        Action action,
        long instrumentId,
        Long positionId,
        BigDecimal ownerAmount,
        BigDecimal agentAmount,
        BigDecimal units,
        BigDecimal ceiling,
        BigDecimal stop,
        String settlementType) {
      this(
          key,
          cycleKey,
          eventKey,
          action,
          instrumentId,
          positionId,
          ownerAmount,
          agentAmount,
          units,
          ceiling,
          stop,
          settlementType,
          "limitIOC");
    }
  }

  public record Decision(Outcome outcome, String reason, List<Intent> intents) {
    public static Decision of(Outcome o, String reason) {
      return new Decision(o, reason, List.of());
    }
  }
}
