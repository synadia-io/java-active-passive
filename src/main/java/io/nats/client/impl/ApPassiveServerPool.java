package io.nats.client.impl;

import io.nats.client.Options;
import io.nats.client.ServerPool;
import io.nats.client.support.NatsUri;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class ApPassiveServerPool implements ServerPool {
    final ServerPool pool;
    final AtomicReference<NatsUri> activeServerRef;

    public ApPassiveServerPool(ServerPool pool) {
        this.pool = pool;
        activeServerRef = new AtomicReference<>();
    }

    public void setActiveServer(NatsUri activeNuri) {
        activeServerRef.set(activeNuri);
    }

    @Override
    public void initialize(@NonNull Options opts) {
        pool.initialize(opts);
    }

    @Override
    public boolean acceptDiscoveredUrls(@NonNull List<@NonNull String> discoveredServers) {
        return pool.acceptDiscoveredUrls(discoveredServers);
    }

    @Override
    public @Nullable NatsUri peekNextServer() {
        NatsUri active = activeServerRef.get();
        if (active == null) {
            return pool.peekNextServer();
        }

        NatsUri firstPeek = pool.peekNextServer();
        NatsUri peek = firstPeek;
        while (peek != null && peek.equivalent(active)) {
            pool.nextServer(); // advance and peek again
            peek = pool.peekNextServer();
            if (peek == firstPeek) { // if we've looped around, nothing else we can do
                break;
            }
        }
        return peek;
    }

    @Override
    public @Nullable NatsUri nextServer() {
        NatsUri active = activeServerRef.get();
        if (active == null) {
            return pool.nextServer();
        }
        NatsUri firstServer = pool.nextServer();
        NatsUri server = firstServer;
        while (server != null && server.equivalent(active)) {
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
