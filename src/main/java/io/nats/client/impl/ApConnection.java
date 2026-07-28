package io.nats.client.impl;

import io.nats.client.*;
import io.nats.client.api.ServerInfo;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.*;

public class ApConnection extends NatsConnection {

    final ApOptions apOptions;
    final Options passiveOptions;  // since we may be making passive more than once
    final ApServerPool activeServerPool;
    final ApServerPool passiveServerPool;

    // volatile: written by the connect/reconnect thread (nulled inside reconnectImplConnect as a
    // re-entrancy guard, reassigned by newPassive) and read by any thread calling a passive accessor.
    // volatile gives those readers visibility of the latest reference; each accessor snapshots it once
    // into a local so a null-check and the following use operate on the SAME reference - lock-free, and
    // without blocking the time-critical reconnect the way a shared lock around RTT/forceReconnect would.
    // See issue #20.
    volatile NatsConnection passiveConnection;

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

    // package-private (not private) so tests can construct an instance without connecting - i.e. with
    // passiveConnection still null - to exercise the passive accessors' null-safety without spinning
    // servers. The public entry point remains connect().
    ApConnection(ApOptions apOptions, Options options, ServerPool activeSp, ServerPool passiveSp) {
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
            // A non-null oldPassive ONLY happens after a successful steal - the other two newPassive()
            // callers (the initial connect, and the re-arm after the passive was found already down)
            // both come in with passiveConnection null. So getting here means this passive was PROMOTED,
            // not lost: its socket is alive and is now serving THIS (active) connection.
            //
            // Detach its listeners before closing it. close() ends with updateStatus(CLOSED), which would
            // otherwise deliver a CLOSED event to the user's passive ConnectionListener for a connection
            // that didn't die - it got promoted - leaving the app unable to tell a successful failover
            // from a passive failure. (CLOSED is the only event this close fires; closeSocketImpl never
            // passes through DISCONNECTED, and updateStatus goes straight from CONNECTED to CLOSED.)
            // Clearing also drops our BridgeConnectionListener, so the server pools don't get a bogus
            // passiveConnectionEvent(CLOSED) either.
            oldPassive.connectionListeners.clear();

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

        // Discard the PINGs we had outstanding on the socket we just let go of. Their PONGs are never
        // coming - the socket is gone - so the futures must be cancelled rather than left queued.
        // A normal (re)connect gets this for free: tryToConnect calls cleanUpPongQueue before it
        // connects the new port. The steal never goes through tryToConnect, so we do it here.
        //
        // This matters more than it looks. sendPing trips handleCommunicationIssue("Max outgoing Ping
        // count exceeded.") once pongQueue.size() + 1 > maxPingsOut, and maxPingsOut defaults to 2. The
        // dead socket typically has unanswered PINGs on it precisely BECAUSE it went unresponsive, so
        // carrying them over means the first softPing after promotion can immediately declare a
        // communication issue and spawn a reconnect on the connection we just successfully failed over
        // to. It also releases anything parked in flush() on a pongFuture for the old socket, which
        // would otherwise block until its timeout.
        //
        // Emptying the queue also makes us robust to the in-flight PONG the PASSIVE had outstanding on
        // this socket: it arrives after the adoption and lands in handlePong on THIS connection, which
        // is a no-op on an empty queue instead of spuriously completing one of our futures.
        cleanUpPongQueue();

        // needPing means "no inbound traffic seen, so a PING is needed to prove liveness" - deliverMessage
        // clears it. It is per-CONNECTION state describing the CURRENT socket, and the socket is about to
        // be swapped, so the value we are holding describes the dead one. Reset it: we have seen nothing
        // on the promoted socket, because on this connection nothing has arrived over it yet.
        // Without this, an active that delivered a message shortly before its socket failed carries a
        // cleared needPing across the steal, and the softPing below would skip - so the promoted socket
        // would silently get no ping at all.
        needPing.set(true);

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
        reader.setConnection(this); // repoint the adopted (live) reader to deliver into this connection
        passiveConnection.reader = deadActiveReader;
        passiveConnection.reader.setConnection(passiveConnection); // hand the dead reader back to the passive

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

        // Put a PING on the promoted socket. Every normal connect ends with a ping/pong round trip
        // (tryToConnect does sendPing and waits on it before it reports CONNECTED); the steal is the one
        // path that reaches CONNECTED without ever touching the wire, so send one here too.
        //
        // Deliberately NOT waited on. The switch is already complete and the socket was warm - the
        // passive was holding it and pinging it on its own timer - so there's nothing to gain from
        // blocking the failover on a round trip. If the socket does turn out to be dead the PONG simply
        // never lands, and the ordinary ping timer / maxPingsOut machinery reconnects us exactly as it
        // would for any other connection.
        //
        // softPing rather than sendPing: this is a liveness check on a socket that has just started
        // serving us, which is exactly what the ping timer does, so it belongs on the same footing -
        // the standard outgoing queue, ordered behind whatever is already queued, rather than jumping
        // the internal queue the way a connect-time ping does. It also stays honest about the point of
        // a ping: once traffic starts arriving on the promoted socket, deliverMessage clears needPing
        // and a redundant ping is skipped.
        // It does send here - needPing was reset above, so the skip branch can't apply - and the pong
        // queue was emptied above, so it can't trip maxPingsOut either.
        softPing();

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
            // close() already ends with updateStatus(Status.CLOSED), which fires the CLOSED event to the
            // user's passive ConnectionListener. A second updateStatus(Status.CLOSED) here was a no-op -
            // updateStatus returns early when newStatus == oldStatus - so it has been removed.
            passiveConnection.close();
        }
        super.close();
        apOptions.options.shutdownExecutors();
    }

    /**
     * Immediately let go of the current active connection and switch over to the passive,
     * promoting the passive's live socket to be the active connection. This is the same failover
     * that happens when the active's socket dies, just triggered on demand instead of by a failure:
     * the active's socket is force closed - nothing queued on it is flushed - and once the switch
     * is complete a new passive is armed.
     * <p>This is a no-op if a (re)connect is already in progress on the active, since that reconnect
     * will itself promote the passive.
     * @throws IllegalStateException there is no connected passive to switch to
     * @throws InterruptedException the thread is interrupted while switching
     */
    public void switchToPassive() throws InterruptedException {
        // Check before letting go of the active. Without a live passive to promote, reconnectImplConnect
        // would fall back to a normal reconnect through the server pool (or, with no passive at all,
        // do nothing and let reconnectImpl close us) - neither is what the caller asked for.
        // This is inherently best effort: the passive can still die between here and the steal, in
        // which case that same fallback applies.
        NatsConnection currentPassive = passiveConnection;
        if (currentPassive == null || !currentPassive.isConnected()) {
            throw new IllegalStateException("There is no connected passive connection to switch to.");
        }

        // Deliberately NOT forceReconnect(). forceReconnectImpl does its own teardown first - nulls
        // dataPort and closes it on a background task, nulls dataPortFuture, stops reader and writer
        // on a 100ms budget and swallows the timeout - which is teardown written for reconnecting
        // through the server pool. reconnectImplConnect below does the teardown the STEAL needs
        // (stop before force close so the loops read the IOException as shutdown, then bounded joins),
        // and it needs the live dataPort/reader still in place to do it. So do the least possible
        // here - just make the connection look disconnected so reconnectImpl runs the connect step -
        // and let the failover path let go of the socket itself.
        if (tryingToConnect.compareAndSet(false, true)) {
            try {
                updateStatus(Status.DISCONNECTED);
                reconnectImpl(); // -> our reconnectImplConnect -> steal the passive -> reArmPassive
            }
            finally {
                tryingToConnect.set(false);
            }
        }
    }

    /**
     * Returns the passive connection's current status.
     *
     * @return the connection's status
     */
    @NonNull
    public Status getPassiveStatus() {
        NatsConnection p = this.passiveConnection;
        return p == null ? Status.DISCONNECTED : p.getStatus();
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
        NatsConnection p = this.passiveConnection;
        return p == null ? Collections.emptyList() : p.getServers();
    }

    /**
     * Return the server info object for the passive connection. Will never be null, but will be an instance of {@link ServerInfo#EMPTY_INFO}
     * before a connection is made, and will represent the last connected server once connected and while disconnected
     * until a new connection is made.
     * @return the server information such as id, client info, etc.
     */
    @NonNull
    public ServerInfo getPassiveServerInfo() {
        NatsConnection p = this.passiveConnection;
        return p == null ? ServerInfo.EMPTY_INFO : p.getServerInfo();
    }

    /**
     * the url used for the passive connection, or null if disconnected
     * @return the url string
     */
    @Nullable
    public String getPassiveConnectedUrl() {
        NatsConnection p = this.passiveConnection;
        return p == null ? null : p.getConnectedUrl();
    }

    /**
     * Forces reconnect behavior on the passive connection. Stops the current connection including the reading and writing,
     * copies already queued outgoing messages, and then begins the reconnect logic.
     * Does not flush. Does not force close the connection. See {@link ForceReconnectOptions}.
     * @throws IOException the forceReconnect fails
     * @throws InterruptedException the connection is not connected
     */
    public void passiveForceReconnect() throws IOException, InterruptedException {
        NatsConnection p = this.passiveConnection;
        if (p != null && p.getStatus() == Status.CONNECTED) {
            p.forceReconnect(ForceReconnectOptions.DEFAULT_INSTANCE);
        }
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
        NatsConnection p = this.passiveConnection;
        if (p != null && p.getStatus() == Status.CONNECTED) {
            p.forceReconnect(options);
        }
    }

    /**
     * Calculates the round trip time between this client and the server for the passive connection.
     * @return the RTT as a duration
     * @throws IOException various IO exception such as timeout or interruption
     * @throws IllegalStateException if the passive connection is absent or not in a
     *         {@link Connection.Status#CONNECTED} state - there is no live socket to measure. This mirrors
     *         {@link #switchToPassive()}, which uses the same exception for the "no usable passive" case.
     */
    @NonNull
    public Duration passiveRTT() throws IOException {
        NatsConnection p = this.passiveConnection;
        Status status = p == null ? Status.DISCONNECTED : p.getStatus();
        if (status != Status.CONNECTED) {
            throw new IllegalStateException("passive connection status is " + status);
        }
        return p.RTT();
    }
}
