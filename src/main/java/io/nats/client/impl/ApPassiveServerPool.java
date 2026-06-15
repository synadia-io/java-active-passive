package io.nats.client.impl;

import io.nats.client.Options;
import io.nats.client.ServerPool;
import io.nats.client.support.NatsUri;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.URISyntaxException;
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

    private void resolve(NatsUri nuri) {
        if (!nuri.hostIsIpAddress() && !nuri.isWebsocket() && resolveMode.resolve) {
            String host = nuri.getHost();
            List<String> resolved = resolvedMap.get(host);
            if (resolved == null) {
                resolved = pool.resolveHostToIps(host, resolveMode.maxOneResult, resolveMode.includeIPV6);
                if (resolved != null && !resolved.isEmpty()) {
                    resolvedMap.put(host, resolved);
                }
            }
        }
    }

    private void resolve(@NonNull List<String> serverList) {
        for (String server : serverList) {
            try {
                resolve(new NatsUri(server));
            }
            catch (URISyntaxException e) {
                // ignore, sorry nothing we can do
            }
        }
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
        String activeHost = active.getHost();
        if (test.getHost().equals(activeHost)) {
            return true;
        }
        if (resolveMode.resolve) {
            List<String> testResolved = resolvedMap.get(test.getHost());
            if (testResolved != null) {
                List<String> activeResolved = resolvedMap.get(active.getHost());
                if (activeResolved != null) {
                    for (String resolved : testResolved) {
                        if (activeResolved.contains(resolved) || resolved.equals(activeHost)) {
                            return true;
                        }
                    }
                }
            }
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
        return pool.resolveHostToIps(host);
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
