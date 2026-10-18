package com.pharmacore.messaging.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * With several application nodes the in-memory broker only reaches clients connected to the
 * same node: enable the relay to an external STOMP broker (RabbitMQ, ActiveMQ Artemis) in
 * production.
 */
@ConfigurationProperties("pharmacore.messaging")
public record MessagingProperties(BrokerRelay brokerRelay) {

    public MessagingProperties {
        if (brokerRelay == null) {
            brokerRelay = new BrokerRelay(false, "localhost", 61613, "guest", "guest");
        }
    }

    public record BrokerRelay(boolean enabled, String host, int port, String login, String passcode) {
    }
}
