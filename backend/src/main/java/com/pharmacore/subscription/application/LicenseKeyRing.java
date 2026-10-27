package com.pharmacore.subscription.application;

import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.runtime.NodeProperties;
import com.pharmacore.subscription.domain.LicenseClaims;
import com.pharmacore.subscription.domain.LicenseCodec;
import com.pharmacore.subscription.domain.LicenseKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The cloud's license authority: Ed25519 keys whose private halves live in files under the keys
 * directory ({@code license-<kid>.p8}) and whose public halves are listed in {@code license_key}.
 * The newest key with a private file signs; older keys stay listed so licenses they signed keep
 * verifying, and each replacement key is endorsed by the one before it.
 */
@Service
public class LicenseKeyRing {

    private static final Logger log = LoggerFactory.getLogger(LicenseKeyRing.class);

    /** Public key as published to hubs, with its endorsement by the previous key. */
    public record PublicLicenseKey(String kid, String algorithm, String publicKeyPem, String endorsedBy,
                                   String endorsement) {
    }

    private record SigningKey(String kid, PrivateKey key) {
    }

    private final NodeProperties node;
    private final JdbcClient jdbc;
    private final AuditTrail audit;
    private final TransactionTemplate tx;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile SigningKey active;

    public LicenseKeyRing(NodeProperties node, JdbcClient jdbc, AuditTrail audit,
                          PlatformTransactionManager transactionManager) {
        this.node = node;
        this.jdbc = jdbc;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public String sign(LicenseClaims claims) {
        SigningKey key = active();
        return LicenseCodec.sign(claims, key.kid(), key.key());
    }

    public String activeKid() {
        return active().kid();
    }

    /** Every key a hub may need to verify a license, oldest first. */
    public List<PublicLicenseKey> publicKeys() {
        active();
        return asPlatform(() -> tx.execute(status -> jdbc.sql("""
                        SELECT kid, algorithm, public_key_pem, endorsed_by, endorsement FROM license_key
                        WHERE retired_at IS NULL ORDER BY created_at, kid""")
                .query((rs, rowNum) -> new PublicLicenseKey(rs.getString("kid"), rs.getString("algorithm"),
                        rs.getString("public_key_pem"), rs.getString("endorsed_by"), rs.getString("endorsement")))
                .list()));
    }

    /**
     * Replaces the signing key by a new one endorsed by it; the previous one keeps verifying.
     * Called by a platform operator, who is the author of the audit event.
     */
    public PublicLicenseKey rotate() {
        lock.lock();
        try {
            SigningKey previous = active();
            SigningKey next = create(previous);
            active = next;
            tx.executeWithoutResult(status -> audit.record("LICENSE_KEY_ROTATED", "LICENSE_KEY", next.kid(),
                    Map.of("previous", previous.kid())));
            log.info("Clé de licence remplacée : {} succède à {}", next.kid(), previous.kid());
            return publicKeys().stream().filter(k -> k.kid().equals(next.kid())).findFirst().orElseThrow();
        } finally {
            lock.unlock();
        }
    }

    private SigningKey active() {
        SigningKey current = active;
        if (current != null) {
            return current;
        }
        lock.lock();
        try {
            if (active == null) {
                active = load();
            }
            return active;
        } finally {
            lock.unlock();
        }
    }

    private SigningKey load() {
        List<String> kids = asPlatform(() -> tx.execute(status -> jdbc.sql(
                        "SELECT kid FROM license_key WHERE retired_at IS NULL ORDER BY created_at DESC, kid")
                .query(String.class).list()));
        for (String kid : kids) {
            Path file = privateFile(kid);
            if (Files.exists(file)) {
                try {
                    return new SigningKey(kid, LicenseKeys.parsePrivate(Files.readString(file)));
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot read license key " + file, e);
                }
            }
        }
        if (!kids.isEmpty()) {
            log.warn("Aucune clé privée de licence trouvée dans {} : nouvelle clé sans endossement, "
                    + "les hubs déjà appairés devront être réappairés", node.keysDir());
        }
        return create(null);
    }

    private SigningKey create(SigningKey endorser) {
        var pair = LicenseKeys.generate();
        String kid = LicenseKeys.kid(pair.getPublic());
        String pem = LicenseKeys.publicPem(pair.getPublic());
        try {
            Files.createDirectories(node.keysDir());
            Path file = privateFile(kid);
            Files.writeString(file, LicenseKeys.privatePem(pair.getPrivate()));
            restrictPermissions(file);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot store the license key", e);
        }
        asPlatform(() -> tx.execute(status -> jdbc.sql("""
                        INSERT INTO license_key (kid, algorithm, public_key_pem, endorsed_by, endorsement, created_at)
                        VALUES (:kid, :algorithm, :pem, :endorsedBy, :endorsement, clock_timestamp())""")
                .param("kid", kid)
                .param("algorithm", LicenseKeys.ALGORITHM)
                .param("pem", pem)
                .param("endorsedBy", endorser == null ? null : endorser.kid())
                .param("endorsement", endorser == null ? null : LicenseKeys.endorse(endorser.key(), kid, pem))
                .update()));
        return new SigningKey(kid, pair.getPrivate());
    }

    private Path privateFile(String kid) {
        return node.keysDir().resolve("license-" + kid + ".p8");
    }

    private static void restrictPermissions(Path file) {
        try {
            var view = Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class);
            if (view != null) {
                view.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException e) {
            log.warn("Droits du fichier {} non restreints : {}", file, e.getMessage());
        }
    }

    private static <T> T asPlatform(java.util.function.Supplier<T> action) {
        return TenantContext.call(RequestContext.of(Actor.SystemProcess.crossTenant("license-keys")), action);
    }
}
