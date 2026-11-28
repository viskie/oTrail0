package in.otrail.extensions.opay.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Multi-source matching. Strong keys (UTR/RRN, provider reference) may link records
 * automatically; soft scores only produce review suggestions, never silent merges.
 */
public final class Reconciliation {
  public static final int STRONG = 100;
  public static final int REVIEW_THRESHOLD = 55;

  private Reconciliation() {}

  public static final class Match {
    public final LedgerTransaction target;
    public final int score;
    public final boolean strong;
    public final boolean ambiguous;
    public final String reason;
    public final List<Match> alternatives;

    Match(LedgerTransaction target, int score, boolean strong, boolean ambiguous, String reason,
        List<Match> alternatives) {
      this.target = target;
      this.score = score;
      this.strong = strong;
      this.ambiguous = ambiguous;
      this.reason = reason;
      this.alternatives = alternatives;
    }

    public boolean canAutoLink() { return strong || (score >= STRONG && !ambiguous); }
  }

  public static boolean sameTransaction(LedgerTransaction left, LedgerTransaction right) {
    if (left == null || right == null) return false;
    if (left.id.equals(right.id)) return true;
    String leftUtr = normalize(left.utr), rightUtr = normalize(right.utr);
    if (!leftUtr.isEmpty() && leftUtr.equals(rightUtr) && left.amountPaise == right.amountPaise)
      return true;
    String leftRef = normalize(left.transactionId), rightRef = normalize(right.transactionId);
    if (!leftRef.isEmpty() && leftRef.equals(rightRef)
        && left.amountPaise == right.amountPaise
        && left.provider.equalsIgnoreCase(right.provider)) return true;
    return left.amountPaise == right.amountPaise
        && left.direction == right.direction
        && Math.abs(left.occurredAt - right.occurredAt) <= 60_000L
        && normalize(left.counterparty).equals(normalize(right.counterparty))
        && !normalize(left.counterparty).isEmpty()
        && left.accountId.equals(right.accountId);
  }

  public static Match best(LedgerTransaction incoming, List<LedgerTransaction> existing) {
    List<Match> matches = candidates(incoming, existing);
    return matches.isEmpty() ? null : matches.get(0);
  }

  public static List<Match> candidates(LedgerTransaction incoming, List<LedgerTransaction> existing) {
    List<Match> matches = new ArrayList<>();
    for (LedgerTransaction candidate : existing) {
      if (candidate.id.equals(incoming.id)) continue;
      int score = 0;
      boolean strong = false;
      StringBuilder reason = new StringBuilder();
      String incomingUtr = normalize(incoming.utr);
      if (!incomingUtr.isEmpty() && incomingUtr.equals(normalize(candidate.utr))) {
        score += STRONG;
        strong = true;
        reason.append("UTR match");
      }
      String incomingRef = normalize(incoming.transactionId);
      if (!incomingRef.isEmpty() && incomingRef.equals(normalize(candidate.transactionId))
          && incoming.provider.equalsIgnoreCase(candidate.provider)) {
        score += STRONG;
        strong = true;
        if (reason.length() > 0) reason.append(" + ");
        reason.append("provider reference match");
      }
      if (incoming.amountPaise == candidate.amountPaise && incoming.amountPaise > 0) {
        score += 40;
        if (reason.length() > 0) reason.append(", ");
        reason.append("same amount");
      }
      if (incoming.direction == candidate.direction) score += 15;
      long deltaDays = Math.abs(incoming.occurredAt - candidate.occurredAt) / 86_400_000L;
      if (deltaDays == 0) score += 20;
      else if (deltaDays <= 1) score += 12;
      else if (deltaDays <= 3) score += 6;
      if (!incoming.accountId.isEmpty() && incoming.accountId.equals(candidate.accountId)) score += 10;
      if (normalize(incoming.counterparty).equals(normalize(candidate.counterparty))
          && !normalize(incoming.counterparty).isEmpty()) score += 10;
      if (!incoming.upiAddress.isEmpty()
          && incoming.upiAddress.equalsIgnoreCase(candidate.upiAddress)) score += 10;
      if (incoming.amountPaise == candidate.amountPaise
          && incoming.direction != candidate.direction) score -= 25;
      if (score < REVIEW_THRESHOLD || (!strong && incoming.amountPaise != candidate.amountPaise)) continue;
      matches.add(new Match(candidate, score, strong, false,
          reason.length() == 0 ? "similar" : reason.toString(), Collections.emptyList()));
    }
    Collections.sort(matches, new Comparator<Match>() {
      @Override public int compare(Match left, Match right) {
        if (left.score != right.score) return right.score - left.score;
        return Long.compare(Math.abs(left.target.occurredAt - incoming.occurredAt),
            Math.abs(right.target.occurredAt - incoming.occurredAt));
      }
    });
    if (matches.size() >= 2) {
      Match first = matches.get(0);
      boolean ambiguous = !first.strong
          && first.score == matches.get(1).score;
      if (ambiguous) {
        List<Match> alternatives = new ArrayList<>(matches.subList(1, matches.size()));
        matches.set(0, new Match(first.target, first.score, false, true,
            first.reason + " (ambiguous)", alternatives));
      }
    }
    return matches;
  }

  /**
   * Links a reviewed pair without merging their amounts. Both records keep their own
   * provenance; the statement copy simply points back at the initiated transaction.
   */
  public static void link(LedgerTransaction initiated, LedgerTransaction imported) {
    imported.relatedId = initiated.id;
    if (imported.reconciliation == LedgerTransaction.Reconciliation.UNVERIFIED)
      imported.reconciliation = LedgerTransaction.Reconciliation.STATEMENT_MATCHED;
    if (initiated.relatedId.isEmpty()) initiated.relatedId = imported.id;
    if (initiated.reconciliation == LedgerTransaction.Reconciliation.UNVERIFIED)
      initiated.reconciliation = LedgerTransaction.Reconciliation.USER_CONFIRMED;
    String provenance = "verified with " + imported.origin.name().toLowerCase(Locale.US)
        + (imported.transactionId.isEmpty() ? "" : " ref " + imported.transactionId);
    if (!initiated.annotations.contains(provenance)) {
      initiated.annotations = (initiated.annotations.isEmpty() ? "" : initiated.annotations + "\n")
          + provenance;
    }
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.US).replaceAll("\\s+", "");
  }
}
