package com.pharmacore.shared.runtime;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param mode               HUB or CLOUD
 * @param keysDir            directory holding the node's private signing key (never in the database)
 * @param cloudUrl           base URL of the cloud API (hub mode)
 * @param syncInterval       delay between two synchronization rounds (hub mode)
 * @param accessTokenTtl     lifetime of user access tokens
 * @param refreshTokenTtl    lifetime of refresh tokens
 * @param terminalTokenTtl   lifetime of the terminal binding allowing PIN switches
 */
@ConfigurationProperties("pharmacore.node")
public record NodeProperties(DeploymentMode mode,
                             Path keysDir,
                             String cloudUrl,
                             Duration syncInterval,
                             Duration accessTokenTtl,
                             Duration refreshTokenTtl,
                             Duration terminalTokenTtl) {

    public NodeProperties {
        mode = mode == null ? DeploymentMode.CLOUD : mode;
        keysDir = keysDir == null ? Path.of("data", "keys") : keysDir;
        syncInterval = syncInterval == null ? Duration.ofSeconds(30) : syncInterval;
        accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(15) : accessTokenTtl;
        refreshTokenTtl = refreshTokenTtl == null ? Duration.ofHours(14) : refreshTokenTtl;
        terminalTokenTtl = terminalTokenTtl == null ? Duration.ofDays(30) : terminalTokenTtl;
    }

    public boolean isHub() {
        return mode == DeploymentMode.HUB;
    }
}
