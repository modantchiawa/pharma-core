package com.pharmacore.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LockedRequestPolicyTest {

    @Test
    void aLockedTerminalOnlyReportsItsOwnState() {
        assertThat(LockedRequestPolicy.allowedWhileLocked("POST", "/api/v1/me/session/lock")).isTrue();
        assertThat(LockedRequestPolicy.allowedWhileLocked("POST", "/api/v1/me/session-events")).isTrue();
        assertThat(LockedRequestPolicy.allowedWhileLocked("POST", "/api/v1/pos/heartbeat/")).isTrue();

        // No data read, nothing changed, no way to wake the session up without the code
        assertThat(LockedRequestPolicy.allowedWhileLocked("GET", "/api/v1/inventory/stocks")).isFalse();
        assertThat(LockedRequestPolicy.allowedWhileLocked("GET", "/api/v1/me")).isFalse();
        assertThat(LockedRequestPolicy.allowedWhileLocked("GET", "/api/v1/subscription/state")).isFalse();
        assertThat(LockedRequestPolicy.allowedWhileLocked("POST", "/api/v1/me/session/activity")).isFalse();
        assertThat(LockedRequestPolicy.allowedWhileLocked("POST", "/api/v1/pos/sync/sales")).isFalse();
        assertThat(LockedRequestPolicy.allowedWhileLocked("POST", "/api/v1/sales")).isFalse();
        assertThat(LockedRequestPolicy.allowedWhileLocked("GET", "/api/v1/pos/heartbeat")).isFalse();
        assertThat(LockedRequestPolicy.allowedWhileLocked("GET", "/api/v1/inventory/products/x/image")).isFalse();
    }

    @Test
    void authenticationAndPublicPagesCarryNoSession() {
        assertThat(LockedRequestPolicy.outsideSession("/api/auth/unlock")).isTrue();
        assertThat(LockedRequestPolicy.outsideSession("/api/auth/logout")).isTrue();
        assertThat(LockedRequestPolicy.outsideSession("/api/public/hub/info")).isTrue();
        assertThat(LockedRequestPolicy.outsideSession("/api/v1/me")).isFalse();
        assertThat(LockedRequestPolicy.outsideSession("/api/authority")).isFalse();
    }

    @Test
    void unmarkedRequestsAreActivityBackgroundOnesAreNot() {
        assertThat(LockedRequestPolicy.countsAsActivity("GET", "/api/v1/inventory/stocks", null)).isTrue();
        assertThat(LockedRequestPolicy.countsAsActivity("POST", "/api/v1/me/session/activity", null)).isTrue();
        assertThat(LockedRequestPolicy.countsAsActivity("GET", "/api/v1/subscription/state", "1")).isFalse();
        assertThat(LockedRequestPolicy.countsAsActivity("POST", "/api/v1/pos/sync/sales", "1")).isFalse();
        assertThat(LockedRequestPolicy.countsAsActivity("GET", "/api/v1/inventory/stocks", " ")).isTrue();
    }

    @Test
    void whatALockedTerminalSendsIsNeverActivityMarkedOrNot() {
        assertThat(LockedRequestPolicy.countsAsActivity("POST", "/api/v1/pos/heartbeat", null)).isFalse();
        assertThat(LockedRequestPolicy.countsAsActivity("POST", "/api/v1/me/session/lock", null)).isFalse();
        assertThat(LockedRequestPolicy.countsAsActivity("POST", "/api/v1/me/session-events", null)).isFalse();
    }
}
