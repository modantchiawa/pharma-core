package com.pharmacore.sync;

import com.pharmacore.shared.runtime.NodeIdentity;
import com.pharmacore.shared.runtime.NodeProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Hub status, pairing (both sides) and synchronization administration. */
@RestController
class HubController {

    private final NodeIdentity node;
    private final NodeProperties properties;
    private final HubSyncService sync;
    private final HubPairingService hubPairing;
    private final PairingService pairing;
    private final HubCloudClient cloud;

    HubController(NodeIdentity node, NodeProperties properties, HubSyncService sync, HubPairingService hubPairing,
                  PairingService pairing, HubCloudClient cloud) {
        this.node = node;
        this.properties = properties;
        this.sync = sync;
        this.hubPairing = hubPairing;
        this.pairing = pairing;
        this.cloud = cloud;
    }

    /** Read by the PWA at startup: which node it talks to and whether a pharmacy is configured. */
    @GetMapping("/api/public/hub/info")
    NodeInfo info() {
        return new NodeInfo(properties.mode().name(), node.nodeId(), node.isPaired(), node.slug().orElse(null),
                properties.isHub() ? node.cloudUrl() : null, cloud.appVersion());
    }

    @PostMapping("/api/public/hub/pair")
    HubCloudClient.PairingReply pair(@Valid @RequestBody PairRequest request) {
        return hubPairing.pair(request.code());
    }

    @GetMapping("/api/v1/hub/sync")
    @PreAuthorize("hasAnyAuthority('HUB_ADMIN', 'AUDIT_VIEW')")
    HubSyncService.Status syncStatus() {
        return sync.status();
    }

    @PostMapping("/api/v1/hub/sync/run")
    @PreAuthorize("hasAuthority('HUB_ADMIN')")
    HubSyncService.Status syncNow() {
        return sync.synchronize();
    }

    /** Cloud side: redeemed by a hub during its first-run wizard. */
    @PostMapping("/api/public/hub/pairing")
    PairingService.PairingResult redeem(@Valid @RequestBody RedeemRequest request, HttpServletRequest http) {
        String base = http.getScheme() + "://" + http.getServerName()
                + ((http.getServerPort() == 80 || http.getServerPort() == 443) ? "" : ":" + http.getServerPort());
        return pairing.redeem(new PairingService.PairingRequest(request.code(), request.nodeId(), request.appVersion(),
                request.keyId(), request.kid(), request.publicKeyPem()), base);
    }

    /** Cloud side: platform operator issues a pairing code for a pharmacy. */
    @PostMapping("/api/platform/tenants/{tenantId}/pairing-codes")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, String> pairingCode(@PathVariable UUID tenantId) {
        return Map.of("code", pairing.createCode(tenantId), "validFor", PairingService.CODE_VALIDITY.toString());
    }

    record NodeInfo(String mode, String nodeId, boolean paired, String pharmacySlug, String cloudUrl, String version) {
    }

    record PairRequest(@NotBlank @Size(max = 12) String code) {
    }

    record RedeemRequest(@NotBlank @Size(max = 12) String code,
                         @NotBlank @Size(max = 64) String nodeId,
                         @Size(max = 40) String appVersion,
                         @NotNull UUID keyId,
                         @NotBlank @Size(max = 64) String kid,
                         @NotBlank @Size(max = 4000) String publicKeyPem) {
    }
}
