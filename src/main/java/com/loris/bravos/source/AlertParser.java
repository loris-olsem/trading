package com.loris.bravos.source;

import com.loris.bravos.domain.Model.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

/** Deliberately recognizes trade instructions, never the surrounding investment thesis. */
public final class AlertParser {
  private static final String N = "([0-9]+(?:(?:,[0-9]{3})+(?:\\.[0-9]+)?|[.,][0-9]+)?)";

  public Alert parse(String key, String url, LocalDate date, String title, String authored) {
    String body =
        authored
            .replace('\u00a0', ' ')
            .replace("We’re", "We are")
            .replace("We're", "We are")
            .trim();
    String text = body.replaceAll("\\s+", " ");
    String symbol = one("\\(\\$?([A-Z][A-Z0-9.]{0,12})\\)", title);
    String lower = title.toLowerCase(Locale.ROOT);
    Action action;
    if (lower.contains("initiating") || lower.contains("entering")) action = Action.OPEN;
    else if (lower.contains("increasing")) action = Action.ADD;
    else if (lower.contains("partial profits")
        || lower.contains("booking profits")
        || lower.contains("reducing")
        || lower.contains("trimming")) action = Action.REDUCE;
    else if (lower.contains("closing") || lower.contains("exiting")) action = Action.CLOSE;
    else if (lower.contains("stop")) action = Action.STOP;
    else throw new IllegalArgumentException("UNSUPPORTED_ALERT_ACTION");
    // Only the first authored instruction paragraph supplies execution price/weight.
    String first =
        Arrays.stream(body.split("\\n"))
            .filter(p -> p.stripLeading().startsWith("We "))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("MISSING_INSTRUCTION_PARAGRAPH"));
    String actionWords =
        switch (action) {
          case OPEN -> "initiating|entering";
          case ADD -> "increasing|adding";
          case REDUCE -> "booking|reducing|trimming";
          case CLOSE -> "closing|exiting";
          case STOP -> "raising|moving|adjusting|lowering";
          default -> "never";
        };
    if (!Pattern.compile("(?i)\\b(?:" + actionWords + ")\\b").matcher(first).find())
      fail("TITLE_INSTRUCTION_CONFLICT");
    if (Pattern.compile(
            "(?i)\\b(?:sell|close|take|trim)\\w*\\s+(?:[0-9]+(?:[.,][0-9]+)?\\s*%|half|one.third).{0,80}\\bat\\s*\\$")
        .matcher(text)
        .find()) fail("QUANTIFIED_TARGET_REQUIRES_REVIEW");
    BigDecimal price = optional("(?i)^\\s*We .*?\\bat (?:a price of )?\\$" + N, first);
    BigDecimal before = null, after = null, stop = null;
    List<BigDecimal> targets = new ArrayList<>();
    if (action == Action.OPEN) {
      BigDecimal labelled = optional("(?i)(?:^|\\n)Entry(?: Price)?\\s*:\\s*\\$" + N, body);
      if (price != null && labelled != null && price.compareTo(labelled) != 0)
        fail("CONFLICTING_ENTRY");
      if (labelled != null) price = labelled;
      after = optional("(?i)Weight Allocation\\s*:\\s*" + N, body);
      stop = optional("(?i)Suggested Stop Loss \\(SL\\)\\s*:\\s*\\$" + N, body);
      BigDecimal inlineWeight = optional("(?i)\\bweight(?: allocation)? of\\s+" + N, first);
      BigDecimal inlineStop = optional("(?i)\\bstop(?: loss)?(?: set)? at\\s*\\$" + N, first);
      if (after == null) after = inlineWeight;
      if (stop == null) stop = inlineStop;
      if (after == null || stop == null) fail("MISSING_OPENING_WEIGHT_OR_STOP");
      if (inlineWeight != null && inlineWeight.compareTo(after) != 0) fail("CONFLICTING_WEIGHT");
      if (inlineStop != null && inlineStop.compareTo(stop) != 0) fail("CONFLICTING_STOP");
      Matcher target =
          Pattern.compile("(?im)^(?:Take Profit|Target Price) \\(TP\\):([^\\n]+)").matcher(body);
      if (target.find()) {
        Matcher values = Pattern.compile("\\$" + N).matcher(target.group(1));
        while (values.find()) targets.add(number(values.group(1)));
        if (target.find()) fail("DUPLICATE_TARGET_LABEL");
      }
      if (targets.isEmpty()) {
        Matcher prose = Pattern.compile("(?i)price targets (?:of|at) (.+)$").matcher(first);
        if (prose.find()) {
          Matcher values = Pattern.compile("\\$" + N).matcher(prose.group(1));
          while (values.find()) targets.add(number(values.group(1)));
        }
      }
      if (targets.isEmpty()) {
        Matcher tp =
            Pattern.compile(
                    "(?i)Our 3 take[- ]profit (?:levels|targets) are ([^.]+(?:\\.[0-9]+[^.]+)*)[.]")
                .matcher(text);
        if (tp.find()) {
          Matcher values = Pattern.compile("\\$" + N).matcher(tp.group(1));
          while (values.find()) targets.add(number(values.group(1)));
        }
      }
    } else if (action == Action.ADD || action == Action.REDUCE) {
      Matcher weights =
          Pattern.compile(
                  "(?i)(?:from|reducing the weight allocation)\\s+(?:a\\s+)?(?:weight(?:\\s+allocation)?(?:\\s+of)?\\s+)?"
                      + N
                      + "\\s+to\\s+(?:a\\s+)?(?:weight(?:\\s+of)?\\s+)?"
                      + N)
              .matcher(first);
      if (weights.find()) {
        before = number(weights.group(1));
        after = number(weights.group(2));
        if (weights.find()) fail("AMBIGUOUS_WEIGHTS");
      } else if (action == Action.ADD) {
        Matcher add =
            Pattern.compile(
                    "(?i)adding a weight of "
                        + N
                        + " to bring our total allocation to a weight of "
                        + N)
                .matcher(first);
        if (!add.find()) fail("MISSING_WEIGHTS");
        BigDecimal delta = number(add.group(1));
        after = number(add.group(2));
        before = after.subtract(delta);
      } else fail("MISSING_WEIGHTS");
      if (before.signum() <= 0
          || after.signum() <= 0
          || (action == Action.ADD ? after.compareTo(before) <= 0 : after.compareTo(before) >= 0))
        fail("INVALID_WEIGHT_DIRECTION");
    } else if (action == Action.CLOSE) after = BigDecimal.ZERO;
    if (action != Action.OPEN) {
      Matcher stops =
          Pattern.compile(
                  "(?i)(?:raising|moving|adjusting|lowering) our stop(?: loss)?(?: higher)?(?: from \\$[0-9.,]+)? to (?:near breakeven at )?\\$"
                      + N)
              .matcher(text);
      while (stops.find()) {
        BigDecimal next = number(stops.group(1));
        if (stop != null && stop.compareTo(next) != 0) fail("CONFLICTING_STOPS");
        stop = next;
      }
      if (action == Action.STOP && stop == null) fail("MISSING_STOP_CHANGE");
    }
    // A proportional reduction needs explicit before/after weights, not a price.
    // Some published reduction instructions omit the currency marker.
    if ((action == Action.OPEN || action == Action.ADD) && price == null) fail("MISSING_PRICE");
    if (price != null && price.signum() <= 0
        || stop != null && stop.signum() <= 0
        || after != null && after.compareTo(new BigDecimal("100")) > 0) fail("INVALID_TRADE_VALUE");
    if (action == Action.OPEN && (after.signum() <= 0 || stop.compareTo(price) >= 0))
      fail("INVALID_OPENING");
    // A protection instruction that the supported grammar did not extract must not disappear.
    if (action != Action.OPEN
        && Pattern.compile("(?i)(?:raising|moving|adjusting|lowering) (?:our |the )?stop")
            .matcher(text)
            .find()
        && stop == null) fail("UNPARSED_STOP_CHANGE");
    return new Alert(
        key,
        url,
        date,
        hash(authored),
        symbol,
        action,
        price,
        before,
        after,
        stop,
        List.copyOf(targets));
  }

  public static String hash(String body) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      body.replaceAll("(?U)\\s+", " ").trim().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String one(String regex, String text) {
    Matcher m = Pattern.compile(regex).matcher(text);
    if (!m.find()) throw new IllegalArgumentException("MISSING_SYMBOL");
    String s = m.group(1);
    if (m.find()) fail("AMBIGUOUS_SYMBOL");
    return s;
  }

  private static BigDecimal number(String s) {
    return new BigDecimal(
        s.matches("[0-9]+(?:,[0-9]{3})+(?:\\.[0-9]+)?") ? s.replace(",", "") : s.replace(',', '.'));
  }

  private static BigDecimal optional(String regex, String text) {
    Matcher m = Pattern.compile(regex).matcher(text);
    if (!m.find()) return null;
    BigDecimal n = number(m.group(1));
    if (m.find()) fail("DUPLICATE_INSTRUCTION");
    return n;
  }

  private static void fail(String code) {
    throw new IllegalArgumentException(code);
  }
}
