package com.pharmacore.inventory.application;

import com.pharmacore.inventory.domain.ProductImageRules;
import com.pharmacore.inventory.domain.ProductImageRules.Verdict;
import com.pharmacore.inventory.persistence.ProductImageEntity;
import com.pharmacore.inventory.persistence.ProductImageRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.media.ImageTypes;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Photos of the pharmacy's products, shown as thumbnails wherever a product is picked. */
@Service
public class ProductImageService {

    private final ProductImageRepository images;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    public ProductImageService(ProductImageRepository images, AuditTrail audit, JdbcClient jdbc, Clock clock) {
        this.images = images;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public ImageView replace(UUID productId, byte[] content) {
        requireProduct(productId);
        String contentType = switch (ProductImageRules.check(content)) {
            case Verdict.Accepted accepted -> accepted.contentType();
            case Verdict.Refused refused -> throw new BusinessRuleException(refused.code(), refused.message());
        };
        String sha256 = ImageTypes.sha256(content);
        Instant now = clock.instant();
        var image = images.findByProductId(productId).orElseGet(() -> new ProductImageEntity(productId));
        String previous = image.sha256();
        image.replace(content, contentType, sha256, TenantContext.requireTenantUser().userId(), now);
        images.save(image);
        audit.record("PRODUCT_IMAGE_UPDATED", "PRODUCT", productId, Map.of(
                "sha256", sha256,
                "previousSha256", String.valueOf(previous),
                "contentType", contentType,
                "sizeBytes", Integer.toString(content.length)));
        return view(image);
    }

    @Transactional
    public ImageView remove(UUID productId) {
        var image = images.findByProductId(productId)
                .filter(ProductImageEntity::present)
                .orElseThrow(() -> new NotFoundException("Product image", productId));
        String previous = image.sha256();
        image.clear(TenantContext.requireTenantUser().userId(), clock.instant());
        audit.record("PRODUCT_IMAGE_REMOVED", "PRODUCT", productId, Map.of("previousSha256", previous));
        return view(image);
    }

    @Transactional(readOnly = true)
    public Optional<StoredImage> image(UUID productId) {
        return images.findByProductId(productId)
                .filter(ProductImageEntity::present)
                .map(image -> new StoredImage(image.contentType(), image.content(), image.sha256(),
                        ProductImageRules.version(image.sha256())));
    }

    private void requireProduct(UUID productId) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM product WHERE id = :id)")
                .param("id", productId)
                .query(Boolean.class)
                .single();
        if (!exists) {
            throw new NotFoundException("Product", productId);
        }
    }

    private static ImageView view(ProductImageEntity image) {
        return new ImageView(image.productId(), ProductImageRules.version(image.sha256()), image.contentType(),
                image.sizeBytes(), image.updatedAt());
    }

    /** @param imageVersion {@code null} once the photo is removed */
    public record ImageView(UUID productId, String imageVersion, String contentType, Integer sizeBytes,
                            Instant updatedAt) {
    }

    public record StoredImage(String contentType, byte[] content, String sha256, String version) {
    }
}
