package com.pharmacore.inventory.application;

import com.pharmacore.inventory.domain.LocationCode;
import com.pharmacore.inventory.domain.Placement;
import com.pharmacore.inventory.domain.StorageLocationKind;
import com.pharmacore.inventory.persistence.StockEntity;
import com.pharmacore.inventory.persistence.StorageLocationEntity;
import com.pharmacore.inventory.persistence.StorageLocationRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage locations of the pharmacy and the placement of each product in them. A location is
 * never deleted; it can be deactivated only once no product is stored there any more, or by
 * moving its products to another location in the same operation.
 */
@Service
public class StorageLocationService {

    private final StorageLocationRepository locations;
    private final StockLedger ledger;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    public StorageLocationService(StorageLocationRepository locations, StockLedger ledger, AuditTrail audit,
                                  JdbcClient jdbc, Clock clock) {
        this.locations = locations;
        this.ledger = ledger;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record LocationView(UUID id, String code, String name, StorageLocationKind kind, int sortOrder,
                               boolean active, int productCount, Instant updatedAt) {
    }

    @Transactional(readOnly = true)
    public List<LocationView> list(boolean includeInactive) {
        return jdbc.sql("""
                        SELECT l.id, l.code, l.name, l.kind, l.sort_order, l.active, l.updated_at,
                               coalesce(c.products, 0) AS products
                        FROM storage_location l
                        LEFT JOIN (
                            SELECT location_id, count(*) AS products FROM stock
                            WHERE location_id IS NOT NULL GROUP BY location_id
                        ) c ON c.location_id = l.id
                        WHERE l.active OR :inactive
                        ORDER BY l.active DESC, l.sort_order, l.code""")
                .param("inactive", includeInactive)
                .query((rs, rowNum) -> new LocationView(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        StorageLocationKind.valueOf(rs.getString("kind")),
                        rs.getInt("sort_order"),
                        rs.getBoolean("active"),
                        rs.getInt("products"),
                        rs.getTimestamp("updated_at").toInstant()))
                .list();
    }

    @Transactional(readOnly = true)
    public LocationView get(UUID id) {
        return view(load(id));
    }

    @Transactional
    public LocationView create(StorageLocationEntity.Details details) {
        return view(save(details));
    }

    @Transactional
    public LocationView update(UUID id, StorageLocationEntity.Details details) {
        var location = load(id);
        var previous = location.details();
        var clean = validate(details, id);
        location.update(clean, clock.instant());
        audit.record("STORAGE_LOCATION_UPDATED", "STORAGE_LOCATION", id, Map.of(
                "previousCode", previous.code(), "code", clean.code(),
                "previousName", previous.name(), "name", clean.name(), "kind", clean.kind().name()));
        return view(location);
    }

    /**
     * Deactivates a location. Products still stored there are moved to {@code reassignTo} (their
     * detail, which described a position in the old place, is cleared); without a target the
     * deactivation is refused while the location is not empty.
     */
    @Transactional
    public LocationView deactivate(UUID id, UUID reassignTo) {
        var location = locations.lockById(id).orElseThrow(() -> new NotFoundException("Storage location", id));
        if (!location.active()) {
            return view(location);
        }
        Instant now = clock.instant();
        int moved = 0;
        if (reassignTo != null) {
            if (reassignTo.equals(id)) {
                throw new BusinessRuleException("location-reassign-self",
                        "Choisissez un autre emplacement que celui que vous désactivez");
            }
            var target = requireActive(reassignTo);
            moved = jdbc.sql("""
                            UPDATE stock SET location_id = :target, location_detail = NULL, updated_at = :now,
                                             version = version + 1
                            WHERE location_id = :source""")
                    .param("target", target.id())
                    .param("source", id)
                    .param("now", Timestamp.from(now))
                    .update();
        } else {
            int remaining = productCount(id);
            if (remaining > 0) {
                throw new BusinessRuleException("location-not-empty",
                        "« " + location.label() + " » contient encore " + remaining + " produit(s) : "
                                + "réaffectez-les à un autre emplacement avant de le désactiver");
            }
        }
        location.setActive(false, now);
        audit.record("STORAGE_LOCATION_DEACTIVATED", "STORAGE_LOCATION", id, Map.of(
                "code", location.code(), "moved", Integer.toString(moved),
                "reassignTo", String.valueOf(reassignTo)));
        return view(location);
    }

    @Transactional
    public LocationView reactivate(UUID id) {
        var location = load(id);
        if (!location.active()) {
            location.setActive(true, clock.instant());
            audit.record("STORAGE_LOCATION_REACTIVATED", "STORAGE_LOCATION", id, Map.of("code", location.code()));
        }
        return view(location);
    }

    /** Puts one product at a location (or nowhere, with a {@code null} location). */
    @Transactional
    public void assign(UUID productId, UUID locationId, String detail) {
        Placement placement;
        try {
            placement = new Placement(locationId == null ? null : requireActive(locationId).id(), detail);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("invalid-location-detail",
                    "Le détail d'emplacement ne doit pas dépasser " + Placement.DETAIL_MAX_LENGTH + " caractères");
        }
        StockEntity stock = ledger.lock(List.of(productId)).get(productId);
        Placement previous = stock.placement();
        stock.place(placement, clock.instant());
        audit.record("STOCK_LOCATION_ASSIGNED", "STOCK", stock.id(), Map.of(
                "productId", productId.toString(),
                "previousLocationId", String.valueOf(previous.locationId()),
                "locationId", String.valueOf(placement.locationId()),
                "detail", String.valueOf(placement.detail())));
    }

    /**
     * Moves several products at once. A product already there keeps its detail; the others lose
     * the detail of their former location.
     *
     * @return number of products whose placement changed
     */
    @Transactional
    public int assignAll(List<UUID> productIds, UUID locationId) {
        UUID target = locationId == null ? null : requireActive(locationId).id();
        Instant now = clock.instant();
        int changed = 0;
        for (StockEntity stock : ledger.lock(productIds).values()) {
            Placement current = stock.placement();
            Placement moved = current.movedTo(target);
            if (!moved.equals(current)) {
                stock.place(moved, now);
                changed++;
            }
        }
        audit.record("STOCK_LOCATION_BULK_ASSIGNED", "STORAGE_LOCATION", target, Map.of(
                "locationId", String.valueOf(target),
                "products", Integer.toString(productIds.size()),
                "changed", Integer.toString(changed)));
        return changed;
    }

    /**
     * Placement given as free text by a data import ("Rayon B", "Frigo"): an existing location
     * with that code or name, otherwise a new one whose code is derived from the text.
     */
    @Transactional
    public void assignByLabel(UUID productId, String label) {
        String code = LocationCode.fromLabel(label).orElseThrow(() -> new BusinessRuleException(
                "invalid-location", "Emplacement illisible : « " + label + " »"));
        String name = label.strip();
        var location = locations.findByCode(code)
                .or(() -> locations.findFirstByNameIgnoreCase(name))
                .orElseGet(() -> save(new StorageLocationEntity.Details(code,
                        name.substring(0, Math.min(name.length(), 80)), StorageLocationKind.guess(name),
                        nextSortOrder())));
        assign(productId, location.id(), null);
    }

    private StorageLocationEntity save(StorageLocationEntity.Details details) {
        var clean = validate(details, null);
        var location = locations.save(new StorageLocationEntity(clean, clock.instant()));
        audit.record("STORAGE_LOCATION_CREATED", "STORAGE_LOCATION", location.id(), Map.of(
                "code", clean.code(), "name", clean.name(), "kind", clean.kind().name()));
        return location;
    }

    private StorageLocationEntity requireActive(UUID locationId) {
        var location = locations.shareById(locationId)
                .orElseThrow(() -> new NotFoundException("Storage location", locationId));
        if (!location.active()) {
            throw new BusinessRuleException("location-inactive",
                    "L'emplacement « " + location.label() + " » est désactivé : réactivez-le ou choisissez-en un autre");
        }
        return location;
    }

    private StorageLocationEntity.Details validate(StorageLocationEntity.Details details, UUID self) {
        String code;
        try {
            code = LocationCode.normalize(details.code());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("invalid-location-code", "Code d'emplacement invalide : lettres, chiffres, "
                    + "espace, point, tiret ou barre oblique, " + LocationCode.MAX_LENGTH + " caractères au plus");
        }
        Optional<StorageLocationEntity> clash = locations.findByCode(code).filter(other -> !other.id().equals(self));
        if (clash.isPresent()) {
            throw new BusinessRuleException("location-code-taken",
                    "Le code « " + code + " » est déjà celui de « " + clash.get().name() + " »"
                            + (clash.get().active() ? "" : " (désactivé)"));
        }
        return new StorageLocationEntity.Details(code, details.name().strip(), details.kind(), details.sortOrder());
    }

    private int nextSortOrder() {
        return jdbc.sql("SELECT coalesce(max(sort_order), 0) + 10 FROM storage_location").query(Integer.class).single();
    }

    private int productCount(UUID locationId) {
        return jdbc.sql("SELECT count(*) FROM stock WHERE location_id = :id").param("id", locationId)
                .query(Integer.class).single();
    }

    private StorageLocationEntity load(UUID id) {
        return locations.findById(id).orElseThrow(() -> new NotFoundException("Storage location", id));
    }

    private LocationView view(StorageLocationEntity location) {
        var details = location.details();
        return new LocationView(location.id(), details.code(), details.name(), details.kind(), details.sortOrder(),
                location.active(), productCount(location.id()), location.updatedAt());
    }
}
