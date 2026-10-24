package com.pharmacore.sales.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Tamper-evident chain of Z reports per terminal: each closed session carries the SHA-256 of its
 * figures and of the previous report's hash, so a report altered or removed after the fact breaks
 * the chain of every later report on that terminal.
 */
public final class CashReportHashing {

    /** Hash used as the predecessor of the first report of a terminal. */
    public static final String GENESIS = "GENESIS";

    private CashReportHashing() {
    }

    public static String hash(UUID sessionId, String terminalId, long openingFloatCents, long salesNetCents,
                              long expectedCashCents, long countedCashCents, Instant closedAt, String previousHash) {
        String payload = String.join("|",
                sessionId.toString(),
                terminalId,
                Long.toString(openingFloatCents),
                Long.toString(salesNetCents),
                Long.toString(expectedCashCents),
                Long.toString(countedCashCents),
                Long.toString(countedCashCents - expectedCashCents),
                closedAt.toString(),
                previousHash == null ? GENESIS : previousHash);
        return sha256(payload);
    }

    /** True when {@code reportHash} is the hash of these figures chained on {@code previousHash}. */
    public static boolean verify(String reportHash, UUID sessionId, String terminalId, long openingFloatCents,
                                 long salesNetCents, long expectedCashCents, long countedCashCents, Instant closedAt,
                                 String previousHash) {
        return reportHash != null && reportHash.equals(hash(sessionId, terminalId, openingFloatCents, salesNetCents,
                expectedCashCents, countedCashCents, closedAt, previousHash));
    }

    private static String sha256(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
