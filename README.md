# AutoGreeting - Hệ Thống Chào Mừng Tự Động Cho Xe Hơi

AutoGreeting là ứng dụng Android chuyên dụng được thiết kế để cá nhân hóa trải nghiệm khởi động xe hơi bằng các âm thanh chào mừng tự động và giao diện điều khiển thông minh.

## Các Tính Năng Chính
| Tính Năng | Mô Tả |
| :--- | :--- |
| **Tự Khởi Động** | Tự động kích hoạt ngay khi hệ thống Android của xe hoàn tất quá trình Boot. |
| **Phát Âm Thanh** | Phát lời chào hoặc bản nhạc đã cấu hình với chất lượng cao. |
| **Nút Điều Khiển Nổi** | Cung cấp widget nổi (Overlay) để dừng/phát nhạc nhanh chóng mà không cần mở app. |
| **Quản Lý Phiên** | Hệ thống đăng nhập bảo mật để đồng bộ hóa các tùy chỉnh cá nhân. |
| **Báo Cáo Chẩn Đoán** | Tự động tạo log và báo cáo trạng thái vận hành để xử lý sự cố. |

## Công Nghệ Sử Dụng
- **Ngôn ngữ:** Kotlin 100%.
- **Kiến trúc:** MVVM với Jetpack Compose cho giao diện người dùng.
- **Xử lý ngầm:** Foreground Services kết hợp với Broadcast Receivers (Boot Completed).
- **DI:** Hilt (Dagger) cho Dependency Injection.
- **Lưu trữ:** Jetpack DataStore.

## Cấu Trúc Dự Án
- `car`: Module chính chứa toàn bộ Logic xử lý âm thanh và giao diện.
- `service`: Chứa các dịch vụ chạy ngầm duy trì hoạt động của ứng dụng.
- `ui`: Giao diện người dùng hiện đại sử dụng Compose.
- `utils`: Bộ công cụ hỗ trợ xử lý quyền và tối ưu hóa thiết bị.

## Yêu Cầu Hệ Thống
- Android 10 trở lên (Khuyến khích Android 12+ cho độ ổn định cao nhất).
- Cần cấp quyền **Hệ thống cửa sổ cảnh báo** (Overlay) và **Tự khởi chạy** (Autostart) trên các dòng máy OEM (Xiaomi, Oppo, Vivo).

---
© 2026 Hạ Ngọc Khánh. Bản quyền đã được bảo lưu.
