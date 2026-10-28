package com.pharmacore.followup.domain;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fills a template's {@code {placeholders}}; an unknown placeholder is left as typed, never fails. */
public final class MessageTemplates {

    public static final int MAX_BODY = 600;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z]+)}");

    private MessageTemplates() {
    }

    public static String render(String template, Map<String, String> values) {
        if (template == null || template.isBlank()) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(out);
        String body = out.toString().replaceAll("[ \\t]+", " ").trim();
        return body.length() <= MAX_BODY ? body : body.substring(0, MAX_BODY - 1) + "…";
    }
}
