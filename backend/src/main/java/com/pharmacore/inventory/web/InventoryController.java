package com.pharmacore.inventory.web;

import com.pharmacore.inventory.application.StockService;
import com.pharmacore.inventory.application.StockService.ExpiryAlert;
import com.pharmacore.inventory.application.StockService.LocationFilter;
import com.pharmacore.inventory.application.StockService.ReorderSuggestion;
import com.pharmacore.inventory.application.StockService.StockOverview;
import com.pharmacore.inventory.domain.InsufficientStockException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@RequestMapping("/api/v1/inventory")
class InventoryController {

    private final StockService stock;

    InventoryController(StockService stock) {
        this.stock = stock;
    }

    @GetMapping("/stocks")
    @PreAuthorize("hasAuthority('STOCK_VIEW')")
    List<StockOverview> overview(@RequestParam(required = false) String q,
                                 @RequestParam(defaultValue = "false") boolean belowThreshold,
                                 @RequestParam(required = false) UUID locationId,
                                 @RequestParam(defaultValue = "false") boolean unassigned,
                                 @RequestParam(defaultValue = "100") int limit,
                                 @RequestParam(defaultValue = "0") int offset) {
        LocationFilter location = locationId != null ? new LocationFilter.At(locationId)
                : unassigned ? new LocationFilter.Unassigned() : new LocationFilter.Any();
        return stock.overview(q, belowThreshold, location, limit, offset);
    }

    @PostMapping("/lots")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('STOCK_RECEIVE')")
    Map<String, UUID> receive(@Valid @RequestBody ReceiptRequest request) {
        return Map.of("lotId", stock.receiveLot(request.productId(), request.lotNumber(), request.expiryDate(),
                request.quantity(), request.unitCostCents()));
    }

    @PutMapping("/lots/{lotId}/quantity")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('STOCK_ADJUST')")
    void adjust(@PathVariable UUID lotId, @Valid @RequestBody AdjustmentRequest request) {
        stock.adjustLot(lotId, request.countedQuantity(), request.reason());
    }

    @PostMapping("/stocks/{productId}/shortfall-resolution")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('STOCK_ADJUST')")
    void resolveShortfall(@PathVariable UUID productId, @Valid @RequestBody ReasonRequest request) {
        stock.resolveShortfall(productId, request.reason());
    }

    @PutMapping("/stocks/{productId}/settings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('STOCK_PRICE_MANAGE')")
    void configure(@PathVariable UUID productId, @Valid @RequestBody SettingsRequest request) {
        stock.configure(productId, request.sellingPriceCents(), request.reorderThreshold(), request.reorderQuantity());
    }

    @GetMapping("/expiry-alerts")
    @PreAuthorize("hasAuthority('STOCK_VIEW')")
    List<ExpiryAlert> expiryAlerts(@RequestParam(defaultValue = "90") int withinDays) {
        return stock.expiryAlerts(withinDays);
    }

    @GetMapping("/reorder-suggestions")
    @PreAuthorize("hasAuthority('STOCK_VIEW')")
    List<ReorderSuggestion> reorderSuggestions() {
        return stock.reorderSuggestions();
    }

    record ReceiptRequest(@NotNull UUID productId,
                          @NotBlank @Size(max = 40) String lotNumber,
                          @NotNull LocalDate expiryDate,
                          @Positive int quantity,
                          @PositiveOrZero long unitCostCents) {
    }

    record AdjustmentRequest(@Min(0) int countedQuantity, @NotBlank @Size(max = 255) String reason) {
    }

    record ReasonRequest(@NotBlank @Size(max = 255) String reason) {
    }

    record SettingsRequest(@PositiveOrZero Long sellingPriceCents,
                           @Min(0) int reorderThreshold,
                           @Min(0) int reorderQuantity) {
    }
}

@RestControllerAdvice
class InventoryExceptionHandler {

    @ExceptionHandler(InsufficientStockException.class)
    ProblemDetail insufficientStock(InsufficientStockException e) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        problem.setType(URI.create("https://pharmacore.io/problems/insufficient-stock"));
        problem.setProperty("code", "insufficient-stock");
        problem.setProperty("productId", e.productId());
        problem.setProperty("requested", e.requested());
        problem.setProperty("available", e.available());
        return problem;
    }
}
