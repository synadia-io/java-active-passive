package io.nats.client.impl;

import io.nats.NatsRunnerUtils;
import io.nats.NatsServerRunner;
import io.nats.client.ConnectionListener;
import io.nats.client.Options;
import io.nats.client.ServerPool;
import io.nats.client.support.NatsUri;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link ApConnection} drives the {@link ApServerPool} callback methods when a user
 * supplies their own active and passive pool via {@link ApOptions.Builder}.
 * <p>
 * A dummy {@link ApServerPool} records the four callbacks ({@code activeConnectSucceeded},
 * {@code passiveConnectSucceeded}, {@code activeConnectionEvent}, {@code passiveConnectionEvent})
 * and delegates all the actual {@link ServerPool} work to a real {@link NatsServerPool}, so a live
 * connection can be made. Because the active and passive pools are distinct instances, the wiring in
 * {@code ApConnection} feeds both of them every callback.
 */
public class ApServerPoolCallbackTests {

    static {
        NatsRunnerUtils.setDefaultOutputLevel(Level.SEVERE);
    }

    /** Dummy ApServerPool: records callbacks, delegates ServerPool duties to a real NatsServerPool. */
    static class RecordingApServerPool implements ApServerPool {
        final ServerPool delegate = new NatsServerPool();
        final List<NatsUri> activeConnectSucceeded = new ArrayList<>();
        final List<NatsUri> passiveConnectSucceeded = new ArrayList<>();
        final List<ConnectionListener.Events> activeConnectionEvents = new ArrayList<>();
        final List<ConnectionListener.Events> passiveConnectionEvents = new ArrayList<>();

        // ApServerPool callbacks - recorded
        @Override public void activeConnectSucceeded(NatsUri nuri) { activeConnectSucceeded.add(nuri); }
        @Override public void passiveConnectSucceeded(NatsUri nuri) { passiveConnectSucceeded.add(nuri); }
        @Override public void activeConnectionEvent(ConnectionListener.Events type, Long time, String uriDetails) { activeConnectionEvents.add(type); }
        @Override public void passiveConnectionEvent(ConnectionListener.Events type, Long time, String uriDetails) { passiveConnectionEvents.add(type); }

        // ServerPool - delegated so a real connection can be made
        @Override public void initialize(Options opts) { delegate.initialize(opts); }
        @Override public boolean acceptDiscoveredUrls(List<String> discovered) { return delegate.acceptDiscoveredUrls(discovered); }
        @Override public NatsUri peekNextServer() { return delegate.peekNextServer(); }
        @Override public NatsUri nextServer() { return delegate.nextServer(); }
        @Override public List<String> resolveHostToIps(String host) { return delegate.resolveHostToIps(host); }
        @Override public List<String> resolveHostToIps(String host, boolean maxOne, boolean ipv6) { return delegate.resolveHostToIps(host, maxOne, ipv6); }
        @Override public void connectSucceeded(NatsUri nuri) { delegate.connectSucceeded(nuri); }
        @Override public void connectFailed(NatsUri nuri) { delegate.connectFailed(nuri); }
        @Override public List<String> getServerList() { return delegate.getServerList(); }
        @Override public boolean hasSecureServer() { return delegate.hasSecureServer(); }
    }

    @Test
    public void suppliedPoolsReceiveAllCallbacks() throws Exception {
        try (NatsServerRunner server = new NatsServerRunner()) {
            RecordingApServerPool active = new RecordingApServerPool();
            RecordingApServerPool passive = new RecordingApServerPool();

            Options options = Options.builder()
                .server(NatsRunnerUtils.getNatsLocalhostUri(server.getPort()))
                .maxReconnects(2)
                .build();

            ApOptions apOptions = ApOptions.builder(options)
                .activeServerPool(active)
                .passiveServerPool(passive)
                .build();

            try (ApConnection apc = ApConnection.connect(apOptions)) {
                assertTrue(apc.isConnected());

                Thread.sleep(1000); // give time for all the callbacks to happen

                // Both pools are handed the active AND passive connect-succeeded callbacks
                assertFalse(active.activeConnectSucceeded.isEmpty());
                assertFalse(active.passiveConnectSucceeded.isEmpty());
                assertFalse(passive.activeConnectSucceeded.isEmpty());
                assertFalse(passive.passiveConnectSucceeded.isEmpty());

                // The recorded server is the one we actually connected to
                assertEquals(apc.currentServer, active.activeConnectSucceeded.get(0));
                assertEquals(apc.currentServer, passive.activeConnectSucceeded.get(0));

                // Both pools are handed the active AND passive connection events, including CONNECTED
                assertTrue(active.activeConnectionEvents.contains(ConnectionListener.Events.CONNECTED));
                assertTrue(active.passiveConnectionEvents.contains(ConnectionListener.Events.CONNECTED));
                assertTrue(passive.activeConnectionEvents.contains(ConnectionListener.Events.CONNECTED));
                assertTrue(passive.passiveConnectionEvents.contains(ConnectionListener.Events.CONNECTED));
            }
        }
    }
}
