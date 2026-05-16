# Kiến Trúc Kỹ Thuật - AutoGreeting

Dự án này được thiết kế để hoạt động ổn định trong môi trường nhúng (Embedded) của Android Car Head Units.

## 1. Mô Hình Phát Triển (Architecture Model)
Ứng dụng sử dụng mô hình **MVVM (Model-View-ViewModel)** kết hợp với **Jetpack Compose**:
- **UI Component:** Sử dụng Compose để xây dựng giao diện khai báo (Declarative UI), giúp giảm thiểu mã nguồn XML và tăng tốc độ xử lý giao diện.
- **State Management:** `MainViewModel` và `LoginViewModel` quản lý trạng thái của ứng dụng thông qua `StateFlow` và `MutableState`, đảm bảo tính phản hồi tức thì của UI.

## 2. Hệ Thống Dịch Vụ Chạy Ngầm (Background Services)
Do tính chất của ứng dụng là tự động hóa, các Service đóng vai trò then chốt:
- **Foreground Service:** `SoundPlayerService` và `FloatingButtonService` được chạy dưới dạng Foreground Service (có thông báo) để tránh bị Android dừng khi thiếu bộ nhớ.
- **Service Coordination:** `CoreService` hoạt động như một lớp trung gian để điều phối dữ liệu giữa UI và các dịch vụ thực thi âm thanh.

## 3. Quản Lý Phụ Thuộc (Dependency Injection)
**Hilt (Dagger)** được sử dụng xuyên suốt dự án:
- Tự động hóa việc khởi tạo `AppLogger`, `UpdateManager` và các Repository.
- Giúp việc kiểm thử (Testing) và bảo trì mã nguồn trở nên dễ dàng hơn nhờ tính lỏng lẻo của các thành phần.

## 4. Xử Lý Giao Diện Lớp Phủ (System Overlay)
`FloatingButtonService` sử dụng quyền `TYPE_APPLICATION_OVERLAY`:
- **FloatingButtonPlaybackResolver:** Chịu trách nhiệm giải quyết các xung đột khi có nhiều yêu cầu điều khiển từ nút nổi.
- Tự động điều chỉnh kích thước và vị trí để không gây cản trở tầm nhìn của tài xế khi sử dụng các ứng dụng dẫn đường.

## 5. Hệ Thống Log & Report chuyên sâu
Khác với các ứng dụng thông thường, AutoGreeting tích hợp sẵn:
- **SupportLogExporter:** Trích xuất log hệ thống dưới dạng tệp tin nén.
- **DiagnosticsPrivacySanitizer:** Tự động xóa bỏ các thông tin nhạy cảm (như địa chỉ, ID thiết bị) khỏi log trước khi gửi báo cáo, bảo vệ quyền riêng tư của người dùng.
