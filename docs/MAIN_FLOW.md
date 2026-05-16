# Luồng Vận Hành Chính - AutoGreeting

Dự án AutoGreeting tập trung vào việc tự động hóa quá trình phát âm thanh chào mừng ngay khi người dùng bước vào xe và khởi động hệ thống Android.

## 1. Cơ Chế Tự Khởi Động (Auto-Boot)
Sự khởi đầu của ứng dụng dựa vào sự kiện hệ thống Android:
- **Lắng nghe:** `BootCompletedReceiver` đăng ký lắng nghe sự kiện `ACTION_BOOT_COMPLETED`.
- **Kích hoạt:** Ngay khi nhận được tín hiệu, Receiver này sẽ gửi một Intent để khởi động `CoreService`.
- **Độ tin cậy:** Hệ thống sử dụng `AutoStartHelper` để kiểm tra và nhắc nhở người dùng cấp quyền tự khởi chạy trên các dòng máy OEM.

## 2. Dịch Vụ Cốt Lõi (Core Logic)
`CoreService` đóng vai trò là "bộ não" điều phối:
- **Kiểm tra trạng thái:** Xác định xem thiết bị đã sẵn sàng phát nhạc hay chưa (ví dụ: đã đăng nhập chưa, có đang trong chế độ xe hơi không).
- **Yêu cầu phát nhạc:** Gửi lệnh đến `SoundPlayerService` thông qua `PlaybackRequest`.

## 3. Xử Lý Âm Thanh (Audio Execution)
`SoundPlayerService` là một Foreground Service chịu trách nhiệm thực thi:
- **Quản lý Media:** Sử dụng trình phát âm thanh để nạp tệp từ tài nguyên hệ thống hoặc bộ nhớ cục bộ.
- **Xử lý ưu tiên:** Đảm bảo âm thanh chào mừng được ưu tiên cao hơn các âm thanh hệ thống khác trong thời điểm khởi động.

## 4. Giao Diện Tương Tác Nổi (Floating Control)
Trong khi ứng dụng đang xử lý âm thanh, `FloatingButtonService` sẽ hiển thị một nút điều khiển nhỏ:
- **Tương tác nhanh:** Cho phép người dùng dừng hoặc phát lại âm thanh chào mừng mà không cần rời khỏi ứng dụng bản đồ.
- **Trạng thái thực:** Nút nổi sẽ thay đổi màu sắc hoặc icon dựa trên trạng thái của `SoundPlayerState`.

## 5. Hệ Thống Chẩn Đoán (Diagnostics)
Mọi bước trong luồng vận hành đều được ghi lại qua `AppLogger`. Nếu có lỗi xảy ra (ví dụ: không có âm thanh), hệ thống `DiagnosticsReportRenderer` sẽ tạo ra một bản báo cáo chi tiết để người dùng có thể gửi hỗ trợ.
