package server;

import common.Protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * MailStorage manages persistent file-based storage for accounts and user mailboxes.
 * Supports accounts.txt (username|STATUS|email), multi-folder mailboxes (inbox, sent, trash),
 * path traversal security checks, and 30-day trash auto-purging.
 */
public class MailStorage {

    public static final String STORAGE_DIR_NAME = "mail_storage";
    public static final String ACCOUNTS_FILE_NAME = "accounts.txt";

    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter FILE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public static class AccountRecord {
        private final String username;
        private volatile String status;
        private volatile String email;
        private volatile String password;
        private volatile String loginTime;
        private volatile String logoutTime;
        private volatile String onlineDuration;

        public AccountRecord(String username, String status, String email, String password) {
            this(username, status, email, password, "-", "-", "-");
        }

        public AccountRecord(String username, String status, String email, String password,
                             String loginTime, String logoutTime, String onlineDuration) {
            this.username = username;
            this.status = status;
            this.email = email;
            this.password = (password != null && !password.trim().isEmpty()) ? password.trim() : "123456";
            this.loginTime = (loginTime != null && !loginTime.trim().isEmpty()) ? loginTime.trim() : "-";
            this.logoutTime = (logoutTime != null && !logoutTime.trim().isEmpty()) ? logoutTime.trim() : "-";
            this.onlineDuration = (onlineDuration != null && !onlineDuration.trim().isEmpty()) ? onlineDuration.trim() : "-";
        }

        public String getUsername() {
            return username;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getLoginTime() {
            return loginTime;
        }

        public void setLoginTime(String loginTime) {
            this.loginTime = (loginTime != null && !loginTime.trim().isEmpty()) ? loginTime.trim() : "-";
        }

        public String getLogoutTime() {
            return logoutTime;
        }

        public void setLogoutTime(String logoutTime) {
            this.logoutTime = (logoutTime != null && !logoutTime.trim().isEmpty()) ? logoutTime.trim() : "-";
        }

        public String getOnlineDuration() {
            return onlineDuration;
        }

        public void setOnlineDuration(String onlineDuration) {
            this.onlineDuration = (onlineDuration != null && !onlineDuration.trim().isEmpty()) ? onlineDuration.trim() : "-";
        }
    }

    private final Path storageRoot;
    private final Path accountsFile;
    private final ReentrantReadWriteLock accountsLock = new ReentrantReadWriteLock();

    // Cache of username -> AccountRecord
    private final Map<String, AccountRecord> accountsCache = new ConcurrentHashMap<>();
    // Lookup of email (lowercase) -> username
    private final Map<String, String> emailToUserCache = new ConcurrentHashMap<>();

    public MailStorage() throws IOException {
        this(Paths.get(STORAGE_DIR_NAME));
    }

    public MailStorage(Path storageRoot) throws IOException {
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
        this.accountsFile = this.storageRoot.resolve(ACCOUNTS_FILE_NAME).toAbsolutePath().normalize();
        initStorage();
    }

    /**
     * Initializes storage directories and loads existing accounts into memory cache.
     */
    private void initStorage() throws IOException {
        if (!Files.exists(storageRoot)) {
            Files.createDirectories(storageRoot);
        }
        if (!Files.exists(accountsFile)) {
            Files.createFile(accountsFile);
        } else {
            loadAccounts();
            ensureUserInfoFiles();
        }
    }

    private void ensureUserInfoFiles() {
        String nowStr = LocalDateTime.now().format(DATE_TIME_FORMATTER);
        for (AccountRecord record : accountsCache.values()) {
            try {
                Path userDir = getUserDirectory(record.getUsername());
                if (Files.exists(userDir)) {
                    Path infoFile = userDir.resolve("info.txt");
                    if (!Files.exists(infoFile)) {
                        String infoContent = "Username: " + record.getUsername() + "\n"
                                + "Password: " + record.getPassword() + "\n"
                                + "Email: " + record.getEmail() + "\n"
                                + "Created Date: " + nowStr + "\n";
                        Files.writeString(infoFile, infoContent, StandardCharsets.UTF_8, StandardOpenOption.CREATE);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Loads account records from accounts.txt with backward compatibility.
     * Supports formats: user|status|email|password, user|status|email, or user|status
     */
    public void loadAccounts() throws IOException {
        accountsLock.writeLock().lock();
        try {
            accountsCache.clear();
            emailToUserCache.clear();
            if (Files.exists(accountsFile)) {
                List<String> lines = Files.readAllLines(accountsFile, StandardCharsets.UTF_8);
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] parts = line.split("\\|", 7);
                    if (parts.length >= 2) {
                        String user = parts[0].trim();
                        String status = parts[1].trim().toUpperCase();
                        String email = (parts.length >= 3 && !parts[2].trim().isEmpty())
                                ? parts[2].trim().toLowerCase()
                                : (user + "@udpmail.com").toLowerCase();
                        String password = (parts.length >= 4 && !parts[3].trim().isEmpty())
                                ? parts[3].trim()
                                : "123456";
                        String loginTime = (parts.length >= 5 && !parts[4].trim().isEmpty())
                                ? parts[4].trim() : "-";
                        String logoutTime = (parts.length >= 6 && !parts[5].trim().isEmpty())
                                ? parts[5].trim() : "-";
                        String onlineDuration = (parts.length >= 7 && !parts[6].trim().isEmpty())
                                ? parts[6].trim() : "-";

                        AccountRecord record = new AccountRecord(user, status, email, password, loginTime, logoutTime, onlineDuration);
                        accountsCache.put(user, record);
                        emailToUserCache.put(email, user);
                    }
                }
            }
        } finally {
            accountsLock.writeLock().unlock();
        }
    }

    /**
     * Persists the current account cache to accounts.txt atomically.
     */
    private void saveAccounts() throws IOException {
        accountsLock.writeLock().lock();
        try {
            Path tempFile = storageRoot.resolve("accounts.txt.tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
                for (AccountRecord record : accountsCache.values()) {
                    writer.write(record.getUsername() + "|" + record.getStatus() + "|"
                            + record.getEmail() + "|" + record.getPassword() + "|"
                            + record.getLoginTime() + "|" + record.getLogoutTime() + "|"
                            + record.getOnlineDuration());
                    writer.newLine();
                }
            }
            Files.move(tempFile, accountsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            accountsLock.writeLock().unlock();
        }
    }

    /**
     * Updates account session time info (login, logout, duration) and persists to accounts.txt.
     */
    public void updateAccountSession(String username, String loginTime, String logoutTime, String onlineDuration) {
        if (username == null) return;
        accountsLock.writeLock().lock();
        try {
            AccountRecord record = accountsCache.get(username);
            if (record != null) {
                if (loginTime != null && !loginTime.isEmpty() && !"-".equals(loginTime)) {
                    record.setLoginTime(loginTime);
                }
                if (logoutTime != null && !logoutTime.isEmpty()) {
                    record.setLogoutTime(logoutTime);
                }
                if (onlineDuration != null && !onlineDuration.isEmpty()) {
                    record.setOnlineDuration(onlineDuration);
                }
                try {
                    saveAccounts();
                } catch (IOException e) {
                    System.err.println("Failed to save accounts after session update: " + e.getMessage());
                }
            }
        } finally {
            accountsLock.writeLock().unlock();
        }
    }

    public boolean accountExists(String username) {
        if (username == null) return false;
        accountsLock.readLock().lock();
        try {
            return accountsCache.containsKey(username);
        } finally {
            accountsLock.readLock().unlock();
        }
    }

    public boolean emailExists(String email) {
        if (email == null) return false;
        accountsLock.readLock().lock();
        try {
            return emailToUserCache.containsKey(email.trim().toLowerCase());
        } finally {
            accountsLock.readLock().unlock();
        }
    }

    public String getUserByEmail(String email) {
        if (email == null) return null;
        accountsLock.readLock().lock();
        try {
            return emailToUserCache.get(email.trim().toLowerCase());
        } finally {
            accountsLock.readLock().unlock();
        }
    }

    public String getEmailByUser(String username) {
        if (username == null) return null;
        accountsLock.readLock().lock();
        try {
            AccountRecord rec = accountsCache.get(username);
            return rec != null ? rec.getEmail() : null;
        } finally {
            accountsLock.readLock().unlock();
        }
    }

    public String getAccountStatus(String username) {
        if (username == null) return null;
        accountsLock.readLock().lock();
        try {
            AccountRecord rec = accountsCache.get(username);
            return rec != null ? rec.getStatus() : null;
        } finally {
            accountsLock.readLock().unlock();
        }
    }

    public boolean verifyPassword(String userOrEmail, String password) {
        if (userOrEmail == null || password == null) return false;
        accountsLock.readLock().lock();
        try {
            String username = userOrEmail.trim();
            AccountRecord record = accountsCache.get(username);
            if (record == null) {
                // Try looking up by email
                String userByEmail = emailToUserCache.get(userOrEmail.trim().toLowerCase());
                if (userByEmail != null) {
                    record = accountsCache.get(userByEmail);
                }
            }
            if (record == null) {
                return false;
            }
            return record.getPassword().equals(password.trim());
        } finally {
            accountsLock.readLock().unlock();
        }
    }

    public String resolveUsername(String userOrEmail) {
        if (userOrEmail == null) return null;
        accountsLock.readLock().lock();
        try {
            String clean = userOrEmail.trim();
            if (accountsCache.containsKey(clean)) {
                return clean;
            }
            return emailToUserCache.get(clean.toLowerCase());
        } finally {
            accountsLock.readLock().unlock();
        }
    }

    public Map<String, AccountRecord> getAllAccountRecords() {
        accountsLock.readLock().lock();
        try {
            return new HashMap<>(accountsCache);
        } finally {
            accountsLock.readLock().unlock();
        }
    }

    public Map<String, String> getAllAccounts() {
        accountsLock.readLock().lock();
        try {
            Map<String, String> map = new HashMap<>();
            for (AccountRecord rec : accountsCache.values()) {
                map.put(rec.getUsername(), rec.getStatus());
            }
            return map;
        } finally {
            accountsLock.readLock().unlock();
        }
    }

    public synchronized void registerAccount(String username) throws IOException {
        registerAccount(username, username + "@udpmail.com", "123456");
    }

    public synchronized void registerAccount(String username, String email) throws IOException {
        registerAccount(username, email, "123456");
    }

    /**
     * Registers a new account with username, email, and password.
     */
    public synchronized void registerAccount(String username, String email, String password) throws IOException {
        if (!Protocol.isValidUsername(username)) {
            throw new IllegalArgumentException("Invalid username. Allowed: [a-zA-Z0-9_]{3,20}");
        }

        if (email == null || email.trim().isEmpty()) {
            email = username + "@udpmail.com";
        }
        email = email.trim().toLowerCase();

        if (!Protocol.isValidEmail(email)) {
            throw new IllegalArgumentException("Invalid email format.");
        }

        if (!Protocol.isValidPassword(password)) {
            throw new IllegalArgumentException("Password must be at least 4 characters.");
        }

        if (accountExists(username)) {
            throw new IllegalStateException("Username already exists");
        }

        if (emailExists(email)) {
            throw new IllegalStateException("Email address already in use: " + email);
        }

        // Add to cache & persist
        AccountRecord record = new AccountRecord(username, Protocol.ACCOUNT_ACTIVE, email, password.trim());
        accountsCache.put(username, record);
        emailToUserCache.put(email, username);
        saveAccounts();

        // Create user directory and subdirectories: inbox, sent, trash
        Path userDir = getUserDirectory(username);
        if (!Files.exists(userDir)) Files.createDirectories(userDir);

        Path inboxDir = getFolderDirectory(username, Protocol.FOLDER_INBOX);
        Path sentDir = getFolderDirectory(username, Protocol.FOLDER_SENT);
        Path trashDir = getFolderDirectory(username, Protocol.FOLDER_TRASH);

        if (!Files.exists(inboxDir)) Files.createDirectories(inboxDir);
        if (!Files.exists(sentDir)) Files.createDirectories(sentDir);
        if (!Files.exists(trashDir)) Files.createDirectories(trashDir);

        String nowStr = LocalDateTime.now().format(DATE_TIME_FORMATTER);

        // Create user info text file inside account directory: info.txt
        Path infoFile = userDir.resolve("info.txt");
        String infoContent = "Username: " + username + "\n"
                + "Password: " + password.trim() + "\n"
                + "Email: " + email + "\n"
                + "Created Date: " + nowStr + "\n";
        Files.writeString(infoFile, infoContent, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        // Create welcome email in inbox: UNREAD_SYSTEM_new_email.txt
        Path welcomeMail = inboxDir.resolve("UNREAD_SYSTEM_new_email.txt");
        String welcomeContent = "From: SYSTEM (system@udpmail.com)\n"
                + "Sender IP: 127.0.0.1\n"
                + "To: " + email + "\n"
                + "Date: " + nowStr + "\n"
                + "Subject: Welcome to UDP Mail Service\n\n"
                + "Thank you for using this service. We hope that you will feel comfortable using our mail service.";
        Files.writeString(welcomeMail, welcomeContent, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public synchronized void setAccountStatus(String username, String newStatus) throws IOException {
        if (!accountExists(username)) {
            throw new IllegalArgumentException("Account does not exist: " + username);
        }
        AccountRecord record = accountsCache.get(username);
        record.setStatus(newStatus);
        saveAccounts();
    }

    public Path getUserDirectory(String username) {
        if (!Protocol.isValidUsername(username)) {
            throw new SecurityException("Security Alert: Invalid or malicious username: " + username);
        }
        Path userDir = storageRoot.resolve(username).normalize().toAbsolutePath();
        if (!userDir.startsWith(storageRoot)) {
            throw new SecurityException("Security Alert: Path traversal attempt on user directory: " + username);
        }
        return userDir;
    }

    public Path getFolderDirectory(String username, String folder) {
        if (!Protocol.isValidFolder(folder)) {
            throw new IllegalArgumentException("Invalid folder name: " + folder);
        }
        Path userDir = getUserDirectory(username);
        Path folderDir = userDir.resolve(folder.trim().toLowerCase()).normalize().toAbsolutePath();
        if (!folderDir.startsWith(userDir)) {
            throw new SecurityException("Security Alert: Path traversal attempt on user folder: " + folder);
        }
        return folderDir;
    }

    public Path resolveUserFile(String username, String folder, String filename) {
        if (!Protocol.isValidFilename(filename)) {
            throw new SecurityException("Security Alert: Invalid or illegal filename: " + filename);
        }
        Path folderDir = getFolderDirectory(username, folder);
        Path targetFile = folderDir.resolve(filename).normalize().toAbsolutePath();
        if (!targetFile.startsWith(folderDir)) {
            throw new SecurityException("Security Alert: Path traversal attempt targeting: " + filename);
        }
        return targetFile;
    }

    /**
     * Sends an email from sender to recipient identified by recipientEmail.
     * Saves incoming mail to recipient's inbox, and outgoing mail to sender's sent folder.
     */
    public synchronized String saveMail(String sender, String recipientEmail, String content) throws IOException {
        return saveMail(sender, "127.0.0.1", recipientEmail, content);
    }

    public synchronized String saveMail(String sender, String senderIp, String recipientEmail, String content) throws IOException {
        String recipient = getUserByEmail(recipientEmail);
        if (recipient == null) {
            throw new IllegalArgumentException("Recipient email does not exist: " + recipientEmail);
        }

        String recipientStatus = getAccountStatus(recipient);
        if (Protocol.ACCOUNT_BANNED.equalsIgnoreCase(recipientStatus)) {
            throw new IllegalStateException("Cannot send mail: Recipient account is banned");
        }

        Path recipientInbox = getFolderDirectory(recipient, Protocol.FOLDER_INBOX);
        if (!Files.exists(recipientInbox)) Files.createDirectories(recipientInbox);

        Path senderSent = getFolderDirectory(sender, Protocol.FOLDER_SENT);
        if (!Files.exists(senderSent)) Files.createDirectories(senderSent);

        LocalDateTime now = LocalDateTime.now();
        String dateHeader = now.format(DATE_TIME_FORMATTER);
        String baseTime = now.format(FILE_TIMESTAMP_FORMATTER);

        String senderEmail = getEmailByUser(sender);
        if (senderEmail == null) senderEmail = sender + "@udpmail.com";
        String ip = (senderIp != null && !senderIp.trim().isEmpty()) ? senderIp.trim() : "127.0.0.1";

        // 1. Deliver to Recipient's Inbox
        String inboxFilename = "UNREAD_" + sender + "_" + baseTime + ".txt";
        Path inboxFile = recipientInbox.resolve(inboxFilename);
        int seq = 1;
        while (Files.exists(inboxFile)) {
            inboxFilename = "UNREAD_" + sender + "_" + baseTime + "_" + seq + ".txt";
            inboxFile = recipientInbox.resolve(inboxFilename);
            seq++;
        }
        // Separate Subject and Body cleanly if content contains Subject header
        String mailSubject = "(No Subject)";
        String mailBody = content;
        if (content.startsWith("Subject: ")) {
            int subEnd = content.indexOf("\n\n");
            if (subEnd != -1) {
                mailSubject = content.substring("Subject: ".length(), subEnd).trim();
                mailBody = content.substring(subEnd + 2);
            }
        }

        String recipientMailContent = "From: " + sender + " (" + senderEmail + ")\n"
                + "Sender IP: " + ip + "\n"
                + "To: " + recipientEmail + "\n"
                + "Date: " + dateHeader + "\n"
                + "Subject: " + mailSubject + "\n\n"
                + mailBody;
        Files.writeString(inboxFile, recipientMailContent, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        // 2. Save copy in Sender's Sent Mailbox
        String sentFilename = "SENT_to_" + recipient + "_" + baseTime + ".txt";
        Path sentFile = senderSent.resolve(sentFilename);
        seq = 1;
        while (Files.exists(sentFile)) {
            sentFilename = "SENT_to_" + recipient + "_" + baseTime + "_" + seq + ".txt";
            sentFile = senderSent.resolve(sentFilename);
            seq++;
        }
        String sentMailContent = "To: " + recipient + " (" + recipientEmail + ")\n"
                + "Sender IP: " + ip + "\n"
                + "Date: " + dateHeader + "\n"
                + "Subject: " + mailSubject + "\n\n"
                + mailBody;
        Files.writeString(sentFile, sentMailContent, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        return inboxFilename;
    }

    /**
     * Reads mail from a specified folder (INBOX, SENT, TRASH).
     * If reading from INBOX and starts with UNREAD_, automatically renames to READ_.
     */
    public synchronized String readMail(String username, String folder, String filename) throws IOException {
        Path mailFile = resolveUserFile(username, folder, filename);
        if (!Files.exists(mailFile) || !Files.isRegularFile(mailFile)) {
            throw new FileNotFoundException("Mail file not found in " + folder + ": " + filename);
        }

        String content = Files.readString(mailFile, StandardCharsets.UTF_8);

        // If file is unread in INBOX, rename to READ_...
        if (Protocol.FOLDER_INBOX.equalsIgnoreCase(folder) && filename.startsWith("UNREAD_")) {
            String newFilename = "READ_" + filename.substring("UNREAD_".length());
            Path newFile = resolveUserFile(username, folder, newFilename);
            try {
                Files.move(mailFile, newFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.err.println("Warning: Failed to rename " + filename + " to " + newFilename + ": " + e.getMessage());
            }
        }

        return content;
    }

    public synchronized String readMail(String username, String filename) throws IOException {
        return readMail(username, Protocol.FOLDER_INBOX, filename);
    }

    /**
     * Moves a mail from INBOX or SENT to TRASH.
     * Sets last-modified time to current time for 30-day retention calculation.
     */
    public synchronized void moveToTrash(String username, String sourceFolder, String filename) throws IOException {
        if (Protocol.FOLDER_TRASH.equalsIgnoreCase(sourceFolder)) {
            throw new IllegalArgumentException("Mail is already in TRASH");
        }
        Path srcFile = resolveUserFile(username, sourceFolder, filename);
        if (!Files.exists(srcFile)) {
            throw new FileNotFoundException("Mail not found in " + sourceFolder + ": " + filename);
        }

        Path trashDir = getFolderDirectory(username, Protocol.FOLDER_TRASH);
        if (!Files.exists(trashDir)) {
            Files.createDirectories(trashDir);
        }

        Path destFile = trashDir.resolve(filename);
        int seq = 1;
        String baseName = filename.endsWith(".txt") ? filename.substring(0, filename.length() - 4) : filename;
        while (Files.exists(destFile)) {
            destFile = trashDir.resolve(baseName + "_" + seq + ".txt");
            seq++;
        }

        Files.move(srcFile, destFile, StandardCopyOption.REPLACE_EXISTING);
        // Stamp current time as deletion time for 30-day purge calculation
        try {
            Files.setLastModifiedTime(destFile, FileTime.from(Instant.now()));
        } catch (Exception ignored) {}
    }

    /**
     * Permanently deletes a file from TRASH.
     */
    public synchronized void deletePermanently(String username, String folder, String filename) throws IOException {
        Path targetFile = resolveUserFile(username, folder, filename);
        if (!Files.exists(targetFile)) {
            throw new FileNotFoundException("File not found to delete: " + filename);
        }
        Files.delete(targetFile);
    }

    /**
     * Lists mails in a specific folder (INBOX, SENT, TRASH).
     * Output: LIST|<folder>|<unread_count>|<file1>,<file2>,...
     */
    public synchronized String listFolder(String username, String folder) throws IOException {
        if (!Protocol.isValidFolder(folder)) {
            folder = Protocol.FOLDER_INBOX;
        }
        String folderName = folder.trim().toUpperCase();
        Path folderDir = getFolderDirectory(username, folderName);
        if (!Files.exists(folderDir)) {
            return Protocol.RESP_LIST + Protocol.DELIMITER + folderName + Protocol.DELIMITER + "0" + Protocol.DELIMITER;
        }

        List<String> files = new ArrayList<>();
        int unreadCount = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folderDir, "*.txt")) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    String name = entry.getFileName().toString();
                    if (name.startsWith("UNREAD_")) {
                        unreadCount++;
                    }
                    files.add(name);
                }
            }
        }

        // Sort files: UNREAD first, then descending
        files.sort((a, b) -> {
            boolean aUnread = a.startsWith("UNREAD_");
            boolean bUnread = b.startsWith("UNREAD_");
            if (aUnread && !bUnread) return -1;
            if (!aUnread && bUnread) return 1;
            return b.compareTo(a);
        });

        String fileListStr = String.join(Protocol.LIST_SEPARATOR, files);
        return Protocol.RESP_LIST + Protocol.DELIMITER + folderName + Protocol.DELIMITER + unreadCount + Protocol.DELIMITER + fileListStr;
    }

    public synchronized String listMails(String username) throws IOException {
        // Backward compatibility for login initial listing
        return listFolder(username, Protocol.FOLDER_INBOX);
    }

    /**
     * Purges all mail files in any user's TRASH folder that are older than retentionMillis (30 days).
     * Returns the total count of purged files.
     */
    public synchronized int purgeExpiredTrash(long retentionMillis) {
        int purgedCount = 0;
        long cutoffTime = System.currentTimeMillis() - retentionMillis;

        if (!Files.exists(storageRoot)) {
            return 0;
        }

        try (DirectoryStream<Path> users = Files.newDirectoryStream(storageRoot)) {
            for (Path userDir : users) {
                if (Files.isDirectory(userDir)) {
                    Path trashDir = userDir.resolve(Protocol.FOLDER_TRASH.toLowerCase());
                    if (Files.exists(trashDir) && Files.isDirectory(trashDir)) {
                        try (DirectoryStream<Path> trashFiles = Files.newDirectoryStream(trashDir, "*.txt")) {
                            for (Path file : trashFiles) {
                                if (Files.isRegularFile(file)) {
                                    try {
                                        long lastModified = Files.getLastModifiedTime(file).toMillis();
                                        if (lastModified < cutoffTime) {
                                            Files.delete(file);
                                            purgedCount++;
                                        }
                                    } catch (Exception e) {
                                        System.err.println("Error evaluating trash file " + file + ": " + e.getMessage());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error purging expired trash: " + e.getMessage());
        }

        return purgedCount;
    }
}
