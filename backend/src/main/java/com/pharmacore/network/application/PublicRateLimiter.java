package com.pharmacore.network.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sliding-window limiter per client address for the unauthenticated directory: protects the
 * cloud from scraping of the national stock picture. Windows of silent clients are evicted every
 * minute so the map stays bounded. {@link ReentrantLock} keeps virtual threads unpinned.
 */
@Component
public class PublicRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int limit;
    private final Clock clock;

    PublicRateLimiter(NetworkProperties properties, Clock clock) {
        this.limit = properties.queriesPerMinute();
        this.clock = clock;
    }

    public boolean tryAcquire(String client) {
        return windows.computeIfAbsent(client, key -> new Window()).tryAcquire(clock.instant());
    }

    @Scheduled(fixedDelayString = "PT1M")
    void evictIdle() {
        Instant floor = clock.instant().minus(WINDOW);
        windows.entrySet().removeIf(entry -> entry.getValue().idleSince(floor));
    }

    int trackedClients() {
        return windows.size();
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

        boolean idleSince(Instant floor) {
            lock.lock();
            try {
                return hits.isEmpty() || !hits.peekLast().isAfter(floor);
            } finally {
                lock.unlock();
            }
        }
    }
}
