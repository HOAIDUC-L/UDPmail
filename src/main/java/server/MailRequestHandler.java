package server;

import common.PacketUtils;
import common.Protocol;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.BiConsumer;

/**
 * MailRequestHandler processes incoming UDP packets in worker threads.
 * Parses protocol commands, enforces anti-spoofing, accesses storage and sessions,
 * and transmits UDP responses back to clients.
 */
public class MailRequestHandler implements Runnable {

    private static final DateTimeFormatter LOG_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final DatagramSocket socket;
    private final DatagramPacket incomingPacket;
    private final MailStorage mailStorage;
    private final SessionManager sessionManager;
    private final BiConsumer<String, String> logger; // (logType, message) -> void

    public MailRequestHandler(DatagramSocket socket,
                              DatagramPacket incomingPacket,
                              MailStorage mailStorage,
                              SessionManager sessionManager,
                              BiConsumer<String, String> logger) {
        this.socket = socket;
        this.incomingPacket = incomingPacket;
        this.mailStorage = mailStorage;
        this.sessionManager = sessionManager;
        this.logger = logger;
    }

    @Override
    public void run() {
        InetAddress clientAddress = incomingPacket.getAddress();
        int clientPort = incomingPacket.getPort();
        String rawMessage = PacketUtils.extractMessage(incomingPacket);

        log("RECEIVE", rawMessage + " from " + clientAddress.getHostAddress() + ":" + clientPort);

        String response = processRequest(rawMessage, clientAddress, clientPort);
        if (response != null && !response.isEmpty()) {
            try {
                PacketUtils.send(socket, response, clientAddress, clientPort);
                log("SEND", response + " to " + clientAddress.getHostAddress() + ":" + clientPort);
            } catch (IOException e) {
                log("ERROR", "Failed to send response to " + clientAddress.getHostAddress() + ":" + clientPort + ": " + e.getMessage());
            }
        }
    }

    private String processRequest(String rawMessage, InetAddress clientAddress, int clientPort) {
        if (rawMessage == null || rawMessage.trim().isEmpty()) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Empty request";
        }

        if (rawMessage.length() > Protocol.MAX_PACKET_SIZE) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Message too large";
        }

        String[] headerParts = rawMessage.split(Protocol.DELIMITER_REGEX, 2);
        String command = headerParts[0].trim().toUpperCase();

        try {
            switch (command) {
                case Protocol.CMD_REGISTER:
                    return handleRegister(rawMessage);

                case Protocol.CMD_LOGIN:
                    return handleLogin(rawMessage, clientAddress, clientPort);

                case Protocol.CMD_SEND:
                    return handleSend(rawMessage, clientAddress, clientPort);

                case Protocol.CMD_READ:
                    return handleRead(rawMessage, clientAddress, clientPort);

                case Protocol.CMD_LIST_FOLDER:
                    return handleListFolder(rawMessage, clientAddress, clientPort);

                case Protocol.CMD_DELETE:
                    return handleDelete(rawMessage, clientAddress, clientPort);

                case Protocol.CMD_HEARTBEAT:
                    return handleHeartbeat(rawMessage, clientAddress, clientPort);

                case Protocol.CMD_LOGOUT:
                    return handleLogout(rawMessage, clientAddress, clientPort);

                default:
                    return Protocol.RESP_ERROR + Protocol.DELIMITER + "Unknown command: " + command;
            }
        } catch (SecurityException se) {
            log("SECURITY", se.getMessage() + " from " + clientAddress.getHostAddress());
            return Protocol.RESP_ERROR + Protocol.DELIMITER + se.getMessage();
        } catch (Exception e) {
            log("ERROR", "Exception handling " + command + ": " + e.getMessage());
            return Protocol.RESP_ERROR + Protocol.DELIMITER + e.getMessage();
        }
    }

    private String handleRegister(String rawMessage) throws IOException {
        // Format: REGISTER|<username>|<email>|<password>
        String[] parts = rawMessage.split(Protocol.DELIMITER_REGEX, 4);
        if (parts.length < 2 || parts[1].trim().isEmpty()) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Username cannot be empty";
        }

        String username = parts[1].trim();
        if (!Protocol.isValidUsername(username)) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Invalid username format (Allowed: 3-20 alphanumeric characters or underscore)";
        }

        String email = (parts.length >= 3 && !parts[2].trim().isEmpty()) ? parts[2].trim().toLowerCase() : (username + "@udpmail.com");
        if (!Protocol.isValidEmail(email)) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Invalid email address format";
        }

        String password = (parts.length >= 4 && !parts[3].trim().isEmpty()) ? parts[3].trim() : "123456";
        if (!Protocol.isValidPassword(password)) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Password must be at least 4 characters";
        }

        if (mailStorage.accountExists(username)) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Username already exists";
        }

        if (mailStorage.emailExists(email)) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Email address already in use";
        }

        mailStorage.registerAccount(username, email, password);
        log("REGISTER", "Registered new user: " + username + " (" + email + ")");
        return Protocol.RESP_SUCCESS + Protocol.DELIMITER + "Register successfully";
    }

    private String handleLogin(String rawMessage, InetAddress address, int port) throws IOException {
        // Format: LOGIN|<email_or_user>|<password>
        String[] parts = rawMessage.split(Protocol.DELIMITER_REGEX, 3);
        if (parts.length < 2 || parts[1].trim().isEmpty()) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Email or username cannot be empty";
        }

        String identifier = parts[1].trim();
        String password = (parts.length >= 3) ? parts[2].trim() : "";

        // Resolve identifier to actual username (supports login by email or username)
        String username = mailStorage.resolveUsername(identifier);
        if (username == null) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Account does not exist: " + identifier;
        }

        // Verify password
        if (!mailStorage.verifyPassword(username, password.isEmpty() ? "123456" : password)) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Sai email hoac mat khau";
        }

        String status = mailStorage.getAccountStatus(username);
        if (Protocol.ACCOUNT_BANNED.equalsIgnoreCase(status)) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Tai khoan da bi khoa";
        }

        String email = mailStorage.getEmailByUser(username);

        // Register/update session
        sessionManager.login(username, email, address, port);
        log("LOGIN", "User " + username + " (" + email + ") logged in from " + address.getHostAddress() + ":" + port);

        // Return initial Inbox list along with canonical username
        return mailStorage.listFolder(username, Protocol.FOLDER_INBOX) + Protocol.DELIMITER + username;
    }

    private String handleSend(String rawMessage, InetAddress address, int port) throws IOException {
        // Format: SEND|<sender>|<recipient_email>|<content>
        // Must use split("\\|", 4) so pipe characters in content do not break protocol parsing
        String[] parts = rawMessage.split(Protocol.DELIMITER_REGEX, 4);
        if (parts.length < 4) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Invalid SEND syntax. Expected: SEND|sender|recipient_email|content";
        }

        String sender = parts[1].trim();
        String recipientEmail = parts[2].trim();
        String content = parts[3];

        if (content.trim().isEmpty()) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Mail content cannot be empty";
        }

        // Anti-spoofing verification
        if (!sessionManager.validateSender(sender, address, port)) {
            log("SECURITY", "Sender spoofing detected: Claimed " + sender + " from " + address.getHostAddress() + ":" + port);
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Unauthorized or spoofed sender: You must be logged in as " + sender;
        }

        // Check sender ban status
        if (Protocol.ACCOUNT_BANNED.equalsIgnoreCase(mailStorage.getAccountStatus(sender))) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Tai khoan da bi khoa";
        }

        // Check if recipient is specified by email or username
        String targetEmail = recipientEmail;
        if (!mailStorage.emailExists(targetEmail)) {
            if (mailStorage.accountExists(recipientEmail)) {
                // Auto-resolve username to email
                targetEmail = mailStorage.getEmailByUser(recipientEmail);
            } else {
                return Protocol.RESP_ERROR + Protocol.DELIMITER + "Recipient email does not exist: " + recipientEmail;
            }
        }

        String recipientUser = mailStorage.getUserByEmail(targetEmail);
        if (recipientUser == null) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Recipient email does not exist: " + targetEmail;
        }

        if (Protocol.ACCOUNT_BANNED.equalsIgnoreCase(mailStorage.getAccountStatus(recipientUser))) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Cannot send mail: Recipient account is banned";
        }

        // Save mail with sender IP
        String senderIp = (address != null) ? address.getHostAddress() : "127.0.0.1";
        mailStorage.saveMail(sender, senderIp, targetEmail, content);
        log("SEND_MAIL", "Mail delivered from " + sender + " (" + senderIp + ") to " + targetEmail + " (" + recipientUser + ")");
        return Protocol.RESP_SUCCESS + Protocol.DELIMITER + "Mail sent successfully";
    }

    private String handleRead(String rawMessage, InetAddress address, int port) throws IOException {
        // Format: READ|<username>|<folder>|<filename> or legacy READ|<username>|<filename>
        String[] parts = rawMessage.split(Protocol.DELIMITER_REGEX, 4);
        if (parts.length < 3) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Invalid READ syntax. Expected: READ|username|folder|filename";
        }

        String username = parts[1].trim();
        String folder = Protocol.FOLDER_INBOX;
        String filename;

        if (parts.length >= 4) {
            folder = parts[2].trim().toUpperCase();
            filename = parts[3].trim();
        } else {
            filename = parts[2].trim();
        }

        // Anti-spoofing verification
        if (!sessionManager.validateSender(username, address, port)) {
            log("SECURITY", "Read spoofing detected: Claimed " + username + " from " + address.getHostAddress() + ":" + port);
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Unauthorized access to mailbox";
        }

        if (Protocol.ACCOUNT_BANNED.equalsIgnoreCase(mailStorage.getAccountStatus(username))) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Tai khoan da bi khoa";
        }

        try {
            String content = mailStorage.readMail(username, folder, filename);
            return Protocol.RESP_CONTENT + Protocol.DELIMITER + content;
        } catch (FileNotFoundException fnf) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "File not found: " + filename;
        }
    }

    private String handleListFolder(String rawMessage, InetAddress address, int port) throws IOException {
        // Format: LIST_FOLDER|<username>|<folder>
        String[] parts = rawMessage.split(Protocol.DELIMITER_REGEX, 3);
        if (parts.length < 2) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Invalid LIST_FOLDER syntax";
        }

        String username = parts[1].trim();
        String folder = (parts.length >= 3) ? parts[2].trim().toUpperCase() : Protocol.FOLDER_INBOX;

        if (!sessionManager.validateSender(username, address, port)) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Unauthorized access to mailbox";
        }

        if (Protocol.ACCOUNT_BANNED.equalsIgnoreCase(mailStorage.getAccountStatus(username))) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Tai khoan da bi khoa";
        }

        return mailStorage.listFolder(username, folder);
    }

    private String handleDelete(String rawMessage, InetAddress address, int port) throws IOException {
        // Format: DELETE|<username>|<folder>|<filename>
        String[] parts = rawMessage.split(Protocol.DELIMITER_REGEX, 4);
        if (parts.length < 4) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Invalid DELETE syntax. Expected: DELETE|username|folder|filename";
        }

        String username = parts[1].trim();
        String folder = parts[2].trim().toUpperCase();
        String filename = parts[3].trim();

        if (!sessionManager.validateSender(username, address, port)) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Unauthorized operation";
        }

        if (Protocol.ACCOUNT_BANNED.equalsIgnoreCase(mailStorage.getAccountStatus(username))) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Tai khoan da bi khoa";
        }

        if (Protocol.FOLDER_TRASH.equalsIgnoreCase(folder)) {
            // Delete permanently from Trash
            mailStorage.deletePermanently(username, folder, filename);
            log("DELETE", "User " + username + " permanently deleted " + filename + " from TRASH");
            return Protocol.RESP_SUCCESS + Protocol.DELIMITER + "Mail deleted permanently";
        } else {
            // Move to Trash from Inbox or Sent
            mailStorage.moveToTrash(username, folder, filename);
            log("TRASH", "User " + username + " moved " + filename + " from " + folder + " to TRASH");
            return Protocol.RESP_SUCCESS + Protocol.DELIMITER + "Mail moved to Trash";
        }
    }

    private String handleHeartbeat(String rawMessage, InetAddress address, int port) {
        String[] parts = rawMessage.split(Protocol.DELIMITER_REGEX, 2);
        if (parts.length < 2) {
            return null;
        }
        String username = parts[1].trim();
        boolean updated = sessionManager.updateHeartbeat(username, address, port);
        if (updated) {
            return Protocol.RESP_SUCCESS + Protocol.DELIMITER + "ACK";
        }
        return null;
    }

    private String handleLogout(String rawMessage, InetAddress address, int port) {
        String[] parts = rawMessage.split(Protocol.DELIMITER_REGEX, 2);
        if (parts.length < 2) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Username cannot be empty";
        }

        String username = parts[1].trim();
        if (!sessionManager.validateSender(username, address, port)) {
            return Protocol.RESP_ERROR + Protocol.DELIMITER + "Unauthorized logout request";
        }

        sessionManager.logout(username);
        log("LOGOUT", "User " + username + " logged out");
        return Protocol.RESP_SUCCESS + Protocol.DELIMITER + "Logout successfully";
    }

    private void log(String type, String message) {
        if (logger != null) {
            logger.accept(type, message);
        } else {
            String timestamp = LocalDateTime.now().format(LOG_TIME_FORMATTER);
            System.out.println("[" + timestamp + "] [" + type + "] " + message);
        }
    }
}
