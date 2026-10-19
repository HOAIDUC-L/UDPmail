# Hệ Thống Mail Server Mô Phỏng Trên Giao Thức UDP (MailUDP)
### Tích Hợp Cổng Chuyển Tiếp Gmail SMTP Relay Gateway

Hệ thống **Mail Server & Client mô phỏng chạy trên giao thức UDP** thuần (`DatagramSocket`, `DatagramPacket`), xây dựng bằng **Java 17+**, giao diện **Java Swing**, lưu trữ **File System persistent**, không sử dụng SQL Database hay Spring framework. Dự án được thiết kế chuẩn mực phục vụ mục đích học tập, thực hành và bảo vệ đồ án môn **Lập trình mạng**.

Hệ thống tích hợp cổng **Gmail SMTP Relay Gateway** cho phép người dùng từ Client gửi email ra các địa chỉ Gmail thật trên Internet (hoặc bất kỳ nhà cung cấp dịch vụ email nào) qua kênh bảo mật **TCP/TLS (`smtp.gmail.com:587`)**, trong khi 100% giao tiếp Client &ndash; Server nội bộ vẫn hoạt động hoàn toàn trên giao thức **UDP thuần**.

---

## 1. TỔNG QUAN VÀ ĐẶC TÍNH NỔI BẬT

* **Chuyển tiếp thư ra Gmail thật (Gmail SMTP Relay Gateway)**:
  * Khi người dùng nhập địa chỉ email bên ngoài (như `@gmail.com`, `@outlook.com`,...): Server tự động lưu bản sao vào ngăn `sent/` của người gửi, đồng thời kích hoạt cổng **SMTP Relay Gateway** gửi thư thật qua **TCP/TLS (`smtp.gmail.com:587`)** sử dụng **Google App Password (Mật khẩu ứng dụng 16 ký tự)**.
  * Hộp thư người nhận trên Gmail thật sẽ nhận được email với người gửi hiển thị dạng alias: `<sender_user> via MailUDP Server <your_gmail@gmail.com>`.
  * Xử lý bất đồng bộ/đa luồng trên Server Worker Pool: Không làm nghẽn luồng nhận gói tin UDP (`socket.receive`).
* **Định tuyến thông minh kép (Smart Dual Routing)**:
  * **Thư nội bộ (`@udpmail.com` hoặc username nội bộ)**: Lưu trữ và chuyển phát tức thì vào hộp thư đến (`inbox/`) của người nhận qua UDP.
  * **Thư bên ngoài (`@gmail.com`,...)**: Lưu bản sao vào ngăn `sent/` của người gửi và chuyển tiếp ra Internet qua cổng Gmail SMTP Relay.
* **Đăng ký với Username, Email và Mật khẩu**: Bắt buộc điền `username`, `email` và `password`. Thông tin tài khoản được lưu bền vững trong `accounts.txt` và `info.txt`.
* **Đăng nhập linh hoạt**: Đăng nhập bằng Email hoặc Username kèm mật khẩu.
* **Gửi thư bằng Email người nhận**: Nhập trực tiếp email người nhận (ví dụ `bob@udpmail.com` hoặc `user@gmail.com`).
* **Hộp thư đa ngăn (Multi-Folder Mailbox)**:
  * **Inbox (Hộp thư đến)**: Chứa thư nhận được, tự động chuyển `UNREAD_` sang `READ_` khi mở xem.
  * **Sent Mail (Thư đã gửi)**: Tự động lưu bản sao thư đã gửi vào thư mục `sent/` của người gửi cho cả thư nội bộ và thư gửi ra Gmail thật.
  * **Trash (Thùng rác & Xóa tự động sau 30 ngày)**: Cho phép chuyển thư từ Inbox hoặc Sent vào Trash. Server tích hợp luồng nền định kỳ tự động xóa vĩnh viễn các thư trong thùng rác có tuổi thọ vượt quá **30 ngày**.
* **Ngôn ngữ**: Java 17+ (Java Standard Library `java.net.*`, `java.io.*`, `javax.swing.*` kết hợp thư viện chuẩn `jakarta.mail-api` 2.1.3 và `angus-mail` 2.0.3 cho cổng SMTP Relay).
* **Giao thức truyền vận**: UDP thuần (User Datagram Protocol) giữa Client và Server.
* **Buffer & Giới hạn gói tin**: `MAX_PACKET_SIZE = 8192` bytes (8KB UTF-8), phân tách giao thức an toàn bằng `split("\\|", 4)`.
* **Cơ chế phiên (Session)**: Quản lý trạng thái phi kết nối bằng `ConcurrentHashMap`.
* **Cơ chế Heartbeat**: Client phát xung nhịp mỗi 5 giây (`HEARTBEAT|<user>`), Server phát hiện ngắt kết nối sau 15 giây timeout (`ONLINE -> OFFLINE`).
* **Bảo mật**: Chống giả mạo người gửi (Anti-spoofing qua IP & Port), chống tấn công duyệt thư mục (Path Traversal Protection với `Path.normalize()`).

---

## 2. KIẾN TRÚC HỆ THỐNG

### 2.1. Sơ đồ xử lý gói tin và Cổng chuyển tiếp SMTP Relay

```text
       [UDP Mail Client 1]       [UDP Mail Client 2]
                │                         │
                │ UDP (:9999)             │ UDP (:9999)
                ▼                         ▼
   +─────────────────────────────────────────────────────────────+
   |                     UDP Socket (:9999)                      |
   +─────────────────────────────────────────────────────────────+
                                  │
                                  ▼  socket.receive(packet)
   +─────────────────────────────────────────────────────────────+
   |           UDP Receiver Thread (Tách biệt Swing EDT)         |
   +─────────────────────────────────────────────────────────────+
                                  │
                workerPool.submit(MailRequestHandler)
                                  ▼
   +─────────────────────────────────────────────────────────────+
   |             ExecutorService (Fixed Worker Pool)             |
   +─────────────────────────────────────────────────────────────+
            │                                           │
            ▼                                           ▼
+───────────────────────+                   +───────────────────────+
|  SessionManager (RAM) |                   |  MailStorage (Disk)   |
| - Anti-spoofing       |                   | - accounts.txt        |
| - Timeout 15s         |                   | - inbox/, sent/       |
| - Heartbeat tracking  |                   | - trash/ (Purge >30d) |
+───────────────────────+                   +───────────────────────+
                                                        │
                   Kiểm tra người nhận                  │
               ┌────────────────────────────────────────┘
               │
    ┌──────────┴──────────┐
    ▼                     ▼
[Người nhận Nội bộ]    [Người nhận Ngoài (@gmail.com)]
    │                     │
    │ Lưu vào inbox       │ 1. Lưu bản sao vào sent/
    │ của người nhận      │ 2. Đưa qua GmailSmtpRelay
    │                     ▼
    │          +──────────────────────────────────────+
    │          |    GmailSmtpRelay Gateway (TLS)      |
    │          |  Jakarta Mail over TCP/TLS Port 587  |
    │          +──────────────────────────────────────+
    │                                     │
    │                                     ▼  smtp.gmail.com:587
    │                          ===============================
    │                           INTERNET GMAIL INFRASTRUCTURE
    │                          ===============================
    │                                     │
    ▼                                     ▼
[Phản hồi UDP SUCCESS/ERROR]  ──>  [Hộp thư Gmail thật của người nhận]
```

---

## 3. CẤU TRÚC DỰ ÁN

```text
MailUDP/
├── pom.xml                               # Maven config (Java 17, Jakarta Mail, Angus Mail, JUnit 5)
├── README.md                             # Tài liệu kỹ thuật và hướng dẫn chi tiết
├── server_config.properties              # File cấu hình Gmail SMTP Relay Gateway
├── mail_storage/                         # Thư mục lưu trữ dữ liệu bền vững
│   ├── accounts.txt                      # Danh sách tài khoản (username|STATUS|email|password|...)
│   └── <username>/                       # Hộp thư cá nhân của từng user
│       ├── info.txt                      # Thông tin hồ sơ (username, email, password, ngày tạo)
│       ├── inbox/                        # Ngăn thư đến (UNREAD_... và READ_...)
│       ├── sent/                         # Ngăn thư đã gửi (SENT_to_...)
│       └── trash/                        # Ngăn thùng rác (xóa tự động sau 30 ngày)
│
└── src/
    ├── main/java/
    │   ├── common/
    │   │   ├── Protocol.java             # Định nghĩa mã lệnh, regex, hằng số giao thức
    │   │   └── PacketUtils.java          # Tiện ích DatagramPacket UTF-8 8KB & tự dò LAN IP
    │   │
    │   ├── server/
    │   │   ├── ClientSession.java        # POJO thông tin phiên người dùng
    │   │   ├── SessionManager.java       # Quản lý phiên RAM, quét Timeout 15s
    │   │   ├── MailStorage.java          # Multi-folder Mailbox, 30-day Purge, ghi accounts.txt & info.txt
    │   │   ├── GmailSmtpRelay.java       # Cổng SMTP Gateway kết nối smtp.gmail.com:587 (TCP/TLS)
    │   │   ├── MailRequestHandler.java   # Xử lý lệnh, định tuyến thư nội bộ vs Gmail SMTP Relay
    │   │   ├── MailServer.java           # Lõi UDP Server, Socket loop, Worker Pool, Trash Purger
    │   │   └── MailServerGUI.java        # Dashboard Server: JTable Session, Log Console, Gmail Relay Config Dialog
    │   │
    │   └── client/
    │       ├── HeartbeatManager.java     # Luồng gửi định kỳ HEARTBEAT mỗi 5s
    │       ├── MailClient.java           # Lõi UDP Client, correlation request-response, timeout 12s
    │       └── MailClientGUI.java        # Swing Client: 4 Tab (Inbox, Sent, Trash, Compose Mail)
    │
    └── test/java/
        └── MailSystemIntegrationTest.java# Kiểm thử tự động 12 kịch bản tích hợp mạng, bảo mật và Relay
```

---

## 4. CHI TIẾT GIAO THỨC UDP (PROTOCOL SPECIFICATION)

Tất cả thông điệp UDP được truyền tải dưới dạng văn bản mã hóa **UTF-8**, phân tách bởi ký tự gạch đứng `|` (`Protocol.DELIMITER`).

### 4.1. Client gửi đến Server
1. **Đăng ký (kèm Email và Mật khẩu)**:
   ```text
   REGISTER|<username>|<email>|<password>
   ```
2. **Đăng nhập (bằng Email hoặc Username kèm Mật khẩu)**:
   ```text
   LOGIN|<email_or_username>|<password>
   ```
3. **Gửi thư (bằng Email người nhận - hỗ trợ cả nội bộ lẫn Gmail)**:
   ```text
   SEND|<sender_username>|<recipient_email>|<content>
   ```
   > **Lưu ý**: Server sử dụng `split("\\|", 4)` để nội dung thư chứa ký tự pipe `|` không làm vỡ cấu trúc gói tin. Thư tự động được lưu vào `inbox` của người nhận nội bộ hoặc chuyển tiếp qua `GmailSmtpRelay` tới Gmail thật, đồng thời luôn lưu một bản sao vào ngăn `sent/` của người gửi.
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
   *(Ví dụ: `SUCCESS|Thư đã được lưu và chuyển tiếp thành công tới Gmail (user@gmail.com)!`)*
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

## 5. HƯỚNG DẪN CẤU HÌNH GMAIL SMTP RELAY (GOOGLE APP PASSWORD)

Để sử dụng tính năng chuyển tiếp thư ra Gmail thật, Server cần một **Mật khẩu ứng dụng (Google App Password)** từ tài khoản Google của bạn.

### 5.1. Các bước tạo Google App Password (16 ký tự)

1. Truy cập vào tài khoản Google: [https://myaccount.google.com/](https://myaccount.google.com/)
2. Chọn mục **Bảo mật (Security)** ở menu bên trái.
3. Trong phần *Cách bạn đăng nhập vào Google*, bật **Xác minh 2 bước (2-Step Verification)** (nếu chưa bật).
4. Tìm kiếm hoặc nhấp vào mục **Mật khẩu ứng dụng (App passwords)** (hoặc truy cập trực tiếp: [https://myaccount.google.com/apppasswords](https://myaccount.google.com/apppasswords)).
5. Nhập tên ứng dụng: `MailUDP Gateway`, sau đó nhấn **Tạo (Create)**.
6. Google sẽ hiển thị một mật khẩu 16 ký tự gồm 4 nhóm chữ cái (ví dụ: `abcd efgh ijkl mnop`). Hãy sao chép chuỗi này.

### 5.2. Thiết lập cấu hình vào Mail Server

Có 2 cách cấu hình cực kỳ tiện lợi:

#### Cách 1: Thiết lập trực tiếp qua giao diện Mail Server GUI (Khuyến nghị)
1. Khởi chạy `MailServerGUI`.
2. Bấm vào nút **"⚙ Gmail Relay Config"** trên thanh công cụ phía dưới.
3. Tích chọn ô **"Kích hoạt chuyển tiếp thư ra Gmail thật (SMTP Relay Gateway)"**.
4. Nhập địa chỉ Gmail và chuỗi App Password 16 ký tự.
5. Bấm nút **"Kiểm tra kết nối"**: Server sẽ thử nghiệm kết nối và xác thực với Google SMTP server (`smtp.gmail.com:587`). Nếu đúng, Server sẽ báo `✓ Kết nối và xác thực Gmail SMTP thành công!`.
6. Bấm **"Lưu cấu hình"**. Trạng thái trên Dashboard sẽ đổi sang `Gmail Relay: ACTIVE (<your_email>)`.

#### Cách 2: Chỉnh sửa file `server_config.properties`
Mở file `server_config.properties` ở thư mục gốc dự án:
```properties
smtp.gmail.enabled=true
smtp.gmail.host=smtp.gmail.com
smtp.gmail.port=587
smtp.gmail.username=your_account@gmail.com
smtp.gmail.password=abcd efgh ijkl mnop
```
Khi khởi động, Server sẽ tự động nạp cấu hình này.

---

## 6. HƯỚNG DẪN BIÊN DỊCH VÀ KHỞI CHẠY

### 6.1. Yêu cầu môi trường
* **JDK**: OpenJDK hoặc Oracle JDK **17** trở lên.
* **Maven**: Apache Maven **3.8+**.

### 6.2. Biên dịch và kiểm thử tự động
Tại thư mục gốc dự án (`UDPmail/`), thực hiện:
```bash
mvn clean test
```
*Kết quả:* Build thành công và vượt qua **12/12 integration tests**, kiểm tra từ bảo mật, UDP session cho đến kiểm thử định tuyến SMTP Relay.

Để đóng gói file JAR thực thi:
```bash
mvn package -DskipTests
```
Sinh file thực thi: `target/MailUDP-1.0.0.jar`.

### 6.3. Khởi chạy Server
Mở terminal 1:
```bash
mvn exec:java@server
# Hoặc: java -cp target/MailUDP-1.0.0.jar server.MailServerGUI
```
Server GUI sẽ hiển thị:
* Địa chỉ IP LAN (ví dụ: `192.168.1.15`) để các máy khác trong mạng kết nối tới.
* Cổng UDP: `9999`.
* Trạng thái Gmail Relay.
* Bảng phiên người dùng trực tuyến.

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

> **Chạy giữa 2 máy khác nhau trong mạng LAN (ví dụ Linux và Windows)**:
> 1. Trên máy chạy Client (ví dụ máy Windows): Tại màn hình đăng nhập, ô **Server Host** nhập địa chỉ **LAN IP** của máy Server (hiển thị màu đỏ trên tiêu đề Server GUI, ví dụ `192.168.1.x`).
> 2. Đảm bảo Firewall trên máy Server cho phép nhận gói tin UDP cổng `9999` (`sudo ufw allow 9999/udp`).

---

## 7. KỊCH BẢN DEMO MỞ RỘNG (14 BƯỚC HOÀN CHỈNH)

| Bước | Thao tác Demo | Kết quả mong đợi |
| :--- | :--- | :--- |
| **Bước 1** | Khởi chạy `MailServerGUI` | Server khởi động trên cổng UDP `9999`, hiển thị IP LAN, bảng session có đầy đủ các cột thông tin. |
| **Bước 2** | Cấu hình Gmail Relay trên Server GUI | Bấm nút **"⚙ Gmail Relay Config"**, điền Gmail và Google App Password, bấm **"Kiểm tra kết nối"** báo thành công, bấm **"Lưu cấu hình"**. Đèn trạng thái chuyển sang màu xanh: `Gmail Relay: ACTIVE`. |
| **Bước 3** | Mở 2 Client: Đăng ký `alice` (`alice@udpmail.com`) và `bob` (`bob@udpmail.com`) | Đăng ký thành công, Server tạo cấu trúc 3 thư mục `inbox`, `sent`, `trash` cho từng user, `accounts.txt` và `info.txt` ghi nhận đầy đủ. |
| **Bước 4** | Client 1 đăng nhập `alice`, Client 2 đăng nhập `bob` | Đăng nhập thành công. Server GUI hiển thị cả 2 user ONLINE kèm địa chỉ Email và IP/Port tương ứng. |
| **Bước 5** | Alice vào tab **Compose Mail**, gửi thư nội bộ tới `bob@udpmail.com` | Gửi thành công bằng địa chỉ email người nhận. Tab **Sent Mail** của Alice tự động hiển thị bản sao thư vừa gửi `SENT_to_bob_....txt`. |
| **Bước 6** | Bob bấm nút Refresh tab **Inbox** | Bob thấy thư đến từ `alice (alice@udpmail.com)`. Số thư chưa đọc cập nhật. |
| **Bước 7** | Bob đọc thư | Nội dung hiển thị rõ From (username & email), To, Date, Subject, Sender IP. File đổi từ `UNREAD_` sang `READ_`. |
| **Bước 8** | Bob chọn thư và bấm nút **Move to Trash** | Thư biến mất khỏi Inbox và xuất hiện trong tab **Trash**. |
| **Bước 9** | Bob vào tab **Trash**, bấm **Delete Permanently** | Thư được xóa hẳn khỏi hệ thống. |
| **Bước 10**| **DEMO GMAIL SMTP RELAY (Chuyển tiếp ra Gmail thật)** | Alice vào tab **Compose Mail**, nhập Người nhận là một địa chỉ Gmail thật (ví dụ `friend@gmail.com`), nhập Tiêu đề và Nội dung thư rồi bấm **Send**. |
| **Bước 11**| Kiểm tra kết quả gửi thư ra Gmail | Client nhận phản hồi: `Thư đã được lưu và chuyển tiếp thành công tới Gmail (friend@gmail.com)!`. Tab **Sent Mail** của Alice lưu bản sao `SENT_to_friend@gmail.com_...`. Mở ứng dụng Gmail thật trên điện thoại hoặc trình duyệt: Hộp thư đến nhận được email ngay tức thì với tên người gửi `alice via MailUDP Server`! |
| **Bước 12**| Admin trên Server Dashboard bấm **Kick User** đối với Bob | Bob nhận popup cảnh báo bị trục xuất, tự động ngắt Heartbeat và văng về màn hình Login. |
| **Bước 13**| Admin bấm **Ban User** đối với Alice rồi **Unban User** | Alice bị khóa tài khoản và không thể đăng nhập; sau khi Unban thì đăng nhập lại bình thường. |
| **Bước 14**| **Demo Heartbeat Timeout** | Tắt đột ngột cửa sổ Client đang online. Sau đúng 15 giây, Server tự động chuyển user sang `OFFLINE` và ghi nhận thời gian online vào `accounts.txt`. |
