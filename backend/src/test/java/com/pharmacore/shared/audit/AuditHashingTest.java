package com.pharmacore.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditHashingTest {

    private static final UUID TENANT = UUID.fromString("3f9a8b2e-4c1d-4e5f-9a7b-1c2d3e4f5a6b");
    private static final Instant AT = Instant.parse("2026-10-18T08:30:00.123456789Z");

    @Test
    void canonicalDetailsAreSortedAndEscaped() {
        var details = new LinkedHashMap<String, String>();
        details.put("zeta", "line\nbreak");
        details.put("alpha", "quote\"d");

        assertThat(AuditHashing.canonicalDetails(details)).isEqualTo("{\"alpha\":\"quote\\\"d\",\"zeta\":\"line\\nbreak\"}");
    }

    @Test
    void hashIsStableAcrossTimestampPrecisionReadBack() {
        String original = hash(AT, "{}", AuditHashing.GENESIS);
        String afterDatabaseRoundTrip = hash(AuditHashing.normalize(AT), "{}", AuditHashing.GENESIS);

        assertThat(original).hasSize(64).isEqualTo(afterDatabaseRoundTrip);
    }

    @Test
    void anyChangeBreaksTheChain() {
        String first = hash(AT, AuditHashing.canonicalDetails(Map.of("netCents", "1000")), AuditHashing.GENESIS);
        String second = hash(AT, "{}", first);
        String tampered = hash(AT, AuditHashing.canonicalDetails(Map.of("netCents", "10")), AuditHashing.GENESIS);

        assertThat(tampered).isNotEqualTo(first);
        assertThat(hash(AT, "{}", tampered)).isNotEqualTo(second);
    }

    private static String hash(Instant at, String details, String previous) {
        return AuditHashing.hash(TENANT, 1, at, "USER", "u-1", "SALE_RECORDED", "SALE", "s-1", details, "corr-1",
                previous);
    }
}
