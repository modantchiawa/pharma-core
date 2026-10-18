package com.pharmacore.network.application;

import com.pharmacore.network.persistence.NetworkMembershipEntity;
import com.pharmacore.network.persistence.NetworkMembershipRepository;
import com.pharmacore.network.persistence.PharmacyNetworkEntity;
import com.pharmacore.network.persistence.PharmacyNetworkRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.NotFoundException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NetworkDirectoryService {

    private final PharmacyNetworkRepository networks;
    private final NetworkMembershipRepository memberships;
    private final JdbcClient jdbc;
    private final AuditTrail audit;
    private final Clock clock;

    public NetworkDirectoryService(PharmacyNetworkRepository networks, NetworkMembershipRepository memberships,
                                   JdbcClient jdbc, AuditTrail audit, Clock clock) {
        this.networks = networks;
        this.memberships = memberships;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    /** Partner pharmacies of every network the current pharmacy belongs to. */
    @Transactional(readOnly = true)
    public List<DirectoryEntry> directory() {
        return jdbc.sql("SELECT * FROM network_directory()")
                .query((rs, rowNum) -> new DirectoryEntry(
                        rs.getObject("network_id", UUID.class),
                        rs.getString("network_name"),
                        rs.getString("region"),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("pharmacy_name"),
                        rs.getString("city"),
                        rs.getString("phone"),
                        rs.getBoolean("share_stock"),
                        rs.getBoolean("coordinator")))
                .list();
    }

    @Transactional
    public void setStockSharing(UUID networkId, boolean share) {
        var tenant = TenantContext.requireTenant().value();
        var membership = memberships.findActive(networkId, tenant)
                .orElseThrow(() -> new NotFoundException("Network membership", networkId));
        membership.shareStock(share);
        audit.record("NETWORK_STOCK_SHARING_CHANGED", "NETWORK", networkId, Map.of("share", Boolean.toString(share)));
    }

    /** Platform operation. */
    @Transactional
    public UUID createNetwork(String name, String region, UUID coordinatorTenantId, int quantityCap,
                              List<UUID> memberTenantIds) {
        var network = networks.save(new PharmacyNetworkEntity(name, region, coordinatorTenantId, quantityCap,
                clock.instant()));
        memberTenantIds.stream().distinct().forEach(tenantId ->
                memberships.save(new NetworkMembershipEntity(network.id(), tenantId, clock.instant())));
        if (!memberTenantIds.contains(coordinatorTenantId)) {
            memberships.save(new NetworkMembershipEntity(network.id(), coordinatorTenantId, clock.instant()));
        }
        audit.record("NETWORK_CREATED", "NETWORK", network.id(), Map.of(
                "name", name, "coordinator", coordinatorTenantId.toString(),
                "members", Integer.toString(memberTenantIds.size())));
        return network.id();
    }

    /** Platform operation. */
    @Transactional
    public void addMember(UUID networkId, UUID tenantId) {
        networks.findById(networkId).orElseThrow(() -> new NotFoundException("Network", networkId));
        memberships.save(new NetworkMembershipEntity(networkId, tenantId, clock.instant()));
        audit.record("NETWORK_MEMBER_ADDED", "NETWORK", networkId, Map.of("tenant", tenantId.toString()));
    }

    public record DirectoryEntry(UUID networkId, String networkName, String region, UUID tenantId,
                                 String pharmacyName, String city, String phone, boolean shareStock,
                                 boolean coordinator) {
    }
}
