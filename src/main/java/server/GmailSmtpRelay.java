package server;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.Properties;

/**
 * GmailSmtpRelay manages SMTP Gateway configuration and relays outgoing emails
 * from internal UDP clients to real Internet email addresses (Gmail, etc.)
 * using Jakarta Mail over TCP/TLS (port 587).
 */
public final class GmailSmtpRelay {

    public static final String DEFAULT_HOST = "smtp.gmail.com";
    public static final int DEFAULT_PORT = 587;
    public static final Path DEFAULT_CONFIG_PATH = Paths.get("server_config.properties");

    private static volatile boolean enabled = false;
    private static volatile String host = DEFAULT_HOST;
    private static volatile int port = DEFAULT_PORT;
    private static volatile String username = "";
    private static volatile String password = "";

    private static Path activeConfigPath = DEFAULT_CONFIG_PATH;

    static {
        // Automatically attempt to load configuration on class load
        loadConfig(DEFAULT_CONFIG_PATH);
    }

    private GmailSmtpRelay() {
        // Prevent instantiation
    }

    /**
     * Initializes the relay with a specific configuration file path.
     */
    public static synchronized void init(Path configPath) {
        if (configPath != null) {
            activeConfigPath = configPath;
            loadConfig(configPath);
        }
    }

    /**
     * Loads SMTP configuration from the specified properties file.
     */
    public static synchronized void loadConfig(Path configPath) {
        if (configPath == null || !Files.exists(configPath)) {
            return;
        }

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(configPath.toFile())) {
            props.load(fis);
            enabled = Boolean.parseBoolean(props.getProperty("smtp.gmail.enabled", "false"));
            host = props.getProperty("smtp.gmail.host", DEFAULT_HOST).trim();
            try {
                port = Integer.parseInt(props.getProperty("smtp.gmail.port", String.valueOf(DEFAULT_PORT)).trim());
            } catch (NumberFormatException e) {
                port = DEFAULT_PORT;
            }
            username = props.getProperty("smtp.gmail.username", "").trim();
            // Google App Passwords may have spaces (e.g. "abcd efgh ijkl mnop"), strip spaces
            password = props.getProperty("smtp.gmail.password", "").replaceAll("\\s+", "");
        } catch (IOException e) {
            System.err.println("Could not load SMTP config from " + configPath + ": " + e.getMessage());
        }
    }

    /**
     * Persists the current configuration to disk.
     */
    public static synchronized void saveConfig(Path configPath) throws IOException {
        if (configPath == null) {
            configPath = activeConfigPath;
        }

        Properties props = new Properties();
        props.setProperty("smtp.gmail.enabled", String.valueOf(enabled));
        props.setProperty("smtp.gmail.host", host != null ? host : DEFAULT_HOST);
        props.setProperty("smtp.gmail.port", String.valueOf(port));
        props.setProperty("smtp.gmail.username", username != null ? username : "");
        props.setProperty("smtp.gmail.password", password != null ? password : "");

        try (FileOutputStream fos = new FileOutputStream(configPath.toFile())) {
            props.store(fos, "MailUDP Server Configuration - Gmail SMTP Relay");
        }
    }

    /**
     * Updates runtime configuration and saves to the active properties file.
     */
    public static synchronized void updateConfig(String user, String pass, boolean isEnabled) throws IOException {
        username = (user != null) ? user.trim() : "";
        password = (pass != null) ? pass.replaceAll("\\s+", "") : "";
        enabled = isEnabled;
        saveConfig(activeConfigPath);
    }

    /**
     * Returns true if SMTP Relay is enabled and credentials are configured.
     */
    public static boolean isConfigured() {
        return enabled && username != null && !username.trim().isEmpty() && password != null && !password.trim().isEmpty();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean isEnabled) {
        enabled = isEnabled;
    }

    public static String getUsername() {
        return username;
    }

    public static String getPassword() {
        return password;
    }

    public static String getHost() {
        return host;
    }

    public static int getPort() {
        return port;
    }

    /**
     * Tests authentication against Gmail SMTP server without sending an email.
     */
    public static void testConnection(String testUser, String testPass) throws Exception {
        if (testUser == null || testUser.trim().isEmpty()) {
            throw new IllegalArgumentException("Vui lòng nhập địa chỉ Gmail!");
        }
        if (testPass == null || testPass.trim().isEmpty()) {
            throw new IllegalArgumentException("Vui lòng nhập Mật khẩu ứng dụng (Google App Password)!");
        }

        String cleanUser = testUser.trim();
        String cleanPass = testPass.replaceAll("\\s+", "");

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.host", host != null ? host : DEFAULT_HOST);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
        props.put("mail.smtp.connectiontimeout", "8000");
        props.put("mail.smtp.timeout", "8000");

        Session session = Session.getInstance(props);
        try (Transport transport = session.getTransport("smtp")) {
            transport.connect(host != null ? host : DEFAULT_HOST, port, cleanUser, cleanPass);
        }
    }

    /**
     * Relays an email message from an internal user to an external Internet email address.
     *
     * @param fromSender  The internal username/alias of the sender (e.g. "alice").
     * @param toEmail     The external recipient's email address (e.g. "bob@gmail.com").
     * @param subject     The subject line of the email.
     * @param bodyContent The UTF-8 text message body.
     * @throws Exception If SMTP connection or sending fails.
     */
    public static void relay(String fromSender, String toEmail, String subject, String bodyContent) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException("Gmail SMTP Relay chưa được cấu hình hoặc đang bị tắt. "
                    + "Vui lòng vào Server GUI -> Gmail Relay Config để thiết lập Gmail và App Password.");
        }

        final String cleanUser = username.trim();
        final String cleanPass = password.replaceAll("\\s+", "");

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.host", host != null ? host : DEFAULT_HOST);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(cleanUser, cleanPass);
            }
        });

        MimeMessage message = new MimeMessage(session);

        // Display alias as: "alice via MailUDP Server" <cleanUser>
        String personalAlias = (fromSender != null && !fromSender.trim().isEmpty())
                ? fromSender.trim() + " via MailUDP Server"
                : "MailUDP Server";
        message.setFrom(new InternetAddress(cleanUser, personalAlias, "UTF-8"));

        message.setRecipient(Message.RecipientType.TO, new InternetAddress(toEmail.trim()));

        // Subject with UTF-8 encoding
        String emailSubject = (subject != null && !subject.trim().isEmpty()) ? subject.trim() : "(No Subject)";
        message.setSubject(emailSubject, "UTF-8");

        message.setSentDate(new Date());

        // Body with UTF-8 encoding
        String emailBody = (bodyContent != null) ? bodyContent : "";
        message.setText(emailBody, "UTF-8", "plain");

        // Send via TCP/TLS
        Transport.send(message);
    }

    /**
     * Reloads configuration from disk.
     */
    public static synchronized void reloadConfig() {
        loadConfig(activeConfigPath);
    }

    public static void main(String[] args) {
        reloadConfig();
        System.out.println("Config path: " + activeConfigPath.toAbsolutePath());
        System.out.println("Enabled: " + isEnabled());
        System.out.println("Configured: " + isConfigured());
        System.out.println("Username: " + getUsername());
        System.out.println("Password length: " + (getPassword() != null ? getPassword().length() : 0));

        if (!getUsername().isEmpty() && !getPassword().isEmpty()) {
            System.out.println("Testing connection to smtp.gmail.com:587 with " + getUsername() + "...");
            try {
                testConnection(getUsername(), getPassword());
                System.out.println("TEST RESULT: SUCCESS! Authentication succeeded.");
            } catch (Exception e) {
                System.err.println("TEST RESULT: FAILED: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}
