package com.pharmacore.sales.web;

import com.pharmacore.sales.application.MobileMoneyWalletService;
import com.pharmacore.sales.application.MobileMoneyWalletService.BalanceReading;
import com.pharmacore.sales.application.MobileMoneyWalletService.Wallet;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
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

/** External balance of the mobile money wallets: readings and what the counter booked since. */
@RestController
@RequestMapping("/api/v1/payments/wallets")
class MobileMoneyWalletController {

    private final MobileMoneyWalletService wallets;

    MobileMoneyWalletController(MobileMoneyWalletService wallets) {
        this.wallets = wallets;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PAYMENT_RECONCILE', 'REPORT_VIEW', 'CASH_APPROVE')")
    List<Wallet> wallets() {
        return wallets.wallets();
    }

    @GetMapping("/balances")
    @PreAuthorize("hasAnyAuthority('PAYMENT_RECONCILE', 'REPORT_VIEW', 'CASH_APPROVE')")
    List<BalanceReading> history(@RequestParam(required = false) String provider,
                                 @RequestParam(defaultValue = "50") int limit) {
        return wallets.history(provider, limit);
    }

    /** A balance read on the operator's app or USSD menu, typed by the pharmacist. */
    @PostMapping("/{provider}/balance")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('PAYMENT_RECONCILE', 'CASH_APPROVE')")
    BalanceReading record(@PathVariable String provider, @Valid @RequestBody BalanceRequest request) {
        return wallets.recordManual(provider, request.balanceCents(), request.note());
    }

    record BalanceRequest(@PositiveOrZero long balanceCents, @Size(max = 255) String note) {
    }
}
