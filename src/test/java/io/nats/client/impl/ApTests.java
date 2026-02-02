package io.nats.client.impl;

import io.nats.NatsRunnerUtils;
import io.nats.NatsServerRunner;
import io.nats.client.ConnectionListener;
import io.nats.client.ForceReconnectOptions;
import io.nats.client.Options;
import io.nats.client.support.Listener;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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
    public void testNoServer() throws Exception {
        ApOptions apOptions = getApOptions(4231); // just a server port that won't exist
        //noinspection resource
        assertThrows(IOException.class, () -> ApConnection.connect(apOptions));
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
                // make sure passive never is the same as active
                helper = getHelper(server1, server2);
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
                        helper.passiveListener.queueConnectionEvent(ConnectionListener.Events.CLOSED);
                        helper.passiveListener.queueConnectionEvent(ConnectionListener.Events.CONNECTED);
                        apc.forceReconnect(ForceReconnectOptions.FORCE_CLOSE_INSTANCE);
                        helper.activeListener.validateAll();
                        helper.passiveListener.validateAll();

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
}
