# Hệ Thống Mail Server Mô Phỏng Trên Giao Thức UDP (MailUDP)

Hệ thống **Mail Server & Client mô phỏng chạy trên giao thức UDP** thuần (`DatagramSocket`, `DatagramPacket`), xây dựng bằng **Java 17+**, giao diện **Java Swing**, lưu trữ **File System persistent**, không sử dụng SQL Database hay Framework nặng. Dự án được thiết kế chuẩn mực phục vụ mục đích học tập, thực hành và bảo vệ đồ án môn **Lập trình mạng**.

---

## 1. TỔNG QUAN VÀ ĐẶC TÍNH MỚI BỔ SUNG

* **Đăng ký với Username, Email và Mật khẩu**: Người dùng khi đăng ký tài khoản bắt buộc điền `username`, `email` và `password`.
* **Đăng nhập với Email (hoặc Username) và Mật khẩu**: Giao diện chia 2 tab rõ ràng ("Đăng nhập" và "Đăng ký"), người dùng đăng nhập bằng địa chỉ email và mật khẩu.
* **Gửi thư bằng Email người nhận**: Khi gửi thư, người dùng nhập địa chỉ email người nhận (ví dụ `bob@udpmail.com`) thay vì username. Server tự động tra cứu tài khoản tương ứng qua email.
* **Hộp thư đa ngăn (Multi-Folder Mailbox)**:
  * **Inbox (Hộp thư đến)**: Chứa thư nhận được, tự động chuyển `UNREAD_` sang `READ_` khi mở xem.
  * **Sent Mail (Thư đã gửi)**: Tự động lưu bản sao thư đã gửi vào thư mục `sent/` của người gửi.
  * **Trash (Thùng rác & Xóa tự động sau 30 ngày)**: Cho phép chuyển thư từ Inbox hoặc Sent vào Trash. Server tích hợp tiến trình chạy nền định kỳ tự động xóa vĩnh viễn các thư trong thùng rác có tuổi thọ vượt quá **30 ngày**.
* **Ngôn ngữ**: Java 17+ (100% Java Standard Library: `java.net.*`, `java.io.*`, `javax.swing.*`, `java.time.*`, `java.util.concurrent.*`).
* **Giao thức truyền vận**: UDP thuần (User Datagram Protocol).
* **Buffer & Giới hạn gói tin**: `MAX_PACKET_SIZE = 8192` bytes (8KB UTF-8).
* **Cơ chế phiên (Session)**: Quản lý trạng thái phi kết nối (Connectionless Session Tracking) bằng `ConcurrentHashMap`.
* **Cơ chế Heartbeat**: Client phát xung nhịp mỗi 5 giây (`HEARTBEAT|<user>`), Server phát hiện ngắt kết nối sau 15 giây timeout (`ONLINE -> OFFLINE`).
* **Bảo mật**: Chống giả mạo người gửi (Anti-spoofing qua IP & Port), chống tấn công duyệt thư mục (Path Traversal Protection với `Path.normalize()`).

---

## 2. KIẾN TRÚC HỆ THỐNG

### 2.1. Sơ đồ xử lý gói tin phía Server

```text
               +-------------------------------------------------------+
               |                  UDP Socket (:9999)                   |
               +-------------------------------------------------------+
                                          │
                                          ▼  socket.receive(packet)
                    +--------------------------------------------+
                    | UDP Receiver Thread (Tách biệt Swing EDT) |
                    +--------------------------------------------+
                                          │
                        workerPool.submit(MailRequestHandler)
                                          ▼
                         +---------------------------------+
                         |  ExecutorService (Fixed Pool)   |
                         +---------------------------------+
                                          │
                     ┌────────────────────┴────────────────────┐
                     ▼                                         ▼
            SessionManager (RAM)                       MailStorage (Disk)
      - Anti-spoofing verification               - accounts.txt (username|STATUS|email)
      - Timeout scanner (15s)                    - mail_storage/<user>/inbox/
      - Heartbeat tracking                       - mail_storage/<user>/sent/
      - User email association                   - mail_storage/<user>/trash/ (Auto-purge >30d)
                     │                                         │
                     └────────────────────┬────────────────────┘
                                          ▼
                         +---------------------------------+
                         | UDP Response / KICK Packet Out  |
                         +---------------------------------+
```

---

## 3. CẤU TRÚC DỰ ÁN

```text
MailUDP/
├── pom.xml                               # File cấu hình Maven (Java 17, Surefire, Exec plugins)
├── README.md                             # Tài liệu kỹ thuật và hướng dẫn chi tiết
├── mail_storage/                         # Thư mục lưu trữ dữ liệu bền vững
│   ├── accounts.txt                      # Danh sách tài khoản (username|STATUS|email)
│   └── <username>/                       # Hộp thư cá nhân của từng user
│       ├── inbox/                        # Ngăn thư đến
│       │   ├── UNREAD_SYSTEM_new_email.txt
│       │   ├── UNREAD_<sender>_<time>.txt
│       │   └── READ_<sender>_<time>.txt
│       ├── sent/                         # Ngăn thư đã gửi
│       │   └── SENT_to_<recipient>_<time>.txt
│       └── trash/                        # Ngăn thùng rác (xóa tự động sau 30 ngày)
│           └── <deleted_mail>.txt
│
└── src/
    ├── main/java/
    │   ├── common/
    │   │   ├── Protocol.java             # Định nghĩa lệnh, hằng số, delimiter, regex email/username
    │   │   └── PacketUtils.java          # Tiện ích đóng gói/giải mã DatagramPacket (UTF-8, 8KB)
    │   │
    │   ├── server/
    │   │   ├── ClientSession.java        # POJO lưu thông tin phiên (IP, Port, Email, Time, Status)
    │   │   ├── SessionManager.java       # Quản lý ConcurrentHashMap, quét Timeout 15s
    │   │   ├── MailStorage.java          # Multi-folder Mailbox, 30-day Purge, ghi accounts.txt
    │   │   ├── MailRequestHandler.java   # Xử lý lệnh, split limit 4, email routing, anti-spoofing
    │   │   ├── MailServer.java           # Lõi UDP Server, Socket loop, Thread pool, Trash purger
    │   │   └── MailServerGUI.java        # Swing Dashboard: JTable Session (kèm Email), Log Console
    │   │
    │   └── client/
    │       ├── HeartbeatManager.java     # Luồng gửi định kỳ HEARTBEAT mỗi 5s
    │       ├── MailClient.java           # Lõi UDP Client, correlation tương tác request-response & bắt KICK
    │       └── MailClientGUI.java        # Swing Client: 4 Tab (Inbox, Sent, Trash, Compose Mail)
    │
    └── test/java/
        └── MailSystemIntegrationTest.java# Kiểm thử tự động 9 kịch bản tích hợp mạng và bảo mật
```

---

## 4. CHI TIẾT GIAO THỨC UDP (PROTOCOL SPECIFICATION)

Tất cả thông điệp được truyền tải dưới dạng văn bản mã hóa **UTF-8**, phân tách bởi ký tự gạch đứng `|` (`Protocol.DELIMITER`).

### 4.1. Client gửi đến Server
1. **Đăng ký (kèm Email và Mật khẩu)**:
   ```text
   REGISTER|<username>|<email>|<password>
   ```
2. **Đăng nhập (bằng Email hoặc Username kèm Mật khẩu)**:
   ```text
   LOGIN|<email_or_username>|<password>
   ```
3. **Gửi thư (bằng Email người nhận)**:
   ```text
   SEND|<sender_username>|<recipient_email>|<content>
   ```
   > **Lưu ý**: Server sử dụng `split("\\|", 4)` để nội dung thư chứa ký tự pipe `|` không phá vỡ cấu trúc gói tin. Thư tự động được lưu vào `inbox` của người nhận và lưu bản sao vào `sent` của người gửi.
4. **Đọc thư theo ngăn**:
   ```text
   READ|<username>|<folder>|<filename>
   ```
   *(Trong đó `folder` là `INBOX`, `SENT`, hoặc `TRASH`).*
5. **Xem danh sách thư theo ngăn**:
   ```text
   LIST_FOLDER|<username>|<folder>
   ```
6. **Xóa thư**:
   ```text
   DELETE|<username>|<folder>|<filename>
   ```
   *(Nếu ở `INBOX` hoặc `SENT`: chuyển sang `TRASH`. Nếu ở `TRASH`: xóa vĩnh viễn khỏi đĩa).*
7. **Duy trì phiên (Heartbeat)**:
   ```text
   HEARTBEAT|<username>
   ```
8. **Đăng xuất**:
   ```text
   LOGOUT|<username>
   ```

### 4.2. Server phản hồi về Client
1. **Thành công**:
   ```text
   SUCCESS|<message>
   ```
2. **Lỗi**:
   ```text
   ERROR|<reason>
   ```
3. **Danh sách thư (kèm canonical username)**:
   ```text
   LIST|<folder>|<unread_count>|<file1>,<file2>,<file3>|<username>
   ```
4. **Nội dung thư**:
   ```text
   CONTENT|<file_content>
   ```
5. **Trục xuất người dùng (KICK notification)**:
   ```text
   KICK|<reason>
   ```

---

## 5. CƠ CHẾ LƯU TRỮ VÀ THÙNG RÁC XÓA SAU 30 NGÀY

### 5.1. Quản lý tài khoản (`mail_storage/accounts.txt`)
* File lưu trữ danh sách tài khoản theo định dạng:
  ```text
  username|STATUS|email|password
  ```
  Ví dụ:
  ```text
  alice|ACTIVE|alice@udpmail.com|123456
  bob|ACTIVE|bob@udpmail.com|123456
  ```
* Đồng bộ luồng bằng `ReentrantReadWriteLock`. Thao tác ghi được thực hiện ra file `.tmp` trước khi đổi tên nguyên tử (`ATOMIC_MOVE`).

### 5.2. Cấu trúc thư mục người dùng (`mail_storage/<username>/`)
* `inbox/`: Lưu thư đến (`UNREAD_` và `READ_`).
* `sent/`: Lưu thư đã gửi dạng `SENT_to_<recipient>_<time>.txt`.
* `trash/`: Lưu thư bị xóa.

### 5.3. Cơ chế tự động dọn Thùng rác sau 30 ngày (Trash Auto-Purge)
* Khi thư được chuyển vào `trash/`, Server cập nhật thuộc tính thời gian sửa đổi cuối (`lastModifiedTime`) của file bằng thời điểm đưa vào thùng rác.
* Trên Server, một tác vụ `trashPurgeScheduler` (`ScheduledExecutorService`) chạy định kỳ mỗi 1 giờ để quét thư mục `trash/` của tất cả người dùng:
  ```java
  long cutoffTime = System.currentTimeMillis() - Protocol.TRASH_RETENTION_MILLIS; // 30 ngày
  if (file.lastModifiedTime() < cutoffTime) {
      Files.delete(file); // Tự động xóa vĩnh viễn
  }
  ```

---

## 6. HƯỚNG DẪN BIÊN DỊCH VÀ KHỞI CHẠY

### 6.1. Yêu cầu môi trường
* **JDK**: OpenJDK hoặc Oracle JDK **17** trở lên.
* **Maven**: Apache Maven **3.8+**.

### 6.2. Biên dịch và chạy toàn bộ kiểm thử tích hợp
Tại thư mục gốc dự án (`UDPmail/`), thực hiện:
```bash
mvn clean package
```
*Kết quả:* Build thành công và vượt qua 9/9 integration test, sinh file `target/MailUDP-1.0.0.jar`.

### 6.3. Khởi chạy Server
Mở terminal 1:
```bash
mvn exec:java@server
# Hoặc: java -cp target/MailUDP-1.0.0.jar server.MailServerGUI
```

### 6.4. Khởi chạy Client 1 (Alice)
Mở terminal 2:
```bash
mvn exec:java@client
# Hoặc: java -cp target/MailUDP-1.0.0.jar client.MailClientGUI
```

### 6.5. Khởi chạy Client 2 (Bob)
Mở terminal 3:
```bash
mvn exec:java@client
# Hoặc: java -cp target/MailUDP-1.0.0.jar client.MailClientGUI
```

---

## 7. KỊCH BẢN DEMO MỞ RỘNG (12 BƯỚC NÂNG CAO)

| Bước | Thao tác Demo | Kết quả mong đợi |
| :--- | :--- | :--- |
| **Bước 1** | Khởi chạy `MailServerGUI` | Server khởi động trên cổng `9999`, bảng session có cột `Email`. |
| **Bước 2** | Mở 2 Client: Đăng ký `alice` (`alice@udpmail.com`) và `bob` (`bob@udpmail.com`) | Đăng ký thành công, Server tạo cấu trúc 3 thư mục `inbox`, `sent`, `trash` cho từng user, `accounts.txt` ghi nhận đầy đủ email. |
| **Bước 3** | Client 1: Đăng nhập `alice` | Đăng nhập thành công, vào giao diện Mailbox có 4 Tab: Inbox, Sent Mail, Trash, Compose Mail. |
| **Bước 4** | Client 2: Đăng nhập `bob` | Đăng nhập thành công. Server GUI hiển thị cả 2 user ONLINE kèm địa chỉ Email tương ứng. |
| **Bước 5** | Alice vào tab **Compose Mail**, gửi thư tới `bob@udpmail.com` | Gửi thành công bằng địa chỉ email người nhận. Tab **Sent Mail** của Alice tự động hiển thị bản sao thư vừa gửi `SENT_to_bob_....txt`. |
| **Bước 6** | Bob bấm nút Refresh tab **Inbox** | Bob thấy thư đến từ `alice (alice@udpmail.com)`. Số thư chưa đọc tăng lên. |
| **Bước 7** | Bob đọc thư | Nội dung hiển thị rõ From (username & email), To, Date, Content. File đổi từ `UNREAD_` sang `READ_`. |
| **Bước 8** | Bob chọn thư và bấm nút **Move to Trash** | Thư biến mất khỏi Inbox và xuất hiện trong tab **Trash**. |
| **Bước 9** | Bob vào tab **Trash**, bấm **Delete Permanently** | Thư được xóa hẳn khỏi hệ thống. |
| **Bước 10**| Admin trên Server Dashboard bấm **Kick User** đối với Bob | Bob nhận popup cảnh báo bị trục xuất, tự động ngắt Heartbeat và văng về màn hình Login. |
| **Bước 11**| Admin bấm **Ban User** đối với Alice rồi **Unban User** | Alice bị khóa tài khoản và không thể đăng nhập; sau khi Unban thì đăng nhập lại bình thường. |
| **Bước 12**| **Demo Heartbeat Timeout** | Tắt đột ngột cửa sổ Client đang online. Sau đúng 15 giây, Server tự động chuyển user sang `OFFLINE` và log `[TIMEOUT]`. |
