package client;

import common.Protocol;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * HeartbeatManager runs on the client in a dedicated daemon thread.
 * It periodically transmits HEARTBEAT|<username> packets to the UDP Mail Server
 * every 5 seconds to keep the session alive.
 */
public class HeartbeatManager {

    private final String username;
    private final Consumer<String> heartbeatSender;
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public HeartbeatManager(String username, Consumer<String> heartbeatSender) {
        this.username = username;
        this.heartbeatSender = heartbeatSender;
    }

    /**
     * Starts periodic heartbeat transmission every 5 seconds.
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Heartbeat-Worker-" + username);
            t.setDaemon(true);
            return t;
        });

        // Send initial heartbeat immediately, then every 5 seconds
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 0, Protocol.HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void sendHeartbeat() {
        if (!running) {
            return;
        }
        try {
            String heartbeatMsg = Protocol.CMD_HEARTBEAT + Protocol.DELIMITER + username;
            heartbeatSender.accept(heartbeatMsg);
        } catch (Exception e) {
            // Heartbeat failure in UDP is ignored as it is a loss-tolerant background check
            System.err.println("Heartbeat send warning: " + e.getMessage());
        }
    }

    /**
     * Stops the heartbeat worker immediately.
     */
    public synchronized void stop() {
        running = false;
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public boolean isRunning() {
        return running;
    }
}
