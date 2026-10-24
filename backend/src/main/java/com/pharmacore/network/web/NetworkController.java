package com.pharmacore.network.web;

import com.pharmacore.network.application.NetworkDirectoryService;
import com.pharmacore.network.application.NetworkDirectoryService.DirectoryEntry;
import com.pharmacore.network.application.NetworkDirectoryService.NetworkSettings;
import com.pharmacore.network.application.NetworkStockQueryService;
import com.pharmacore.network.application.NetworkStockQueryService.PartnerAvailability;
import com.pharmacore.network.application.NetworkStockQueryService.PartnerProduct;
import com.pharmacore.network.application.NetworkStockQueryService.ProductPartners;
import com.pharmacore.network.application.RateLimitExceededException;
import com.pharmacore.network.application.TransferService;
import com.pharmacore.network.application.TransferService.TransferView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
class NetworkController {

    private final NetworkStockQueryService stockQuery;
    private final NetworkDirectoryService directory;
    private final TransferService transfers;

    NetworkController(NetworkStockQueryService stockQuery, NetworkDirectoryService directory,
                      TransferService transfers) {
        this.stockQuery = stockQuery;
        this.directory = directory;
        this.transfers = transfers;
    }

    @GetMapping("/api/v1/network/directory")
    @PreAuthorize("hasAnyAuthority('NETWORK_STOCK_QUERY', 'NETWORK_CHAT', 'GUARD_VIEW')")
    List<DirectoryEntry> directory() {
        return directory.directory();
    }

    @PutMapping("/api/v1/network/{networkId}/stock-sharing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('CONFIG_BRANDING_MANAGE')")
    void stockSharing(@PathVariable UUID networkId, @RequestBody SharingRequest request) {
        directory.setStockSharing(networkId, request.share());
    }

    @GetMapping("/api/v1/network/networks")
    @PreAuthorize("hasAnyAuthority('NETWORK_STOCK_QUERY', 'NETWORK_CHAT', 'GUARD_VIEW', 'GUARD_MANAGE')")
    List<NetworkSettings> networks() {
        return directory.networks();
    }

    @PutMapping("/api/v1/network/{networkId}/guards-publication")
    @PreAuthorize("hasAuthority('GUARD_MANAGE')")
    NetworkSettings guardsPublication(@PathVariable UUID networkId, @RequestBody PublicationRequest request) {
        return directory.setGuardsPublication(networkId, request.publish());
    }

    @GetMapping("/api/v1/network/stock/{productId}")
    @PreAuthorize("hasAuthority('NETWORK_STOCK_QUERY')")
    List<PartnerAvailability> availability(@PathVariable UUID productId) {
        return stockQuery.availability(productId);
    }

    /** Who has it: every matching product with the partners holding it. */
    @GetMapping("/api/v1/network/stock")
    @PreAuthorize("hasAuthority('NETWORK_STOCK_QUERY')")
    List<ProductPartners> search(@RequestParam String q) {
        return stockQuery.search(q);
    }

    /** What this partner has among the matching products. */
    @GetMapping("/api/v1/network/pharmacies/{tenantId}/stock")
    @PreAuthorize("hasAuthority('NETWORK_STOCK_QUERY')")
    List<PartnerProduct> partnerStock(@PathVariable UUID tenantId, @RequestParam String q) {
        return stockQuery.partnerStock(tenantId, q);
    }

    @GetMapping("/api/v1/network/transfers")
    @PreAuthorize("hasAnyAuthority('NETWORK_TRANSFER_REQUEST', 'NETWORK_TRANSFER_RESPOND')")
    List<TransferView> transfers(@RequestParam(defaultValue = "50") int limit) {
        return transfers.list(limit);
    }

    @PostMapping("/api/v1/network/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('NETWORK_TRANSFER_REQUEST')")
    TransferView request(@Valid @RequestBody TransferRequest request) {
        return transfers.request(request.networkId(), request.providerTenantId(), request.productId(),
                request.quantity(), request.note());
    }

    @PostMapping("/api/v1/network/transfers/{transferId}/acceptance")
    @PreAuthorize("hasAuthority('NETWORK_TRANSFER_RESPOND')")
    TransferView accept(@PathVariable UUID transferId, @Valid @RequestBody NoteRequest request) {
        return transfers.accept(transferId, request.note());
    }

    @PostMapping("/api/v1/network/transfers/{transferId}/refusal")
    @PreAuthorize("hasAuthority('NETWORK_TRANSFER_RESPOND')")
    TransferView decline(@PathVariable UUID transferId, @Valid @RequestBody NoteRequest request) {
        return transfers.decline(transferId, request.note());
    }

    @PostMapping("/api/v1/network/transfers/{transferId}/completion")
    @PreAuthorize("hasAuthority('NETWORK_TRANSFER_RESPOND')")
    TransferView complete(@PathVariable UUID transferId) {
        return transfers.complete(transferId);
    }

    @PostMapping("/api/v1/network/transfers/{transferId}/cancellation")
    @PreAuthorize("hasAuthority('NETWORK_TRANSFER_REQUEST')")
    TransferView cancel(@PathVariable UUID transferId) {
        return transfers.cancel(transferId);
    }

    /** Platform: network creation. */
    @PostMapping("/api/platform/networks")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> createNetwork(@Valid @RequestBody NetworkRequest request) {
        return Map.of("networkId", directory.createNetwork(request.name(), request.region(),
                request.coordinatorTenantId(), request.quantityCap(), request.memberTenantIds()));
    }

    @PostMapping("/api/platform/networks/{networkId}/members")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void addMember(@PathVariable UUID networkId, @Valid @RequestBody MemberRequest request) {
        directory.addMember(networkId, request.tenantId());
    }

    record SharingRequest(boolean share) {
    }

    record PublicationRequest(boolean publish) {
    }

    record TransferRequest(@NotNull UUID networkId,
                           @NotNull UUID providerTenantId,
                           @NotNull UUID productId,
                           @Positive @Max(1000) int quantity,
                           @Size(max = 500) String note) {
    }

    record NoteRequest(@Size(max = 500) String note) {
    }

    record NetworkRequest(@NotBlank @Size(max = 150) String name,
                          @Size(max = 100) String region,
                          @NotNull UUID coordinatorTenantId,
                          @Min(1) @Max(1000) int quantityCap,
                          @NotEmpty List<UUID> memberTenantIds) {
    }

    record MemberRequest(@NotNull UUID tenantId) {
    }
}

@RestControllerAdvice
class NetworkExceptionHandler {

    @ExceptionHandler(RateLimitExceededException.class)
    ProblemDetail rateLimited(RateLimitExceededException e) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        problem.setType(URI.create("https://pharmacore.io/problems/rate-limited"));
        return problem;
    }
}
