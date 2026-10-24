package com.pharmacore.shared.runtime;

import com.pharmacore.shared.context.TenantId;
import java.security.SecureRandom;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Identity of this node: a stable id, and for a hub the pharmacy it was paired with.
 * Everything is persisted in {@code node_setting} so a reinstall over the same database keeps
 * its identity.
 */
@Component
public class NodeIdentity {

    public static final String CLOUD_NODE_ID = "cloud";
    static final String KEY_NODE_ID = "node.id";
    static final String KEY_TENANT_ID = "hub.tenant_id";
    static final String KEY_SLUG = "hub.slug";
    static final String KEY_CLOUD_URL = "hub.cloud_url";
    static final String KEY_PAIRED_AT = "hub.paired_at";

    private final NodeProperties properties;
    private final NodeSettings settings;
    private volatile String nodeId;
    private volatile TenantId tenantId;
    private volatile String slug;
    private volatile String cloudUrl;

    public NodeIdentity(NodeProperties properties, NodeSettings settings) {
        this.properties = properties;
        this.settings = settings;
    }

    public DeploymentMode mode() {
        return properties.mode();
    }

    public String nodeId() {
        if (nodeId == null) {
            nodeId = properties.isHub() ? settings.get(KEY_NODE_ID).orElseGet(this::createNodeId) : CLOUD_NODE_ID;
        }
        return nodeId;
    }

    /** The pharmacy this hub serves, empty while not paired or in cloud mode. */
    public Optional<TenantId> hubTenant() {
        if (!properties.isHub()) {
            return Optional.empty();
        }
        if (tenantId == null) {
            tenantId = settings.get(KEY_TENANT_ID).map(TenantId::of).orElse(null);
        }
        return Optional.ofNullable(tenantId);
    }

    public TenantId requireHubTenant() {
        return hubTenant().orElseThrow(() -> new IllegalStateException("This hub is not paired with a pharmacy yet"));
    }

    public Optional<String> slug() {
        if (slug == null) {
            slug = settings.get(KEY_SLUG).orElse(null);
        }
        return Optional.ofNullable(slug);
    }

    public String cloudUrl() {
        if (cloudUrl == null) {
            cloudUrl = settings.get(KEY_CLOUD_URL).orElse(properties.cloudUrl());
        }
        return cloudUrl;
    }

    public boolean isPaired() {
        return !properties.isHub() || hubTenant().isPresent();
    }

    public void recordPairing(TenantId tenant, String pharmacySlug, String cloudBaseUrl, String pairedAt) {
        settings.put(KEY_TENANT_ID, tenant.toString());
        settings.put(KEY_SLUG, pharmacySlug);
        settings.put(KEY_CLOUD_URL, cloudBaseUrl);
        settings.put(KEY_PAIRED_AT, pairedAt);
        this.tenantId = tenant;
        this.slug = pharmacySlug;
        this.cloudUrl = cloudBaseUrl;
    }

    private String createNodeId() {
        var random = new SecureRandom();
        var alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        var builder = new StringBuilder("hub-");
        for (int i = 0; i < 10; i++) {
            builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        String id = builder.toString();
        settings.put(KEY_NODE_ID, id);
        return id;
    }
}
