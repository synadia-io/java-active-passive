package io.nats.client.impl;

import io.nats.client.Options;
import io.nats.client.ServerPool;
import io.nats.client.support.NatsUri;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class ApPassiveServerPool implements ServerPool {
    final ServerPool pool;
    final AtomicReference<NatsUri> activeServerRef;
    final Map<String, List<String>> resolvedMap;
    Options.HostnameResolveMode resolveMode;

    public ApPassiveServerPool(ServerPool pool) {
        this.pool = pool;
        activeServerRef = new AtomicReference<>();
        resolvedMap = new HashMap<>();
    }

    public void setActiveServer(NatsUri activeNuri) {
        activeServerRef.set(activeNuri);
        resolve(activeNuri);
    }

    @Override
    public void initialize(@NonNull Options opts) {
        resolveMode = opts.hostnameResolveMode();
        pool.initialize(opts);
        resolve(pool.getServerList());
    }

    @Override
    public boolean acceptDiscoveredUrls(@NonNull List<@NonNull String> discoveredServers) {
        boolean accepted = pool.acceptDiscoveredUrls(discoveredServers);
        if (accepted) {
            resolve(pool.getServerList());
        }
        return accepted;
    }

    @Override
    public @Nullable NatsUri peekNextServer() {
        NatsUri active = activeServerRef.get();
        if (active == null) {
            return pool.peekNextServer();
        }

        NatsUri firstPeek = pool.peekNextServer();
        NatsUri peek = firstPeek;
        while (peek != null && isEquivalent(peek, active)) {
            pool.nextServer(); // advance and peek again
            peek = pool.peekNextServer();
            if (peek == firstPeek) { // if we've looped around, nothing else we can do
                break;
            }
        }
        return peek;
    }

    private boolean isEquivalent(NatsUri test, NatsUri active) {
        String testHost = test.getHost();
        String activeHost = active.getHost();
        if (testHost.equals(activeHost)) {
            return true;
        }
        if (resolveMode.resolve) {
            // The NATS server reports discovered servers as IP addresses, while users typically
            // supply hostnames - so a comparison is always host-vs-IP, never hostname-vs-hostname
            // by resolution (two distinct hostnames only match by the direct equality check above).
            if (active.hostIsIpAddress()) {
                // active is an IP: equivalent only to a test hostname that resolves to that IP.
                return !test.hostIsIpAddress() && _resolveHostToIps(testHost).contains(activeHost);
            }
            // active is a hostname: equivalent only to a test IP that is one of its resolved IPs.
            return test.hostIsIpAddress() && _resolveHostToIps(activeHost).contains(testHost);
        }
        return false;
    }

    @Override
    public @Nullable NatsUri nextServer() {
        NatsUri active = activeServerRef.get();
        if (active == null) {
            return pool.nextServer();
        }
        NatsUri firstServer = pool.nextServer();
        NatsUri server = firstServer;
        while (server != null && isEquivalent(server, active)) {
            server = pool.nextServer(); // get the next nextServer
            if (server == firstServer) { // if we've looped around, nothing else we can do
                break;
            }
        }
        return server;
    }

    @Override
    public @Nullable List<String> resolveHostToIps(@NonNull String host) {
        return _resolveHostToIps(host);
    }

    private @NonNull List<String> _resolveHostToIps(@NonNull String host) {
        List<String> resolved = resolvedMap.get(host);
        if (resolved == null) {
            resolved = pool.resolveHostToIps(host, resolveMode.maxOneResult, resolveMode.includeIPV6);
            if (resolved == null || resolved.isEmpty()) {
                // placeholder so we don't keep re-resolving a host that resolves to nothing
                resolved = Collections.emptyList();
            }
            resolvedMap.put(host, resolved);
        }
        return resolved;
    }

    private void resolve(NatsUri nuri) {
        if (!nuri.hostIsIpAddress() && !nuri.isWebsocket() && resolveMode.resolve) {
            _resolveHostToIps(nuri.getHost());
        }
    }

    private void resolve(@NonNull List<String> serverList) {
        for (String server : serverList) {
            try {
                resolve(new NatsUri(server));
            }
            catch (URISyntaxException e) {
                throw new RuntimeException(e); // this should never happen, if it does it's a user error
            }
        }
    }

    @Override
    public void connectSucceeded(@NonNull NatsUri nuri) {
        pool.connectSucceeded(nuri);
    }

    @Override
    public void connectFailed(@NonNull NatsUri nuri) {
        pool.connectFailed(nuri);
    }

    @Override
    public @NonNull List<String> getServerList() {
        return pool.getServerList();
    }

    @Override
    public boolean hasSecureServer() {
        return pool.hasSecureServer();
    }
}
