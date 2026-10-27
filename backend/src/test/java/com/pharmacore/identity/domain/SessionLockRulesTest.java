package com.pharmacore.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pharmacore.identity.domain.SessionLockRules.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionLockRulesTest {

    private static final Instant T0 = Instant.parse("2026-10-27T08:00:00Z");
    private static final IdlePolicy POLICY = new IdlePolicy(5, 30);

    private static LoginSessionState active(Instant lastActivity) {
        return new LoginSessionState(UUID.randomUUID(), lastActivity, null, null, false);
    }

    private static Instant at(Duration elapsed) {
        return T0.plus(elapsed);
    }

    @Test
    void graceCoversTheActivityPingAndTheWriteInterval() {
        assertThat(SessionLockRules.GRACE).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void anActiveSessionLocksOnlyOnceTheLockDelayAndTheGraceArePast() {
        var state = active(T0);
        assertThat(SessionLockRules.evaluate(state, POLICY, at(Duration.ofMinutes(5))).status()).isEqualTo(Status.ACTIVE);
        assertThat(SessionLockRules.evaluate(state, POLICY, at(Duration.ofSeconds(389))).status())
                .isEqualTo(Status.ACTIVE);

        var verdict = SessionLockRules.evaluate(state, POLICY, at(Duration.ofSeconds(390)));
        assertThat(verdict.status()).isEqualTo(Status.LOCKED);
        assertThat(verdict.changed()).isTrue();
        // The lock dates from the deadline, not from the request that found it
        assertThat(verdict.lockedAt()).isEqualTo(at(Duration.ofMinutes(5)));
    }

    @Test
    void aLockedSessionStaysLockedWhateverItsActivity() {
        var locked = active(T0).lockedAt(at(Duration.ofMinutes(1)), LockReason.MANUAL);
        var verdict = SessionLockRules.evaluate(locked, POLICY, at(Duration.ofMinutes(2)));
        assertThat(verdict.status()).isEqualTo(Status.LOCKED);
        assertThat(verdict.changed()).isFalse();
        assertThat(verdict.lockedAt()).isEqualTo(at(Duration.ofMinutes(1)));
        assertThat(locked.withActivity(at(Duration.ofMinutes(2))).locked()).isTrue();
    }

    @Test
    void theLogoutDelayClosesTheSessionLockedOrNot() {
        var state = active(T0);
        Instant justBefore = at(Duration.ofMinutes(31).plusSeconds(29));
        Instant due = at(Duration.ofMinutes(31).plusSeconds(30));
        assertThat(SessionLockRules.evaluate(state.lockedAt(at(Duration.ofMinutes(5)), LockReason.IDLE), POLICY,
                justBefore).status()).isEqualTo(Status.LOCKED);

        var closing = SessionLockRules.evaluate(state, POLICY, due);
        assertThat(closing.status()).isEqualTo(Status.CLOSED);
        assertThat(closing.changed()).isTrue();
        assertThat(SessionLockRules.evaluate(state.lockedAt(at(Duration.ofMinutes(5)), LockReason.IDLE), POLICY, due)
                .status()).isEqualTo(Status.CLOSED);
    }

    @Test
    void anEndedOrUnknownSessionIsClosedWithoutAnotherTransition() {
        assertThat(SessionLockRules.evaluate(null, POLICY, T0).status()).isEqualTo(Status.CLOSED);
        var ended = SessionLockRules.evaluate(active(T0).closed(), POLICY, T0);
        assertThat(ended.status()).isEqualTo(Status.CLOSED);
        assertThat(ended.changed()).isFalse();
    }

    @Test
    void aPharmacyThatNeverLocksStillClosesIdleSessions() {
        var never = new IdlePolicy(0, 45);
        var state = active(T0);
        assertThat(SessionLockRules.lockAt(state, never)).isNull();
        assertThat(SessionLockRules.evaluate(state, never, at(Duration.ofMinutes(40))).status()).isEqualTo(Status.ACTIVE);
        assertThat(SessionLockRules.evaluate(state, never, at(Duration.ofMinutes(47))).status()).isEqualTo(Status.CLOSED);
    }

    @Test
    void activityPushesTheDeadlinesAndUnlockRestartsTheCount() {
        var state = active(T0).withActivity(at(Duration.ofMinutes(4)));
        assertThat(SessionLockRules.evaluate(state, POLICY, at(Duration.ofMinutes(8))).status()).isEqualTo(Status.ACTIVE);
        assertThat(state.withActivity(T0).lastActivityAt()).isEqualTo(at(Duration.ofMinutes(4)));

        var unlocked = state.lockedAt(at(Duration.ofMinutes(9)), LockReason.SERVER).unlocked(at(Duration.ofMinutes(20)));
        assertThat(unlocked.locked()).isFalse();
        assertThat(SessionLockRules.evaluate(unlocked, POLICY, at(Duration.ofMinutes(24))).status())
                .isEqualTo(Status.ACTIVE);
        assertThat(SessionLockRules.idleMinutes(unlocked, at(Duration.ofMinutes(27)))).isEqualTo(7);
    }

    @Test
    void activityIsWrittenAtMostEveryThirtySeconds() {
        assertThat(SessionLockRules.persistDue(T0, null)).isFalse();
        assertThat(SessionLockRules.persistDue(T0, at(Duration.ofSeconds(29)))).isFalse();
        assertThat(SessionLockRules.persistDue(T0, at(Duration.ofSeconds(30)))).isTrue();
    }
}
