package com.pharmacore.shared.runtime;

/**
 * Where this process runs. The same binary serves both roles.
 * <ul>
 *   <li>{@code HUB}: the local server of one pharmacy (LAN), master of its operational data,
 *       works for weeks without Internet and synchronizes with the cloud when it can.</li>
 *   <li>{@code CLOUD}: the multi-tenant platform: national catalog, inter-pharmacy network,
 *       supervision, replica of every hub, and direct service for pharmacies without hub.</li>
 * </ul>
 */
public enum DeploymentMode {
    HUB,
    CLOUD
}
