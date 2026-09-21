package com.loris.bravos.domain;

import com.loris.bravos.domain.Model.*;
import java.time.LocalDate;
import java.util.*;

/** Owns source chronology; broker participation is never inferred from source changes. */
public final class SourceBook {
  public Map<String, Cycle> cycles = new LinkedHashMap<>();
  public Map<String, List<Alert>> revisions = new LinkedHashMap<>();
  public List<String> blockers = new ArrayList<>();

  public void apply(Collection<Alert> observations, LocalDate enrollmentFloor) {
    // Older versions saved orphan observations from incomplete scans. Replay only
    // unattached orphans; never erase revisions of an accepted instruction.
    List<Alert> pending = new ArrayList<>(observations);
    for (String blocker : new ArrayList<>(blockers)) {
      if (!blocker.startsWith("ORPHAN_UPDATE:")) continue;
      String key = blocker.substring("ORPHAN_UPDATE:".length());
      List<Alert> history = revisions.get(key);
      boolean attached =
          cycles.values().stream()
              .anyMatch(c -> c.events.stream().anyMatch(e -> e.key().equals(key)));
      if (history == null || attached) continue;
      if (history.stream().anyMatch(a -> !sameFacts(history.getFirst(), a))) continue;
      pending.addAll(history);
      revisions.remove(key);
      blockers.remove(blocker);
    }
    List<Alert> sorted =
        pending.stream()
            .sorted(
                Comparator.comparing(Alert::date)
                    .thenComparing(Alert::key, SourceBook::compareKeys))
            .toList();
    for (Alert a : sorted) {
      List<Alert> old = revisions.get(a.key());
      if (old != null) {
        Alert prior = old.getLast();
        if (prior.hash().equals(a.hash())) continue;
        old.add(a);
        if (!sameFacts(prior, a)) {
          blockers.add("REVISED_INSTRUCTION:" + a.key());
          cycles.values().stream()
              .filter(c -> c.events.stream().anyMatch(e -> e.key().equals(a.key())))
              .forEach(c -> c.blocker = "REVISED_INSTRUCTION:" + a.key());
        }
        continue;
      }
      revisions.put(a.key(), new ArrayList<>(List.of(a)));
      Optional<Cycle> current =
          cycles.values().stream()
              .filter(c -> c.symbol.equals(a.symbol()) && c.sourceOpen)
              .findFirst();
      if (a.action() == Action.OPEN) {
        if (current.isPresent()) {
          current.get().blocker = "OVERLAPPING_OPENING";
          blockers.add("OVERLAPPING_OPENING:" + a.key());
          continue;
        }
        cycles.put(
            a.key(), new Cycle(a, enrollmentFloor != null && !a.date().isBefore(enrollmentFloor)));
        continue;
      }
      if (current.isEmpty()) {
        blockers.add("ORPHAN_UPDATE:" + a.key());
        continue;
      }
      Cycle c = current.get();
      if (a.date().isBefore(c.events.getLast().date())) {
        c.blocker = "BROKEN_CHRONOLOGY";
        blockers.add("BACKDATED_UPDATE:" + a.key());
        continue;
      }
      if (a.before() != null && a.before().compareTo(c.weight) != 0)
        c.blocker = "WEIGHT_CHAIN_MISMATCH";
      c.events.add(a);
      if (a.after() != null) c.weight = a.after();
      if (a.stop() != null) c.stop = a.stop();
      if (a.action() == Action.CLOSE) c.sourceOpen = false;
    }
  }

  /** WordPress IDs are numbers, not lexicographic strings (999 precedes 1000). */
  public static int compareKeys(String left, String right) {
    if (left.matches("bravos:post:[0-9]+") && right.matches("bravos:post:[0-9]+"))
      return new java.math.BigInteger(left.substring(12))
          .compareTo(new java.math.BigInteger(right.substring(12)));
    return left.compareTo(right);
  }

  private boolean sameFacts(Alert a, Alert b) {
    return a.symbol().equals(b.symbol())
        && a.date().equals(b.date())
        && a.action() == b.action()
        && sameNumber(a.price(), b.price())
        && sameNumber(a.before(), b.before())
        && sameNumber(a.after(), b.after())
        && sameNumber(a.stop(), b.stop())
        && a.targets().size() == b.targets().size()
        && java.util.stream.IntStream.range(0, a.targets().size())
            .allMatch(i -> sameNumber(a.targets().get(i), b.targets().get(i)));
  }

  private static boolean sameNumber(java.math.BigDecimal a, java.math.BigDecimal b) {
    return a == null ? b == null : b != null && a.compareTo(b) == 0;
  }
}
