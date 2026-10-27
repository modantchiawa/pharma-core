package com.pharmacore.subscription.domain;

import java.time.Instant;

/**
 * What a pharmacy may do right now.
 *
 * @param reason       why the state is not ACTIVE, {@code null} when it is
 * @param deadline     the date the reason refers to (end of the paid period, offline limit…)
 * @param daysLeft     whole days left before {@code deadline}, rounded up; {@code null} without deadline
 * @param blocksAt     earliest instant at which the pharmacy becomes BLOCKED if nothing happens, so
 *                     a terminal that loses its server keeps enforcing the block
 * @param blockedSince when the block took effect, {@code null} when it cannot be dated (clock
 *                     tampered with, no license): sales replayed from offline terminals that
 *                     happened before it are still accepted
 * @param claims       the license the state was computed from, {@code null} without license
 */
public record LicenseState(Status status,
                           Reason reason,
                           Instant deadline,
                           Integer daysLeft,
                           Instant blocksAt,
                           Instant blockedSince,
                           LicenseClaims claims) {

    public enum Status {
        ACTIVE,
        WARNING,
        GRACE,
        BLOCKED
    }

    public enum Reason {
        /** WARNING: the paid period ends soon. */
        PAYMENT_DUE,
        /** WARNING: the hub has not reached the cloud for a while. */
        OFFLINE_LIMIT,
        /** GRACE: the paid period is over; BLOCKED: so is the grace period. */
        EXPIRED,
        SUSPENDED,
        OFFLINE_TOO_LONG,
        CLOCK_TAMPERED,
        NO_LICENSE
    }

    public boolean blocked() {
        return status == Status.BLOCKED;
    }

    /** A sale that happened at {@code occurredAt} on an offline terminal may still be recorded. */
    public boolean admitsSaleAt(Instant occurredAt) {
        return !blocked() || (blockedSince != null && occurredAt.isBefore(blockedSince));
    }

    /** Problem detail shown when a modification is refused. */
    public String blockedMessage() {
        return switch (reason) {
            case SUSPENDED -> "Abonnement suspendu par l'éditeur : les ventes et les modifications sont bloquées. "
                    + "La consultation, l'ordonnancier, les rapports et les exports restent accessibles.";
            case EXPIRED -> "Abonnement expiré : les ventes et les modifications sont bloquées jusqu'au renouvellement. "
                    + "La consultation, l'ordonnancier, les rapports et les exports restent accessibles.";
            case OFFLINE_TOO_LONG -> "Licence non renouvelée depuis plus de 15 jours : connectez le serveur de "
                    + "l'officine à Internet pour la renouveler.";
            case CLOCK_TAMPERED -> "Horloge du poste incohérente : corrigez la date et l'heure du serveur de "
                    + "l'officine puis connectez-le à Internet.";
            case NO_LICENSE -> "Aucune licence valide : connectez le serveur de l'officine à Internet pour la recevoir.";
            case PAYMENT_DUE, OFFLINE_LIMIT -> "Licence valide.";
        };
    }
}
