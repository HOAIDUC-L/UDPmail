package server;

import common.Protocol;

import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * SessionManager manages client UDP sessions in a thread-safe manner using ConcurrentHashMap.
 * It periodically inspects sessions for heartbeat timeout (> 15 seconds) and provides
 * anti-spoofing verification against sender identity forgery.
 */
public class SessionManager {

    public interface SessionChangeListener {
        void onSessionChanged(ClientSession session);
    }

    private final ConcurrentMap<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final List<SessionChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Session-Timeout-Checker");
        t.setDaemon(true);
        return t;
    });

    private Consumer<ClientSession> timeoutCallback;

    public SessionManager() {
        startTimeoutChecker();
    }

    public void addSessionChangeListener(SessionChangeListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeSessionChangeListener(SessionChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(ClientSession session) {
        for (SessionChangeListener listener : listeners) {
            try {
                listener.onSessionChanged(session);
            } catch (Exception e) {
                System.err.println("Error notifying session listener: " + e.getMessage());
            }
        }
    }

    public void setTimeoutCallback(Consumer<ClientSession> callback) {
        this.timeoutCallback = callback;
    }

    /**
     * Starts periodic session timeout scanning every 1 second.
     */
    private void startTimeoutChecker() {
        scheduler.scheduleAtFixedRate(this::checkTimeouts, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Checks all ONLINE sessions. If lastActive was > 15s ago, transitions status to OFFLINE.
     */
    public void checkTimeouts() {
        LocalDateTime now = LocalDateTime.now();
        for (ClientSession session : sessions.values()) {
            if (session.getStatus() == Protocol.SessionStatus.ONLINE) {
                LocalDateTime lastActive = session.getLastActive();
                if (lastActive != null && Duration.between(lastActive, now).toMillis() > Protocol.SESSION_TIMEOUT_MS) {
                    session.setStatus(Protocol.SessionStatus.OFFLINE);
                    session.setLogoutTime(now);
                    notifyListeners(session);
                    if (timeoutCallback != null) {
                        try {
                            timeoutCallback.accept(session);
                        } catch (Exception e) {
                            System.err.println("Error executing timeout callback: " + e.getMessage());
                        }
                    }
                }
            }
        }
    }

    public ClientSession login(String username, InetAddress address, int port) {
        return login(username, "", address, port);
    }

    /**
     * Records or updates a client login session with email.
     */
    public ClientSession login(String username, String email, InetAddress address, int port) {
        ClientSession session = sessions.compute(username, (user, existing) -> {
            if (existing == null) {
                return new ClientSession(user, email, address, port);
            } else {
                if (email != null && !email.isEmpty()) {
                    existing.setEmail(email);
                }
                existing.setAddress(address);
                existing.setPort(port);
                existing.setStatus(Protocol.SessionStatus.ONLINE);
                existing.setLoginTime(LocalDateTime.now());
                existing.updateLastActive();
                existing.setLogoutTime(null);
                return existing;
            }
        });
        notifyListeners(session);
        return session;
    }

    /**
     * Updates heartbeat timestamp and client network endpoint.
     */
    public boolean updateHeartbeat(String usernameOrEmail, InetAddress address, int port) {
        if (usernameOrEmail == null) return false;
        ClientSession session = sessions.get(usernameOrEmail);
        if (session == null) {
            for (ClientSession s : sessions.values()) {
                if (s.getEmail() != null && s.getEmail().equalsIgnoreCase(usernameOrEmail.trim())) {
                    session = s;
                    break;
                }
            }
        }
        if (session != null && session.getStatus() == Protocol.SessionStatus.ONLINE) {
            session.setAddress(address);
            session.setPort(port);
            session.updateLastActive();
            notifyListeners(session);
            return true;
        }
        return false;
    }

    /**
     * Logs out a user session.
     */
    public ClientSession logout(String username) {
        ClientSession session = sessions.get(username);
        if (session != null) {
            session.setStatus(Protocol.SessionStatus.OFFLINE);
            session.setLogoutTime(LocalDateTime.now());
            notifyListeners(session);
        }
        return session;
    }

    /**
     * Kicks a user session.
     */
    public ClientSession kick(String username) {
        ClientSession session = sessions.get(username);
        if (session != null) {
            session.setStatus(Protocol.SessionStatus.OFFLINE);
            session.setLogoutTime(LocalDateTime.now());
            notifyListeners(session);
        }
        return session;
    }

    /**
     * Bans a user session.
     */
    public ClientSession ban(String username) {
        ClientSession session = sessions.get(username);
        if (session != null) {
            session.setStatus(Protocol.SessionStatus.BANNED);
            session.setLogoutTime(LocalDateTime.now());
            notifyListeners(session);
        } else {
            // Register session entry as BANNED even if not logged in
            session = new ClientSession(username, null, 0);
            session.setStatus(Protocol.SessionStatus.BANNED);
            session.setLogoutTime(LocalDateTime.now());
            sessions.put(username, session);
            notifyListeners(session);
        }
        return session;
    }

    /**
     * Unbans a user session.
     */
    public ClientSession unban(String username) {
        ClientSession session = sessions.get(username);
        if (session != null && session.getStatus() == Protocol.SessionStatus.BANNED) {
            session.setStatus(Protocol.SessionStatus.OFFLINE);
            notifyListeners(session);
        }
        return session;
    }

    /**
     * Validates whether an incoming UDP request claiming to be from sender
     * legitimately originates from the active session's registered IP and port.
     * Supports sender represented as username or registered email.
     */
    public boolean validateSender(String sender, InetAddress address, int port) {
        if (sender == null) return false;
        ClientSession session = sessions.get(sender);
        if (session == null) {
            for (ClientSession s : sessions.values()) {
                if (s.getEmail() != null && s.getEmail().equalsIgnoreCase(sender.trim())) {
                    session = s;
                    break;
                }
            }
        }
        if (session == null || session.getStatus() != Protocol.SessionStatus.ONLINE) {
            return false;
        }
        // Verify IP and Port match active session
        return session.getAddress() != null &&
               session.getAddress().equals(address) &&
               session.getPort() == port;
    }

    public ClientSession getSession(String username) {
        return sessions.get(username);
    }

    public Collection<ClientSession> getAllSessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    public long getOnlineCount() {
        return sessions.values().stream()
                .filter(s -> s.getStatus() == Protocol.SessionStatus.ONLINE)
                .count();
    }

    /**
     * Shuts down the background timeout scheduler.
     */
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
