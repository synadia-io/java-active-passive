package io.nats.client.impl;

import io.nats.client.*;
import io.nats.client.api.ServerInfo;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.*;

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

        // maxReconnects(-1): in an active/passive pair we need ONE of them connected, so the active must
        // never give up on its own - it keeps retrying forever and short-circuits to the passive whenever
        // the passive is the one that's up (reconnectImplConnect steal). Set it explicitly here rather than
        // relying on the shared-pool side effect (constructing the passive re-initializes a shared pool with
        // the passive's -1); that side effect doesn't happen when the caller supplies a SEPARATE passive
        // server pool, which would silently leave the active at the default finite budget. This governs only
        // the RUNNING active's reconnect: bootstrap stays bounded because reconnectImplConnect returns early
        // while passiveConnection is still null, so connect()'s own loop is what limits the initial attempts.
        activeBuilder.maxReconnects(-1);

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
                if (activeServerPool != passiveServerPool) { // if they are the same pool no need to send this twice
                    passiveServerPool.activeConnectionEvent(type, time, uriDetails);
                }
            }
            else {
                activeServerPool.passiveConnectionEvent(type, time, uriDetails);
                if (activeServerPool != passiveServerPool) { // if they are the same pool no need to send this twice
                    passiveServerPool.passiveConnectionEvent(type, time, uriDetails);
                }
            }
        }
    }

    private void activeConnectSucceeded() {
        activeServerPool.activeConnectSucceeded(currentServer);
        if (activeServerPool != passiveServerPool) { // if they are the same pool no need to send this twice
            passiveServerPool.activeConnectSucceeded(currentServer);
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
        // maxReconnects(-1): the passive must keep retrying its OWN reconnect forever. A passive that
        // loses its socket and then exhausts a finite reconnect budget would close itself permanently,
        // and nothing re-arms it while the active stays happily connected - so the warm standby would
        // silently disappear. Infinite reconnect lets the passive self-heal via jnats' own mechanism.
        // (This governs only the post-connect live reconnect; newPassive() creates the passive with
        // connect(false) so this can never turn the initial connect into an unbounded synchronous block.)
        this.passiveOptions = new Options.Builder(options)
            .connectionListener(apOptions.passiveConnectionListener)
            .errorListener(apOptions.passiveErrorListener)
            .serverPool(passiveServerPool)
            .maxReconnects(-1)
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

        activeConnectSucceeded();
        newPassive();
    }

    private void newPassive() throws InterruptedException {
        // Capture the old passive (if any) before we overwrite the field. We must build and connect the
        // NEW passive BEFORE closing the old one: reconnectImplConnect adopted the old passive's reader
        // into this (active) connection, and that reader thread is still running on the old passive's
        // executor pool (shared per passiveOptions). Closing the old passive decrements passiveOptions'
        // executor refcount; if it reached 0 the pool would be shut down and the adopted reader killed.
        // Creating the new passive first (it increments the same refcount) keeps the pool alive across
        // the close.
        NatsConnection oldPassive = passiveConnection;

        passiveConnection = new NatsConnection(passiveOptions);
        passiveConnection.addConnectionListener(new BridgeConnectionListener(false));
        try {
            // connect(false): one pass through the server pool, then throw on failure - do NOT enter
            // reconnectImpl here. With the passive's maxReconnects(-1), connect(true) would loop the
            // initial connect forever, synchronously, on whatever thread called newPassive (the active's
            // reconnect thread on failover, or the app thread at bootstrap). Fail fast instead: the caller
            // (reArmPassive) is best-effort and the next active reconnect re-arms again. Once the passive
            // IS connected, maxReconnects(-1) still gives it unlimited LIVE reconnect on its own thread.
            passiveConnection.connect(false);
            activeServerPool.passiveConnectSucceeded(passiveConnection.currentServer);
            passiveServerPool.passiveConnectSucceeded(passiveConnection.currentServer);
        }
        catch (IOException e) {
            throw new RuntimeException("Unable to make Passive connection to NATS servers", e);
        }
        if (!passiveConnection.isConnected()) {
            throw new RuntimeException("Unable to make Passive connection to NATS servers");
        }

        if (oldPassive != null) {
            // Close the old passive in the background so we can get the new passive connected faster.
            // Its reader/dataPort were handed off in reconnectImplConnect, so this close touches neither
            // the adopted reader nor the live socket.
            // Capture the executor: a concurrent ApConnection.close() can null this field or shut the
            // pool down. If there's no usable background executor (we're closing) fall back to closing
            // inline so the old passive is never leaked.
            ExecutorService backgroundExecutor = executor;
            boolean submitted = false;
            if (backgroundExecutor != null) {
                try {
                    backgroundExecutor.submit(() -> {
                        try {
                            oldPassive.close(false, true);
                        }
                        catch (Exception e) {
                            processException(e);
                            if (e instanceof InterruptedException) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    });
                    submitted = true;
                }
                catch (RejectedExecutionException ree) {
                    // pool is shutting down - fall through to the inline close
                }
            }
            if (!submitted) {
                oldPassive.close(false, true);
            }
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

        if (!passiveConnection.isConnected()) {
            // The passive is also down (e.g. the whole cluster is restarting), so there's no live
            // socket to promote. Drop it and let the base logic reconnect the active through the
            // server pool. Once the active is back, RE-ARM the passive: a warm standby must ALWAYS
            // be re-established, or the first full-cluster outage would permanently leave us with no
            // passive. Best-effort (reArmPassive) so a passive that can't connect yet never aborts
            // the active's reconnect; the next active reconnect will try again.
            passiveConnection = null;
            super.reconnectImplConnect();
            if (isConnected()) {
                activeConnectSucceeded();
                reArmPassive();
            }
            return;
        }

        statusLock.lock();
        try {
            if (this.connecting) {
                return;
            }
            this.connecting = true;
            updateStatus(Status.RECONNECTING, passiveConnection.currentServer, passiveConnection.currentServer);
            clearCurrentServer();
            statusChanged.signalAll();
        }
        finally {
            statusLock.unlock();
        }


        // Stop the reader and writer, then force-close the OLD (now dead) active port to unblock
        // anything still parked on it. This is the key step: shutdownInput() (what stop does) is a
        // no-op on TLS sockets, so a blocked read would otherwise sit there until the get() times out.
        // Order matters - stop() sets running=false first, so the forced-close IOException is seen by
        // the loops as an expected shutdown rather than a communication issue that spawns a reconnect.
        long timeoutNanos = options.getConnectionTimeout().toNanos();
        Future<Boolean> readerStopped = reader.isRunning() ? reader.stop() : null;
        Future<Boolean> writerStopped = writer.isRunning() ? writer.stop() : null;

        DataPort oldDataPort = this.dataPort;
        if (oldDataPort != null) {
            try {
                oldDataPort.forceClose();
            }
            catch (IOException e) {
                // we are discarding this port anyway, nothing we can do or care about
            }
        }

        // Join the loops before we reuse the same reader/writer instances below. With the old port
        // force-closed they exit promptly; the bounded get() is just a backstop. It's my job to try
        // to stop them - if a get() still fails, log it and move on rather than block the failover.
        try {
            if (readerStopped != null) {
                readerStopped.get(timeoutNanos, TimeUnit.NANOSECONDS);
            }
        }
        catch (ExecutionException | TimeoutException e) {
            processException(e);
        }
        try {
            if (writerStopped != null) {
                writerStopped.get(timeoutNanos, TimeUnit.NANOSECONDS);
            }
        }
        catch (ExecutionException | TimeoutException e) {
            processException(e);
        }

        // Stop the passive's writer so it doesn't share the socket with the active's writer. It blocks
        // on its queue, so stop() (poison pill) is clean; join it before our writer takes the socket.
        Future<Boolean> passiveWriterStopped = passiveConnection.writer.isRunning()
            ? passiveConnection.writer.stop() : null;
        try {
            if (passiveWriterStopped != null) {
                passiveWriterStopped.get(timeoutNanos, TimeUnit.NANOSECONDS);
            }
        }
        catch (ExecutionException | TimeoutException e) {
            processException(e);
        }

        // Cancel the old passive's scheduled tasks NOW, at steal time. Its status is still CONNECTED,
        // and only close() would otherwise stop these - but close() runs later, inside newPassive(),
        // and won't run at all if the new passive's connect throws. Left running, pingTask keeps
        // queuing PINGs through the now-stopped writer until "Max outgoing Ping count exceeded", which
        // spawns a zombie reconnect loop on this discarded passive. Cancelling here is refcount-safe
        // (it does not touch the shared executor pool, unlike close()), so it's always correct to do.
        if (passiveConnection.pingTask != null) {
            passiveConnection.pingTask.shutdown();
            passiveConnection.pingTask = null;
        }
        if (passiveConnection.cleanupTask != null) {
            passiveConnection.cleanupTask.shutdown();
            passiveConnection.cleanupTask = null;
        }

        // ADOPT the passive's live reader rather than starting a second reader on the stolen socket.
        // A socket-blocked reader can't be cleanly stopped without wrecking the socket we're keeping,
        // so instead we take the passive's reader - which is already coherently reading this socket -
        // and repoint it to deliver into THIS (active) connection. That repoint relies on jnats Hook 1
        // (NatsConnectionReader.connection is volatile + assignable). We hand our now-dead reader
        // (stopped and its port force-closed above) to the passive, so the passive's own close() has an
        // already-stopped reader to shut down instead of the live one we just took.
        NatsConnectionReader deadActiveReader = this.reader;
        this.reader = passiveConnection.reader;
        setReaderConnection(this); // repoint the adopted (live) reader to deliver into this connection
        passiveConnection.reader = deadActiveReader;
        passiveConnection.setReaderConnection(passiveConnection); // hand the dead reader back to the passive

        this.dataPort = passiveConnection.dataPort;
        // the port was connected by the passive connection, so its back-reference points there.
        // re-point it at this (the active) connection now that we've taken the port over.
        // same package as SocketDataPort, so the protected field is directly assignable.
        if (this.dataPort instanceof SocketDataPort) {
            ((SocketDataPort) this.dataPort).connection = this;
        }
        passiveConnection.dataPort = null;

        this.dataPortFuture = new CompletableFuture<>();
        this.dataPortFuture.complete(this.dataPort);

        // Only the writer (re)starts on the new port - the adopted reader is already running on it.
        // The writer restart preserves its queued outgoing: reconnectImpl flushes them via
        // enterWaitingForEndReconnectMode.
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

        activeConnectSucceeded();
        reArmPassive();
    }

    // Best-effort re-arm of the passive standby from within a reconnect. The active is already
    // (re)connected at every call site, so a failure to (re)establish the passive must NOT propagate
    // into reconnectImpl - that would skip the active's subscription resend / end-reconnect and leave
    // the ACTIVE broken over a merely-passive problem. Log it and move on; the next active reconnect
    // re-arms again. (The initial connect() calls newPassive() directly, on purpose: there the failure
    // should surface to the caller.)
    private void reArmPassive() throws InterruptedException {
        try {
            newPassive();
        }
        catch (RuntimeException e) {
            processException(e);
        }
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
