package com.pharmacore.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdlePolicyTest {

    @Test
    void defaultsAreFiveMinutesToLockAndThirtyToLogOut() {
        assertThat(IdlePolicy.stored(null, null)).isEqualTo(new IdlePolicy(5, 30));
        assertThat(IdlePolicy.stored(10, null)).isEqualTo(new IdlePolicy(10, 30));
        assertThat(IdlePolicy.stored(0, 60)).isEqualTo(new IdlePolicy(0, 60));
    }

    @Test
    void acceptsDelaysWithinBoundsWithLogoutAfterLock() {
        assertThat(IdlePolicy.problem(5, 30)).isEmpty();
        assertThat(IdlePolicy.problem(0, 5)).isEmpty();
        assertThat(IdlePolicy.problem(120, 720)).isEmpty();
        assertThat(IdlePolicy.problem(4, 5)).isEmpty();
    }

    @Test
    void refusesOutOfBoundsDelaysWithAReadableReason() {
        assertThat(IdlePolicy.problem(-1, 30)).hasValueSatisfying(p -> assertThat(p).contains("120 minutes"));
        assertThat(IdlePolicy.problem(121, 720)).isPresent();
        assertThat(IdlePolicy.problem(0, 4)).hasValueSatisfying(p -> assertThat(p).contains("5 minutes"));
        assertThat(IdlePolicy.problem(0, 721)).hasValueSatisfying(p -> assertThat(p).contains("12 heures"));
        assertThat(IdlePolicy.problem(30, 30)).hasValue("La déconnexion doit intervenir après le verrouillage");
        assertThat(IdlePolicy.problem(60, 45)).isPresent();
    }
}
