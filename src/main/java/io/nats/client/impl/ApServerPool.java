package io.nats.client.impl;

import io.nats.client.ConnectionListener;
import io.nats.client.ServerPool;
import io.nats.client.support.NatsUri;

public interface ApServerPool extends ServerPool {
    /**
     * any time the active connection succeeds in connecting
     * @param nuri the connected NatsUri
     */
    void activeConnectSucceeded(NatsUri nuri);

    /**
     * any time the pasive connection succeeds in connecting
     * @param nuri the connected NatsUri
     */
    void passiveConnectSucceeded(NatsUri nuri);

    /**
     * Any time the active connection emits a Connection Event, this method is called with the details
     * @param type the event
     * @param time the time the event happened
     * @param uriDetails the uri affected
     */
    void activeConnectionEvent(ConnectionListener.Events type, Long time, String uriDetails);

    /**
     * Any time the passive connection emits a Connection Event, this method is called with the details
     * @param type the event
     * @param time the time the event happened
     * @param uriDetails the uri affected
     */
    void passiveConnectionEvent(ConnectionListener.Events type, Long time, String uriDetails);
}
