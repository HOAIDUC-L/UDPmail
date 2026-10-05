import client.MailClient;
import common.Protocol;
import org.junit.jupiter.api.*;
import server.MailServer;
import server.MailStorage;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MailSystemIntegrationTest {

    private static final int TEST_PORT = 19999;
    private static MailServer server;
    private static Path tempStorage;
    private static MailStorage mailStorage;

    @BeforeAll
    public static void setUpServer() throws Exception {
        tempStorage = Paths.get("mail_storage_test").toAbsolutePath();
        if (Files.exists(tempStorage)) {
            deleteDirectoryRecursively(tempStorage);
        }
        Files.createDirectories(tempStorage);

        mailStorage = new MailStorage(tempStorage);
        server = new MailServer("127.0.0.1", TEST_PORT, mailStorage);
        server.start();
        Thread.sleep(200); // Allow socket listener to start
    }

    @AfterAll
    public static void tearDownServer() throws Exception {
        if (server != null) {
            server.stop();
        }
        if (tempStorage != null && Files.exists(tempStorage)) {
            deleteDirectoryRecursively(tempStorage);
        }
    }

    private static void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    @Order(1)
    public void testRegisterAliceAndBobWithEmail() throws Exception {
        MailClient client = new MailClient("127.0.0.1", TEST_PORT);
        try {
            // Register Alice with email
            String respAlice = client.register("alice", "alice@udpmail.com");
            assertEquals("Register successfully", respAlice);

            // Register Bob with email
            String respBob = client.register("bob", "bob@udpmail.com");
            assertEquals("Register successfully", respBob);

            // Duplicate registration with same username should fail
            assertThrows(Exception.class, () -> client.register("alice", "another_alice@udpmail.com"));

            // Duplicate registration with same email should fail
            assertThrows(Exception.class, () -> client.register("alice2", "alice@udpmail.com"));
        } finally {
            client.close();
        }
    }

    @Test
    @Order(2)
    public void testLoginAlice() throws Exception {
        MailClient clientAlice = new MailClient("127.0.0.1", TEST_PORT);
        try {
            String listResp = clientAlice.login("alice");
            assertNotNull(listResp);
            assertTrue(listResp.startsWith(Protocol.RESP_LIST));
            // Should contain welcome email in Inbox
            assertTrue(listResp.contains("UNREAD_SYSTEM_new_email.txt"));
        } finally {
            clientAlice.close();
        }
    }

    @Test
    @Order(3)
    public void testSendMailAliceToBobByEmail() throws Exception {
        MailClient clientAlice = new MailClient("127.0.0.1", TEST_PORT);
        MailClient clientBob = new MailClient("127.0.0.1", TEST_PORT);

        try {
            clientAlice.login("alice");
            clientBob.login("bob");

            // Alice sends mail to Bob specifying recipient's email address
            String sendResp = clientAlice.sendMail("bob@udpmail.com", "Hello Bob, this is sent using recipient email via UDP!");
            assertEquals("Mail sent successfully", sendResp);

            // 1. Verify Alice's Sent folder contains the sent copy
            String aliceSent = clientAlice.listFolder(Protocol.FOLDER_SENT);
            assertTrue(aliceSent.contains("SENT_to_bob_"));

            // 2. Bob refreshes inbox and receives the email
            String bobInbox = clientBob.listFolder(Protocol.FOLDER_INBOX);
            assertTrue(bobInbox.contains("UNREAD_alice_"));

            // Extract the filename
            String aliceMailFile = null;
            String[] parts = bobInbox.split(Protocol.DELIMITER_REGEX);
            for (String part : parts) {
                if (part.contains("UNREAD_alice_")) {
                    for (String token : part.split(",")) {
                        if (token.startsWith("UNREAD_alice_")) {
                            aliceMailFile = token.trim();
                            break;
                        }
                    }
                }
            }
            assertNotNull(aliceMailFile, "Bob should have received an UNREAD email from Alice");

            // Bob reads the mail from INBOX
            String mailContent = clientBob.readMail(Protocol.FOLDER_INBOX, aliceMailFile);
            assertTrue(mailContent.contains("Hello Bob, this is sent using recipient email via UDP!"));
            assertTrue(mailContent.contains("From: alice (alice@udpmail.com)"));
            assertTrue(mailContent.contains("To: bob@udpmail.com"));

            // After reading, file should be renamed to READ_... in INBOX
            String bobInboxAfterRead = clientBob.listFolder(Protocol.FOLDER_INBOX);
            assertFalse(bobInboxAfterRead.contains(aliceMailFile));
            String readFileName = "READ_" + aliceMailFile.substring("UNREAD_".length());
            assertTrue(bobInboxAfterRead.contains(readFileName));

            // Test Long Subject and Long Content: Verify absolutely NO character loss
            String longSubject = "Tiêu đề kiểm thử rất dài: " + "A".repeat(150);
            String longBody = "Nội dung rất dài có dấu tiếng Việt: " + "Cộng hòa xã hội chủ nghĩa Việt Nam. ".repeat(40);
            String combinedContent = "Subject: " + longSubject + "\n\n" + longBody;

            String longSendResp = clientAlice.sendMail("bob@udpmail.com", combinedContent);
            assertEquals("Mail sent successfully", longSendResp);

            // Bob reads this long email
            String bobUpdatedList = clientBob.listFolder(Protocol.FOLDER_INBOX);
            String latestMail = null;
            String[] listParts = bobUpdatedList.split(Protocol.DELIMITER_REGEX);
            for (String part : listParts) {
                if (part.contains("UNREAD_alice_")) {
                    for (String token : part.split(",")) {
                        if (token.startsWith("UNREAD_alice_")) {
                            latestMail = token.trim();
                            break;
                        }
                    }
                }
            }
            assertNotNull(latestMail);
            String receivedLongMail = clientBob.readMail(Protocol.FOLDER_INBOX, latestMail);
            assertTrue(receivedLongMail.contains(longSubject), "Subject must be preserved 100%");
            assertTrue(receivedLongMail.contains(longBody), "Content must be preserved 100%");

        } finally {
            clientAlice.close();
            clientBob.close();
        }
    }

    @Test
    @Order(4)
    public void testMoveToTrashAndPermanentDelete() throws Exception {
        MailClient clientBob = new MailClient("127.0.0.1", TEST_PORT);
        try {
            clientBob.login("bob");

            // Move welcome email from INBOX to TRASH
            String delResp = clientBob.deleteMail(Protocol.FOLDER_INBOX, "UNREAD_SYSTEM_new_email.txt");
            assertEquals("Mail moved to Trash", delResp);

            // INBOX should no longer have UNREAD_SYSTEM_new_email.txt
            String bobInbox = clientBob.listFolder(Protocol.FOLDER_INBOX);
            assertFalse(bobInbox.contains("UNREAD_SYSTEM_new_email.txt"));

            // TRASH should now have UNREAD_SYSTEM_new_email.txt
            String bobTrash = clientBob.listFolder(Protocol.FOLDER_TRASH);
            assertTrue(bobTrash.contains("UNREAD_SYSTEM_new_email.txt"));

            // Now permanently delete from TRASH
            String permDelResp = clientBob.deleteMail(Protocol.FOLDER_TRASH, "UNREAD_SYSTEM_new_email.txt");
            assertEquals("Mail deleted permanently", permDelResp);

            // TRASH should now be empty
            String bobTrashAfter = clientBob.listFolder(Protocol.FOLDER_TRASH);
            assertFalse(bobTrashAfter.contains("UNREAD_SYSTEM_new_email.txt"));

        } finally {
            clientBob.close();
        }
    }

    @Test
    @Order(5)
    public void testTrashAutoPurge30Days() throws Exception {
        // Create an old file in Bob's trash simulating an email deleted 31 days ago
        Path bobTrash = mailStorage.getFolderDirectory("bob", Protocol.FOLDER_TRASH);
        Path oldTrashFile = bobTrash.resolve("OLD_expired_mail.txt");
        Files.writeString(oldTrashFile, "Simulated 31 day old trash mail");

        // Set last modified time to 31 days ago
        Instant thirtyOneDaysAgo = Instant.now().minus(31, ChronoUnit.DAYS);
        Files.setLastModifiedTime(oldTrashFile, FileTime.from(thirtyOneDaysAgo));

        // Create a recent file in Bob's trash (1 day ago)
        Path freshTrashFile = bobTrash.resolve("FRESH_recent_mail.txt");
        Files.writeString(freshTrashFile, "Recent trash mail");
        Instant oneDayAgo = Instant.now().minus(1, ChronoUnit.DAYS);
        Files.setLastModifiedTime(freshTrashFile, FileTime.from(oneDayAgo));

        // Execute purge for 30 days retention
        int purged = mailStorage.purgeExpiredTrash(Protocol.TRASH_RETENTION_MILLIS);
        assertTrue(purged >= 1, "Should purge at least 1 expired file");

        // The 31-day-old file must be deleted
        assertFalse(Files.exists(oldTrashFile), "31-day-old file should be automatically purged");

        // The 1-day-old file must still exist
        assertTrue(Files.exists(freshTrashFile), "1-day-old file should NOT be purged");

        // Cleanup
        Files.deleteIfExists(freshTrashFile);
    }

    @Test
    @Order(6)
    public void testAntiSpoofingSenderValidation() throws Exception {
        MailClient clientAlice = new MailClient("127.0.0.1", TEST_PORT);
        try {
            clientAlice.login("alice");

            // Forged packet claiming to be sent by bob
            String rawSpoofed = "SEND|bob|alice@udpmail.com|I am forging bob identity!";
            common.PacketUtils.send(new java.net.DatagramSocket(), rawSpoofed,
                    java.net.InetAddress.getByName("127.0.0.1"), TEST_PORT);

            // Alice's mailbox should not have received the spoofed email
            String aliceMailbox = clientAlice.listFolder(Protocol.FOLDER_INBOX);
            assertFalse(aliceMailbox.contains("forging bob identity"));
        } finally {
            clientAlice.close();
        }
    }

    @Test
    @Order(7)
    public void testPathTraversalProtection() throws Exception {
        MailClient clientAlice = new MailClient("127.0.0.1", TEST_PORT);
        try {
            clientAlice.login("alice");

            // Attempt to read outside mailbox
            assertThrows(Exception.class, () -> clientAlice.readMail("../accounts.txt"));
            assertThrows(Exception.class, () -> clientAlice.readMail("..\\..\\accounts.txt"));
            assertThrows(Exception.class, () -> clientAlice.readMail("..", "accounts.txt"));
        } finally {
            clientAlice.close();
        }
    }

    @Test
    @Order(8)
    public void testKickUser() throws Exception {
        MailClient clientBob = new MailClient("127.0.0.1", TEST_PORT);
        CountDownLatch kickLatch = new CountDownLatch(1);
        AtomicReference<String> kickReason = new AtomicReference<>();

        clientBob.setKickListener(reason -> {
            kickReason.set(reason);
            kickLatch.countDown();
        });

        try {
            clientBob.login("bob");
            assertTrue(server.getSessionManager().validateSender("bob",
                    java.net.InetAddress.getByName("127.0.0.1"), clientBob.getLocalPort()));

            // Server kicks bob
            boolean kicked = server.kickUser("bob");
            assertTrue(kicked);

            // Bob should receive KICK packet within 3 seconds
            boolean received = kickLatch.await(3, TimeUnit.SECONDS);
            assertTrue(received, "Bob client should receive KICK packet from server");
            assertNotNull(kickReason.get());

            // Bob session status should now be OFFLINE
            assertEquals(Protocol.SessionStatus.OFFLINE, server.getSessionManager().getSession("bob").getStatus());
        } finally {
            clientBob.close();
        }
    }

    @Test
    @Order(9)
    public void testBanAndUnbanUser() throws Exception {
        MailClient clientAlice = new MailClient("127.0.0.1", TEST_PORT);
        try {
            clientAlice.login("alice");

            // Admin bans Alice
            boolean banned = server.banUser("alice");
            assertTrue(banned);

            // Alice cannot login when banned
            Exception ex = assertThrows(Exception.class, () -> clientAlice.login("alice"));
            assertTrue(ex.getMessage().contains("Tai khoan da bi khoa"));

            // Admin unbans Alice
            boolean unbanned = server.unbanUser("alice");
            assertTrue(unbanned);

            // Alice can now login again
            String listResp = clientAlice.login("alice");
            assertNotNull(listResp);
            assertTrue(listResp.startsWith(Protocol.RESP_LIST));

        } finally {
            clientAlice.close();
        }
    }

    @Test
    @Order(10)
    public void testPasswordAuthenticationAndEmailLogin() throws Exception {
        MailClient clientCharlie = new MailClient("127.0.0.1", TEST_PORT);
        try {
            // Register Charlie with explicit custom password
            String regResp = clientCharlie.register("charlie", "charlie@udpmail.com", "MySecretPass99");
            assertEquals("Register successfully", regResp);

            // Attempt login with wrong password
            Exception ex = assertThrows(Exception.class, () -> clientCharlie.login("charlie@udpmail.com", "WrongPass123"));
            assertTrue(ex.getMessage().contains("Sai email hoac mat khau"), "Should reject incorrect password");

            // Attempt login with non-existent user
            Exception exNonExistent = assertThrows(Exception.class, () -> clientCharlie.login("unknown@udpmail.com", "any"));
            assertTrue(exNonExistent.getMessage().contains("Account does not exist"), "Should report user not found");

            // Successful login via email + correct password
            String loginResp = clientCharlie.login("charlie@udpmail.com", "MySecretPass99");
            assertNotNull(loginResp);
            assertTrue(loginResp.startsWith(Protocol.RESP_LIST));
            assertEquals("charlie", clientCharlie.getCurrentUser(), "Canonical user should be resolved from email");

            clientCharlie.logout();

            // Successful login via username + correct password
            String loginUserResp = clientCharlie.login("charlie", "MySecretPass99");
            assertNotNull(loginUserResp);
            assertEquals("charlie", clientCharlie.getCurrentUser());
            clientCharlie.logout();

        } finally {
            clientCharlie.close();
        }
    }

    @Test
    @Order(11)
    public void testSenderIpInSavedMailAndAccountSessionPersistence() throws Exception {
        MailClient clientSender = new MailClient("127.0.0.1", TEST_PORT);
        MailClient clientReceiver = new MailClient("127.0.0.1", TEST_PORT);
        try {
            clientSender.register("dan", "dan@udpmail.com", "pass1234");
            clientReceiver.register("eve", "eve@udpmail.com", "pass1234");

            clientSender.login("dan", "pass1234");
            clientReceiver.login("eve", "pass1234");

            // Send mail from dan to eve
            clientSender.sendMail("eve@udpmail.com", "Subject: IP Test\n\nTesting Sender IP presence in file.");

            // Eve checks inbox and reads mail
            String eveInbox = clientReceiver.listFolder(Protocol.FOLDER_INBOX);
            String targetMail = null;
            String[] parts = eveInbox.split(Protocol.DELIMITER_REGEX);
            for (String part : parts) {
                if (part.contains("UNREAD_dan_")) {
                    for (String token : part.split(",")) {
                        if (token.startsWith("UNREAD_dan_")) {
                            targetMail = token.trim();
                            break;
                        }
                    }
                }
            }
            assertNotNull(targetMail, "Eve should receive mail from dan");
            String content = clientReceiver.readMail(Protocol.FOLDER_INBOX, targetMail);
            assertTrue(content.contains("Sender IP: 127.0.0.1"), "Mail content should include Sender IP");

            // Logout dan and check session persistence in accounts
            clientSender.logout();
            server.getMailStorage().loadAccounts();
            server.MailStorage.AccountRecord danRecord = server.getMailStorage().getAllAccountRecords().get("dan");
            assertNotNull(danRecord);
            assertNotEquals("-", danRecord.getLoginTime(), "Login time should be recorded");
            assertNotEquals("-", danRecord.getLogoutTime(), "Logout time should be recorded");
            assertNotEquals("-", danRecord.getOnlineDuration(), "Online duration should be recorded");

            clientReceiver.logout();
        } finally {
            clientSender.close();
            clientReceiver.close();
        }
    }
}
