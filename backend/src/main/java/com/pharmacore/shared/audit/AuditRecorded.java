package com.pharmacore.shared.audit;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Published synchronously, inside the business transaction, for every audit event written. Lets
 * detective controls (alerts) react to what just happened without each module calling them; a
 * rolled-back operation takes its reactions with it.
 */
public record AuditRecorded(AuditRecord record, Map<String, String> details) {

    public AuditRecorded {
        details = details == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(details));
    }

    public String action() {
        return record.action();
    }

    public String detail(String key) {
        return details.get(key);
    }
}
