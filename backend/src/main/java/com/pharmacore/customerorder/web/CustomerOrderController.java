package com.pharmacore.customerorder.web;

import com.pharmacore.customerorder.application.CustomerOrderService;
import com.pharmacore.customerorder.application.CustomerOrderService.OrderView;
import com.pharmacore.customerorder.application.CustomerOrderService.SpecialOrderCommand;
import com.pharmacore.customerorder.application.CustomerOrderService.Summary;
import com.pharmacore.customerorder.domain.CustomerOrderKind;
import com.pharmacore.customerorder.domain.CustomerOrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Bons and special orders: what the counter owes its customers. */
@RestController
@RequestMapping("/api/v1/customer-orders")
class CustomerOrderController {

    private final CustomerOrderService orders;

    CustomerOrderController(CustomerOrderService orders) {
        this.orders = orders;
    }

    /** {@code pending}: only what is still owed (waiting or ready), ready first. */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('SALE_CREATE', 'SALE_VIEW', 'PROCUREMENT_VIEW')")
    List<OrderView> list(@RequestParam(required = false) CustomerOrderStatus status,
                         @RequestParam(required = false) CustomerOrderKind kind,
                         @RequestParam(required = false) UUID customerId,
                         @RequestParam(defaultValue = "false") boolean pending,
                         @RequestParam(defaultValue = "200") int limit) {
        return orders.list(status, kind, customerId, pending, limit);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyAuthority('SALE_CREATE', 'SALE_VIEW', 'PROCUREMENT_VIEW')")
    Summary summary() {
        return orders.summary();
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasAnyAuthority('SALE_CREATE', 'SALE_VIEW', 'PROCUREMENT_VIEW')")
    OrderView get(@PathVariable UUID orderId) {
        return orders.get(orderId);
    }

    /** A special order taken at the counter (a bon is created by the sale itself). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    OrderView create(@Valid @RequestBody SpecialOrderRequest request) {
        return orders.createSpecial(new SpecialOrderCommand(request.customerId(), request.holderName(),
                request.holderPhone(), request.productId(), request.quantity(), request.unitPriceCents(),
                request.notes()));
    }

    /** Hands over the units of a bon (already paid) now that the product is here. */
    @PostMapping("/{orderId}/delivery")
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    OrderView deliver(@PathVariable UUID orderId) {
        return orders.deliverVoucher(orderId);
    }

    @PostMapping("/{orderId}/cancellation")
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    OrderView cancel(@PathVariable UUID orderId, @Valid @RequestBody CancelRequest request) {
        return orders.cancel(orderId, request.reason());
    }

    record SpecialOrderRequest(UUID customerId,
                               @Size(max = 150) String holderName,
                               @Size(max = 30) String holderPhone,
                               @NotNull UUID productId,
                               @Min(1) @Max(9999) int quantity,
                               @PositiveOrZero Long unitPriceCents,
                               @Size(max = 255) String notes) {
    }

    record CancelRequest(@NotBlank @Size(max = 255) String reason) {
    }
}
