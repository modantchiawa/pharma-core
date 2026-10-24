package com.pharmacore.tenant.application;

import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import com.pharmacore.tenant.persistence.PharmacyConfigEntity;
import com.pharmacore.tenant.persistence.PharmacyConfigEntity.Branding;
import com.pharmacore.tenant.persistence.PharmacyConfigRepository;
import com.pharmacore.tenant.persistence.TenantAssetEntity;
import com.pharmacore.tenant.persistence.TenantAssetRepository;
import com.pharmacore.tenant.persistence.TenantRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** White-label configuration: identity, colors, receipt mentions and logo of a pharmacy. */
@Service
public class BrandingService {

    static final int MAX_LOGO_BYTES = 512 * 1024;

    private final PharmacyConfigRepository configs;
    private final TenantRepository tenants;
    private final TenantAssetRepository assets;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;
    private final Clock clock;

    public BrandingService(PharmacyConfigRepository configs, TenantAssetRepository assets, AuditTrail audit,
                           JdbcClient jdbc, PlatformTransactionManager transactionManager, Clock clock,
                           TenantRepository tenants) {
        this.tenants = tenants;
        this.configs = configs;
        this.assets = assets;
        this.audit = audit;
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BrandingView current() {
        return view(load());
    }

    @Transactional
    public BrandingView update(Branding branding) {
        var config = load();
        var before = config.branding();
        config.update(branding, clock.instant());
        audit.record("BRANDING_UPDATED", "PHARMACY_CONFIG", config.tenantId(), Map.of(
                "displayName", branding.displayName(),
                "legalIdentifierChanged", String.valueOf(!java.util.Objects.equals(before.legalIdentifier(), branding.legalIdentifier())),
                "ordonnancierPrefix", String.valueOf(branding.ordonnancierPrefix())));
        return view(config);
    }

    @Transactional
    public BrandingView replaceLogo(byte[] content) {
        String contentType = detectImageType(content);
        var asset = assets.save(new TenantAssetEntity("LOGO", contentType, content, sha256(content), clock.instant()));
        var config = load();
        config.useLogo(asset.id(), clock.instant());
        audit.record("LOGO_REPLACED", "PHARMACY_CONFIG", config.tenantId(), Map.of(
                "assetId", asset.id().toString(), "sha256", asset.sha256()));
        return view(config);
    }

    @Transactional(readOnly = true)
    public Optional<Logo> logo() {
        return Optional.ofNullable(load().logoAssetId())
                .flatMap(assets::findById)
                .map(asset -> new Logo(asset.contentType(), asset.content(), asset.sha256()));
    }

    /** Unauthenticated access by slug (login screen, customer display). */
    public Optional<BrandingView> publicBranding(String slug) {
        return resolveActiveTenant(slug).flatMap(tenant -> asPublicReader(tenant, () -> configs.findCurrent().map(this::view)));
    }

    public Optional<Logo> publicLogo(String slug) {
        return resolveActiveTenant(slug).flatMap(tenant -> asPublicReader(tenant, this::logo));
    }

    private <T> Optional<T> asPublicReader(TenantId tenant, java.util.function.Supplier<Optional<T>> query) {
        var context = RequestContext.of(Actor.SystemProcess.forTenant("public-branding", tenant));
        return TenantContext.call(context, () -> readOnly.execute(status -> query.get()));
    }

    private Optional<TenantId> resolveActiveTenant(String slug) {
        return jdbc.sql("SELECT resolve_active_tenant(:slug)")
                .param("slug", slug)
                .query(UUID.class)
                .optional()
                .map(TenantId::new);
    }

    private PharmacyConfigEntity load() {
        return configs.findCurrent().orElseThrow(() -> new NotFoundException("Pharmacy configuration", "current"));
    }

    /** Magic-number sniffing: never trust the client-declared type. SVG is refused (script injection). */
    static String detectImageType(byte[] content) {
        if (content.length == 0 || content.length > MAX_LOGO_BYTES) {
            throw new BusinessRuleException("logo-size", "Logo must be between 1 byte and 512 KB");
        }
        if (startsWith(content, new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'})) {
            return "image/png";
        }
        if (startsWith(content, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return "image/jpeg";
        }
        if (content.length > 12 && startsWith(content, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && Arrays.equals(Arrays.copyOfRange(content, 8, 12), "WEBP".getBytes(StandardCharsets.US_ASCII))) {
            return "image/webp";
        }
        throw new BusinessRuleException("logo-format", "Logo must be a PNG, JPEG or WebP image");
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        return content.length >= prefix.length && Arrays.equals(Arrays.copyOf(content, prefix.length), prefix);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Logo(String contentType, byte[] content, String sha256) {
    }

    public record BrandingView(Branding branding, boolean hasLogo, long version, String currency) {
    }

    private BrandingView view(PharmacyConfigEntity config) {
        String currency = tenants.findById(config.tenantId()).map(t -> t.currency()).orElse("XOF");
        return new BrandingView(config.branding(), config.logoAssetId() != null, config.version(), currency);
    }
}
