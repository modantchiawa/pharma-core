package com.pharmacore.procurement.web;

import com.pharmacore.procurement.application.ProcurementService;
import com.pharmacore.procurement.application.ProcurementService.OrderLineCommand;
import com.pharmacore.procurement.application.ProcurementService.OrderView;
import com.pharmacore.procurement.application.ProcurementService.ProposalSheet;
import com.pharmacore.procurement.application.ProcurementService.ReceiptCommand;
import com.pharmacore.procurement.application.ProcurementService.ReceiptLineCommand;
import com.pharmacore.procurement.application.ProcurementService.ReceiptView;
import com.pharmacore.procurement.application.ProcurementService.SupplierProductView;
import com.pharmacore.procurement.application.ProcurementService.SupplierView;
import com.pharmacore.procurement.persistence.PurchaseOrderEntity;
import com.pharmacore.procurement.persistence.SupplierEntity;
import com.pharmacore.tenant.application.BrandingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

@RestController
@RequestMapping("/api/v1/procurement")
class ProcurementController {

    private final ProcurementService procurement;
    private final BrandingService branding;

    ProcurementController(ProcurementService procurement, BrandingService branding) {
        this.procurement = procurement;
        this.branding = branding;
    }

    @GetMapping("/suppliers")
    @PreAuthorize("hasAuthority('PROCUREMENT_VIEW')")
    List<SupplierView> suppliers() {
        return procurement.suppliers();
    }

    @PostMapping("/suppliers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PROCUREMENT_MANAGE')")
    SupplierView createSupplier(@Valid @RequestBody SupplierRequest request) {
        return procurement.createSupplier(request.toDetails());
    }

    @PutMapping("/suppliers/{supplierId}")
    @PreAuthorize("hasAuthority('PROCUREMENT_MANAGE')")
    SupplierView updateSupplier(@PathVariable UUID supplierId, @Valid @RequestBody SupplierRequest request) {
        return procurement.updateSupplier(supplierId, request.toDetails());
    }

    @GetMapping("/suppliers/{supplierId}/products")
    @PreAuthorize("hasAuthority('PROCUREMENT_VIEW')")
    List<SupplierProductView> supplierCatalog(@PathVariable UUID supplierId) {
        return procurement.supplierCatalog(supplierId);
    }

    @PutMapping("/suppliers/{supplierId}/products/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PROCUREMENT_MANAGE')")
    void linkProduct(@PathVariable UUID supplierId, @PathVariable UUID productId,
                     @Valid @RequestBody LinkRequest request) {
        procurement.linkProduct(supplierId, productId, request.supplierReference(), request.purchasePriceCents(),
                request.packSize(), request.preferred());
    }

    /** The next-day order sheet, computed live from sales velocity, stock, orders and expiries. */
    @GetMapping("/proposal")
    @PreAuthorize("hasAuthority('PROCUREMENT_VIEW')")
    ProposalSheet proposal(@RequestParam(required = false) UUID supplierId) {
        return procurement.proposal(supplierId);
    }

    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('PROCUREMENT_VIEW')")
    List<OrderView> orders(@RequestParam(required = false) PurchaseOrderEntity.Status status,
                           @RequestParam(defaultValue = "50") int limit) {
        return procurement.orders(status, limit);
    }

    @GetMapping("/orders/{orderId}")
    @PreAuthorize("hasAuthority('PROCUREMENT_VIEW')")
    OrderView order(@PathVariable UUID orderId) {
        return procurement.order(orderId);
    }

    @GetMapping(value = "/orders/{orderId}/text", produces = MediaType.TEXT_PLAIN_VALUE)
    @PreAuthorize("hasAuthority('PROCUREMENT_VIEW')")
    String orderText(@PathVariable UUID orderId) {
        return procurement.orderAsText(orderId, branding.current().branding().displayName());
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PROCUREMENT_MANAGE')")
    OrderView createOrder(@Valid @RequestBody OrderRequest request) {
        return procurement.createOrder(request.supplierId(), request.toCommands(), request.notes());
    }

    @PutMapping("/orders/{orderId}")
    @PreAuthorize("hasAuthority('PROCUREMENT_MANAGE')")
    OrderView updateOrder(@PathVariable UUID orderId, @Valid @RequestBody OrderRequest request) {
        return procurement.updateOrder(orderId, request.toCommands(), request.notes());
    }

    @PostMapping("/orders/{orderId}/send")
    @PreAuthorize("hasAuthority('PROCUREMENT_MANAGE')")
    OrderView send(@PathVariable UUID orderId) {
        return procurement.send(orderId);
    }

    @PostMapping("/orders/{orderId}/cancellation")
    @PreAuthorize("hasAuthority('PROCUREMENT_MANAGE')")
    OrderView cancel(@PathVariable UUID orderId) {
        return procurement.cancel(orderId);
    }

    @PostMapping("/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('PROCUREMENT_MANAGE', 'STOCK_RECEIVE')")
    ReceiptView receive(@Valid @RequestBody ReceiptRequest request) {
        return procurement.receive(new ReceiptCommand(request.supplierId(), request.purchaseOrderId(),
                request.supplierInvoiceNumber(), request.invoiceDate(), request.notes(),
                request.lines().stream().map(l -> new ReceiptLineCommand(l.productId(), l.lotNumber(), l.expiryDate(),
                        l.quantity(), l.unitCostCents(), l.vatRate(), l.sellingPriceCents())).toList()));
    }

    record SupplierRequest(@NotBlank @Size(max = 150) String name,
                           @Size(max = 150) String contactName,
                           @Size(max = 30) String phone,
                           @Size(max = 30) String whatsapp,
                           @Email @Size(max = 200) String email,
                           @Size(max = 300) String address,
                           @Min(0) @Max(90) int leadTimeDays,
                           @Size(max = 30) String orderDays,
                           @PositiveOrZero long minOrderCents,
                           @Size(max = 100) String paymentTerms,
                           @Size(max = 500) String notes) {

        SupplierEntity.Details toDetails() {
            return new SupplierEntity.Details(name, contactName, phone, whatsapp, email, address, leadTimeDays, orderDays,
                    minOrderCents, paymentTerms, notes);
        }
    }

    record LinkRequest(@Size(max = 40) String supplierReference,
                       @PositiveOrZero Long purchasePriceCents,
                       @Min(1) @Max(1000) int packSize,
                       boolean preferred) {
    }

    record OrderRequest(@NotNull UUID supplierId,
                        @NotEmpty @Size(max = 500) List<@Valid OrderLineRequest> lines,
                        @Size(max = 500) String notes) {

        List<OrderLineCommand> toCommands() {
            return lines.stream().map(l -> new OrderLineCommand(l.productId(), l.quantity(), l.unitCostCents(),
                    l.suggestedQuantity(), l.reason())).toList();
        }
    }

    record OrderLineRequest(@NotNull UUID productId,
                            @PositiveOrZero int quantity,
                            @PositiveOrZero Long unitCostCents,
                            Integer suggestedQuantity,
                            @Size(max = 255) String reason) {
    }

    record ReceiptRequest(@NotNull UUID supplierId,
                          UUID purchaseOrderId,
                          @Size(max = 40) String supplierInvoiceNumber,
                          LocalDate invoiceDate,
                          @Size(max = 500) String notes,
                          @NotEmpty @Size(max = 500) List<@Valid ReceiptLineRequest> lines) {
    }

    record ReceiptLineRequest(@NotNull UUID productId,
                              @NotBlank @Size(max = 40) String lotNumber,
                              @NotNull LocalDate expiryDate,
                              @Positive int quantity,
                              @PositiveOrZero long unitCostCents,
                              @DecimalMin("0") BigDecimal vatRate,
                              @PositiveOrZero Long sellingPriceCents) {
    }
}
