package com.pharmacore.shared.media;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Image type read from the leading bytes (magic numbers), never from the name or the type the
 * client declares. Only raster formats every browser displays are recognized: SVG, which can
 * carry scripts, is not.
 */
public final class ImageTypes {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] RIFF = "RIFF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WEBP = "WEBP".getBytes(StandardCharsets.US_ASCII);

    private ImageTypes() {
    }

    /** {@code image/png}, {@code image/jpeg} or {@code image/webp}; empty for anything else. */
    public static Optional<String> detect(byte[] content) {
        if (startsWith(content, PNG)) {
            return Optional.of("image/png");
        }
        if (startsWith(content, JPEG)) {
            return Optional.of("image/jpeg");
        }
        if (content.length > 12 && startsWith(content, RIFF)
                && Arrays.equals(Arrays.copyOfRange(content, 8, 12), WEBP)) {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    /** Lower-case hexadecimal SHA-256, used as ETag and version of a stored image. */
    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        return content.length >= prefix.length && Arrays.equals(Arrays.copyOf(content, prefix.length), prefix);
    }
}
