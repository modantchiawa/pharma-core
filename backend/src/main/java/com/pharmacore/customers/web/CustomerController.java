package com.pharmacore.customers.web;

import com.pharmacore.customers.application.CustomerService;
import com.pharmacore.customers.application.CustomerService.CustomerView;
import com.pharmacore.customers.application.CustomerService.PaymentView;
import com.pharmacore.customers.persistence.CustomerEntity;
import com.pharmacore.followup.domain.MessageChannel;
import com.pharmacore.sales.domain.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
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

@RestController
@RequestMapping("/api/v1/customers")
class CustomerController {

    private final CustomerService customers;

    CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('SALE_CREATE', 'CUSTOMER_MANAGE')")
    List<CustomerView> search(@RequestParam @Size(min = 2, max = 100) String q,
                              @RequestParam(defaultValue = "20") int limit) {
        return customers.search(q, limit);
    }

    @GetMapping("/debtors")
    @PreAuthorize("hasAnyAuthority('CUSTOMER_MANAGE', 'REPORT_VIEW')")
    List<CustomerView> debtors(@RequestParam(defaultValue = "100") int limit) {
        return customers.debtors(limit);
    }

    @GetMapping("/{customerId}")
    @PreAuthorize("hasAnyAuthority('SALE_CREATE', 'CUSTOMER_MANAGE')")
    CustomerView get(@PathVariable UUID customerId) {
        return customers.get(customerId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    CustomerView create(@Valid @RequestBody CustomerRequest request) {
        return customers.create(request.toDetails());
    }

    @PutMapping("/{customerId}")
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    CustomerView update(@PathVariable UUID customerId, @Valid @RequestBody CustomerRequest request) {
        return customers.update(customerId, request.toDetails());
    }

    @PostMapping("/{customerId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    PaymentView pay(@PathVariable UUID customerId, @Valid @RequestBody PaymentRequest request) {
        return customers.receivePayment(customerId, request.amountCents(), request.method(), request.provider(),
                request.reference(), request.terminalId());
    }

    record CustomerRequest(@NotBlank @Size(max = 150) String displayName,
                           @Size(max = 30) String phone,
                           @Email @Size(max = 200) String email,
                           @Size(max = 300) String address,
                           @PositiveOrZero long creditLimitCents,
                           @Size(max = 500) String notes,
                           boolean messagingConsent,
                           MessageChannel preferredChannel) {

        CustomerEntity.Details toDetails() {
            return new CustomerEntity.Details(displayName, phone, email, address, creditLimitCents, notes,
                    messagingConsent, preferredChannel == null ? MessageChannel.WHATSAPP : preferredChannel);
        }
    }

    record PaymentRequest(@Positive long amountCents,
                          @NotNull PaymentMethod method,
                          @Size(max = 30) String provider,
                          @Size(max = 64) String reference,
                          @Size(max = 40) String terminalId) {
    }
}
