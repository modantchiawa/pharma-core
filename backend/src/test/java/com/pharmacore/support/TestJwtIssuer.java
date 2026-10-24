package com.pharmacore.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.pharmacore.shared.security.SecurityProperties.IssuerKind;
import com.pharmacore.shared.security.TrustedIssuerRegistry;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Signs real RS256 tokens for two in-memory issuers (platform and pharmacies). */
public final class TestJwtIssuer implements TrustedIssuerRegistry {

    public static final String PLATFORM = "https://idp.test/realms/platform";
    public static final String PHARMACIES = "https://idp.test/realms/pharmacies";

    private final KeyPair keys;
    private final Map<String, TrustedIssuer> issuers;

    public TestJwtIssuer() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            this.keys = generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        this.issuers = Map.of(
                PLATFORM, new TrustedIssuer(IssuerKind.PLATFORM, decoder(PLATFORM)),
                PHARMACIES, new TrustedIssuer(IssuerKind.TENANT, decoder(PHARMACIES)));
    }

    @Override
    public Optional<TrustedIssuer> find(String issuer, String kid) {
        return Optional.ofNullable(issuers.get(issuer));
    }

    public String platformOperator(String subject) {
        return sign(new JWTClaimsSet.Builder()
                .issuer(PLATFORM)
                .subject(subject)
                .claim("name", "Ops " + subject)
                .claim("platform_roles", List.of("SUPER_ADMIN")));
    }

    public String pharmacist(UUID tenantId, String subject) {
        return sign(new JWTClaimsSet.Builder()
                .issuer(PHARMACIES)
                .subject(subject)
                .claim("tenant_id", tenantId.toString()));
    }

    private String sign(JWTClaimsSet.Builder claims) {
        Instant now = Instant.now();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(15))))
                .jwtID(UUID.randomUUID().toString())
                .build());
        try {
            jwt.sign(new RSASSASigner(keys.getPrivate()));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    private NimbusJwtDecoder decoder(String issuer) {
        var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }
}
