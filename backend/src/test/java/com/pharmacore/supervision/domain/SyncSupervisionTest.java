package com.pharmacore.supervision.domain;

import static com.pharmacore.supervision.domain.SyncSupervision.HUB_UNREACHABLE_AFTER;
import static com.pharmacore.supervision.domain.SyncSupervision.TERMINAL_STALE_AFTER;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SyncSupervisionTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private static final Instant RECENT = NOW.minusSeconds(30);

    @Test
    void thresholdsMatchTheHeartbeatAndSyncCadences() {
        // PWA heartbeat every 60 s, hub round every 30 s: five and ten missed signals
        assertThat(TERMINAL_STALE_AFTER).isEqualTo(Duration.ofMinutes(5));
        assertThat(HUB_UNREACHABLE_AFTER).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void aRegisterInContactWithAnEmptyJournalIsSynchronized() {
        assertThat(SyncSupervision.terminalState(RECENT, NOW.minus(Duration.ofDays(3)), 0, NOW))
                .as("no sale sold since: nothing to send, the age of the last replay does not matter")
                .isEqualTo(TerminalSyncState.SYNCED);
    }

    @Test
    void pendingSalesMakeARegisterPending() {
        assertThat(SyncSupervision.terminalState(RECENT, NOW.minusSeconds(60), 3, NOW))
                .isEqualTo(TerminalSyncState.PENDING);
        assertThat(SyncSupervision.terminalState(RECENT, null, 1, NOW))
                .as("pending wins over never synchronized: it is the actionable fact")
                .isEqualTo(TerminalSyncState.PENDING);
    }

    @Test
    void aRegisterThatNeverReplayedASaleIsNever() {
        assertThat(SyncSupervision.terminalState(RECENT, null, 0, NOW)).isEqualTo(TerminalSyncState.NEVER);
    }

    @Test
    void silenceBeyondTheThresholdIsStaleWhateverTheJournal() {
        Instant atThreshold = NOW.minus(TERMINAL_STALE_AFTER);
        Instant justBeyond = atThreshold.minusMillis(1);
        assertThat(SyncSupervision.terminalState(atThreshold, NOW, 0, NOW))
                .as("exactly at the threshold the register is still in contact")
                .isEqualTo(TerminalSyncState.SYNCED);
        assertThat(SyncSupervision.terminalState(justBeyond, NOW, 0, NOW)).isEqualTo(TerminalSyncState.STALE);
        assertThat(SyncSupervision.terminalState(NOW.minus(Duration.ofDays(2)), null, 4, NOW))
                .isEqualTo(TerminalSyncState.STALE);
        assertThat(SyncSupervision.isStale(null, NOW)).isTrue();
    }

    @Test
    void statesAreComputedAtTheReferenceInstantNotAgainstTheWallClock() {
        // hub last contacted the cloud two days ago; the register beat 30 s before that contact
        Instant hubContact = NOW.minus(Duration.ofDays(2));
        Instant reference = SyncSupervision.reference(true, hubContact, NOW);
        assertThat(reference).isEqualTo(hubContact);
        assertThat(SyncSupervision.terminalState(hubContact.minusSeconds(30), hubContact, 0, reference))
                .isEqualTo(TerminalSyncState.SYNCED);
        assertThat(SyncSupervision.terminalState(hubContact.minusSeconds(30), hubContact, 0, NOW))
                .isEqualTo(TerminalSyncState.STALE);
    }

    @Test
    void referenceIsNowForLiveDataOrWithoutUsableHubContact() {
        Instant hubContact = NOW.minusSeconds(90);
        assertThat(SyncSupervision.reference(false, hubContact, NOW)).isEqualTo(NOW);
        assertThat(SyncSupervision.reference(true, null, NOW)).isEqualTo(NOW);
        assertThat(SyncSupervision.reference(true, NOW.plusSeconds(5), NOW))
                .as("a hub clock ahead of the cloud never pushes the reference into the future")
                .isEqualTo(NOW);
        assertThat(SyncSupervision.reference(true, hubContact, NOW)).isEqualTo(hubContact);
    }

    @Test
    void hubIsReachableUpToItsThreshold() {
        assertThat(SyncSupervision.hubReachable(NOW.minus(HUB_UNREACHABLE_AFTER), NOW)).isTrue();
        assertThat(SyncSupervision.hubReachable(NOW.minus(HUB_UNREACHABLE_AFTER).minusMillis(1), NOW)).isFalse();
        assertThat(SyncSupervision.hubReachable(null, NOW)).isFalse();
    }
}
