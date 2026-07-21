package example;

import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Options;
import io.nats.client.impl.ApConnection;
import io.nats.client.impl.ApOptions;
import io.nats.client.impl.ErrorListenerConsoleImpl;
import org.jspecify.annotations.NonNull;

public class ForceVsSwitch {
    static class Cl implements ConnectionListener {
        boolean active;
        String name;

        public Cl(boolean active) {
            this.active = active;
            this.name = active ? "Active CL: " : "Passive CL: ";
        }

        @Override
        public void connectionEvent(Connection conn, Events type) {
        }

        @Override
        public void connectionEvent(Connection conn, Events type, Long time, String uriDetails) {
            System.out.println("[" + time + "] " + name + type + " " + uriDetails.replace("localhost:", "").replace("nats://", ""));
        }
    }

    public static void main(String[] args) {
        Options options = Options.builder()
            .connectionListener(new Cl(true))
            .errorListener(new ErrorListenerConsoleImpl())
            .build();

        ApOptions apOptions = ApOptions.builder()
            .options(options)
            .passiveConnectionListener(new Cl(false))
            .passiveErrorListener(new ErrorListenerConsoleImpl())
            .build();
        System.out.println(time() + "Main: About To Connect");
        try (ApConnection apc = ApConnection.connect(apOptions)) {
            Thread.sleep(3000);
            System.out.println(time() + "Main: About To Force Reconnect, " + currentState(apc));
            apc.forceReconnect();
            Thread.sleep(3000);
            System.out.println(time() + "Main: About To Switch, " + currentState(apc));
            apc.switchToPassive();
            Thread.sleep(3000);
            System.out.println(time() + "Main: Closing, " + currentState(apc));
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static @NonNull String currentState(ApConnection apc) {
        return "A: " + apc.getServerInfo().getPort() + ", P: " + apc.getPassiveServerInfo().getPort();
    }

    static String time() {
        return "[" + System.currentTimeMillis() + "] ";
    }
}
