package io.nats.client.impl;

import io.nats.client.ConnectionListener;
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

public class ApPassiveServerPool implements ApServerPool {
    final ServerPool pool;
    final AtomicReference<NatsUri> activeServerRef;
    final AtomicReference<NatsUri> passiveServerRef;
    final Map<String, List<String>> resolvedMap;
    Options.HostnameResolveMode resolveMode;

    public ApPassiveServerPool() {
        this(new NatsServerPool());
    }

    public ApPassiveServerPool(ServerPool pool) {
        this.pool = pool;
        activeServerRef = new AtomicReference<>();
        passiveServerRef = new AtomicReference<>();
        resolvedMap = new HashMap<>();
    }

    @Override
    public void activeConnectSucceeded(NatsUri nuri) {
        activeServerRef.set(nuri);
        resolve(nuri);
    }

    @Override
    public void passiveConnectSucceeded(NatsUri nuri) {
        passiveServerRef.set(nuri);
        resolve(nuri);
    }

    @Override
    public void activeConnectionEvent(ConnectionListener.Events type, Long time, String uriDetails) {
        // TODO What to do here?
    }

    @Override
    public void passiveConnectionEvent(ConnectionListener.Events type, Long time, String uriDetails) {
        // TODO What to do here?
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

    protected boolean isEquivalent(NatsUri testNuri, NatsUri activeNuri) {
        // Step 1. if they aren't the same port, they are never equivalent, even on the same host
        int testPort = testNuri.getPort();
        int activePort = activeNuri.getPort();
        if (testPort != activePort) {
            return false;
        }
        // At this point, the port is known to be the same

        // Step 2. if same host string (since ports are known to be the same) they are equivalent
        //         reminder *Host can be a name or an ip address.
        String testHost = testNuri.getHost().toLowerCase();
        String activeHost = activeNuri.getHost().toLowerCase();
        if (testHost.equals(activeHost)) {
            return true;
        }

        // Resolve mode means we're allowed to resolve,
        // Meaning host names must be resolved to ip addresses and tested
        if (resolveMode.resolve) {
            boolean activeIsIp = activeNuri.hostIsIpAddress();
            boolean testIsIp = testNuri.hostIsIpAddress();

            // if neither are a host name (both are ip) it would have matched in Step 2
            if (activeIsIp && testIsIp) {
                return false;
            }

            List<String> activeIps = activeIsIp ? Collections.singletonList(activeHost) : _resolveHostToIps(activeHost);
            List<String> testIps = testIsIp ? Collections.singletonList(testHost) : _resolveHostToIps(testHost);
            // disjoint returns true if the two collections have NO elements in common.
            // if the lists of ip addresses have any elements in common, they are equivalent.
            return !Collections.disjoint(activeIps, testIps);
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

    protected @NonNull List<String> _resolveHostToIps(@NonNull String host) {
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
