package com.pharmacore.shared.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.runtime.NodeIdentity;
import com.pharmacore.shared.runtime.NodeProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The node's RSA signing key. The private key lives in a file under {@code keysDir}; only the
 * public key is stored in {@code signing_key} (and replicated to the cloud for hubs), so any
 * node can verify tokens issued by any other without sharing secrets.
 */
@Service
public class SigningKeyService {

    public static final String ISSUER_PREFIX = "pharmacore:";
    public static final String CLOUD_ISSUER = ISSUER_PREFIX + NodeIdentity.CLOUD_NODE_ID;

    private final NodeProperties properties;
    private final NodeIdentity node;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private volatile KeyPair keyPair;
    private volatile String kid;

    public SigningKeyService(NodeProperties properties, NodeIdentity node, JdbcClient jdbc,
                             PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.node = node;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Issuer of the tokens signed by this node: the pharmacy for a hub, {@code pharmacore:cloud} otherwise. */
    public String issuer() {
        return node.hubTenant().map(SigningKeyService::issuerFor).orElse(CLOUD_ISSUER);
    }

    public static String issuerFor(TenantId tenant) {
        return ISSUER_PREFIX + tenant;
    }

    public String kid() {
        ensureLoaded();
        return kid;
    }

    public RSAPublicKey publicKey() {
        ensureLoaded();
        return (RSAPublicKey) keyPair.getPublic();
    }

    public String publicKeyPem() {
        return pem("PUBLIC KEY", publicKey().getEncoded());
    }

    /** Deterministic row id of this node's key, so a pairing insert on the cloud and the hub's own row are the same row. */
    public UUID keyRowId() {
        return UUID.nameUUIDFromBytes(("signing-key:" + kid()).getBytes(StandardCharsets.UTF_8));
    }

    public String sign(JWTClaimsSet claims) {
        ensureLoaded();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims);
        try {
            jwt.sign(new RSASSASigner(keyPair.getPrivate()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign token", e);
        }
        return jwt.serialize();
    }

    /**
     * Records (or refreshes) this node's public key in {@code signing_key} for the given tenant.
     * The row belongs to the node, not to a pharmacy: it exists before pairing (no tenant) and is
     * re-bound when the node is paired, so it is written as a cross-tenant system process.
     */
    public void publish(TenantId tenant) {
        ensureLoaded();
        var context = RequestContext.of(Actor.SystemProcess.crossTenant("signing-key"));
        TenantContext.run(context, () -> tx.executeWithoutResult(status -> jdbc.sql("""
                        INSERT INTO signing_key (id, kid, issuer, tenant_id, node_id, algorithm, public_key_pem, created_at)
                        VALUES (:id, :kid, :issuer, :tenant, :node, 'RS256', :pem, now())
                        ON CONFLICT (kid) DO UPDATE SET issuer = EXCLUDED.issuer, tenant_id = EXCLUDED.tenant_id,
                            public_key_pem = EXCLUDED.public_key_pem, revoked_at = NULL""")
                .param("id", keyRowId())
                .param("kid", kid)
                .param("issuer", tenant == null ? CLOUD_ISSUER : issuerFor(tenant))
                .param("tenant", tenant == null ? null : tenant.value())
                .param("node", node.nodeId())
                .param("pem", publicKeyPem())
                .update()));
    }

    public static PublicKey parsePublicKey(String pem) {
        String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid RSA public key", e);
        }
    }

    private synchronized void ensureLoaded() {
        if (keyPair != null) {
            return;
        }
        try {
            Path dir = properties.keysDir();
            Files.createDirectories(dir);
            Path privateFile = dir.resolve("signing-" + node.nodeId() + ".p8");
            Path publicFile = dir.resolve("signing-" + node.nodeId() + ".pub");
            KeyPair pair;
            if (Files.exists(privateFile) && Files.exists(publicFile)) {
                pair = new KeyPair(parsePublicKey(Files.readString(publicFile)),
                        parsePrivateKey(Files.readString(privateFile)));
            } else {
                var generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(3072);
                pair = generator.generateKeyPair();
                Files.writeString(privateFile, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
                Files.writeString(publicFile, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
                restrictPermissions(privateFile);
            }
            this.kid = fingerprint(pair.getPublic());
            this.keyPair = pair;
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot initialize the node signing key", e);
        }
    }

    private static PrivateKey parsePrivateKey(String pem) {
        String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Corrupted private key file", e);
        }
    }

    private static void restrictPermissions(Path file) {
        try {
            var view = Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class);
            if (view != null) {
                view.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException ignored) {
            // Windows: the installer sets an ACL on the data directory instead.
        }
    }

    private static String fingerprint(PublicKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }
}
