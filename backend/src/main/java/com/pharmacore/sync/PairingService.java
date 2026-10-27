package com.pharmacore.sync;

import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.security.InternalIssuerRegistry;
import com.pharmacore.shared.security.SigningKeyService;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import com.pharmacore.subscription.application.LicenseIssuer;
import com.pharmacore.subscription.application.LicenseKeyRing;
import com.pharmacore.subscription.application.LicenseKeyRing.PublicLicenseKey;
import com.pharmacore.tenant.persistence.TenantRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Cloud side of pairing: one-time codes issued by the platform, redeemed once by a hub. */
@Service
public class PairingService {

    static final Duration CODE_VALIDITY = Duration.ofHours(24);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final TenantRepository tenants;
    private final AuditTrail audit;
    private final InternalIssuerRegistry issuers;
    private final LicenseIssuer licenses;
    private final LicenseKeyRing licenseKeys;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PairingService(JdbcClient jdbc, TenantRepository tenants, AuditTrail audit, InternalIssuerRegistry issuers,
                          LicenseIssuer licenses, LicenseKeyRing licenseKeys,
                          PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.licenses = licenses;
        this.licenseKeys = licenseKeys;
        this.tenants = tenants;
        this.audit = audit;
        this.issuers = issuers;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public record PairingRequest(String code, String nodeId, String appVersion, UUID keyId, String kid,
                                 String publicKeyPem) {
    }

    /**
     * @param licenseKeys public keys of the license authority, pinned by the hub (first-pairing
     *                    trust, over HTTPS): from then on it verifies its license offline
     * @param license     the pharmacy's current license
     */
    public record PairingResult(UUID tenantId, String slug, String displayName, String cloudUrl,
                                List<PublicLicenseKey> licenseKeys, String license) {
    }

    /** Platform operator creates a code to type on the hub's first-run screen (shown once). */
    @Transactional
    public String createCode(UUID tenantId) {
        tenants.findById(tenantId).orElseThrow(() -> new NotFoundException("Tenant", tenantId));
        var builder = new StringBuilder(9);
        for (int i = 0; i < 8; i++) {
            if (i == 4) {
                builder.append('-');
            }
            builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        String code = builder.toString();
        jdbc.sql("""
                        INSERT INTO hub_pairing_code (id, tenant_id, code_hash, created_by, created_at, expires_at)
                        VALUES (:id, :tenant, :hash, :by, :now, :expires)""")
                .param("id", UUID.randomUUID())
                .param("tenant", tenantId)
                .param("hash", hash(code))
                .param("by", TenantContext.actor().auditId())
                .param("now", Timestamp.from(clock.instant()))
                .param("expires", Timestamp.from(clock.instant().plus(CODE_VALIDITY)))
                .update();
        audit.record("HUB_PAIRING_CODE_CREATED", "TENANT", tenantId, Map.of());
        return code;
    }

    /** Unauthenticated redemption by the hub; runs as a platform process. */
    public PairingResult redeem(PairingRequest request, String cloudUrl) {
        SigningKeyService.parsePublicKey(request.publicKeyPem());
        var context = RequestContext.of(Actor.SystemProcess.crossTenant("hub-pairing"));
        var result = TenantContext.call(context, () -> tx.execute(status -> {
            Instant now = clock.instant();
            UUID tenantId = jdbc.sql("""
                            UPDATE hub_pairing_code SET used_at = :now, used_by_node = :node
                            WHERE code_hash = :hash AND used_at IS NULL AND expires_at > :now
                            RETURNING tenant_id""")
                    .param("now", Timestamp.from(now))
                    .param("node", request.nodeId())
                    .param("hash", hash(request.code()))
                    .query(UUID.class)
                    .optional()
                    .orElseThrow(() -> new BusinessRuleException("invalid-pairing-code", "Code d'appairage invalide ou expiré"));
            var tenant = tenants.findById(tenantId).orElseThrow(() -> new NotFoundException("Tenant", tenantId));
            jdbc.sql("""
                            INSERT INTO signing_key (id, kid, issuer, tenant_id, node_id, algorithm, public_key_pem, created_at)
                            VALUES (:id, :kid, :issuer, :tenant, :node, 'RS256', :pem, :now)
                            ON CONFLICT (kid) DO UPDATE SET issuer = EXCLUDED.issuer, tenant_id = EXCLUDED.tenant_id,
                                node_id = EXCLUDED.node_id, public_key_pem = EXCLUDED.public_key_pem, revoked_at = NULL""")
                    .param("id", request.keyId())
                    .param("kid", request.kid())
                    .param("issuer", SigningKeyService.issuerFor(new TenantId(tenantId)))
                    .param("tenant", tenantId)
                    .param("node", request.nodeId())
                    .param("pem", request.publicKeyPem())
                    .param("now", Timestamp.from(now))
                    .update();
            jdbc.sql("""
                            INSERT INTO hub_node (tenant_id, node_id, app_version, paired_at, last_seen_at)
                            VALUES (:tenant, :node, :version, :now, :now)
                            ON CONFLICT (tenant_id, node_id) DO UPDATE SET app_version = EXCLUDED.app_version, last_seen_at = EXCLUDED.last_seen_at""")
                    .param("tenant", tenantId)
                    .param("node", request.nodeId())
                    .param("version", request.appVersion())
                    .param("now", Timestamp.from(now))
                    .update();
            tenant.attachHub(now);
            audit.record("HUB_PAIRED", "TENANT", tenantId, Map.of("node", request.nodeId(), "kid", request.kid()));
            // The connection is bound to the platform context: filter explicitly rather than through
            // the tenant-scoped repository.
            String displayName = jdbc.sql("SELECT display_name FROM pharmacy_config WHERE tenant_id = :tenant")
                    .param("tenant", tenantId).query(String.class).optional().orElse(tenant.legalName());
            return new PairingResult(tenantId, tenant.slug(), displayName, cloudUrl, null, null);
        }));
        issuers.invalidate();
        String license = licenses.issue(result.tenantId()).token();
        return new PairingResult(result.tenantId(), result.slug(), result.displayName(), result.cloudUrl(),
                licenseKeys.publicKeys(), license);
    }

    static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(code.trim().toUpperCase().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
