package com.pharmacore.procurement.domain;

import java.time.LocalDate;
import java.util.List;

/** Follow-up rules of a purchase order once it has left the pharmacy. Pure functions. */
public final class OrderTracking {

    public static final String DRAFT = "DRAFT";
    public static final String SENT = "SENT";
    public static final String PARTIALLY_RECEIVED = "PARTIALLY_RECEIVED";
    public static final String RECEIVED = "RECEIVED";
    public static final String CANCELLED = "CANCELLED";

    private OrderTracking() {
    }

    /** List filters of the tracking screen. */
    public enum Scope {
        DRAFTS(List.of(DRAFT)),
        OPEN(List.of(SENT, PARTIALLY_RECEIVED)),
        OVERDUE(List.of(SENT, PARTIALLY_RECEIVED)),
        CLOSED(List.of(RECEIVED, CANCELLED)),
        ALL(List.of(DRAFT, SENT, PARTIALLY_RECEIVED, RECEIVED, CANCELLED));

        private final List<String> statuses;

        Scope(List<String> statuses) {
            this.statuses = statuses;
        }

        public List<String> statuses() {
            return statuses;
        }

        /** Open orders are listed by delivery date (the most urgent first), the others by creation. */
        public boolean byExpectedDate() {
            return this == OPEN || this == OVERDUE;
        }
    }

    /** Sent to the supplier and still waiting for goods. */
    public static boolean open(String status) {
        return SENT.equals(status) || PARTIALLY_RECEIVED.equals(status);
    }

    /** Still waiting for goods after the announced delivery day, in the pharmacy's calendar. */
    public static boolean overdue(String status, LocalDate expectedAt, LocalDate today) {
        return open(status) && expectedAt != null && expectedAt.isBefore(today);
    }

    /** Closed as received although some ordered units never came. */
    public static boolean shortClosed(String status, boolean anyLineIncomplete) {
        return RECEIVED.equals(status) && anyLineIncomplete;
    }
}
