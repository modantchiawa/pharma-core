package com.pharmacore.printing.application;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * Thermal printer listening on TCP (raw port 9100 on Ethernet or Wi-Fi models). Short timeouts:
 * a printer that is off must not hold the counter; the terminal then falls back to the browser.
 */
public final class NetworkPrinter implements PrinterPort {

    static final int DEFAULT_PORT = 9100;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(5);

    private final String host;
    private final int port;

    public NetworkPrinter(String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("A network printer needs an address (host or host:port)");
        }
        String value = address.strip();
        int colon = value.lastIndexOf(':');
        if (colon > 0 && colon < value.length() - 1 && value.indexOf(':') == colon) {
            this.host = value.substring(0, colon);
            this.port = Integer.parseInt(value.substring(colon + 1));
        } else {
            this.host = value;
            this.port = DEFAULT_PORT;
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Invalid printer port " + port);
        }
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    @Override
    public void print(byte[] payload) {
        try (var socket = new Socket()) {
            socket.setSoTimeout((int) WRITE_TIMEOUT.toMillis());
            socket.connect(new InetSocketAddress(host, port), (int) CONNECT_TIMEOUT.toMillis());
            var out = socket.getOutputStream();
            out.write(payload);
            out.flush();
        } catch (IOException e) {
            throw new PrinterUnavailableException("Imprimante réseau " + host + ":" + port + " injoignable", e);
        }
    }
}
