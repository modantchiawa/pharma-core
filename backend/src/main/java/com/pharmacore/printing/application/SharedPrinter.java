package com.pharmacore.printing.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Printer reached through the operating system as a file: a Windows printer share
 * ({@code \\PC-CAISSE\Ticket}, the usual way to drive a USB thermal printer from the hub), a
 * serial or USB device node on Linux ({@code /dev/usb/lp0}), or any path for diagnostics.
 */
public final class SharedPrinter implements PrinterPort {

    private final Path target;

    public SharedPrinter(String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("A shared printer needs a share name or a device path");
        }
        this.target = Path.of(address.strip());
    }

    public Path target() {
        return target;
    }

    @Override
    public void print(byte[] payload) {
        try (var out = Files.newOutputStream(target, StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
            out.write(payload);
            out.flush();
        } catch (IOException | UnsupportedOperationException e) {
            throw new PrinterUnavailableException("Imprimante partagée " + target + " indisponible", e);
        }
    }
}
