package com.pharmacore.inventory.domain;

import com.pharmacore.shared.media.ImageTypes;

/**
 * What a product photo must be to be stored: a PNG, JPEG or WebP image (read from its bytes) of
 * at most {@value #MAX_BYTES} bytes. The terminal crops and reduces the photo to a 256 px square
 * before sending it, so a few tens of kilobytes are expected; the limit only stops abuse. The
 * version given to clients is the start of the content hash: an image URL carrying it never
 * changes content and can be cached for good, offline included.
 */
public final class ProductImageRules {

    public static final int MAX_BYTES = 256 * 1024;
    public static final int VERSION_LENGTH = 16;

    private ProductImageRules() {
    }

    /** Decision on an uploaded image. */
    public sealed interface Verdict {

        record Accepted(String contentType) implements Verdict {
        }

        record Refused(String code, String message) implements Verdict {
        }
    }

    public static Verdict check(byte[] content) {
        if (content == null || content.length == 0) {
            return new Verdict.Refused("product-image-empty", "Image vide : choisissez une photo du produit");
        }
        if (content.length > MAX_BYTES) {
            return new Verdict.Refused("product-image-size",
                    "Image trop lourde (" + content.length / 1024 + " Ko) : 256 Ko au plus");
        }
        return ImageTypes.detect(content)
                .<Verdict>map(Verdict.Accepted::new)
                .orElseGet(() -> new Verdict.Refused("product-image-format",
                        "Format non pris en charge : envoyez une image PNG, JPEG ou WebP"));
    }

    /** Short version of an image derived from its SHA-256, {@code null} without image. */
    public static String version(String sha256) {
        return sha256 == null ? null : sha256.substring(0, Math.min(VERSION_LENGTH, sha256.length()));
    }
}
