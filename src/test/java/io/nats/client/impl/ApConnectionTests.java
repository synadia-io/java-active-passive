package io.nats.client.impl;

import io.nats.NatsRunnerUtils;
import io.nats.NatsServerRunner;
import io.nats.client.Connection;
import io.nats.client.ForceReconnectOptions;
import io.nats.client.Options;
import io.nats.client.ServerPool;
import io.nats.client.api.ServerInfo;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the passive accessor methods on {@link ApConnection}, focused on the null-safety fix for
 * issue #20: {@code passiveConnection} is null before a connection is fully established and, more
 * importantly, is nulled inside {@code reconnectImplConnect()} as a re-entrancy guard for the
 * (possibly multi-second) duration of a whole-cluster reconnect. Any concurrent caller of a passive
 * accessor while it is null must get a safe default rather than a {@link NullPointerException}.
 * <p>
 * The null-safety tests need no servers: an {@code ApConnection} constructed but not connected already
 * has {@code passiveConnection == null} (only {@code newPassive()} ever assigns it), and the passive
 * accessors read nothing but that field. Only the delegation test - which checks the non-null path -
 * needs live servers.
 */
public class ApConnectionTests {

    static {
        NatsRunnerUtils.setDefaultOutputLevel(Level.SEVERE);
    }

    // An ApConnection that was never connected: passiveConnection is null, which is exactly the state
    // the accessors must tolerate. No servers, no threads started (executors are created on connect).
    private static ApConnection unconnected() {
        ServerPool sp = new ApPassiveServerPool();
        Options options = Options.builder().serverPool(sp).build();
        ApOptions apOptions = ApOptions.builder(options).build();
        return new ApConnection(apOptions, options, sp, sp);
    }

    // ---- issue #20: every passive accessor is null-safe when passiveConnection is null (server-free) ----

    @Test
    public void nullPassive_getPassiveStatus_returnsDisconnected() {
        //noinspection resource unconnected -> ApConnection is AutoCloseable
        assertEquals(Connection.Status.DISCONNECTED, unconnected().getPassiveStatus());
    }

    @Test
    public void nullPassive_getPassiveServers_returnsEmptyNotNull() {
        //noinspection resource unconnected -> ApConnection is AutoCloseable
        Collection<String> servers = unconnected().getPassiveServers();
        assertNotNull(servers);
        assertTrue(servers.isEmpty());
    }

    @Test
    public void nullPassive_getPassiveServerInfo_returnsEmptyInfo() {
        //noinspection resource unconnected -> ApConnection is AutoCloseable
        assertSame(ServerInfo.EMPTY_INFO, unconnected().getPassiveServerInfo());
    }

    @Test
    public void nullPassive_getPassiveConnectedUrl_returnsNull() {
        //noinspection resource unconnected -> ApConnection is AutoCloseable
        assertNull(unconnected().getPassiveConnectedUrl());
    }

    @Test
    public void nullPassive_passiveForceReconnect_noArg_doesNotThrow() {
        //noinspection resource unconnected -> ApConnection is AutoCloseable
        ApConnection apc = unconnected();
        assertDoesNotThrow(() -> apc.passiveForceReconnect());
    }

    @Test
    public void nullPassive_passiveForceReconnect_options_doesNotThrow() {
        //noinspection resource unconnected -> ApConnection is AutoCloseable
        ApConnection apc = unconnected();
        assertDoesNotThrow(() -> apc.passiveForceReconnect(ForceReconnectOptions.DEFAULT_INSTANCE));
    }

    @Test
    public void nullPassive_passiveRTT_doesNotNPE() {
        // The guarantee from issue #20 is "not an NPE." The method may reasonably throw to signal there
        // is no passive to time; it just must not be a NullPointerException.
        //noinspection resource unconnected -> ApConnection is AutoCloseable
        Throwable t = assertThrows(Throwable.class, unconnected()::passiveRTT);
        assertFalse(t instanceof NullPointerException,
            "passiveRTT must not NPE on a null passive, but threw: " + t);
    }

    // ---- happy path: with a live passive, the accessors delegate to it (needs two servers) ----

    @Test
    public void connectedPassive_accessorsDelegate() throws Exception {
        try (NatsServerRunner server1 = new NatsServerRunner();
             NatsServerRunner server2 = new NatsServerRunner()) {
            Options options = Options.builder()
                .servers(new String[]{
                    NatsRunnerUtils.getNatsLocalhostUri(server1.getPort()),
                    NatsRunnerUtils.getNatsLocalhostUri(server2.getPort())
                })
                .build();
            ApOptions apOptions = ApOptions.builder(options).build();
            try (ApConnection apc = ApConnection.connect(apOptions)) {
                assertEquals(Connection.Status.CONNECTED, apc.getPassiveStatus());
                assertFalse(apc.getPassiveServers().isEmpty());
                assertNotSame(ServerInfo.EMPTY_INFO, apc.getPassiveServerInfo());
                assertNotNull(apc.getPassiveConnectedUrl());
                assertNotNull(apc.passiveRTT());
            }
        }
    }
}
