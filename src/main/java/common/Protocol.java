package common;

import java.util.regex.Pattern;

/**
 * Protocol defines the standardized constants, commands, delimiters, 
 * packet size constraints, and validation rules for the UDP Mail system.
 */
public final class Protocol {

    private Protocol() {
        // Prevent instantiation
    }

    // Network defaults
    public static final String DEFAULT_HOST = "0.0.0.0";
    public static final int DEFAULT_PORT = 9999;
    public static final int MAX_PACKET_SIZE = 65507; // Maximum theoretical UDP datagram payload (64KB)

    // Timing constants (in milliseconds)
    public static final long HEARTBEAT_INTERVAL_MS = 5000L; // 5 seconds
    public static final long SESSION_TIMEOUT_MS = 15000L;   // 15 seconds
    public static final long TRASH_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L; // 30 days

    // Delimiters
    public static final String DELIMITER = "|";
    public static final String DELIMITER_REGEX = "\\|";
    public static final String LIST_SEPARATOR = ",";

    // Mailbox Folders
    public static final String FOLDER_INBOX = "INBOX";
    public static final String FOLDER_SENT = "SENT";
    public static final String FOLDER_TRASH = "TRASH";

    // Client -> Server Commands
    public static final String CMD_REGISTER = "REGISTER";
    public static final String CMD_LOGIN = "LOGIN";
    public static final String CMD_SEND = "SEND";
    public static final String CMD_READ = "READ";
    public static final String CMD_LIST_FOLDER = "LIST_FOLDER";
    public static final String CMD_DELETE = "DELETE";
    public static final String CMD_HEARTBEAT = "HEARTBEAT";
    public static final String CMD_LOGOUT = "LOGOUT";

    // Server -> Client Responses
    public static final String RESP_SUCCESS = "SUCCESS";
    public static final String RESP_ERROR = "ERROR";
    public static final String RESP_LIST = "LIST";
    public static final String RESP_CONTENT = "CONTENT";
    public static final String RESP_KICK = "KICK";

    // Account Statuses in accounts.txt
    public static final String ACCOUNT_ACTIVE = "ACTIVE";
    public static final String ACCOUNT_BANNED = "BANNED";

    // Session Statuses
    public enum SessionStatus {
        ONLINE,
        OFFLINE,
        BANNED
    }

    // Validation patterns
    // Username: 3 to 20 alphanumeric characters or underscore
    public static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,20}$");
    // Standard RFC-compliant email pattern
    public static final Pattern EMAIL_PATTERN = Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,63}$");

    /**
     * Validates whether a username conforms to security and formatting rules.
     * Prevents path traversal characters, spaces, and empty names.
     */
    public static boolean isValidUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            return false;
        }
        return USERNAME_PATTERN.matcher(username).matches();
    }

    /**
     * Validates whether an email conforms to valid format and character rules.
     */
    public static boolean isValidEmail(String email) {
        if (email == null || email.trim().isEmpty() || email.length() > 100) {
            return false;
        }
        return EMAIL_PATTERN.matcher(email.trim()).matches();
    }

    /**
     * Validates that the requested folder name is one of INBOX, SENT, or TRASH.
     */
    public static boolean isValidFolder(String folder) {
        if (folder == null) return false;
        String f = folder.trim().toUpperCase();
        return FOLDER_INBOX.equals(f) || FOLDER_SENT.equals(f) || FOLDER_TRASH.equals(f);
    }

    /**
     * Validates that a mail file name is safe and does not contain directory traversal tokens.
     */
    public static boolean isValidFilename(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return false;
        }
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            return false;
        }
        return filename.endsWith(".txt") && 
                (filename.startsWith("UNREAD_") || filename.startsWith("READ_") || filename.startsWith("SENT_"));
    }

    /**
     * Validates whether a password conforms to minimal security rules (at least 4 chars, no pipe delimiter).
     */
    public static boolean isValidPassword(String password) {
        if (password == null || password.length() < 4) {
            return false;
        }
        return !password.contains(DELIMITER);
    }
}
