package io.nats.client.impl;

import io.nats.client.*;
import io.nats.client.api.ServerInfo;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ApConnection extends NatsConnection {

    final ApOptions apOptions;
    final Options passiveOptions;  // since we may be making passive more than once
    final ApServerPool activeServerPool;
    final ApServerPool passiveServerPool;

    NatsConnection passiveConnection;

    public static ApConnection connect(ApOptions apOptions) throws IOException, InterruptedException {
        if (apOptions == null) {
            apOptions = ApOptions.builder().build();
        }

        Options.Builder activeBuilder = new Options.Builder(apOptions.options);

        // this set's up the server pool here instead of waiting for the
        // NatsConnection constructor to do it.
        ServerPool activeSp = apOptions.activeServerPool;
        if (activeSp == null) {
            activeSp = apOptions.options.getServerPool();
        }
        if (activeSp == null) {
            activeSp = new ApPassiveServerPool();
        }
        else if (!(activeSp instanceof ApServerPool)) {
            activeSp = new ApPassiveServerPool(activeSp);
        }
        activeBuilder.serverPool(activeSp);

        ServerPool passiveSp = apOptions.passiveServerPool;
        if (passiveSp == null) {
            passiveSp = activeSp;
        }

        ApConnection apc = new ApConnection(apOptions, activeBuilder.build(), activeSp, passiveSp);
        apc.connect();
        return apc;
    }

    /* inner */ class BridgeConnectionListener implements ConnectionListener {
        final boolean activeListener;

        public BridgeConnectionListener(boolean activeListener) {
            this.activeListener = activeListener;
        }

        @Override
        public void connectionEvent(Connection conn, Events type) {
            connectionEvent(conn, type, null, null);
        }

        @Override
        public void connectionEvent(Connection conn, Events type, Long time, String uriDetails) {
            if (activeListener) {
                activeServerPool.activeConnectionEvent(type, time, uriDetails);
                if (activeServerPool != passiveServerPool) {
                    // if they are the same pool no need to send this twice
                    passiveServerPool.activeConnectionEvent(type, time, uriDetails);
                }
            }
            else {
                activeServerPool.passiveConnectionEvent(type, time, uriDetails);
                if (activeServerPool != passiveServerPool) {
                    // if they are the same pool no need to send this twice
                    passiveServerPool.passiveConnectionEvent(type, time, uriDetails);
                }
            }
        }
    }

    private ApConnection(ApOptions apOptions, Options options, ServerPool activeSp, ServerPool passiveSp) {
        super(options);
        this.apOptions = apOptions;

        // we made the pool or verified, so we know this cast is safe
        activeServerPool = (ApServerPool)activeSp;
        passiveServerPool = (ApServerPool)passiveSp;

        addConnectionListener(new BridgeConnectionListener(true));

        // get the server pool from the NatsConnection instance
        // it's only ready after [super] construction
        this.passiveOptions = new Options.Builder(options)
            .connectionListener(apOptions.passiveConnectionListener)
            .errorListener(apOptions.passiveErrorListener)
            .serverPool(passiveServerPool)
            .build();
    }

    private void connect() throws InterruptedException, IOException {
        int connectsLeft = serverPool.getServerList().size();
        while (!isConnected() && connectsLeft-- > 0) {
            super.connect(true);
        }

        if (!isConnected()) {
            throw new IOException("Unable to make Active connection to NATS servers");
        }

        activeServerPool.activeConnectSucceeded(currentServer);
        passiveServerPool.activeConnectSucceeded(currentServer);

        newPassive();
    }

    private void newPassive() throws InterruptedException {
        if (passiveConnection != null) {
            passiveConnection.close(false, true);
        }
        passiveConnection = new NatsConnection(passiveOptions);
        passiveConnection.addConnectionListener(new BridgeConnectionListener(false));
        try {
            passiveConnection.connect(true);
            activeServerPool.passiveConnectSucceeded(passiveConnection.currentServer);
            passiveServerPool.passiveConnectSucceeded(passiveConnection.currentServer);
        }
        catch (IOException e) {
            throw new RuntimeException("Unable to make Passive connection to NATS servers", e);
        }
        if (!passiveConnection.isConnected()) {
            throw new RuntimeException("Unable to make Passive connection to NATS servers");
        }
    }

    @Override
    protected void reconnectImplConnect() throws InterruptedException {
        if (passiveConnection == null) {
            // this can happen on the initial connect, if the bootstrap
            // servers are unreachable.
            // Don't do anything, it will fall into the connect's loop
            return;
        }

        updateStatus(Status.RECONNECTING, passiveConnection.currentServer, passiveConnection.currentServer);
        clearCurrentServer();

        try {
            statusLock.lock();
            try {
                if (this.connecting) {
                    return;
                }
                this.connecting = true;
                statusChanged.signalAll();
            }
            finally {
                statusLock.unlock();
            }

            long timeoutNanos = options.getConnectionTimeout().toNanos();
            // Make sure the reader and writer are stopped
            if (reader.isRunning()) {
                this.reader.stop().get(timeoutNanos, TimeUnit.NANOSECONDS);
            }
            if (writer.isRunning()) {
                this.writer.stop().get(timeoutNanos, TimeUnit.NANOSECONDS);
            }

            this.dataPort = passiveConnection.dataPort;
            this.dataPortFuture = new CompletableFuture<>();
            this.dataPortFuture.complete(this.dataPort);

            this.reader.start(this.dataPortFuture);
            this.writer.start(this.dataPortFuture);

            statusLock.lock();
            try {
                this.connecting = false;
                this.currentServer = passiveConnection.currentServer;
                this.serverInfo.set(passiveConnection.serverInfo.get());
                this.serverAuthErrors.clear(); // reset on successful connection
                updateStatus(Status.CONNECTED); // will signal status change, we also signal in finally
            }
            finally {
                statusLock.unlock();
            }
        }
        catch (Exception exp) {
            processException(exp);
            try {
                // allow force reconnect since this is pretty exceptional,
                // a connection failure while trying to connect
                this.closeSocket(false, true);
            }
            catch (InterruptedException e) {
                processException(e);
                Thread.currentThread().interrupt();
            }
        }
        finally {
            statusLock.lock();
            try {
                this.connecting = false;
                statusChanged.signalAll();
            }
            finally {
                statusLock.unlock();
            }
        }

        activeServerPool.activeConnectSucceeded(currentServer);
        passiveServerPool.activeConnectSucceeded(currentServer);
        newPassive();
    }

    @Override
    public void close() throws InterruptedException {
        // close the passive
        // - manually send DISCONNECTED to the user's passive connection listener
        if (passiveConnection != null) {
            passiveConnection.close();
            if (apOptions.passiveConnectionListener != null) {
                passiveConnection.updateStatus(Status.CLOSED);
            }
        }
        super.close();
        apOptions.options.shutdownExecutors();
    }

    /**
     * Returns the passive connection's current status.
     *
     * @return the connection's status
     */
    @NonNull
    public Status getPassiveStatus() {
        return passiveConnection.getStatus();
    }

    /**
     * Return the list of known server urls for the passive connection,
     * including additional servers discovered
     * after a connection has been established.
     * Will be empty (but not null) before a connection is made and will represent the last connected server while disconnected
     * @return this connection's list of known server URLs
     */
    @NonNull
    public Collection<String> getPassiveServers() {
        return passiveConnection.getServers();
    }

    /**
     * Return the server info object for the passive connection. Will never be null, but will be an instance of {@link ServerInfo#EMPTY_INFO}
     * before a connection is made, and will represent the last connected server once connected and while disconnected
     * until a new connection is made.
     * @return the server information such as id, client info, etc.
     */
    @NonNull
    public ServerInfo getPassiveServerInfo() {
        return passiveConnection.getServerInfo();
    }

    /**
     * the url used for the passive connection, or null if disconnected
     * @return the url string
     */
    @Nullable
    public String getPassiveConnectedUrl() {
        return passiveConnection.getConnectedUrl();
    }

    /**
     * Forces reconnect behavior on the passive connection. Stops the current connection including the reading and writing,
     * copies already queued outgoing messages, and then begins the reconnect logic.
     * Does not flush. Does not force close the connection. See {@link ForceReconnectOptions}.
     * @throws IOException the forceReconnect fails
     * @throws InterruptedException the connection is not connected
     */
    public void passiveForceReconnect() throws IOException, InterruptedException {
        passiveConnection.forceReconnect(ForceReconnectOptions.DEFAULT_INSTANCE);
    }

    /**
     * Forces reconnect behavior on the passive connection. Stops the current connection including the reading and writing,
     * copies already queued outgoing messages, and then begins the reconnect logic.
     * If options are not provided, the default options are used meaning Does not flush and Does not force close the connection.
     * See {@link ForceReconnectOptions}.
     * @param options options for how the forceReconnect works.
     * @throws IOException the forceReconnect fails
     * @throws InterruptedException the connection is not connected
     */
    public void passiveForceReconnect(@Nullable ForceReconnectOptions options) throws IOException, InterruptedException {
        passiveConnection.forceReconnect(options);
    }

    /**
     * Calculates the round trip time between this client and the server for the passive connection.
     * @return the RTT as a duration
     * @throws IOException various IO exception such as timeout or interruption
     */
    @NonNull
    public Duration passiveRTT() throws IOException {
        return passiveConnection.RTT();
    }
}
