package com.pharmacore.shared.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Trusts tokens issued by PharmaCore nodes (this one, the cloud, or any paired hub) whose public
 * key is registered in {@code signing_key}. Keys are looked up by {@code kid} and must belong to
 * the issuer claimed by the token.
 */
public class InternalIssuerRegistry implements TrustedIssuerRegistry {

    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;
    private final Cache<String, Optional<TrustedIssuer>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(10_000)
            .build();

    public InternalIssuerRegistry(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public Optional<TrustedIssuer> find(String issuer, String kid) {
        if (issuer == null || kid == null || !issuer.startsWith(SigningKeyService.ISSUER_PREFIX)) {
            return Optional.empty();
        }
        return cache.get(issuer + "|" + kid, key -> load(issuer, kid));
    }

    /** A freshly paired hub must be trusted at once. */
    public void invalidate() {
        cache.invalidateAll();
    }

    private Optional<TrustedIssuer> load(String issuer, String kid) {
        var context = RequestContext.of(Actor.SystemProcess.crossTenant("issuer-registry"));
        return TenantContext.call(context, () -> readOnly.execute(status -> jdbc.sql("""
                        SELECT public_key_pem FROM signing_key
                        WHERE kid = :kid AND issuer = :issuer AND revoked_at IS NULL""")
                .param("kid", kid)
                .param("issuer", issuer)
                .query(String.class)
                .optional()
                .map(pem -> {
                    var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) SigningKeyService.parsePublicKey(pem)).build();
                    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
                    return new TrustedIssuer(SecurityProperties.IssuerKind.INTERNAL, decoder);
                })));
    }
}
