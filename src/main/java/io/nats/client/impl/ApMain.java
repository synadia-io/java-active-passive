package io.nats.client.impl;

import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Options;

import java.io.IOException;

public class ApMain {

    static class MainConnectionListener implements ConnectionListener {
        @Override
        public void connectionEvent(Connection conn, Events type) {
            System.out.printf("CL %s/%s/%s\n", Integer.toHexString(conn.hashCode()), conn.getStatus(), type.getEvent());
        }

        @Override
        public void connectionEvent(Connection conn, Events type, Long time, String uriDetails) {
            System.out.printf("CL %s@%s/%s/%s(%s)\n", Integer.toHexString(conn.hashCode()).toUpperCase(), time, type.getEvent(), conn.getStatus(), uriDetails);
        }
    }

    public static void main(String[] args) {
        ApOptions apOptions = ApOptions.builder(Options.builder()
                .server("nats://localhost:4222")
                .maxReconnects(2)
                .connectionListener(new MainConnectionListener())
                .errorListener(new ErrorListenerConsoleImpl())
                .build())
            .passiveConnectionListener(new MainConnectionListener())
            .passiveErrorListener(new ErrorListenerConsoleImpl())
            .build();

        try (ApConnection apc = ApConnection.connect(apOptions)) {
            Thread.sleep(1000);
            System.out.println(apc.getServerInfo());
            System.out.println(apc.getPassiveServerInfo());
        }
        catch (InterruptedException | IOException e) {
            System.out.println(e);
        }
    }
}
