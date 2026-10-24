package com.pharmacore.shared.web;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * The node serves the PWA itself (hub: from the {@code web/} folder next to the jar; cloud: the
 * same, or a CDN in front). Unknown non-API paths fall back to {@code index.html} so deep links
 * and the service worker work.
 */
@Configuration(proxyBeanMethods = false)
class SpaConfiguration implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("file:./web/", "classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = super.getResource(resourcePath, location);
                        if (requested != null) {
                            return requested;
                        }
                        boolean apiOrAsset = resourcePath.startsWith("api/") || resourcePath.startsWith("ws")
                                || resourcePath.startsWith("actuator/") || resourcePath.contains(".");
                        return apiOrAsset ? null : super.getResource("index.html", location);
                    }
                });
    }
}
