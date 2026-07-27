package io.nats.client.impl;

import io.nats.NatsRunnerUtils;
import io.nats.NatsServerRunner;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.ForceReconnectOptions;
import io.nats.client.Options;
import io.nats.client.support.Listener;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

public class ApTests {

    static {
        NatsRunnerUtils.setDefaultOutputLevel(Level.SEVERE);
    }

    static class OptionsHelper {
        final Options options;
        final Listener activeListener;
        final Listener passiveListener;
        final ApOptions apOptions;

        public OptionsHelper(String[] servers) {
            this(Options.builder().servers(servers));
        }

        public OptionsHelper(Options.Builder builder) {
            activeListener = new Listener().label("active");
            passiveListener = new Listener().label("passive");
            this.options = builder
                .connectionListener(activeListener)
                .errorListener(activeListener)
                .maxReconnects(2)
                .build();
            apOptions = ApOptions.builder(options)
                .passiveConnectionListener(passiveListener)
                .build();

            activeListener.queueConnectionEvent(ConnectionListener.Events.CONNECTED);
            passiveListener.queueConnectionEvent(ConnectionListener.Events.CONNECTED);
        }

        void validateConnected() {
            activeListener.validate();
            passiveListener.validate();
        }
    }

    private static OptionsHelper getHelper(int... ports) {
        String[] servers = new String[ports.length];
        for (int i = 0; i < ports.length; i++) {
            servers[i] = NatsRunnerUtils.getNatsLocalhostUri(ports[i]);
        }
        return new OptionsHelper(servers);
    }

    private static OptionsHelper getHelper(NatsServerRunner... runners) {
        int[] ports = new int[runners.length];
        for (int i = 0; i < runners.length; i++) {
            ports[i] = runners[i].getPort();
        }
        return getHelper(ports);
    }

    private static ApOptions getApOptions(int... ports) {
        return getHelper(ports).apOptions;
    }

    private static ApOptions getApOptions(NatsServerRunner... runners) {
        return getHelper(runners).apOptions;
    }

    private static Options getOptions(int... ports) {
        return getHelper(ports).options;
    }

    private static Options getOptions(NatsServerRunner... runners) {
        return getHelper(runners).options;
    }

    @Test
    public void testAllBadServers() throws Exception {
        ApOptions apOptions = getApOptions(4444, 5555); // server ports that won't exist
        //noinspection resource
        IOException ioe = assertThrows(IOException.class, () -> ApConnection.connect(apOptions));
        assertTrue(ioe.getMessage().contains("Active"));
    }

    @Test
    public void testSomeBadServers() throws Exception {
        try (NatsServerRunner server1 = new NatsServerRunner()) {
            try (NatsServerRunner server2 = new NatsServerRunner()) {
                // A dead server plus two good ones. The active takes a good server (skipping the dead one),
                // and the passive must take a DISTINCT good server - a different port is no longer treated
                // as equivalent, so the passive will not co-locate with the active. (Two good servers are
                // required: with only one, the passive has no distinct server to connect to and the connect
                // fails - which is the corrected behavior.)
                OptionsHelper helper = getHelper(4444, server1.getPort(), server2.getPort());
                try (ApConnection apc = ApConnection.connect(helper.apOptions)) {
                    helper.validateConnected();
                    assertNotEquals(
                        apc.getServerInfo().getServerId(),
                        apc.getPassiveServerInfo().getServerId());
                }
                catch (InterruptedException | IOException e) {
                    fail();
                }
            }
        }
    }

    @Test
    public void testPassiveRequiresDistinctServer() throws Exception {
        try (NatsServerRunner server1 = new NatsServerRunner()) {
            // A dead server plus ONE good server. The active takes the good server, but the passive has
            // no DISTINCT server to take - co-locating with the active is no longer allowed - so the
            // bootstrap fails. (This is the behavior that replaced the old faulty co-location.)
            ApOptions apOptions = getApOptions(4444, server1.getPort());
            //noinspection resource
            RuntimeException e = assertThrows(RuntimeException.class, () -> ApConnection.connect(apOptions));
            assertTrue(e.getMessage().contains("Passive"));
        }
    }

    @Test
    public void testConnect() throws Exception {
        try (NatsServerRunner server1 = new NatsServerRunner()) {
            OptionsHelper helper = getHelper(server1);

            try (ApConnection apc = ApConnection.connect(helper.apOptions)) {
                helper.activeListener.validate();
                helper.passiveListener.validate();
                helper.activeListener.queueConnectionEvent(ConnectionListener.Events.CLOSED);
                helper.passiveListener.queueConnectionEvent(ConnectionListener.Events.CLOSED);
            }
            catch (InterruptedException | IOException e) {
                fail();
            }
            helper.activeListener.validate();
            helper.passiveListener.validate();
        }
    }

    @Test
    public void testServerPoolBehavior() throws Exception {
        try (NatsServerRunner server1 = new NatsServerRunner()) {
            // only 1 server, nothing we can do
            // to prevent passive from being the same as active
            // this confirms that ApPassiveServerPool works
            OptionsHelper helper = getHelper(server1);
            try (ApConnection apc = ApConnection.connect(helper.apOptions)) {
                helper.validateConnected();
                assertEquals(
                    apc.getServerInfo().getServerId(),
                    apc.getPassiveServerInfo().getServerId());
            }
            catch (InterruptedException | IOException e) {
                fail();
            }

            try (NatsServerRunner server2 = new NatsServerRunner()) {
                try (NatsServerRunner server3 = new NatsServerRunner()) {
                    // make sure passive never is the same as active
                    helper = getHelper(server1, server2, server3);
                    try (ApConnection apc = ApConnection.connect(helper.apOptions)) {
                        helper.validateConnected();
                        assertNotEquals(
                            apc.getServerInfo().getServerId(),
                            apc.getPassiveServerInfo().getServerId());

                        apc.passiveForceReconnect();
                        assertNotEquals(
                            apc.getServerInfo().getServerId(),
                            apc.getPassiveServerInfo().getServerId());
                    }
                    catch (InterruptedException | IOException e) {
                        fail();
                    }
                }
            }
        }
    }

    // A promoted passive is not a lost passive - its socket is alive and now serving the active - so
    // the user's passive ConnectionListener must never see CLOSED for it. Checked by count rather than
    // by a queued future so it reads as "this never happened" instead of "this hasn't happened yet".
    private static void assertNoPromotionClosed(OptionsHelper helper) {
        assertEquals(0, helper.passiveListener.getConnectionEventCount(ConnectionListener.Events.CLOSED),
            "the promoted passive must not report CLOSED to the passive listener");
    }

    @Test
    public void testForceReconnect() throws Exception {
        try (NatsServerRunner server1 = new NatsServerRunner()) {
            try (NatsServerRunner server2 = new NatsServerRunner()) {
                try (NatsServerRunner server3 = new NatsServerRunner()) {
                    Options.Builder builder = new Options.Builder(getOptions(server1, server2, server3))
                        .noRandomize();
                    OptionsHelper helper = new OptionsHelper(builder);
                    try (ApConnection apc = ApConnection.connect(helper.apOptions)) {
                        helper.validateConnected();
                        assertNotEquals(
                            apc.getServerInfo().getServerId(),
                            apc.getPassiveServerInfo().getServerId());

                        helper.activeListener.queueConnectionEvent(ConnectionListener.Events.DISCONNECTED);
                        helper.activeListener.queueConnectionEvent(ConnectionListener.Events.RECONNECTED);
                        // Only CONNECTED for the new passive. The promoted passive must NOT report
                        // CLOSED - see assertNoPromotionClosed.
                        helper.passiveListener.queueConnectionEvent(ConnectionListener.Events.CONNECTED);
                        apc.forceReconnect(ForceReconnectOptions.FORCE_CLOSE_INSTANCE);
                        helper.activeListener.validateAll();
                        helper.passiveListener.validateAll();
                        assertNoPromotionClosed(helper);

                        assertNotEquals(
                            apc.getServerInfo().getServerId(),
                            apc.getPassiveServerInfo().getServerId());
                    }
                    catch (InterruptedException | IOException e) {
                        fail();
                    }
                }
            }
        }
    }

    @Test
    public void testSwitchToPassive() throws Exception {
        try (NatsServerRunner server1 = new NatsServerRunner()) {
            try (NatsServerRunner server2 = new NatsServerRunner()) {
                try (NatsServerRunner server3 = new NatsServerRunner()) {
                    Options.Builder builder = new Options.Builder(getOptions(server1, server2, server3))
                        .noRandomize();
                    OptionsHelper helper = new OptionsHelper(builder);
                    try (ApConnection apc = ApConnection.connect(helper.apOptions)) {
                        helper.validateConnected();

                        String originalActiveId = apc.getServerInfo().getServerId();
                        String originalPassiveId = apc.getPassiveServerInfo().getServerId();
                        assertNotEquals(originalActiveId, originalPassiveId);

                        long pingsBefore = apc.getStatistics().getPings();

                        // Stand in for the active having delivered a message just before its socket
                        // failed - deliverMessage clears needPing. That flag describes the socket we are
                        // about to throw away, so it must not be allowed to suppress the ping on the
                        // promoted one.
                        apc.needPing.set(false);

                        helper.activeListener.queueConnectionEvent(ConnectionListener.Events.DISCONNECTED);
                        helper.activeListener.queueConnectionEvent(ConnectionListener.Events.RECONNECTED);
                        helper.passiveListener.queueConnectionEvent(ConnectionListener.Events.CONNECTED);

                        apc.switchToPassive();

                        helper.activeListener.validateAll();
                        helper.passiveListener.validateAll();
                        assertNoPromotionClosed(helper);

                        // the switch put a PING on the promoted socket, the way a normal connect does
                        assertTrue(apc.getStatistics().getPings() > pingsBefore,
                            "the switch did not send a ping on the promoted socket");

                        // the active is now serving the socket the passive was holding
                        assertEquals(originalPassiveId, apc.getServerInfo().getServerId());

                        // and a fresh passive was armed on some other server
                        assertNotEquals(
                            apc.getServerInfo().getServerId(),
                            apc.getPassiveServerInfo().getServerId());

                        // the promoted socket really works - this round trips over it
                        assertNotNull(apc.RTT());
                    }
                    catch (InterruptedException | IOException e) {
                        fail();
                    }
                }
            }
        }
    }

    @Test
    public void testSwitchToPassiveNoPassive() throws Exception {
        try (NatsServerRunner server1 = new NatsServerRunner()) {
            try (NatsServerRunner server2 = new NatsServerRunner()) {
                OptionsHelper helper = getHelper(server1, server2);
                try (ApConnection apc = ApConnection.connect(helper.apOptions)) {
                    helper.validateConnected();

                    // take the passive away, there is nothing to switch to
                    apc.passiveConnection.close();
                    assertThrows(IllegalStateException.class, apc::switchToPassive);

                    // and the active was left alone - we check before letting go of it
                    assertEquals(Connection.Status.CONNECTED, apc.getStatus());
                    assertNotNull(apc.RTT());
                }
                catch (InterruptedException | IOException e) {
                    fail();
                }
            }
        }
    }

    // The dead socket usually has unanswered PINGs on it - that is often WHY it is being failed away
    // from. Carrying them into the promoted connection means the next softPing sees
    // pongQueue.size() + 1 > maxPingsOut and raises "Max outgoing Ping count exceeded", which spawns a
    // reconnect on the connection we just failed over to. The switch must discard them.
    @Test
    public void testSwitchToPassiveClearsOutstandingPings() throws Exception {
        try (NatsServerRunner server1 = new NatsServerRunner()) {
            try (NatsServerRunner server2 = new NatsServerRunner()) {
                try (NatsServerRunner server3 = new NatsServerRunner()) {
                    Options.Builder builder = new Options.Builder(getOptions(server1, server2, server3))
                        .noRandomize();
                    OptionsHelper helper = new OptionsHelper(builder);
                    try (ApConnection apc = ApConnection.connect(helper.apOptions)) {
                        helper.validateConnected();

                        // stand in for PINGs written to the active's socket whose PONGs never came back.
                        // Fill to maxPingsOut so that inheriting even one of them would trip sendPing.
                        int maxPingsOut = helper.options.getMaxPingsOut();
                        assertTrue(maxPingsOut > 0);
                        List<CompletableFuture<Boolean>> orphaned = new ArrayList<>();
                        for (int i = 0; i < maxPingsOut; i++) {
                            CompletableFuture<Boolean> pongFuture = new CompletableFuture<>();
                            orphaned.add(pongFuture);
                            apc.pongQueue.add(pongFuture);
                        }
                        assertEquals(maxPingsOut, apc.pongQueue.size());

                        helper.activeListener.queueConnectionEvent(ConnectionListener.Events.DISCONNECTED);
                        helper.activeListener.queueConnectionEvent(ConnectionListener.Events.RECONNECTED);
                        helper.passiveListener.queueConnectionEvent(ConnectionListener.Events.CONNECTED);

                        apc.switchToPassive();

                        helper.activeListener.validateAll();
                        helper.passiveListener.validateAll();

                        // The promoted connection did not inherit the dead socket's pings, and whoever
                        // was waiting on them was released rather than left parked.
                        // Asserted per future rather than as pongQueue.isEmpty(): the switch sends a
                        // fresh PING on the promoted socket, so the queue legitimately holds that one
                        // until its PONG lands.
                        for (CompletableFuture<Boolean> pongFuture : orphaned) {
                            assertFalse(apc.pongQueue.contains(pongFuture),
                                "an orphaned pong future survived the switch");
                            assertTrue(pongFuture.isDone(),
                                "an orphaned pong future was left uncompleted");
                        }

                        // a real ping round trip still works, so the ping budget was not exhausted
                        assertNotNull(apc.RTT());
                        assertEquals(Connection.Status.CONNECTED, apc.getStatus());

                        // no second failover was provoked by "Max outgoing Ping count exceeded"
                        assertEquals(1,
                            helper.activeListener.getConnectionEventCount(ConnectionListener.Events.RECONNECTED));
                    }
                    catch (InterruptedException | IOException e) {
                        fail();
                    }
                }
            }
        }
    }
}
