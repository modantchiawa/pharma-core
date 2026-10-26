package com.pharmacore.sync;

import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.runtime.NodeIdentity;
import com.pharmacore.shared.runtime.NodeProperties;
import com.pharmacore.shared.security.SigningKeyService;
import com.pharmacore.shared.web.BusinessRuleException;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

/** Hub side of pairing: redeems a one-time code at the cloud, then loads the pharmacy's data. */
@Service
public class HubPairingService {

    private static final Logger log = LoggerFactory.getLogger(HubPairingService.class);

    private final NodeIdentity node;
    private final NodeProperties properties;
    private final HubCloudClient cloud;
    private final SigningKeyService signingKey;
    private final HubSyncService sync;
    private final Clock clock;
    private final ExecutorService bootstrapExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public HubPairingService(NodeIdentity node, NodeProperties properties, HubCloudClient cloud,
                             SigningKeyService signingKey, HubSyncService sync, Clock clock) {
        this.node = node;
        this.properties = properties;
        this.cloud = cloud;
        this.signingKey = signingKey;
        this.sync = sync;
        this.clock = clock;
    }

    public HubCloudClient.PairingReply pair(String code) {
        if (!properties.isHub()) {
            throw new BusinessRuleException("not-a-hub", "Ce nœud n'est pas un serveur local");
        }
        if (node.isPaired()) {
            throw new BusinessRuleException("already-paired", "Ce serveur est déjà appairé avec " + node.slug().orElse("?"));
        }
        HubCloudClient.PairingReply reply;
        try {
            reply = cloud.pair(code.trim().toUpperCase().replace(" ", ""));
        } catch (HttpClientErrorException e) {
            throw new BusinessRuleException("pairing-refused", "Code d'appairage refusé par le cloud");
        } catch (ResourceAccessException e) {
            throw new BusinessRuleException("cloud-unreachable", "Cloud injoignable, vérifiez la connexion Internet");
        }
        var tenant = new TenantId(reply.tenantId());
        node.recordPairing(tenant, reply.slug(), reply.cloudUrl() == null ? node.cloudUrl() : reply.cloudUrl(),
                clock.instant().toString());
        signingKey.publish(tenant);
        // The initial load can take minutes on a large catalog: it runs in the background and
        // the first-run screen shows its progress through the sync status.
        bootstrapExecutor.submit(() -> {
            try {
                sync.bootstrap();
                sync.synchronize();
            } catch (RuntimeException e) {
                log.error("Chargement initial en échec, il sera retenté à la prochaine synchronisation", e);
            }
        });
        return reply;
    }
}
