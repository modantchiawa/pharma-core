package com.pharmacore.subscription.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pharmacore.subscription.domain.LicenseRejectedException.Reason;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class HubLicenseRulesTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final Instant ISSUED = Instant.parse("2026-10-20T10:00:00Z");
    private static final Duration TOLERANCE = LicensePolicy.DEFAULT.clockTolerance();

    private final KeyPair cloud = LicenseKeys.generate();
    private final String kid = LicenseKeys.kid(cloud.getPublic());
    private final Function<String, Optional<PublicKey>> pinned =
            id -> Optional.ofNullable(Map.of(kid, cloud.getPublic()).get(id));

    private static LicenseClaims claims(UUID tenant, long serial) {
        return new LicenseClaims(tenant, "sahel", "PRO", 5, LicenseClaims.Status.ACTIVE,
                ISSUED.plus(Duration.ofDays(90)), ISSUED.plus(Duration.ofDays(93)), null, ISSUED,
                ISSUED.plus(Duration.ofDays(15)), serial);
    }

    @Test
    void signedLicenseRoundTripsEveryClaim() {
        var original = claims(TENANT, 7);
        String token = LicenseCodec.sign(original, kid, cloud.getPrivate());

        assertThat(token).startsWith("PCL1." + kid + ".");
        assertThat(HubLicenseRules.accept(token, pinned, TENANT, null)).isEqualTo(original);
    }

    @Test
    void licenseSignedWithAnotherKeyIsRejected() {
        var forger = LicenseKeys.generate();
        String unknownKid = LicenseCodec.sign(claims(TENANT, 7), LicenseKeys.kid(forger.getPublic()), forger.getPrivate());
        String stolenKid = LicenseCodec.sign(claims(TENANT, 7), kid, forger.getPrivate());

        assertRejected(() -> HubLicenseRules.accept(unknownKid, pinned, TENANT, null), Reason.UNKNOWN_KEY);
        assertRejected(() -> HubLicenseRules.accept(stolenKid, pinned, TENANT, null), Reason.BAD_SIGNATURE);
    }

    @Test
    void editedClaimsBreakTheSignature() {
        String token = LicenseCodec.sign(claims(TENANT, 7), kid, cloud.getPrivate());
        String[] parts = token.split("\\.");
        String claimsText = new String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8)
                .replace("exp=2026-11-04T10:00:00Z", "exp=2027-11-04T10:00:00Z");
        String forged = parts[0] + "." + parts[1] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(claimsText.getBytes(StandardCharsets.UTF_8))
                + "." + parts[3];

        assertThat(claimsText).contains("exp=2027-11-04T10:00:00Z");
        assertRejected(() -> HubLicenseRules.accept(forged, pinned, TENANT, null), Reason.BAD_SIGNATURE);
        assertRejected(() -> HubLicenseRules.accept("not-a-license", pinned, TENANT, null), Reason.MALFORMED);
    }

    @Test
    void licenseOfAnotherPharmacyIsRejected() {
        String token = LicenseCodec.sign(claims(UUID.randomUUID(), 7), kid, cloud.getPrivate());

        assertRejected(() -> HubLicenseRules.accept(token, pinned, TENANT, null), Reason.WRONG_TENANT);
    }

    @Test
    void olderSerialIsNeverAcceptedAgain() {
        var held = claims(TENANT, 8);
        String older = LicenseCodec.sign(claims(TENANT, 7), kid, cloud.getPrivate());
        String same = LicenseCodec.sign(held, kid, cloud.getPrivate());

        assertRejected(() -> HubLicenseRules.accept(older, pinned, TENANT, held), Reason.SERIAL_REGRESSION);
        assertThat(HubLicenseRules.accept(same, pinned, TENANT, held).serial()).isEqualTo(8);
    }

    @Test
    void watermarkOnlyMovesForward() {
        Instant mark = ISSUED.plus(Duration.ofDays(4));

        assertThat(HubLicenseRules.observe(null, ISSUED)).isEqualTo(ISSUED);
        assertThat(HubLicenseRules.observe(mark, ISSUED)).isEqualTo(mark);
        assertThat(HubLicenseRules.observe(mark, mark.plusSeconds(5))).isEqualTo(mark.plusSeconds(5));
    }

    @Test
    void freshLicenseReanchorsTheWatermarkOnlyWhenTheClockAgreesWithTheCloud() {
        Instant farFuture = ISSUED.plus(Duration.ofDays(400));

        // clock corrected after a mistake far in the future: the cloud's time takes over
        assertThat(HubLicenseRules.afterLicense(farFuture, ISSUED.plusSeconds(30), ISSUED, TOLERANCE))
                .isEqualTo(ISSUED.plusSeconds(30));
        // clock set back: the watermark jumps to the cloud's time, and the evaluator sees tampering
        Instant behind = ISSUED.minus(Duration.ofDays(3));
        Instant mark = HubLicenseRules.afterLicense(null, behind, ISSUED, TOLERANCE);
        assertThat(mark).isEqualTo(ISSUED);
        var state = LicenseEvaluator.evaluate(claims(TENANT, 1), behind, mark, null, LicensePolicy.DEFAULT);
        assertThat(state.reason()).isEqualTo(LicenseState.Reason.CLOCK_TAMPERED);
    }

    @Test
    void replacementKeyIsTrustedOnlyThroughTheEndorsementOfThePreviousOne() {
        var next = LicenseKeys.generate();
        String nextKid = LicenseKeys.kid(next.getPublic());
        String nextPem = LicenseKeys.publicPem(next.getPublic());
        String endorsement = LicenseKeys.endorse(cloud.getPrivate(), nextKid, nextPem);
        var impostor = LicenseKeys.generate();

        assertThat(LicenseKeys.endorsementValid(cloud.getPublic(), nextKid, nextPem, endorsement)).isTrue();
        assertThat(LicenseKeys.endorsementValid(cloud.getPublic(), nextKid,
                LicenseKeys.publicPem(impostor.getPublic()), endorsement)).isFalse();
        assertThat(LicenseKeys.endorsementValid(cloud.getPublic(), nextKid, nextPem,
                LicenseKeys.endorse(impostor.getPrivate(), nextKid, nextPem))).isFalse();
        assertThat(LicenseKeys.parsePublic(nextPem)).isEqualTo(next.getPublic());
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, Reason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(LicenseRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
