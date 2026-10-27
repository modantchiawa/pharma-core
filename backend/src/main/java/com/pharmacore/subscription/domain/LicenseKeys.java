package com.pharmacore.subscription.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Ed25519 keys of the license authority: short signatures (64 bytes), no parameters to get
 * wrong, verification in the JDK on every node without extra dependency.
 */
public final class LicenseKeys {

    public static final String ALGORITHM = "Ed25519";
    private static final String ENDORSEMENT_CONTEXT = "pharmacore-license-key\n";

    private LicenseKeys() {
    }

    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 unavailable", e);
        }
    }

    /** Key id: first 16 bytes of the SHA-256 of the encoded public key, in hex. */
    public static String kid(PublicKey key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()), 0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String publicPem(PublicKey key) {
        return pem("PUBLIC KEY", key.getEncoded());
    }

    public static String privatePem(PrivateKey key) {
        return pem("PRIVATE KEY", key.getEncoded());
    }

    public static PublicKey parsePublic(String pem) {
        try {
            return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(der(pem)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Ed25519 public key", e);
        }
    }

    public static PrivateKey parsePrivate(String pem) {
        try {
            return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(der(pem)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Ed25519 private key", e);
        }
    }

    public static byte[] sign(PrivateKey key, byte[] message) {
        try {
            var signature = Signature.getInstance(ALGORITHM);
            signature.initSign(key);
            signature.update(message);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign", e);
        }
    }

    public static boolean verify(PublicKey key, byte[] message, byte[] signatureBytes) {
        try {
            var signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(key);
            signature.update(message);
            return signature.verify(signatureBytes);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Rotation: a new key is endorsed by the key it replaces, so a hub that pinned the old key
     * accepts the new one without trusting the network it learns it from.
     */
    public static String endorse(PrivateKey endorser, String kid, String publicKeyPem) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sign(endorser, endorsementMessage(kid, publicKeyPem)));
    }

    public static boolean endorsementValid(PublicKey endorser, String kid, String publicKeyPem, String endorsement) {
        try {
            return verify(endorser, endorsementMessage(kid, publicKeyPem), Base64.getUrlDecoder().decode(endorsement));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] endorsementMessage(String kid, String publicKeyPem) {
        return (ENDORSEMENT_CONTEXT + kid + "\n" + publicKeyPem.strip()).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] der(String pem) {
        return Base64.getDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }
}
