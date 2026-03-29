package com.github.pangolin.routing.upstream.adaptive;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * Stores per-(server, destination) {@link RouteQuality} instances.
 *
 * <p>Uses a per-server LRU cache (Guava Cache) to bound memory usage to
 * {@code MAX_DESTINATIONS} entries per server.
 */
public class RouteQualityTable {
    private static final int MAX_DESTINATIONS = 5000;

    private final long initialSrtt;
    private final ConcurrentHashMap<String, Cache<String, RouteQuality>> serverCaches = new ConcurrentHashMap<>();

    public RouteQualityTable(final long initialSrtt) {
        this.initialSrtt = initialSrtt;
    }

    /**
     * Gets the existing quality entry or creates a new one with initial values.
     * Use this when recording connection outcomes.
     */
    public RouteQuality getOrCreate(final String serverName, final String destination) {
        final Cache<String, RouteQuality> cache = serverCaches.computeIfAbsent(serverName, k ->
                CacheBuilder.newBuilder()
                        .maximumSize(MAX_DESTINATIONS)
                        .build());
        try {
            return cache.get(destination, () -> new RouteQuality(initialSrtt));
        } catch (final ExecutionException e) {
            return new RouteQuality(initialSrtt);
        }
    }

    /**
     * Returns the quality entry if it exists, or {@code null} if no data has been recorded yet.
     * Use this during server selection to avoid creating phantom entries.
     */
    public RouteQuality getIfPresent(final String serverName, final String destination) {
        final Cache<String, RouteQuality> cache = serverCaches.get(serverName);
        return cache != null ? cache.getIfPresent(destination) : null;
    }
}
