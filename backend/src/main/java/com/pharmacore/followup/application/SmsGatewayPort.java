package com.pharmacore.followup.application;

import java.util.Optional;

/** Outbound port to the pharmacy's SMS provider; the adapter is chosen by the pharmacy's settings. */
public interface SmsGatewayPort {

    record Gateway(String url, String token, String sender) {
    }

    /** @return the failure, or empty when the provider accepted the message */
    Optional<String> send(Gateway gateway, String internationalDigits, String body);
}
