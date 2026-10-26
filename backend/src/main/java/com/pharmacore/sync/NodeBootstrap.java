package com.pharmacore.sync;

import com.pharmacore.shared.runtime.NodeIdentity;
import com.pharmacore.shared.security.SigningKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Publishes this node's public key at startup so its tokens are verifiable at once. */
@Component
class NodeBootstrap {

    private static final Logger log = LoggerFactory.getLogger(NodeBootstrap.class);

    private final NodeIdentity node;
    private final SigningKeyService signingKey;

    NodeBootstrap(NodeIdentity node, SigningKeyService signingKey) {
        this.node = node;
        this.signingKey = signingKey;
    }

    @EventListener(ApplicationReadyEvent.class)
    void publishKey() {
        var tenant = node.hubTenant().orElse(null);
        signingKey.publish(tenant);
        log.info("Nœud {} ({}) : clé de signature {} publiée{}", node.nodeId(), node.mode(), signingKey.kid(),
                tenant == null ? "" : " pour la pharmacie " + node.slug().orElse(tenant.toString()));
    }
}
