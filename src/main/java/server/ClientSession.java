package server;

import common.Protocol;

import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * ClientSession represents an active or past client session on the server.
 * Tracks network endpoints (IP, port), connection status, and activity timestamps.
 */
public class ClientSession {

    public static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String username;
    private volatile String email;
    private volatile InetAddress address;
    private volatile int port;
    private volatile Protocol.SessionStatus status;
    private volatile LocalDateTime loginTime;
    private volatile LocalDateTime lastActive;
    private volatile LocalDateTime logoutTime;

    public ClientSession(String username, InetAddress address, int port) {
        this(username, "", address, port);
    }

    public ClientSession(String username, String email, InetAddress address, int port) {
        this.username = username;
        this.email = email != null ? email : "";
        this.address = address;
        this.port = port;
        this.status = Protocol.SessionStatus.ONLINE;
        this.loginTime = LocalDateTime.now();
        this.lastActive = LocalDateTime.now();
        this.logoutTime = null;
    }

    public String getEmail() {
        return email;
    }

    public synchronized void setEmail(String email) {
        this.email = email != null ? email : "";
    }

    public String getUsername() {
        return username;
    }

    public InetAddress getAddress() {
        return address;
    }

    public synchronized void setAddress(InetAddress address) {
        this.address = address;
    }

    public int getPort() {
        return port;
    }

    public synchronized void setPort(int port) {
        this.port = port;
    }

    public Protocol.SessionStatus getStatus() {
        return status;
    }

    public synchronized void setStatus(Protocol.SessionStatus status) {
        this.status = status;
        if (status == Protocol.SessionStatus.OFFLINE && this.logoutTime == null) {
            this.logoutTime = LocalDateTime.now();
        }
    }

    public LocalDateTime getLoginTime() {
        return loginTime;
    }

    public synchronized void setLoginTime(LocalDateTime loginTime) {
        this.loginTime = loginTime;
    }

    public LocalDateTime getLastActive() {
        return lastActive;
    }

    public synchronized void updateLastActive() {
        this.lastActive = LocalDateTime.now();
    }

    public LocalDateTime getLogoutTime() {
        return logoutTime;
    }

    public synchronized void setLogoutTime(LocalDateTime logoutTime) {
        this.logoutTime = logoutTime;
    }

    /**
     * Calculates the formatted online duration for UI table presentation.
     */
    public String getOnlineDurationFormatted() {
        if (loginTime == null) {
            return "0s";
        }
        LocalDateTime end = (status == Protocol.SessionStatus.ONLINE) ? LocalDateTime.now() : 
                (logoutTime != null ? logoutTime : lastActive);
        if (end == null || end.isBefore(loginTime)) {
            end = loginTime;
        }
        Duration duration = Duration.between(loginTime, end);
        long seconds = Math.max(0, duration.getSeconds());
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (hours > 0) {
            return String.format("%02dh %02dm %02ds", hours, minutes, secs);
        } else if (minutes > 0) {
            return String.format("%02dm %02ds", minutes, secs);
        } else {
            return String.format("%ds", secs);
        }
    }

    public String formatTime(LocalDateTime dt) {
        return dt == null ? "-" : dt.format(TIME_FORMATTER);
    }
}
