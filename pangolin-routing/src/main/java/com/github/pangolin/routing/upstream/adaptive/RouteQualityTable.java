package com.github.pangolin.routing.upstream.adaptive;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * Stores per-(destination, server) {@link RouteQuality} instances, modelled after a
 * network routing table: the primary key is the <em>destination</em> and each row
 * holds one quality entry per upstream server.
 *
 * <p>The outer Guava Cache bounds total memory to {@code MAX_DESTINATIONS} distinct
 * destinations via LRU eviction. The inner map is an unbounded
 * {@link ConcurrentHashMap} because the number of upstream servers is always small
 * and fixed.
 */
public class RouteQualityTable {
    private static final int MAX_DESTINATIONS = 5000;

    private final long initialSrtt;
    private final Cache<String, ConcurrentHashMap<String, RouteQuality>> destCache;

    public RouteQualityTable(final long initialSrtt) {
        this.initialSrtt = initialSrtt;
        this.destCache = CacheBuilder.newBuilder()
                .maximumSize(MAX_DESTINATIONS)
                .build();
    }

    /**
     * Gets the existing quality entry or creates a new one with initial values.
     * Use this when recording connection outcomes.
     */
    public RouteQuality getOrCreate(final String serverName, final String destination) {
        try {
            final ConcurrentHashMap<String, RouteQuality> row =
                    destCache.get(destination, ConcurrentHashMap::new);
            return row.computeIfAbsent(serverName, k -> new RouteQuality(initialSrtt));
        } catch (final ExecutionException e) {
            return new RouteQuality(initialSrtt);
        }
    }

    /**
     * Returns the quality entry if it exists, or {@code null} if no data has been recorded yet.
     * Use this during server selection to avoid creating phantom entries.
     */
    public RouteQuality getIfPresent(final String serverName, final String destination) {
        final ConcurrentHashMap<String, RouteQuality> row = destCache.getIfPresent(destination);
        return row != null ? row.get(serverName) : null;
    }

    /**
     * Returns all server quality entries for a given destination, or {@code null} if the
     * destination has no recorded data. Callers may iterate over the returned map to inspect
     * every server in a single cache lookup.
     */
    public ConcurrentHashMap<String, RouteQuality> getRow(final String destination) {
        return destCache.getIfPresent(destination);
    }

    /**
     * Returns a human-readable snapshot of the routing table, one row per
     * (destination, server) pair, sorted by destination string.
     *
     * <p>Example output:
     * <pre>
     * [ROUTE TABLE] 2 destination(s)
     *   claude.com:443          -> server-1            srtt=  10ms  score=0.2323  [OK]
     *                           -> server-2            srtt=   5ms  score=0.0120  [OK]
     *   google.com:80           -> server-1            srtt= 150ms  score=0.0031  [OPEN]
     * </pre>
     */
    public String dumpRoutes() {
        final Map<String, ConcurrentHashMap<String, RouteQuality>> snapshot = destCache.asMap();
        final StringBuilder sb = new StringBuilder();
        sb.append("[ROUTE TABLE] ").append(snapshot.size()).append(" destination(s)\n");

        snapshot.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(destEntry -> {
                    final String dest = destEntry.getKey();
                    final Map<String, RouteQuality> servers = destEntry.getValue();
                    boolean first = true;
                    for (final Map.Entry<String, RouteQuality> e : servers.entrySet()) {
                        final RouteQuality q = e.getValue();
                        final boolean circuitOpen = q.circuitOpenUntil > System.currentTimeMillis();
                        final String state = circuitOpen ? "[OPEN]" : "[OK]  ";
                        sb.append(String.format("  %-26s -> %-20s srtt=%6dms  score=%.4f  %s%n",
                                first ? dest : "",
                                e.getKey(),
                                q.srtt,
                                q.effectiveScore(),
                                state));
                        first = false;
                    }
                });
        return sb.toString();
    }
}
