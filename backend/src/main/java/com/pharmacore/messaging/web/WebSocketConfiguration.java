package com.pharmacore.messaging.web;

import com.pharmacore.shared.security.SecurityProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
class WebSocketConfiguration implements WebSocketMessageBrokerConfigurer {

    private final StompAuthenticationInterceptor authentication;
    private final SecurityProperties security;
    private final MessagingProperties messaging;

    WebSocketConfiguration(StompAuthenticationInterceptor authentication, SecurityProperties security,
                           MessagingProperties messaging) {
        this.authentication = authentication;
        this.security = security;
        this.messaging = messaging;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(security.allowedOrigins().toArray(String[]::new));
        registry.setPreserveReceiveOrder(true);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        var relay = messaging.brokerRelay();
        if (relay.enabled()) {
            registry.enableStompBrokerRelay("/topic", "/queue")
                    .setRelayHost(relay.host())
                    .setRelayPort(relay.port())
                    .setClientLogin(relay.login())
                    .setClientPasscode(relay.passcode())
                    .setSystemLogin(relay.login())
                    .setSystemPasscode(relay.passcode());
        } else {
            registry.enableSimpleBroker("/topic", "/queue");
        }
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authentication);
        registration.executor(new VirtualThreadTaskExecutor("stomp-in-"));
    }
}
