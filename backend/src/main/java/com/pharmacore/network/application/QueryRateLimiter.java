package com.pharmacore.network.application;

import com.pharmacore.shared.context.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * Sliding-window limiter per pharmacy against scraping of partners' stock. Uses
 * {@link ReentrantLock} rather than {@code synchronized} so virtual threads are never pinned.
 * In a multi-node deployment each node enforces the limit locally (bounded by node count).
 */
@Component
class QueryRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final ConcurrentHashMap<TenantId, Window> windows = new ConcurrentHashMap<>();
    private final int limit;
    private final Clock clock;

    QueryRateLimiter(NetworkProperties properties, Clock clock) {
        this.limit = properties.queriesPerMinute();
        this.clock = clock;
    }

    boolean tryAcquire(TenantId tenant) {
        return windows.computeIfAbsent(tenant, key -> new Window()).tryAcquire(clock.instant());
    }

    private final class Window {

        private final ReentrantLock lock = new ReentrantLock();
        private final Deque<Instant> hits = new ArrayDeque<>();

        boolean tryAcquire(Instant now) {
            lock.lock();
            try {
                Instant floor = now.minus(WINDOW);
                while (!hits.isEmpty() && !hits.peekFirst().isAfter(floor)) {
                    hits.pollFirst();
                }
                if (hits.size() >= limit) {
                    return false;
                }
                hits.addLast(now);
                return true;
            } finally {
                lock.unlock();
            }
        }
    }
}
