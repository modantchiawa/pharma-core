package com.pharmacore.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pharmacore.identity.domain.LockedRequestPolicy;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.support.IntegrationTest;
import com.pharmacore.support.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The lock of an unattended terminal is the server's: past the pharmacy's delay, or as soon as
 * the terminal locks, its token reads and changes nothing (423) until the server has checked the
 * owner's PIN; background requests do not keep it open, a ping cannot wake it up, five wrong
 * codes close it, and neither another pharmacy nor the vendor's console is concerned. Its moved
 * clock needs a context of its own, closed afterwards so that its connections are given back.
 */
@Import(ServerSessionLockIT.Clocks.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServerSessionLockIT extends IntegrationTest {

    private static final MutableClock CLOCK = new MutableClock();

    @TestConfiguration(proxyBeanMethods = false)
    static class Clocks {

        @Bean
        @Primary
        Clock mutableClock() {
            return CLOCK;
        }
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private record Signed(String access, String refresh) {
    }

    @Test
    void pastTheDelayTheServerLocksAndOnlyTheOwnersPinUnlocks() throws Exception {
        var pharmacy = onboardPharmacy("Verrou Maradi");
        var owner = signIn(pharmacy.slug(), pharmacy.ownerEmail(), pharmacy.ownerPassword());
        call(put("/api/v1/me/pin"), owner.access(), """
                {"password":"%s","pin":"2580"}""".formatted(pharmacy.ownerPassword()))
                .andExpect(status().isNoContent());
        call(get("/api/v1/inventory/stocks"), owner.access(), null).andExpect(status().isOk());

        // --- the terminal's own requests (heartbeat, background polls) do not keep the session open
        CLOCK.advance(Duration.ofMinutes(4));
        heartbeat(owner.access()).andExpect(status().isNoContent());
        background(get("/api/v1/subscription/state"), owner.access(), null).andExpect(status().isOk());
        CLOCK.advance(Duration.ofMinutes(3));
        call(get("/api/v1/inventory/stocks"), owner.access(), null)
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("session-locked"))
                .andExpect(jsonPath("$.detail").value(
                        "Poste verrouillé : saisissez votre code PIN ou votre mot de passe pour continuer"));
        assertThat(audit(pharmacy.tenantId(), "SESSION_LOCKED"))
                .singleElement().satisfies(d -> assertThat(d).contains("SERVER_LOCK", "\"decidedBy\":\"SERVER\""));

        // --- the same token replayed elsewhere, a ping, a fresh refresh: still nothing
        call(get("/api/v1/customers"), owner.access(), null).andExpect(status().isLocked());
        call(get("/api/v1/me"), owner.access(), null).andExpect(status().isLocked());
        call(post("/api/v1/me/session/activity"), owner.access(), null).andExpect(status().isLocked());
        heartbeat(owner.access()).andExpect(status().isNoContent());
        var refreshed = refresh(owner.refresh()).andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionLocked").value(true));
        String lockedToken = read(refreshed, "$.accessToken");
        String refreshToken = read(refreshed, "$.refreshToken");
        call(get("/api/v1/inventory/stocks"), lockedToken, null).andExpect(status().isLocked());
        heartbeat(lockedToken).andExpect(status().isNoContent());

        // --- a wrong PIN is counted, the owner's PIN unlocks and the data comes back
        unlock(refreshToken, "PIN", "0000")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("bad-credentials"))
                .andExpect(jsonPath("$.attemptsLeft").value(4));
        var unlocked = unlock(refreshToken, "PIN", "2580").andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionLocked").value(false));
        String fresh = read(unlocked, "$.accessToken");
        call(get("/api/v1/inventory/stocks"), fresh, null).andExpect(status().isOk());
        unlock(refreshToken, "PIN", "2580").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("session-expired"));
        // A token issued while locked is outdated once unlocked: the terminal refreshes it
        call(get("/api/v1/inventory/stocks"), lockedToken, null).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("token-outdated"));
        assertThat(audit(pharmacy.tenantId(), "SESSION_UNLOCK_FAILED")).singleElement()
                .satisfies(d -> assertThat(d).contains("\"attemptsLeft\":\"4\"", "\"method\":\"PIN\""));
        assertThat(audit(pharmacy.tenantId(), "SESSION_UNLOCKED")).singleElement()
                .satisfies(d -> assertThat(d).contains("\"method\":\"PIN\""));
    }

    @Test
    void aManualLockAppliesAtOnceAndFiveWrongCodesCloseTheSession() throws Exception {
        var pharmacy = onboardPharmacy("Verrou Agadez");
        var cashier = cashier(pharmacy);
        call(get("/api/v1/inventory/stocks"), cashier.access(), null).andExpect(status().isOk());

        call(post("/api/v1/me/session/lock"), cashier.access(), """
                {"reason":"MANUAL","terminalId":"T-TEST","idleMinutes":0}""")
                .andExpect(status().isNoContent());
        call(get("/api/v1/inventory/stocks"), cashier.access(), null).andExpect(status().isLocked());
        call(post("/api/v1/sales"), cashier.access(), "{}").andExpect(status().isLocked());
        // Reported again (another tab): journaled once
        call(post("/api/v1/me/session/lock"), cashier.access(), """
                {"reason":"MANUAL","terminalId":"T-TEST","idleMinutes":0}""")
                .andExpect(status().isNoContent());
        assertThat(audit(pharmacy.tenantId(), "SESSION_LOCKED"))
                .singleElement().satisfies(d -> assertThat(d).contains("MANUAL_LOCK", "\"decidedBy\":\"TERMINAL\""));

        for (int attempt = 1; attempt <= 4; attempt++) {
            unlock(cashier.refresh(), "PASSWORD", "faux-" + attempt)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.attemptsLeft").value(5 - attempt));
        }
        unlock(cashier.refresh(), "PASSWORD", "faux-5")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("session-expired"))
                .andExpect(jsonPath("$.reason").value("UNLOCK_FAILURES"));
        call(get("/api/v1/inventory/stocks"), cashier.access(), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("session-expired"));
        refresh(cashier.refresh()).andExpect(status().isUnprocessableContent());
        assertThat(audit(pharmacy.tenantId(), "SESSION_UNLOCK_FAILED")).hasSize(5);
        assertThat(audit(pharmacy.tenantId(), "SESSION_PIN_LOGOUT"))
                .singleElement().satisfies(d -> assertThat(d).contains("\"decidedBy\":\"SERVER\""));
    }

    @Test
    void activityPingsKeepTheSessionOpenAndTheLogoutDelayClosesIt() throws Exception {
        var pharmacy = onboardPharmacy("Verrou Tahoua");
        var owner = signIn(pharmacy.slug(), pharmacy.ownerEmail(), pharmacy.ownerPassword());

        CLOCK.advance(Duration.ofMinutes(4));
        call(post("/api/v1/me/session/activity"), owner.access(), null).andExpect(status().isNoContent());
        CLOCK.advance(Duration.ofMinutes(4));
        call(get("/api/v1/inventory/stocks"), owner.access(), null).andExpect(status().isOk());

        CLOCK.advance(Duration.ofMinutes(32));
        call(get("/api/v1/inventory/stocks"), owner.access(), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("session-expired"));
        refresh(owner.refresh()).andExpect(status().isUnprocessableContent());
        unlock(owner.refresh(), "PASSWORD", pharmacy.ownerPassword()).andExpect(status().isUnauthorized());
        assertThat(audit(pharmacy.tenantId(), "SESSION_IDLE_LOGOUT"))
                .singleElement().satisfies(d -> assertThat(d).contains("\"decidedBy\":\"SERVER\"", "T-TEST"));
        // A new sign-in opens a new session
        var again = signIn(pharmacy.slug(), pharmacy.ownerEmail(), pharmacy.ownerPassword());
        call(get("/api/v1/inventory/stocks"), again.access(), null).andExpect(status().isOk());
    }

    @Test
    void aLockConcernsOneSessionOfOnePharmacyAndNeverTheVendorConsole() throws Exception {
        var locked = onboardPharmacy("Verrou Zinder");
        var other = onboardPharmacy("Verrou Diffa");
        var lockedOwner = signIn(locked.slug(), locked.ownerEmail(), locked.ownerPassword());
        var secondTerminal = signIn(locked.slug(), locked.ownerEmail(), locked.ownerPassword());
        var otherOwner = signIn(other.slug(), other.ownerEmail(), other.ownerPassword());

        call(post("/api/v1/me/session/lock"), lockedOwner.access(), """
                {"reason":"IDLE","terminalId":"T-TEST","idleMinutes":5}""")
                .andExpect(status().isNoContent());
        call(get("/api/v1/inventory/stocks"), lockedOwner.access(), null).andExpect(status().isLocked());
        call(get("/api/v1/inventory/stocks"), secondTerminal.access(), null).andExpect(status().isOk());
        call(get("/api/v1/inventory/stocks"), otherOwner.access(), null).andExpect(status().isOk());
        assertThat(audit(other.tenantId(), "SESSION_LOCKED")).isEmpty();
        assertThat(rows(other.tenantId(), "SELECT count(*) FROM login_session WHERE locked_at IS NOT NULL"))
                .isZero();
        assertThat(rows(locked.tenantId(), "SELECT count(*) FROM login_session WHERE locked_at IS NOT NULL"))
                .isEqualTo(1);
        // A pharmacy reads only its own sessions (RLS)
        assertThat(rows(other.tenantId(), "SELECT count(*) FROM login_session")).isEqualTo(2);

        // The vendor's operators have no terminal session: never locked, however long they wait
        CLOCK.advance(Duration.ofHours(2));
        call(get("/api/platform/tenants"), platformToken, null).andExpect(status().isOk());
        call(get("/api/v1/inventory/stocks"), otherOwner.access(), null).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    private Signed signIn(String slug, String email, String password) throws Exception {
        var result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"pharmacy":"%s","email":"%s","password":"%s","terminalId":"T-TEST"}
                        """.formatted(slug, email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionLocked").value(false));
        return new Signed(read(result, "$.accessToken"), read(result, "$.refreshToken"));
    }

    private Signed cashier(Pharmacy pharmacy) throws Exception {
        String role = JsonPath.<List<String>>read(call(get("/api/v1/roles"), pharmacy.ownerToken(), null)
                .andReturn().getResponse().getContentAsString(), "$[?(@.code == 'CAISSIER')].id").getFirst();
        String email = "cashier-" + pharmacy.slug() + "@example.org";
        var registration = call(post("/api/v1/users"), pharmacy.ownerToken(), """
                {"email":"%s","displayName":"Caissier","roleIds":["%s"]}""".formatted(email, role))
                .andExpect(status().isCreated());
        return signIn(pharmacy.slug(), email, read(registration, "$.temporaryPassword"));
    }

    private ResultActions heartbeat(String token) throws Exception {
        return background(post("/api/v1/pos/heartbeat"), token, """
                {"terminalId":"T-TEST","appVersion":"test","pendingSales":0}""");
    }

    private ResultActions background(MockHttpServletRequestBuilder request, String token, String json)
            throws Exception {
        request.header(LockedRequestPolicy.BACKGROUND_HEADER, "1");
        return call(request, token, json);
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)));
    }

    private ResultActions unlock(String refreshToken, String method, String secret) throws Exception {
        return mvc.perform(post("/api/auth/unlock").contentType(MediaType.APPLICATION_JSON).content("""
                {"refreshToken":"%s","method":"%s","secret":"%s"}""".formatted(refreshToken, method, secret)));
    }

    private List<String> audit(UUID tenant, String action) {
        return inTenant(tenant, () -> jdbc.sql("""
                        SELECT details::text FROM audit_event WHERE action = :action ORDER BY sequence_no""")
                .param("action", action)
                .query(String.class)
                .list());
    }

    private long rows(UUID tenant, String sql) {
        return inTenant(tenant, () -> jdbc.sql(sql).query(Long.class).single());
    }

    private <T> T inTenant(UUID tenant, Supplier<T> query) {
        var context = RequestContext.of(Actor.SystemProcess.forTenant("test", new TenantId(tenant)));
        return TenantContext.call(context, () -> new TransactionTemplate(transactionManager).execute(s -> query.get()));
    }
}
