package com.pharmacore.demo;

import com.nimbusds.jwt.JWTClaimsSet;
import com.pharmacore.shared.security.ActorResolver;
import com.pharmacore.shared.security.SigningKeyService;
import com.pharmacore.shared.web.BusinessRuleException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Password entry to the supervision console for demonstrations: issues a platform token signed
 * by this node, trusted through the internal issuer registry. Exists only when
 * {@code pharmacore.demo.platform-password} is set; a wrong password is throttled.
 */
@RestController
@ConditionalOnProperty(prefix = "pharmacore.demo", name = "platform-password")
class DemoPlatformAuthController {

    static final Duration TOKEN_TTL = Duration.ofHours(8);
    private static final int MAX_FAILURES = 10;

    private final DemoProperties demo;
    private final SigningKeyService signingKey;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private int failures;
    private Instant lockedUntil = Instant.EPOCH;

    DemoPlatformAuthController(DemoProperties demo, SigningKeyService signingKey, Clock clock) {
        this.demo = demo;
        this.signingKey = signingKey;
        this.clock = clock;
    }

    record LoginRequest(@NotBlank String password) {
    }

    record LoginResponse(String accessToken, Instant expiresAt, String displayName) {
    }

    @PostMapping("/api/auth/platform-login")
    LoginResponse login(@Valid @RequestBody LoginRequest request) {
        Instant now = clock.instant();
        lock.lock();
        try {
            if (now.isBefore(lockedUntil)) {
                throw new BusinessRuleException("too-many-attempts", "Trop de tentatives, réessayez dans quelques minutes");
            }
            if (!MessageDigest.isEqual(request.password().getBytes(), demo.platformPassword().getBytes())) {
                failures++;
                if (failures >= MAX_FAILURES) {
                    failures = 0;
                    lockedUntil = now.plus(Duration.ofMinutes(5));
                }
                throw new BusinessRuleException("bad-credentials", "Mot de passe de démonstration incorrect");
            }
            failures = 0;
        } finally {
            lock.unlock();
        }
        Instant expiresAt = now.plus(TOKEN_TTL);
        String token = signingKey.sign(new JWTClaimsSet.Builder()
                .issuer(signingKey.issuer())
                .subject("demo-operator")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .claim(ActorResolver.TYPE_CLAIM, ActorResolver.TYPE_PLATFORM)
                .claim(ActorResolver.PLATFORM_ROLES_CLAIM, List.of(ActorResolver.SUPER_ADMIN))
                .claim("name", "Opérateur démo")
                .claim("demo", Map.of("mode", "platform"))
                .build());
        return new LoginResponse(token, expiresAt, "Opérateur démo");
    }
}
