package com.pharmacore.network.application;

import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Aggregated trace of the public directory: how many searches of each kind, how many answered
 * with results. One audit event every few minutes, never an address or a query in clear.
 */
@Component
public class PublicDirectoryUsage {

    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final AuditTrail audit;
    private final TransactionTemplate tx;

    public PublicDirectoryUsage(AuditTrail audit, PlatformTransactionManager transactionManager) {
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public void count(String kind, boolean answered) {
        counters.computeIfAbsent(kind, key -> new LongAdder()).increment();
        if (answered) {
            counters.computeIfAbsent(kind + ".answered", key -> new LongAdder()).increment();
        }
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
    public void flush() {
        Map<String, String> snapshot = new TreeMap<>();
        counters.forEach((kind, adder) -> {
            long value = adder.sumThenReset();
            if (value > 0) {
                snapshot.put(kind, Long.toString(value));
            }
        });
        if (snapshot.isEmpty()) {
            return;
        }
        var context = RequestContext.of(Actor.SystemProcess.crossTenant("public-directory"));
        TenantContext.run(context, () -> tx.executeWithoutResult(status ->
                audit.record("PUBLIC_DIRECTORY_USAGE", "DIRECTORY", "public", snapshot)));
    }
}
