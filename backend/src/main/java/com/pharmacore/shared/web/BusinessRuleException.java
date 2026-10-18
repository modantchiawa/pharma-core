package com.pharmacore.shared.web;

/** A request that is well-formed but violates a business rule (HTTP 422). */
public class BusinessRuleException extends RuntimeException {

    private final String code;

    public BusinessRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
