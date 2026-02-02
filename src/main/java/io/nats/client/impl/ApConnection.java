package io.nats.client.impl;

import io.nats.client.ForceReconnectOptions;
import io.nats.client.Options;
import io.nats.client.ServerPool;
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
    final ApPassiveServerPool apServerPool;
    NatsConnection passive;

    public static ApConnection connect(ApOptions apOptions) throws IOException, InterruptedException {
        if (apOptions == null) {
            apOptions = ApOptions.builder().build();
        }

        Options.Builder activeBuilder = new Options.Builder(apOptions.options);

        // this set's up the server pool here instead of waiting for the
        // NatsConnection constructor to do it.
        ServerPool apServerPool = new ApPassiveServerPool(
            apOptions.options.getServerPool() == null
                ? new NatsServerPool()
                : apOptions.options.getServerPool());
        activeBuilder.serverPool(apServerPool);

        ApConnection apc = new ApConnection(apOptions, activeBuilder.build());
        apc.connect();
        return apc;
    }

    private ApConnection(ApOptions apOptions, Options activeOptions) {
        super(activeOptions);
        this.apOptions = apOptions;

        // we made the pool, so we know this cast is safe
        apServerPool = (ApPassiveServerPool)activeOptions.getServerPool();

        // get the server pool from the NatsConnection instance
        // it's only ready after [super] construction
        this.passiveOptions = new Options.Builder(activeOptions)
            .connectionListener(apOptions.passiveConnectionListener)
            .errorListener(apOptions.passiveErrorListener)
            .serverPool(apServerPool)
            .build();
    }

    private void connect() throws InterruptedException, IOException {
        super.connect(true);
        if (!isConnected()) {
            throw new IOException("Unable to connect to NATS servers");
        }
        apServerPool.setActiveServer(currentServer);
        newPassive();
    }

    private void newPassive() throws InterruptedException {
        if (passive != null) {
            passive.close(false, true);
        }
        passive = new NatsConnection(passiveOptions);
        this.getOptions().getExecutor().execute(() -> {
            try {
                passive.connect(true);
            }
            catch (InterruptedException | IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    protected void reconnectImplConnect() throws InterruptedException {
        if (passive == null) {
            // passive is not available, do the normal reconnect
            super.reconnectImplConnect();
            return;
        }

        updateStatus(Status.RECONNECTING, passive.currentServer, passive.currentServer);
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

            this.dataPort = passive.dataPort;
            this.dataPortFuture = new CompletableFuture<>();
            this.dataPortFuture.complete(this.dataPort);

            this.reader.start(this.dataPortFuture);
            this.writer.start(this.dataPortFuture);

            statusLock.lock();
            try {
                this.connecting = false;
                this.currentServer = passive.currentServer;
                this.serverInfo.set(passive.serverInfo.get());
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

        apServerPool.setActiveServer(currentServer);
        newPassive();
    }

    @Override
    public void close() throws InterruptedException {
        // close the passive
        // - manually send DISCONNECTED to the user's passive connection listener
        if (passive != null) {
            passive.close();
            if (apOptions.passiveConnectionListener != null) {
                passive.updateStatus(Status.CLOSED);
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
        return passive.getStatus();
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
        return passive.getServers();
    }

    /**
     * Return the server info object for the passive connection. Will never be null, but will be an instance of {@link ServerInfo#EMPTY_INFO}
     * before a connection is made, and will represent the last connected server once connected and while disconnected
     * until a new connection is made.
     * @return the server information such as id, client info, etc.
     */
    @NonNull
    public ServerInfo getPassiveServerInfo() {
        return passive.getServerInfo();
    }

    /**
     * the url used for the passive connection, or null if disconnected
     * @return the url string
     */
    @Nullable
    public String getPassiveConnectedUrl() {
        return passive.getConnectedUrl();
    }

    /**
     * Forces reconnect behavior on the passive connection. Stops the current connection including the reading and writing,
     * copies already queued outgoing messages, and then begins the reconnect logic.
     * Does not flush. Does not force close the connection. See {@link ForceReconnectOptions}.
     * @throws IOException the forceReconnect fails
     * @throws InterruptedException the connection is not connected
     */
    public void passiveForceReconnect() throws IOException, InterruptedException {
        passive.forceReconnect(ForceReconnectOptions.DEFAULT_INSTANCE);
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
        passive.forceReconnect(options);
    }

    /**
     * Calculates the round trip time between this client and the server for the passive connection.
     * @return the RTT as a duration
     * @throws IOException various IO exception such as timeout or interruption
     */
    @NonNull
    public Duration passiveRTT() throws IOException {
        return passive.RTT();
    }
}
