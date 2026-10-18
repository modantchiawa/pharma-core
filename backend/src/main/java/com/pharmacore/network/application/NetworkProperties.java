package com.pharmacore.network.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pharmacore.network")
public record NetworkProperties(int queriesPerMinute, Duration reservationTtl) {

    public NetworkProperties {
        if (queriesPerMinute <= 0) {
            queriesPerMinute = 30;
        }
        if (reservationTtl == null) {
            reservationTtl = Duration.ofHours(4);
        }
    }
}
