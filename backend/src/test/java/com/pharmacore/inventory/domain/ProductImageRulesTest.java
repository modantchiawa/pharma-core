package com.pharmacore.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pharmacore.inventory.domain.ProductImageRules.Verdict;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ProductImageRulesTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16};

    @Test
    void acceptsRasterImagesByTheirBytes() {
        assertThat(ProductImageRules.check(PNG)).isEqualTo(new Verdict.Accepted("image/png"));
        assertThat(ProductImageRules.check(JPEG)).isEqualTo(new Verdict.Accepted("image/jpeg"));
        byte[] webp = "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.US_ASCII);
        assertThat(ProductImageRules.check(webp)).isEqualTo(new Verdict.Accepted("image/webp"));
    }

    @Test
    void refusesSvgEmptyAndOversizedFiles() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);
        assertThat(ProductImageRules.check(svg)).isInstanceOfSatisfying(Verdict.Refused.class,
                refused -> assertThat(refused.code()).isEqualTo("product-image-format"));
        assertThat(ProductImageRules.check(new byte[0])).isInstanceOfSatisfying(Verdict.Refused.class,
                refused -> assertThat(refused.code()).isEqualTo("product-image-empty"));
        byte[] huge = Arrays.copyOf(JPEG, ProductImageRules.MAX_BYTES + 1);
        assertThat(ProductImageRules.check(huge)).isInstanceOfSatisfying(Verdict.Refused.class,
                refused -> assertThat(refused.message()).contains("256 Ko"));
        assertThat(ProductImageRules.check(Arrays.copyOf(JPEG, ProductImageRules.MAX_BYTES)))
                .isInstanceOf(Verdict.Accepted.class);
    }

    @Test
    void versionIsTheStartOfTheHashAndNullWithoutImage() {
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        assertThat(ProductImageRules.version(sha)).isEqualTo("0123456789abcdef");
        assertThat(ProductImageRules.version(null)).isNull();
    }
}
