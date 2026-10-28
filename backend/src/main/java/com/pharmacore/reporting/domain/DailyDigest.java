package com.pharmacore.reporting.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

/**
 * The owner's evening figures, written for a phone screen: one compact line per subject, the
 * comparison with the day before, and what needs attention tomorrow. Pure value; amounts are
 * formatted by the caller's currency function.
 */
public record DailyDigest(LocalDate date, String pharmacy, long sales, long netCents, long previousNetCents,
                          long marginCents, long averageBasketCents, long cancelled, List<Payment> payments,
                          long cashDifferenceCents, long expensesCents, long sessionsClosed, long belowThreshold,
                          long shortfallProducts, long expiringLots, long customerCreditCents, long debtors,
                          long pendingMessages) {

    /** What the alert feed can hold (control_alert.detail). */
    public static final int ALERT_MAX = 500;

    public record Payment(String label, long amountCents) {
    }

    public DailyDigest {
        payments = payments == null ? List.of() : List.copyOf(payments);
    }

    /** Variation against the previous day in percent, null when there is nothing to compare with. */
    public Integer variationPercent() {
        if (previousNetCents <= 0) {
            return null;
        }
        return (int) Math.round((netCents - previousNetCents) * 100.0 / previousNetCents);
    }

    public int marginPercent() {
        return netCents <= 0 ? 0 : (int) Math.round(marginCents * 100.0 / netCents);
    }

    /** Short form for the alert feed and an SMS: the essentials, never more than {@value #ALERT_MAX} characters. */
    public String summary(LongFunction<String> money) {
        List<String> parts = new ArrayList<>();
        String variation = variationPercent() == null ? "" : " (" + signed(variationPercent()) + " % vs veille)";
        parts.add("CA " + money.apply(netCents) + variation);
        parts.add(sales + " vente(s), panier " + money.apply(averageBasketCents));
        parts.add("marge " + marginPercent() + " %");
        for (Payment payment : payments) {
            parts.add(payment.label() + " " + money.apply(payment.amountCents()));
        }
        if (cancelled > 0) {
            parts.add(cancelled + " annulée(s)");
        }
        if (sessionsClosed > 0) {
            parts.add("écart de caisse " + money.apply(cashDifferenceCents));
        }
        if (expensesCents > 0) {
            parts.add("dépenses " + money.apply(expensesCents));
        }
        if (shortfallProducts > 0 || belowThreshold > 0) {
            parts.add(shortfallProducts + " rupture(s), " + belowThreshold + " sous le seuil");
        }
        if (expiringLots > 0) {
            parts.add(expiringLots + " lot(s) périment sous 90 j");
        }
        if (customerCreditCents > 0) {
            parts.add("créances " + money.apply(customerCreditCents) + " (" + debtors + " client(s))");
        }
        if (pendingMessages > 0) {
            parts.add(pendingMessages + " message(s) patient à envoyer");
        }
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            String candidate = out.isEmpty() ? part : out + " · " + part;
            if (candidate.length() > ALERT_MAX) {
                break;
            }
            out.setLength(0);
            out.append(candidate);
        }
        return out.toString();
    }

    /** Long form for WhatsApp: a titled message with one line per subject. */
    public String message(LongFunction<String> money) {
        StringBuilder out = new StringBuilder();
        out.append(pharmacy).append(" — rapport du ").append(date).append('\n');
        out.append("Chiffre d'affaires : ").append(money.apply(netCents));
        if (variationPercent() != null) {
            out.append(" (").append(signed(variationPercent())).append(" % vs veille)");
        }
        out.append('\n');
        out.append("Ventes : ").append(sales).append(" · panier moyen ").append(money.apply(averageBasketCents))
                .append(" · marge ").append(marginPercent()).append(" %\n");
        if (!payments.isEmpty()) {
            out.append("Encaissements : ");
            List<String> shares = new ArrayList<>();
            for (Payment payment : payments) {
                shares.add(payment.label() + " " + money.apply(payment.amountCents()));
            }
            out.append(String.join(", ", shares)).append('\n');
        }
        if (sessionsClosed > 0) {
            out.append("Caisse : ").append(sessionsClosed).append(" session(s) clôturée(s), écart ")
                    .append(money.apply(cashDifferenceCents)).append('\n');
        }
        if (expensesCents > 0) {
            out.append("Dépenses : ").append(money.apply(expensesCents)).append('\n');
        }
        if (cancelled > 0) {
            out.append("Annulations : ").append(cancelled).append('\n');
        }
        out.append("Stock : ").append(shortfallProducts).append(" rupture(s), ").append(belowThreshold)
                .append(" produit(s) sous le seuil, ").append(expiringLots).append(" lot(s) périment sous 90 jours\n");
        out.append("Créances clients : ").append(money.apply(customerCreditCents)).append(" (").append(debtors)
                .append(" client(s))\n");
        if (pendingMessages > 0) {
            out.append("Suivi patients : ").append(pendingMessages).append(" message(s) à envoyer\n");
        }
        return out.toString().trim();
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }
}
