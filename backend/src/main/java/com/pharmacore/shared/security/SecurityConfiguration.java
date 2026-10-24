package com.pharmacore.shared.security;

import com.pharmacore.shared.runtime.NodeProperties;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, BearerTokenAuthenticator authenticator, NodeProperties node,
                                    JdbcClient jdbc) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> { })
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/public/hub/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh", "/api/auth/pin",
                                "/api/auth/logout").permitAll()
                        // STOMP authenticates the CONNECT frame itself (see StompAuthenticationInterceptor)
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/api/platform/**").hasAuthority(BearerTokenAuthenticator.PLATFORM_ADMIN_AUTHORITY)
                        .requestMatchers("/api/sync/**").hasAuthority(BearerTokenAuthenticator.HUB_NODE_AUTHORITY)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.authenticationManagerResolver(request -> authenticator))
                .addFilterAfter(new TenantContextFilter(), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new TopologyGuardFilter(node, jdbc), TenantContextFilter.class)
                .build();
    }

    @Bean
    InternalIssuerRegistry internalIssuerRegistry(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        return new InternalIssuerRegistry(jdbc, transactionManager);
    }

    @Bean
    OidcTrustedIssuerRegistry oidcTrustedIssuerRegistry(SecurityProperties properties) {
        return new OidcTrustedIssuerRegistry(properties);
    }

    /** Every registry bean (internal keys, OIDC realms, test issuers) consulted in turn. */
    @Bean
    @Primary
    TrustedIssuerRegistry trustedIssuerRegistry(List<TrustedIssuerRegistry> registries) {
        return new CompositeTrustedIssuerRegistry(registries);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", TenantContextFilter.CORRELATION_HEADER));
        cors.setExposedHeaders(List.of(TenantContextFilter.CORRELATION_HEADER, "ETag"));
        cors.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
