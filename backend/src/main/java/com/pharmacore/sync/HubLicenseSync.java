package com.pharmacore.sync;

import com.pharmacore.shared.runtime.NodeIdentity;
import com.pharmacore.shared.runtime.NodeProperties;
import com.pharmacore.subscription.application.HubLicenseService;
import com.pharmacore.subscription.application.LicenseRefresher;
import com.pharmacore.subscription.domain.LicenseRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Hub side of the license refresh, run at the end of every synchronization cycle and on demand:
 * learns endorsed key rotations, then takes the license the cloud returns (re-issued when old
 * enough, so the 15-day offline window slides while the hub is online).
 */
@Component
class HubLicenseSync implements LicenseRefresher {

    private static final Logger log = LoggerFactory.getLogger(HubLicenseSync.class);

    private final NodeProperties properties;
    private final NodeIdentity node;
    private final HubCloudClient cloud;
    private final HubLicenseService licenses;

    HubLicenseSync(NodeProperties properties, NodeIdentity node, HubCloudClient cloud, HubLicenseService licenses) {
        this.properties = properties;
        this.node = node;
        this.cloud = cloud;
        this.licenses = licenses;
    }

    @Override
    public void refresh() {
        if (!properties.isHub() || !node.isPaired()) {
            return;
        }
        licenses.learn(cloud.licenseKeys());
        try {
            licenses.accept(cloud.license().token());
        } catch (LicenseRejectedException e) {
            log.warn("Licence reçue du cloud refusée ({}) : la licence en vigueur est conservée", e.reason());
        }
    }
}
