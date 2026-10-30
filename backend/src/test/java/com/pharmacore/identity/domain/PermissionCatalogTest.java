package com.pharmacore.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The application refuses to start when the {@code permission} table and the {@link Permission}
 * enum diverge ({@code PermissionCatalogVerifier}). This test reads the migrations so the
 * divergence is caught by the build, long before a deployment.
 */
class PermissionCatalogTest {

    private static final Pattern INSERT = Pattern.compile(
            "INSERT\\s+INTO\\s+permission\\s*\\(\\s*code\\s*,\\s*category\\s*,\\s*description\\s*\\)\\s*VALUES(.*?);",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CODE = Pattern.compile("\\(\\s*'([A-Z_]+)'\\s*,");

    @Test
    void everyPermissionSeededByTheMigrationsIsDeclaredAndConversely() throws IOException {
        Set<String> seeded = new TreeSet<>();
        Path migrations = Path.of("src/main/resources/db/migration");
        try (Stream<Path> files = Files.list(migrations)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".sql")).toList()) {
                Matcher insert = INSERT.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (insert.find()) {
                    Matcher code = CODE.matcher(insert.group(1));
                    while (code.find()) {
                        seeded.add(code.group(1));
                    }
                }
            }
        }
        Set<String> declared = Arrays.stream(Permission.values()).map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(seeded).isNotEmpty();
        assertThat(declared)
                .as("permissions in the enum but never inserted by a migration")
                .containsAll(seeded);
        assertThat(seeded)
                .as("permissions inserted by a migration but unknown to the enum: the backend would refuse to start")
                .containsAll(declared);
    }
}
