# P2P Chat — Java 21 / Swing

Ứng dụng chat LAN: server quản lý danh bạ, phòng và chỉ mục file; nội dung chat, file và media truyền trực tiếp giữa các peer.

## Chạy ứng dụng

Yêu cầu JDK 21+ và thư mục `lib/` đi kèm. Mở terminal tại thư mục dự án:

```powershell
java Build.java test
java Build.java server
java Build.java client An
```

Chạy nhanh nhiều cửa sổ để demo:

```powershell
java Build.java demo6
```

Lệnh `demo6` mở một cửa sổ server và sáu cửa sổ client. Bấm **Bật server**, sau đó **Kết nối** ở từng client. Tạo phòng ở một client và cho các client khác tham gia phòng đó. Có thể chạy các hàm `server.ServerMain.main()` và `client.ClientMain.main()` trực tiếp trong IDE với các JAR trong `lib/` trên classpath.

Server không có giao diện:

```powershell
java Build.java server --cli --host 0.0.0.0 --port 9000
```

## Địa chỉ server và quét LAN

- `0.0.0.0` ở **Địa chỉ lắng nghe** của server nghĩa là lắng nghe mọi card mạng.
- Client cùng máy dùng `127.0.0.1`. Client khác máy dùng IP của server trên mạng LAN chung.
- Client từ chối `0.0.0.0`/`::` và hướng dẫn nhập địa chỉ cụ thể.
- **Tìm Server (UDP)** quét các địa chỉ broadcast của các card mạng và loopback; lấy IP nguồn của phản hồi, kiểm tra cổng TCP có thể kết nối. Nếu tìm thấy nhiều địa chỉ, mỗi dòng hiển thị `IP:cổng — mạng/card mạng của server — tên máy`. Wi-Fi/Ethernet được ưu tiên trước VPN, mạng ảo và localhost. Chọn địa chỉ phù hợp mạng đang demo.
- Tên máy và card mạng do server gửi trong phản hồi discovery mới; vẫn hỗ trợ server cũ. Server cũ trên máy khác hiện “chưa rõ” thay vì đoán loại mạng theo IP. Tên Wi-Fi ở đây chỉ loại card mạng, không phải tên điểm phát (SSID).
- Máy có VPN/card mạng ảo có thể hiện nhiều IP. Không mặc định coi một IP bất kỳ là IP Wi-Fi/Ethernet.

## Các chức năng

| Yêu cầu | Cách thực hiện |
|---|---|
| Giao diện | Các tab chat, file, lịch sử; trạng thái kết nối và lỗi hiển thị rõ |
| Text, emoji | TCP P2P, tiếng Việt; chọn emoji chèn tại con trỏ; Backspace/Delete xóa trọn cụm emoji; Enter gửi, Shift+Enter xuống dòng |
| Multicast | UDP `230.0.0.1:9876`; bản tin có ID và trường riêng cho phòng/người gửi/nội dung; người nhận lọc phòng đã tham gia, chống lặp và lưu lịch sử |
| Broadcast | UDP cổng `9006` và gửi TCP đến các peer online; dùng cùng ID để chỉ hiển thị một lần |
| Tin nhắn thoại | WAV PCM 16 kHz, mono, 16 bit; truyền nhị phân qua TCP có xác nhận, tối đa 5 phút; lưu WAV và phát lại từ lịch sử |
| Gọi thoại/video | Mời/nhận/từ chối/hủy/kết thúc qua TCP; hai bên giữ cổng UDP thực tế trước khi gửi thông tin cho nhau; media truyền UDP trực tiếp |
| Webcam/màn hình | Webcam sau khi nhận cuộc gọi video; chia sẻ màn hình chỉ khi bấm nút; không tự bật chia sẻ màn hình khi thiếu webcam |
| Tắt mic | Ngừng phát tiếng mic, vẫn nhận âm thanh và giữ cổng UDP đã thống nhất |
| Đính kèm | Thông báo file qua TCP, tải trực tiếp P2P, tiến trình, không ghi đè file có sẵn, dọn file tải dở |
| Lịch sử | File văn bản UTF-8 `p2p_chat_<tên>.db`, thư mục WAV `.db.media`; có tìm kiếm và đọc lại khi khởi động |

Emoji dùng font có sẵn trên máy (ưu tiên Segoe UI Emoji trên Windows); Java/Swing có thể hiển thị emoji đơn sắc. Các nút chức năng dùng nhãn chữ để không phụ thuộc glyph emoji.

**Lịch sử hiện là định dạng file riêng, không phải SQLite/MySQL hay hệ quản trị CSDL.** Đây là phần lưu trữ bổ sung; đề bài ghi database là điểm cộng. File lịch sử cũ vẫn được đọc. Tin thoại cũ chỉ lưu mô tả mà không có WAV thì không thể khôi phục âm thanh từ mô tả đó.

Mỗi client có một cuộc gọi đang chờ/đang diễn ra. Gọi thoại không mở webcam. Cuộc gọi không được trả lời hết hạn sau khoảng 40 giây. Không có server trung chuyển media/NAT traversal: thiết kế này dành cho các peer truy cập trực tiếp được nhau trong LAN.

## TCP và UDP khi vấn đáp

- **TCP:** đăng ký/danh bạ/phòng, text, tin thoại WAV, thông báo và truyền file, tín hiệu điều khiển cuộc gọi. Dùng khi cần dữ liệu đầy đủ, đúng thứ tự.
- **UDP:** dò server, multicast/broadcast, PCM âm thanh và ảnh JPEG video trực tiếp. Luồng thời gian thực chấp nhận mất gói; UDP không bảo đảm giao nhận.
- Cổng server TCP mặc định `9000`; UDP discovery `9005`, broadcast `9006`, multicast `9876`. Cổng TCP P2P và cổng UDP media được hệ điều hành cấp động. Khi chạy nhiều máy, Java phải được phép giao tiếp trên mạng LAN trong tường lửa của từng máy.
- Video dùng JPEG trong một datagram; khung quá lớn được giảm kích thước. Chưa có jitter buffer, FEC hoặc codec video chuyên dụng.

## Kiểm thử

```powershell
java Build.java test
```

Lệnh này biên dịch lại và chạy:

1. `IntegrationTest`: 20 kiểm tra TCP/file gốc, gồm Unicode, file rỗng/nhị phân, tải bị ngắt, tên trùng và P2P sau khi index server tắt.
2. `RegressionTest`: 66 kiểm tra mới bằng socket thật: sáu peer, WAV 1/2/5/120/300 giây, gói thoại thiếu/quá lớn, thương lượng và đóng cuộc gọi, audio mẫu/video sinh tự động hai chiều, multicast dài, sáu bộ nhận broadcast, discovery và lịch sử.
3. `UiRegressionTest`: thao tác Swing trên EDT, kết nối hai client, lọc phòng, chống lặp TCP/UDP, gửi multicast khi server là loopback, emoji/chèn/xóa cụm, tên có dấu cách, hủy cuộc gọi và ngắt kết nối.
4. Smoke test khởi tạo cửa sổ client/server.

Ảnh giao diện phục vụ kiểm tra được tạo tại `build/qa/chat-window.png` và `build/qa/emoji-picker.png`. Kiểm thử dùng dữ liệu tạm riêng, không xóa lịch sử người dùng.

**Phạm vi đã kiểm tra:** nhiều peer trên cùng máy, socket thật, media dùng dữ liệu mẫu. Việc mic/loa/webcam hoạt động và kết nối qua các máy vật lý phải được xác nhận trên thiết bị/mạng dùng để demo.

## Demo trên 5–6 máy

1. Dùng cùng bản mã đã sửa trên tất cả các máy, cùng mạng LAN; chạy server trên một máy.
2. Quét/chọn IP server LAN và kết nối bằng tên riêng.
3. Vào chung phòng; gửi tiếng Việt và emoji; đính kèm file, tải về và so sánh nội dung.
4. Chọn **UDP Multicast**, thử tin dài; client ngoài phòng không được thấy tin.
5. Gửi **Broadcast**; mỗi client thấy một bản, không bị nhân đôi.
6. Ghi âm 5–10 giây, gửi và phát lại. Khởi động lại client để kiểm tra phát lại WAV từ lịch sử.
7. Gọi thoại/video giữa hai máy, nhận/từ chối/hủy/kết thúc; kiểm tra hai chiều, tắt/bật mic, chia sẻ rồi dừng màn hình và gọi lại.

## Mã nguồn chính

- `client/Peer.java`: TCP P2P, voice binary, signaling cuộc gọi.
- `client/CallSession.java`: giữ cổng UDP trong suốt vòng đời cuộc gọi.
- `client/AudioService.java`, `client/VideoCallWindow.java`: thiết bị và media.
- `client/ClientWindow.java`, `client/EmojiSupport.java`, `client/EmojiDialog.java`: giao diện và biểu cảm.
- `common/UdpService.java`, `common/NetworkSupport.java`: UDP và discovery.
- `common/Protocol.java`, `common/ChatDatabase.java`: framing và lưu lịch sử.
- `server/IndexServer.java`, `server/ServerWindow.java`: danh bạ/server.
