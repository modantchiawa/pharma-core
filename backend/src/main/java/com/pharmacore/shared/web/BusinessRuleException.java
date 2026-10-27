package com.pharmacore.shared.web;

import java.util.Map;

/** A request that is well-formed but violates a business rule (HTTP 422). */
public class BusinessRuleException extends RuntimeException {

    private final String code;
    private final Map<String, Object> properties;

    public BusinessRuleException(String code, String message) {
        this(code, message, Map.of());
    }

    /** @param properties extra members of the problem detail (what the client needs to react) */
    public BusinessRuleException(String code, String message, Map<String, Object> properties) {
        super(message);
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    public String code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
