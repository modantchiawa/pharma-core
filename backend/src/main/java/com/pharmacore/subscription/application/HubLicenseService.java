package com.pharmacore.subscription.application;

import com.pharmacore.shared.runtime.NodeIdentity;
import com.pharmacore.subscription.application.LicenseKeyRing.PublicLicenseKey;
import com.pharmacore.subscription.domain.HubLicenseRules;
import com.pharmacore.subscription.domain.LicenseClaims;
import com.pharmacore.subscription.domain.LicenseCodec;
import com.pharmacore.subscription.domain.LicenseEvaluator;
import com.pharmacore.subscription.domain.LicenseKeys;
import com.pharmacore.subscription.domain.LicenseRejectedException;
import com.pharmacore.subscription.domain.LicenseState;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Hub side of the license: the cloud's public keys pinned at pairing, the license in force and
 * the clock watermark, all in {@code node_setting} (local to the hub, never synchronized).
 * The hub enforces the license with no network: it trusts only what verifies with a pinned key.
 */
@Service
public class HubLicenseService {

    private static final Logger log = LoggerFactory.getLogger(HubLicenseService.class);
    static final String KEYS = "license.keys";
    static final String TOKEN = "license.token";
    static final String VERIFIED_AT = "license.verified_at";
    static final String WATERMARK = "license.watermark";
    static final String MISSING_SINCE = "license.missing_since";
    private static final Duration WATERMARK_PERSIST_EVERY = Duration.ofMinutes(1);

    /** The license in force and when this hub last received it from the cloud. */
    public record Held(LicenseClaims claims, Instant verifiedAt) {
    }

    record PinnedKey(String kid, String pem) {
    }

    private final NodeIdentity identity;
    private final HubLicenseSettings settings;
    private final ObjectMapper json;
    private final SubscriptionProperties properties;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicReference<Instant> watermark = new AtomicReference<>();
    private volatile Map<String, PublicKey> pinned;
    private volatile Held held;
    private volatile Instant missingSince;
    private volatile Instant persistedWatermark;

    public HubLicenseService(NodeIdentity identity, HubLicenseSettings settings, ObjectMapper json,
                             SubscriptionProperties properties, Clock clock) {
        this.identity = identity;
        this.settings = settings;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
    }

    /** Evaluated on every request from the local clock; the watermark only moves forward. */
    public LicenseState state() {
        load();
        Instant now = clock.instant();
        Held current = held;
        if (current == null && missingSince == null) {
            // A hub upgraded from a version without licenses gets one offline window to reach the cloud
            missingSince = now;
            settings.put(MISSING_SINCE, now.toString());
        }
        LicenseState state = LicenseEvaluator.evaluate(current == null ? null : current.claims(), now, watermark.get(),
                missingSince, properties.policy());
        if (state.reason() != LicenseState.Reason.CLOCK_TAMPERED) {
            observe(now);
        }
        return state;
    }

    public Optional<Held> held() {
        load();
        return Optional.ofNullable(held);
    }

    /** First-pairing trust: the keys come with the pairing reply, over HTTPS, before anything is pinned. */
    public void pin(List<PublicLicenseKey> keys) {
        lock.lock();
        try {
            load();
            if (!pinned.isEmpty()) {
                learn(keys);
                return;
            }
            var next = new LinkedHashMap<String, PublicKey>();
            for (PublicLicenseKey key : keys) {
                next.put(key.kid(), LicenseKeys.parsePublic(key.publicKeyPem()));
            }
            store(next, keys.stream().map(k -> new PinnedKey(k.kid(), k.publicKeyPem())).toList());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Keys listed by the cloud after pairing: a new key is pinned only when a pinned key endorses
     * it, so a server impersonating the cloud cannot introduce its own key. A hub paired before
     * licenses existed pins the first list it gets (same trust as a pairing).
     *
     * @return how many keys were added
     */
    public int learn(List<PublicLicenseKey> keys) {
        lock.lock();
        try {
            load();
            if (pinned.isEmpty()) {
                pin(keys);
                return pinned.size();
            }
            var next = new LinkedHashMap<>(pinned);
            var stored = new java.util.ArrayList<>(readPinned());
            boolean added;
            do {
                added = false;
                for (PublicLicenseKey key : keys) {
                    PublicKey endorser = key.endorsedBy() == null ? null : next.get(key.endorsedBy());
                    if (!next.containsKey(key.kid()) && endorser != null
                            && LicenseKeys.endorsementValid(endorser, key.kid(), key.publicKeyPem(), key.endorsement())) {
                        next.put(key.kid(), LicenseKeys.parsePublic(key.publicKeyPem()));
                        stored.add(new PinnedKey(key.kid(), key.publicKeyPem()));
                        added = true;
                    }
                }
            } while (added);
            int count = next.size() - pinned.size();
            if (count > 0) {
                store(next, stored);
                log.info("Licence : {} nouvelle(s) clé(s) de l'éditeur épinglée(s)", count);
            }
            return count;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Holds a license received from the cloud (pairing reply or synchronization).
     *
     * @throws LicenseRejectedException when it does not verify, is for another pharmacy or is
     *                                  older than the one held; the held license stays in force
     */
    public LicenseClaims accept(String token) {
        lock.lock();
        try {
            load();
            Held current = held;
            LicenseClaims claims = HubLicenseRules.accept(token, kid -> Optional.ofNullable(pinned.get(kid)),
                    identity.requireHubTenant().value(), current == null ? null : current.claims());
            Instant now = clock.instant();
            Instant mark = HubLicenseRules.afterLicense(watermark.get(), now, claims.issuedAt(),
                    properties.clockTolerance());
            watermark.set(mark);
            persistedWatermark = mark;
            settings.put(TOKEN, token);
            settings.put(VERIFIED_AT, now.toString());
            settings.put(WATERMARK, mark.toString());
            held = new Held(claims, now);
            return claims;
        } finally {
            lock.unlock();
        }
    }

    private void observe(Instant now) {
        Instant mark = watermark.accumulateAndGet(now, HubLicenseRules::observe);
        Instant persisted = persistedWatermark;
        if ((persisted == null || mark.isAfter(persisted.plus(WATERMARK_PERSIST_EVERY))) && lock.tryLock()) {
            try {
                settings.put(WATERMARK, mark.toString());
                persistedWatermark = mark;
            } finally {
                lock.unlock();
            }
        }
    }

    private void load() {
        if (pinned != null) {
            return;
        }
        lock.lock();
        try {
            if (pinned != null) {
                return;
            }
            var keys = new LinkedHashMap<String, PublicKey>();
            for (PinnedKey key : readPinned()) {
                keys.put(key.kid(), LicenseKeys.parsePublic(key.pem()));
            }
            settings.get(WATERMARK).map(Instant::parse).ifPresent(mark -> {
                watermark.set(mark);
                persistedWatermark = mark;
            });
            missingSince = settings.get(MISSING_SINCE).map(Instant::parse).orElse(null);
            held = settings.get(TOKEN).flatMap(token -> verifyStored(token, keys))
                    .map(claims -> new Held(claims, settings.get(VERIFIED_AT).map(Instant::parse).orElse(null)))
                    .orElse(null);
            pinned = keys;
        } finally {
            lock.unlock();
        }
    }

    /** A stored license that no longer verifies (edited in the database) counts as no license. */
    private Optional<LicenseClaims> verifyStored(String token, Map<String, PublicKey> keys) {
        try {
            LicenseClaims claims = LicenseCodec.verify(token, kid -> Optional.ofNullable(keys.get(kid)));
            return identity.hubTenant().filter(t -> t.value().equals(claims.tenantId())).map(t -> claims);
        } catch (LicenseRejectedException e) {
            log.warn("Licence enregistrée invalide ({}), ignorée", e.reason());
            return Optional.empty();
        }
    }

    private List<PinnedKey> readPinned() {
        return settings.get(KEYS)
                .map(text -> json.readValue(text, new TypeReference<List<PinnedKey>>() { }))
                .orElse(List.of());
    }

    private void store(Map<String, PublicKey> keys, List<PinnedKey> stored) {
        settings.put(KEYS, json.writeValueAsString(stored));
        pinned = Map.copyOf(keys);
    }
}
