package io.nats.client;

import io.nats.NatsRunnerUtils;
import io.nats.NatsServerRunner;
import io.nats.client.api.ServerInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.logging.Level;

import static io.nats.client.utils.ConnectionUtils.managedConnect;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ConnectTests {
    static NatsServerRunner RUNNER;
    static int RUNNER_PORT;

    @BeforeAll
    static void beforeAll() throws IOException {
        NatsRunnerUtils.setDefaultOutputLevel(Level.SEVERE);
        RUNNER = NatsServerRunner.builder().build();
        RUNNER_PORT = RUNNER.getPort();
    }

    @AfterAll
    static void afterAll() throws IOException {
        try {
            RUNNER.shutdown();
        }
        catch (InterruptedException e) {
            // ignore, shutting down anyway
        }
    }

    private static Options.Builder getOptionsBuilder() {
        return Options.builder().server(NatsRunnerUtils.getNatsLocalhostUri(RUNNER_PORT));
    }

    private static Options getOptions() {
        return getOptionsBuilder().build();
    }

    @Test
    public void testConnection() throws Exception {
        Options options = getOptions();
        try (Connection apc = managedConnect(options)) {
            ServerInfo info = apc.getServerInfo();
            assertEquals(RUNNER_PORT, info.getPort());
        }
    }
}
