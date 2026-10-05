package client;

import common.Protocol;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;

/**
 * MailClientGUI provides the Java Swing user interface for the UDP Mail Client.
 * Features Email-based registration/sending, live character/size indicators,
 * dedicated header/body preview panels, and a multi-folder dashboard:
 * Inbox, Sent, Trash (with 30-day auto-purge indication), and Mail Composer.
 */
public class MailClientGUI extends JFrame {

    private static final String CARD_AUTH = "AUTH";
    private static final String CARD_MAILBOX = "MAILBOX";

    private CardLayout cardLayout;
    private JPanel cardsPanel;

    // Network Engine
    private MailClient client;

    // --- Auth Screen Components ---
    private JTextField txtServerHost;
    private JTextField txtServerPort;
    private JTabbedPane authTabbedPane;

    // Login Tab Controls
    private JTextField txtLoginEmail;
    private JTextField txtLoginPassword;
    private JButton btnLogin;

    // Register Tab Controls
    private JTextField txtRegUsername;
    private JTextField txtRegEmail;
    private JTextField txtRegPassword;
    private JButton btnRegister;

    private JLabel lblAuthStatus;

    // --- Mailbox Screen Components ---
    private JLabel lblCurrentUser;
    private JLabel lblUnreadBadge;
    private JTabbedPane mailboxTabbedPane;

    // Inbox Tab
    private DefaultListModel<String> inboxListModel;
    private JList<String> inboxJList;
    private MailViewerPane inboxViewerPane;

    // Sent Tab
    private DefaultListModel<String> sentListModel;
    private JList<String> sentJList;
    private MailViewerPane sentViewerPane;

    // Trash Tab
    private DefaultListModel<String> trashListModel;
    private JList<String> trashJList;
    private MailViewerPane trashViewerPane;

    // Compose Tab
    private JTextField txtRecipientEmail;
    private JTextField txtSubject;
    private JTextArea txtContent;
    private JLabel lblComposeStats;
    private JButton btnSend;

    public MailClientGUI() {
        super("UDP Mail Client");
        initUI();
    }

    private void initUI() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(920, 680);
        setMinimumSize(new Dimension(800, 550));
        setLocationRelativeTo(null);

        cardLayout = new CardLayout();
        cardsPanel = new JPanel(cardLayout);

        cardsPanel.add(createAuthPanel(), CARD_AUTH);
        cardsPanel.add(createMailboxPanel(), CARD_MAILBOX);

        setContentPane(cardsPanel);
        cardLayout.show(cardsPanel, CARD_AUTH);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (client != null) {
                    client.close();
                }
            }
        });
    }

    // ==========================================
    // AUTHENTICATION VIEW (LOGIN / REGISTER)
    // ==========================================
    private JPanel createAuthPanel() {
        JPanel authPanel = new JPanel(new GridBagLayout());
        authPanel.setBackground(new Color(245, 247, 250));

        JPanel box = new JPanel(new BorderLayout(10, 15));
        box.setPreferredSize(new Dimension(440, 500));
        box.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(210, 215, 225), 1),
                new EmptyBorder(20, 25, 20, 25)
        ));
        box.setBackground(Color.WHITE);

        // Header Panel (Title + Server Config)
        JPanel topPanel = new JPanel(new GridBagLayout());
        topPanel.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(3, 4, 3, 4);

        JLabel lblTitle = new JLabel("UDP Mail Simulation", SwingConstants.CENTER);
        lblTitle.setFont(new Font("SansSerif", Font.BOLD, 20));
        lblTitle.setForeground(new Color(30, 50, 100));
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        topPanel.add(lblTitle, gbc);

        JLabel lblSubtitle = new JLabel("Client - Server Network Programming Demo", SwingConstants.CENTER);
        lblSubtitle.setFont(new Font("SansSerif", Font.PLAIN, 12));
        lblSubtitle.setForeground(Color.GRAY);
        gbc.gridy = 1;
        topPanel.add(lblSubtitle, gbc);

        // Server connection fields
        JPanel serverPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 2));
        serverPanel.setOpaque(false);
        serverPanel.setBorder(new EmptyBorder(6, 0, 8, 0));

        JLabel lblHost = new JLabel("Host:");
        lblHost.setFont(new Font("SansSerif", Font.PLAIN, 12));
        serverPanel.add(lblHost);
        txtServerHost = new JTextField("127.0.0.1", 10);
        txtServerHost.setPreferredSize(new Dimension(120, 28));
        txtServerHost.setFont(new Font("SansSerif", Font.PLAIN, 12));
        serverPanel.add(txtServerHost);

        JLabel lblPort = new JLabel("Port:");
        lblPort.setFont(new Font("SansSerif", Font.PLAIN, 12));
        serverPanel.add(lblPort);
        txtServerPort = new JTextField(String.valueOf(Protocol.DEFAULT_PORT), 5);
        txtServerPort.setPreferredSize(new Dimension(65, 28));
        txtServerPort.setFont(new Font("SansSerif", Font.PLAIN, 12));
        serverPanel.add(txtServerPort);

        gbc.gridy = 2;
        topPanel.add(serverPanel, gbc);
        box.add(topPanel, BorderLayout.NORTH);

        // Tabbed Pane for Login vs Register
        authTabbedPane = new JTabbedPane();
        authTabbedPane.setFont(new Font("SansSerif", Font.BOLD, 13));

        // --- TAB 1: LOGIN ---
        JPanel loginPanel = new JPanel(new GridBagLayout());
        loginPanel.setOpaque(false);
        loginPanel.setBorder(new EmptyBorder(20, 20, 20, 20));
        GridBagConstraints lgbc = new GridBagConstraints();
        lgbc.insets = new Insets(8, 8, 8, 8);

        // Row 0
        lgbc.gridx = 0; lgbc.gridy = 0;
        lgbc.weightx = 0.0;
        lgbc.fill = GridBagConstraints.NONE;
        lgbc.anchor = GridBagConstraints.EAST;
        JLabel lblLoginEmail = new JLabel("Email / Username:");
        lblLoginEmail.setFont(new Font("SansSerif", Font.PLAIN, 13));
        loginPanel.add(lblLoginEmail, lgbc);

        lgbc.gridx = 1;
        lgbc.weightx = 1.0;
        lgbc.fill = GridBagConstraints.HORIZONTAL;
        lgbc.anchor = GridBagConstraints.WEST;
        txtLoginEmail = new JTextField();
        txtLoginEmail.setPreferredSize(new Dimension(220, 32));
        txtLoginEmail.setFont(new Font("SansSerif", Font.PLAIN, 13));
        txtLoginEmail.setToolTipText("Nhập email (ví dụ: user@udpmail.com) hoặc username");
        loginPanel.add(txtLoginEmail, lgbc);

        // Row 1
        lgbc.gridx = 0; lgbc.gridy = 1;
        lgbc.weightx = 0.0;
        lgbc.fill = GridBagConstraints.NONE;
        lgbc.anchor = GridBagConstraints.EAST;
        JLabel lblLoginPass = new JLabel("Mật khẩu:");
        lblLoginPass.setFont(new Font("SansSerif", Font.PLAIN, 13));
        loginPanel.add(lblLoginPass, lgbc);

        lgbc.gridx = 1;
        lgbc.weightx = 1.0;
        lgbc.fill = GridBagConstraints.HORIZONTAL;
        lgbc.anchor = GridBagConstraints.WEST;
        txtLoginPassword = new JTextField();
        txtLoginPassword.setPreferredSize(new Dimension(220, 32));
        txtLoginPassword.setFont(new Font("SansSerif", Font.PLAIN, 13));
        txtLoginPassword.addActionListener(e -> onLoginClicked());
        loginPanel.add(txtLoginPassword, lgbc);

        // Row 2: Login button
        lgbc.gridx = 0; lgbc.gridy = 2; lgbc.gridwidth = 2;
        lgbc.weightx = 1.0;
        lgbc.fill = GridBagConstraints.HORIZONTAL;
        lgbc.insets = new Insets(18, 8, 8, 8);
        btnLogin = new JButton("Đăng nhập");
        btnLogin.setFont(new Font("SansSerif", Font.BOLD, 13));
        btnLogin.setPreferredSize(new Dimension(160, 36));
        btnLogin.setBackground(new Color(51, 122, 183));
        btnLogin.addActionListener(e -> onLoginClicked());
        loginPanel.add(btnLogin, lgbc);

        // Row 3: Spacer
        lgbc.gridx = 0; lgbc.gridy = 3; lgbc.gridwidth = 2;
        lgbc.weighty = 1.0;
        lgbc.fill = GridBagConstraints.VERTICAL;
        loginPanel.add(Box.createGlue(), lgbc);

        // --- TAB 2: REGISTER ---
        JPanel regPanel = new JPanel(new GridBagLayout());
        regPanel.setOpaque(false);
        regPanel.setBorder(new EmptyBorder(20, 20, 20, 20));
        GridBagConstraints rgbc = new GridBagConstraints();
        rgbc.insets = new Insets(8, 8, 8, 8);

        // Row 0
        rgbc.gridx = 0; rgbc.gridy = 0;
        rgbc.weightx = 0.0;
        rgbc.fill = GridBagConstraints.NONE;
        rgbc.anchor = GridBagConstraints.EAST;
        JLabel lblRegUser = new JLabel("Username:");
        lblRegUser.setFont(new Font("SansSerif", Font.PLAIN, 13));
        regPanel.add(lblRegUser, rgbc);

        rgbc.gridx = 1;
        rgbc.weightx = 1.0;
        rgbc.fill = GridBagConstraints.HORIZONTAL;
        rgbc.anchor = GridBagConstraints.WEST;
        txtRegUsername = new JTextField();
        txtRegUsername.setPreferredSize(new Dimension(220, 32));
        txtRegUsername.setFont(new Font("SansSerif", Font.PLAIN, 13));
        regPanel.add(txtRegUsername, rgbc);

        // Row 1
        rgbc.gridx = 0; rgbc.gridy = 1;
        rgbc.weightx = 0.0;
        rgbc.fill = GridBagConstraints.NONE;
        rgbc.anchor = GridBagConstraints.EAST;
        JLabel lblRegEmail = new JLabel("Email:");
        lblRegEmail.setFont(new Font("SansSerif", Font.PLAIN, 13));
        regPanel.add(lblRegEmail, rgbc);

        rgbc.gridx = 1;
        rgbc.weightx = 1.0;
        rgbc.fill = GridBagConstraints.HORIZONTAL;
        rgbc.anchor = GridBagConstraints.WEST;
        txtRegEmail = new JTextField();
        txtRegEmail.setPreferredSize(new Dimension(220, 32));
        txtRegEmail.setFont(new Font("SansSerif", Font.PLAIN, 13));
        txtRegEmail.setToolTipText("e.g. user@udpmail.com");
        regPanel.add(txtRegEmail, rgbc);

        // Row 2
        rgbc.gridx = 0; rgbc.gridy = 2;
        rgbc.weightx = 0.0;
        rgbc.fill = GridBagConstraints.NONE;
        rgbc.anchor = GridBagConstraints.EAST;
        JLabel lblRegPass = new JLabel("Mật khẩu:");
        lblRegPass.setFont(new Font("SansSerif", Font.PLAIN, 13));
        regPanel.add(lblRegPass, rgbc);

        rgbc.gridx = 1;
        rgbc.weightx = 1.0;
        rgbc.fill = GridBagConstraints.HORIZONTAL;
        rgbc.anchor = GridBagConstraints.WEST;
        txtRegPassword = new JTextField();
        txtRegPassword.setPreferredSize(new Dimension(220, 32));
        txtRegPassword.setFont(new Font("SansSerif", Font.PLAIN, 13));
        txtRegPassword.addActionListener(e -> onRegisterClicked());
        regPanel.add(txtRegPassword, rgbc);

        // Row 3: Register button
        rgbc.gridx = 0; rgbc.gridy = 3; rgbc.gridwidth = 2;
        rgbc.weightx = 1.0;
        rgbc.fill = GridBagConstraints.HORIZONTAL;
        rgbc.insets = new Insets(18, 8, 8, 8);
        btnRegister = new JButton("Đăng ký");
        btnRegister.setFont(new Font("SansSerif", Font.BOLD, 13));
        btnRegister.setPreferredSize(new Dimension(160, 36));
        btnRegister.setBackground(new Color(40, 167, 69));
        btnRegister.addActionListener(e -> onRegisterClicked());
        regPanel.add(btnRegister, rgbc);

        // Row 4: Spacer
        rgbc.gridx = 0; rgbc.gridy = 4; rgbc.gridwidth = 2;
        rgbc.weighty = 1.0;
        rgbc.fill = GridBagConstraints.VERTICAL;
        regPanel.add(Box.createGlue(), rgbc);

        authTabbedPane.addTab("Đăng nhập", loginPanel);
        authTabbedPane.addTab("Đăng ký", regPanel);
        box.add(authTabbedPane, BorderLayout.CENTER);

        // Status Label at bottom
        lblAuthStatus = new JLabel(" ", SwingConstants.CENTER);
        lblAuthStatus.setFont(new Font("SansSerif", Font.ITALIC, 11));
        box.add(lblAuthStatus, BorderLayout.SOUTH);

        authPanel.add(box);
        return authPanel;
    }

    // ==========================================
    // MAILBOX VIEW
    // ==========================================
    private JPanel createMailboxPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Top Header
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEtchedBorder(),
                new EmptyBorder(8, 12, 8, 12)
        ));

        JPanel userBadgePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
        lblCurrentUser = new JLabel("User: -");
        lblCurrentUser.setFont(new Font("SansSerif", Font.BOLD, 14));

        lblUnreadBadge = new JLabel("Unread: 0");
        lblUnreadBadge.setFont(new Font("SansSerif", Font.BOLD, 12));
        lblUnreadBadge.setForeground(new Color(200, 50, 50));

        userBadgePanel.add(lblCurrentUser);
        userBadgePanel.add(lblUnreadBadge);
        headerPanel.add(userBadgePanel, BorderLayout.WEST);

        JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton btnRefreshAll = new JButton("Refresh All");
        btnRefreshAll.addActionListener(e -> refreshAllFolders());

        JButton btnLogout = new JButton("Logout");
        btnLogout.addActionListener(e -> onLogoutClicked());

        headerActions.add(btnRefreshAll);
        headerActions.add(btnLogout);
        headerPanel.add(headerActions, BorderLayout.EAST);

        panel.add(headerPanel, BorderLayout.NORTH);

        // Center Tabbed Pane (Inbox, Sent, Trash, Compose)
        mailboxTabbedPane = new JTabbedPane();
        mailboxTabbedPane.addTab("Inbox", createFolderTab(Protocol.FOLDER_INBOX));
        mailboxTabbedPane.addTab("Sent Mail", createFolderTab(Protocol.FOLDER_SENT));
        mailboxTabbedPane.addTab("Trash", createFolderTab(Protocol.FOLDER_TRASH));
        mailboxTabbedPane.addTab("Compose Mail", createComposeTab());

        panel.add(mailboxTabbedPane, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createFolderTab(String folder) {
        JPanel tabContent = new JPanel(new BorderLayout(8, 8));
        tabContent.setBorder(new EmptyBorder(6, 6, 6, 6));

        if (Protocol.FOLDER_TRASH.equalsIgnoreCase(folder)) {
            JLabel lblTrashNotice = new JLabel(" \u2139 Thư trong thùng rác tự động được Server dọn sạch sau 30 ngày.");
            lblTrashNotice.setForeground(new Color(160, 50, 50));
            lblTrashNotice.setFont(new Font("SansSerif", Font.ITALIC, 12));
            lblTrashNotice.setBorder(new EmptyBorder(0, 4, 4, 4));
            tabContent.add(lblTrashNotice, BorderLayout.NORTH);
        }

        // Left: Mail file list
        JPanel listPanel = new JPanel(new BorderLayout(5, 5));
        listPanel.setPreferredSize(new Dimension(340, 400));
        listPanel.setBorder(BorderFactory.createTitledBorder(folder + " Items"));

        DefaultListModel<String> model = new DefaultListModel<>();
        JList<String> jList = new JList<>(model);
        jList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        jList.setFont(new Font("Monospaced", Font.PLAIN, 12));

        // Right: Content previewer
        MailViewerPane viewerPane = new MailViewerPane();

        // Store references
        if (Protocol.FOLDER_INBOX.equalsIgnoreCase(folder)) {
            inboxListModel = model;
            inboxJList = jList;
            inboxViewerPane = viewerPane;
        } else if (Protocol.FOLDER_SENT.equalsIgnoreCase(folder)) {
            sentListModel = model;
            sentJList = jList;
            sentViewerPane = viewerPane;
        } else if (Protocol.FOLDER_TRASH.equalsIgnoreCase(folder)) {
            trashListModel = model;
            trashJList = jList;
            trashViewerPane = viewerPane;
        }

        jList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                if (evt.getClickCount() == 2) {
                    onReadFolderMail(folder, jList, viewerPane);
                }
            }
        });

        listPanel.add(new JScrollPane(jList), BorderLayout.CENTER);

        JPanel listActions = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 2));
        JButton btnRead = new JButton("Read Mail");
        btnRead.addActionListener(e -> onReadFolderMail(folder, jList, viewerPane));
        listActions.add(btnRead);

        if (Protocol.FOLDER_TRASH.equalsIgnoreCase(folder)) {
            JButton btnDeletePermanent = new JButton("Delete Permanently");
            btnDeletePermanent.setForeground(Color.RED);
            btnDeletePermanent.addActionListener(e -> onDeleteMailClicked(folder, jList, viewerPane, true));
            listActions.add(btnDeletePermanent);
        } else {
            JButton btnMoveTrash = new JButton("Move to Trash");
            btnMoveTrash.addActionListener(e -> onDeleteMailClicked(folder, jList, viewerPane, false));
            listActions.add(btnMoveTrash);
        }

        JButton btnRefresh = new JButton("Refresh");
        btnRefresh.addActionListener(e -> refreshFolder(folder));
        listActions.add(btnRefresh);

        listPanel.add(listActions, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listPanel, viewerPane);
        split.setDividerLocation(350);
        tabContent.add(split, BorderLayout.CENTER);

        return tabContent;
    }

    private JPanel createComposeTab() {
        JPanel composePanel = new JPanel(new BorderLayout(10, 10));
        composePanel.setBorder(new EmptyBorder(12, 16, 12, 16));

        JPanel fieldsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(6, 6, 6, 6);

        gbc.gridx = 0; gbc.gridy = 0;
        fieldsPanel.add(new JLabel("Recipient Email:"), gbc);
        txtRecipientEmail = new JTextField();
        txtRecipientEmail.setToolTipText("Enter recipient's registered email, e.g. bob@udpmail.com");
        gbc.gridx = 1; gbc.weightx = 1.0;
        fieldsPanel.add(txtRecipientEmail, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0;
        fieldsPanel.add(new JLabel("Subject:"), gbc);
        txtSubject = new JTextField();
        gbc.gridx = 1; gbc.weightx = 1.0;
        fieldsPanel.add(txtSubject, gbc);

        composePanel.add(fieldsPanel, BorderLayout.NORTH);

        JPanel contentPanel = new JPanel(new BorderLayout(5, 5));
        contentPanel.setBorder(BorderFactory.createTitledBorder("Message Body"));
        txtContent = new JTextArea();
        txtContent.setFont(new Font("SansSerif", Font.PLAIN, 13));
        txtContent.setLineWrap(true);
        txtContent.setWrapStyleWord(true);
        contentPanel.add(new JScrollPane(txtContent), BorderLayout.CENTER);

        composePanel.add(contentPanel, BorderLayout.CENTER);

        // Bottom Bar: Live counter on Left, Action buttons on Right
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 5));

        lblComposeStats = new JLabel("Subject: 0 chars | Body: 0 chars | Size: 0 / 65507 bytes");
        lblComposeStats.setFont(new Font("SansSerif", Font.PLAIN, 12));
        lblComposeStats.setForeground(new Color(80, 85, 95));
        lblComposeStats.setBorder(new EmptyBorder(0, 5, 0, 0));
        bottomPanel.add(lblComposeStats, BorderLayout.WEST);

        DocumentListener docListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { updateStats(); }
            @Override
            public void removeUpdate(DocumentEvent e) { updateStats(); }
            @Override
            public void changedUpdate(DocumentEvent e) { updateStats(); }

            private void updateStats() {
                int subChars = txtSubject.getText().length();
                int bodyChars = txtContent.getText().length();
                String full = txtSubject.getText().trim().isEmpty() ? txtContent.getText()
                        : "Subject: " + txtSubject.getText().trim() + "\n\n" + txtContent.getText();
                int bytes = full.getBytes(StandardCharsets.UTF_8).length;
                lblComposeStats.setText(String.format("Subject: %d chars | Body: %d chars | Size: %d / %d bytes",
                        subChars, bodyChars, bytes, Protocol.MAX_PACKET_SIZE));
                if (bytes > Protocol.MAX_PACKET_SIZE) {
                    lblComposeStats.setForeground(Color.RED);
                } else {
                    lblComposeStats.setForeground(new Color(80, 85, 95));
                }
            }
        };

        txtSubject.getDocument().addDocumentListener(docListener);
        txtContent.getDocument().addDocumentListener(docListener);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        JButton btnClear = new JButton("Clear");
        btnClear.addActionListener(e -> {
            txtRecipientEmail.setText("");
            txtSubject.setText("");
            txtContent.setText("");
        });

        btnSend = new JButton("Send Mail");
        btnSend.setFont(new Font("SansSerif", Font.BOLD, 12));
        btnSend.setBackground(new Color(40, 167, 69));
        btnSend.addActionListener(e -> onSendMailClicked());

        btnPanel.add(btnClear);
        btnPanel.add(btnSend);
        bottomPanel.add(btnPanel, BorderLayout.EAST);

        composePanel.add(bottomPanel, BorderLayout.SOUTH);

        return composePanel;
    }

    // ==========================================
    // EVENT HANDLERS & LOGIC
    // ==========================================
    private boolean ensureClientConnected() {
        String host = txtServerHost.getText().trim();
        int port;
        try {
            port = Integer.parseInt(txtServerPort.getText().trim());
        } catch (NumberFormatException e) {
            setAuthStatus("Invalid server port.", Color.RED);
            return false;
        }

        if (client != null && (!client.getServerHost().equalsIgnoreCase(host) || client.getServerPort() != port)) {
            client.close();
            client = null;
        }

        if (client == null) {
            try {
                client = new MailClient(host, port);
                client.setKickListener(this::handleServerKick);
            } catch (Exception e) {
                setAuthStatus("Failed to open UDP socket: " + e.getMessage(), Color.RED);
                return false;
            }
        }
        return true;
    }

    private void onRegisterClicked() {
        if (!ensureClientConnected()) return;

        String username = txtRegUsername.getText().trim();
        String email = txtRegEmail.getText().trim();
        String password = txtRegPassword.getText().trim();

        if (!Protocol.isValidUsername(username)) {
            setAuthStatus("Username must be 3-20 alphanumeric characters or underscore.", Color.RED);
            return;
        }

        if (email.isEmpty()) {
            email = username + "@udpmail.com";
            txtRegEmail.setText(email);
        }

        if (!Protocol.isValidEmail(email)) {
            setAuthStatus("Invalid email format (e.g. user@domain.com).", Color.RED);
            return;
        }

        if (!Protocol.isValidPassword(password)) {
            setAuthStatus("Password must be at least 4 characters and cannot contain '|'.", Color.RED);
            return;
        }

        setAuthStatus("Registering...", Color.BLUE);
        btnRegister.setEnabled(false);

        final String regEmail = email;
        final String regPass = password;
        final String targetHost = txtServerHost.getText().trim();
        final String targetPort = txtServerPort.getText().trim();
        new Thread(() -> {
            try {
                String resp = client.register(username, regEmail, regPass);
                SwingUtilities.invokeLater(() -> {
                    setAuthStatus(resp, new Color(0, 120, 0));
                    JOptionPane.showMessageDialog(this, resp, "Registration Success", JOptionPane.INFORMATION_MESSAGE);
                    // Prefill credentials into login tab and switch
                    txtLoginEmail.setText(regEmail);
                    txtLoginPassword.setText(regPass);
                    authTabbedPane.setSelectedIndex(0);
                    txtRegUsername.setText("");
                    txtRegEmail.setText("");
                    txtRegPassword.setText("");
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    String err = e.getMessage() != null ? e.getMessage() : "";
                    if (err.contains("timed out") || err.contains("unreachable")) {
                        String msg = "Không thể kết nối đến Mail Server (" + targetHost + ":" + targetPort + ")!\n\n"
                                + "Hướng dẫn khắc phục khi chạy 2 máy khác nhau (Windows & Linux):\n"
                                + "1. Ô 'Host' trên máy Windows phải điền đúng IP mạng LAN của máy Linux (thay vì 127.0.0.1).\n"
                                + "   (Xem địa chỉ 'LAN IP' hiển thị màu đỏ trên cửa sổ Mail Server ở máy Linux).\n"
                                + "2. Máy Server (Linux) cần mở cổng tường lửa UDP 9999:\n"
                                + "   Chạy lệnh: sudo ufw allow 9999/udp\n"
                                + "3. Cả 2 máy phải kết nối chung một mạng Wi-Fi hoặc mạng LAN.";
                        JOptionPane.showMessageDialog(this, msg, "Lỗi kết nối Server (Timeout)", JOptionPane.ERROR_MESSAGE);
                        setAuthStatus("Timeout: Không kết nối được Server " + targetHost + ":" + targetPort, Color.RED);
                    } else {
                        setAuthStatus("Registration failed: " + e.getMessage(), Color.RED);
                    }
                });
            } finally {
                SwingUtilities.invokeLater(() -> {
                    btnRegister.setEnabled(true);
                });
            }
        }).start();
    }

    private void onLoginClicked() {
        if (!ensureClientConnected()) return;

        String emailOrUser = txtLoginEmail.getText().trim();
        String password = txtLoginPassword.getText().trim();

        if (emailOrUser.isEmpty()) {
            setAuthStatus("Please enter your Email or Username.", Color.RED);
            return;
        }
        if (password.isEmpty()) {
            setAuthStatus("Please enter your Password.", Color.RED);
            return;
        }

        setAuthStatus("Logging in...", Color.BLUE);
        btnLogin.setEnabled(false);

        final String targetHost = txtServerHost.getText().trim();
        final String targetPort = txtServerPort.getText().trim();
        new Thread(() -> {
            try {
                String listResponse = client.login(emailOrUser, password);
                SwingUtilities.invokeLater(() -> {
                    setAuthStatus("", Color.BLACK);
                    String user = client.getCurrentUser();
                    lblCurrentUser.setText("User: " + user);
                    setTitle("UDP Mail Client - [" + user + "]");
                    txtLoginPassword.setText("");
                    updateFolderList(Protocol.FOLDER_INBOX, listResponse);
                    cardLayout.show(cardsPanel, CARD_MAILBOX);
                    refreshAllFolders();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    String err = e.getMessage() != null ? e.getMessage() : "";
                    if (err.contains("timed out") || err.contains("unreachable")) {
                        String msg = "Không thể kết nối đến Mail Server (" + targetHost + ":" + targetPort + ")!\n\n"
                                + "Hướng dẫn khắc phục khi chạy 2 máy khác nhau (Windows & Linux):\n"
                                + "1. Ô 'Host' trên máy Windows phải điền đúng IP mạng LAN của máy Linux (thay vì 127.0.0.1).\n"
                                + "   (Xem địa chỉ 'LAN IP' hiển thị màu đỏ trên cửa sổ Mail Server ở máy Linux).\n"
                                + "2. Máy Server (Linux) cần mở cổng tường lửa UDP 9999:\n"
                                + "   Chạy lệnh: sudo ufw allow 9999/udp\n"
                                + "3. Cả 2 máy phải kết nối chung một mạng Wi-Fi hoặc mạng LAN.";
                        JOptionPane.showMessageDialog(this, msg, "Lỗi kết nối Server (Timeout)", JOptionPane.ERROR_MESSAGE);
                        setAuthStatus("Timeout: Không kết nối được Server " + targetHost + ":" + targetPort, Color.RED);
                    } else {
                        setAuthStatus("Login failed: " + e.getMessage(), Color.RED);
                    }
                });
            } finally {
                SwingUtilities.invokeLater(() -> {
                    btnLogin.setEnabled(true);
                });
            }
        }).start();
    }

    private void refreshAllFolders() {
        refreshFolder(Protocol.FOLDER_INBOX);
        refreshFolder(Protocol.FOLDER_SENT);
        refreshFolder(Protocol.FOLDER_TRASH);
    }

    private void refreshFolder(String folder) {
        if (client == null) return;
        new Thread(() -> {
            try {
                String listResp = client.listFolder(folder);
                SwingUtilities.invokeLater(() -> updateFolderList(folder, listResp));
            } catch (Exception e) {
                System.err.println("Error refreshing folder " + folder + ": " + e.getMessage());
            }
        }).start();
    }

    private void onReadFolderMail(String folder, JList<String> jList, MailViewerPane viewerPane) {
        String selected = jList.getSelectedValue();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Please select an email to view.", "Notice", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String filename = selected.trim();
        new Thread(() -> {
            try {
                String content = client.readMail(folder, filename);
                SwingUtilities.invokeLater(() -> {
                    viewerPane.displayMail(content);
                    // Refresh this folder so unread state updates if in inbox
                    refreshFolder(folder);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, "Failed to read mail: " + e.getMessage(), "Read Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();
    }

    private void onDeleteMailClicked(String folder, JList<String> jList, MailViewerPane viewerPane, boolean permanent) {
        String selected = jList.getSelectedValue();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Please select a mail to delete.", "Notice", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String msg = permanent ? "Are you sure you want to PERMANENTLY delete this mail?"
                : "Move this email to Trash?";
        int confirm = JOptionPane.showConfirmDialog(this, msg, "Confirm Delete", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        String filename = selected.trim();
        new Thread(() -> {
            try {
                String resp = client.deleteMail(folder, filename);
                SwingUtilities.invokeLater(() -> {
                    viewerPane.clear();
                    refreshFolder(folder);
                    refreshFolder(Protocol.FOLDER_TRASH);
                    JOptionPane.showMessageDialog(this, resp, "Success", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, "Delete failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();
    }

    private void onSendMailClicked() {
        String recipientEmail = txtRecipientEmail.getText().trim();
        String subject = txtSubject.getText().trim();
        String body = txtContent.getText();

        if (recipientEmail.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please specify a recipient email address.", "Validation", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (body.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter email content.", "Validation", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String fullContent = subject.isEmpty() ? body : "Subject: " + subject + "\n\n" + body;
        byte[] payloadBytes = fullContent.getBytes(StandardCharsets.UTF_8);
        if (payloadBytes.length > Protocol.MAX_PACKET_SIZE) {
            JOptionPane.showMessageDialog(this, "Email payload size (" + payloadBytes.length + " bytes) exceeds maximum UDP packet limit ("
                    + Protocol.MAX_PACKET_SIZE + " bytes).", "Payload Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        btnSend.setEnabled(false);
        new Thread(() -> {
            try {
                String resp = client.sendMail(recipientEmail, fullContent);
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, resp, "Success", JOptionPane.INFORMATION_MESSAGE);
                    txtRecipientEmail.setText("");
                    txtSubject.setText("");
                    txtContent.setText("");
                    mailboxTabbedPane.setSelectedIndex(1); // Switch to Sent tab to view sent copy
                    refreshFolder(Protocol.FOLDER_SENT);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, "Failed to send mail: " + e.getMessage(), "Send Error", JOptionPane.ERROR_MESSAGE);
                });
            } finally {
                SwingUtilities.invokeLater(() -> btnSend.setEnabled(true));
            }
        }).start();
    }

    private void onLogoutClicked() {
        if (client != null) {
            new Thread(() -> {
                client.logout();
                SwingUtilities.invokeLater(this::resetToAuthScreen);
            }).start();
        } else {
            resetToAuthScreen();
        }
    }

    private void handleServerKick(String reason) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, reason, "Kicked from Server", JOptionPane.WARNING_MESSAGE);
            resetToAuthScreen();
        });
    }

    private void resetToAuthScreen() {
        setTitle("UDP Mail Client");
        lblCurrentUser.setText("User: -");
        lblUnreadBadge.setText("Unread: 0");
        if (inboxListModel != null) inboxListModel.clear();
        if (sentListModel != null) sentListModel.clear();
        if (trashListModel != null) trashListModel.clear();
        if (inboxViewerPane != null) inboxViewerPane.clear();
        if (sentViewerPane != null) sentViewerPane.clear();
        if (trashViewerPane != null) trashViewerPane.clear();
        if (txtLoginPassword != null) txtLoginPassword.setText("");
        if (txtRegPassword != null) txtRegPassword.setText("");
        cardLayout.show(cardsPanel, CARD_AUTH);
        setAuthStatus("Logged out.", Color.DARK_GRAY);
    }

    private void updateFolderList(String folder, String listResponse) {
        // Format: LIST|<folder>|<unread_count>|<file1>,<file2>,...
        // Or legacy: LIST|<unread_count>|<file1>,...
        String[] parts = listResponse.split(Protocol.DELIMITER_REGEX);
        int unread = 0;
        String fileTokens = "";

        if (parts.length >= 4) {
            // LIST | folder | unread | files
            try { unread = Integer.parseInt(parts[2].trim()); } catch (NumberFormatException ignored) {}
            fileTokens = parts[3];
        } else if (parts.length == 3) {
            // LIST | unread | files
            try { unread = Integer.parseInt(parts[1].trim()); } catch (NumberFormatException ignored) {}
            fileTokens = parts[2];
        }

        DefaultListModel<String> model = null;
        if (Protocol.FOLDER_INBOX.equalsIgnoreCase(folder)) {
            model = inboxListModel;
            lblUnreadBadge.setText("Unread: " + unread);
        } else if (Protocol.FOLDER_SENT.equalsIgnoreCase(folder)) {
            model = sentListModel;
        } else if (Protocol.FOLDER_TRASH.equalsIgnoreCase(folder)) {
            model = trashListModel;
        }

        if (model == null) return;
        model.clear();

        if (fileTokens != null && !fileTokens.trim().isEmpty()) {
            String[] files = fileTokens.split(Protocol.LIST_SEPARATOR);
            for (String f : files) {
                String name = f.trim();
                if (!name.isEmpty()) {
                    model.addElement(name);
                }
            }
        }
    }

    private void setAuthStatus(String text, Color color) {
        lblAuthStatus.setText(text);
        lblAuthStatus.setForeground(color);
    }

    /**
     * MailViewerPane provides a structured, multi-field viewer for emails.
     * Separates From/To/Date metadata and Subject into dedicated header labels,
     * giving 100% of the scrollable text area to the message body.
     */
    public static class MailViewerPane extends JPanel {
        private final JLabel lblSenderRecipient;
        private final JLabel lblDate;
        private final JLabel lblSubject;
        private final JTextArea txtBodyViewer;
        private final JLabel lblFooterStats;

        public MailViewerPane() {
            super(new BorderLayout(0, 6));
            setBorder(new EmptyBorder(4, 4, 4, 4));

            // Top Header Box
            JPanel headerBox = new JPanel(new GridLayout(3, 1, 3, 3));
            headerBox.setBackground(new Color(245, 247, 252));
            headerBox.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(210, 220, 235), 1),
                    new EmptyBorder(8, 12, 8, 12)
            ));

            lblSenderRecipient = new JLabel("Select an email from the list to view.");
            lblSenderRecipient.setFont(new Font("SansSerif", Font.PLAIN, 12));
            lblSenderRecipient.setForeground(new Color(50, 60, 80));

            lblDate = new JLabel(" ");
            lblDate.setFont(new Font("SansSerif", Font.ITALIC, 11));
            lblDate.setForeground(Color.GRAY);

            lblSubject = new JLabel("Subject: -");
            lblSubject.setFont(new Font("SansSerif", Font.BOLD, 13));
            lblSubject.setForeground(new Color(20, 35, 70));

            headerBox.add(lblSenderRecipient);
            headerBox.add(lblDate);
            headerBox.add(lblSubject);
            add(headerBox, BorderLayout.NORTH);

            // Message Body Panel
            JPanel bodyPanel = new JPanel(new BorderLayout(4, 4));
            bodyPanel.setBorder(BorderFactory.createTitledBorder("Message Body"));

            txtBodyViewer = new JTextArea();
            txtBodyViewer.setEditable(false);
            txtBodyViewer.setFont(new Font("SansSerif", Font.PLAIN, 13));
            txtBodyViewer.setLineWrap(true);
            txtBodyViewer.setWrapStyleWord(true);
            txtBodyViewer.setMargin(new Insets(10, 10, 10, 10));

            JScrollPane scrollPane = new JScrollPane(txtBodyViewer);
            scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
            bodyPanel.add(scrollPane, BorderLayout.CENTER);

            lblFooterStats = new JLabel("Characters: 0", SwingConstants.RIGHT);
            lblFooterStats.setFont(new Font("SansSerif", Font.ITALIC, 11));
            lblFooterStats.setForeground(Color.GRAY);
            lblFooterStats.setBorder(new EmptyBorder(2, 0, 0, 6));
            bodyPanel.add(lblFooterStats, BorderLayout.SOUTH);

            add(bodyPanel, BorderLayout.CENTER);
        }

        public void displayMail(String content) {
            if (content == null || content.isEmpty()) {
                clear();
                return;
            }

            String[] lines = content.split("\\r?\\n");
            String senderTo = "";
            String date = "";
            String subject = "";
            StringBuilder body = new StringBuilder();
            boolean headersEnded = false;

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (!headersEnded) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        // Lookahead: do remaining lines have headers?
                        boolean upcomingHeader = false;
                        for (int j = i + 1; j < lines.length; j++) {
                            String nextTrim = lines[j].trim();
                            if (nextTrim.isEmpty()) continue;
                            if (nextTrim.startsWith("Subject:") || nextTrim.startsWith("From:") ||
                                    nextTrim.startsWith("To:") || nextTrim.startsWith("Date:") ||
                                    nextTrim.startsWith("Sender IP:") || nextTrim.startsWith("IP:")) {
                                upcomingHeader = true;
                            }
                            break;
                        }
                        if (upcomingHeader) {
                            continue; // skip blank line between headers
                        }
                        headersEnded = true;
                        continue;
                    }

                    if (trimmed.startsWith("Subject:")) {
                        subject = trimmed.substring("Subject:".length()).trim();
                    } else if (trimmed.startsWith("From:") || trimmed.startsWith("To:") ||
                            trimmed.startsWith("Sender IP:") || trimmed.startsWith("IP:")) {
                        if (!senderTo.isEmpty()) senderTo += "  |  ";
                        senderTo += trimmed;
                    } else if (trimmed.startsWith("Date:")) {
                        date = trimmed;
                    } else {
                        headersEnded = true;
                        body.append(line).append("\n");
                    }
                } else {
                    body.append(line).append("\n");
                }
            }

            String bodyStr = body.toString();
            // If subject was somehow not extracted in headers, check body start
            if (subject.isEmpty()) {
                String[] bodyLines = bodyStr.split("\\r?\\n");
                for (int b = 0; b < bodyLines.length; b++) {
                    String bTrim = bodyLines[b].trim();
                    if (bTrim.startsWith("Subject:")) {
                        subject = bTrim.substring("Subject:".length()).trim();
                        StringBuilder newBody = new StringBuilder();
                        for (int k = b + 1; k < bodyLines.length; k++) {
                            newBody.append(bodyLines[k]).append("\n");
                        }
                        bodyStr = newBody.toString();
                        break;
                    } else if (!bTrim.isEmpty()) {
                        break;
                    }
                }
            } else {
                // Ensure body doesn't redundantly display leading "Subject: ..."
                String[] bodyLines = bodyStr.split("\\r?\\n");
                if (bodyLines.length > 0 && bodyLines[0].trim().startsWith("Subject:")) {
                    StringBuilder newBody = new StringBuilder();
                    for (int k = 1; k < bodyLines.length; k++) {
                        newBody.append(bodyLines[k]).append("\n");
                    }
                    bodyStr = newBody.toString();
                }
            }

            bodyStr = bodyStr.replaceFirst("^(\\r?\\n)+", "");
            if (subject.isEmpty()) {
                subject = "(No Subject)";
            }

            lblSenderRecipient.setText(senderTo.isEmpty() ? "Email Metadata" : senderTo);
            lblDate.setText(date.isEmpty() ? " " : date);
            lblSubject.setText("Subject: " + subject);
            txtBodyViewer.setText(bodyStr);
            txtBodyViewer.setCaretPosition(0);
            lblFooterStats.setText("Body Characters: " + bodyStr.length());
        }

        public void clear() {
            lblSenderRecipient.setText("Select an email from the list to view.");
            lblDate.setText(" ");
            lblSubject.setText("Subject: -");
            txtBodyViewer.setText("");
            lblFooterStats.setText("Characters: 0");
        }
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            MailClientGUI gui = new MailClientGUI();
            gui.setVisible(true);
        });
    }
}
