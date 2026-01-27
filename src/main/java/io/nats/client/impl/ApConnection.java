package io.nats.client.impl;

import io.nats.client.Connection;
import io.nats.client.Options;
import org.jspecify.annotations.NonNull;

import java.io.IOException;

public class ApConnection extends NatsConnection {
    public static Connection connect(Options options) throws IOException, InterruptedException {
        return createConnection(options, true);
    }

    public static Connection createConnection(Options options, boolean reconnectOnConnect) throws IOException, InterruptedException {
        ApConnection conn = new ApConnection(options);
        conn.connect(reconnectOnConnect);
        return conn;
    }

    ApConnection(@NonNull Options options) {
        super(options);
    }
}
