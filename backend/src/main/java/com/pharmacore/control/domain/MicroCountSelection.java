package com.pharmacore.control.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Draw of the daily blind micro-count: a weighted sample without replacement where each
 * product's weight is {@code 1 + score}.
 * <p>
 * The draw is the Efraimidis–Spirakis method: every product gets the key {@code ln(u) / weight}
 * where {@code u} is uniform in (0, 1), and the products with the largest keys win. {@code u} is
 * not random: it is derived from SHA-256 of the seed and the product id, and the seed combines a
 * secret of the pharmacy, its id and the date. The same day therefore always gives the same list
 * whatever the order of the candidates, the next day gives another one, and nobody without the
 * pharmacy's secret can compute tomorrow's list.
 */
public final class MicroCountSelection {

    private MicroCountSelection() {
    }

    public record Pick(RiskFactors factors, int score, List<String> reasons) {
    }

    public static String seed(String secret, UUID tenantId, LocalDate day) {
        return secret + ":" + tenantId + ":" + day;
    }

    public static List<Pick> select(List<RiskFactors> candidates, int size, String seed) {
        record Keyed(RiskFactors factors, int score, double key) {
        }
        return candidates.stream()
                .map(f -> {
                    int score = RiskScore.of(f).total();
                    return new Keyed(f, score, Math.log(uniform(seed, f.productId())) / (1.0 + score));
                })
                .sorted(Comparator.comparingDouble(Keyed::key).reversed()
                        .thenComparing(k -> k.factors().productId()))
                .limit(Math.max(0, size))
                .map(k -> new Pick(k.factors(), k.score(), RiskScore.reasons(k.factors())))
                .toList();
    }

    /** Uniform value in the open interval (0, 1), stable for a seed and a product. */
    static double uniform(String seed, UUID productId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((seed + "|" + productId).getBytes(StandardCharsets.UTF_8));
            long bits = ByteBuffer.wrap(digest).getLong() >>> 11;
            return (bits + 1.0) / ((1L << 53) + 2.0);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
