package com.pharmacore.sync;

import com.nimbusds.jwt.JWTClaimsSet;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.runtime.NodeIdentity;
import com.pharmacore.shared.security.ActorResolver;
import com.pharmacore.shared.security.SigningKeyService;
import com.pharmacore.subscription.application.LicenseIssuer.IssuedLicense;
import com.pharmacore.subscription.application.LicenseKeyRing.PublicLicenseKey;
import com.pharmacore.sync.SyncModels.Change;
import com.pharmacore.sync.SyncModels.LogPosition;
import com.pharmacore.sync.SyncModels.PullResponse;
import com.pharmacore.sync.SyncModels.PushResponse;
import com.pharmacore.sync.SyncModels.SnapshotPage;
import com.pharmacore.sync.SyncModels.SnapshotStart;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** HTTP client of the cloud, authenticated as this hub with a self-signed node token. */
@Component
public class HubCloudClient {

    private final RestClient.Builder builder;
    private final SigningKeyService signingKey;
    private final NodeIdentity node;
    private final Clock clock;
    private final String appVersion;

    public HubCloudClient(RestClient.Builder builder, SigningKeyService signingKey, NodeIdentity node, Clock clock,
                          java.util.Optional<BuildProperties> build) {
        this.builder = builder;
        this.signingKey = signingKey;
        this.node = node;
        this.clock = clock;
        this.appVersion = build.map(BuildProperties::getVersion).orElse("dev");
    }

    public String appVersion() {
        return appVersion;
    }

    public PushResponse push(List<Change> changes) {
        return client().post().uri("/api/sync/push")
                .body(Map.of("nodeId", node.nodeId(), "appVersion", appVersion, "changes", changes))
                .retrieve()
                .body(PushResponse.class);
    }

    public PullResponse pull(LogPosition after, int limit) {
        return client().get()
                .uri("/api/sync/pull?afterXid={xid}&afterSeq={seq}&limit={limit}", after.xid(), after.seq(), limit)
                .retrieve()
                .body(PullResponse.class);
    }

    public SnapshotStart snapshotStart(boolean includeHubOwned) {
        return client().get().uri("/api/sync/snapshot?includeHubOwned={flag}", includeHubOwned)
                .retrieve().body(SnapshotStart.class);
    }

    public SnapshotPage snapshot(String table, String after, int limit) {
        return client().get()
                .uri(uri -> uri.path("/api/sync/snapshot/" + table).queryParam("limit", limit)
                        .queryParamIfPresent("after", java.util.Optional.ofNullable(after)).build())
                .retrieve().body(SnapshotPage.class);
    }

    /** The pharmacy's license, freshly re-issued by the cloud when the previous one is old enough. */
    public IssuedLicense license() {
        return client().post().uri("/api/sync/license").retrieve().body(IssuedLicense.class);
    }

    public List<PublicLicenseKey> licenseKeys() {
        return client().get().uri("/api/sync/license-keys").retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<PublicLicenseKey>>() { });
    }

    /** Pairing is the only unauthenticated call: the hub has no key registered yet. */
    public PairingReply pair(String code) {
        return builder.clone().baseUrl(node.cloudUrl()).build()
                .post().uri("/api/public/hub/pairing")
                .body(Map.of(
                        "code", code,
                        "nodeId", node.nodeId(),
                        "appVersion", appVersion,
                        "keyId", signingKey.keyRowId(),
                        "kid", signingKey.kid(),
                        "publicKeyPem", signingKey.publicKeyPem()))
                .retrieve().body(PairingReply.class);
    }

    public record PairingReply(UUID tenantId, String slug, String displayName, String cloudUrl,
                               List<PublicLicenseKey> licenseKeys, String license) {
    }

    private RestClient client() {
        TenantId tenant = node.requireHubTenant();
        var now = clock.instant();
        String token = signingKey.sign(new JWTClaimsSet.Builder()
                .issuer(SigningKeyService.issuerFor(tenant))
                .subject("hub:" + node.nodeId())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                .jwtID(UUID.randomUUID().toString())
                .claim(ActorResolver.TENANT_CLAIM, tenant.toString())
                .claim(ActorResolver.TYPE_CLAIM, ActorResolver.TYPE_HUB_NODE)
                .claim(ActorResolver.NODE_CLAIM, node.nodeId())
                .build());
        return builder.clone()
                .baseUrl(node.cloudUrl())
                .defaultHeader("Authorization", "Bearer " + token)
                .build();
    }
}
