package com.github.pangolin.routing.upstream.spi;

import com.github.pangolin.routing.upstream.AbstractUpstream;
import com.github.pangolin.routing.upstream.Upstream;
import com.github.pangolin.routing.upstream.UpstreamCombiner;
import com.github.pangolin.routing.upstream.UpstreamRegistry;
import com.github.pangolin.routing.upstream.adaptive.RouteQuality;
import com.github.pangolin.routing.upstream.adaptive.RouteQualityHandler;
import com.github.pangolin.routing.upstream.adaptive.RouteQualityTable;
import io.netty.channel.ChannelHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Adaptive load balancer implementing per-(server, destination) quality tracking via EWMA RTT
 * with circuit breaker and exploration-exploitation selection.
 *
 * <p>Selection strategy:
 * <ol>
 *   <li>Exploration (P_EXPLORE=0.60): if any server has no recorded data for this destination,
 *       randomly pick one of them.</li>
 *   <li>Exploitation: score-proportional random sampling weighted by {@code 1/(srtt + 4*rttvar)}.
 *       Circuit-open servers get score=0 and are excluded. If ALL servers are circuit-open,
 *       returns {@code null} (no server available).</li>
 * </ol>
 */
@Slf4j
public class UpstreamAdaptiveFactory implements UpstreamCombiner {

    private static final double P_EXPLORE = 0.60;
    /** Assumed connect timeout / 2 (ms). Default: 10s timeout → 5s initial srtt. */
    private static final long INITIAL_SRTT_MS = 5_000L;
    /** Penalty for a failed connection = 2 × assumed connect timeout (ms). */
    private static final long PENALTY_MS = 20_000L;
    private static final int CIRCUIT_OPEN_THRESHOLD = 5;
    private static final long CIRCUIT_BASE_MS = 30_000L;
    private static final long CIRCUIT_MAX_MS = 600_000L;

    @Override
    public String name() {
        return "select";
    }

    @Override
    public Upstream combine(final String name, final Iterable<String> names, final UpstreamRegistry registry) {
        final RouteQualityTable qualityTable = new RouteQualityTable(INITIAL_SRTT_MS);
        final List<String> nameList = StreamSupport.stream(names.spliterator(), false)
                .collect(Collectors.toList());

        return new AbstractUpstream(name) {

            @Override
            public SocketAddress address() {
                return null;
            }

            @Override
            public boolean isVirtual() {
                return true;
            }

            @Override
            public boolean isAvailable() {
                return nameList.stream()
                        .map(registry::getUpstream)
                        .filter(Objects::nonNull)
                        .anyMatch(Upstream::isAvailable);
            }

            /**
             * Returns only the upstream proxy handler (no quality tracking).
             * Prefer {@link #newSocketProxyHandlers} for full adaptive behavior.
             */
            @Override
            public ChannelHandler newSocketProxyHandler(final InetSocketAddress destination) {
                final Upstream selected = select(destination);
                if (selected == null) {
                    log.warn("[SELECT] {} => [{}] No available server", stringify(destination), name);
                    return null;
                }
                log.info("[SELECT] {} => [{}] => [{}]", stringify(destination), name, selected.name());
                return selected.newSocketProxyHandler(destination);
            }

            /**
             * Returns {@code [proxyHandler, qualityHandler]} so that quality metrics are
             * recorded for this connection. Both handlers are added to the upstream channel pipeline.
             */
            @Override
            public ChannelHandler[] newSocketProxyHandlers(final InetSocketAddress destination) {
                final Upstream selected = select(destination);
                if (selected == null) {
                    log.warn("[SELECT] {} No available server", stringify(destination));
                    return new ChannelHandler[0];
                }
                if (log.isDebugEnabled()) {
                    log.debug("[SELECT] {} => \n{}", stringify(destination), qualityTable.dumpRoutes());
                }
                log.info("[SELECT] {} => [{}] => [{}]", stringify(destination), name, selected.name());
                final ChannelHandler proxy = selected.newSocketProxyHandler(destination);
                if (proxy == null) {
                    return new ChannelHandler[0];
                }
                final RouteQualityHandler qualityHandler = new RouteQualityHandler(
                        selected.name(), destination, qualityTable,
                        PENALTY_MS, CIRCUIT_OPEN_THRESHOLD, CIRCUIT_BASE_MS, CIRCUIT_MAX_MS);
                return new ChannelHandler[]{proxy, qualityHandler};
            }

            @Override
            public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
                final Upstream selected = select(destination);
                if (selected == null) {
                    log.warn("[SELECT] {} No available server", stringify(destination));
                    return null;
                }
                return selected.newDatagramProxyHandler(destination);
            }

            private Upstream select(final InetSocketAddress destination) {
                final String dest = stringify(destination);
                final List<Upstream> available = nameList.stream()
                        .map(registry::getUpstream)
                        .filter(Objects::nonNull)
                        .filter(Upstream::isAvailable)
                        .collect(Collectors.toList());

                if (available.isEmpty()) {
                    return null;
                }

                // Phase 1: Exploration — pick an untried server for this destination.
                // A server is "untried" if it has no quality entry (null) or nTried==0
                // (entry created but no completed connection attempt yet).
                final List<Upstream> untried = available.stream()
                        .filter(u -> {
                            final RouteQuality q = qualityTable.getIfPresent(u.name(), dest);
                            return q == null || q.isUntried();
                        })
                        .collect(Collectors.toList());
                if (!untried.isEmpty() && ThreadLocalRandom.current().nextDouble() < P_EXPLORE) {
                    return untried.get(ThreadLocalRandom.current().nextInt(untried.size()));
                }

                // Phase 2: Score-proportional random sampling
                return scoreProportionalRandom(available, dest);
            }

            private Upstream scoreProportionalRandom(final List<Upstream> servers, final String dest) {
                final java.util.concurrent.ConcurrentHashMap<String, RouteQuality> row =
                        qualityTable.getRow(dest);
                final double[] scores = new double[servers.size()];
                double total = 0.0;
                for (int i = 0; i < servers.size(); i++) {
                    final RouteQuality q = row != null ? row.get(servers.get(i).name()) : null;
                    final double sc = (q != null) ? q.effectiveScore() : (1.0 / INITIAL_SRTT_MS);
                    scores[i] = sc;
                    total += sc;
                }
                if (total <= 0.0) {
                    // All servers are circuit-open; no server available
                    return null;
                }
                final double r = ThreadLocalRandom.current().nextDouble() * total;
                double cumulative = 0.0;
                for (int i = 0; i < servers.size(); i++) {
                    cumulative += scores[i];
                    if (r < cumulative) {
                        Upstream upstream = servers.get(i);
                        log.debug("[SELECT] {} => [{}] => [{}] score: {}", dest, name, upstream.name(), scores[i]);
                        return upstream;
                    }
                }
                return servers.get(servers.size() - 1);
            }

            @Override
            public String toString() {
                return name + "/adaptive," + nameList;
            }
        };
    }

    private String stringify(final InetSocketAddress destination) {
        return destination.getHostString() + ":" + destination.getPort();
    }
}
