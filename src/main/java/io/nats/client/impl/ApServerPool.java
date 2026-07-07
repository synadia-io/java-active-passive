package io.nats.client.impl;

import io.nats.client.ConnectionListener;
import io.nats.client.ServerPool;
import io.nats.client.support.NatsUri;

public interface ApServerPool extends ServerPool {
    void activeConnectSucceeded(NatsUri nuri);
    void passiveConnectSucceeded(NatsUri nuri);
    void activeConnectionEvent(ConnectionListener.Events type, Long time, String uriDetails);
    void passiveConnectionEvent(ConnectionListener.Events type, Long time, String uriDetails);
}
