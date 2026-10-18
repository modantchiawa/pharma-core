package com.pharmacore.identity.application;

import com.pharmacore.identity.domain.Permission;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Fails fast when the Permission enum and the {@code permission} reference table diverge. */
@Component
class PermissionCatalogVerifier {

    private final JdbcClient jdbc;

    PermissionCatalogVerifier(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    void verify() {
        Set<String> stored = new HashSet<>(jdbc.sql("SELECT code FROM permission").query(String.class).list());
        Set<String> declared = Arrays.stream(Permission.values()).map(Enum::name).collect(Collectors.toSet());
        if (!stored.equals(declared)) {
            var missingInDb = new HashSet<>(declared);
            missingInDb.removeAll(stored);
            var unknownInCode = new HashSet<>(stored);
            unknownInCode.removeAll(declared);
            throw new IllegalStateException("Permission catalog mismatch, missing in database: " + missingInDb
                    + ", unknown to the application: " + unknownInCode);
        }
    }
}
