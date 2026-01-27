package io.nats.client.utils;

public abstract class ThreadUtils {
    public static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { /* ignored */ }
    }
}