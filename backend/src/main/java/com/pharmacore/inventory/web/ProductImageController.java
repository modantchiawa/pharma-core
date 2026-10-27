package com.pharmacore.inventory.web;

import com.pharmacore.inventory.application.ProductImageService;
import com.pharmacore.inventory.application.ProductImageService.ImageView;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Product photos. Changing one needs the right to set the product's price and thresholds; every
 * member of the pharmacy sees them. The terminal builds the URL from the product views
 * ({@code imageVersion}). The browser keeps the photo but asks the server before each use (ETag,
 * 304 without the bytes): a locked session gets 423 instead, never the cached photo.
 */
@RestController
@RequestMapping("/api/v1/inventory/products/{productId}/image")
class ProductImageController {

    private static final CacheControl REVALIDATE = CacheControl.noCache().cachePrivate();

    private final ProductImageService images;

    ProductImageController(ProductImageService images) {
        this.images = images;
    }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('STOCK_PRICE_MANAGE')")
    ImageView upload(@PathVariable UUID productId, @RequestParam("file") MultipartFile file) throws IOException {
        return images.replace(productId, file.getBytes());
    }

    @DeleteMapping
    @PreAuthorize("hasAuthority('STOCK_PRICE_MANAGE')")
    ImageView remove(@PathVariable UUID productId) {
        return images.remove(productId);
    }

    @GetMapping
    ResponseEntity<byte[]> image(@PathVariable UUID productId,
                                 @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch) {
        var image = images.image(productId);
        if (image.isEmpty()) {
            return ResponseEntity.notFound().cacheControl(REVALIDATE).build();
        }
        String etag = "\"" + image.get().sha256() + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(304).eTag(etag).cacheControl(REVALIDATE).build();
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(REVALIDATE)
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(image.get().contentType()))
                .body(image.get().content());
    }
}
