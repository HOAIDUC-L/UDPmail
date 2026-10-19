package server;

import common.Protocol;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MailServerGUI is the Administrative Dashboard for monitoring the UDP Mail Server.
 * Displays real-time session tracking in a JTable, live UDP packet logs, and admin actions.
 */
public class MailServerGUI extends JFrame {

    private final MailServer server;

    private JLabel lblStatus;
    private JLabel lblHost;
    private JLabel lblPort;
    private JLabel lblOnlineCount;
    private JLabel lblSmtpStatus;

    private JTable userTable;
    private DefaultTableModel tableModel;

    private JTextArea logArea;
    private JButton btnKick;
    private JButton btnBan;
    private JButton btnUnban;
    private JButton btnRefresh;
    private JButton btnSmtpConfig;

    private Timer refreshTimer;

    public MailServerGUI() {
        super("Mail UDP Server - Admin Dashboard");

        try {
            this.server = new MailServer();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Failed to initialize server storage: " + e.getMessage(),
                    "Fatal Error", JOptionPane.ERROR_MESSAGE);
            throw new RuntimeException(e);
        }

        initUI();
        startServer();
    }

    private void initUI() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 720);
        setMinimumSize(new Dimension(850, 600));
        setLocationRelativeTo(null);

        // Content panel
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // 1. Top Panel: Server Information
        JPanel topInfoPanel = createServerInfoPanel();
        mainPanel.add(topInfoPanel, BorderLayout.NORTH);

        // 2. Center Panel: SplitPane (User Table on top, UDP Log Console on bottom)
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setResizeWeight(0.5);

        // Users Panel
        JPanel usersPanel = createUsersPanel();
        splitPane.setTopComponent(usersPanel);

        // Log Console Panel
        JPanel logPanel = createLogPanel();
        splitPane.setBottomComponent(logPanel);

        mainPanel.add(splitPane, BorderLayout.CENTER);

        setContentPane(mainPanel);

        // Handle Window Closing
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdownServer();
            }
        });

        // Hook server logs into GUI console
        server.setLogConsumer((type, logLine) -> {
            SwingUtilities.invokeLater(() -> {
                if (logArea != null) {
                    logArea.append(logLine + "\n");
                    logArea.setCaretPosition(logArea.getDocument().getLength());
                }
            });
        });

        // Hook SessionManager updates into GUI
        server.getSessionManager().addSessionChangeListener(session -> {
            SwingUtilities.invokeLater(this::refreshUserTable);
        });

        // Setup timer to refresh durations and online count every 1 second
        refreshTimer = new Timer(1000, e -> refreshUserTable());
        refreshTimer.start();
    }

    private JPanel createServerInfoPanel() {
        JPanel panel = new JPanel(new GridLayout(1, 5, 10, 0));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "Server Information",
                        TitledBorder.LEFT, TitledBorder.TOP, new Font("SansSerif", Font.BOLD, 12)),
                new EmptyBorder(8, 12, 8, 12)
        ));

        lblStatus = new JLabel("Status: STARTING...");
        lblStatus.setFont(new Font("SansSerif", Font.BOLD, 13));
        lblStatus.setForeground(new Color(0, 128, 0));

        String localIp = common.PacketUtils.detectLanIp();

        lblHost = new JLabel("LAN IP: " + localIp);
        lblHost.setFont(new Font("SansSerif", Font.BOLD, 13));
        lblHost.setForeground(new Color(180, 40, 40));
        lblHost.setToolTipText("Client tren may khac (vi du: Windows) can nhap IP nay vao o Server Host");

        lblPort = new JLabel("Port: " + Protocol.DEFAULT_PORT);
        lblPort.setFont(new Font("SansSerif", Font.PLAIN, 13));

        lblOnlineCount = new JLabel("Connected / Online Users: 0");
        lblOnlineCount.setFont(new Font("SansSerif", Font.BOLD, 13));
        lblOnlineCount.setForeground(new Color(0, 70, 160));

        lblSmtpStatus = new JLabel("Gmail Relay: OFF");
        lblSmtpStatus.setFont(new Font("SansSerif", Font.BOLD, 12));
        updateSmtpStatusDisplay();

        panel.add(lblStatus);
        panel.add(lblHost);
        panel.add(lblPort);
        panel.add(lblOnlineCount);
        panel.add(lblSmtpStatus);

        return panel;
    }

    private JPanel createUsersPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "Registered Accounts & Sessions",
                TitledBorder.LEFT, TitledBorder.TOP, new Font("SansSerif", Font.BOLD, 12)));

        String[] columns = {"Username", "Password", "Email", "IP", "Port", "Status", "Login Time", "Logout Time", "Last Active", "Online Duration"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false; // Read-only
            }
        };

        userTable = new JTable(tableModel);
        userTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userTable.setRowHeight(24);
        userTable.getTableHeader().setReorderingAllowed(false);

        // Status column color renderer (column index 5)
        userTable.getColumnModel().getColumn(5).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int col) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
                if (value != null) {
                    String status = value.toString();
                    if ("ONLINE".equals(status)) {
                        c.setForeground(new Color(0, 150, 0));
                        setFont(getFont().deriveFont(Font.BOLD));
                    } else if ("BANNED".equals(status)) {
                        c.setForeground(Color.RED);
                        setFont(getFont().deriveFont(Font.BOLD));
                    } else {
                        c.setForeground(Color.GRAY);
                        setFont(getFont().deriveFont(Font.PLAIN));
                    }
                }
                return c;
            }
        });

        JScrollPane scrollPane = new JScrollPane(userTable);
        panel.add(scrollPane, BorderLayout.CENTER);

        // Admin Action buttons toolbar
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));

        btnSmtpConfig = new JButton("⚙ Gmail Relay Config");
        btnSmtpConfig.setToolTipText("Cấu hình tài khoản Gmail và App Password để chuyển tiếp thư ra Gmail thật");
        btnSmtpConfig.setFont(new Font("SansSerif", Font.BOLD, 12));
        btnSmtpConfig.setBackground(new Color(230, 245, 255));
        btnSmtpConfig.addActionListener(e -> openSmtpConfigDialog());

        btnKick = new JButton("Kick User");
        btnKick.setToolTipText("Disconnect the selected online user immediately");
        btnKick.addActionListener(e -> onKickClicked());

        btnBan = new JButton("Ban User");
        btnBan.setToolTipText("Lock user account and disconnect user");
        btnBan.setForeground(new Color(180, 0, 0));
        btnBan.addActionListener(e -> onBanClicked());

        btnUnban = new JButton("Unban User");
        btnUnban.setToolTipText("Unlock user account to permit login");
        btnUnban.setForeground(new Color(0, 120, 0));
        btnUnban.addActionListener(e -> onUnbanClicked());

        btnRefresh = new JButton("Refresh");
        btnRefresh.addActionListener(e -> refreshUserTable());

        btnPanel.add(btnSmtpConfig);
        btnPanel.add(btnKick);
        btnPanel.add(btnBan);
        btnPanel.add(btnUnban);
        btnPanel.add(btnRefresh);

        panel.add(btnPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "UDP Packet Log Console",
                TitledBorder.LEFT, TitledBorder.TOP, new Font("SansSerif", Font.BOLD, 12)));

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        logArea.setBackground(new Color(30, 30, 30));
        logArea.setForeground(new Color(220, 220, 220));

        JScrollPane logScroll = new JScrollPane(logArea);
        panel.add(logScroll, BorderLayout.CENTER);

        JPanel logControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 2));
        JButton btnClearLog = new JButton("Clear Console");
        btnClearLog.addActionListener(e -> logArea.setText(""));
        logControls.add(btnClearLog);

        panel.add(logControls, BorderLayout.SOUTH);
        return panel;
    }

    private void startServer() {
        new Thread(() -> {
            try {
                server.start();
                SwingUtilities.invokeLater(() -> {
                    lblStatus.setText("Status: RUNNING");
                    lblStatus.setForeground(new Color(0, 130, 0));
                    refreshUserTable();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    lblStatus.setText("Status: FAILED TO START");
                    lblStatus.setForeground(Color.RED);
                    JOptionPane.showMessageDialog(this, "Failed to start UDP Server: " + e.getMessage(),
                            "Server Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "Server-Starter").start();
    }

    private void refreshUserTable() {
        if (server == null) return;

        int selectedRow = userTable.getSelectedRow();
        String selectedUsername = null;
        if (selectedRow >= 0 && selectedRow < tableModel.getRowCount()) {
            selectedUsername = (String) tableModel.getValueAt(selectedRow, 0);
        }

        // Get accounts from accounts.txt
        Map<String, MailStorage.AccountRecord> accounts = server.getMailStorage().getAllAccountRecords();
        SessionManager sessionMgr = server.getSessionManager();

        tableModel.setRowCount(0);
        long onlineCount = 0;

        List<String> sortedUsers = new ArrayList<>(accounts.keySet());
        Collections.sort(sortedUsers);

        int reselectIndex = -1;
        int rowIndex = 0;

        for (String user : sortedUsers) {
            MailStorage.AccountRecord accRecord = accounts.get(user);
            String accStatus = accRecord != null ? accRecord.getStatus() : "ACTIVE";
            String email = accRecord != null ? accRecord.getEmail() : "-";
            String password = accRecord != null ? accRecord.getPassword() : "-";
            ClientSession session = sessionMgr.getSession(user);

            String ip = "-";
            String port = "-";
            String status = accStatus;
            String loginTime = accRecord != null ? accRecord.getLoginTime() : "-";
            String logoutTime = accRecord != null ? accRecord.getLogoutTime() : "-";
            String lastActive = "-";
            String onlineDuration = accRecord != null ? accRecord.getOnlineDuration() : "-";

            if (session != null) {
                if (session.getAddress() != null) {
                    ip = session.getAddress().getHostAddress();
                }
                if (session.getPort() > 0) {
                    port = String.valueOf(session.getPort());
                }
                if (session.getEmail() != null && !session.getEmail().isEmpty()) {
                    email = session.getEmail();
                }
                if (Protocol.ACCOUNT_BANNED.equalsIgnoreCase(accStatus) ||
                        session.getStatus() == Protocol.SessionStatus.BANNED) {
                    status = "BANNED";
                } else {
                    status = session.getStatus().name();
                }
                loginTime = session.formatTime(session.getLoginTime());
                logoutTime = session.formatTime(session.getLogoutTime());
                lastActive = session.formatTime(session.getLastActive());
                onlineDuration = session.getOnlineDurationFormatted();
            }

            if ("ONLINE".equals(status)) {
                onlineCount++;
            }

            tableModel.addRow(new Object[]{
                    user, password, email, ip, port, status, loginTime, logoutTime, lastActive, onlineDuration
            });

            if (user.equals(selectedUsername)) {
                reselectIndex = rowIndex;
            }
            rowIndex++;
        }

        lblOnlineCount.setText("Connected / Online Users: " + onlineCount);

        if (reselectIndex >= 0) {
            userTable.setRowSelectionInterval(reselectIndex, reselectIndex);
        }
    }

    private String getSelectedUsername() {
        int selectedRow = userTable.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(this, "Please select a user from the table first.", "Selection Required", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return (String) tableModel.getValueAt(selectedRow, 0);
    }

    private void onKickClicked() {
        String username = getSelectedUsername();
        if (username == null) return;

        int confirm = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to KICK online user '" + username + "'?",
                "Confirm Kick", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            server.kickUser(username);
            refreshUserTable();
        }
    }

    private void onBanClicked() {
        String username = getSelectedUsername();
        if (username == null) return;

        int confirm = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to BAN user '" + username + "'?\nThis will disconnect them and lock their account.",
                "Confirm Ban", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            server.banUser(username);
            refreshUserTable();
        }
    }

    private void onUnbanClicked() {
        String username = getSelectedUsername();
        if (username == null) return;

        int confirm = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to UNBAN user '" + username + "'?",
                "Confirm Unban", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            server.unbanUser(username);
            refreshUserTable();
        }
    }

    private void updateSmtpStatusDisplay() {
        if (lblSmtpStatus == null) return;
        if (GmailSmtpRelay.isConfigured()) {
            lblSmtpStatus.setText("Gmail Relay: ACTIVE (" + GmailSmtpRelay.getUsername() + ")");
            lblSmtpStatus.setForeground(new Color(0, 130, 0));
            lblSmtpStatus.setToolTipText("Hệ thống sẵn sàng chuyển tiếp thư ra Gmail thật");
        } else if (GmailSmtpRelay.isEnabled()) {
            lblSmtpStatus.setText("Gmail Relay: ENABLED (Chưa có mật khẩu)");
            lblSmtpStatus.setForeground(new Color(200, 120, 0));
            lblSmtpStatus.setToolTipText("Cần nhập đầy đủ Gmail và Google App Password");
        } else {
            lblSmtpStatus.setText("Gmail Relay: OFF");
            lblSmtpStatus.setForeground(Color.GRAY);
            lblSmtpStatus.setToolTipText("Chưa kích hoạt tính năng chuyển tiếp Gmail");
        }
    }

    private void openSmtpConfigDialog() {
        JDialog dialog = new JDialog(this, "⚙ Cấu hình Gmail SMTP Relay Gateway", true);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setSize(520, 360);
        dialog.setLocationRelativeTo(this);
        dialog.setResizable(false);

        JPanel contentPanel = new JPanel(new GridBagLayout());
        contentPanel.setBorder(new EmptyBorder(15, 20, 10, 20));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        GmailSmtpRelay.reloadConfig();
        boolean initialEnabled = GmailSmtpRelay.isEnabled() || (!GmailSmtpRelay.getUsername().isEmpty() && !GmailSmtpRelay.getPassword().isEmpty());
        JCheckBox chkEnable = new JCheckBox("Kích hoạt chuyển tiếp thư ra Gmail thật (SMTP Relay Gateway)", initialEnabled);
        chkEnable.setFont(new Font("SansSerif", Font.BOLD, 12));

        JTextField txtGmail = new JTextField(GmailSmtpRelay.getUsername(), 25);
        JPasswordField txtAppPassword = new JPasswordField(GmailSmtpRelay.getPassword(), 25);

        JLabel lblNotice = new JLabel("<html><font color='#555555'><b>Lưu ý:</b> Mật khẩu là <b>Google App Password (16 ký tự)</b>, "
                + "KHÔNG PHẢI mật khẩu đăng nhập Google.<br>"
                + "Tạo tại: <i>Google Account &rarr; Security &rarr; 2-Step Verification &rarr; App passwords</i>.</font></html>");
        lblNotice.setFont(new Font("SansSerif", Font.PLAIN, 11));

        JLabel lblTestResult = new JLabel(" ");
        lblTestResult.setFont(new Font("SansSerif", Font.BOLD, 12));

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        contentPanel.add(chkEnable, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1;
        contentPanel.add(new JLabel("Gmail Server:"), gbc);
        gbc.gridx = 1; gbc.gridy = 1;
        contentPanel.add(txtGmail, gbc);

        gbc.gridx = 0; gbc.gridy = 2;
        contentPanel.add(new JLabel("App Password:"), gbc);
        gbc.gridx = 1; gbc.gridy = 2;
        contentPanel.add(txtAppPassword, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2;
        contentPanel.add(lblNotice, gbc);

        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2;
        contentPanel.add(lblTestResult, gbc);

        dialog.add(contentPanel, BorderLayout.CENTER);

        // Buttons
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        JButton btnTest = new JButton("Kiểm tra kết nối");
        JButton btnSave = new JButton("Lưu cấu hình");
        JButton btnCancel = new JButton("Đóng");

        btnTest.addActionListener(e -> {
            String testUser = txtGmail.getText().trim();
            String testPass = new String(txtAppPassword.getPassword()).trim();
            if (testUser.isEmpty() || testPass.isEmpty()) {
                lblTestResult.setText("Vui lòng nhập email và App Password!");
                lblTestResult.setForeground(Color.RED);
                return;
            }

            btnTest.setEnabled(false);
            lblTestResult.setText("Đang kiểm tra kết nối tới smtp.gmail.com:587 (TLS)...");
            lblTestResult.setForeground(new Color(0, 70, 160));

            new Thread(() -> {
                try {
                    GmailSmtpRelay.testConnection(testUser, testPass);
                    SwingUtilities.invokeLater(() -> {
                        lblTestResult.setText("✓ Kết nối và xác thực Gmail SMTP thành công!");
                        lblTestResult.setForeground(new Color(0, 140, 0));
                        chkEnable.setSelected(true);
                        btnTest.setEnabled(true);
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        lblTestResult.setText("✗ Lỗi xác thực: " + ex.getMessage());
                        lblTestResult.setForeground(Color.RED);
                        btnTest.setEnabled(true);
                    });
                }
            }).start();
        });

        btnSave.addActionListener(e -> {
            String user = txtGmail.getText().trim();
            String pass = new String(txtAppPassword.getPassword()).trim();
            boolean enabled = chkEnable.isSelected();

            if (!user.isEmpty() && !pass.isEmpty() && !enabled) {
                int option = JOptionPane.showConfirmDialog(dialog,
                        "Bạn đã nhập thông tin Gmail nhưng chưa tích chọn ô 'Kích hoạt'.\n"
                        + "Bạn có muốn BẬT tính năng chuyển tiếp ngay bây giờ không?",
                        "Kích hoạt Gmail Relay", JOptionPane.YES_NO_OPTION);
                if (option == JOptionPane.YES_OPTION) {
                    enabled = true;
                    chkEnable.setSelected(true);
                }
            }

            try {
                GmailSmtpRelay.updateConfig(user, pass, enabled);
                updateSmtpStatusDisplay();
                String statusMsg = enabled
                        ? "Đã lưu cấu hình và BẬT tính năng Gmail SMTP Relay thành công!"
                        : "Đã lưu cấu hình (Trạng thái: TẮT).";
                JOptionPane.showMessageDialog(dialog, statusMsg, "Thông báo", JOptionPane.INFORMATION_MESSAGE);
                dialog.dispose();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog, "Lỗi khi lưu cấu hình: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        });

        btnCancel.addActionListener(e -> dialog.dispose());

        btnPanel.add(btnTest);
        btnPanel.add(btnSave);
        btnPanel.add(btnCancel);
        dialog.add(btnPanel, BorderLayout.SOUTH);

        dialog.setVisible(true);
    }

    private void shutdownServer() {
        if (refreshTimer != null) {
            refreshTimer.stop();
        }
        if (server != null) {
            server.stop();
        }
    }

    public static void main(String[] args) {
        // Set Look and Feel to System or CrossPlatform
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            MailServerGUI gui = new MailServerGUI();
            gui.setVisible(true);
        });
    }
}
