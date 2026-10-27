package com.pharmacore.inventory.web;

import com.pharmacore.inventory.application.StorageLocationService;
import com.pharmacore.inventory.application.StorageLocationService.LocationView;
import com.pharmacore.inventory.domain.LocationCode;
import com.pharmacore.inventory.domain.Placement;
import com.pharmacore.inventory.domain.StorageLocationKind;
import com.pharmacore.inventory.persistence.StorageLocationEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Storage locations of the pharmacy and the placement of products in them. */
@RestController
@RequestMapping("/api/v1/inventory")
class StorageLocationController {

    /** Locations are read at the counter, at receipt and during counts, not only by stock managers. */
    private static final String READ = "hasAnyAuthority('STOCK_VIEW', 'SALE_CREATE', 'STOCK_COUNT', "
            + "'STOCK_COUNT_VALIDATE', 'STOCK_LOCATION_MANAGE')";

    private final StorageLocationService locations;

    StorageLocationController(StorageLocationService locations) {
        this.locations = locations;
    }

    @GetMapping("/locations")
    @PreAuthorize(READ)
    List<LocationView> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return locations.list(includeInactive);
    }

    @GetMapping("/locations/{locationId}")
    @PreAuthorize(READ)
    LocationView get(@PathVariable UUID locationId) {
        return locations.get(locationId);
    }

    @PostMapping("/locations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('STOCK_LOCATION_MANAGE')")
    LocationView create(@Valid @RequestBody LocationRequest request) {
        return locations.create(request.toDetails());
    }

    @PutMapping("/locations/{locationId}")
    @PreAuthorize("hasAuthority('STOCK_LOCATION_MANAGE')")
    LocationView update(@PathVariable UUID locationId, @Valid @RequestBody LocationRequest request) {
        return locations.update(locationId, request.toDetails());
    }

    /** Deactivation is refused while products are stored there, unless they move to {@code reassignTo}. */
    @PutMapping("/locations/{locationId}/activation")
    @PreAuthorize("hasAuthority('STOCK_LOCATION_MANAGE')")
    LocationView activation(@PathVariable UUID locationId, @Valid @RequestBody ActivationRequest request) {
        return request.active() ? locations.reactivate(locationId)
                : locations.deactivate(locationId, request.reassignTo());
    }

    @PutMapping("/stocks/{productId}/location")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('STOCK_LOCATION_MANAGE')")
    void assign(@PathVariable UUID productId, @Valid @RequestBody PlacementRequest request) {
        locations.assign(productId, request.locationId(), request.detail());
    }

    @PostMapping("/stocks/location-assignments")
    @PreAuthorize("hasAuthority('STOCK_LOCATION_MANAGE')")
    Map<String, Integer> assignAll(@Valid @RequestBody BulkPlacementRequest request) {
        return Map.of("changed", locations.assignAll(request.productIds(), request.locationId()));
    }

    record LocationRequest(@NotBlank @Size(max = LocationCode.MAX_LENGTH) String code,
                           @NotBlank @Size(max = 80) String name,
                           @NotNull StorageLocationKind kind,
                           @Min(0) @Max(100_000) int sortOrder) {

        StorageLocationEntity.Details toDetails() {
            return new StorageLocationEntity.Details(code, name, kind, sortOrder);
        }
    }

    record ActivationRequest(boolean active, UUID reassignTo) {
    }

    record PlacementRequest(UUID locationId, @Size(max = Placement.DETAIL_MAX_LENGTH) String detail) {
    }

    record BulkPlacementRequest(@NotEmpty @Size(max = 500) List<@NotNull UUID> productIds, UUID locationId) {
    }
}
