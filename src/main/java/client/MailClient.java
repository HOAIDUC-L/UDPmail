package client;

import common.PacketUtils;
import common.Protocol;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * MailClient provides the UDP network communication layer for the client.
 * Features an asynchronous receiver thread that dispatches request-response cycles
 * and immediately triggers callbacks upon receiving unsolicited server KICK notifications.
 */
public class MailClient {

    private static final int DEFAULT_REQUEST_TIMEOUT_MS = 4000;

    private final String serverHost;
    private final int serverPort;
    private DatagramSocket socket;
    private InetAddress serverAddress;

    private Thread receiverThread;
    private volatile boolean running = false;
    private volatile String currentUser = null;

    private HeartbeatManager heartbeatManager;
    private Consumer<String> kickListener;

    private final ReentrantLock requestLock = new ReentrantLock();
    private volatile CompletableFuture<String> pendingResponseFuture = null;

    public MailClient(String serverHost, int serverPort) throws IOException {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        initSocket();
    }

    private void initSocket() throws IOException {
        this.serverAddress = InetAddress.getByName(serverHost);
        this.socket = new DatagramSocket(); // Bind to ephemeral port
        this.running = true;

        this.receiverThread = new Thread(this::receiveLoop, "Client-Receiver-" + socket.getLocalPort());
        this.receiverThread.setDaemon(true);
        this.receiverThread.start();
    }

    public void setKickListener(Consumer<String> kickListener) {
        this.kickListener = kickListener;
    }

    public String getCurrentUser() {
        return currentUser;
    }

    public String getServerHost() {
        return serverHost;
    }

    public int getServerPort() {
        return serverPort;
    }

    public int getLocalPort() {
        return socket != null ? socket.getLocalPort() : -1;
    }

    /**
     * Dedicated background receiver loop.
     */
    private void receiveLoop() {
        while (running && socket != null && !socket.isClosed()) {
            try {
                byte[] buffer = new byte[Protocol.MAX_PACKET_SIZE];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String msg = PacketUtils.extractMessage(packet);
                handleIncomingMessage(msg);

            } catch (SocketException se) {
                if (!running) {
                    break;
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("Client socket receive error: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Handles an incoming message from the server.
     */
    private void handleIncomingMessage(String msg) {
        if (msg == null || msg.isEmpty()) {
            return;
        }

        // 1. Unsolicited KICK from Server
        if (msg.startsWith(Protocol.RESP_KICK + Protocol.DELIMITER)) {
            String[] parts = msg.split(Protocol.DELIMITER_REGEX, 2);
            String reason = parts.length > 1 ? parts[1] : "You were kicked by the administrator";

            stopHeartbeat();
            currentUser = null;

            if (kickListener != null) {
                kickListener.accept(reason);
            }

            CompletableFuture<String> future = pendingResponseFuture;
            if (future != null && !future.isDone()) {
                future.completeExceptionally(new RuntimeException("Kicked from server: " + reason));
            }
            return;
        }

        // 2. Heartbeat ACK
        if (msg.equals(Protocol.RESP_SUCCESS + Protocol.DELIMITER + "ACK")) {
            // Heartbeat acknowledged, discard so it doesn't collide with request responses
            return;
        }

        // 3. Regular Command Response (SUCCESS, ERROR, LIST, CONTENT)
        CompletableFuture<String> future = pendingResponseFuture;
        if (future != null && !future.isDone()) {
            future.complete(msg);
        }
    }

    /**
     * Sends a request to the server and awaits a matching response synchronously.
     */
    private String sendAndReceive(String request, int timeoutMs) throws IOException {
        requestLock.lock();
        try {
            CompletableFuture<String> future = new CompletableFuture<>();
            this.pendingResponseFuture = future;

            PacketUtils.send(socket, request, serverAddress, serverPort);

            try {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                throw new IOException("Request timed out after " + timeoutMs + "ms. Server may be unreachable.", te);
            } catch (ExecutionException ee) {
                throw new IOException("Execution error: " + ee.getCause().getMessage(), ee.getCause());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Request interrupted", ie);
            }
        } finally {
            this.pendingResponseFuture = null;
            requestLock.unlock();
        }
    }

    /**
     * Sends a fire-and-forget message (e.g. Heartbeat).
     */
    public void sendRaw(String message) {
        if (socket == null || socket.isClosed()) {
            return;
        }
        try {
            PacketUtils.send(socket, message, serverAddress, serverPort);
        } catch (IOException e) {
            System.err.println("Failed to send raw message: " + e.getMessage());
        }
    }

    /**
     * Registers a new account with username, email, and password.
     */
    public String register(String username, String email, String password) throws Exception {
        if (!Protocol.isValidUsername(username)) {
            throw new IllegalArgumentException("Username must be 3-20 alphanumeric characters or underscore.");
        }
        if (email != null && !email.trim().isEmpty() && !Protocol.isValidEmail(email.trim())) {
            throw new IllegalArgumentException("Invalid email format.");
        }
        String pwd = (password != null && !password.trim().isEmpty()) ? password.trim() : "123456";
        if (!Protocol.isValidPassword(pwd)) {
            throw new IllegalArgumentException("Password must be at least 4 characters.");
        }
        String cleanEmail = (email != null && !email.trim().isEmpty()) ? email.trim() : (username + "@udpmail.com");
        String req = Protocol.CMD_REGISTER + Protocol.DELIMITER + username + Protocol.DELIMITER + cleanEmail + Protocol.DELIMITER + pwd;
        String resp = sendAndReceive(req, DEFAULT_REQUEST_TIMEOUT_MS);
        return parseResponse(resp);
    }

    public String register(String username, String email) throws Exception {
        return register(username, email, "123456");
    }

    public String register(String username) throws Exception {
        return register(username, username + "@udpmail.com", "123456");
    }

    /**
     * Logs into an existing account with email or username and password.
     */
    public String login(String emailOrUser, String password) throws Exception {
        if (emailOrUser == null || emailOrUser.trim().isEmpty()) {
            throw new IllegalArgumentException("Email or username cannot be empty.");
        }
        String pwd = (password != null && !password.trim().isEmpty()) ? password.trim() : "123456";
        String req = Protocol.CMD_LOGIN + Protocol.DELIMITER + emailOrUser.trim() + Protocol.DELIMITER + pwd;
        String resp = sendAndReceive(req, DEFAULT_REQUEST_TIMEOUT_MS);

        if (resp.startsWith(Protocol.RESP_ERROR + Protocol.DELIMITER)) {
            throw new RuntimeException(resp.substring((Protocol.RESP_ERROR + Protocol.DELIMITER).length()));
        }

        if (resp.startsWith(Protocol.RESP_LIST + Protocol.DELIMITER)) {
            String[] parts = resp.split(Protocol.DELIMITER_REGEX);
            // Format: LIST|folder|unread|files|username
            if (parts.length >= 5) {
                this.currentUser = parts[4].trim();
            } else {
                this.currentUser = emailOrUser.trim();
            }
            startHeartbeat();
            return resp;
        }

        throw new RuntimeException("Unexpected response from server: " + resp);
    }

    public String login(String emailOrUser) throws Exception {
        return login(emailOrUser, "123456");
    }

    /**
     * Refreshes the mailbox inbox mail list.
     */
    public String refreshMailbox() throws Exception {
        return listFolder(Protocol.FOLDER_INBOX);
    }

    /**
     * Lists emails in a specific folder (INBOX, SENT, TRASH).
     */
    public String listFolder(String folder) throws Exception {
        if (currentUser == null) {
            throw new IllegalStateException("Not logged in.");
        }
        String req = Protocol.CMD_LIST_FOLDER + Protocol.DELIMITER + currentUser + Protocol.DELIMITER + folder;
        String resp = sendAndReceive(req, DEFAULT_REQUEST_TIMEOUT_MS);
        if (resp.startsWith(Protocol.RESP_ERROR + Protocol.DELIMITER)) {
            throw new RuntimeException(resp.substring((Protocol.RESP_ERROR + Protocol.DELIMITER).length()));
        }
        return resp;
    }

    /**
     * Sends an email to a recipient specified by email address.
     */
    public String sendMail(String recipientEmail, String content) throws Exception {
        if (currentUser == null) {
            throw new IllegalStateException("Not logged in.");
        }
        if (recipientEmail == null || recipientEmail.trim().isEmpty()) {
            throw new IllegalArgumentException("Recipient email cannot be empty.");
        }
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("Email content cannot be empty.");
        }

        String req = Protocol.CMD_SEND + Protocol.DELIMITER + currentUser + Protocol.DELIMITER
                + recipientEmail.trim() + Protocol.DELIMITER + content;
        String resp = sendAndReceive(req, DEFAULT_REQUEST_TIMEOUT_MS);
        return parseResponse(resp);
    }

    /**
     * Reads a specific mail file from a specified folder (INBOX, SENT, TRASH).
     */
    public String readMail(String folder, String filename) throws Exception {
        if (currentUser == null) {
            throw new IllegalStateException("Not logged in.");
        }
        if (!Protocol.isValidFilename(filename)) {
            throw new IllegalArgumentException("Invalid filename.");
        }

        String req = Protocol.CMD_READ + Protocol.DELIMITER + currentUser + Protocol.DELIMITER + folder + Protocol.DELIMITER + filename;
        String resp = sendAndReceive(req, DEFAULT_REQUEST_TIMEOUT_MS);

        if (resp.startsWith(Protocol.RESP_ERROR + Protocol.DELIMITER)) {
            throw new RuntimeException(resp.substring((Protocol.RESP_ERROR + Protocol.DELIMITER).length()));
        }

        if (resp.startsWith(Protocol.RESP_CONTENT + Protocol.DELIMITER)) {
            return resp.substring((Protocol.RESP_CONTENT + Protocol.DELIMITER).length());
        }

        throw new RuntimeException("Unexpected response format: " + resp);
    }

    public String readMail(String filename) throws Exception {
        return readMail(Protocol.FOLDER_INBOX, filename);
    }

    /**
     * Moves a mail from INBOX or SENT to TRASH, or permanently deletes it if already in TRASH.
     */
    public String deleteMail(String folder, String filename) throws Exception {
        if (currentUser == null) {
            throw new IllegalStateException("Not logged in.");
        }
        String req = Protocol.CMD_DELETE + Protocol.DELIMITER + currentUser + Protocol.DELIMITER + folder + Protocol.DELIMITER + filename;
        String resp = sendAndReceive(req, DEFAULT_REQUEST_TIMEOUT_MS);
        return parseResponse(resp);
    }

    /**
     * Logs out of current session and stops heartbeat.
     */
    public String logout() {
        if (currentUser == null) {
            return "Already logged out.";
        }
        String user = currentUser;
        stopHeartbeat();
        currentUser = null;

        try {
            String req = Protocol.CMD_LOGOUT + Protocol.DELIMITER + user;
            return sendAndReceive(req, 2000);
        } catch (Exception e) {
            return "Logged out locally (" + e.getMessage() + ")";
        }
    }

    private void startHeartbeat() {
        stopHeartbeat();
        if (currentUser != null) {
            heartbeatManager = new HeartbeatManager(currentUser, this::sendRaw);
            heartbeatManager.start();
        }
    }

    public void stopHeartbeat() {
        if (heartbeatManager != null) {
            heartbeatManager.stop();
            heartbeatManager = null;
        }
    }

    private String parseResponse(String resp) {
        if (resp.startsWith(Protocol.RESP_SUCCESS + Protocol.DELIMITER)) {
            return resp.substring((Protocol.RESP_SUCCESS + Protocol.DELIMITER).length());
        } else if (resp.startsWith(Protocol.RESP_ERROR + Protocol.DELIMITER)) {
            throw new RuntimeException(resp.substring((Protocol.RESP_ERROR + Protocol.DELIMITER).length()));
        }
        return resp;
    }

    /**
     * Closes the client socket and releases all resources.
     */
    public void close() {
        logout();
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (receiverThread != null && receiverThread.isAlive()) {
            receiverThread.interrupt();
        }
    }
}
