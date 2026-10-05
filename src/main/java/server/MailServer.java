package server;

import common.PacketUtils;
import common.Protocol;

import java.io.IOException;
import java.net.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * MailServer is the core UDP Server engine.
 * It manages the DatagramSocket, dispatches incoming packets to an ExecutorService thread pool,
 * enforces session timeout handling, and exposes administrative functions (Kick, Ban, Unban).
 */
public class MailServer {

    private static final DateTimeFormatter LOG_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int THREAD_POOL_SIZE = 10;

    private final String host;
    private final int port;
    private final MailStorage mailStorage;
    private final SessionManager sessionManager;
    private final ExecutorService workerPool;

    private DatagramSocket socket;
    private Thread receiverThread;
    private volatile boolean running = false;

    private BiConsumer<String, String> logConsumer;

    private final ScheduledExecutorService trashPurgeScheduler;

    public MailServer() throws IOException {
        this(Protocol.DEFAULT_HOST, Protocol.DEFAULT_PORT);
    }

    public MailServer(String host, int port) throws IOException {
        this(host, port, new MailStorage());
    }

    public MailServer(String host, int port, MailStorage mailStorage) {
        this.host = host;
        this.port = port;
        this.mailStorage = mailStorage;
        this.sessionManager = new SessionManager();
        this.workerPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE, new ThreadFactory() {
            private int counter = 1;
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "UDP-Worker-" + (counter++));
                t.setDaemon(true);
                return t;
            }
        });

        // Register session timeout listener to log status changes
        this.sessionManager.setTimeoutCallback(session -> {
            log("TIMEOUT", "User " + session.getUsername() + " timed out after 15s of inactivity. Marked OFFLINE.");
        });

        // Periodic 30-day trash auto-purger (runs every 1 hour)
        this.trashPurgeScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Trash-Purge-Worker");
            t.setDaemon(true);
            return t;
        });
        this.trashPurgeScheduler.scheduleAtFixedRate(this::purgeTrash, 0, 1, TimeUnit.HOURS);
    }

    private void purgeTrash() {
        int purged = mailStorage.purgeExpiredTrash(Protocol.TRASH_RETENTION_MILLIS);
        if (purged > 0) {
            log("PURGE", "Purged " + purged + " expired trash emails older than 30 days.");
        }
    }

    public void setLogConsumer(BiConsumer<String, String> logConsumer) {
        this.logConsumer = logConsumer;
    }

    public MailStorage getMailStorage() {
        return mailStorage;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    public String getHost() {
        return host;
    }

    /**
     * Starts the UDP server listener on the configured port.
     */
    public synchronized void start() throws SocketException, UnknownHostException {
        if (running) {
            return;
        }

        InetAddress bindAddress = InetAddress.getByName(host);
        socket = new DatagramSocket(new InetSocketAddress(bindAddress, port));
        running = true;

        log("SERVER", "Mail Server started on " + host + ":" + port + " [Thread Pool Size: " + THREAD_POOL_SIZE + "]");

        receiverThread = new Thread(this::receiveLoop, "UDP-Receiver-Thread");
        receiverThread.start();
    }

    /**
     * Dedicated receiver loop running off the Swing EDT.
     */
    private void receiveLoop() {
        while (running && socket != null && !socket.isClosed()) {
            try {
                byte[] buffer = new byte[Protocol.MAX_PACKET_SIZE];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                // Dispatch to fixed thread pool for concurrent processing
                workerPool.submit(new MailRequestHandler(socket, packet, mailStorage, sessionManager, this::log));

            } catch (SocketException se) {
                // Socket closed during shutdown is normal
                if (!running) {
                    break;
                }
                log("ERROR", "Socket exception in receiver thread: " + se.getMessage());
            } catch (IOException ioe) {
                if (running) {
                    log("ERROR", "I/O error reading UDP packet: " + ioe.getMessage());
                }
            }
        }
        log("SERVER", "UDP receiver loop terminated.");
    }

    /**
     * Kicks an active user by sending a KICK UDP packet and marking session OFFLINE.
     */
    public synchronized boolean kickUser(String username) {
        ClientSession session = sessionManager.getSession(username);
        if (session == null) {
            log("KICK", "Cannot kick: User " + username + " session not found.");
            return false;
        }

        if (session.getAddress() != null && session.getPort() > 0) {
            try {
                String kickMsg = Protocol.RESP_KICK + Protocol.DELIMITER + "Ban da bi Admin truc xuat khoi he thong";
                PacketUtils.send(socket, kickMsg, session.getAddress(), session.getPort());
                log("SEND", kickMsg + " to " + session.getAddress().getHostAddress() + ":" + session.getPort());
            } catch (Exception e) {
                log("ERROR", "Failed to deliver KICK packet to " + username + ": " + e.getMessage());
            }
        }

        sessionManager.kick(username);
        log("KICK", "User " + username + " has been kicked by Admin.");
        return true;
    }

    /**
     * Bans a user: updates accounts.txt, sends KICK packet if online, and marks session BANNED.
     */
    public synchronized boolean banUser(String username) {
        try {
            mailStorage.setAccountStatus(username, Protocol.ACCOUNT_BANNED);
            ClientSession session = sessionManager.getSession(username);

            if (session != null && session.getStatus() == Protocol.SessionStatus.ONLINE) {
                if (session.getAddress() != null && session.getPort() > 0) {
                    try {
                        String banKickMsg = Protocol.RESP_KICK + Protocol.DELIMITER + "Tai khoan cua ban da bi khoa boi Admin";
                        PacketUtils.send(socket, banKickMsg, session.getAddress(), session.getPort());
                        log("SEND", banKickMsg + " to " + session.getAddress().getHostAddress() + ":" + session.getPort());
                    } catch (Exception e) {
                        log("ERROR", "Failed to deliver BAN-KICK packet to " + username + ": " + e.getMessage());
                    }
                }
            }

            sessionManager.ban(username);
            log("BAN", "User " + username + " has been BANNED by Admin. accounts.txt updated.");
            return true;
        } catch (IOException e) {
            log("ERROR", "Failed to persist BAN status for " + username + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Unbans a user: updates accounts.txt and restores session to OFFLINE.
     */
    public synchronized boolean unbanUser(String username) {
        try {
            mailStorage.setAccountStatus(username, Protocol.ACCOUNT_ACTIVE);
            sessionManager.unban(username);
            log("UNBAN", "User " + username + " has been UNBANNED by Admin. accounts.txt updated.");
            return true;
        } catch (IOException e) {
            log("ERROR", "Failed to persist UNBAN status for " + username + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Clean shutdown of the server.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;

        log("SERVER", "Initiating graceful shutdown of Mail Server...");

        // Mark all online sessions offline
        for (ClientSession session : sessionManager.getAllSessions()) {
            if (session.getStatus() == Protocol.SessionStatus.ONLINE) {
                session.setStatus(Protocol.SessionStatus.OFFLINE);
            }
        }

        // Close socket
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }

        // Interrupt receiver thread
        if (receiverThread != null && receiverThread.isAlive()) {
            receiverThread.interrupt();
        }

        // Shutdown session manager timeout scheduler
        sessionManager.shutdown();

        // Shutdown trash purge scheduler
        if (trashPurgeScheduler != null) {
            trashPurgeScheduler.shutdownNow();
        }

        // Shutdown worker pool
        workerPool.shutdown();
        try {
            if (!workerPool.awaitTermination(3, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log("SERVER", "Mail Server stopped successfully.");
    }

    public void log(String type, String message) {
        String timestamp = LocalDateTime.now().format(LOG_TIME_FORMATTER);
        String logLine = "[" + timestamp + "] [" + type + "] " + message;
        System.out.println(logLine);
        if (logConsumer != null) {
            logConsumer.accept(type, logLine);
        }
    }
}
